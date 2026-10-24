![image-20261019153223740](D:\code\Java\ai-agent-study\docs\AiChatController.png)

# 接口文档 · 消息流式落库（第④格纵切）

> 状态：**已完成**（2026-10-19：本格测试 7/7 绿 + 回归 10/10 绿，合计 17/17；curl 端到端实测通过）。实测记录见 [`impl-log.md`](./impl-log.md) 第④格。
> **全局约定不在本页重复**：路径不带 `/api`、认证走 `HttpSession`、错误体统一 `{"message": ...}`、异常 → 状态码映射，全部沿用 [`api-conversations.md`](./api-conversations.md) §2。
> 分层与写法约定见 [`impl-log.md`](./impl-log.md) 第①格；表结构见 [`data-design.md`](./data-design.md) 的 `t\_message`。

***

## 1. 本格范围

**做**

1. `POST /ai/chat/stream`：收 `{conversationId, message}` → 归属校验 → SSE 流式吐 AI 回答 → **流结束后把这一轮的 USER / ASSISTANT 两条消息在同一事务里按序写入 `t_message`**
2. 第一次真正触发 `ForbiddenException`（→ 403）的归属校验 `assertOwned`
3. 新增 `model.MessageRole`、`model.MessageType`、`repository.MessageRepository`、`service.MessageService`、`service.ChatService`、`controller.AiChatController`

**不做（划界，别越界）**

| 项 | 留给 |
| --- | --- |
| `GET /conversations/{id}/messages`（历史回读） | 第⑤格 |
| 把多轮记忆（`FileBasedChatMemory` / 内存 `ConversationStore`）换成读 DB | MVP 主干（`resume-assets.md` 台账#3）。**本格只做"存得进"，不碰"读得出"** |
| 自测/复习那套「出题 → 作答 → 批改」消息流（`ANSWER` / `FEEDBACK` 的真实登场） | MVP 主干 |
| 换提示词 / 换模型 / 接 RAG | 本格**不改 AI 行为**——它和"落库"正交，先让这条链路可验证 |
| 逐 chunk 增量落库、断流续传 | 不做（YAGNI） |
| USER 消息"先落库再开流" | 不做（见 §5 拍板） |
| 前端改造（`fetch` + `ReadableStream`） | 第③格 |

**已就绪、本格直接复用**：`ConversationRepository.belongsTo(UUID, UUID)`（②格写好，**至今零调用方——本格是它的第一个调用方**）、`CurrentUserResolver`、`GlobalExceptionHandler`（403 那一行已就位）、`ForbiddenException`、`LoveApp.doChatByStream(message, chatId)`。

***

## 2. 接口契约

| 项 | 值 |
| --- | --- |
| 方法 | `POST` |
| 路径 | `/ai/chat/stream`（完整：`/api/ai/chat/stream`） |
| 请求头 | `Content-Type: application/json`；必须带有效 session Cookie |
| 认证 | **必须**，未登录 401 |
| 成功 | `200` + `Content-Type: text/event-stream`，每 chunk 一帧，流结束即关闭连接 |
| 失败 | 见 §2.3。**本格所有失败都发生在"流开始之前"**，所以还能正常返回 JSON |

### 2.1 请求体

```json
{ "conversationId": "0d5a1d0e-3a1b-4d6f-9c2e-8b7f1a2c3d4e", "message": "什么是指针的别名？", "useRag": true, "useTools": false }
```

| 字段 | 类型 | 必填 | 校验 | 说明 |
| --- | --- | --- | --- | --- |
| `conversationId` | string(UUID) | 是 | `@NotNull(message="conversationId 不能为空")` | 由②格 `POST /conversations` 签发。**不是前端自己生成的** |
| `message` | string | 是 | `@NotBlank(message="message 不能为空")` + `@Size(max=4000, message="message 过长")` | 请求体里**没有 `userId` 字段**：身份只从 session 取 |
| `useRag` | boolean | 否 | 无 | `true` 挂 `QuestionAnswerAdvisor`：按 `userId` 过滤、`topK(5)` 检索本人资料；缺省 `false` |
| `useTools` | boolean | 否 | 无 | `true` 挂 `chatWebTools`（网页搜索 / 网页抓取）；缺省 `false`。**写文件、执行命令、下载、生成 PDF 等有副作用的工具不挂到聊天** |

> 两个开关默认都是 `false`，由用户在对话页输入框右侧显式打开（图标按钮「资料检索」「联网」），服务端不做意图猜测。
> 开关只影响本轮回答，不改任何历史消息。
> `chatClockTool`（查时间）不受 `useTools` 控制，每一轮都挂——它不联网、无副作用，没有理由要求用户先点开关。

> 校验注解与类型匹配（②格定的规则）：`String` 用 `@NotBlank`，`UUID` / 枚举用 `@NotNull`——不能互换。

### 2.2 成功响应

```http
HTTP/1.1 200 OK
Content-Type: text/event-stream

data:指针

data:是变量在内存里的地址……
```

- 每收到一个 chunk 就 `emitter.send(chunk)` —— 现有 `AiController.doChatWithLoveAppSseEmitter` 就是这么写的，**照抄**。
- **不发结束帧、不回 messageId**：SSE 连接关闭本身就是结束信号（前端 `fetch` 读流时 `reader.read()` 返回 `done`）；消息 id 的来源是第⑤格的历史回读。YAGNI。

### 2.3 失败响应

| 场景 | 状态码 | body |
| --- | --- | --- |
| 未登录 / session 过期 | 401 | `{"message":"未登录"}` |
| 缺 `conversationId` | 400 | `{"message":"conversationId 不能为空"}` |
| `conversationId` 不是合法 UUID 字符串 | 400 | `{"message":"请求体不合法"}` |
| `message` 为空 / 全空白 | 400 | `{"message":"message 不能为空"}` |
| `message` 超过 4000 字符 | 400 | `{"message":"message 过长"}` |
| **`conversationId` 不存在** *或* **不属于当前用户** | 403 | `{"message":"会话不存在或无权访问"}` |
| 数据库炸了 / AI 调用失败 | 500 | 不 catch，冒泡 |

> **执行顺序**：`@Valid` 由 Spring 在**进方法体之前**执行 → 所以"未登录 + body 缺字段"实际得到 **400 而不是 401**；进到方法体后才是 401 → 403 → 开流。顺序不能反，否则"没登录也能探测会话是否存在"就是个旁信道。（同②格）

### 2.4 端到端手动示例（PowerShell）

```powershell
# -N 关闭缓冲，不然看不见"逐字流出"
curl.exe -N -b cookies.txt -X POST http://localhost:8123/api/ai/chat/stream `
  -H "Content-Type: application/json" `
  -d '{\"conversationId\":\"<②拿到的 id>\",\"message\":\"什么是指针的别名？\"}'
```

三条对照：不带 cookie → 401；带**别人的** conversationId → 403；换一个随机 UUID → **403（同一句话）**。

***

## 3. 分层落点与类清单

> **契约（类名 / 包 / 方法签名）按此表；方法体你自己写。**

| 层 | 类 / 位置 | 职责 | 契约 |
| --- | --- | --- | --- |
| Controller | `controller.AiChatController`<br>`@RestController` + `@RequestMapping("/ai")` | HTTP 翻译：建 emitter、订阅、转发 chunk | `SseEmitter stream(@Valid @RequestBody StreamRequest req, HttpSession session)` |
| DTO | **嵌套** `record StreamRequest(...)`（定义在 `AiChatController` 内） | web 层形状 | 沿用①格拍板：只服务单入口的 DTO 就近定义 |
| Service | `service.ConversationService`（**追加**方法，不新建类） | 业务拒绝：会话不归你 | `void assertOwned(UUID conversationId, UUID userId)`；不过就 `throw new ForbiddenException("会话不存在或无权访问")` |
| Service | `service.ChatService`（新） | 组装回答流 + 累积全文 + 流完成时触发落库 | `Flux<String> stream(UUID conversationId, String message)` |
| Service | `service.MessageService`（新，`@Transactional`） | **一个事务插两条** | `void appendRound(UUID conversationId, String userText, String assistantText)` |
| Repository | `repository.MessageRepository`（新） | 只跟数据库说话，**无 `if`** | `void insert(UUID conversationId, String role, String type, String content)` |
| model | `model.MessageRole`（`USER` / `ASSISTANT`）<br>`model.MessageType`（`QUESTION` / `ANSWER` / `FEEDBACK` / `CHAT`） | 跨层形状 | 常量名必须逐字等于 DB `CHECK` 里的字符串 |
| 复用 | `constant.ConversationKeys.CONVERSATION_ID` | 请求 / 响应字段名 | 已有常量就叫 `conversationId`，**不要新建 `MessageKeys`** |
| 复用 | `app.LoveApp.doChatByStream(message, chatId)` | 回答流来源 | `chatId` 传 `conversationId.toString()` |

### 3.1 目标调用链

```
POST /api/ai/chat/stream {"conversationId":"...","message":"..."}
  → AiChatController.stream(req, session)
      → CurrentUserResolver.requireUserId(session)                     → 取不到 401
      → ConversationService.assertOwned(req.conversationId(), userId)  → 不归你 403
      → ChatService.stream(conversationId, message)
            └─ LoveApp.doChatByStream(message, conversationId.toString())
                 .doOnNext(全文累积)
                 .doOnComplete(→ MessageService.appendRound(...))      → 同一事务两条 INSERT
  ← Controller 订阅：chunk → emitter.send ／ 完成 → emitter.complete ／ 出错 → completeWithError
```

### 3.2 三个必须讲清的实现要点

**(1) `@Transactional` 拦不住流式方法——"同一事务"必须落在回调里**

`Flux` 是"装配说明"，不是"结果"：`stream()` 一返回，若它在 `@Transactional` 方法里，**事务当场就提交了**，而 chunk 还在路上。所以：

- `ChatService.stream` **不能**标 `@Transactional`；
- 落库动作放进 `doOnComplete`，调**另一个 bean** 的 `@Transactional` 方法（`MessageService.appendRound`）。
- ⚠️ 这就是经典的 **self-invocation 坑**：如果 `appendRound` 和调用它的人在**同一个类**里、用 `this.appendRound(...)`，Spring 的代理被绕过，`@Transactional` **静默失效——不报错，只是事务不生效**。两个类分开是刻意的。

**(2) 累积全文用 `StringBuilder`，流结束才聚合**

`doOnNext` 每片拼进去，`doOnComplete` 取全文。**别指望从 emitter 里把已发出去的内容读回来**——框架不提供这个能力。

```java
public Flux<String> stream(UUID conversationId, String message) {
    StringBuilder full = new StringBuilder();
    return loveApp.doChatByStream(message, conversationId.toString())
            .doOnNext(full::append)
            .doOnComplete(() -> messageService.appendRound(conversationId, message, full.toString()));
}
```

**(3) Controller 直接抄 `AiController` 的 emitter 骨架**

订阅、`Disposable`、`onCompletion / onTimeout / onError` 三处 `dispose` —— 全都已经写在 [`AiController`](file:///d:/code/Java/ai-agent-study/src/main/java/com/lcz/aiagentstudy/controller/AiController.java#L56-L84) 里，**照抄，别重新发明**。

差别只有一处，而且是**自动产生的**：因为落库挂在 Flux 的 `doOnComplete` 上，它抛出的异常会顺着订阅的 `onError` 传到 `emitter::completeWithError` —— 于是"落库失败 → 流以错误结束"不需要额外写代码。

***

## 4. 验收清单（Definition of Done）

| # | 用例 | 断言 | 需要真实模型吗 |
| --- | --- | --- | --- |
| 1 | 未登录 + 合法 body | `401` + `jsonPath("$.message")` 有值 | 否 |
| 2 | 已登录 + `conversationId` 属于**别人** | `403` + `{"message":"会话不存在或无权访问"}` | 否 |
| 3 | 已登录 + 随机 UUID（不存在） | `403`，**与用例 2 逐字同文案**（证明"不存在"与"不归你"归并成同一种失败） | 否 |
| 4 | 缺 `conversationId` / `conversationId` 非 UUID 字符串 | `400` | 否 |
| 5 | `message` 空 / 全空白 / 超 4000 字符 | `400` | 否 |
| 6 | 落库：直接调 `MessageService.appendRound` 后查库 | `SELECT role, type, content FROM t_message WHERE conversation_id = ? ORDER BY id` → 恰好 **2 行**，USER 在前 ASSISTANT 在后，`type` 分别为 `QUESTION` / `ANSWER`，`conversation_id` 正确 | 否 |
| 7 | 接线：`@MockitoBean LoveApp` 返回 `Flux.just("答","案")`，消费 `ChatService.stream(...)` | 消费完再查库 → 2 行，ASSISTANT 那行 `content` == `"答案"`（证明"流式累积 + 完成时落库"这条线真的接上了） | 否（被 mock 掉） |
| 8 | 回归 | `AuthControllerTest` 3 + `ConversationControllerTest` 5 + `ConversationRepositoryTest` 2 = **10 条仍全绿** | 否 |

- 测试类：`controller/AiChatControllerTest`（用例 1-5）、`service/MessageServiceTest`（用例 6）、`service/ChatServiceTest`（用例 7）；基建同第①格（`@SpringBootTest` + `@Transactional`，Controller 那条再加 `@AutoConfigureMockMvc`）。
- `@MockitoBean`：本项目 Boot 版本 **3.4.4**，用 `@MockitoBean`（3.4 起取代 `@MockBean`）。
- **SSE 真实流不进自动化**：MockMvc 里异步响应要等流结束才可见，为它搭异步断言框架不值得（提速优先）。用 §2.4 的 `curl.exe -N` 手动跑一遍，**把真实输出贴进 `impl-log.md`**。

***

## 5. 本格拍板

| 项 | 结论 | 理由 / 否决了什么 |
| --- | --- | --- |
| 接口形态 | **新增 `POST /ai/chat/stream`**（用户已拍板） | 发消息 = 写操作（创建两条消息），GET + 查询串的语义（幂等 / 可缓存 / 可分享）与之冲突；中文内容进 URL 要编码、有长度上限、还会落进代理日志。代价是前端 `EventSource` 只支持 GET，③ 要换成 `fetch` + `ReadableStream`（约 20 行）。既有 `GET /ai/love_app/chat/sse` 教学接口保持不动 |
| 落库时机 | **流结束后一次性写两条（同一事务）** | `impl-log.md` 第③~⑤格表已定「一轮多条消息必须同一事务按序插入」。否决"先落 USER 再落 ASSISTANT"：中途失败会留下一条永远等不到回答的孤儿问句。否决"逐 chunk upsert"：把 1 次写放大成 N 次事务，且半截回答更没有意义 |
| 落库失败的对外表现 | **顺着 `onError` → `completeWithError`**（不静默 complete） | 宁可让前端知道"这一轮没存上"，也不要静默丢数据——⑤ 的历史回读是唯一真相，静默丢的数据不会自愈。代价：前端已渲染全文却收到错误，**这是刻意的取舍**（前端应表现为"回答已显示，但本轮未保存"） |
| `conversationId` 不存在 vs 不归你 | **一律 403 + 同一句文案**「会话不存在或无权访问」 | 同①格「用户不存在 / 密码错必须归并成同一种失败」的同类推广：**404 与 403 的差别本身就是存在性旁信道**——枚举 UUID 时，404 表示"不存在"、403 表示"存在但非你的"，等于免费确认了哪些 UUID 真实存在。否决分设 404 |
| USER / ASSISTANT 的 `type` 映射 | 本格**只固定自由问答轮**：USER → `QUESTION`，ASSISTANT → `ANSWER` | `type` **不能只按 role 推**：自测模式下用户那条是*作答*（应为 `ANSWER`）、AI 那条先是*出题*（`QUESTION`）再是*批改*（`FEEDBACK`）。那套映射需要"上一轮是不是题"的上下文，**留给自测模式格**，本格不预支。`CHAT`（值域里有）本格用不到，**留白不删**（同 `status` 字段的 YAGNI 处理） |
| `message` 长度上限 | `@Size(max = 4000)` → 400 | 边界校验的意义是**挡住能造成后果的输入**（TEXT 列无上限，但内存和 prompt 有）；4000 字符 ≈ 2000 汉字，够一条学习消息用。数字是可调拍板，不是原则 |
| Repository 收 `String` 还是枚举 | **收 `String`**，Service 传 `role.name()` / `type.name()` | 沿用②格实测结论：枚举对象直传 `JdbcTemplate` 会被 PG 驱动拒执行（`Can't infer the SQL type ...` → 500）；且 `name()` 与 DB `CHECK` 字符串逐字对齐 |
| 是否新建 `MessageKeys` | **不建**，复用 `ConversationKeys.CONVERSATION_ID` | 请求字段名就叫 `conversationId`，与②格一致（同一概念的常量只应有一份） |
| 回答流来源 | **复用 `LoveApp.doChatByStream`**，不改提示词 | 换提示词 / 接 RAG 是**产品行为**，与"落库"正交；先让这条链路可验证。改名（`LoveApp` → `StudyApp`）留给后续 |

***

## 6. 已知坑（本格大概率会踩）

1. **self-invocation 让 `@Transactional` 静默失效** —— 见 §3.2(1)。症状是"事务不生效但**没有异常可看**"，只能靠"两个类分开"来保证。
2. **异步响应在 MockMvc 里断言不到** —— SSE 响应体要等流结束才写出，别在 `andExpect` 上硬等；④ 的自动化只覆盖 §4 用例 1-7 那三块。
3. **先 400 后 401** —— `@Valid` 在进方法体前执行，所以"未登录 + body 缺字段"得到 400 而不是 401；写用例别把顺序搞反。
4. **客户端不可自报 `type` / `userId`** —— 请求体里没有这两个字段，它们是服务端决定的。客户端能自报的字段迟早变成越权入口（②格"身份权威"的同一条）。
5. **错误帧出不了状态码** —— 一旦开始写 SSE 响应头，后面失败就只能是"连接异常结束"，HTTP 状态码永远是 200。**这是 SSE 的固有约束，不是做错了**；前端要按"流中断"处理，而不是按 500 处理。
6. **`chatId` 就是 `conversationId.toString()`** —— 现有 `ChatMemory` 拿它做记忆键；别另造一个 id，否则同一会话在记忆和 DB 里分裂成两个身份（这正是台账#3 要收拾的老问题）。
7. **撞 `CHECK` 报的是 500** —— 看到 `23514` 说明"枚举值 / role 值"写错了，回去查 `name()` 有没有漏（别去改 SQL）。

***

## 7. 完成后回填

实现并通过验收后，把以下内容补进 [`impl-log.md`](./impl-log.md) 的第④格小节：

- 实际产出（**`curl.exe -N` 的真实输出片段**）
- 与本文档的差异（按"版本化修订"记，不覆盖本文档）
- 踩到的坑（**预测外的**同时转记到 [`resume-assets.md`](./resume-assets.md) 的风险预案 → 并进亮点台账）
- 归属校验的实际形态（`assertOwned` 落点、`MessageService` 之外是否还需要别的类）
