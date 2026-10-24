package com.lcz.aiagentstudy.chatmemory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * 按字符(可简单看作是token)预算裁剪历史，而不是按条数。
 * 从最新一条往前累加，超预算就停；最新那一條无论多长都保留。
 */
@Slf4j
public class BudgetedChatMemory implements ChatMemory {

    private final ChatMemory delegate;
    private final int maxChars;

    public BudgetedChatMemory(ChatMemory delegate, int maxChars) {
        this.delegate = delegate;
        this.maxChars = maxChars;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        delegate.add(conversationId, messages);
    }

    @Override
    public void clear(String conversationId) {
        delegate.clear(conversationId);
    }

    @Override
    public List<Message> get(String conversationId) {
        List<Message> history = delegate.get(conversationId);
        if (history.isEmpty()) {
            return history;
        }
        List<Message> kept = new ArrayList<>(history.size());
        int chars = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            Message message = history.get(i);
            String text = message.getText();
            int length = text == null ? 0 : text.length();
            if (!kept.isEmpty() && chars + length > maxChars) {
                break;
            }
            kept.add(0, message);
            chars += length;
        }
        if (kept.size() < history.size()) {
            log.debug("聊天历史按预算裁剪：保留 {}/{} 条，{} 字符（预算 {}）",
                    kept.size(), history.size(), chars, maxChars);
        }
        return kept;
    }
}