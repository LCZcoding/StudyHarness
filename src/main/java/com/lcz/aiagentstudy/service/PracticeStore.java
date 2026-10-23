package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.model.ConversationMode;
import com.lcz.aiagentstudy.model.MessageRole;
import com.lcz.aiagentstudy.model.MessageType;
import com.lcz.aiagentstudy.repository.ConversationRepository;
import com.lcz.aiagentstudy.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PracticeStore {
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    public record Guide(UUID conversationId, String content) {
    }

    @Transactional
    public Guide save(UUID userId, String userMessage, String guideContent) {
        UUID conversationId = conversationRepository.create(userId, ConversationMode.PRACTICE.name());
        messageRepository.insert(conversationId, MessageRole.USER.name(), MessageType.CHAT.name(), userMessage);
        messageRepository.insert(conversationId, MessageRole.ASSISTANT.name(), MessageType.CHAT.name(), guideContent);
        conversationRepository.touch(conversationId);
        return new Guide(conversationId, guideContent);
    }
}