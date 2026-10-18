package com.lcz.aiagentstudy.agent;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

/**
 * ReAct (Reasoning and Acting) 模式的代理抽象类
 * 实现了思考-行动的循环模式
 */
@Slf4j
@Data// @Getter + @Setter + @ToString + @EqualsAndHashCode + @RequiredArgsConstructor
@EqualsAndHashCode(callSuper = true)// 生成的 equals() 会调用 super.equals()，把父类字段也纳入比较
public abstract class ReActAgent extends BaseAgent{

    /**
     * 最近一次 think 中大模型输出的真实文本，
     * 供 step() 在"无需行动"时把真实回答返回给上层（SSE/同步调用）
     */
    protected String lastThought = "";

    /**
     * 最近一次 step() 是否为模型直接给出的文本回答（true）
     * 还是工具执行结果（false）。用于决定是否加 "Step N:" 前缀。
     */
    protected boolean lastStepIsFinalAnswer = false;

    /**
     * 处理当前状态并决定下一步行动
     *
     * @return 是否需要执行行动，true表示需要执行，false表示不需要执行
     */
    public abstract boolean think();

    /**
     * 执行决定的行动
     *
     * @return 行动执行结果
     */
    public abstract String act();

    /**
     * 执行单个步骤：思考和行动
     *
     * @return 步骤执行结果
     */
    @Override
    public String step() {
        try {
            boolean shouldAct = think();
            if (!shouldAct) {
                // 模型直接给出文本回答，不加 Step 前缀
                lastStepIsFinalAnswer = true;
                // 返回大模型本轮的真实文本；文本为空时再降级为提示语
                return (lastThought != null && !lastThought.isEmpty())
                        ? lastThought
                        : "思考完成 - 无需行动";
            }
            lastStepIsFinalAnswer = false;
            return act();
        } catch (Exception e) {
            // 记录异常日志
            log.error("步骤执行失败", e);  // ✅ 代替 e.printStackTrace()
            lastStepIsFinalAnswer = false;
            return "步骤执行失败: " + e.getMessage();
        }
    }

    @Override
    protected boolean isLastStepFinalAnswer() {
        return lastStepIsFinalAnswer;
    }
}
