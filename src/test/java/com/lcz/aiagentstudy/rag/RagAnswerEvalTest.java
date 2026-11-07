package com.lcz.aiagentstudy.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * RAG 基线评测 —— 答案层（慢，约 160 次模型调用）。
 *
 * 从 rag_eval_questions.json 分层抽样（直问 16 / 改述 16 / 应用 8），每题跑两遍：
 * 带 RAG（QuestionAnswerAdvisor，topK=5 + userId 过滤，与线上一致）与不带 RAG，
 * 回答模型用与线上一致的 deepseek-flash，且不挂聊天记忆（每题独立，互不污染）。
 *
 * 判定分两层：
 * - 规则层：回答非空、不是拒答话术；
 * - 语义层：qwen-plus 裁判判"回答是否覆盖参考答案要点"（换模型裁判，避免偏袒自己的输出）。
 *
 * 语义层不硬断言，数字写进 target/rag-eval/answer-baseline.md 供人工复审。
 * 关注两个数：带 RAG 的覆盖率（应显著高），以及带/不带 RAG 的配对增量。
 */
@SpringBootTest(properties = "app.pgvector.enabled=true")
@Tag("slow")
class RagAnswerEvalTest {

    @Resource
    private VectorStore vectorStore;

    @Resource
    private JdbcTemplate jdbcTemplate;

    /** 回答模型与线上一致。 */
    @Autowired
    private ChatModel deepSeekChatModel;

    /** 裁判用 qwen-plus（与回答模型不同，避免模型偏袒自己的输出）。bean 名固定是 dashScopeChatModel。 */
    @Autowired
    private ChatModel dashScopeChatModel;

    private static final int TOP_K = 5;
    private static final Path DATASET = Path.of("rag_eval_questions.json");
    private static final Path OUT_DIR = Path.of("target", "rag-eval");

    /** 分层抽样目标：直问 16 / 改述 16 / 应用 8。 */
    private static final Map<String, Integer> SAMPLE_TARGETS =
            Map.of("直问", 16, "改述", 16, "应用", 8);

    /**
     * 中性回答提示词。不用线上的"学习引导"提示词——那个提示词故意不直接给答案，
     * 会把"覆盖率"拉低成提示词风格问题；基线要量的是 RAG 本身有没有把答案带进来。
     */
    private static final String ANSWER_SYSTEM = """
            你是知识问答助手。请简洁、准确地回答用户的问题。
            如果附带了参考资料，优先依据资料作答；
            如果资料中没有与问题相关的内容，请明确说"资料中没有与这个问题相关的内容"。
            """;

    private static final String JUDGE_SYSTEM = """
            你是评测裁判。给定问题、参考答案和候选回答，判断候选回答是否覆盖参考答案的要点。
            规则：
            1. 只判要点覆盖：参考答案中的关键事实与结论在候选回答里表达出来即算覆盖；
               允许换一种说法，不要求逐字一致。
            2. 候选回答多出的、参考答案之外的内容不扣分；但与参考答案相矛盾的表述判 FAIL。
            3. 候选回答拒绝作答（说不知道、无法回答、资料里没有等）判 FAIL。
            只输出 JSON：verdict（PASS 或 FAIL）、missing（未覆盖的要点列表，PASS 时为空数组）、reason（一句话理由）。
            """;

    /** 规则层拒答识别：命中任一短语即视为拒答。 */
    private static final List<String> REFUSAL_MARKS = List.of(
            "资料中没有", "没有相关", "没有包含", "无法回答", "不知道", "无法确定", "未提供相关");

    record EvalQuestion(String query, String answer, String expectTitle,
                        String knowledgeItemId, String type) {
    }

    record JudgeVerdict(String verdict, List<String> missing, String reason) {
    }

    /** 单臂结果：answer 生成或裁判失败时 error 非空，verdict 记 ERROR，不中断整轮。 */
    record ArmResult(String answer, boolean refused, String verdict,
                     List<String> missing, String reason, String error) {
    }

    record AnswerResult(int index, String type, String query, String reference,
                        ArmResult rag, ArmResult noRag) {
    }

    @Test
    void answerBaseline() throws IOException {
        EvalQuestion[] all = new ObjectMapper().readValue(DATASET.toFile(), EvalQuestion[].class);
        assertTrue(all.length > 0, "数据集为空: " + DATASET.toAbsolutePath());

        Map<String, UUID> owners = loadOwners(all);
        checkVectorsExist(owners.keySet());

        List<EvalQuestion> sample = stratifiedSample(List.of(all));
        System.out.printf("分层抽样 %d 题（直问/改述/应用 = %s）%n", sample.size(),
                SAMPLE_TARGETS);

        ChatClient answerClient = ChatClient.builder(deepSeekChatModel)
                .defaultSystem(ANSWER_SYSTEM).build();
        ChatClient judgeClient = ChatClient.builder(dashScopeChatModel)
                .defaultSystem(JUDGE_SYSTEM).build();

        List<AnswerResult> results = new ArrayList<>(sample.size());
        for (int i = 0; i < sample.size(); i++) {
            EvalQuestion q = sample.get(i);
            UUID userId = owners.get(q.knowledgeItemId());

            ArmResult rag = runArm(answerClient, judgeClient, q, userId, true);
            ArmResult noRag = runArm(answerClient, judgeClient, q, userId, false);
            results.add(new AnswerResult(i, q.type(), q.query(), q.answer(), rag, noRag));

            System.out.printf("[%d/%d] %s | %s | RAG=%s noRAG=%s%n", i + 1, sample.size(),
                    q.type(), abbreviate(q.query(), 24), rag.verdict(), noRag.verdict());
        }

        writeReport(results, sample.size(), all.length);
    }

    /** 一题一臂：生成回答 -> 规则层 -> 裁判。任何一步失败都记进 error，不打断整轮。 */
    private ArmResult runArm(ChatClient answerClient, ChatClient judgeClient,
                             EvalQuestion q, UUID userId, boolean rag) {
        String answer;
        try {
            answer = ask(answerClient, q.query(), userId, rag);
        } catch (RuntimeException e) {
            return new ArmResult("", false, "ERROR", List.of(), "",
                    "生成回答失败: " + e.getMessage());
        }
        boolean refused = isRefusal(answer);
        try {
            JudgeVerdict v = judge(judgeClient, q.query(), q.answer(), answer);
            List<String> missing = v.missing() == null ? List.of() : v.missing();
            return new ArmResult(answer, refused, v.verdict(), missing, v.reason(), null);
        } catch (RuntimeException e) {
            return new ArmResult(answer, refused, "ERROR", List.of(), "",
                    "裁判失败: " + e.getMessage());
        }
    }

    private String ask(ChatClient client, String query, UUID userId, boolean rag) {
        ChatClient.ChatClientRequestSpec spec = client.prompt().user(query);
        if (rag) {
            spec = spec.advisors(QuestionAnswerAdvisor.builder(vectorStore)
                    .searchRequest(SearchRequest.builder()
                            .topK(TOP_K)
                            .filterExpression("userId == '" + userId + "'")
                            .build())
                    .build());
        }
        ChatResponse response = spec.call().chatResponse();
        if (response == null || response.getResult() == null
                || response.getResult().getOutput().getText() == null) {
            return "";
        }
        return response.getResult().getOutput().getText();
    }

    private JudgeVerdict judge(ChatClient judgeClient, String query,
                               String reference, String candidate) {
        String userMessage = "问题：" + query
                + "\n\n参考答案：\n" + reference
                + "\n\n候选回答：\n" + (candidate.isBlank() ? "（空）" : candidate);
        JudgeVerdict verdict = judgeClient.prompt().user(userMessage)
                .call().entity(JudgeVerdict.class);
        if (verdict == null || verdict.verdict() == null) {
            throw new IllegalStateException("裁判没有返回 verdict");
        }
        String v = verdict.verdict().trim().toUpperCase(Locale.ROOT);
        if (!"PASS".equals(v) && !"FAIL".equals(v)) {
            throw new IllegalStateException("裁判返回未知结论: " + verdict.verdict());
        }
        return new JudgeVerdict(v, verdict.missing(), verdict.reason());
    }

    private static boolean isRefusal(String answer) {
        if (answer == null || answer.isBlank()) {
            return true;
        }
        for (String mark : REFUSAL_MARKS) {
            if (answer.contains(mark)) {
                return true;
            }
        }
        return false;
    }

    /** 确定性等距抽样：同题型按数据集原序等间隔取，重跑结果一致。 */
    static List<EvalQuestion> stratifiedSample(List<EvalQuestion> all) {
        Map<String, List<EvalQuestion>> byType = new LinkedHashMap<>();
        for (EvalQuestion q : all) {
            byType.computeIfAbsent(q.type(), k -> new ArrayList<>()).add(q);
        }
        List<EvalQuestion> sample = new ArrayList<>();
        for (Map.Entry<String, List<EvalQuestion>> e : byType.entrySet()) {
            int target = SAMPLE_TARGETS.getOrDefault(e.getKey(), 0);
            List<EvalQuestion> group = e.getValue();
            if (target <= 0) {
                continue;
            }
            if (target >= group.size()) {
                sample.addAll(group);
                continue;
            }
            for (int i = 0; i < target; i++) {
                sample.add(group.get((int) ((long) i * group.size() / target)));
            }
        }
        return sample;
    }

    private Map<String, UUID> loadOwners(EvalQuestion[] questions) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (EvalQuestion q : questions) {
            ids.add(UUID.fromString(q.knowledgeItemId()));
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                        select id, user_id from t_knowledge_item where id in (%s)
                        """.formatted(placeholders), ids.toArray());
        Map<String, UUID> map = new HashMap<>();
        for (Map<String, Object> row : rows) {
            map.put(String.valueOf(row.get("id")), (UUID) row.get("user_id"));
        }
        List<String> missing = ids.stream().map(UUID::toString)
                .filter(id -> !map.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            fail("以下 knowledgeItemId 在 t_knowledge_item 中不存在: " + missing);
        }
        return map;
    }

    private void checkVectorsExist(Set<String> itemIds) {
        List<String> ids = new ArrayList<>(itemIds);
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                        select metadata->>'knowledgeItemId' as kid, count(*) as cnt
                          from vector_store
                         where metadata->>'knowledgeItemId' in (%s)
                         group by 1
                        """.formatted(placeholders), ids.toArray());
        Map<String, Long> counts = new HashMap<>();
        for (Map<String, Object> row : rows) {
            counts.put(String.valueOf(row.get("kid")), ((Number) row.get("cnt")).longValue());
        }
        List<String> missing = ids.stream()
                .filter(id -> counts.getOrDefault(id, 0L) == 0)
                .toList();
        if (!missing.isEmpty()) {
            fail("以下章节在 vector_store 中没有向量，请先重新入库: " + missing);
        }
    }

    private void writeReport(List<AnswerResult> results, int sampleSize, int totalSize)
            throws IOException {
        Files.createDirectories(OUT_DIR);
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        // 文件名带运行时间，重跑不会覆盖历史基线
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

        StringBuilder md = new StringBuilder();
        md.append("# RAG 基线评测报告 —— 答案层 ").append(now).append("\n\n");
        md.append("- 数据集：rag_eval_questions.json（共 ").append(totalSize)
                .append(" 题），分层抽样 ").append(sampleSize).append(" 题\n");
        md.append("- 回答模型：deepseek-flash（与线上一致），中性提示词，不挂聊天记忆\n");
        md.append("- RAG 配置：QuestionAnswerAdvisor, topK=").append(TOP_K)
                .append("，filter userId == 资料归属用户（与线上一致）\n");
        md.append("- 裁判：qwen-plus，判\"候选回答是否覆盖参考答案要点\"\n\n");

        md.append("## 汇总\n\n");
        md.append("| 组 | 判定数 | 覆盖通过 | 通过率 | 规则层拒答 |\n| --- | --- | --- | --- | --- |\n");
        appendArmSummary(md, "带 RAG", results, true);
        appendArmSummary(md, "不带 RAG", results, false);

        md.append("\n## 按题型（覆盖率）\n\n");
        md.append("| 题型 | 题数 | 带 RAG | 不带 RAG |\n| --- | --- | --- | --- |\n");
        for (String type : List.of("直问", "改述", "应用")) {
            List<AnswerResult> subset = results.stream()
                    .filter(r -> r.type().equals(type)).toList();
            md.append("| ").append(type).append(" | ").append(subset.size())
                    .append(" | ").append(passRate(subset, true))
                    .append(" | ").append(passRate(subset, false))
                    .append(" |\n");
        }

        // 配对增量：同一题带 RAG 从不通过变通过 / 反向
        int lifted = 0;
        int dropped = 0;
        for (AnswerResult r : results) {
            boolean ragPass = "PASS".equals(r.rag().verdict());
            boolean noPass = "PASS".equals(r.noRag().verdict());
            if (ragPass && !noPass) {
                lifted++;
            } else if (!ragPass && noPass) {
                dropped++;
            }
        }
        md.append("\n## RAG 增量（配对）\n\n")
                .append("- 带 RAG 救回（不带 RAG FAIL/ERROR -> 带 RAG PASS）：").append(lifted).append(" 题\n")
                .append("- 带 RAG 反而变差（不带 RAG PASS -> 带 RAG FAIL/ERROR）：").append(dropped).append(" 题\n\n");
        md.append("> 语义层不参与断言，以上结论需人工复审确认。\n\n");

        md.append("## 明细\n\n");
        for (AnswerResult r : results) {
            md.append("### ").append(r.index() + 1).append(". [").append(r.type()).append("] ")
                    .append(r.query()).append("\n\n");
            appendArmDetail(md, "带 RAG", r.rag());
            appendArmDetail(md, "不带 RAG", r.noRag());
        }

        Path mdPath = OUT_DIR.resolve("answer-baseline-" + stamp + ".md");
        Files.writeString(mdPath, md.toString(), StandardCharsets.UTF_8);
        Path jsonPath = OUT_DIR.resolve("answer-details-" + stamp + ".json");
        Files.writeString(jsonPath, new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(results), StandardCharsets.UTF_8);

        System.out.printf("答案层基线：带 RAG 覆盖 %s，不带 RAG 覆盖 %s，RAG 救回 %d 题、变差 %d 题%n",
                passRate(results, true), passRate(results, false), lifted, dropped);
        System.out.println("报告: " + mdPath.toAbsolutePath());
    }

    private void appendArmSummary(StringBuilder md, String name,
                                  List<AnswerResult> results, boolean ragArm) {
        int judged = 0;
        int pass = 0;
        int refused = 0;
        for (AnswerResult r : results) {
            ArmResult arm = ragArm ? r.rag() : r.noRag();
            if (arm.error() == null) {
                judged++;
                if ("PASS".equals(arm.verdict())) {
                    pass++;
                }
            }
            if (arm.refused()) {
                refused++;
            }
        }
        md.append("| ").append(name)
                .append(" | ").append(judged)
                .append(" | ").append(pass)
                .append(" | ").append(judged == 0 ? "-"
                        : String.format("%.1f%% (%d/%d)", 100.0 * pass / judged, pass, judged))
                .append(" | ").append(refused)
                .append(" |\n");
    }

    private String passRate(List<AnswerResult> subset, boolean ragArm) {
        int judged = 0;
        int pass = 0;
        for (AnswerResult r : subset) {
            ArmResult arm = ragArm ? r.rag() : r.noRag();
            if (arm.error() == null) {
                judged++;
                if ("PASS".equals(arm.verdict())) {
                    pass++;
                }
            }
        }
        return judged == 0 ? "-" : String.format("%.1f%% (%d/%d)", 100.0 * pass / judged, pass, judged);
    }

    private void appendArmDetail(StringBuilder md, String name, ArmResult arm) {
        md.append("**").append(name).append("**：").append(arm.verdict());
        if (arm.refused()) {
            md.append("（规则层判定拒答）");
        }
        if (arm.error() != null) {
            md.append("（").append(arm.error()).append("）");
        }
        md.append('\n');
        if (arm.reason() != null && !arm.reason().isBlank()) {
            md.append("裁判理由：").append(arm.reason()).append('\n');
        }
        if (!arm.missing().isEmpty()) {
            md.append("缺失要点：").append(arm.missing()).append('\n');
        }
        md.append("> ").append(abbreviate(arm.answer(), 400).replace("\n", " ")).append("\n\n");
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        String oneLine = text.strip();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }
}
