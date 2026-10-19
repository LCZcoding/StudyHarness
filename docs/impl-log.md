# 纵切实施记录 · ai-agent-study

> 记录「一条纵切竖着穿过所有层」的实施过程：接口约定 → 每一格的产出、拍板、踩坑。
> **纵切**：一条窄任务，切完就能"跑"（区别于"逐层堆"——逐层堆的过程没有一刻能跑，所有坑在最后一次性炸）。
> 五格：**①登录（产出 userId）→ ②建会话（产出 conversationId）→ ③前端接上 → ④消息落库 → ⑤历史恢复**。
> 返回 [决策日志索引](./decision-log.md) ｜ 相关：[表设计](./data-design.md) ｜ [简历素材 / 风险预案](./resume-assets.md)
>
> 规则：每格的「预测外问题」→ 转记到 [`resume-assets.md`](./resume-assets.md) 的风险预案，并进亮点台账。

***

## 接口约定 · 会话标识（2026-10-16 定）

**结论**：`conversationId` 就是 `t_conversation.id`（UUID 字符串）。**前端不生成会话主键**，也不再维护任何映射层。

**依据（企业级标准做法）**

1. **服务端是会话 id 的唯一权威**。前端不自己发明业务主键：新建会话 = `POST /conversations` 拿服务端生成的 id，之后所有消息请求都带这个 id。前端自己生成的随机串如果要用，只能当**幂等键**（`client_message_id` / `Idempotency-Key`），用于防止"重试导致重复建会话"，不能当会话主键。
2. **对外标识符的选择取决于主键类型**——这才是取舍的分水岭：
   - 主键是**自增整型**（MySQL 场景常见）：连续、可枚举、能推算业务量 → 不直接暴露，另设业务号/雪花 ID 做对外标识。**别的方式在这里不是"落后"，而是标准做法。**
   - 主键是 **UUID**：不可枚举、不可猜、全局唯一 → **本身就是合格的外部标识符**，不需要再加一层。
3. **映射层 = 第二份真相**。任何「外部 chatId → 内部 id」的映射都要额外承担缓存一致性、多实例同步、失效清理三件麻烦事。除非有历史包袱（老客户端协议不能改）或天生外部标识（三方渠道如微信 openid），否则不引入。

**取舍维度小结**（面试可直接讲）

| 维度 | 直接暴露 UUID（选定） | 维护映射层 |
| --- | --- | --- |
| 是否可枚举 | 不可枚举，天然安全 | 取决于映射键，可能可枚举 |
| 代码复杂度 | 零映射，零缓存 | 多一份映射 + 一致性维护 |
| 多实例部署 | 无状态，天然支持 | 需共享缓存或 DB 存映射 |
| 主键类型无关性 | 依赖 UUID 这个前提 | 自增强主键时才有必要性 |

**否决 B（保留前端自定义 chatId + 映射）**：在本项目前提下，它等于存了一份「能推导出来却存了第二份」的东西，违反既定的字段取舍规则。

**面试口径（比"我加了个映射字段"更干净）**：主键选 UUID 的理由之一就是**能直接对外暴露、省掉映射层**；对外不可枚举 + 对内可直接用作外键，一举两得。

**遗留约束（不可省）**

- 每次访问会话必须校验归属：**UUID 只防猜，不防偷**。
- 若要防"重试重复建会话"，用 `client_message_id` 幂等键，而不是让前端自己生成会话主键。

**可推迟的升级路径**：懒创建（lazy create）——前端不带 id 直接发消息，服务端发现无 id 就建会话并把 id 回传（SSE 首帧或响应头），省一次往返。MVP 不做（YAGNI），但方案 A 的接口设计要留得住这个升级。

***

## 第①格纵切 · 登录（2026-10-17 完成，测试 3/3 绿）

**产出**：查 `t_user` → BCrypt 校验 → `HttpSession` 落 `userId`；接口 `POST /auth/login`；MockMvc 3 条测试全绿。

### 分层与职责（本格确立，后续格沿用）

| 层 | 职责 | 禁止 |
| --- | --- | --- |
| Controller | HTTP 语义翻译：收 JSON、定状态码、写 session | 不做业务判断 |
| Service | 业务决策：密码对不对、失败归并 | — |
| Repository | 只跟数据库说话，**返回事实** | **不出现 `if`** |

铁律：**Repository 里出现 `if` 就是职责越界的报警器** —— 它一旦开始"判断"，Service 层就失去了存在理由。

### 「空 / 错」四种归属

| 情形 | 归属 | 表现 |
| --- | --- | --- |
| 参数缺失 | Controller 的 `@Valid` | 400 |
| 用户不存在 | Service | 401 |
| 密码不对 | Service | 401 |
| 数据库炸了 | 谁都不 catch，冒泡到全局处理器 | 500 |

关键取舍：**「用户不存在」与「密码错误」必须归并成同一种失败**（同样返回 401 + 同一句提示）。否则接口本身就成了用户名枚举器。

### 返回值 vs 异常（选择规则）

规则：**只在一个入口出现的失败 → 用返回值（`Optional`）；会在 N 个入口重复出现的拒绝 → 用异常 + 全局处理器。**

- 本格：`AuthService.login` 返回 `Optional<UUID>`（登录校验只有这一个入口）
- 后续格：`ConversationService.assertOwned` 抛 `ForbiddenException`（归属校验会在 N 个接口重复）——**见第④/⑤格**（②格只有"新建"动作，构造不出自然的越权场景，故未做，符合划界表）

**动词与返回类型必须一致**：`find...` → `Optional<T>`；`get...` → `T`（拿不到就是异常）。本格复用既有的 `UserRepository.findByUsername`，未新造 `get`。

### 失败归并的实现（`Optional.filter().map()`）

```java
return userRepository.findByUsername(username)
        .filter(u -> passwordEncoder.matches(rawPassword, u.passwordHash()))
        .map(UserRow::userId);
```

一个链式表达式同时表达「查不到 → 空」与「密码不对 → 空」，正是"两种失败必须是同一种"这条取舍的代码化身；null 判断分支被彻底消除。

### 具体拍板

| 项 | 结论 | 理由 / 否决 |
| --- | --- | --- |
| 登录范围 | 最小登录（同表设计 v1 的 A 方案） | 否决验证码 / JWT 刷新 / OAuth |
| 引入依赖 | 仅 `spring-security-crypto`（提供 `BCryptPasswordEncoder`） | **否决 `spring-boot-starter-security`**：自带整套 Filter 链与默认登录页，与"最小登录"冲突 |
| `PasswordEncoder` 归宿 | 自建 `PasswordConfig` 里的 `@Bean`（用户自主拍板） | `spring-security-crypto` **只提供工具类、不提供任何 Bean**；直接 `@Autowired PasswordEncoder` 会 `NoSuchBeanDefinitionException` 启动失败。自建 Bean 保证全局只有一套 encode 参数（优于在 Service 里 `new`） |
| 会话凭据 | `HttpSession` + `SessionKeys.USER_ID` 常量 | 不引入 JWT（面试讲清原理即可） |
| DTO 位置 | **嵌套 record 就近定义**（`AuthController.LoginRequest`） | 用户自主拍板：「不用标准做法，这样就好」。原判据是"它属于哪一层"（web 层形状 → `controller/dto/`），只服务单入口的 DTO 就近定义同样成立；**记偏差、不覆盖** |
| 测试粒度 | 3 条（200 / 401 / 400），**不拆分** | 否决我提出的"每个分支单独拆测试类"：3 条测试的规模上拆分收益接近零，属过度工程 |

### 踩坑记录

**1. `spring-boot-starter-validation` 从来不在 `starter-web` 里（静默失效家族第五形态）**

- 起因：用户质疑「这个不用加吧，我有 stater 啊」
- `mvnw dependency:tree` 实测：只有 `jakarta.validation-api:3.0.2` 与 `json-schema-validator`，**没有 hibernate-validator**
- 根因：Spring Boot 2.3（2020-05）起 validation starter 被移出 Web starters
- 教训：**「classpath 上能 import 注解」≠「功能可用」** —— 有 API 无实现时 `@Valid` **静默不生效**，无任何报错

**2. `@Valid` 看门狗测试（`login_blankUsername`）**

- 断言 `username: ""` → **400**（而不是一路走到 Service 变成 401）
- 运行时证据（非推断）：日志实打实打印 `Resolved [MethodArgumentNotValidException: field 'username': rejected value []]`
- 价值：以后谁把 `jakarta` 改回 `javax`、谁删掉 `@NotBlank`、谁去掉 hibernate-validator，这条立刻从 400 变 401 而红

**3. MockMvc 测不到容器行为（我给的断言错了，不是用户代码错）**

- 现象：`cookie().exists("JSESSIONID")` 失败，输出中 `Cookies = []` 且无 `Set-Cookie` 头
- 根因：**写 `Set-Cookie` 是容器（Tomcat）的职责，不是 Spring MVC 的**。MockMvc 伪造了"请求从哪来"，同时丢掉了容器
- 修法：改用 `andReturn()` + 断言 `result.getRequest().getSession().getAttribute(SessionKeys.USER_ID)`
- 边界结论：**MockMvc 适合验证业务链路，不适合验证容器行为**（Cookie、真实过滤器链、序列化细节）

**4. 我给错的一次预测：PG 的赋值转换是单向的**

- 我断言「int 传 varchar 会报 `42804`」→ 实测**允许**（`int → varchar` 属解析器 I/O 转换，**不在 `pg_cast` 目录里**）；反向 `varchar → int` 不允许
- 教训：别把"PG 强类型"当成双向规则。**每次给出可证伪的预测 + 让程序真跑一遍**，比靠推理更可靠

### 测试基建（后续格直接沿用）

`@SpringBootTest` + `@AutoConfigureMockMvc` + `@Transactional`

- `@SpringBootTest` 默认 `webEnvironment = MOCK`：**不启动 Tomcat、不占端口**
- **路径不带 context-path**：写 `post("/auth/login")`，不是 `/api/auth/login`
- `@Transactional` 只对**同线程**测试有效 → MockMvc 可用；`RANDOM_PORT + TestRestTemplate` 不可用（脏数据会真写进云库）

***

## 第②格纵切 · 建会话（2026-10-18 完成，本格 5/5 绿 + 回归 5/5 绿）

**产出**：`POST /api/conversations` → `201 Created` + `Location: /conversations/{id}` + `{"conversationId":"<uuid>"}`。

MockMvc 5 条全绿：未登录 401 / 已登录 201+Location+conversationId **+ 查库比对 `user_id` == session userId** / 缺 mode 400 / `"abc"` 400 / `"free_chat"` 400。回归：`AuthControllerTest` 3 条 + `ConversationRepositoryTest` 2 条仍绿（合计 10/10）。

### 本格确立的基建（后续格沿用）

| 组件 | 职责 | 关键取舍 |
| --- | --- | --- |
| `web.CurrentUserResolver` | session → userId，取不到抛 `UnauthorizedException` | 用 `instanceof` 模式匹配，**不用强转**：强转失败是 `ClassCastException` → 500，而它实际是"登录态不可用" → 该 401 |
| `web.GlobalExceptionHandler` | 4 类异常 → HTTP | **不写 `Exception.class` 兜底**：会把 404/405 一起吞成 500 |
| `exception.UnauthorizedException` / `ForbiddenException` | 业务拒绝 | 构造器只收 message、**不带状态码**：401 是 HTTP 层的词汇，异常不该认识它（同一异常还要能被定时任务等非 HTTP 入口复用） |

**文案归属**（沿用①格"文案随产生拒绝的地方走"）：`throw new UnauthorizedException("未登录")` 写在抛的地方；`@NotNull(message="mode 不能为空")` 写在校验注解上；处理器**只做转发** `e.getMessage()`。

**身份权威**（本格的核心，也是③格的前提）：服务端签发 UUID、身份只从 session 取、请求体里**没有 userId 字段**（客户端想自报也没地方报）。验收用例 3 用"查库比对"把这条钉死——**201 只证明"创建动作成功"，一个字都没说归属**。

### 与文档的差异（版本化修订，不覆盖 api-conversations.md）

- `@NotNull` 带了 `message="mode 不能为空"`（文档 §3.3 定的就是这句；运行日志实测 `default message [mode 不能为空]` 生效）
- **`ConversationRepository.create` 的签名一度被改成 `ConversationMode`，后回退为 `String`**——违反文档 §4.1.1 的拍板。偏离 → 实测 → 回退，全过程见下
- `ConversationService.belongsTo` 出口一度加上、后删除：④/⑤真正需要的形态是 `assertOwned`（校验不过就抛 403），**不是返回 boolean 的查询**

### 踩坑：枚举对象直传 JDBC（预测外，已转记 resume-assets.md）

- **现象**：`org.postgresql.util.PSQLException: Can't infer the SQL type to use for an instance of com.lcz.aiagentstudy.model.ConversationMode. Use setObject() with an explicit Types value to specify the type to use.` → `BadSqlGrammar` → 两条 Repository 用例全 ERROR
- **根因**：JDBC 驱动只认标准类型，**它不猜，直接拒绝执行**（我曾预测"驱动会调 `toString()` 兜底"——实测证明我错了）
- **修法**：显式 `mode.name()`。为什么是 `name()` 不是 `toString()`：`name()` 不可覆写、与 DB 的 CHECK 字符串逐字对齐；`toString()` 可被覆写，将来有人为了日志好读加一行 override，就会撞 CHECK 报 `23514`
- **教训**：**"签名更类型安全"不等于更安全**——契约一改，调用方（测试）跟着改，转换一步没省，代价却是运行时 500。**"优雅"之前要先算改动波及面**

### 归属校验 `assertOwned` / 403

未做，**符合预期**（文档 §1 划界表留给④/⑤；②格只有"新建"动作，构造不出自然的越权场景，硬写测试就是人为造场景）。

***

## 第④格纵切 · 消息落库（2026-10-19 完成，本格 7/7 绿 + 回归 10/10 绿）

**产出**：`POST /ai/chat/stream` → SSE 逐字流出；流结束后把这一轮的 USER / ASSISTANT 两条**同一事务、按序**写进 `t_message`；`ConversationService.assertOwned` 第一次真正抛 `ForbiddenException` → 403。

契约（唯一真理源）：[`api-chat-stream.md`](./api-chat-stream.md)。

新增类：`model.MessageRole` / `model.MessageType`、`repository.MessageRepository`、`service.MessageService`、`service.ChatService`、`controller.AiChatController`；`ConversationService` 追加 `assertOwned`。

测试 7 条全绿：`AiChatControllerTest` 5（未登录 401 / 别人的会话 403 / 不存在的 UUID 403 同文案 / 坏 `conversationId` 400 带文案 / 坏 `message` 400 带文案）、`MessageServiceTest` 1（一轮落两行，顺序与 `role`/`type` 对齐）、`ChatServiceTest` 1（mock 模型吐两片 → 拼成全文落库）。回归 10 条仍绿，合计 **17/17**。

### 本格的核心：`@Transactional` 拦不住流式方法

`Flux` 是「装配说明」不是「结果」：`ChatService.stream` 一返回，若它标了 `@Transactional`，**事务当场提交，而 chunk 还在路上**。所以落库挂在 `doOnComplete` 上、由**另一个 bean**（`MessageService.appendRound`）承接 `@Transactional`——同一个类里写 `this.appendRound(...)` 会绕过 Spring 代理，`@Transactional` **静默失效、不报错**。两个类分开是刻意的。

第二层：`Flux` **懒执行**——不 `subscribe` 一行回调都不跑；`AiChatController` 的 `.subscribe()` 才是启动开关。

### 本格确立的基建

| 组件 | 职责 | 关键取舍 |
| --- | --- | --- |
| `MessageRepository.insert(UUID, String role, String type, String content)` | 一条 INSERT | **收 `String` 不收枚举**：沿用②格「枚举直传 JDBC 被驱动拒绝」的教训，`name()` 转换归 Service 侧 |
| `MessageService.appendRound(...)`（`@Transactional`） | 一个事务插两条 | 否决「先落 USER 再落 ASSISTANT」：中途失败会留下永远等不到回答的孤儿问句 |
| `ChatService.stream(...)` | 组装流 + 累积全文 + 完成时落库 | **不标 `@Transactional`**；累积用 `StringBuilder`（框架不提供"把已发出的 chunk 读回来"的能力），且必须在方法内 `new`——`ChatService` 是单例，写成字段会让不同会话串内容 |
| `ConversationService.assertOwned` | 归属拒绝 → 403 | 形态是「不过就抛异常」，**不是返回 boolean 的查询**（②格已预判并删掉了 `belongsTo` 的出口包装） |
| `AiChatController.stream` | 身份(401) → 归属(403) → 开流 | emitter 骨架照抄 `AiController.doChatWithLoveAppSseEmitter`；**`SseEmitter` 不出现在 Service**（业务层不认识 web，⑤ 与自测模式才能复用 `ChatService`） |

### 归属失败为什么要归并

`conversationId` 不存在 与 不属于你 → **一律 403 + 同一句「会话不存在或无权访问」**。404 与 403 的差别本身就是存在性旁信道：枚举 UUID 时，404 表示"不存在"、403 表示"存在但非你的"，等于免费确认了哪些 UUID 真实存在。用例 2/3 共用同一个文案常量，把这条钉死。

### 测试看门狗（本格新增的锋利处）

- 用例 4/5 **故意不伪造登录态**：`@Valid` 一旦静默失效，请求会走到「取身份」变成 401 而不是 400，测试照样红。
- 用例 4/5 断言的是**文案**（`conversationId 不能为空` / `请求体不合法` / `message 不能为空` / `message 过长`），不再只看状态码——原因是预测外问题 1。
- `AiChatControllerTest` 用 `@MockitoBean LoveApp` 顶掉模型：万一归属校验被改坏，请求走到开流那步也不会真的打收费 API。**Boot 3.4 起是 `@MockitoBean`，不是 `@MockBean`**。

### 预测外问题 1：400 的两类处理器从未实现（已修）

- **现象**：测试跑通后核对④契约 §2.3，发现 400 的响应体**是空的**。日志实证（非推断）：`Resolved [MethodArgumentNotValidException: ... default message [conversationId 不能为空]]` 与 `Resolved [HttpMessageNotReadableException: Cannot deserialize value of type java.util.UUID from String "not-a-uuid"]`——**`Resolved` 二字说明是 Spring 的 `DefaultHandlerExceptionResolver` 接的，不是我们的 `GlobalExceptionHandler`**。
- **根因**：②格契约 §2.4 承诺了 4 类映射（401 / 403 / `MethodArgumentNotValidException`→400 / `HttpMessageNotReadableException`→400），**但 `GlobalExceptionHandler` 只实现了前两类**；②格测试只断言状态码，缺口因此一直隐身。
- **修法**：补齐两个 `@ExceptionHandler`。校验失败那条**守规矩**（文案由 `@NotNull(message = ...)` 携带、处理器只转发第一条 `defaultMessage`）；body 解不开那条是**必要例外**（拒绝方是 Jackson 序列化器，它带不了我们的文案，只能固定给一句「请求体不合法」）。
- **教训**：「契约里写了 body」+「测试只断言状态码」= **假验收**。文档承诺的输出形状，必须有测试真的去读它。

### 预测外问题 2：流式回调里的阻塞调用（记账，本格未修）

- **现象**：IDE 提示 `ChatService.stream`「在非阻塞上下文中使用阻塞调用可能会导致线程匮乏」。
- **根因**：`doOnComplete` 的回调跑在**「完成信号」所在的那个线程**上——即底层 HTTP 客户端的线程（`WebClient` / Reactor Netty 场景下是 event loop）。`messageService.appendRound` 是 JDBC 阻塞调用（两条 INSERT + 事务提交），阻塞 event loop 会连累同一条 loop 上的其他请求。
- **为什么不能随手"把落库甩到别的线程"**：契约 §5 拍板「落库失败 → 顺着 `onError` → `completeWithError`」，而异常**只有从链路内部抛出**才会变成 `onError`。`Schedulers.boundedElastic().schedule(落库)` 会让异常逃逸到另一个线程，前端再也收不到「本轮未保存」。
- **正确修法**：`loveApp.doChatByStream(...).publishOn(Schedulers.boundedElastic())`——`publishOn` 把**下游所有信号**（`onNext` / `onComplete` / `onError`）切到 `boundedElastic`（专为阻塞工作准备的池），整条下游（含 `emitter.send` 的 servlet 阻塞写）一并挪出 event loop。代价是每个 chunk 多一次线程出入队。
- **易混点**：`subscribeOn` 只改「订阅 / request 发生在哪个线程」，**不改**数据与完成信号的发射线程——用它解决阻塞是无效的。
- **本格决策**：**不修**。本格目标是「存得进 + 归属校验可验证」，引入线程模型变更会把调试面扩大；记账待主干处理。附带说明：既有 `AiController.doChatWithLoveAppSseEmitter` 在同一个订阅回调里做 `emitter.send`，属同类问题（教学 demo，未管）。

### curl 端到端实测（2026-10-19 16:10，应用跑在 8123，真实 DeepSeek 模型）

**① 逐字流出**（`POST /api/ai/chat/stream`，带 cookie，`-N` 关缓冲）→ HTTP 200，`data:` 帧按 token 粒度到达（中文甚至按单字切）：

```
data:指针

data:的

data:别名
...
data:你判断一下，说说理由？
HTTP:200
```

**② 不带 cookie** → 401

```
{"message":"未登录"} HTTP:401
```

**③ 随机 UUID（会话不存在）** → 403

```
{"message":"会话不存在或无权访问"} HTTP:403
```

**④ 别人的会话**（另建一个 synthetic user 持有会话）→ 403，**与 ③ 逐字同文案**——「不存在」与「不归你」归并成功

```
{"message":"会话不存在或无权访问"} HTTP:403
```

**⑤ 查库确认落两行**（`ORDER BY id`）

```
role | type | len | head
USER | QUESTION | 9 | 什么是指针的别名？
ASSISTANT | ANSWER | 1053 | # 指针的别名
```

恰好 2 行、USER 在前、`type` 分别为 `QUESTION` / `ANSWER`；`ASSISTANT` 那行是 **1053 字符的全文**——证明 `StringBuilder` 累积 + `doOnComplete` 落库这条线在真实模型下也成立（不只是 mock 里成立）。

**⑥ 附带验证**：测试过程中误传空 `conversationId`，返回 `{"message":"conversationId 不能为空"} HTTP:400`——**预测外问题 1 补上的 400 处理器在真实请求下也生效了**。

**收尾**：登录 `POST /api/auth/login` → 200 + `userId`；建会话 `POST /api/conversations` → `201` + `Location: /conversations/<uuid>`（②格契约顺带在真实请求下复验了一次）；④ 用的 synthetic user / conversation 已 `DELETE`，远端库无残留。

### 本格仍未做

- 「把多轮记忆换成读 DB」（`FileBasedChatMemory` → DB）：本格只做「存得进」，「读得出」归⑤格与主干（台账 #3）。
- `publishOn(Schedulers.boundedElastic())`（预测外问题 2）：记账不修。

***

## 第③ / ⑤格（待做）

| 格 | 产出 | 要点 |
| --- | --- | --- |
| ③ 前端接上 | 前端能拿到并使用 `conversationId` | `api` 加 `createConversation()`；`GuideChatView` 异步拿 id；`withCredentials` 带 Cookie；SSE 从 `EventSource` 换成 `fetch` + `ReadableStream`（④ 已把聊天接口改成 POST，`EventSource` 只支持 GET） |
| ⑤ 历史恢复 | 会话可回读 | `GET /conversations/{id}/messages`；复用 `assertOwned`（④ 已就位），只做「读得出」 |
