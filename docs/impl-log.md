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

（登录的姊妹接口 `POST /auth/logout` 于 2026-10-20 补上，契约见文末「③格遗留收口」一节。）

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

## 第③格纵切 · 前端接上（2026-10-19 完成，浏览器端到端实测通过）

**产出**：`/guide` 的登录态与会话 id 全部改为「后端权威」；聊天接口从**无鉴权的教学接口** `/ai/love_app/chat/sse`（GET + 原生 `EventSource`）切到 ④格契约 `POST /ai/chat/stream`。

纯前端改动，无后端改动：

| 文件 | 改动 |
| --- | --- |
| `api/chatStream.js`（新增） | `fetch` + `res.body.getReader()` 手工解 SSE 帧，替代 `EventSource` |
| `components/ChatRoom.vue` | 加 `transport` prop；`closeSource()` 按 `source` / `controller` 双分派 |
| `views/GuideChatView.vue` | 删 `sse-path`、加 `transport="post-stream"`；`onMounted` 调 `createConversation('PRACTICE')` 拿后端签发的 id |
| `stores/auth.js`（新增）+ `router/index.js` | 显式登录态标记（`localStorage`）+ `router.beforeEach` 守卫（`meta.requiresAuth`） |
| `views/LoginView.vue` | 登录成功后 `setAuth()` |

### 核心 1 · `EventSource` 接不了 POST 流（换 `fetch` 的四个反直觉点）

④格把聊天接口改成 `POST` + JSON body。原生 `EventSource` **只能发 GET、不能自定义请求头、不能带 body**，于是只能换 `fetch`：

1. **4xx/5xx 不抛异常**——`await fetch()` 照常返回，必须自己判 `res.ok` 再看错误体。
2. **`res.body` 是一次性资源**——要读流就用 `res.body.getReader()`，不能再调 `res.text()`（会抛 `body stream already read`）。
3. **逐片解码要 `decoder.decode(value, { stream: true })`**——UTF-8 一个汉字 3 字节，会被切在两片之间，不加 `stream: true` 会输出 `�`。
4. **取消靠 `AbortController`**（不是 `.close()`）；`AbortError` 是「用户主动取消」，不是错误，要静默收场。

### 核心 2 · SSE 的两层换行（`\n\n` 是帧界，`\n` 是帧内行界）

一帧形如 `data:<文本>\n\n`：`\n\n`（空行）分隔**帧与帧**，单个 `\n` 分隔**帧内的行**。`SseEmitter.send(Object)` 在文本含换行时，会**给每一行都补一个 `data:` 前缀**——AI 输出的 `。\n\n我们…` 因此被编码成 `data:。\ndata:\ndata:\n\n` 三个 data 行。

解帧必须按帧隔离：`buffer` 累积 → `split('\n\n')` → `frames.pop()`（最后一段可能是不完整帧，退回 `buffer` 等下一片）→ 帧内 `split('\n').filter(startsWith('data:')).map(slice(5)).join('\n')`。实测证据：**网络分片边界与帧边界完全无关**（出现过「一片含一整帧 + 半帧」、「一帧被切在 `data:！` 与 `\n\n` 两片之间」），用上述算法还原后与 AI 原文逐字一致。

### 核心 3 · 登录态不能用 Cookie 判断（`stores/auth.js` 存在的理由）

`JSESSIONID` 是 **HttpOnly Cookie**：`document.cookie` 读不到（浏览器仍会自动随请求携带）。更要命的是正确性——**「有 Cookie ≠ 已登录」**：Spring MVC 解析 `HttpSession` 参数时执行 `getSession()`（= `getSession(true)`），**未登录请求也会被创建空会话并下发 `Set-Cookie`**。所以路由守卫只能依赖前端自己的显式标记，不能读 Cookie。

### 预测外问题 1 · 未登录也下发 `JSESSIONID`（记账，本格未修）

- **现象**：无痕窗口未登录访问 `/guide`，Network 里也出现 `Set-Cookie: JSESSIONID`。
- **根因**：`AiChatController.stream` / `ConversationController.create` 的 `HttpSession session` 参数在参数解析阶段按需 `getSession(true)`——**先建空会话、下发 Cookie，之后才走到 `requireUserId` 抛 401**。
- **修法（用户最初拍板「没必要改」，记账不修 → 2026-10-20 已修）**：控制器参数换 `HttpServletRequest`，内部 `request.getSession(false)`——取不到就不创建。见文末「③格遗留收口」。
- **影响面**：不构成越权（空会话无 `userId` 属性），但会让「有没有 Cookie」看起来像登录信号，正是核心 3 要绕开的坑。

### 预测外问题 2 · Vite 端口自增（环境坑，非代码问题）

- **现象**：后端占 8123；前端 `5173` 起不来（被上一次残留的 node 进程占用），Vite **自动退到 5174**，两个端口都能访问到页面，误判成「前端没起」或「起两份」。
- **根因**：Vite `server.strictPort` 默认 `false`，端口被占自动 +1。
- **附带**：Cookie 按域（`localhost`）存储、**不区分端口**，所以 5173 / 5174 共用同一份 `JSESSIONID`。

### 验收（浏览器端到端）

4 条守卫用例通过：无痕访问 `/guide` 立刻跳 `/login` 且 Network 无 `POST /conversations` ／ 登录后可进 `/guide` ／ `localStorage.removeItem('ai-agent-auth')` 后被弹回 ／ `/manus` 同样被拦。功能侧：登录 → `/guide` → 发消息 → 逐字流出正常；落库侧已由 ④格 `t_message` 实证（USER→ASSISTANT 两行、同事务）。

### 本格仍未做（已入 decision-log 待办）

- `POST /auth/logout` 与前端 `clearAuth()`（`setAuth()` 目前没有对应的撤销出口）——**2026-10-20 已修**，见文末「③格遗留收口」
- `HomeView` 顶栏登录态展示——**2026-10-20 已修**，见文末「③格遗留收口」

***

## 第⑤格纵切 · 历史恢复（2026-10-20 完成，本格 8/8 绿 + 回归 12/12 绿）

**产出**：`GET /api/conversations`（当前用户自己的会话列表，按「最近活动」倒序，`title` 由首条 USER 消息派生）；`GET /api/conversations/{conversationId}/messages`（某会话的全部消息，`id` 序）；顺带给 `GlobalExceptionHandler` 补一条 `MethodArgumentTypeMismatchException` → 400（本项目第一次出现**路径变量**）。

契约（唯一真理源）：[`api-conversation-history.md`](./api-conversation-history.md)。

新增：`model.ConversationRow` / `model.MessageRow`（record）；`ConversationController` 追加 `list` / `messages`；`ConversationService.list`、`MessageService.listByConversation`；`ConversationRepository.findAllByUserId` / `touch`、`MessageRepository.findAllByConversation`；`ConversationKeys` 追加 `CONVERSATIONS` / `MESSAGES`。

### 返回样例

```json
// GET /api/conversations —— title 由首条 USER 消息前 50 字派生；无 USER 消息则为 null
{
  "conversations": [
    {
      "conversationId": "0d5a1d0e-3a1b-4d6f-9c2e-8b7f1a2c3d4e",
      "mode": "PRACTICE",
      "title": "我的代码报错了，帮我理一下排查思路",
      "updatedAt": "2026-10-19T13:58:45.123456Z"
    }
  ]
}

// GET /api/conversations/{conversationId}/messages —— 按 id 序（= 对话顺序），不返回 type
{
  "messages": [
    { "messageId": 23, "role": "USER",      "content": "什么是指针的别名？", "createdAt": "2026-10-19T13:58:41.001Z" },
    { "messageId": 24, "role": "ASSISTANT", "content": "指针的别名是引用。", "createdAt": "2026-10-19T13:58:45.123Z" }
  ]
}
```

（时间字段是 `TIMESTAMPTZ` → 带偏移的 ISO-8601。上面两段是**按契约 §3.2 / §4.2 的形状示意**；真实抓包见下一节。）

### curl 端到端实测（2026-10-20 08:33，应用跑在 8123，真实数据库）

§4.4 的五条 + 一条补测，全部符合期望。cookie 存临时文件（用完即删）。

| # | 请求 | 实测结果 |
| --- | --- | --- |
| 1 | `POST /api/auth/login` | `200`，`{"userId":"008e1d32-..."}`，响应头 `Set-Cookie: JSESSIONID=...; Path=/api; HttpOnly` |
| 2 | `GET /api/conversations`（带 cookie） | `200`，24 条，按 `updatedAt` **倒序**（`14:44:58` → `14:44:56` → `14:27:12` → … → 10-16 那条在末位）；字段名 `conversationId` / `mode` / `title` / `updatedAt` |
| 3 | `GET /api/conversations/{id}/messages` | `200`，6 条，`messageId` `19→24` **升序**，`USER` 与 `ASSISTANT` 交替 |
| 4 | `GET /api/conversations/abc/messages` | `400` + `{"message":"conversationId 不合法"}`（**body 有文案，非空**） |
| 5 | `GET /api/conversations`（不带 cookie） | `401` + `{"message":"未登录"}` |
| 补 | `GET /api/conversations/11111111-.../messages`（不存在） | `403` + `{"message":"会话不存在或无权访问"}` |

**三条实证**（真机证据，不是推测）：

1. **同一事务两行 `created_at` 完全相同**：第 3 条里 `messageId` 19 与 20 的 `createdAt` 都是 `2026-10-19T13:16:20.489572Z`（21/22、23/24 亦然）。这实证了 §6 那条「消息按 `id` 排序、不按 `created_at`」——按时间排序在这份数据上确实不稳定。
2. **AI 正文的换行结构完整往返**：第 3 条的 `content` 里是 `你好！很高兴见到你。\n\n我们先从…`，说明④格 SSE 的「每行加 `data:` 前缀」与③格前端的「帧内 `data:` 行拼接」两端是对齐的。
3. **未登录也下发 `JSESSIONID`**：第 5 条那条 401 的响应头里同样带 `Set-Cookie: JSESSIONID=...`，③格记录在案的已知问题再次复现（不影响功能，当时为待办）——**2026-10-20 已修**，见文末「③格遗留收口」。

### 测试 8 条新增全绿

`ConversationControllerTest` 追加：list 未登录 401 / 只返回本用户会话（另一用户那条**不出现**、`updatedAt` 非 null）/ `updated_at DESC` 排序（**显式 `UPDATE t_conversation SET updated_at = ...`** 造两个可区分的值）/ `title` 派生（>50 字截到 50 字、长度恰为 50，无消息为 `null`）/ messages 未登录 401 / **别人的会话**与**不存在的随机 UUID**均 403 且 `$.message` **逐字同**「会话不存在或无权访问」（归属校验看门狗）/ 按④格方式落一轮消息后 `USER → ASSISTANT`、`content` 一致 / `abc` 非 UUID → 400 `{"message":"conversationId 不合法"}`。**每条错误响应都断言了 `$.message`**（不重蹈「假验收」）。

回归 12 条仍绿（`AuthControllerTest` 3 + `ConversationControllerTest` 原 5 + `MessageServiceTest` 1 + `ChatServiceTest` 1 + `ConversationRepositoryTest` 2），合计 **20/20**。

### 版本化修订（不覆盖 api-conversation-history.md 原记录）

1. **列表排序键：`created_at` → `updated_at`**。`t_conversation.updated_at` 现由 `MessageService.appendRound` 在两条消息落库后调 `ConversationRepository.touch(conversationId)` 维护（SQL：`update t_conversation set updated_at = now() where id = ?`；`now()` 取的是「事务开始时间」，同一事务内多次调用返回同一个值）。**理由**：原否决「按 `updated_at` 排序」的依据是"没有代码维护它"，现在有维护者了 —— 让「按最近活动排序」成立。**否决**「把这条 UPDATE 塞进 `MessageRepository.insert`」：那会让管 `t_message` 的仓库去改 `t_conversation`（职责越界），且每条消息各更新一次。
2. **列表响应时间字段：`createdAt` → `updatedAt`**。**理由**：排序依据与显示依据必须一致 —— 最新活动的会话排第一行，那行就该显示它的最近活动时间；且未聊过的会话 `updated_at` 等于 `created_at`，一个字段同时覆盖"创建时间"与"最近活动时间"两种语义。**否决**「保持 `createdAt`」（排第一的行显示更早的时间）与「两个都给」（多一个前端用不到的字段进契约，改起来更贵）。

### 与文档的微偏差

- `ConversationKeys.CONVERSATIONS` 曾声明成 `Object`（契约 §5 写的是 `String`）——**收尾时已改为 `String`**。`Map.of` 接受 `Object`，所以这个笔误不影响功能，但类型该是 `String`。
- `ConversationRepository` 顶部残留一个未使用的 import（`com.alibaba.dashscope.aigc.conversation.Conversation`）——**收尾时已删除**。

### 已知坑（记账，不修）

- `t_message.conversation_id` **无索引**：派生 `title` 的相关子查询逐会话扫 `t_message`；MVP 数据量下无感，数据量上来再加（转记 resume-assets）。
- `updated_at` **只在 `MessageService.appendRound` 里更新**：将来别的"写消息"入口（复习闭环的 `FEEDBACK`、手工导入历史等）必须也调 `ConversationRepository.touch`，否则列表排序会漏掉这类活动（转记 resume-assets）。
- e2e curl（§4.4 五条）：本格收尾时未在本地跑 → **2026-10-20 08:33 已补跑，见上面「curl 端到端实测」**。

### 前端侧（`/guide` 左侧栏：历史会话列表 → 点开 → 接着聊）

归 [`frontend-learning.md`](./frontend-learning.md)：**练习 7**（侧栏布局 + 会话列表）、**练习 8**（点历史会话 → 灌历史 + 接着聊 + 懒创建），均已写入。

- **推翻的初版**：最初做的是独立 `/conversations` 页 + 首页「我的会话」入口。拍板改为**侧栏**并删掉该页、该路由与首页入口。理由：①用户要「点一下就能接着聊」，独立页面还得先跳回去；②`/guide` 每次进入就建空会话，是库里 22 条空会话的源头，列表放同页才能掐掉；③`/manus` 无会话列表、不落库，侧栏不该做进公共组件 `ChatRoom.vue`。
- **路由**：新增 `/guide/:conversationId`（`name: 'guide-conversation'`），与 `/guide`（`name: 'guide'`，空白新对话态）共用 `GuideChatView.vue`。刷新后停在同一条会话，治③格记在案的「刷新就丢」。
- **默认打开最近一条**：进 `/guide` 不带 id → 取列表第一条（后端已按 `updated_at DESC` 排好）并 `router.replace` 把 id 写进地址栏；列表为空则停在空白态等懒创建。
- **懒创建**：`+ 新对话` 只切空白态、**不建会话**；`ChatRoom.vue` 新增可选 prop `resolveChatId`（返回 `Promise<conversationId>`），仅在 `post-stream` 且 `chatId` 为空时调用 → 父组件 `ensureChatId()` 才发 `POST /conversations`。这正是「每次进聊天页塞一条无消息会话」的源头封堵。
- **历史回灌**：`ChatRoom.vue` 新增可选 prop `historyMessages` + 一个 `immediate` 的 `watch`，把后端 `{messageId, role: USER|ASSISTANT, content, createdAt}` 映射为界面 `{id, role: user|ai, content, time}`；本地新消息的 `msgSeq` 先抬到历史最大 `messageId`，避免 `:key` 与历史行撞号。**换会话是整批替换 `messages.value`，不是追加**。
- **列表刷新**：`ChatRoom.vue` 新增 `done` 事件（挂在 `finish()` 里，正常完成/出错/被停止都发），父组件 `onRoundDone()` 刷新侧栏 → 刚聊过的会话冒到最上面、`title` 从 `null` 变为首条消息前 50 字。
- **`title` 兜底**：侧栏对 `title === null` 显示「（暂无内容）」（那些是③格遗留的空会话）。

**未做**：侧栏的删除 / 重命名 / 搜索；消息分页；「切会话时让上一轮的流式请求优雅收尾」（现在换会话直接断开，不报错、不落半截）。

***

## ③格遗留收口 · 登出与空会话（2026-10-20 完成，新增 2 条测试，全量 27/27 绿）

**产出**：①`POST /api/auth/logout`（204 幂等）+ 前端 `clearAuth()` 与顶栏登出按钮；②`HomeView` 顶栏显示登录身份；③控制器不再用参数式 `HttpSession`，未登录请求不再被建出空会话。

### 契约 `POST /api/auth/logout`（落位在①格登录契约旁）

| 项 | 结论 |
| --- | --- |
| 请求 | 无 body、无参数 |
| 成功 | `204 No Content`，**无响应体** |
| 未登录调用 | **同样 `204`**（幂等） |
| 失败态 | 无 —— 登出不存在"失败"，也没有需要区分的两种结果 |

**拍板理由（用户拍板「204 幂等」）**：登出本就不该有失败态，重复调用天然安全；不需要靠 `401` 去区分「本来就已登出」——那个信息对调用方没有任何用处，只会逼前端多写一个分支。**否决**「200 + body（回显"已登出"）」：204 已经表达了全部语义，加 body 只是给前端多一个没用的字段。

**分层归属**：`session.invalidate()` 是「写 session」→ 归 Controller（①格分层表里 Controller 的职责就是"收 JSON、定状态码、写 session"）；`AuthService` **不加** `logout` 方法 —— 它是纯 HTTP/会话语义，Service 层没有需要决策的东西，硬塞进去只会得到一个空实现。

### 消除「未登录也下发空会话 JSESSIONID」（③格预测外问题 1，已修）

| 文件 | 改动 |
| --- | --- |
| `web/CurrentUserResolver.requireUserId` | 参数 `HttpSession` → `HttpServletRequest`，内部 `request.getSession(false)`；`null` → `UnauthorizedException("未登录")` |
| `controller/AiChatController.stream` | 参数同步改为 `HttpServletRequest` |
| `controller/ConversationController.create / list / messages` | 3 处参数同步改为 `HttpServletRequest` |
| `controller/AuthController.login` | **保留**参数式 `HttpSession` —— 它要写 session 属性，这里必须 `getSession(true)` |
| `controller/AuthController.logout` | 用 `HttpServletRequest` + `getSession(false)`：若仍用参数式 `HttpSession`，未登录的登出会 `getSession(true)` **凭空建出一个新会话**，"登出"反而登出出一个新 `JSESSIONID` |

**根因一句话**：Spring MVC 解析**参数式** `HttpSession` 时执行 `getSession(true)` —— 先建空会话、下发 Cookie，之后才走到 `requireUserId` 抛 401。改成 `request.getSession(false)` 后，取不到会话就 `null`，**既不创建也不下发 Cookie**。

### 测试（新增 2 条 + 2 条老用例补断言）

- `AuthControllerTest.logout_ok`：204 + **响应体为空** + `result.getRequest().getSession(false)` 为 `null`。第三条才是"非假验收"的那条 —— 它证明 `session.invalidate()` **真的执行了**，而不是只回了个 204。
- `AuthControllerTest.logout_withoutLogin_isStillNoContentAndCreatesNoSession`：未登录 → 204，且会话对象为 `null`。**看门狗**：谁把 `logout` 的参数改回参数式 `HttpSession`，这条立刻红。
- `ConversationControllerTest.create_unauthorized` / `list_unauthorized` **各追加一条 `assertNull(getSession(false))`**：未登录请求不应被建出空会话。
- 说明：**MockMvc 测不到"容器不下发 `Set-Cookie`"**（`Cookies = []`，①格已记档）。在 MockMvc 里唯一可观测的代理就是"请求上有没有会话对象"，所以断言落在 `getSession(false)` 上。真机上可证伪的验收是**响应头 `Set-Cookie` 从「有」变「无」**，由人工实测覆盖。

### 前端改动

| 文件 | 改动 |
| --- | --- |
| `stores/auth.js` | 新增 `clearAuth()`（`setAuth` 的撤销出口）；新增 `currentUsername` ref + `localStorage` 的 `ai-agent-username` |
| `api/auth.js` | 新增 `logout = () => request.post('/auth/logout')` |
| `views/LoginView.vue` | 登录成功后 `setAuth(username.value)`（传 username 只为界面显示"我是谁"） |
| `views/HomeView.vue` | 顶栏按 `isAuthenticated` 切换：已登录 → 显示 `currentUsername` + 「登出」按钮；未登录 → 「登录」链接 |
| `router/index.js` | **未改** —— 守卫已按 `isAuthenticated.value` 判断，`clearAuth()` 把它置 `false`，守卫无需感知登出动作 |

**取舍**：`currentUsername` 是**纯显示缓存**，不参与任何身份判定（身份权威仍在服务端 session）；丢了也不影响鉴权，最坏是顶栏少显示一个名字。**否决**「把 `userId` 存下来显示」：UUID 对人没有意义，且会让人误以为它是第二份身份真相。

前端侧已归 [`frontend-learning.md`](./frontend-learning.md)：**练习 9**（登出 + 顶栏显示「我是谁」）。

**登出失败怎么办**：`logout()` 抛错时**照样执行 `clearAuth()`**（`finally` 里调用）。理由：登出接口已经是幂等的 204，若因为网络抖动把用户卡在"已登录"界面，反而是更坏的结果；服务端 session 最坏靠超时兜底。

### 实测（人工，用户自己终端起 8123 / 5173）

- 未登录访问 `/api/conversations` 的响应头：`Set-Cookie` 从「有」变「无」（③格预测外问题 1 的可证伪验收）。
- 登录 → 登出 → 再访问受保护接口 → 401。
- 首页顶栏：登录后显示用户名 + 「登出」；点登出后立刻变回「登录」链接，刷新仍是未登录态。

***

## 前端入口合并（2026-10-20，非纵切格）

**结论**：主页原来的两个入口合二为一，只留 `/guide`（AI 问题引导解决助手）；「AI 超级智能体」入口 `/manus` 下线。

**依据**：两个入口能力重叠但都不是"最全"——`/guide` 底座最全（登录 / 归属 / `t_conversation` / `t_message` 落库 / 历史侧栏 / 懒创建），`/manus` 能力最全（`LiManus` ReAct 循环 + 7 个工具）。合并有两条路：

| | 路 A（选定） | 路 B（记为待办） |
| --- | --- | --- |
| 做法 | 只留 `/guide`，下线 `/manus` 入口 | 把 `LiManus` 的 ReAct + 工具调用接进 `/guide` |
| 工作量 | 前端一处改动 + 零后端改动 | 等于新增一格纵切（新契约 + 归属 + 落库 + 记忆换源） |
| 失掉什么 | 界面上暂时看不到工具调用步骤 | — |

选路 A 的理由：符合"快速方便"，且 `LiManus` 的能力**没有消失**——后端 `/api/ai/manus/chat`、`LiManus`、`agent/`、`tools/` 全部保留，等主干之后要做工具调用时按路 B 一格接进去（`tools/` 同时也是主干「资料接入第二层」的原料）。**否决**路 C（反向只留 `/manus`）：那要补回登录、归属、`t_conversation`/`t_message` 落库、历史侧栏、服务端签发 id，等于把前面 5 格重做一遍。

**改动清单（前端，零后端改动）**

| 文件 | 改动 |
| --- | --- |
| `views/HomeView.vue` | 双卡 → 单卡：删第二张卡、`VS` 分隔符、`card.manus` / `.divider` / `.num` 样式，`grid-template-columns` 改单列 |
| `router/index.js` | 删 `/manus` 路由 |
| `views/ManusChatView.vue` | 删除 |
| `utils/chatId.js` | 删除（只有 `ManusChatView` 用） |
| `api/sse.js`、`api/chat.js` | 删除（零引用） |
| `components/ChatRoom.vue` | 删 `event-source` 分支 + `ssePath` / `transport` 两个 prop，`closeSource` 只保留 `controller.abort()`；组件现在只支持 `post-stream` |
| `views/GuideChatView.vue` | 去掉 `transport="post-stream"`（已是唯一传输） |

**可复用的教训**：`/manus` 是唯一走原生 `EventSource` + 前端自报 `chatId` 的入口，入口一撤整条链路就没有调用方，所以能一次清干净。**顺序必须是"先断引用、再删文件"**——若先删 `api/sse.js`，`ChatRoom.vue` 的 import 会直接把构建打断（实测确认：删完跑 `vite build` 通过，且 `ManusChatView` 的 chunk 从产物中消失）。

**验收**：`vite build` 通过；`/` 只剩一张卡；访问 `/manus` 不再匹配任何路由（`router/index.js` 没有 catch-all，会落到空白页 + 控制台 `No match found` 警告）；`/guide` 聊天、侧栏、懒创建、登出全部照旧。

***

## 首页视觉打磨（2026-10-20，只改 HomeView.vue 的 style）

**起因**：入口合并成单卡后，卡片被拉伸到全宽、文案靠左、右侧大片留白，整页不像"一个入口"，像"没做完的双栏"。

**三处根因**（不是审美问题，是布局写法的必然结果）

| 现象 | 根因 |
| --- | --- |
| 卡片占满整屏宽、内容靠左 | 卡片没有 `max-width`；`.card-inner` 只 `justify-content: center`（纵向），没有 `align-items` / `text-align`（横向） |
| 顶栏左右留白与卡片对不齐 | 顶栏 `padding: 18px 30px`、内容区 `18px`，两套留白互相独立 |
| 垂直方向没居中 | `.grid` 是 `display: grid` + `grid-template-columns: 1fr`，行高由卡片 `min-height: 0` 撑开，没有"在剩余空间里居中"的约束 |

**改法（`HomeView.vue` 的 `<style scoped>` 整体重写）**

| 项 | 做法 | 理由 |
| --- | --- | --- |
| 设计令牌 | 颜色 / 圆角 / 两层阴影 / 缓动曲线收进 `.home` 的 CSS 变量 | 阴影这类长数值不散落；`--shadow-soft` 与 `--shadow-lift` 名字本身说明了层次 |
| 双轴居中 | `.grid` 改 `flex` + `overflow-y: auto`；`.card` 用 `margin: auto` | **不能用 `align-items: center`**：内容高于容器时顶部会被推出可视区且滚不回来；`margin:auto` 在空间不足时自动塌成 0，溢出安全 |
| 滚动归属 | 滚动放 `.grid`，`.home` 设 `overflow: hidden` | 放 `.home` 会让顶栏跟着滚走 |
| 宽度 | 顶栏与卡片共用 `max-width: 1080px` + `margin: 0 auto` | 两边留白同源，视觉才对得齐 |
| 卡片质感 | `border-radius: 26px`；两层阴影（大而淡 + 小而贴边），阴影色用深蓝 `rgba(12,46,70,…)` 而非纯黑 | 纯黑压在浅蓝卡上会发灰发脏 |
| 高度 | `height: clamp(320px, 66vh, 560px)` | 矮屏不挤、高屏不空 |
| 文案居中 | `.card-inner` 加 `align-items: center` + `text-align: center`；`.feats` 加 `justify-content: center`；`<p>` 去掉 `<br />` 改 `max-width: 44ch` 自然换行 | 硬换行在窄屏会出现"半句话孤行" |
| hover | 上浮 4px + 换 `--shadow-lift` + `::after` 白色柔光淡入 + 箭头右移 8px + 标签变亮；`transition` 统一 `cubic-bezier(0.22,1,0.36,1)` | 四层动效（位移/阴影/柔光/内部元素）比只改阴影更有"抬起"感 |
| 焦点 | `.card:focus-visible` 描边 | 必须 `:focus-visible` 而非 `:focus`，否则鼠标点击也留描边 |
| 响应式 | 只留 `≤820px` 与 `≤480px` 两个断点；窄屏隐藏 `.tag`、卡片改 `height: auto` + `min-height` | 断点越少越好维护 |
| 无障碍 | `prefers-reduced-motion: reduce` 下关掉全部 transition/transform | 尊重系统设置 |

**踩坑（预测外）**：hover 规则最初包在 `@media (hover: hover)` 里，浏览器验证时**扫不到这些规则**、误判成"没写 hover"。改为顶层规则。理由：本卡是导航入口，点下去就跳走，触摸端不会留下"粘住的 hover"，包在 media 里没有实际收益。**教训：可验证性也是设计约束——把规则藏在 media 查询里，会让人（和自动化）验证不到。**

**验证（真实浏览器，非推测）**

- `npm run build` 两次通过（hover 改动前后各一次）。
- 620px 视口实测：卡片 `left=20px / width=580px / right=600px`——左右边距各 20px，**水平居中无偏移**；顶栏高约 64px，可用高度约 456px，卡片 top≈88px、底部留白≈52px，上下居中。
- hover 前后 computed style 对比：`transform` 出现 `translateY(-4px)`、`box-shadow` 切到 `--shadow-lift`、`::after` 的 `opacity` 从 0 变 1；`.enter b` 出现 `translateX(8px)`；`.feats li` 背景变 `rgba(255,255,255,0.7)`。
- 控制台：硬刷新后无消息（先前的 `net::ERR_ABORTED localhost:5173` 是刷新动作本身中断旧请求，非页面报错）。
- **未验到的部分**：本机浏览器工具不支持动态改视口，`1440×900 / 834×1112 / 390×844` 三档未实测，仅有 `≤820px / ≤480px` 断点的代码保证，需人工在 DevTools 响应模式过一遍。

**前端侧已归 [`frontend-learning.md`](./frontend-learning.md)：练习 10。**

***

## 登录后回跳（redirect after login，2026-10-20）

**现象**：未登录点首页卡片 → 被送 `/login` → 登录成功 → **回到首页**，要再点一次卡片才进得去。

**根因**：不是"登录没生效"，是**「用户本来想去哪」这条信息被丢了**。第一次改只修了守卫一处，实测又发现**还有第二个丢失点**。

**把用户送去 `/login` 的入口共三处，只有守卫带了目标**

| 入口 | 位置 | 状态 |
| --- | --- | --- |
| 路由守卫（`requiresAuth` 且未登录） | [`router/index.js`](../ai-agent-frontend/src/router/index.js#L38-L43) | 改：`return { name: 'login', query: { redirect: to.fullPath } }` |
| 视图内接口 401 兜底 | [`GuideChatView.vue`](../ai-agent-frontend/src/views/GuideChatView.vue#L69-L78) `toLoginIfUnauthorized` | 改：`push({ name: 'login', query: { redirect: route.fullPath } })` |
| axios 响应拦截器 | [`api/request.js`](../ai-agent-frontend/src/api/request.js#L18-L24) | 未做统一 401 处理，靠各视图自己 catch → 记为待办 |

登录侧 [`LoginView.vue`](../ai-agent-frontend/src/views/LoginView.vue#L59-L84)：`router.push(safeRedirect(route.query.redirect))`，新增 `safeRedirect` 做站内路径白名单。

**白名单的必要性**：`redirect` 是用户可改输入，直接拿它跳转会形成 **open redirect**（`?redirect=//evil.com` 把登录成功的人送去站外）。三条判断里 **`//` 那条才是真坑**——它"以 `/` 开头"能骗过前缀检查，但浏览器按协议相对地址解析成 `https://evil.com`。这与后端「输入校验在 DTO 边界完成」是同一原则的前端对应物。

**踩坑（预测外，本次实测才发现）**：前端登录标记会**残留过期**——localStorage 的 `ai-agent-auth='1'`，而服务端 session 已失效。此时守卫按标记放行（用户确实没被拦），进 `/guide` 后接口 401，才由视图内的兜底把用户踢去登录页。**所以"守卫那半修好"并不等于闭环**：只要还有别的分支往 `/login` 跳，用户照样会被丢回首页。教训：**同一种"把用户踢去登录页"的动作散落多处时，先数清入口再改**，入口清单本身就是验收清单。

**验收（真实浏览器实测，非推断）**

- `npm run build` 通过（改动前后各一次）。
- 未登录点卡片 → 地址栏 `/login?redirect=/guide`；登录页 F5 后参数保留。
- localStorage 残留 `'1'`（标记已登录）→ 直接开 `/guide` → 接口 401 → 地址栏 `/login?redirect=/guide`（走的是第二个入口）；控制台只有预期内的 401，无 JS 崩溃。
- **未验到的部分**：登录成功后的实际落点、`?redirect=//evil.com` 的白名单拦截，这两条都需要账号密码走完整提交流程，由用户自测确认。

**前端侧已归 [`frontend-learning.md`](./frontend-learning.md)：练习 11。**
