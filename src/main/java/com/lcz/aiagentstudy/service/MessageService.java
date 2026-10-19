package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.model.MessageRole;
import com.lcz.aiagentstudy.model.MessageType;
import com.lcz.aiagentstudy.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MessageService {
    private final MessageRepository messageRepository;

    // 一个事务里按序插两条：USER 在前、ASSISTANT 在后
    // role/type 用枚举常量名逐字传（name()），与 DB CHECK 对齐
    @Transactional
    public void appendRound(UUID conversationId, String userText, String assistantText) {

        messageRepository.insert(conversationId, MessageRole.USER.name(), MessageType.QUESTION.name(), userText);
        messageRepository.insert(conversationId, MessageRole.ASSISTANT.name(), MessageType.ANSWER.name(), assistantText);

    }
}
