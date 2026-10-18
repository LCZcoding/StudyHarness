package com.lcz.aiagentstudy.app;

import com.lcz.aiagentstudy.advisor.MyLoggerAdvisor;
import com.lcz.aiagentstudy.advisor.ReReadingAdvisor;
import com.lcz.aiagentstudy.chatmemory.FileBasedChatMemory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;

import java.util.List;

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

    /**
     * 构造方法，初始化对话客户端与会话记忆。
     *
     * @param deepSeekChatModel 底层大语言模型（DeepSeek）
     */
    public LoveApp(ChatModel deepSeekChatModel) {
        //  初始化基于文件的会话记忆
        String fileDir = System.getProperty("user.dir") + "/tmp/chat-memory";


        // 创建基于文件的会话记忆，最多保留最近 10 条消息
        ChatMemory chatMemory = MessageWindowChatMemory
                .builder()                                          // 创建 MessageWindowChatMemory 构建器
                .chatMemoryRepository(new FileBasedChatMemory(fileDir))  // 设置file存储仓库
                .maxMessages(10)                                    // 设置最大保留消息数
                .build();
//        // 创建基于内存的会话记忆，最多保留最近 10 条消息
//        ChatMemory chatMemory = MessageWindowChatMemory
//                .builder()                                          // 创建 MessageWindowChatMemory 构建器
//                .chatMemoryRepository(new InMemoryChatMemoryRepository())  // 设置内存存储仓库
//                .maxMessages(10)                                    // 设置最大保留消息数
//                .build();                                           // 构建 ChatMemory 实例

        // 创建对话客户端
        chatClient = ChatClient.builder(deepSeekChatModel)         // 指定底层大模型
                .defaultSystem(SYSTEM_PROMPT)                       // 设置系统提示词，定义 AI 角色和行为
                .defaultAdvisors(
                        MessageChatMemoryAdvisor
                                .builder(chatMemory)                    // 传入记忆对象，自动附加历史消息
                                .build(),
                        new MyLoggerAdvisor()
//                        ,new ReReadingAdvisor()
                )
                .build();                                           // 构建 ChatClient 实例
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

    /**
     * AI恋爱报告，结构化输出
     *
     * @param message 用户输入的消息内容
     * @param chatId  聊天会话的唯一标识符，用于关联历史消息
     * @return AI 助手的回复内容；如果响应为空则返回 {@code null}
     */
    public LoveReport doChatWithReport(String message, String chatId) {
        LoveReport loveReport = chatClient.prompt()             // 开始构建一次对话请求
                .system(SYSTEM_PROMPT + "请结构化输出恋爱报告，标题为用户名，内容为建议列表")
                .user(message)                                      // 设置用户发送的消息内容
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, chatId))  // 传入会话 ID，关联历史记忆
                .call()                                             // 执行同步调用（非流式）
                .entity(LoveReport.class);                          //  解析响应为 LoveReport 对象
        //log.info("loveReport: {}", loveReport);
        return loveReport;
    }

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

}

