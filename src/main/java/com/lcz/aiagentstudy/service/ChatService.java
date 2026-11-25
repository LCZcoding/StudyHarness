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
    // asNote=true 时走笔记链路：换客户端与系统提示词、换落库类型、多发一帧 note。
    // 为什么把开关放在最后一个参数：前 5 个是"这轮聊什么、谁能看"，第 6 个是"输出成什么形状"，
    // 后者是派生选择，放末位旧调用点（若有）只需补一个 false。
    public Flux<ChatEvent> stream(UUID conversationId, String message, UUID userId,
                                  boolean useRag, boolean useTools, boolean asNote) {
        StringBuilder full = new StringBuilder();
        AtomicReference<Long> firstTokenAt = new AtomicReference<>();
        long startedAt = aiCallRecorder.start();
        // 观测口径：笔记生成单独记账，否则"聊天变慢了"会把 1000+ 字的成篇输出算进去，看不出真相
        String scene = asNote ? "NOTE_GENERATE" : "CHAT";

        // 两条链路的差异只在"事件从哪来"：笔记链路把纯文本包成 Delta，
        // 之后就并进同一条管道 —— 累积全文、首 token 计时、落库时机都不必重写第二遍。
        // 反过来若给笔记单独写一套 concatWith，两份落库逻辑迟早出现一处漏改。
        Flux<ChatEvent> events = asNote
                ? loveApp.doNoteByStream(message, conversationId.toString()).map(ChatEvent::delta)
                : loveApp.doChatByStream(message, conversationId.toString(), userId, useRag, useTools);

        return events
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
                    if (asNote) {
                        // 笔记要先拿到行号才能告诉前端"存到哪了"。
                        // 顺序硬约束：先落库、再发 Note、最后 Done —— id 拿不到就等于没存上，
                        // 绝不能先报成功再补一个可能失败的写库。
                        long noteId = messageService.appendNoteRound(conversationId, message, full.toString());
                        aiCallRecorder.recordStream(userId, scene, startedAt, firstTokenAt.get(), true, null);
                        return Flux.just(new ChatEvent.Note(noteId), new ChatEvent.Done());
                    }
                    messageService.appendRound(conversationId, message, full.toString());
                    aiCallRecorder.recordStream(userId, scene, startedAt, firstTokenAt.get(), true, null);
                    return Flux.just(new ChatEvent.Done());
                }))
                .doOnError(e -> aiCallRecorder.recordStream(
                        userId, scene, startedAt, firstTokenAt.get(), false, e.getClass().getSimpleName()));
    }
}
