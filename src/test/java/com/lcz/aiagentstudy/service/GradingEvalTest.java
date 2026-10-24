package com.lcz.aiagentstudy.service;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 判分评测：每条跑 3 次，看同一答案是否稳定得到同一个 verdict，
 * 以及这个 verdict 是否与人工标注一致。
 * 跑一次 = 6 条 × 3 次 = 18 次模型调用，别在每次构建里跑。
 */
@SpringBootTest
class GradingEvalTest {

    @Resource
    private GradingService gradingService;

    private static final String MATERIAL = """
            HNSW 是分层可导航小世界图索引。每个节点在每一层最多保留 m 个邻居，
            第 0 层邻居数通常是 2m。m 越大，图的连通性越好、召回越高，
            但内存占用和构图时间也线性上升。ef_construction 控制构图时每层候选队列的长度，
            ef_search 控制查询时候选队列的长度；前者影响索引质量，后者影响查询召回与延迟。
            """;

    record Case(String target, String kind, String question, String answer, String expected) {
    }

    private static final List<Case> CASES = List.of(
            new Case("HNSW 的 m 参数", "FACT", "HNSW 的 m 参数控制什么？",
                    "控制每个节点在每层保留的最大邻居数。m 越大图越连通，召回更高，"
                            + "但内存和构图时间都会上升；第 0 层一般是 2m。", "PASS"),
            new Case("HNSW 的 m 参数", "FACT", "HNSW 的 m 参数控制什么？",
                    "控制邻居数量。", "PARTIAL"),
            new Case("HNSW 的 m 参数", "FACT", "HNSW 的 m 参数控制什么？",
                    "控制查询时候选队列的长度，调大召回更高。", "FAIL"),
            new Case("HNSW 的分层结构", "EXPLAIN",
                    "假设对方只会 Python、没听过向量检索，把 HNSW 为什么快讲给他听。",
                    "它先建好几层图，最上层很稀疏，像高速公路；越往下节点越多，像市区小路。"
                            + "查询时从最上层随便一个点出发，每次只走到当前点附近更近的邻居，"
                            + "走到走不动了就下一层继续找。因为每层只走几条边，总步数很少，"
                            + "所以比把所有向量逐个算距离快。m 决定每个点最多连几个邻居，"
                            + "连得多不容易走丢，但占内存。", "PASS"),
            new Case("HNSW 的分层结构", "EXPLAIN",
                    "假设对方只会 Python、没听过向量检索，把 HNSW 为什么快讲给他听。",
                    "它用了分层图和贪心搜索，所以快。", "PARTIAL"),
            new Case("HNSW 的分层结构", "EXPLAIN",
                    "假设对方只会 Python、没听过向量检索，把 HNSW 为什么快讲给他听。",
                    "因为 HNSW 是近似最近邻算法，复杂度比暴力搜索低。", "FAIL")
    );

    @Test
    void gradingShouldBeStableAndMatchLabels() {
        List<String> unstable = new ArrayList<>();
        int matched = 0;

        for (Case c : CASES) {
            String first = null;
            boolean same = true;
            for (int i = 0; i < 3; i++) {
                String verdict = gradingService.grade(
                        null, MATERIAL, c.target(), c.kind(), c.question(), c.answer()).verdict();
                if (first == null) {
                    first = verdict;
                } else if (!first.equals(verdict)) {
                    same = false;
                }
            }
            if (!same) {
                unstable.add(c.target() + " / " + c.expected());
            }
            if (same && first.equals(c.expected())) {
                matched++;
            }
        }

        System.out.printf("判分评测：稳定 %d/%d，与标注一致 %d/%d，不稳定=%s%n",
                CASES.size() - unstable.size(), CASES.size(), matched, CASES.size(), unstable);

        assertTrue(CASES.size() - unstable.size() >= 5, "判分稳定性不足: " + unstable);
        assertTrue(matched >= 4, "与人工标注一致率不足 4/6");
    }
}