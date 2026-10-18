package com.lcz.aiagentstudy.agent;


import com.lcz.aiagentstudy.agent.model.AgentState;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import opennlp.tools.util.StringUtil;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 抽象代理类，属性模版，状态管理（状态转换、内存管理）、事件循环（step接口的执行循环），记忆
 *
 * 用于管理代理状态和执行的抽象基类。
 *
 * 提供状态转换、内存管理和基于步骤的执行循环等基础功能。子类必须实现 `step` 方法。
 */
@Data
@Slf4j
public abstract class BaseAgent {

    // 代理名称
    private String name;

    // 代理描述
    private String description;

    // 系统提示词
    private String systemPrompt;

    // 决定下一步动作的提示词
    private String nextStepPrompt;

    // llm 实例
    private ChatClient chatClient;

    // memory存储(自主维护会话上下文)
    private List<Message> messageList = new ArrayList<>();

    // agent状态
    private AgentState state = AgentState.IDLE;

    // 执行控制
    private int maxSteps = 20;// 最大执行步骤
    private int currentStep = 0;// 当前执行步骤, 从0开始计数

    // 助手回复消息的重复阈值
    private int duplicateThreshold = 3;// 有三条重复消息时确认重复

    // 会话 ID（用于多轮记忆）；未设置则不启用记忆
    private String conversationId;

    // 会话记忆存储（由外部注入，BaseAgent 不是 Spring Bean，故用 setter 注入）
    private ConversationStore conversationStore;

    // 工具调用结束后是否需要再生成一次最终文本回答
    // （模型调用 doTerminate 或达到最大步数时为 true；模型直接文本回答时为 false）
    protected boolean needsFinalAnswer = false;

    /**
     * 运行代理
     * @param userPrompt 用户输入的提示词
     * @return 执行结果
     */
    public String run(String userPrompt) {

        // 判断代理状态是否为IDLE
        if (this.state != AgentState.IDLE) {
            throw new RuntimeException("Cannot run agent from state: " + this.state);
        }

        // 用户输入的提示词不能为空
        if (StringUtil.isEmpty(userPrompt)) {
            throw new RuntimeException("Cannot run agent with empty user prompt");
        }
        // 切换状态为RUNNING
        this.state = AgentState.RUNNING;
        this.needsFinalAnswer = false;
        // 记录消息上下文
        this.messageList.add(new UserMessage(userPrompt));
        // 结果列表
        List<String> resultList = new ArrayList<>();

        try {
            // 执行循环
            for (int i = 0; i < this.maxSteps && this.state != AgentState.FINISHED; i++) {
                // 步数++
                int stepNumber = i + 1;
                this.currentStep = stepNumber;
                log.info("Executing step {}/{}", stepNumber, maxSteps);
                // 执行一步
                String stepResult = this.step();
                // 助手回复循环卡死判断、处理
                if (this.isStuck()) {
                    this.handleStuck();
                }
                // 执行结果存储：直接文本回答不加 Step 前缀，工具执行结果加前缀
                String result = this.isLastStepFinalAnswer()
                        ? stepResult
                        : "Step " + stepNumber + ": " + stepResult;
                resultList.add(result);
            }
            // 检查是否超出步骤限制,可以用来通知用户
            if (currentStep >= maxSteps) {
                state = AgentState.FINISHED;
                needsFinalAnswer = true;
            }
            // 工具调用结束后，生成最终文本回答
            if (needsFinalAnswer) {
                String finalAnswer = this.produceFinalAnswer();
                if (finalAnswer != null && !finalAnswer.isEmpty()) {
                    resultList.add(finalAnswer);
                }
            }
            return String.join("\n", resultList);
        } catch (Exception e) {
            // 异常捕获(状态设置+日志)
            state = AgentState.ERROR;
            log.error("Error executing agent", e);
            return "执行错误" + e.getMessage();
        } finally {
            // 清理资源
            this.cleanup();
            // 保存会话记忆
            this.saveConversation();
        }
    }

    /**
     * 运行代理（流式输出）
     * @param userPrompt 用户输入的提示词
     * @return SseEmitter
     */
    public SseEmitter runStream(String userPrompt) {
        // 创建一个超时时间较长的 SseEmitter
        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时,创建一个 SSE 响应对象。

        // 使用线程异步处理，避免阻塞主线程
        CompletableFuture.runAsync(() -> {
            try {
                // 判断代理状态是否为IDLE
                if (this.state != AgentState.IDLE) {
                    emitter.send("错误：无法从状态运行代理: " + this.state);
                    emitter.complete();
                    return;
                }

                // 用户输入的提示词不能为空
                if (StringUtil.isEmpty(userPrompt)) {
                    emitter.send("错误：不能使用空提示词运行代理");
                    emitter.complete();
                    return;
                }
                // 切换状态为RUNNING
                this.state = AgentState.RUNNING;
                this.needsFinalAnswer = false;
                // 记录消息上下文
                this.messageList.add(new UserMessage(userPrompt));
                // 结果列表
                //List<String> resultList = new ArrayList<>();

                try {
                    // 执行循环
                    for (int i = 0; i < this.maxSteps && this.state != AgentState.FINISHED; i++) {
                        // 步数++
                        int stepNumber = i + 1;
                        this.currentStep = stepNumber;
                        log.info("Executing step {}/{}", stepNumber, maxSteps);
                        // 执行一步
                        String stepResult = this.step();
                        // 助手回复循环卡死判断、处理
                        if (this.isStuck()) {
                            this.handleStuck();
                        }
                        // 执行结果存储：直接文本回答不加 Step 前缀，工具执行结果加前缀
                        String result = this.isLastStepFinalAnswer()
                                ? stepResult
                                : "Step " + stepNumber + ": " + stepResult;
                        emitter.send(result);
                    }
                    // 检查是否超出步骤限制,可以用来通知用户
                    if (currentStep >= maxSteps) {
                        state = AgentState.FINISHED;
                        needsFinalAnswer = true;
                    }
                    // 工具调用结束后，生成最终文本回答
                    if (needsFinalAnswer) {
                        String finalAnswer = this.produceFinalAnswer();
                        if (finalAnswer != null && !finalAnswer.isEmpty()) {
                            emitter.send(finalAnswer);
                        }
                    }
                    // 正常完成
                    emitter.complete();
                } catch (Exception e) {
                    // 异常捕获(状态设置+日志)
                    state = AgentState.ERROR;
                    log.error("Error executing agent", e);
                    //return "执行错误" + e.getMessage();
                    try {
                        emitter.send("执行错误: " + e.getMessage());
                        emitter.complete();
                    } catch (Exception ex) {
                        emitter.completeWithError(ex);
                    }
                } finally {
                    // 清理资源
                    this.cleanup();
                    // 保存会话记忆
                    this.saveConversation();
                }
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        emitter.onCompletion(() -> {
            if (this.state == AgentState.RUNNING) {
                this.state = AgentState.FINISHED;
            }
            this.cleanup();
            log.info("SSE connection completed");
        });
        return emitter;
    }


    /**
     * 判断AI输出消息是否重复超过2次（有三条重复消息时确认重复）
     * @return 是否超过
     */
    private boolean isStuck() {
        if(messageList.size() < 2){// 不小于2是可比条件\小于3是业务阈值
            return false;
        }
        Message lastMessage = this.messageList.get(messageList.size() - 1);
        String lastMessageText = lastMessage.getText();
        if(lastMessageText == null || lastMessageText.isEmpty()){
            return false;
        }
        // 写法1：stream
        long count = messageList.subList( 0 , messageList.size() - 1 ).stream()
                .filter(m -> m.getMessageType() == MessageType.ASSISTANT)
                .filter(m -> lastMessageText.equals(m.getText())) // lastText 已非空，天然 null 安全
                .count();
        return count >= duplicateThreshold;// end

        // 写法2：frequencyAPI（不太一样）
//        int frequency = Collections.frequency(messageList, lastMessage);// 神api,底层是遍历计数。
//        // `frequency` 用 整个 Message 对象的`equals()` 比较——类型、文本、metadata、toolCalls 全相等才算一条。
//        // 而需要的是 字段级比较 （type == ASSISTANT 且 text 相同）。
//
//        if (frequency >= duplicateThreshold){
//            return true;
//        }

        // 写法2: 普通for循环写法
        // 如果不用Collections.frequency:
        // 最后一条消息的数量
//        int countMessage = 0;
//        // 遍历size-1条消息
//        for (int i = 0; i < this.messageList.size() - 1; i++){
//            Message message = messageList.get(i);
//            // 相等则++
//            if (message.getMessageType() == MessageType.ASSISTANT && message.getText().equals(lastMessageText)){
//                countMessage++;
//                if (countMessage >= duplicateThreshold) {
//                    return true ;
//                }
//            }
//        }
//        return countMessage >= duplicateThreshold;
    }

    /**
     * Handle stuck state by adding a prompt to change strategy
     */
    private void handleStuck() {

        String stuckPrompt =
                "Observed duplicate responses. Consider new strategies and avoid repeating ineffective paths already attempted.";

        this.nextStepPrompt = stuckPrompt + this.nextStepPrompt;
        log.warn("Agent detected stuck state. Added prompt: {}", stuckPrompt);

    }



    /**
     * 执行单个步骤
     * 子类必须实现的抽象方法
     * @return 步骤执行结果
     */
    public abstract String step();

    /**
     * 上一步是否为模型直接给出的文本回答（非工具执行）。
     * 用于决定输出是否加 "Step N:" 前缀。默认 false，ReActAgent 重写。
     */
    protected boolean isLastStepFinalAnswer() {
        return false;
    }

    /**
     * 工具调用结束后生成最终文本回答。
     * 默认返回 null（表示无需总结），ToolCallAgent 重写以调用模型生成总结。
     */
    protected String produceFinalAnswer() {
        return null;
    }

    /**
     * 保存当前会话的消息上下文到 ConversationStore（若已设置 conversationId）。
     */
    protected void saveConversation() {
        if (conversationId != null && conversationStore != null) {
            conversationStore.save(conversationId, messageList);
            log.info("Conversation saved for chatId={}, messageCount={}", conversationId, messageList.size());
        }
    }

    /**
     * clean资源
     * 基类控制"何时"清理，子类决定"如何"清理。
     *
     * protected让子类可重写，同包可用，让外部不可用。
     * 如果用private子类不能重写，用public外部能调用。
     */
    protected void cleanup(){
        // 子类可以重写此方法来清理资源
    }

}
