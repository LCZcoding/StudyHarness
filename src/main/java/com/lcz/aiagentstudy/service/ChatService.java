package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.app.LoveApp;
import com.lcz.aiagentstudy.model.ChatEvent;
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
    public Flux<ChatEvent> stream(UUID conversationId, String message, UUID userId,
                                  boolean useRag, boolean useTools) {
        StringBuilder full = new StringBuilder();
        AtomicReference<Long> firstTokenAt = new AtomicReference<>();
        long startedAt = aiCallRecorder.start();

        return loveApp.doChatByStream(message, conversationId.toString(), userId, useRag, useTools)
                // 只有 Delta 是"正文"：工具事件、来源事件不参与拼接，也绝不能落库，
                // 否则历史消息里会混进 JSON，还会被"把这轮做成题"当材料存进知识库。
                .doOnNext(event -> {
                    if (event instanceof ChatEvent.Delta delta && !delta.text().isEmpty()) {
                        // 首 token 计时只认真正的正文，工具事件的到达时间不算
                        firstTokenAt.compareAndSet(null, System.nanoTime());
                        full.append(delta.text());
                    }
                })
                .publishOn(Schedulers.boundedElastic())
                // 用 concatWith + defer 而不是原来的 doOnComplete：
                // 1) 保证"落库 → Done"的先后顺序；
                // 2) 落库抛异常会变成下游 onError（doOnComplete 里的异常可能被吞），
                //    前端才能知道"回答出来了但没存上"。
                .concatWith(Flux.defer(() -> {
                    messageService.appendRound(conversationId, message, full.toString());
                    aiCallRecorder.recordStream(userId, "CHAT", startedAt, firstTokenAt.get(), true, null);
                    return Flux.just(new ChatEvent.Done());
                }))
                .doOnError(e -> aiCallRecorder.recordStream(
                        userId, "CHAT", startedAt, firstTokenAt.get(), false, e.getClass().getSimpleName()));
    }
}
