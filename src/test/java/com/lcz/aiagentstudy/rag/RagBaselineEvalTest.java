package com.lcz.aiagentstudy.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * RAG 基线评测 —— 检索层。
 *
 * 用 rag_eval_questions.json 全量 184 题直接调 vectorStore.similaritySearch
 * （topK=5 + userId 过滤，与线上 ragSearchRequest 同口径），只看"检索能不能把
 * 包含答案的块捞回来"，不涉及大模型生成。
 *
 * 命中判两个粒度：
 * - 章级：命中块 metadata 的 knowledgeItemId == 题目的 knowledgeItemId（答案所在章节）；
 * - 节级：命中块 metadata 的 title 匹配 expectTitle。合并块标题形如「X · A + B」，
 *   按 " + " 拆开逐段匹配。
 *
 * 指标：Recall@1/@3/@5 与 MRR，按总体 / 题型 / 文档分组。
 * 首跑只记录数字、不设硬阈值；报告写 target/rag-eval/，不进 git。
 */
@SpringBootTest(properties = "app.pgvector.enabled=true")
@Tag("rag-eval")
class RagBaselineEvalTest {

    @Resource
    private VectorStore vectorStore;

    @Resource
    private JdbcTemplate jdbcTemplate;

    private static final int TOP_K = 5;
    private static final Path DATASET = Path.of("rag_eval_questions.json");
    private static final Path OUT_DIR = Path.of("target", "rag-eval");

    record EvalQuestion(String query, String answer, String expectTitle,
                        String knowledgeItemId, String type) {
    }

    record ItemInfo(UUID userId, String docTitle) {
    }

    /** 单题结果。rank 是首个命中块的名次（1 起），-1 表示 topK 内未命中。 */
    record QuestionResult(int index, String type, String docTitle, String query,
                          String expectTitle, int chapterRank, int sectionRank,
                          List<String> topTitles) {
    }

    record Metrics(int n, int hit1, int hit3, int hit5, double mrr) {
        String line() {
            if (n == 0) {
                return "0 | - | - | - | -";
            }
            return n + " | " + pct(hit1) + " | " + pct(hit3) + " | " + pct(hit5)
                    + " | " + String.format("%.3f", mrr / n);
        }

        private String pct(int hit) {
            return String.format("%.1f%% (%d/%d)", 100.0 * hit / n, hit, n);
        }
    }

    @Test
    void retrievalBaseline() throws IOException {
        // 1. 数据集
        EvalQuestion[] questions = new ObjectMapper().readValue(DATASET.toFile(), EvalQuestion[].class);
        assertTrue(questions.length > 0, "数据集为空: " + DATASET.toAbsolutePath());

        // 2. fail-fast：题目引用的章节必须存在，且向量库里真的有它们的块
        Map<String, ItemInfo> items = loadItemInfo(questions);
        checkVectorsExist(items.keySet());

        // 3. 逐题检索
        List<QuestionResult> results = new ArrayList<>(questions.length);
        for (int i = 0; i < questions.length; i++) {
            EvalQuestion q = questions[i];
            ItemInfo item = items.get(q.knowledgeItemId());

            List<Document> found = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(q.query())
                    .topK(TOP_K)
                    .filterExpression("userId == '" + item.userId() + "'")
                    .build());
            if (found == null) {
                found = List.of();
            }

            int chapterRank = -1;
            int sectionRank = -1;
            List<String> topTitles = new ArrayList<>();
            for (int r = 0; r < found.size(); r++) {
                Map<String, Object> metadata = found.get(r).getMetadata();
                String title = String.valueOf(metadata.get("title"));
                String kid = String.valueOf(metadata.get("knowledgeItemId"));
                topTitles.add(title);
                if (chapterRank < 0 && q.knowledgeItemId().equals(kid)) {
                    chapterRank = r + 1;
                }
                if (sectionRank < 0 && titleMatch(title, q.expectTitle())) {
                    sectionRank = r + 1;
                }
            }
            results.add(new QuestionResult(i, q.type(), item.docTitle(), q.query(),
                    q.expectTitle(), chapterRank, sectionRank, topTitles));

            if ((i + 1) % 20 == 0) {
                System.out.printf("检索进度 %d/%d%n", i + 1, questions.length);
            }
        }
        assertEquals(questions.length, results.size());

        // 4. 报告
        writeReport(results);
    }

    /** 章节归属与所在文档。缺任何一条都直接失败——带着错误过滤条件跑完 184 题没有意义。 */
    private Map<String, ItemInfo> loadItemInfo(EvalQuestion[] questions) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (EvalQuestion q : questions) {
            ids.add(UUID.fromString(q.knowledgeItemId()));
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                        select id, user_id, coalesce(doc_title, title) as doc_title
                          from t_knowledge_item
                         where id in (%s)
                        """.formatted(placeholders), ids.toArray());

        Map<String, ItemInfo> map = new HashMap<>();
        for (Map<String, Object> row : rows) {
            map.put(String.valueOf(row.get("id")),
                    new ItemInfo((UUID) row.get("user_id"), String.valueOf(row.get("doc_title"))));
        }
        List<String> missing = ids.stream().map(UUID::toString)
                .filter(id -> !map.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            fail("以下 knowledgeItemId 在 t_knowledge_item 中不存在: " + missing);
        }
        return map;
    }

    /** 向量库前置校验：每个章节都要有块，否则检索必然全空。 */
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
        System.out.println("向量前置校验通过：" + ids.size() + " 个章节共 "
                + counts.values().stream().mapToLong(Long::longValue).sum() + " 个块");
    }

    /**
     * 节级匹配。合并块标题形如「X · A + B」，按 " + " 拆开逐段比；
     * expectTitle 既可能是合并块全名，也可能是其中一个小节。
     */
    static boolean titleMatch(String chunkTitle, String expectTitle) {
        if (chunkTitle.equals(expectTitle)) {
            return true;
        }
        if (splitPlus(chunkTitle).contains(expectTitle)) {
            return true;
        }
        return splitPlus(expectTitle).contains(chunkTitle);
    }

    private static List<String> splitPlus(String title) {
        return Arrays.stream(title.split(" \\+ ")).map(String::strip).toList();
    }

    private void writeReport(List<QuestionResult> results) throws IOException {
        Files.createDirectories(OUT_DIR);
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        // 文件名带运行时间，重跑不会覆盖历史基线
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

        List<String> types = List.of("直问", "改述", "应用");
        List<QuestionResult> misses = results.stream()
                .filter(r -> r.chapterRank() < 0).toList();

        StringBuilder md = new StringBuilder();
        md.append("# RAG 基线评测报告 —— 检索层 ").append(now).append("\n\n");
        md.append("- 数据集：rag_eval_questions.json，共 ").append(results.size()).append(" 题\n");
        md.append("- 检索：PgVectorStore(HNSW, 余弦, 1024维), topK=").append(TOP_K)
                .append("，filter userId == 资料归属用户（与线上一致）\n");
        md.append("- 章级 = 命中块的 knowledgeItemId 等于题目章节；节级 = 块 title 匹配 expectTitle（合并块按 \" + \" 拆开匹配）\n\n");

        md.append("## 总体\n\n");
        appendMetricsTable(md, results);

        md.append("\n## 按题型\n\n");
        for (String type : types) {
            md.append("### ").append(type).append("\n\n");
            appendMetricsTable(md, results.stream().filter(r -> r.type().equals(type)).toList());
            md.append('\n');
        }

        md.append("## 按文档（章级 Recall@5）\n\n");
        md.append("| 文档 | 题数 | 章级 Recall@5 | 节级 Recall@5 |\n| --- | --- | --- | --- |\n");
        Map<String, List<QuestionResult>> byDoc = new LinkedHashMap<>();
        for (QuestionResult r : results) {
            byDoc.computeIfAbsent(r.docTitle(), k -> new ArrayList<>()).add(r);
        }
        for (Map.Entry<String, List<QuestionResult>> e : byDoc.entrySet()) {
            Metrics chapter = metrics(e.getValue(), true);
            Metrics section = metrics(e.getValue(), false);
            md.append("| ").append(e.getKey())
                    .append(" | ").append(chapter.n())
                    .append(" | ").append(String.format("%.1f%%", 100.0 * chapter.hit5() / chapter.n()))
                    .append(" | ").append(String.format("%.1f%%", 100.0 * section.hit5() / section.n()))
                    .append(" |\n");
        }

        md.append("\n## 章级 topK 未命中清单（").append(misses.size()).append(" 题）\n\n");
        if (misses.isEmpty()) {
            md.append("无。\n");
        } else {
            for (QuestionResult r : misses) {
                md.append("- [").append(r.type()).append("] ").append(r.query())
                        .append("\n  期望: ").append(r.expectTitle())
                        .append("\n  实际 top").append(TOP_K).append(": ").append(r.topTitles())
                        .append('\n');
            }
        }

        Path mdPath = OUT_DIR.resolve("retrieval-baseline-" + stamp + ".md");
        Files.writeString(mdPath, md.toString(), StandardCharsets.UTF_8);

        Path jsonPath = OUT_DIR.resolve("retrieval-details-" + stamp + ".json");
        Files.writeString(jsonPath, new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(results), StandardCharsets.UTF_8);

        // 控制台摘要
        Metrics chapter = metrics(results, true);
        Metrics section = metrics(results, false);
        System.out.printf("检索层基线：章级 R@1/R@3/R@5 = %.1f%% / %.1f%% / %.1f%%，MRR=%.3f%n",
                100.0 * chapter.hit1() / chapter.n(), 100.0 * chapter.hit3() / chapter.n(),
                100.0 * chapter.hit5() / chapter.n(), chapter.mrr() / chapter.n());
        System.out.printf("检索层基线：节级 R@1/R@3/R@5 = %.1f%% / %.1f%% / %.1f%%，MRR=%.3f%n",
                100.0 * section.hit1() / section.n(), 100.0 * section.hit3() / section.n(),
                100.0 * section.hit5() / section.n(), section.mrr() / section.n());
        System.out.println("报告: " + mdPath.toAbsolutePath());
    }

    private void appendMetricsTable(StringBuilder md, List<QuestionResult> subset) {
        Metrics chapter = metrics(subset, true);
        Metrics section = metrics(subset, false);
        md.append("| 粒度 | 题数 | Recall@1 | Recall@3 | Recall@5 | MRR |\n");
        md.append("| --- | --- | --- | --- | --- | --- |\n");
        md.append("| 章级 | ").append(chapter.line()).append(" |\n");
        md.append("| 节级 | ").append(section.line()).append(" |\n");
    }

    private Metrics metrics(List<QuestionResult> subset, boolean chapterLevel) {
        int hit1 = 0;
        int hit3 = 0;
        int hit5 = 0;
        double mrr = 0;
        for (QuestionResult r : subset) {
            int rank = chapterLevel ? r.chapterRank() : r.sectionRank();
            if (rank >= 1) {
                hit5++;
                if (rank <= 3) {
                    hit3++;
                }
                if (rank <= 1) {
                    hit1++;
                }
                mrr += 1.0 / rank;
            }
        }
        return new Metrics(subset.size(), hit1, hit3, hit5, mrr);
    }
}
