package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.model.MessageRole;
import com.lcz.aiagentstudy.model.MessageRow;
import com.lcz.aiagentstudy.model.MessageType;
import com.lcz.aiagentstudy.repository.ConversationRepository;
import com.lcz.aiagentstudy.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MessageService {
    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;

    // 一个事务里按序插两条：USER 在前、ASSISTANT 在后
    // role/type 用枚举常量名逐字传（name()），与 DB CHECK 对齐
    @Transactional
    public void appendRound(UUID conversationId, String userText, String assistantText) {

        messageRepository.insert(conversationId, MessageRole.USER.name(), MessageType.QUESTION.name(), userText);
        messageRepository.insert(conversationId, MessageRole.ASSISTANT.name(), MessageType.ANSWER.name(), assistantText);
        conversationRepository.touch(conversationId);

    }
    /**
     * V2 笔记模式：同一事务里插「用户提问 + 笔记全文」两条并 touch 会话，
     * 返回 ASSISTANT/NOTE 那行的 id，供 ChatService 发 SSE note 帧（plan.md 6.1 / C2）。
     * 与 appendRound 的唯一差别是第二条用 NOTE 且要拿回 id；原链路一行不动。
     */
    @Transactional
    public long appendNoteRound(UUID conversationId, String userText, String noteText) {
        messageRepository.insert(conversationId, MessageRole.USER.name(), MessageType.QUESTION.name(), userText);
        long noteId = messageRepository.insertReturningId(
                conversationId, MessageRole.ASSISTANT.name(), MessageType.NOTE.name(), noteText);
        conversationRepository.touch(conversationId);
        return noteId;
    }

    // 透传消息历史（V2：MessageRow 已带 type，历史接口自动多返回一个字段）
    public List<MessageRow> listByConversation(UUID conversationId) {
        return messageRepository.findAllByConversation(conversationId);
    }
}
