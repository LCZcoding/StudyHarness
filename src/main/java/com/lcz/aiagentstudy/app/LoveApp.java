package com.lcz.aiagentstudy.app;

import com.lcz.aiagentstudy.advisor.MyLoggerAdvisor;
import com.lcz.aiagentstudy.advisor.ReReadingAdvisor;
import com.lcz.aiagentstudy.chatmemory.BudgetedChatMemory;
import com.lcz.aiagentstudy.chatmemory.FileBasedChatMemory;
import com.lcz.aiagentstudy.model.ChatEvent;
import com.lcz.aiagentstudy.tools.ToolTraceCallback;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 恋爱心理专家对话应用。
 *
 * <p>封装了与 AI 大模型交互的完整流程，包括系统提示词、会话记忆和增强器链。
 * 通过 {@link #doChat(String, String)} 方法发送用户消息并获取 AI 回复。
 */
@Component
@Slf4j
public class LoveApp {

    /**
     * 对话客户端，封装了系统提示词、会话记忆以及底层大模型调用。
     */
    private final ChatClient chatClient;

    /**
     * 系统提示词：定义 AI 的角色身份、提问策略及引导方式。
     */
    private static final String SYSTEM_PROMPT = "# 角色\n" +
            "你是学习引导型 AI 助手，不是答案机器。目标是让用户自己得出答案、定位问题、完成修复。\n" +
            "\n" +
            "# 总原则\n" +
            "1. 基础概念可直接回答，但要多角度解释：定义、原理、用途、例子、误区、相关概念，并给学习路径、官方文档查找方法和搜索关键词。\n" +
            "2. 工程/报错/代码/配置问题，默认不直接给修复答案。先引导用户补信息、读报错、提假设、查官方文档、做最小验证。\n" +
            "3. 每次只推进 1-2 步，多提问，少下结论。用户卡住时按：方向→关键词→伪代码→最小示例→完整答案 分级给。\n" +
            "4. 解决后必须复盘：根本原因、验证方法、下次如何定位。\n" +
            "5. 不伪造文档、链接、版本。不确定就说不确定，并教用户如何查证。\n" +
            "\n" +
            "# 工程排错引导\n" +
            "先让用户提供：完整报错、堆栈、环境版本、复现步骤、最近改动、已试方法。\n" +
            "然后问：第一行关键报错是什么？指向哪里？你猜最可能原因是什么？\n" +
            "再引导：用错误码/异常名/函数名去官方文档的 Troubleshooting、FAQ、API Reference 搜。找到后一起读。\n" +
            "最后：做最小复现、二分定位、打印变量、看日志验证。解决后复盘。\n" +
            "\n" +
            "# 基础概念引导\n" +
            "直接答定义，再从原理、用途、例子、误区、相关概念展开。给官方文档入口和搜索关键词，例如：`技术名 + 版本 + official docs + 关键词`。最后让用户复述，或选一个方向深入。\n" +
            "\n" +
            "# 风格\n" +
            "温和、简洁、具体。多用“我们先”“你可以先看”“你猜”“找到后贴出来”。少用“答案是”“直接改成”。每次结尾留一个下一步行动或问题。";

//    /**
//     * 构造方法，初始化对话客户端与会话记忆。
//     *
//     * @param deepSeekChatModel 底层大语言模型（DeepSeek）
//     */
//    public LoveApp(ChatModel deepSeekChatModel) {
//        //  初始化基于文件的会话记忆
//        String fileDir = System.getProperty("user.dir") + "/tmp/chat-memory";
//
//
//        // 创建基于文件的会话记忆，最多保留最近 10 条消息
//        ChatMemory chatMemory = MessageWindowChatMemory
//                .builder()                                          // 创建 MessageWindowChatMemory 构建器
//                .chatMemoryRepository(new FileBasedChatMemory(fileDir))  // 设置file存储仓库
//                .maxMessages(10)                                    // 设置最大保留消息数
//                .build();
////        // 创建基于内存的会话记忆，最多保留最近 10 条消息
////        ChatMemory chatMemory = MessageWindowChatMemory
////                .builder()                                          // 创建 MessageWindowChatMemory 构建器
////                .chatMemoryRepository(new InMemoryChatMemoryRepository())  // 设置内存存储仓库
////                .maxMessages(10)                                    // 设置最大保留消息数
////                .build();                                           // 构建 ChatMemory 实例
//
//        // 创建对话客户端
//        chatClient = ChatClient.builder(deepSeekChatModel)         // 指定底层大模型
//                .defaultSystem(SYSTEM_PROMPT)                       // 设置系统提示词，定义 AI 角色和行为
//                .defaultAdvisors(
//                        MessageChatMemoryAdvisor
//                                .builder(chatMemory)                    // 传入记忆对象，自动附加历史消息
//                                .build(),
//                        new MyLoggerAdvisor()
////                        ,new ReReadingAdvisor()
//                )
//                .build();                                           // 构建 ChatClient 实例
//    }

public LoveApp(ChatModel deepSeekChatModel,
               @Value("${app.ai.context.max-history-chars:8000}") int maxHistoryChars) {
    String fileDir = System.getProperty("user.dir") + "/tmp/chat-memory";

    ChatMemory base = MessageWindowChatMemory
            .builder()
            .chatMemoryRepository(new FileBasedChatMemory(fileDir))
            .maxMessages(30)
            .build();
    ChatMemory chatMemory = new BudgetedChatMemory(base, maxHistoryChars);

    chatClient = ChatClient.builder(deepSeekChatModel)
            .defaultSystem(SYSTEM_PROMPT)
            .defaultAdvisors(
                    MessageChatMemoryAdvisor.builder(chatMemory).build(),
                    new MyLoggerAdvisor())
            .build();
}

    /**
     * 执行聊天对话。
     *
     * @param message 用户输入的消息内容
     * @param chatId  聊天会话的唯一标识符，用于关联历史消息
     * @return AI 助手的回复内容；如果响应为空则返回 {@code null}
     */
    public String doChat(String message, String chatId) {
        ChatResponse chatResponse = chatClient.prompt()             // 开始构建一次对话请求
                .user(message)                                      // 设置用户发送的消息内容
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, chatId))  // 传入会话 ID，关联历史记忆
                .call()                                             // 执行同步调用（非流式）
                .chatResponse();                                    // 获取完整的 ChatResponse 对象

        String content = null;
        if (chatResponse != null) {
            content = chatResponse.getResult().getOutput().getText();  // 获取第一个结果中 AI 回复的文本
        }

        return content;
    }

    record LoveReport(String title, List<String> suggestions) {

    }

//     /**
//      * AI恋爱报告，结构化输出
//      *
//      * @param message 用户输入的消息内容
//      * @param chatId  聊天会话的唯一标识符，用于关联历史消息
//      * @return AI 助手的回复内容；如果响应为空则返回 {@code null}
//      */
//     public LoveReport doChatWithReport(String message, String chatId) {
//         LoveReport loveReport = chatClient.prompt()             // 开始构建一次对话请求
//                 .system(SYSTEM_PROMPT + "请结构化输出恋爱报告，标题为用户名，内容为建议列表")
//                 .user(message)                                      // 设置用户发送的消息内容
//                 .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, chatId))  // 传入会话 ID，关联历史记忆
//                 .call()                                             // 执行同步调用（非流式）
//                 .entity(LoveReport.class);                          //  解析响应为 LoveReport 对象
//         //log.info("loveReport: {}", loveReport);
//         return loveReport;
//     }

    @Autowired(required = false)//todo  后续再配置
    private VectorStore loveAppVectorStore;

    public String doChatWithRag(String message, String chatId) {
        ChatResponse chatResponse = chatClient.prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .advisors(QuestionAnswerAdvisor.builder(loveAppVectorStore).build())
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        return content;
    }

    @Resource
    private ToolCallback[] allTools;

    public String doChatWithTools(String message, String chatId) {
        ChatResponse chatResponse = chatClient.prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                //.advisors(new MyLoggerAdvisor())
                //.tools(allTools).tools() 的语义是：接收"原始工具对象"（带 @Tool 注解的类），然后内部再次扫描 @Tool 方法。
                // 而allTools内都是自动包装好的对象，已经没有了@Tool 注解
                .toolCallbacks(allTools)
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        return content;
    }

    @Resource
    private ToolCallbackProvider toolCallbackProvider;

    public String doChatWithMcp(String message, String chatId) {
        ChatResponse chatResponse = chatClient.prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .toolCallbacks(toolCallbackProvider)//不确定
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }

    // 流式聊天方法
    // Flux<String> 是一个异步、非阻塞、可取消、可组合的文本片段流对象，被订阅后，多次发送文本片段
    public Flux<String> doChatByStream(String message, String chatId) {
        return chatClient.prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .stream()// 开启流式模式
                .content();// 返回文本片段流对象
    }

    @Resource(name = "chatWebTools")
    private ToolCallback[] chatWebTools;

    // 查时间默认常开：不联网、无副作用，不需要用户先点开关
    @Resource(name = "chatClockTool")
    private ToolCallback[] chatClockTool;

    // ===== 本轮可观测性的常量 =====
    /** RAG 条数上限。"展示用的检索"和"喂给模型的检索"必须共用这一个值。 */
    private static final int RAG_TOP_K = 5;
    /** 每段来源的展示长度。整块向量 1000 字，200 字足够判断"是不是这条"。 */
    private static final int RAG_SNIPPET_CHARS = 200;
    /** 工具参数/结果的展示长度。网页抓取会返回整页 HTML，不截断会发出几十 KB 的单帧。 */
    private static final int TOOL_ARGS_CHARS = 400;
    private static final int TOOL_RESULT_CHARS = 400;

    /**
     * 带 RAG / 联网开关的流式对话（事件版）。
     *
     * 返回 Flux<ChatEvent> 而不是 Flux<String>：
     * 正文、来源、工具调用是三种不同形状的信息，用一条具名事件流承载，
     * 前端才能"分别渲染"，落库时也才能只挑 delta。
     */
    public Flux<ChatEvent> doChatByStream(String message, String chatId,
                                          UUID userId, boolean useRag, boolean useTools) {

        // 1) 工具事件的"旁路"。工具在模型调用内部执行，没有返回值能带出信息，
        //    只能通过一个 sink 把事件旁路出去，再和正文流合并。
        Sinks.Many<ChatEvent> trace = Sinks.many().unicast().onBackpressureBuffer();
        Consumer<ChatEvent> emitTrace = event -> {
            // Spring AI 可能并行执行多个工具调用；Sinks 不是线程安全的，必须自己串行化。
            // 用 tryEmitNext 而不是 emitNext：取消/关闭后迟到的事件直接丢掉，不要抛异常。
            synchronized (trace) {
                trace.tryEmitNext(event);
            }
        };

        // 2) 工具挂载策略保持不变：查时间常开，联网开关只决定是否加搜索/抓取。
        //    唯一区别是外面包了一层"观测装饰器"。
        ToolCallback[] tools = useTools
                ? Stream.concat(Arrays.stream(chatClockTool), Arrays.stream(chatWebTools))
                .toArray(ToolCallback[]::new)
                : chatClockTool;
        ToolCallback[] tracedTools = ToolTraceCallback.wrap(
                tools, emitTrace, TOOL_ARGS_CHARS, TOOL_RESULT_CHARS);

        // 3) 检索条件只构造一份：下面的"展示用检索"和 advisor 的"喂模型检索"共用它，
        //    保证前端看到的片段就是模型实际拿到的那批。
        SearchRequest ragRequest = (useRag && loveAppVectorStore != null)
                ? ragSearchRequest(userId)
                : null;

        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, chatId));
        if (ragRequest != null) {
            spec = spec.advisors(QuestionAnswerAdvisor.builder(loveAppVectorStore)
                    .searchRequest(ragRequest)
                    .build());
        }

        // 4) 来源事件：开流前先检索一次，保证 sources 一定早于任何 delta 到达。
        //    检索失败只降级为"没有来源"，不能让整轮聊天 500——检索是增强项，不是前置条件。
        //    代价：这一轮会检索两次（一次展示、一次 advisor）。约多一次 embedding + SQL，
        //    换来的确定性是"展示的片段 == 模型看到的片段"，值得。
        List<ChatEvent.Source> sources = ragRequest == null
                ? List.of()
                : retrieveSources(ragRequest);

        // 5) 正文流：.content() 只有最终答案文本；每个 chunk 包成 Delta。
        Flux<ChatEvent> content = spec.toolCallbacks(tracedTools).stream().content()
                .map(ChatEvent::delta)
                // 关键：正文流结束/出错/被取消时关闭旁路。
                // 否则 merge 永远等不到第二条源结束，SSE 不会关闭，前端会一直转圈。
                .doFinally(signal -> {
                    synchronized (trace) {
                        trace.tryEmitComplete();
                    }
                });

        // 6) 先下来源，再合流"正文 + 工具事件"。
        //    merge 会同时订阅两条源；下游取消（点停止 / 断开）时两条一起取消，
        //    模型调用也会被取消——这一点和原来 Flux 的惰性语义一致，别改成自己 subscribe。
        Flux<ChatEvent> head = sources.isEmpty()
                ? Flux.empty()
                : Flux.just(ChatEvent.sources(sources));

        return Flux.concat(head, Flux.merge(content, trace.asFlux()));
    }

    /** 检索条件只写一份：userId 过滤 + topK。改这里等于同时改"展示"和"喂模型"。 */
    private SearchRequest ragSearchRequest(UUID userId) {
        // 只检索这个用户自己的资料：向量入库时写了 userId 元数据（见 KnowledgeService.indexQuietly）
        return SearchRequest.builder()
                .filterExpression("userId == '" + userId + "'")
                .topK(RAG_TOP_K)
                .build();
    }

    /** 展示用召回。异常吞掉只打日志——没有来源顶多是不显示，不该影响回答。 */
    private List<ChatEvent.Source> retrieveSources(SearchRequest request) {
        try {
            return loveAppVectorStore.similaritySearch(request).stream()
                    .map(this::toSource)
                    .toList();
        } catch (RuntimeException e) {
            log.warn("RAG 来源检索失败，本轮不展示来源: cause={}", e.getMessage());
            return List.of();
        }
    }

    /** Document -> 前端可读片段。元数据缺失一律给 null，前端自己兜底，别在后端抛。 */
    private ChatEvent.Source toSource(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        Object chunkSeq = metadata.get("chunkSeq");
        return new ChatEvent.Source(
                metadata.get("knowledgeItemId") == null ? null : metadata.get("knowledgeItemId").toString(),
                metadata.get("title") == null ? null : metadata.get("title").toString(),
                chunkSeq instanceof Number n ? n.intValue() : null,
                ToolTraceCallback.truncate(document.getText(), RAG_SNIPPET_CHARS),
                // PgVectorStore 不一定回填 score；没有就是 null，前端只在有值时显示
                document.getScore());
    }

//    /**
//     * 带 RAG / 联网开关的流式对话。开关由前端传入，服务端不做"智能判断"——
//     * 猜用户要不要检索，比让用户自己点一下更不可控。
//     */
//    public Flux<String> doChatByStream(String message, String chatId,
//                                       UUID userId, boolean useRag, boolean useTools) {
//        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
//                .user(message)
//                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, chatId));
//
//        if (useRag && loveAppVectorStore != null) {
//            // 只检索这个用户自己的资料：向量入库时写了 userId 元数据（见 KnowledgeService.indexQuietly）
//            spec = spec.advisors(QuestionAnswerAdvisor.builder(loveAppVectorStore)
//                    .searchRequest(SearchRequest.builder()
//                            .filterExpression("userId == '" + userId + "'")
//                            .topK(5)
//                            .build())
//                    .build());
//        }
//        // 查时间始终挂上；联网开关只决定要不要挂网页搜索与抓取
//        ToolCallback[] tools = useTools
//                ? Stream.concat(Arrays.stream(chatClockTool), Arrays.stream(chatWebTools))
//                        .toArray(ToolCallback[]::new)
//                : chatClockTool;
//        return spec.toolCallbacks(tools).stream().content();
//    }

}

