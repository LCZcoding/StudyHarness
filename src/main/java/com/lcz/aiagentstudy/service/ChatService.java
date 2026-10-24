package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.app.LoveApp;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class ChatService {
    private final LoveApp loveApp;
    private final MessageService messageService;
    private final AiCallRecorder aiCallRecorder;

//    // 组装回答流 + 累积全文 + 流完成时触发落库
//    // 本方法不能标 @Transactional：Flux 是"装配说明"，标了方法返回时事务就提交，chunk 还在路上
//    public Flux<String> stream(UUID conversationId, String message) {
//        StringBuilder full = new StringBuilder();
//
//        return loveApp.doChatByStream(message, conversationId.toString())
//                .doOnNext(full::append)
//                .publishOn(Schedulers.boundedElastic()) // Reactor 专门给阻塞调用准备的弹性线程池，默认上限是 CPU×10，且不会无限膨胀。
//                .doOnComplete(()->messageService.appendRound(conversationId, message, full.toString()));
//    }

    // 身份要透传下去：RAG 的过滤条件靠 userId，不能用 conversationId 代替
    public Flux<String> stream(UUID conversationId, String message, UUID userId,
                               boolean useRag, boolean useTools) {
        StringBuilder full = new StringBuilder();
        AtomicReference<Long> firstTokenAt = new AtomicReference<>();
        long startedAt = aiCallRecorder.start();

        return loveApp.doChatByStream(message, conversationId.toString(), userId, useRag, useTools)
                .doOnNext(chunk -> {
                    firstTokenAt.compareAndSet(null, System.nanoTime());
                    full.append(chunk);
                })
                .publishOn(Schedulers.boundedElastic())
                .doOnComplete(() -> {
                    aiCallRecorder.recordStream(userId, "CHAT", startedAt, firstTokenAt.get(), true, null);
                    messageService.appendRound(conversationId, message, full.toString());
                })
                .doOnError(e -> aiCallRecorder.recordStream(
                        userId, "CHAT", startedAt, firstTokenAt.get(), false, e.getClass().getSimpleName()));
    }
}
