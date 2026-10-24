package com.lcz.aiagentstudy.service;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class HelpService {

    private static final String SYSTEM_PROMPT = """
            用户正在凭记忆回答一道题，卡住了，向你提问。
            规则：
            1. 直接回答他的问题，讲清楚即可，不要长篇大论。
            2. 不要替他把整道题答完，也不要给出这道题的完整答案。
            3. 回答完提醒一句："回到题目，用你自己的话接着讲。"
            """;

    private static final int MATERIAL_LIMIT = 8000;

    private final ChatModel deepSeekChatModel;

    // 只新增这一行；deepSeekChatModel 原本就有
    private final AiCallRecorder aiCallRecorder;

    public String answer(UUID userId, String material, String question, String userQuestion) {
        ChatClient client = ChatClient.builder(deepSeekChatModel).defaultSystem(SYSTEM_PROMPT).build();
        String userMessage = "材料：\n" + truncate(material)
                + "\n\n题目：" + question
                + "\n\n我的问题：" + userQuestion;

        long startedAt = aiCallRecorder.start();
        ChatResponse response;
        try {
            response = client.prompt().user(userMessage).call().chatResponse();
        } catch (RuntimeException e) {
            aiCallRecorder.recordFailure(userId, "HELP", startedAt, e);
            throw e;
        }
        aiCallRecorder.recordCall(userId, "HELP", startedAt, response);

        String content = response == null || response.getResult() == null
                ? null : response.getResult().getOutput().getText();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("求助回答生成失败，请重试");
        }
        return content;
    }

    private static String truncate(String material) {
        if (material == null) {
            return "";
        }
        return material.length() <= MATERIAL_LIMIT
                ? material : material.substring(0, MATERIAL_LIMIT) + "\n……（材料已截断）";
    }
}