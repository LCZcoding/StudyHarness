package com.lcz.aiagentstudy.agent;

import cn.hutool.core.collection.CollUtil;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.lcz.aiagentstudy.agent.model.AgentState;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 处理工具调用的基础代理类，具体实现了 think 和 act 方法，可以用作创建实例的父类
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Slf4j
public class ToolCallAgent extends ReActAgent{
    // 有一个问题：Spring 的 ChatClient 已经支持选择工具进行调用（内部完成了 think、act、observe），
    // 但这里我们要自主实现，三种方式
    // 1. 可以使用 Spring AI 提供的 手动控制工具执行。
    // 2. 直接把所有工具调用的代码作为think，act不做事
    // 3. 自主实现工具调用能力。也就是工具调用章节提‌到的实现原理：自己写 Prompt，引导 AI 回复想要调用的工具列表和调用参数，然后再执行工具并将结果返送给 AI 再次执行。
    // 选择第一种


    // 可用的工具
    private final ToolCallback[] availableTools;

    // 保存了工具调用信息的响应
    private ChatResponse toolCallChatResponse;

    // 工具调用管理者
    private final ToolCallingManager toolCallingManager;

    // 禁用内置的工具调用机制，自己维护上下文
    private final ChatOptions chatOptions;

    public ToolCallAgent(ToolCallback[] availableTools) {
        super();
        this.availableTools = availableTools;
        this.toolCallingManager = ToolCallingManager.builder().build();

        // 禁用 Spring AI 内置的工具调用机制，自己维护选项和消息上下文
        this.chatOptions = DashScopeChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .toolCallbacks(List.of(availableTools))
                .build();
    }


    /**
     * 处理当前状态并决定下一步行动
     *
     * @return 是否需要执行行动，true表示需要执行，false表示不需要执行
     */
    @Override
    public boolean think() {
        // 不为null和空 添加NextStepPrompt到用户消息到上下文
        if (getNextStepPrompt() != null && !getNextStepPrompt().isEmpty()){
            UserMessage userMessage = new UserMessage(getNextStepPrompt());
            this.getMessageList().add(userMessage);
        }
        List<Message> messageList = getMessageList();
        Prompt prompt = new Prompt(messageList);//Prompt设置用户消息、工具调用选项到上下文，因为用户是并发的
        try {
            // 获取带工具选项的响应
            ChatResponse chatResponse = getChatClient().prompt(prompt)
                    .system(this.getSystemPrompt())
                    .options(chatOptions) // 传递配置,包含工具选项,表明手动实现
//                    .toolCallbacks(availableTools)// 工具已经在chatOptions中
                    .call()
                    .chatResponse();
            // 记录响应，包含需要调用的工具信息，用于 Act
            this.toolCallChatResponse = chatResponse;

            // 得到需要的助手消息
            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
            String result = assistantMessage.getText();
            // 记录本轮思考文本，供 step() 在无工具调用时返回给上层
            this.lastThought = result;
            // 得到要调用的工具列表信息
            List<AssistantMessage.ToolCall> toolCallList = assistantMessage.getToolCalls();
            // 输出提示信息
            log.info("{}的思考: {}", getName(), result);
            log.info("{}选择了 {} 个工具来使用", getName(), toolCallList.size());

            // 输出工具调用信息
            String toolCallInfo = toolCallList.stream()
                    .map(toolCall -> String.format("工具名称：%s，参数：%s",
                            toolCall.name(),
                            toolCall.arguments())
                    )
                    .collect(Collectors.joining("\n"));

            log.info("{}的工具调用信息: {}", getName(), toolCallInfo);

            if (toolCallList.isEmpty()) {
                // 只有不调用工具时，才记录助手消息 ,因为Act阶段调用工具时会自动记录
                getMessageList().add(assistantMessage);
                // 模型直接给出了文本回答，视为最终答复，结束执行循环，
                // 避免重复 think 把模型逼向无关的工具调用
                this.setState(AgentState.FINISHED);
                return false;
            } else {
                // 需要调用工具时，无需记录助手消息，因为Act阶段调用工具时会自动记录
                return true;
            }
        } catch (Exception e) {
            log.error("{}的思考过程遇到了问题: {}", getName(), e.getMessage());
            getMessageList().add(
                    new AssistantMessage("处理时遇到错误: " + e.getMessage()));
            return false;
        }
    }

    /**
     * 执行决定的行动
     *
     * @return 行动执行结果
     */
    @Override
    public String act() {
        if (!toolCallChatResponse.hasToolCalls()) {
            return "没有工具调用";
        }
        // 调用工具
        Prompt prompt = new Prompt(getMessageList(), chatOptions);
        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);
        // 记录消息上下文，conversationHistory 已经包含了助手消息和工具调用返回的结果
        setMessageList(toolExecutionResult.conversationHistory());
        // 当前工具调用的结果
        ToolResponseMessage toolResponseMessage =
                (ToolResponseMessage) CollUtil.getLast(toolExecutionResult.conversationHistory());

        String results = toolResponseMessage.getResponses().stream()
                .map(response -> "工具 " + response.name() + " 完成了它的任务！结果: " + response.responseData())
                .collect(Collectors.joining("\n"));
        // 判断是否需要终止交互
        boolean terminateToolCalled = toolResponseMessage.getResponses().stream()
                        .anyMatch(response -> response.name().equals("doTerminate"));
        if(terminateToolCalled){
            this.setState(AgentState.FINISHED);
            // 工具调用已结束，需要再生成一次最终文本回答返回给用户
            this.needsFinalAnswer = true;
        }
        log.info(results);
        return results;
    }

    /**
     * 工具调用全部结束后，让模型基于已有的工具执行结果生成最终文本回答。
     * 不带工具选项，确保模型只输出文本不再调用工具。
     */
    @Override
    protected String produceFinalAnswer() {
        try {
            // 追加一条引导消息，要求模型直接给出总结
            getMessageList().add(new UserMessage(
                    "以上是所有工具的执行结果。请你基于这些结果，直接用中文向用户给出清晰、完整的最终回答，不要调用任何工具。"));
            Prompt prompt = new Prompt(getMessageList());
            ChatResponse chatResponse = getChatClient().prompt(prompt)
                    .system(this.getSystemPrompt())
                    .call()
                    .chatResponse();
            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
            String text = assistantMessage.getText();
            // 将最终回答也记入上下文，便于多轮记忆
            getMessageList().add(assistantMessage);
            return text;
        } catch (Exception e) {
            log.error("生成最终回答失败: {}", e.getMessage());
            return null;
        }
    }
}
