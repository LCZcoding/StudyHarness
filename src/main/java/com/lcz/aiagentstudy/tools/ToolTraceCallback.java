package com.lcz.aiagentstudy.tools;

import com.lcz.aiagentstudy.model.ChatEvent;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Arrays;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 工具调用的观测装饰器。
 * 为什么不在每个 Tool 类里埋点：
 * 工具只管"做事"，不该知道 SSE、事件名、前端长什么样。
 * 观测是横切关注点，包在 ToolCallback 这一层，所有工具自动生效。
 * 为什么不自己实现 ReAct 循环来拿工具信息：
 * Spring AI 内置的 ToolCallingManager 已经能跑通工具调用，
 * 我们要的是"看见它"，不是"接管它"——接管会丢掉流式输出，代价大得多。
 */

public final class ToolTraceCallback implements ToolCallback {


    private final ToolCallback delegate; // 工具
    private final Consumer<ChatEvent> emit; // 用于事件记录
    private final int maxArgsChars;
    private final int maxResultChars;

    public ToolTraceCallback(ToolCallback delegate, Consumer<ChatEvent> emit,
                             int maxArgsChars, int maxResultChars) {
        this.delegate = delegate;
        this.emit = emit;
        this.maxArgsChars = maxArgsChars;
        this.maxResultChars = maxResultChars;
    }

    /** 批量包装：LoveApp 拿到的工具数组原样交进来即可。 */
    public static ToolCallback[] wrap(ToolCallback[] delegates, Consumer<ChatEvent> emit,
                                      int maxArgsChars, int maxResultChars) {
        return Arrays.stream(delegates)
                .map(d -> (ToolCallback) new ToolTraceCallback(d, emit, maxArgsChars, maxResultChars))
                .toArray(ToolCallback[]::new);
    }


    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();

    }

    /**
     * ToolMetadata 里可能有 returnDirect 之类的行为开关，必须委托。
     * 如果你的 1.1.0 里这个方法是 default 且编译不过，删掉本方法即可——但优先保留。
     */
    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    /**
     * 入口一：少数实现/测试会直接调它。
     * 注意：这里不要去调另一个 public call(...)，两边各埋一次会发出重复事件。
     */
    @Override
    public String call(String toolInput) {
        return tracedCall(toolInput, null);
    }

    /** 入口二：ToolCallingManager 实际走的是这个重载。 */
    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return tracedCall(toolInput, toolContext);
    }

    private String tracedCall(String toolInput, ToolContext toolContext) {
        String id = UUID.randomUUID().toString();
        String name = delegate.getToolDefinition().name();
        long startedAt = System.nanoTime();

        // start 帧先发：前端立刻能看到"正在调用 xxx"，而不是干等
        emit.accept(new ChatEvent.Tool(id, "start", name,
                truncate(toolInput, maxArgsChars), null, null, null));

        try {
            String result = (toolContext == null)
                    ? delegate.call(toolInput)
                    : delegate.call(toolInput, toolContext);

            emit.accept(new ChatEvent.Tool(id, "end", name, null, true,
                    elapsedMs(startedAt), truncate(result, maxResultChars)));
            return result;
        } catch (RuntimeException e) {
            // 失败也要发 end 帧，否则前端那一行永远停在"调用中…"
            emit.accept(new ChatEvent.Tool(id, "end", name, null, false,
                    elapsedMs(startedAt), truncate(e.getMessage(), maxResultChars)));
            // 必须重抛：工具失败怎么处理（转成模型可见的错误文本 / 中断）由 Spring AI 决定
            throw e;
        }
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    /**
     * 展示用截断。null 安全；不改变原文，只改"发给前端的那一份"。
     * 注意：截断只发生在事件里，工具返回值本身是完整的——模型拿到的仍是全文。
     */
    public static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
