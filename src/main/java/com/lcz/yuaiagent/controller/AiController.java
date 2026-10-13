package com.lcz.yuaiagent.controller;


import com.lcz.yuaiagent.agent.ConversationStore;
import com.lcz.yuaiagent.agent.LiManus;
//import com.lcz.yuaiagent.agent.YuManus;
import com.lcz.yuaiagent.app.LoveApp;
import jakarta.annotation.Resource;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;

@RestController
@RequestMapping("/ai")
public class AiController {
    @Resource
    private LoveApp loveApp;

    @Resource
    private ToolCallback[] allTools;

    @Resource
    private ChatModel deepSeekChatModel;

    @Resource
    private ConversationStore conversationStore;

    // 同步
    @GetMapping("/love_app/chat/sync")
    public String doChatWithLoveAppSync(String message, String chatId){
        return loveApp.doChat(message, chatId);
    }

//    // sse方法1
//    @GetMapping(value = "/love_app/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
//    public Flux<String> doChatWithLoveAppSSE(String message, String chatId) {
//        return loveApp.doChatByStream(message, chatId);
//    }
//    // sse方法2
//    @GetMapping(value = "/love_app/chat/sse")
//    public Flux<ServerSentEvent<String>> doChatWithLoveAppSSE(String message, String chatId) {
//        return loveApp.doChatByStream(message, chatId)
//                .map(chunk -> ServerSentEvent.<String>builder()
//                        .data(chunk)
//                        .build());
//    }

//    // sse方法3
    @GetMapping("/love_app/chat/sse")
    public SseEmitter doChatWithLoveAppSseEmitter(String message, String chatId) {
        // 创建一个超时时间较长的 SseEmitter
        SseEmitter emitter = new SseEmitter(180000L); // 3分钟超时,创建一个 SSE 响应对象。

        // 保存 Disposable（订阅句柄），用于断连时取消订阅
        Disposable subscription = loveApp.doChatByStream(message, chatId)
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

    /**
     * 流式调用 Manus 超级智能体
     *
     * @param message 用户输入
     * @param chatId  会话 ID，用于加载/保存多轮对话记忆
     */
    @GetMapping("/manus/chat")
    public SseEmitter doChatWithManus(String message, String chatId) {
        LiManus liManus = new LiManus(allTools, deepSeekChatModel);// 每次对话都要创建一个新的实例,记忆等的影响
        // 注入会话记忆能力
        liManus.setConversationId(chatId);
        liManus.setConversationStore(conversationStore);
        // 加载该会话的历史消息，实现多轮记忆
        liManus.setMessageList(conversationStore.load(chatId));
        return liManus.runStream(message);
    }


}
