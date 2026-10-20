package com.lcz.aiagentstudy.controller;

import com.lcz.aiagentstudy.service.ChatService;
import com.lcz.aiagentstudy.service.ConversationService;
import com.lcz.aiagentstudy.web.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiChatController {
    private final CurrentUserResolver currentUserResolver;
    private final ConversationService conversationService;
    private final ChatService chatService;

    // 请求体没有 userId：身份只从 session 取（①格硬约束）
    public record StreamRequest(
            @NotNull(message = "conversationId 不能为空") UUID conversationId,
            @NotBlank(message = "message 不能为空")
            @Size(max = 4000, message = "message 过长") String message) {
    }

    // 身份校验(401) → 归属校验(403) → 开流
    // emitter 骨架照抄 AiController.doChatWithLoveAppSseEmitter，别重新发明
    @PostMapping("/chat/stream")
    public SseEmitter stream(@Valid @RequestBody StreamRequest req, HttpServletRequest request) {
        // 拿到userId，同时currentUserResolver可以校验身份
        UUID userId = currentUserResolver.requireUserId(request);
        // 拿到conversationId
        UUID conversationId = req.conversationId();
        // 归属校验
        conversationService.assertOwned(conversationId, userId);

        // 创建一个超时时间较长的 SseEmitter
        SseEmitter emitter = new SseEmitter(180000L); // 3分钟超时,创建一个 SSE 响应对象。

        // 保存 Disposable（订阅句柄），用于断连时取消订阅
        Disposable subscription = chatService.stream(conversationId, req.message())
                .subscribe(// 订阅 Flux 数据流
                        chunk -> {// 大模型每生成一个字/词，就会触发一次。
                            try {
                                emitter.send(chunk); // 发送消息到客户端，客户端会自动解析并显示
                            } catch (IOException e) {
                                emitter.completeWithError(e); // 客户端断连会抛IOException
                            }
                        },
                        // 处理错误
                        emitter::completeWithError,
                        // 处理完成,关闭订阅
                        emitter::complete// Flux 数据流完成后，通知emitter关闭订阅
                );

        // 当客户端断开、超时或发生错误时，取消对 Flux 的订阅，停止大模型调用！
        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(subscription::dispose);
        emitter.onError((e) -> subscription.dispose());

        // 返回emitter
        return emitter;

    }
}
