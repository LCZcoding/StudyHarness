package com.lcz.aiagentstudy.agent;

import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 会话记忆存储：按 conversationId(chatId) 保存完整消息上下文，
 * 供多轮对话复用，避免每次请求新建 Agent 后丢失历史。
 *
 * 当前为内存实现，重启即清空；如需持久化可替换为基于文件/DB 的实现。
 */
@Component
public class ConversationStore {

    private final Map<String, List<Message>> store = new ConcurrentHashMap<>();

    /**
     * 加载指定会话的历史消息（返回副本，避免外部修改污染存储）
     */
    public List<Message> load(String conversationId) {
        if (conversationId == null) {
            return new ArrayList<>();
        }
        List<Message> history = store.get(conversationId);
        return history == null ? new ArrayList<>() : new ArrayList<>(history);
    }

    /**
     * 保存指定会话的完整消息列表
     */
    public void save(String conversationId, List<Message> messages) {
        if (conversationId == null || messages == null) {
            return;
        }
        store.put(conversationId, new ArrayList<>(messages));
    }

    public void clear(String conversationId) {
        if (conversationId != null) {
            store.remove(conversationId);
        }
    }
}
