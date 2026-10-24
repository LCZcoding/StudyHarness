package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.model.KnowledgeItemRow;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PracticeService {

    private static final String SYSTEM_PROMPT = """
            你是实践教练。用户学完一份材料，想把它用起来。你的任务不是讲知识，而是给一个能动手做的小任务。

            输出结构（用 markdown，四段，不要多）：
            1. 任务：一个 1 到 2 小时内能做完的具体动作，必须用到材料里的至少一个知识点。
            2. 验收标准：做完怎么判断成功，写成 2 到 3 条可勾选的检查项。
            3. 常见坑：2 到 3 条，写清楚会踩到什么、为什么。
            4. 对应材料：指出材料里哪一部分是完成任务必须回看的。

            规则：
            - 不写教程全文，不贴完整代码，让用户自己动手。
            - 任务要小到今晚就能开始；不要"做一个完整系统"这种空话。
            - 用户给了目标就围绕目标设计，没给就基于材料主题设计。
            """;

    // 材料太长时只截取前 8000 字做任务设计
    private static final int MATERIAL_LIMIT = 8000;

    private final ChatModel deepSeekChatModel;
    private final KnowledgeService knowledgeService;
    private final PracticeStore practiceStore;
    private final AiCallRecorder aiCallRecorder;

    public PracticeStore.Guide guide(UUID userId, UUID knowledgeItemId, String goal) {
        KnowledgeItemRow item = knowledgeService.requireOwned(knowledgeItemId, userId);

        String userMessage = "材料标题：" + item.title()
                + "\n\n材料内容：\n" + truncate(item.content())
                + (goal == null || goal.isBlank() ? "" : "\n\n我的目标：" + goal.trim());

        ChatClient client = ChatClient.builder(deepSeekChatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .build();
//        String content = client.prompt().user(userMessage).call().content();
        long startedAt = aiCallRecorder.start();
        ChatResponse response;
        try {
            response = client.prompt().user(userMessage).call().chatResponse();
        } catch (RuntimeException e) {
            aiCallRecorder.recordFailure(userId, "PRACTICE", startedAt, e);
            throw e;
        }
        aiCallRecorder.recordCall(userId, "PRACTICE", startedAt, response);

        String content = response == null || response.getResult() == null
                ? null : response.getResult().getOutput().getText();

        if (content == null || content.isBlank()) {
            throw new IllegalStateException("生成练习指引失败，请重试");
        }
        String storedUserMessage = (goal == null || goal.isBlank())
                ? "给我一个练习任务：" + item.title()
                : "我想这样练：" + goal.trim();
        return practiceStore.save(userId, storedUserMessage, content);
    }

    private static String truncate(String material) {
        if (material == null) {
            return "";
        }
        return material.length() <= MATERIAL_LIMIT
                ? material : material.substring(0, MATERIAL_LIMIT) + "\n……（材料已截断）";
    }
}