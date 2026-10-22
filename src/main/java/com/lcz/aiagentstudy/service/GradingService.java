package com.lcz.aiagentstudy.service;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class GradingService {

    private static final String SYSTEM_PROMPT = """
            你是判分官。用户刚凭记忆作答，你要判断他讲清楚了多少。
            只输出 JSON，字段固定为 verdict、feedback、gaps。

            判分标准：
            - PASS：核心要点齐全、因果正确、用自己的话讲明白（不是背原句）。
            - PARTIAL：方向对，但缺关键一环、某一步跳过了、或者用了没解释的术语。
            - FAIL：结论错误、答非所问、只重复题面。

            铁律：
            1. feedback 一到两句，只指出"问题出在哪"，绝对不给出正确答案、不复述材料原文。
            2. gaps 是漏洞清单：PARTIAL / FAIL 给 2 到 4 条，每条一句话。
               每条只描述「缺了哪一环」或「哪一步没说清」，可以给思考方向，
               但不得出现材料中的数值、公式、术语定义、结论，也不要举例。PASS 给空数组。
            3. 不许因为答案短就判 FAIL；短但讲对了就是 PASS。
            """;

    // 材料可能几万字，判分只看和这道题相关的一段，截断以控制成本
    private static final int MATERIAL_LIMIT = 8000;

    private final ChatModel deepSeekChatModel;

    public record Grading(String verdict, String feedback, List<String> gaps) {
    }

    private record GradingDraft(String verdict, String feedback, List<String> gaps) {
    }

    public Grading grade(String material, String target, String kind, String question,
                         String answer) {
        ChatClient client = ChatClient.builder(deepSeekChatModel).defaultSystem(SYSTEM_PROMPT).build();

        String userMessage = "材料：\n" + truncate(material)
                + "\n\n考点：" + target
                + "\n任务类型：" + kind
                + "\n题目：" + question
                + "\n\n用户的作答：\n" + answer;

        GradingDraft draft = client.prompt().user(userMessage).call().entity(GradingDraft.class);
        if (draft == null || draft.verdict() == null) {
            throw new IllegalStateException("判分失败，请重试");
        }

        String verdict = draft.verdict().trim().toUpperCase(Locale.ROOT);
        if (!"PASS".equals(verdict) && !"PARTIAL".equals(verdict) && !"FAIL".equals(verdict)) {
            throw new IllegalStateException("判分返回了未知结论: " + draft.verdict());
        }

        String feedback = draft.feedback() == null || draft.feedback().isBlank()
                ? "已收到作答。" : draft.feedback().trim();

        List<String> gaps = new ArrayList<>();
        if (draft.gaps() != null) {
            for (String gap : draft.gaps()) {
                if (gap != null && !gap.isBlank()) {
                    gaps.add(gap.trim());
                }
            }
        }
        if (!"PASS".equals(verdict) && gaps.isEmpty()) {
            // 模型偶尔只给结论不给清单。这里不造漏洞（造出来的会误导用户），
            // 只放一条兜底提示，前端仍能正常展示。
            gaps.add("没看到你的推理过程，试着把因果一步步讲出来。");
        }
        return new Grading(verdict, feedback, gaps);
    }

    private static String truncate(String material) {
        if (material == null) {
            return "";
        }
        return material.length() <= MATERIAL_LIMIT
                ? material : material.substring(0, MATERIAL_LIMIT) + "\n……（材料已截断）";
    }
}