package com.lcz.aiagentstudy.controller;

import com.lcz.aiagentstudy.constant.ConversationKeys;
import com.lcz.aiagentstudy.model.ConversationMode;
import com.lcz.aiagentstudy.service.ConversationService;
import com.lcz.aiagentstudy.web.CurrentUserResolver;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

//定201、写Location、拼响应体
@RestController
@RequestMapping("/conversations")
@RequiredArgsConstructor
public class ConversationController {
    private final ConversationService conversationService;
    private final CurrentUserResolver currentUserResolver;

    //DTO
    public record CreateRequest(@NotNull(message="mode 不能为空") ConversationMode mode){}

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateRequest createRequest, HttpSession session){
        // 通过session创建对话，返回uuid
        // to know why currentUserResolver
        UUID userId = currentUserResolver.requireUserId(session);
        UUID conversationId = conversationService.create(userId, createRequest.mode());

        return ResponseEntity.created(URI.create("/conversations/" + conversationId))//create自动包括201
                .body(Map.of(ConversationKeys.CONVERSATION_ID, conversationId));
    }
}
