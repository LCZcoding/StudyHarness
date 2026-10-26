package com.lcz.aiagentstudy.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcz.aiagentstudy.model.ChatEvent;
import com.lcz.aiagentstudy.service.ChatService;
import com.lcz.aiagentstudy.service.ConversationService;
import com.lcz.aiagentstudy.web.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiChatController {
    // Spring Boot 已自动配置 ObjectMapper；注入而不是 new，
    // 这样以后改 Jackson 全局配置（命名策略等）能一起生效
    private final ObjectMapper objectMapper;
    private final CurrentUserResolver currentUserResolver;
    private final ConversationService conversationService;
    private final ChatService chatService;

    // 请求体没有 userId：身份只从 session 取（①格硬约束）
    public record StreamRequest(
            @NotNull(message = "conversationId 不能为空") UUID conversationId,
            @NotBlank(message = "message 不能为空")
            @Size(max = 4000, message = "message 过长") String message,
            Boolean useRag,
            Boolean useTools) {
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
        Disposable subscription = chatService.stream(
                        conversationId, req.message(), userId,
                        Boolean.TRUE.equals(req.useRag()),
                        Boolean.TRUE.equals(req.useTools()))
                .subscribe(// 订阅 Flux 数据流
                        event -> {// 正文增量 / 来源 / 工具调用，一个事件一帧
                            try {
                                sendEvent(emitter, event);
                            } catch (IOException e) {
                                // 客户端断连：交给 emitter 收尾，onError 里会 dispose 订阅
                                emitter.completeWithError(e);
                            }
                        },
                        // 流已开始后 HTTP 状态码改不了，只能发一帧 error 让前端有话说。
                        error -> {
                            log.error("聊天流失败: conversationId={}", conversationId, error);
                            String message = (error instanceof DataAccessException)
                                    ? "回答已生成，但本轮未能保存，请稍后重试"
                                    : "回答中断，请稍后重试";
                            try {
                                sendEvent(emitter, new ChatEvent.Error(message));
                                // 先发错误帧再优雅关闭：completeWithError 在部分浏览器里
                                // 会让已写入的最后一帧读不到，前端就只剩"连接中断"
                                emitter.complete();
                            } catch (IOException e) {
                                emitter.completeWithError(error);
                            }
                        },
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

    /**
     * 发一帧具名事件。
     *
     * 为什么 data 必须是单行 JSON：SseEmitter 对含换行的字符串会把裸换行写进响应体，
     * 前端按空行切帧时会直接切坏。Jackson 会把字符串内的换行转义成 \n，所以
     * 只要不开 pretty-print（不要配 spring.jackson.serialization.indent-output），就是安全的。
     */
    private void sendEvent(SseEmitter emitter, ChatEvent event) throws IOException {
        String json = objectMapper.writeValueAsString(event);
        emitter.send(SseEmitter.event().name(event.type()).data(json));
    }
}
