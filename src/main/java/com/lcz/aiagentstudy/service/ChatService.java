package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.app.LoveApp;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChatService {
    private final LoveApp loveApp;
    private final MessageService messageService;

    // 组装回答流 + 累积全文 + 流完成时触发落库
    // 本方法不能标 @Transactional：Flux 是"装配说明"，标了方法返回时事务就提交，chunk 还在路上
    public Flux<String> stream(UUID conversationId, String message) {
        StringBuilder full = new StringBuilder();

        return loveApp.doChatByStream(message, conversationId.toString())
                .doOnNext(full::append)
                .publishOn(Schedulers.boundedElastic()) // Reactor 专门给阻塞调用准备的弹性线程池，默认上限是 CPU×10，且不会无限膨胀。
                .doOnComplete(()->messageService.appendRound(conversationId, message, full.toString()));
    }
}
