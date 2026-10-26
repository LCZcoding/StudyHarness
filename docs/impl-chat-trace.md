# 对话可观测性改造（线 A + B1）：RAG 来源 + 工具调用

> 状态：**已实施**（2026-10-26 出稿并当日落地）。回填见 [`impl-log.md`](./impl-log.md) 末节；接口修订见 [`api-chat-stream.md`](./api-chat-stream.md) §8，原文未覆盖。
> 本页只管这一件事：把「本轮召回了哪些资料片段」「调用了什么工具、参数、结果、耗时」实时展示到 `/guide` 的引导式答疑里。

## 0. 范围与文件清单

**做**

| 层 | 文件 | 动作 |
| --- | --- | --- |
| model | `src/main/java/com/lcz/aiagentstudy/model/ChatEvent.java` | 新建 |
| tools | `src/main/java/com/lcz/aiagentstudy/tools/ToolTraceCallback.java` | 新建 |
| app | `src/main/java/com/lcz/aiagentstudy/app/LoveApp.java` | 改 5 参重载 |
| service | `src/main/java/com/lcz/aiagentstudy/service/ChatService.java` | 改返回类型 |
| controller | `src/main/java/com/lcz/aiagentstudy/controller/AiChatController.java` | 改发帧方式 |
| test | `src/test/java/com/lcz/aiagentstudy/service/ChatServiceTest.java` | 改 mock 与断言 |
| api | `ai-agent-frontend/src/api/chatStream.js` | 重写解析器 |
| component | `ai-agent-frontend/src/components/ChatRoom.vue` | 加展示 |

**不做**

- 不存工具/来源事件：`t_message` 不动，`MessageService` 不动。
- 不改 `LiManus` / `ToolCallAgent` / `AgentState`。
- 不改 `/ai/love_app/chat/sse`：2 参 `doChatByStream(String, String)` 保持 `Flux<String>`，`AiController` 不受影响。
- 不改提示词、不改模型。
- 不做「模型思考过程」展示，也不做 ReAct（那是 `decision-log.md` 里的「路 B」，另立项）。

**为什么不按「路 B」做**：ReAct 的 think 是非流式的，接进来会丢掉逐字输出，还要重写记忆/落库/取消，属于重写对话内核；本次只做「看见工具调用」，用装饰器即可，改动面小一个数量级。

---

## 1. 最终契约（先定，别边写边改）

`POST /ai/chat/stream` 从「纯文本帧」变成「具名事件帧」。每个帧形如：

```
event:delta
data:{"text":"线程池的"}

```

规则：

| 项 | 约定 |
| --- | --- |
| 帧分隔 | 空行（`\n\n`） |
| 事件名 | `event:` 后**不带空格**（Spring 就是这么写的，前端两种都兼容） |
| 负载 | **单行 JSON**（Jackson 默认不缩进；字符串里的换行会被转义成 `\n`，不会破坏帧） |
| 结束 | 先发 `done`，服务端随后关闭连接 |
| 错误 | 流已开始后 HTTP 状态码改不了，用 `error` 事件兜底 |

| event | data | 时机 | 前端动作 |
| --- | --- | --- | --- |
| `delta` | `{"text":"..."}` | 模型每吐一小段 | 追加到答案正文 |
| `sources` | `{"sources":[{knowledgeItemId,title,chunkSeq,snippet,score}]}` | 开流第一条（仅 `useRag=true`） | 答案下方展示「参考了 N 段资料」 |
| `tool` | `{"id","phase":"start"/"end","name","args","ok","ms","result"}` | 工具执行前后 | 同 `id` 合并成一行：调用中 → 完成/失败 |
| `done` | `{}` | 落库完成后 | 标记本轮结束（前端可提前收尾） |
| `error` | `{"message":"..."}` | 模型/落库出错 | 气泡下方红字提示 |

一轮 RAG + 工具对话的时序：

```
sources  →  delta × N  →  tool(start) → … → tool(end) →  delta × M  →  done
```

> `sources` 一定在 `delta` 之前（开流前就检索完）。工具事件夹在正文中间是正常的（模型可能先输出一句话再调工具）。

**为什么不能继续用 `Flux<String>`**：工具名/参数/结果、章节/片段/序号都是多字段结构。硬塞进纯文本只有两种下场——要么丢字段，要么用魔法字符串污染正文；而正文会被 `MessageService.appendRound` 原样落库，还会被「把这轮对话做成题」当成学习材料存进知识库。

---

## 2. 后端

### 步骤 B1：新建事件模型 `model/ChatEvent.java`

**目的**：把「一轮对话将要发给前端的所有东西」定义成一个封闭集合。`sealed` 是为了以后加事件类型时编译器会提醒你回来处理，比到处传 `Map<String,Object>` 安全。

```java
package com.lcz.aiagentstudy.model;

import java.util.List;

/**
 * 一轮对话对外吐出的事件。
 *
 * 为什么不再用 Flux<String>：
 * 工具调用（名字/参数/结果）和 RAG 来源（章节/片段/序号）都是多字段结构，
 * 硬塞进纯文本只有两种下场——要么丢字段，要么用魔法字符串污染正文，
 * 而正文会被 MessageService 原样落库、还会被"把这轮做成题"当成学习材料。
 *
 * 为什么用 sealed + record：
 * 1) record 自带不可变与序列化语义，直接交给 Jackson 就是一个单行 JSON；
 * 2) sealed 让"有哪些事件"成为编译期事实，加一种就必须回来处理。
 */
public sealed interface ChatEvent {

    /**
     * SSE 的 event: 名。这是前后端唯一的契约字符串，
     * 改这里等于改接口，必须同步改 chatStream.js。
     */
    String type();

    /** 正文增量。只有它会进答案、进聊天记忆、进数据库。 */
    record Delta(String text) implements ChatEvent {
        @Override public String type() { return "delta"; }
    }

    /** 本轮 RAG 召回结果。开流前一次性下发，之后不再变化。 */
    record Sources(List<Source> sources) implements ChatEvent {
        @Override public String type() { return "sources"; }
    }

    /**
     * 工具调用。start / end 两帧共享同一个 id，前端按 id 合并。
     * start 帧：args 有值，ok/ms/result 为 null
     * end 帧：args 为 null，ok/ms/result 有值
     */
    record Tool(String id, String phase, String name, String args,
                Boolean ok, Long ms, String result) implements ChatEvent {
        @Override public String type() { return "tool"; }
    }

    /** 正常结束。它到达时，本轮消息已经落库成功。 */
    record Done() implements ChatEvent {
        @Override public String type() { return "done"; }
    }

    /** 流内错误。SSE 已经发不出 HTTP 状态码，只能靠事件告诉前端。 */
    record Error(String message) implements ChatEvent {
        @Override public String type() { return "error"; }
    }

    /**
     * 一条召回片段（展示用，不参与任何业务逻辑）。
     * snippet 已在服务端截断：整块向量是 1000 字，全发过去会让单帧过大、前端也没人看完。
     */
    record Source(String knowledgeItemId, String title, Integer chunkSeq,
                  String snippet, Double score) {
    }

    // ---- 便捷工厂：调用点少写几个 new / 泛型 ----

    static ChatEvent delta(String text) {
        return new Delta(text);
    }

    static ChatEvent sources(List<Source> sources) {
        return new Sources(sources);
    }
}
```

**自检**：只新增文件，不改任何逻辑，项目应能编译通过。

---

### 步骤 B2：新建工具观测装饰器 `tools/ToolTraceCallback.java`

**目的**：不改任何 `@Tool` 类，在 `ToolCallback` 这一层统一埋点。以后新加工具自动带上观测。

当前挂给模型的工具是这样来的（只是包一层，策略不变）：

```239:257:src/main/java/com/lcz/aiagentstudy/app/LoveApp.java
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, chatId));
        ...
        ToolCallback[] tools = useTools
                ? Stream.concat(Arrays.stream(chatClockTool), Arrays.stream(chatWebTools))
                        .toArray(ToolCallback[]::new)
                : chatClockTool;
        return spec.toolCallbacks(tools).stream().content();
```

**关键设计点（注释里都写了，别删）**：

1. 框架实际调用的是 **两参数** `call(String, ToolContext)`，一参数版本有些实现根本不会走。所以两个入口都要覆盖，且逻辑只写一份（否则会埋两次点）。
2. `Sinks` 不是线程安全的，而 Spring AI 可能并行执行多个工具调用 → 事件发射必须外部串行化（放在 `LoveApp` 的 emit 里做）。
3. 结果可能是一整页 HTML（`WebScrapingTool`）→ **必须截断**，否则单个 SSE 帧几十 KB，浏览器和前端都会卡。
4. 工具抛异常时**原样重抛**：观测层不改变任何业务语义。

```java
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
 *
 * 为什么不在每个 Tool 类里埋点：
 * 工具只管"做事"，不该知道 SSE、事件名、前端长什么样。
 * 观测是横切关注点，包在 ToolCallback 这一层，所有工具自动生效。
 *
 * 为什么不自己实现 ReAct 循环来拿工具信息：
 * Spring AI 内置的 ToolCallingManager 已经能跑通工具调用，
 * 我们要的是"看见它"，不是"接管它"——接管会丢掉流式输出，代价大得多。
 */
public final class ToolTraceCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final Consumer<ChatEvent> emit;
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

    /** 工具定义必须原样透出，否则模型拿到的参数 schema 就变了。 */
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
```

**自检**：

- 编译通过（如 `ToolMetadata` / `ToolContext` 的 import 报错，用 IDE 补全定位；1.1.0 里 `ToolDefinition` 在 `org.springframework.ai.tool.definition`）。
- 此时还没有调用方，行为零变化。

---

### 步骤 B3：改 `LoveApp` 的 5 参流式方法

**目的**：

1. 返回值从 `Flux<String>` 变成 `Flux<ChatEvent>`；
2. 挂工具时套上 B2 的装饰器；
3. 开流前自己检索一次 RAG，作为 `sources` 事件；
4. 用 `Flux.merge` 把正文流和工具事件流合成一条。

把现有的 5 参方法整体替换为下面这段（2 参重载保持不动）：

```java
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
```

需要新增的 import：

```java
import com.lcz.aiagentstudy.model.ChatEvent;
import com.lcz.aiagentstudy.tools.ToolTraceCallback;
import org.springframework.ai.document.Document;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
```

**注意（容易踩）**：

- 2 参 `doChatByStream(String, String)` 保持不动，`AiController` 还在用。
- `retrieveSources` 是同步阻塞的（embedding + SQL，约 100~300ms），会在请求线程上执行。这是刻意取舍：换来「sources 一定先到」，可接受。**不要**把它丢进 `Flux.defer` 里再阻塞另一个线程，那样时序更难推理。
- 别把 `trace` 的 `doFinally` 写到 `Flux.concat` 外面，那样 `sources` 提前取消时 sink 永远不结束。
- `loveAppVectorStore` 是 `@Autowired(required = false)` 的字段，可能为 null，判断不能少。

**自检**：先只编译，不启动（`ChatService` 还没改，此处会编译报错，属正常，下一步顺掉）。

---

### 步骤 B4：改 `ChatService`

**目的**：从事件流里只挑 `Delta` 拼正文，落库仍然只存「问题 + 答案」；在落库成功之后补一个 `Done`。

当前实现（要替换）：

```31:49:src/main/java/com/lcz/aiagentstudy/service/ChatService.java
    public Flux<String> stream(UUID conversationId, String message, UUID userId,
                               boolean useRag, boolean useTools) {
        StringBuilder full = new StringBuilder();
        AtomicReference<Long> firstTokenAt = new AtomicReference<>();
        long startedAt = aiCallRecorder.start();

        return loveApp.doChatByStream(message, conversationId.toString(), userId, useRag, useTools)
                .doOnNext(chunk -> {
                    firstTokenAt.compareAndSet(null, System.nanoTime());
                    full.append(chunk);
                })
                .publishOn(Schedulers.boundedElastic())
                .doOnComplete(() -> {
                    aiCallRecorder.recordStream(userId, "CHAT", startedAt, firstTokenAt.get(), true, null);
                    messageService.appendRound(conversationId, message, full.toString());
                })
                .doOnError(e -> aiCallRecorder.recordStream(
                        userId, "CHAT", startedAt, firstTokenAt.get(), false, e.getClass().getSimpleName()));
    }
```

替换为：

```java
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
```

新增 import：`com.lcz.aiagentstudy.model.ChatEvent`（`Flux`、`AtomicReference`、`Schedulers`、`StringBuilder` 已经在用）。

**为什么要动这一段**：原实现是 `doOnComplete(() -> {record; save;})`。一旦引入 `done` 事件，「落库失败」和「正常结束」必须能被区分，否则前端会把「没存上」当成成功。`concatWith(Flux.defer(...))` 让「保存」成为流水线上的一环，抛异常自然以 `onError` 收场。

> 如果你想先最小改动：保留原 `doOnComplete`，末尾接 `.concatWithValues(new ChatEvent.Done())`。功能能跑，但落库失败时 `done` 仍可能被发出，前端会误判为已保存。**建议按上面的写法。**

**自检**：`ChatServiceTest` 此时会编译失败（mock 返回 `Flux<String>`），下一步修。

---

### 步骤 B5：改 `AiChatController`

**目的**：把每个 `ChatEvent` 序列化成单行 JSON，按 `event:` 名发给前端；错误时先发 `error` 帧再优雅关闭。

```java
@Slf4j
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiChatController {

    // Spring Boot 已自动配置 ObjectMapper；注入而不是 new，
    // 这样以后改 Jackson 全局配置（命名策略等）能一起生效
    private final ObjectMapper objectMapper;
    private final CurrentUserResolver currentUserResolver;
    private final ConversationService conversationService;
    private final ChatService chatService;

    // ... StreamRequest 保持不变

    @PostMapping("/chat/stream")
    public SseEmitter stream(@Valid @RequestBody StreamRequest req, HttpServletRequest request) {
        UUID userId = currentUserResolver.requireUserId(request);          // 401
        UUID conversationId = req.conversationId();
        conversationService.assertOwned(conversationId, userId);           // 403

        SseEmitter emitter = new SseEmitter(180000L);

        Disposable subscription = chatService.stream(
                        conversationId, req.message(), userId,
                        Boolean.TRUE.equals(req.useRag()),
                        Boolean.TRUE.equals(req.useTools()))
                .subscribe(
                        event -> {
                            try {
                                sendEvent(emitter, event);
                            } catch (IOException e) {
                                // 客户端断连：交给 emitter 收尾，onError 里会 dispose 订阅
                                emitter.completeWithError(e);
                            }
                        },
                        // 流已开始后 HTTP 状态码改不了，只能发一帧 error 让前端有话说。
                        error -> {
                            log.error("聊天流失败: conversationId={}", conversationId, error);
                            String message = (error instanceof DataAccessException)
                                    ? "回答已生成，但本轮未能保存，请稍后重试"
                                    : "回答中断，请稍后重试";
                            try {
                                sendEvent(emitter, new ChatEvent.Error(message));
                                // 先发错误帧再优雅关闭：completeWithError 在部分浏览器里
                                // 会让已写入的最后一帧读不到，前端就只剩"连接中断"
                                emitter.complete();
                            } catch (IOException e) {
                                emitter.completeWithError(error);
                            }
                        },
                        emitter::complete);

        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(subscription::dispose);
        emitter.onError((e) -> subscription.dispose());

        return emitter;
    }

    /**
     * 发一帧具名事件。
     *
     * 为什么 data 必须是单行 JSON：SseEmitter 对含换行的字符串会把裸换行写进响应体，
     * 前端按空行切帧时会直接切坏。Jackson 会把字符串内的换行转义成 \n，所以
     * 只要不开 pretty-print（不要配 spring.jackson.serialization.indent-output），就是安全的。
     */
    private void sendEvent(SseEmitter emitter, ChatEvent event) throws IOException {
        String json = objectMapper.writeValueAsString(event);
        emitter.send(SseEmitter.event().name(event.type()).data(json));
    }
}
```

新增 import：`com.fasterxml.jackson.databind.ObjectMapper`、`com.lcz.aiagentstudy.model.ChatEvent`、`lombok.extern.slf4j.Slf4j`、`org.springframework.dao.DataAccessException`。

**注意**：

- 不要给 `emitter.send(...)` 传对象让它自己转 JSON——那会走消息转换器，格式不受我们控制；手动 `writeValueAsString` 才能保证单行。
- 错误分支里 `emitter.complete()` 是刻意的：`completeWithError` 在流已开始时，Chrome 里常常表现为「最后一帧丢失」。错误信息我们已经用 `error` 事件发出去了，服务端日志也留了栈。
- `subscription` 在 lambda 里被引用会编译不过，所以错误分支只操作 `emitter`（和原实现一致）。

---

### 步骤 B6：改测试 `ChatServiceTest`

**目的**：mock 的返回类型变了；顺便加一条断言——**工具/来源事件不能进正文**。

```java
    @Test
    void stream_accumulatesChunksAndSavesOnComplete() {
        // 模型分两片吐出来；中间夹一条工具事件和一份来源——它们都不是正文
        when(loveApp.doChatByStream(anyString(), anyString(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(Flux.just(
                        new ChatEvent.Delta("答"),
                        new ChatEvent.Tool("t-1", "start", "getCurrentTime", "{}", null, null, null),
                        new ChatEvent.Tool("t-1", "end", "getCurrentTime", null, true, 3L, "2026-10-26 10:00:00"),
                        new ChatEvent.Sources(List.of(
                                new ChatEvent.Source("k-1", "线程池核心数", 0, "片段…", 0.83))),
                        new ChatEvent.Delta("案")));

        UUID userId = newUser("t-stream-" + UUID.randomUUID());
        UUID conversationId = newConversation(userId);

        List<ChatEvent> events = chatService.stream(conversationId, "问题", userId, false, false)
                .collectList().block();

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT role, content FROM t_message WHERE conversation_id = ? ORDER BY id",
                conversationId);

        assertEquals(2, rows.size(), "流结束时应落两行");
        assertEquals("问题", rows.get(0).get("content"));
        assertEquals("答案", rows.get(1).get("content"), "只拼 delta，事件不能混进正文");
        assertTrue(events.get(events.size() - 1) instanceof ChatEvent.Done, "最后一帧必须是 done");
    }
```

需要补的 import：`com.lcz.aiagentstudy.model.ChatEvent`、`java.util.List`（已有）、`static org.junit.jupiter.api.Assertions.assertTrue`。

`AiChatControllerTest` 不用改：5 条用例都发生在开流之前。

**跑一遍**：

```powershell
./mvnw -q -Dtest=ChatServiceTest test
```

---

### 步骤 B7：curl 验收

> 注意：从这一步到前端改完之前，浏览器里的 `/guide` 会把 JSON 当正文显示。**先用 curl 验收后端，别急着开页面。**

1. 拿会话 id：浏览器打开 `/guide`，地址栏 `/guide/<uuid>` 里那串就是。

2. 纯文本（两个开关都关）：

```powershell
curl.exe -N -s -b cookies.txt -X POST http://localhost:8123/api/ai/chat/stream `
  -H "Content-Type: application/json" `
  -d '{\"conversationId\":\"<会话id>\",\"message\":\"什么是指针的别名？\",\"useRag\":false,\"useTools\":false}'
```

期望看到（事件名 + 单行 JSON）：

```
event:delta
data:{"text":"指针"}
event:delta
data:{"text":"是变量"}
...
event:done
data:{}
```

`useRag=false` 时不应出现 `event:sources`。

3. 查时间工具（`useTools=false` 也会触发，因为时钟工具常开）：

```powershell
curl.exe -N -s -b cookies.txt -X POST http://localhost:8123/api/ai/chat/stream `
  -H "Content-Type: application/json" `
  -d '{\"conversationId\":\"<会话id>\",\"message\":\"现在几点了？\",\"useRag\":false,\"useTools\":false}'
```

期望中间出现：

```
event:tool
data:{"id":"...","phase":"start","name":"getCurrentTime","args":"{}","ok":null,"ms":null,"result":null}
event:tool
data:{"id":"...","phase":"end","name":"getCurrentTime","args":null,"ok":true,"ms":2,"result":"2026-10-26 10:00:00"}
```

4. RAG（先在资料页传一份材料，再问材料里的内容）：

```powershell
curl.exe -N -s -b cookies.txt -X POST http://localhost:8123/api/ai/chat/stream `
  -H "Content-Type: application/json" `
  -d '{\"conversationId\":\"<会话id>\",\"message\":\"我资料里线程池怎么讲的？\",\"useRag\":true,\"useTools\":false}'
```

期望第一帧就是：

```
event:sources
data:{"sources":[{"knowledgeItemId":"...","title":"...","chunkSeq":0,"snippet":"...","score":0.8}]}
```

5. 落库没被污染：

```sql
SELECT role, left(content, 60) FROM t_message WHERE conversation_id = '<id>' ORDER BY id DESC LIMIT 2;
```

ASSISTANT 那行应当是纯答案，没有 `{"text":`、没有工具 JSON。

---

### 步骤 B8：文档回填

实施通过验收后，在 `docs/api-chat-stream.md` 末尾追加「版本化修订（日期）——事件化」小节，写清：新增事件类型、为什么从纯文本改具名事件、落库仍只存 delta、前端解析方式。**不要覆盖原文**，原文留作历史判断。

---

## 3. 前端

> 前置：后端 B1~B6 已完成。此时 `chatStream.js` 还只认 `data:`，页面会显示 JSON——改完 F1~F2 就恢复。

### 步骤 F1：重写 `api/chatStream.js` 的解析器

**目的**：从「文本管道」升级为「事件分发器」。回调从 `onMessage(chunk)` 变成 `onEvent(name, payload)`。

当前解析器只挑 `data:` 行：

```50:59:ai-agent-frontend/src/api/chatStream.js
      const chunk = frame
        .split('\n')
        .filter((line) => line.startsWith('data:'))
        .map((line) => line.slice(5))
        .join('\n')

      if (chunk) onMessage?.(chunk)
```

**顺序要求**：先读 `event:`，再收集 `data:` 行，最后一帧只 `JSON.parse` 一次。

```js
/**
 * POST /api/ai/chat/stream 的流式读取（事件版）。
 *
 * 服务端每帧形如：
 *   event:delta
 *   data:{"text":"线程池"}
 *   <空行>
 *
 * 契约见 docs/api-chat-stream.md。与旧版的区别只有两点：
 *   1) 每帧带 event: 名，必须读出来，不能只挑 data:；
 *   2) data 是单行 JSON，要 parse 成对象再交给上层。
 */
export async function streamChat({
  conversationId,
  message,
  useRag = false,
  useTools = false,
  onEvent,   // (name, payload) => void   —— 唯一的事件出口
  onDone,    // 流关闭（无论是否收到 done 帧）调一次
  onError,   // 网络/解码/HTTP 错误
  signal
}) {
  try {
    const res = await fetch('/api/ai/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ conversationId, message, useRag, useTools }),
      credentials: 'include',
      signal
    })

    if (!res.ok) {
      // 4xx/5xx 仍然是 JSON { message }，发生在开流之前
      const err = await res.json()
      throw new Error(err.message)
    }

    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      // stream: true —— 结尾那半个多字节字符先留在解码器里，等下一片凑齐
      buffer += decoder.decode(value, { stream: true })
      // 个别反向代理会把 \n 变成 \r\n，先归一化，否则按 \n\n 切帧会漏帧
      buffer = buffer.replace(/\r\n/g, '\n')

      const frames = buffer.split('\n\n')
      buffer = frames.pop() // 最后一段可能是不完整帧，退回 buffer

      for (const frame of frames) {
        const { name, data } = parseFrame(frame)
        if (data === null) continue // 心跳/注释帧

        // 兼容旧后端：没有 event: 名、data 也不是 JSON 时，按正文增量处理
        if (name === 'delta' && data.raw !== undefined) {
          onEvent?.('delta', { text: data.raw })
          continue
        }
        onEvent?.(name, data.json)
      }
    }

    onDone?.()
  } catch (e) {
    if (e.name === 'AbortError') return // 用户点停止，不是错误
    onError?.(e)
  }
}

/**
 * 解析一帧：返回 { name, data }。
 * data 为 { raw } 表示"不是 JSON，当纯文本用"；为 { json } 表示解析成功；
 * 为 null 表示这一帧没有 data（心跳、注释），调用方跳过。
 */
function parseFrame(frame) {
  let name = 'delta'
  const dataLines = []

  for (const line of frame.split('\n')) {
    if (line.startsWith('event:')) {
      name = line.slice(6).trim() || 'delta'
    } else if (line.startsWith('data:')) {
      // 规范允许冒号后带一个空格，去掉它；JSON 本身不含前导空格，去掉是安全的
      dataLines.push(line.slice(5).replace(/^ /, ''))
    }
    // id: / retry: / 以 : 开头的注释行，一律忽略
  }

  if (!dataLines.length) return { name, data: null }

  const raw = dataLines.join('\n')
  try {
    return { name, data: { json: JSON.parse(raw) } }
  } catch {
    return { name, data: { raw } }
  }
}
```

**为什么 `JSON.parse` 失败还要当文本**：这样「新前端 + 旧后端」也能工作（旧后端只发 `data:文本`），联调时少一种互相等待。反过来「旧前端 + 新后端」不行，所以上线顺序是**先发后端、立刻发前端**（同一个提交里）。

---

### 步骤 F2：改 `ChatRoom.vue` 的脚本

**目的**：消息对象新增三个字段，事件按类型落到对应字段。

**2.1 扩展消息形状**（`addMessage` 与历史映射都要带默认值，避免模板里到处判空）

```js
function addMessage(role, content = '') {
  const msg = {
    id: ++msgSeq,
    role,
    content,
    time: nowText(),
    // 这三个字段不落库：只活在本次会话的内存里，刷新即消失（本次刻意不做持久化）
    sources: [],   // RAG 召回片段
    tools: [],     // 工具调用记录，按 id 合并 start/end
    errorText: ''  // 流内错误（SSE 已经发不出状态码）
  }
  messages.value.push(msg)
  return messages.value[messages.value.length - 1]
}
```

历史映射（`watch(() => props.historyMessages)` 里）同步补上：

```js
messages.value = items.map((m) => ({
  id: m.messageId,
  role: m.role === 'USER' ? 'user' : 'ai',
  content: m.content,
  time: formatHistoryTime(m.createdAt),
  sources: [],
  tools: [],
  errorText: ''
}))
```

**2.2 替换 `send()` 里的回调**

```js
  const aiMsg = addMessage('ai', '')
  streaming.value = true
  closeSource()

  // 只有真正的正文 delta 才会让它变 true。
  // 注意别用"收到任何事件"来判断：sources 往往比第一个字先到，用它会误判"已经吐过内容"。
  let hasDelta = false

  const onEvent = (name, payload) => {
    if (name === 'delta') {
      aiMsg.content += payload.text || ''
      hasDelta = true
      return
    }
    if (name === 'sources') {
      aiMsg.sources = payload.sources || []
      return
    }
    if (name === 'tool') {
      upsertTool(aiMsg, payload)
      return
    }
    if (name === 'done') {
      // 后端明确说"本轮已落库"，可以立刻收尾；随后流关闭还会再调一次 finish，靠幂等挡住
      finish()
      return
    }
    if (name === 'error') {
      aiMsg.errorText = payload.message || '回答中断'
    }
  }

  const onDone = () => finish()

  const onError = (err) => {
    aiMsg.content = hasDelta
      ? `${aiMsg.content}\n（连接中断，以上为已接收内容）`
      : `（${err.message}）`
    finish()
  }
```

**2.3 加 `upsertTool`（同 id 合并 start / end）**

```js
/**
 * 工具事件是"两半"发过来的：start 只有参数，end 只有结果/耗时。
 * 按 id 合并成一行，前端就不会出现两条记录。
 */
function upsertTool(msg, trace) {
  const index = msg.tools.findIndex((t) => t.id === trace.id)
  if (index === -1) {
    msg.tools.push({ ...trace })
  } else {
    msg.tools[index] = { ...msg.tools[index], ...trace }
  }
}
```

**2.4 `finish()` 改成幂等**（`done` 帧和流关闭会各触发一次，否则父组件会被通知两次去刷新侧栏）

```js
function finish() {
  if (!streaming.value) return
  streaming.value = false
  emit('done')
  nextTick(() => inputEl.value?.focus())
}
```

**2.5 调用方式**

```js
  controller = new AbortController()
  streamChat({
    conversationId: cid,
    message: text,
    useRag: props.useRag,
    useTools: props.useTools,
    signal: controller.signal,
    onEvent,
    onDone,
    onError
  })
```

**2.6 自动滚动**（原来只看 `content`，工具卡片出现时不会滚）

```js
watch(
  () => messages.value
    .map((m) => `${m.content}·${m.tools?.length || 0}·${m.sources?.length || 0}`)
    .join(''),
  async () => {
    await nextTick()
    const el = listEl.value
    if (el) el.scrollTop = el.scrollHeight
  }
)
```

**保留不动**：`parseAiContent`（`Step N:` 文本兼容）、`stop()`、`closeSource()`、`onKeydown`、`autoResize`。

---

### 步骤 F3：改模板

**目的**：工具卡片放在答案**上方**（工具发生在出答案之前），来源放在答案**下方**（引用习惯放底部），都不塞进 `.bubble`（那里是 `white-space: pre-wrap`，塞块级元素会打架）。

在 `.col` 内部、`<div :class="['bubble', m.role]">` **之前**插入：

```html
            <!-- 工具调用：只在 AI 消息、且本轮真的调过工具时出现 -->
            <div v-if="m.role === 'ai' && m.tools?.length" class="trace-block">
              <div class="trace-title">工具调用 · {{ m.tools.length }}</div>
              <div
                v-for="t in m.tools"
                :key="t.id"
                :class="['tool-row', { done: t.phase === 'end' && t.ok !== false, bad: t.ok === false }]"
              >
                <span class="tool-dot"></span>
                <span class="tool-name">{{ t.name }}</span>
                <span v-if="t.phase === 'start'" class="tool-status">调用中…</span>
                <span v-else-if="t.ok" class="tool-status">完成 · {{ t.ms }}ms</span>
                <span v-else class="tool-status bad">失败</span>

                <details v-if="t.args || t.result" class="tool-detail">
                  <summary>详情</summary>
                  <!-- 参数/结果都是服务端截断过的纯文本，用 pre 保留 JSON 观感 -->
                  <pre v-if="t.args">参数：{{ t.args }}</pre>
                  <pre v-if="t.result">结果：{{ t.result }}</pre>
                </details>
              </div>
            </div>
```

在 `<div :class="['bubble', m.role]">…</div>` **之后**插入：

```html
            <!-- RAG 召回片段：只在 AI 消息、且本轮开了资料检索时出现 -->
            <div v-if="m.role === 'ai' && m.sources?.length" class="sources-block">
              <div class="sources-title">参考了 {{ m.sources.length }} 段资料</div>
              <ul>
                <li
                  v-for="(s, i) in m.sources"
                  :key="`${s.knowledgeItemId || 'x'}-${s.chunkSeq}-${i}`"
                >
                  <!-- 有 knowledgeItemId 就跳到章节阅读页；没有就退化成纯文本 -->
                  <RouterLink
                    v-if="s.knowledgeItemId"
                    class="src-title"
                    :to="{ name: 'section-read', params: { knowledgeItemId: s.knowledgeItemId } }"
                  >{{ i + 1 }}. {{ s.title || '未命名章节' }}</RouterLink>
                  <span v-else class="src-title">{{ i + 1 }}. {{ s.title || '未命名章节' }}</span>
                  <p class="src-snippet">{{ s.snippet }}</p>
                </li>
              </ul>
            </div>

            <!-- 流内错误：模型中断 / 本轮没存上 -->
            <p v-if="m.errorText" class="ai-error">{{ m.errorText }}</p>
```

> `RouterLink` 在 `ChatRoom.vue` 里已经能用（模板里已有 `<RouterLink to="/">`，由 vue-router 全局注册），不需要额外 import。

---

### 步骤 F4：加样式

追加到 `<style scoped>` 末尾（浅色令牌已经定义在 `.chat-room` / `.theme-guide` 上，直接用）：

```css
/* ===== 本轮可观测性：工具调用 ===== */
.trace-block {
  margin: 0 0 10px;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: #fafafa;
}

.trace-title {
  margin-bottom: 6px;
  font-size: 11.5px;
  color: var(--muted);
  letter-spacing: 0.2px;
}

.tool-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  padding: 5px 0;
  border-top: 1px dashed var(--border);
}
.tool-row:first-of-type {
  border-top: 0;
}

/* 小圆点承担状态：调用中闪烁、完成变绿、失败变红 */
.tool-dot {
  width: 6px;
  height: 6px;
  flex: none;
  border-radius: 50%;
  background: var(--accent);
  animation: toolBlink 1s ease-in-out infinite;
}
.tool-row.done .tool-dot {
  background: #10a37f;
  animation: none;
}
.tool-row.bad .tool-dot {
  background: #d14343;
  animation: none;
}
@keyframes toolBlink {
  50% { opacity: 0.25; }
}

.tool-name {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  color: var(--text);
}
.tool-status {
  font-size: 11.5px;
  color: var(--muted);
}
.tool-status.bad {
  color: #d14343;
}

.tool-detail {
  flex: 1 1 100%;
  margin: 0;
}
.tool-detail summary {
  cursor: pointer;
  font-size: 11.5px;
  color: var(--muted);
}
.tool-detail pre {
  margin: 4px 0 0;
  padding: 6px 8px;
  border-radius: 6px;
  background: #ffffff;
  border: 1px solid var(--border);
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--ink-soft);
  white-space: pre-wrap;
  word-break: break-all;
}

/* ===== 本轮可观测性：RAG 来源 ===== */
.sources-block {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px solid var(--border);
}
.sources-title {
  font-size: 11.5px;
  color: var(--muted);
}
.sources-block ul {
  list-style: none;
  margin: 6px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.src-title {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--accent);
  text-decoration: none;
}
a.src-title:hover {
  text-decoration: underline;
}
/* 片段最多两行：详细内容让用户点进章节看，聊天区不当阅读器 */
.src-snippet {
  margin: 2px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ink-soft);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.ai-error {
  margin: 6px 0 0;
  font-size: 12px;
  color: #d14343;
}

@media (prefers-reduced-motion: reduce) {
  .tool-dot { animation: none; }
}
```

---

### 步骤 F5：浏览器验收

1. 开 `/guide`，发一条普通问题 → 只有答案，无卡片、无来源（两个开关都关，且模型没调工具）。
2. 问「现在几点了？」→ 答案上方出现「工具调用 · 1」，`getCurrentTime` **调用中…** → **完成 · 2ms**；展开详情能看到 `{}` 和返回的时间。
3. 打开「资料检索」开关，问材料里的内容 → 答案下方出现「参考了 N 段资料」，点标题能跳 `/materials/:id`。
4. 打开「联网」开关，问一个需要搜索的问题 → 出现 `searchWeb`，耗时通常几百毫秒到几秒；结果详情是截断过的，不会卡。
5. 生成中点「停止」→ 立刻停，页面不回滚、不报错。
6. 刷新页面 → 答案仍在，**卡片和来源消失**（预期：本轮不做持久化）。
7. 「把这轮做成题」→ 生成的资料里只有问答正文，没有 JSON（验证 `makeQuiz` 没被污染）。
8. Network → `chat/stream` 的 EventStream 面板能看到 `sources/delta/tool/done` 几种帧。

---

## 4. 已知坑（按踩到概率排序）

| # | 坑 | 症状 | 处理 |
| --- | --- | --- | --- |
| 1 | `doFinally` 漏写/写错位置 | 回答完了页面还在「正在想…」，前端收不到关闭 | `trace.tryEmitComplete()` 必须挂在 content 流的 `doFinally` 上 |
| 2 | 只重写 `call(String)` | 一个工具事件都不发 | 框架走两参数重载，必须在 `call(String, ToolContext)` 里埋点 |
| 3 | JSON pretty-print | 帧被切坏，前端报解析错 | 别开 `spring.jackson.serialization.indent-output`；手动 `writeValueAsString` |
| 4 | 工具结果不截断 | `scrapeWebPage` 一张网页 → 单帧几十 KB，页面卡死 | `TOOL_RESULT_CHARS = 400` |
| 5 | 用「收到任何事件」判断首包 | 点停止时误判「已吐内容」，占位文案错 | 只认 `delta`（`hasDelta`） |
| 6 | `finish()` 不幂等 | 侧栏刷新两次、输入框重复 focus | 加 `if (!streaming.value) return` |
| 7 | 重复埋点 | 同一工具出现两行/重复事件 | 两个 `call` 重载都只调私有 `tracedCall`，互相不调用 |
| 8 | 工具并行执行 | 偶发事件丢失/顺序乱 | 发射处 `synchronized (trace)` |
| 9 | 前后端上线顺序 | 页面显示 JSON 或 0 帧 | 一起发；联调期先用 curl 验后端 |
| 10 | 历史消息 `m.tools` 为 undefined | 模板报错 | 历史映射也补 `sources: [] / tools: [] / errorText: ''` |

---

## 5. 最终验收清单

**后端**

- [ ] `ChatEvent` 5 种事件，`type()` 与前端字符串逐字一致
- [ ] 工具 wrapper 对 start/end 各发一帧，同 id，异常时 `ok=false` 且原样重抛
- [ ] `useRag=false` 不发 `sources`；`loveAppVectorStore=null` 也不发
- [ ] `useTools=false` 仍有 `getCurrentTime` 的 `tool` 事件（时钟常开）
- [ ] 落库内容只含 delta 拼接结果
- [ ] 落库失败 → 前端收到 `error`（「本轮未能保存」）
- [ ] `ChatServiceTest` 绿；`AiChatControllerTest` 仍绿
- [ ] `docs/api-chat-stream.md` 追加版本化修订

**前端**

- [ ] 事件分发正确：delta 追加、sources 填充、tool 按 id 合并、error 显示
- [ ] 工具行有「调用中→完成/失败」状态变化
- [ ] 来源可点击跳章节页
- [ ] 停止按钮、断网、刷新三种异常路径表现正常
- [ ] 历史会话不显示卡片（而不是报错）

**明确不做（留在待办）**

- [ ] 事件不落库：刷新后消失
- [ ] 不展示模型思考文本
- [ ] 不接 ReAct / `POST /ai/agent/stream`

---

## 6. 可选加固（本次不做）

1. **消除 RAG 重复检索**：包装 `VectorStore`（`similaritySearch` 后把结果截下来）交给 `QuestionAnswerAdvisor`，一次检索两用。收益是一次 embedding 的延迟，代价是引入跨线程时序问题。
2. **事件落库**：`t_message` 加 `meta jsonb`，历史回放也能看到卡片；同时要改历史接口与前端映射。
3. **工具展示分级**：`getCurrentTime` 这类基础能力可以选择不显示，只显示联网类工具。

---

## 7. 建议的提交拆分

| 提交 | 内容 | 可验证点 |
| --- | --- | --- |
| ① | `ChatEvent` + `ToolTraceCallback` | 纯新增，编译通过，行为零变化 |
| ② | `LoveApp` + `ChatService` + `AiChatController` + `ChatServiceTest` | curl 能收到 `sources/delta/tool/done`；落库只存正文 |
| ③ | `chatStream.js` + `ChatRoom.vue` | 页面恢复并出现工具卡片与来源 |
| ④ | `api-chat-stream.md` 版本化修订 + `impl-log.md` 回填 | 文档与实现对齐 |
