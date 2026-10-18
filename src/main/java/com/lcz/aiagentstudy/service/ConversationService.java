package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.model.ConversationMode;
import com.lcz.aiagentstudy.repository.ConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConversationService {
    private final ConversationRepository conversationRepository;

    public UUID create(UUID userId, ConversationMode mode){
        // 不需要做啥条件判断吗？其他层都给拦了下来，如果在这里在做一次会导致如果设计变化，导致两边不一致
        return conversationRepository.create(userId, mode.name());
    }
//    public boolean belongsTo(UUID conversationId, UUID userId){
//        return conversationRepository.belongsTo(conversationId, userId);
//    }
}
