package com.lcz.aiagentstudy.rag;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 评测：用固定语料 + userId 过滤，检查 topK=5 是否命中预期文档。
 * 用完把测试向量删掉，避免污染真实资料的检索结果。
 */
@SpringBootTest(properties = "app.pgvector.enabled=true")
class RagEvalTest {

    @Resource
    private VectorStore vectorStore;

    private static final UUID EVAL_USER =
            UUID.nameUUIDFromBytes("rag-eval-user".getBytes(StandardCharsets.UTF_8));

    record Doc(String title, String text) {
    }

    record Case(String query, String expectedTitle) {
    }

    private static final List<Doc> DOCS = List.of(
            new Doc("HNSW", "HNSW 是分层可导航小世界图索引，每个节点每层保留 m 个邻居，"
                    + "查询从上层往底层贪心走。"),
            new Doc("距离度量", "余弦距离只看方向不看长度，适合文本向量；欧氏距离受向量长度影响。"),
            new Doc("文本分片", "分片时相邻片段要保留重叠字符，否则跨片段的句子会被切断，检索时丢上下文。"),
            new Doc("重排", "重排是在向量召回之后，用更贵的模型对少量候选重新打分，提高 top 结果的相关性。"),
            new Doc("IVFFlat", "IVFFlat 先把向量聚成若干簇，查询时只扫最近的几个簇，建索引比 HNSW 快但召回略低。")
    );

    private static final List<Case> CASES = List.of(
            new Case("HNSW 每个节点保留多少个邻居", "HNSW"),
            new Case("文本相似度为什么用余弦而不是欧氏距离", "距离度量"),
            new Case("切片的时候为什么要让相邻块重叠", "文本分片"),
            new Case("召回之后为什么还要 rerank", "重排"),
            new Case("pgvector 里 IVFFlat 索引适合什么场景", "IVFFlat")
    );

    @Test
    void retrievalShouldHitExpectedDocument() {
        List<String> ids = DOCS.stream()
                .map(doc -> UUID.nameUUIDFromBytes(("rag-eval-" + doc.title()).getBytes(StandardCharsets.UTF_8)))
                .map(UUID::toString)
                .toList();

        for (int i = 0; i < DOCS.size(); i++) {
            Doc doc = DOCS.get(i);
            vectorStore.add(List.of(Document.builder()
                    .id(ids.get(i))
                    .text(doc.text())
                    .metadata("userId", EVAL_USER.toString())
                    .metadata("title", doc.title())
                    .build()));
        }

        try {
            int hit = 0;
            for (Case c : CASES) {
                List<Document> found = vectorStore.similaritySearch(SearchRequest.builder()
                        .query(c.query())
                        .topK(5)
                        .filterExpression("userId == '" + EVAL_USER + "'")
                        .build());
                boolean ok = found != null && found.stream()
                        .anyMatch(doc -> c.expectedTitle().equals(doc.getMetadata().get("title")));
                if (ok) {
                    hit++;
                } else {
                    System.out.println("未命中: " + c.query());
                }
            }
            System.out.printf("RAG 评测：命中 %d/%d%n", hit, CASES.size());
            assertTrue(hit >= 4, "RAG top5 命中率不足 4/5");
        } finally {
            vectorStore.delete(ids);
        }
    }
}