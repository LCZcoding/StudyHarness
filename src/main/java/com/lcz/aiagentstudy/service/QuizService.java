package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.model.KnowledgeItemRow;
import com.lcz.aiagentstudy.repository.KnowledgePointRepository;
import com.lcz.aiagentstudy.repository.QuestionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class QuizService {

    /** 评测集（test 包）也要用同一段提示词，所以不给 private。 */
    static final String SYSTEM_PROMPT = """
            你是出题官，只出题、不答题。
            规则：
            1. 必须严格基于用户给出的材料出题，不得引入材料以外的知识点。
            2. 只输出任务，绝不包含答案、提示、解释。题面只能点明考察对象，不能把结论写进题面：
               例如「为什么优化器会判断全表扫描更便宜」把答案写进了题面，
               应改为「优化器在什么情况下会放弃使用索引」。
            3. 任务分两类：
               - FACT：一个明确的回忆问题（是什么 / 为什么 / 怎么做），不要选择题。
               - EXPLAIN：讲解任务。必须给听众设定（例如"只会 Python、没听过向量检索的人"），
                 要求用户用自己的话讲清楚因果，而不是背结论。
            4. 每个任务给三个字段：
               - kind：FACT 或 EXPLAIN（大写）
               - target：这道题考的知识点，一个名词短语（例如"HNSW 的 m 参数"），不超过 40 字
               - text：题面，一句话，20 到 80 字
            5. 每个任务的 target 不能重复。
            """;

    private final ChatModel deepSeekChatModel;
    private final KnowledgeService knowledgeService;
    private final KnowledgePointRepository knowledgePointRepository;
    private final QuestionRepository questionRepository;
    private final QuizStore quizStore;

    // 出题只看材料前 12000 字：再长会顶到模型上下文上限，
    // 且「一次出 3 题」本来就覆盖不了整份讲义（写进技术债第 7 条）
    private static final int MATERIAL_LIMIT = 12_000;

    public record TaskDraft(String kind, String target, String text) {
    }

    public record QuizDraft(List<TaskDraft> tasks) {
    }

    public QuizStore.CreatedQuiz generate(UUID userId, UUID knowledgeItemId) {
        KnowledgeItemRow item = knowledgeService.requireOwned(knowledgeItemId, userId);

        Optional<QuestionRepository.RetryQuestion> retry =
                questionRepository.findRetryQuestion(userId, knowledgeItemId);

        // 有旧题就重问 1 道 + 新出 2 道；没有就新出 3 道
        int newCount = retry.isPresent() ? 2 : 3;
        boolean requireExplain = retry.map(r -> !"EXPLAIN".equals(r.kind())).orElse(true);

        List<String> verified = knowledgePointRepository.findRecentVerified(userId, 5);
        List<TaskDraft> tasks = draft(item.content(), newCount, requireExplain, verified);

        return quizStore.createSelfTest(userId, item, retry.orElse(null), tasks);
    }

    /** 复习的交错题：只出 1 个。出不来就返回 empty，不阻断这次复习。 */
    public Optional<TaskDraft> draftOne(KnowledgeItemRow item, List<String> verified) {
        try {
            return draft(item.content(), 1, false, verified).stream().findFirst();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private List<TaskDraft> draft(String content, int count, boolean requireExplain,
                                  List<String> verified) {
        ChatClient client = ChatClient.builder(deepSeekChatModel).defaultSystem(SYSTEM_PROMPT).build();
        QuizDraft draft = client.prompt()
                .user(buildUserMessage(truncate(content), count, requireExplain, verified))
                .call()
                .entity(QuizDraft.class);

        if (draft == null || draft.tasks() == null || draft.tasks().size() != count) {
            throw new IllegalStateException("模型未按要求返回 " + count + " 个任务，请重试");
        }

        List<TaskDraft> normalized = new ArrayList<>(count);
        for (TaskDraft t : draft.tasks()) {
            if (t == null || t.kind() == null || t.target() == null || t.text() == null
                    || t.target().isBlank() || t.text().isBlank()) {
                throw new IllegalStateException("模型返回的任务缺少 kind/target/text，请重试");
            }
            String kind = t.kind().trim().toUpperCase(Locale.ROOT);
            if (!"FACT".equals(kind) && !"EXPLAIN".equals(kind)) {
                throw new IllegalStateException("模型返回了未知任务类型: " + t.kind());
            }
            normalized.add(new TaskDraft(kind, t.target().trim(), t.text().trim()));
        }

        if (requireExplain && normalized.stream().noneMatch(t -> "EXPLAIN".equals(t.kind()))) {
            throw new IllegalStateException("模型没有给出 EXPLAIN 任务，请重试");
        }
        return normalized;
    }

    private String buildUserMessage(String content, int count, boolean requireExplain,
                                    List<String> verified) {
        StringBuilder sb = new StringBuilder();
        sb.append("材料如下：\n\n").append(content).append("\n\n");
        sb.append("请出 ").append(count).append(" 个任务。");
        sb.append(requireExplain ? "其中至少 1 个是 EXPLAIN。" : "类型 FACT / EXPLAIN 不限。");
        if (!verified.isEmpty()) {
            sb.append("\n\n用户已掌握这些概念：").append(String.join("、", verified))
              .append("。如果本次任务里有 EXPLAIN，请让它要求用户用已掌握的概念做类比。");
        }
        return sb.toString();
    }

    private static String truncate(String material) {
        if (material == null) {
            return "";
        }
        return material.length() <= MATERIAL_LIMIT
                ? material : material.substring(0, MATERIAL_LIMIT) + "\n……（材料已截断）";
    }
}