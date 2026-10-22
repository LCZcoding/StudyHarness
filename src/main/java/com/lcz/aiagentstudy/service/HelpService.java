package com.lcz.aiagentstudy.service;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

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

    public String answer(String material, String question, String userQuestion) {
        ChatClient client = ChatClient.builder(deepSeekChatModel).defaultSystem(SYSTEM_PROMPT).build();
        String userMessage = "材料：\n" + truncate(material)
                + "\n\n题目：" + question
                + "\n\n我的问题：" + userQuestion;
        String content = client.prompt().user(userMessage).call().content();
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