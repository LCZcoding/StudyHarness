package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.exception.ForbiddenException;
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
    // 归属校验：会话不存在 / 不属于你，一律同一句文案（403 归并，避免存在性旁信道）
    public void assertOwned(UUID conversationId, UUID userId){
        if (!conversationRepository.belongsTo(conversationId, userId)) {
            throw new ForbiddenException("会话不存在或无权访问");
        }
    }
}
