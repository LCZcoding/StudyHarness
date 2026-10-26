# 纵切实施记录 · StudyHarness

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

***

## 阶段 1 资料接入（knowledge-items，2026-10-22）

**做了什么**：把「学习材料」落成 `t_knowledge_item`，提供三个入口 —— 粘贴建、文件上传、列表。
上传支持 `.md` / `.txt` / `.pdf`（PDF 仅文字层），落库前统一过 `DocumentTextExtractor`。

| 类 | 职责 |
| --- | --- |
| [`KnowledgeItemController`](../src/main/java/com/lcz/aiagentstudy/controller/KnowledgeItemController.java) | 三个接口；`userId` 只从 session 取，不接受前端自报 |
| [`KnowledgeService`](../src/main/java/com/lcz/aiagentstudy/service/KnowledgeService.java) | 生成 200 字内摘要（失败置 `null` 不阻断保存）、列表、`requireOwned` |
| [`DocumentTextExtractor`](../src/main/java/com/lcz/aiagentstudy/service/DocumentTextExtractor.java) | 字节 → 文本；三条拒绝（扩展名、抽不出字、超 5 万字） |
| [`KnowledgeItemRepository`](../src/main/java/com/lcz/aiagentstudy/repository/KnowledgeItemRepository.java) | `JdbcTemplate` 读写，`RETURNING id` 拿回 UUID |

**关键取舍**

1. **摘要失败不阻断保存**：摘要只服务列表展示，模型挂了不该让用户的材料丢失。
2. **`sourceUrl` 存文件名**：上传接口把 `originalFilename` 写进 `source_url`，让列表能显示「来自 hnsw.md」；本轮不做网页抓取，这一列不承载 URL 语义。
3. **扩展名白名单不看 MIME**：浏览器上报的 `Content-Type` 不可靠，`.docx` 改名成 `.pdf` 也会在解析阶段被挡下。
4. **「抽不出字」与「解析失败」分两条文案**：扫描件走前者，加密/损坏 PDF 走后者。合并会让用户不知道该改什么。
5. **<100 字不按扩展名分文案（有意接受）**：`.md` 太短也复用「没有可提取的文字（PDF 可能是扫描件）」。
   后端不分支，**前端在提交前拦住并提示「至少 100 字」**。

**验收（后端 8123 实跑，`curl.exe` + 真实 session，6/6 通过）**

| # | 用例 | 实测 |
| --- | --- | --- |
| 1 | 粘贴建资料 | `201` + `Location: /knowledge-items/{uuid}` + `preview` + `summary` |
| 2 | 列表 | `200`，3 条，`sourceUrl` 粘贴为 `null`、上传为文件名 |
| 3 | 上传 `.md`（1002 字节） | `201`，`title` 自动取 `hnsw`，`sourceUrl = hnsw.md` |
| 4 | 上传文字版 `.pdf`（781 字符） | `201`，`preview` 为完整英文正文 |
| 5 | 上传 `.docx` | `400 {"message":"只支持 .md / .txt / .pdf"}` |
| 6 | 上传 6 MB `.pdf` | `400 {"message":"文件太大，请控制在 5MB 以内"}` |
| + | 未登录 `GET` | `401 {"message":"未登录"}` |

**两条排除掉的假 bug**

- `tmp/pdf` 下 4 份 PDF 抽取结果都是 1 个换行符。用同版本 iText（9.1.0）写探针直接读同一批文件，结果一致：
  **`PDFGenerationTool` 生成的 PDF 缺少 ToUnicode 映射**，与 `DocumentTextExtractor` 无关。
  验收改用 iText 现场生成、拉丁字体的 PDF，抽取 781 字符正常。
- 公网 `pdf-test.pdf` 是**加密 PDF**，返回「PDF 解析失败，请确认文件未加密且格式正常」是正确分支。

**未验到的部分**

- 扫描件 PDF（按用户要求跳过，空文本分支已由「1 个换行符的 PDF」等价覆盖）。
- 5 万字上限未构造真实超长文档，只做了代码审阅。

**教训**：验收数据本身要先验证。拿一批「看起来像 PDF」的文件直接测，会把生成器的缺陷误记到抽取器头上，
浪费一轮排查。**先确认输入合法，再判断被测代码。**

***

## 阶段 2 出题（quiz/generate，2026-10-22）

**做了什么**：`POST /quiz/generate` 读一份资料 → 模型出 3 个任务 → 建 `SELF_TEST` 会话 → 一条 USER 消息 +
3 条 `ASSISTANT/QUESTION` 消息 → 3 行 `t_question` → 3 行 `CANDIDATE` 知识点。

| 类 | 职责 |
| --- | --- |
| [`QuizController`](../src/main/java/com/lcz/aiagentstudy/controller/QuizController.java) | 唯一入口；响应形状 `model.QuizResponse` 被复习接口共用（阶段 4 抽出） |
| [`QuizService`](../src/main/java/com/lcz/aiagentstudy/service/QuizService.java) | 拼提示词、调模型、校验模型返回（条数 / 字段 / `kind` / 至少 1 个 `EXPLAIN`） |
| [`QuizStore`](../src/main/java/com/lcz/aiagentstudy/service/QuizStore.java) | 唯一的写库点；`@Transactional` 包住「会话 + 消息 + 题目 + 知识点」 |
| [`QuestionRepository`](../src/main/java/com/lcz/aiagentstudy/repository/QuestionRepository.java) | 题目读写，`insertReturningId` 把消息 id 回填 `message_id` |

**关键取舍**

1. **模型调用在事务外**：`QuizService.generate` 先调模型拿到草稿，再交给 `QuizStore.createSelfTest` 写库。
   模型慢，不能让一次 4 秒的调用占着数据库连接。
2. **模型返回不合规就抛异常，不猜**：条数不对、字段缺、`kind` 不在枚举内、没有 `EXPLAIN` 四种情况统一
   `IllegalStateException` → `500`。宁可让用户点重试，也不静默存一份残缺题目（技术债第 1 条）。
3. **材料截断到 12000 字**：一份 5 万字讲义塞进去会顶到上下文上限，而「一次 3 题」本来就覆盖不了整份材料。
4. **`target` 去重靠数据库**：`uq_knowledge_point_user_name` 保证同一用户同名知识点只有一行，代码不查重。
5. **重问优先**：资料下有「`FAIL`/`PARTIAL` 且未补讲」的旧题时，第 1 个任务换成那道题，模型只出新 2 道。

**验收（后端 8123 实跑，8/8 通过，耗时 4411 ms）**

| # | 用例 | 实测 |
| --- | --- | --- |
| 1 | 3 个任务、至少 1 个 `EXPLAIN` | 2 `FACT` + 1 `EXPLAIN` |
| 2 | 题目落库且 `knowledge_item_id` 正确 | 3 行全部指向 `12dda403-...` |
| 3 | 知识点 `CANDIDATE` | 3 行 |
| 4 | 会话与消息序列 | `mode=SELF_TEST`，消息 id 54~57（1 USER + 3 QUESTION） |
| 5 | 不存在 / 无权资料 | `403 {"message":"资料不存在或无权访问"}` |
| 6 | 非法 UUID | `400 {"message":"请求体不合法"}` |
| 7 | 未登录 | `401 {"message":"未登录"}` |
| 8 | 人工审题 | 三题分别命中 m / ef_construction / 对数级复杂度，均不泄露答案，`EXPLAIN` 带听众设定 |

**本轮验不到的**：`retry` 重问分支（需先有一道 `FAIL`/`PARTIAL`，阶段 3 才有判分）；
`VERIFIED` 知识点注入（当前全是 `CANDIDATE`）。两条等阶段 3 跑完补验。

**教训**：模型返回必须当外部输入校验。`entity()` 只是把 JSON 反序列化，管不了「条数对不对」「有没有
`EXPLAIN`」「`kind` 是不是枚举值」，这些都得在 `QuizService.draft` 里自己断言。

## 阶段 2.5 出题评测（2026-10-22，规则 10/10，语义 0 告警）

**做了什么**：把 `QuizPromptEvalTest` 从「字符串锚点」改成三层 —— 规则层硬断言、语义层 AI 裁判、
报告落盘人工复审。裁判用 `dashScopeChatModel`（qwen-plus），出题仍用 `deepSeekChatModel`。

| 轮次 | 规则层 | 泄露 | 超纲 | 缺听众 |
| --- | --- | --- | --- | --- |
| 第一轮（字符串锚点 + 旧 rubric） | 5/10 | — | — | — |
| 第一轮（AI 裁判） | 10/10 | 6 | 1 | 1 |
| 第二轮（按人工复审口径重写 rubric） | 10/10 | 0 | 0 | 0 |

**人工复审的口径修正（用户拍板）**：产品唯一目的是让用户自己输出所学内容。
题面给出前提、现象、反事实**不算泄露**（「如果只用两次握手会引发什么问题」是有效问题，
用户仍要说出问题在哪）；只有替用户说出「要输出的结论 / 数值 / 选择」才算泄露。
第一轮 8 条告警按此标准全部为误报，改的是裁判 rubric，不是出题提示词。

**为什么废弃字符串锚点**：锚点写的是术语（`Proxy` / `布隆过滤器` / `预主密钥`），
术语出现在题面属正常，`contains` 判不了「术语还是结论」，10 份材料误报 3 条。

**取舍**

1. 语义层不硬断言：裁判自身会偏，硬断言会让每次改提示词都被误判卡住。连续两轮人工结论一致后再升级。
2. 裁判换模型：`deepseek` 出题、`qwen-plus` 判题，避免自我偏好。
3. 报告写 `target/quiz-eval-report.md`，不进 git；人工结论只记本文件，避免报告与结论两份真相。

**教训**：评测指标写错比没有评测更危险。第一轮 5/10 看起来像「提示词不行」，
实际是判定规则把术语当答案。**先确认判定标准，再看通过率。**

***

## 阶段 3 作答判分（quiz/attempt + help，2026-10-22）

**做了什么**：`POST /quiz/{questionId}/attempt` 判分落 `t_test_record` + 推进 `t_review_schedule`；
`POST /quiz/{questionId}/help` 先答再标 `help_used`。判分规则与 `gaps` 措辞口径见 `product-spec.md` 第 6 节，
逐条实测见 [`acceptance-log.md`](./acceptance-log.md) 第 5 节（9/9 通过）。

**关键取舍**

1. **判分用 AI，不用字符串匹配**：答案对错是语义问题，`contains` 判不了「换个说法讲对了」。代价是判分不可复现，所以
   `GradingService` 强制返回结构化 `Grading(verdict, feedback, gaps)`，`verdict` 不在枚举内直接抛异常，不猜。
2. **`gaps` 不许给答案**：`SYSTEM_PROMPT` 第 2 条限定「只描述缺了哪一环，可给方向，不给数值/公式/术语定义/结论，不举例」。
   给答案会让补讲的 `PASS` 变成抄写，1→3→7 排期全部失真；真不会走 `/help`（明码标记求助过）。
3. **第 3 次提交在服务端拒绝**：`attempt_no` 由库里的最大序号推导，不信任前端传的顺序；拒绝文案写
   `BadRequestException` 抛出点，全局处理器只转发。
4. **求助不产生成绩**：`help` 只写 `t_question.help_used`，不动 `t_test_record`、不动排期。

**踩坑（预测外，已修）**：`gaps` 初版把 `2m` 这类数值写进了漏洞清单——提示词只约束了 `feedback`，没约束 `gaps`。
修法见技术债第 25 条（已修），复测对照见 `acceptance-log.md` 3-2 与 3-12。

***

## 阶段 4 待办 / 复习 / 进度（todo / review / progress，2026-10-22）

**做了什么**：`GET /todos` 三类待办、`POST /review/start` 启动复习、`GET /progress` 三指标、
`GET /quiz/conversations/{conversationId}` 恢复测验页。逐条实测见 [`acceptance-log.md`](./acceptance-log.md) 第 6 节
（19 条：2026-10-22 首轮 16 条，其中 6-8 暴露「连点两次会复制题」；2026-10-23 加幂等后复测 6-17~6-19 三条全过）。

| 类 | 职责 |
| --- | --- |
| [`TodoRepository`](../src/main/java/com/lcz/aiagentstudy/repository/TodoRepository.java) | 三段查询各管一类：`REVIEW` 一条汇总 / `RETRY` 按资料聚合 / `UNTESTED` 按会话聚合 |
| [`TodoService`](../src/main/java/com/lcz/aiagentstudy/service/TodoService.java) | 三类合并 + 固定排序（`REVIEW` → `RETRY` → `UNTESTED`） |
| [`ProgressService`](../src/main/java/com/lcz/aiagentstudy/service/ProgressService.java) | `masteredItems` / `streakDays` / 近 8 周活跃 |
| [`QuizStore`](../src/main/java/com/lcz/aiagentstudy/service/QuizStore.java) 追加 `createReview` | 到期题**复制新行**并迁移排期，旧题保留作历史 |
| [`model.QuizResponse`](../src/main/java/com/lcz/aiagentstudy/model/QuizResponse.java) | `/quiz/generate` 与 `/review/start` 共用响应形状 |

**关键取舍**

1. **复习复制题、不改旧题**：`t_question` 是历史记录（当时问了什么、答了什么），复习是新一轮作答，只能新增行。
   旧排期行迁到新题（`ReviewScheduleRepository.moveQuestion` 只改 `question_id`，`due_at` 刻意不动），
   旧题变成「有历史、无排期」。
2. **`/review/start` 做幂等**：先查最新一条 `mode='REVIEW'` 会话，其中有未作答题就把那批题原样返回，不再新建。
   原始实现在连点两次时会把同一批到期题复制一遍（`acceptance-log.md` 6-8），复制题撑大 `countMasteredItems` 的分母、
   且旧题彻底失去排期。修复后实测 6-17~6-19：连点两次同一 `conversationId`、`t_question` 行数不变；
   答 1 道再点只返回剩下 2 道。代价是「放弃的那一轮」没有出口，见技术债第 26 条。
3. **只认最新一条 `REVIEW` 会话**，不认所有未答完的：否则几个月前遗留的一轮会永远劫持「开始复习」，用户再也开不了新一轮。
4. **交错题用 `order by random()` 抽另一份资料**：同一用户资料多于两份时两次复习抽到的不一样，不是缺陷，
   但演示时不可复现（`acceptance-log.md` 观察 2）。
5. **响应形状不挂在 Controller 上**：`TodoController` 曾直接用 `QuizController.GenerateResponse`，形成 Controller 反向依赖。
   改为两个 Controller 都返回 `model.QuizResponse.of(...)`；恢复接口多一个 `verdict`，形状不同，单独定义 `ConversationQuizResponse`。
6. **`dueCount` 与待办同源**：`/progress.dueCount` 直接数 `findDue` 的结果，不另写一条 `count(*)`，
   避免两个接口对「到期」的定义各写一遍。

**教训**：连点两次的幂等性要在设计时明写前置条件，并且**幂等分支必须排在「判断有没有到期项」之前**。
7.10 步骤 3 原先的期望没错，但把「没有到期项」当成了起点；实现里刚启动的复习题仍然到期，
于是一条命令既可能是 `200` 也可能是 `400`，取决于猜不到的历史状态。修复后 `400` 只在「这一轮答完且无到期题」时出现。

***

## 阶段 6 收尾：验收补测与缺陷修复（2026-10-24）

### 1. 长章节向量化超 token 上限（已修）

**现象**：前端上传《JUC》8 章，其中 3 章（11988 / 11960 / 9596 字）各报一次
`Tokens in a single document exceeds the maximum number of allowed input tokens`，
资料落库但没有任何向量（走 `KnowledgeService.indexQuietly` 的 warn 分支，接口仍返回 201）。

**根因**：`indexQuietly` 把整章正文当成一个 `Document` 交给 `vectorStore.add()`。
DashScope embedding 单条输入上限 8192 token，中文约等于 1 字 1 token，所以超过 8000 字的章节必挂。
章节切分（8000~12000 字）服务于展示与出题，embedding 切块服务于召回，两者粒度本来就该不同。

**改法**（[`KnowledgeService`](../src/main/java/com/lcz/aiagentstudy/service/KnowledgeService.java)）：新增 `toEmbeddingChunks`

1. 按 1000 字一块，切点优先落在换行/空格处，避免把句子劈开。
2. `vector_store.id` 是 uuid 列，一章多块必须各用独立 UUID，不能沿用 `knowledgeItemId`。
3. 每块 metadata 带 `userId` / `title` / `knowledgeItemId` / `chunkSeq`：前两个供 `filterExpression` 用，
   后两个供「按章节回溯删除/回填」用。
4. DashScope embedding 单次请求最多 10 条文本，按 10 条一批提交。

**实测**：`delete from vector_store where metadata->>'userId'='008e1d32…'`、`delete from t_knowledge_item where doc_title='JUC'`
后重新上传 → `vector_store` 71 行，各章块数 13/13/13/10/13/10/5/6，正好等于 `ceil(字数/1000)`，无 WARN。
RAG 追问能引用该章原句，见 [`acceptance-log.md`](./acceptance-log.md) 6-16 / 6-17。

### 2. 两个失效测试挡住 testCompile（已修）

`mvn spring-boot:run` 会先编译测试代码：`LoveAppTest` 仍在调已被注释的 `doChatWithReport` / `doChatWithRag`，
`ChatServiceTest` 仍在用 2 参数的 `stream`，导致后端起不来。删掉两个失效用例、把 `ChatServiceTest` 改成 5 参数并同步 mock。
这一步同时解除了 `GradingEvalTest` 的编译阻塞。

### 3. 上下文预算验收（已验，6-18 / 6-19）

| 预算 | 会话 | 发送量 | 日志里的裁剪结果 |
| --- | --- | --- | --- |
| 8000 | `c3e1f1c1` | 12 轮 × 750 字 | 裁剪 5 次：`13/14 条，7701 字符`、`12/16 条，7758`、`11/18 条，7252`、`11/20 条，7436`、`11/22 条，7647` |
| 500 | `adb5c282` | 3 轮 × 750 字 | `1/2 条，430 字符`、`1/4 条，276 字符` |

8000 下保留量贴着预算，500 下掉到 1 条，证明配置真的注入，不是恰好没触发。
验完已把 `app.ai.context.max-history-chars` 改回 8000 并重启。裁剪日志是 `log.debug`，
`application.yml` 里已配 `com.lcz.aiagentstudy.chatmemory: DEBUG`。

### 4. 判分评测（`GradingEvalTest`）

`mvn -Dtest=GradingEvalTest test`：`Tests run: 1, Failures: 0`、`BUILD SUCCESS`；
控制台 `稳定 5/6，与人工标注一致 4/6，不稳定=[HNSW 的分层结构 / PASS]`。只到断言阈值下沿，属模型能力边界。

### 5. 剩余待办（按执行顺序，完成一步即回填本节）

1. ~~`PASSED` 三态~~（2026-10-24 完成）：JUC 第 1 章 `7a7acf52…`（704 字）出题 25/26/27，
   三题各答一次全 `PASS`（`attemptNo=1`、`gaps=[]`），列表里该章 `PASSED`、JUC `passedCount=1`；
   同文档第 7 章 3 题未答为 `IN_PROGRESS`，三态一次覆盖。见 [`acceptance-log.md`](./acceptance-log.md) 1-20。
2. ~~`DocumentSplitter.chunks()` 均分切块~~（2026-10-24 完成）：`chunks()` 改为「先算剩余要分几段，再按均分长度找句末标点下刀」。
   复测：两节 × 12220 字的上传从「12000 + 220 + 220 残段」变成 `6106 / 6136 / 6097 / 6136` 四章；
   31 节 × 12220 字仍按预期 `400 文档切分后超过 30 章（当前 62 章）`。见 [`acceptance-log.md`](./acceptance-log.md) 1-21。
   **注意**：改完必须重启后端才生效，本次第一次上传仍走旧字节码，切出了 11999 + 234。
3. ~~阶段 5 材料截断~~（2026-10-24 完成）：JUC 第 1 章（704 字）与第 2 章（11988 字）各跑一次 `/practice/guide`，
   `t_ai_call` 的 `prompt_tokens` 为 `679` / `4526`，长章只多 6.7 倍而非 17 倍，与 `MATERIAL_LIMIT=8000` 截断吻合。
   见 [`acceptance-log.md`](./acceptance-log.md) 5-9。
4. ~~失败调用落行~~（2026-10-24 完成）：新增 [`FailedCallRecordingTest`](../src/test/java/com/lcz/aiagentstudy/service/FailedCallRecordingTest.java)，
   mock 掉 `deepSeekChatModel` 抛 `RuntimeException`，走真实 `PracticeService.guide`，
   断言 `t_ai_call` 落 `success=false` / `error_code=RuntimeException` / `prompt_tokens=null`。`Tests run: 1, Failures: 0`。
   踩坑：容器里有 dashScope / deepSeek / ollama 三个 `ChatModel`，`@MockitoBean` 必须写 `name = "deepSeekChatModel"` 才能选中。
   见 [`acceptance-log.md`](./acceptance-log.md) 6-20。
5. ~~`RagEvalTest`~~（2026-10-24 完成）：语料其实硬编码在测试类里（5 条 + 5 问），写入独立 `rag-eval-user`、跑完 `delete`，
   与线上库无关，之前的「必须换语料」判断有误。直接跑：`RAG 评测：命中 5/5`，`BUILD SUCCESS`；跑完 `vector_store` 仍 71 行 / 1 个用户。
   见 [`acceptance-log.md`](./acceptance-log.md) 7-5。
6. ~~脏数据~~（2026-10-24 完成）：按 13.4 的外键顺序清空 `lcz` 名下全部测试资料：
   `t_test_record` 12 行、`t_review_schedule` 8 行、`t_question` 27 行、`t_message` 27 行、`t_knowledge_item` 34 行、`vector_store` 71 行。
   清完 `items=0 / vectors=0 / questions=0`，等用户重新上传《JUC》。
7. ~~复习轮次的跳过出口~~（2026-10-24 完成）：`t_question` 加 `skipped` 列，新增 `POST /quiz/{questionId}/skip`。
   `QuizService.skip` 校验归属 → 已作答报 400 → 已跳过报 400 → 标 `skipped` → 有排期才 `postpone` 1 天；
   `QuestionRepository.findUnanswered` 排除 skipped，会话查询把跳过题返回 `verdict="SKIPPED"`。
   实测：跳过 q31/q32 后本轮未答列表清空，再点 `/review/start` 得到 `400 现在没有到期需要复习的题`，
   复习循环被打断；`user2` 跳过 `lcz` 的题 403。见 [`acceptance-log.md`](./acceptance-log.md) 6-21 ~ 6-24。
   坑：`startReview` 会用 `moveQuestion` 把排期从旧题迁到新题，所以「跳过新题」等价于「推迟那次到期」，
   不需要再去改源题。重复跳过守卫写完还没重启验证过。
8. `PRACTICE` 会话后续追问走练习提示词（技术债 13，用户决定不改）。
9. ~~前端资料页按「文档 → 章节」重写~~（2026-10-24 完成）：同轮把待办、进度、测验、对话四页也统一了视觉，
   见本文件下一节「前端改版」。6 个提交在分支 `feat/frontend-materials`（`ea972d5`…`885f0eb`），待合并。

***

## 前端改版（文档 → 章节两级 + 全站视觉统一，2026-10-24）

**起因**：后端闭环跑通后，前端仍是「单条资料 + 卡片」的旧结构，六个页面各写一套样式（有的胶囊、有的卡片、对话区还是浅蓝）。
用户拍板两件事：资料按「文档 → 章节」组织；视觉参考苹果官网的排版节奏，配色沿用项目既有的深色青色。

**设计约定（六页共用，写在各自根类的 CSS 变量里）**

| 令牌 | 值 | 用途 |
| --- | --- | --- |
| `--bg` / `--surface` | `#0e1116` / `#141b23` | 页面底 / 抬升面 |
| `--ink` / `--ink-soft` / `--ink-dim` | `#eef4fa` / `#8ba3b8` / `#6b7f92` | 三级文字 |
| `--line` | `rgba(148, 163, 184, 0.13)` | 发丝线（替代卡片描边） |
| `--brand` / `--brand-lit` | `#0284c7` / `#22d3ee` | 主色 / 高亮 |
| `--radius-pill` | `999px` | 全部按钮 |
| `--ease` | `cubic-bezier(0.22, 1, 0.36, 1)` | 动效统一 0.2s |

版式：内容列 `max-width: 1040px`、左右 32px（阅读页正文 760px）；章节与行之间用发丝线分隔而不是卡片；
层级只靠字号与字重表达；按钮一律 pill。

**逐页改法**

| 页面 | 改前 | 改后 |
| --- | --- | --- |
| [`MaterialsView.vue`](../ai-agent-frontend/src/views/MaterialsView.vue) | 平铺资料条目 | 文档分组 + 每章「序号 / 标题 / 摘要 / 三态 / 字数」，文档头带通过进度条 |
| [`SectionReadView.vue`](../ai-agent-frontend/src/views/SectionReadView.vue)（新增） | 无 | 760px 正文列、去掉 `##` 标记、上一章/下一章、两个检测入口，底栏 sticky |
| [`TodoView.vue`](../ai-agent-frontend/src/views/TodoView.vue) | 圆角卡片 + 彩色图标块 | 发丝线清单 + 状态圆点 + 一行总览（复习/补讲/未测） |
| [`ProgressView.vue`](../ai-agent-frontend/src/views/ProgressView.vue) | 三张统计卡 + 各自断开的柱底 | 竖线分隔的大字栏；8 周柱图共用一条伪元素基线，柱顶标数值 |
| [`QuizView.vue`](../ai-agent-frontend/src/views/QuizView.vue) | 卡片式题目 | 两位序号分节；判分标签四态（`PASS` / `PARTIAL` / `FAIL` / `SKIPPED`，`SKIPPED` 用中性灰） |
| [`GuideChatView.vue`](../ai-agent-frontend/src/views/GuideChatView.vue) + [`ChatRoom.vue`](../ai-agent-frontend/src/components/ChatRoom.vue) | 浅蓝聊天区 + 描边卡片侧栏 | 侧栏改发丝线列表、选中态左缘亮条；`ChatRoom` 的 `theme-guide` 全套令牌改深色 |

**踩坑（四个都是窄屏/边界才暴露的真问题，不是审美偏好）**

| 现象 | 根因 | 改法 |
| --- | --- | --- |
| `/guide` 在 375px 横向溢出，聊天头部按钮被推出视口 | 侧栏固定 280px + 聊天区两列并排，合计超出 375 | `≤640px` 断点把 `.guide-layout` 改 `flex-direction: column`，会话列表转顶部横向条（`max-height: 150px`） |
| 阅读页底栏有正文文字透上来 | `.action-bar` 用 `linear-gradient(rgba(14,17,22,.86), …)` 做玻璃感 | 窄屏改实色 `#0e1116`，桌面保留玻璃 |
| 上一章/下一章标题互相压字 | `.pager-title { max-width: 220px }` 大于按钮盒子宽度（`max-width: 46%`），子元素溢出父盒 | 按钮改定宽 `46%`，标题改 `max-width: 100%` |
| 聊天头部标题把「把这轮做成题」挤到边界外 | `.title-box` 没设 `min-width: 0`，不收缩 | 窄屏 `.chat-header` 允许换行，右侧操作整行右对齐 |

**验证**

- `npm run build` 每步通过（共 5 次）。
- 桌面：内置浏览器子代理逐页截图核对顶栏、分组、发丝线、气泡与按钮，未发现重叠或截断，控制台无报错。
- 375×812：Playwright 脚本登录 `lcz` 后依次访问 `/materials`、`/todo`、`/progress`、`/guide`、`/quiz/{id}`、`/materials/{id}`，
  六页均 `scrollWidth == clientWidth == 375`，控制台 `errors=[]`。
- 工具边界：浏览器子代理不支持改视口，375px 必须换 Playwright（临时脚本跑完即删，不入库）。

**未验**：真机 iOS Safari / Android Chrome；平板 768~820 区间只靠断点代码保证。

**实施顺序**：资料页 → 章节阅读页 → 待办 → 进度 → 测验 → 对话，每个页面「改完 build + 浏览器实测 + 提交」，
共 6 个提交（`ea972d5`、`41f6543`、`9e86a15`、`93e0c99`、`98b1aec`、`885f0eb`）。

**预测外问题（按规则转记）**：窄屏三处布局缺陷已按上面表格修掉；「子元素 `min-width: 0` 才能让 flex 子项收缩」这条
与⑤格 `side-title` 的踩坑同源，说明这套令牌换页时很容易复现，记为复查项。

**补充（同日）：聊天页补 RAG / 工具开关**

后台早已支持 `useRag` / `useTools`，但前端 [chatStream.js](../ai-agent-frontend/src/api/chatStream.js) 只发
`{conversationId, message}`，两个开关恒为 `false`，等于能力在页面里不可用。改法：

| 位置 | 改动 |
| --- | --- |
| [`chatStream.js`](../ai-agent-frontend/src/api/chatStream.js) | 入参加 `useRag` / `useTools`（默认 `false`），写进请求体 |
| [`ChatRoom.vue`](../ai-agent-frontend/src/components/ChatRoom.vue) | 新增同名副属性（props）透传给 `streamChat`；窄屏 `.h-right` 允许折行 |
| [`GuideChatView.vue`](../ai-agent-frontend/src/views/GuideChatView.vue) | 顶栏加「资料检索」「工具」两个开关按钮：默认灰底空心点，开启后青底实心点，带 `aria-pressed` |

设计取舍：开关放在页面层而不是 `ChatRoom` 内部状态，`ChatRoom` 保持「只负责渲染与会话」的通用职责；
两个开关都是用户显式打开，不做「问题里含资料就不检索」这类意图猜测——猜错比多一次检索更糟。

验证（Playwright，登录 `lcz`，界面操作）：

| 开关 | 关 | 开 |
| --- | --- | --- |
| 资料检索 | 「我这边看不到你上传的资料内容」并反问资料类型 | 按 JUC 资料列锁升级链路、乐观/悲观锁、CAS 与 ABA、`ReentrantLock`、`ThreadLocal` |
| 工具 | 「没有实时时钟，无法告知」 | `2026 年 10 月 24 日 17:31:48` |

两次运行 `console errors` 均为空。附带发现：对话页引用 Google Fonts，离线时 `fonts.gstatic.com` 请求失败会回退系统字体，不影响功能。
接口契约已同步到 [`api-chat-stream.md`](./api-chat-stream.md) §2.1。

**补充（同日）：AI 回答按 markdown 渲染**

改前 `.text-seg` 是 `white-space: pre-wrap` 的纯文本，模型输出的 `**加粗**`、`| 表格 |`、``` 代码块 ``` 全按原文吐出来。

| 位置 | 改动 |
| --- | --- |
| `ai-agent-frontend/src/utils/markdown.js`（新增） | `marked`（GFM + `breaks`）解析 → `DOMPurify.sanitize` 过滤；链接统一补 `target="_blank"` 与 `rel="noopener noreferrer"` |
| [`ChatRoom.vue`](../ai-agent-frontend/src/components/ChatRoom.vue) | 文本段改 `v-html="renderMarkdown(seg.text)"`；`.text-seg.md` 去掉 `pre-wrap`，并补标题 / 列表 / 行内代码 / 代码块 / 表格 / 引用 / 分隔线样式 |

安全取舍：模型输出属于不可信内容，直接 `v-html` 会把 `&lt;img onerror=...&gt;` 变成脚本；因此必须过 DOMPurify，
不能只用 `marked` 自带的 HTML 透传。`Step N` 段落仍按纯文本渲染，保持与最终回答的视觉区分。

验证（Playwright，界面提问要求输出表格 + java 代码块 + 要点）：`.text-seg.md` 内 `table` 1 个、`pre` 1 个、
`strong` 2 个、`li` 2 个，正文不含 `**` 与 `|---` 残留；375px `scrollWidth == clientWidth == 375`；`console errors=[]`。

依赖：`marked@18.1.1`、`dompurify`。`npm audit` 的两条告警来自 devDependency `vite`→`esbuild`（开发服务器跨域读取问题），
与本轮新增依赖无关，且预览/生产构建不使用 dev server，暂不升级。

**补充（同日）：全站改浅色，对齐 ChatGPT / DeepSeek 网页版**

改前全站是深色青蓝主题，用户反馈「还是不好看」，并要求**先去官网取真实样式**而不是凭记忆改。做法是先用浏览器读
`chat.deepseek.com` 与 `chatgpt.com` 的 `getComputedStyle`：页面底色 `rgb(252, 252, 252)`、输入框 768×52 / 28px 圆角 /
`0.8px solid rgba(0, 0, 0, 0.1)` 描边、标题 24px/400。据此定浅色规格再动手。

| 位置 | 改动 |
| --- | --- |
| `styles/global.css` | 新增中性浅色令牌：`--bg #fcfcfc`、`--surface #ffffff`、`--surface-soft #f4f4f5`、`--ink #0d0d0d`、`--ink-soft #565869`、`--ink-dim #8e8ea0`、`--line rgba(0,0,0,0.1)`、`--accent #4d6bfe`；字体改 system-ui 栈，`body` 底色 `#fcfcfc` |
| `components/ChatRoom.vue` | AI 回答**去气泡平铺**（`--ai-bubble: transparent`），用户消息 `#f4f4f5` + 20px 圆角；消息列 768px 居中；输入框 26px 圆角胶囊 + 38px 圆形黑色发送键；顶栏 56px 白底 + 发丝线；在线点改 `#10a37f` |
| `views/GuideChatView.vue` | 侧栏 260px 浅底、条目 hover `#f0f0f2` / 选中 `#ececef`；「资料检索 / 工具」开关改白底描边、开启态黑底白字 |
| `views/MaterialsView.vue` `TodoView.vue` `ProgressView.vue` `QuizView.vue` `SectionReadView.vue` | 令牌改浅；主按钮纯黑底白字、次按钮白底描边；状态点改绿 `#10a37f` / 琥珀 `#d97706` / 红 `#b4341a` / 空 `#d4d4d8`；阅读页正文列 720px、行高 1.85，底栏由半透明渐变改**白色实色** + 上边框 |
| `views/HomeView.vue` `LoginView.vue` | 首页去掉蓝青渐变大卡，改白面 + 发丝描边的入口卡；登录按钮改纯黑胶囊 |
| `index.html` | 移除 Google Fonts（`IBM Plex Mono` / `Noto Serif SC` 已无引用），少一次外部阻塞请求 |

验证（Playwright，登录 `lcz`，6 页 × 2 视口共 14 张截图）：`/`、`/guide`、`/materials`、`/todo`、`/progress`、
`/materials/{id}`（读第 1 章）、`/quiz/{id}`（从待办进入）在 1440×900 与 375×812 下 `scrollWidth == innerWidth`，
`console errors = []`，各页 `.page` 底色均为 `rgb(252, 252, 252)`。

顺手修掉两个改版暴露出来的缺陷：375px 对话页输入框占位文案过长会换行被裁 → 文案缩短为「说出你卡住的地方…」；
375px 阅读页底部栏是两行，正文最后一段会被压住 → `.reader-inner` 底部留白 56px 提到 132px。

**补充（同日）：发送按钮改图标、三个操作收进输入框**

| 位置 | 改动 |
| --- | --- |
| `ChatRoom.vue` | 删除 `header-extra` 插槽，新增 `composer-extra` 插槽，位置在 `.actions` 内、停止/发送按钮之前；顶栏 `.h-right` 只剩会话 ID。窄屏（≤640px）`.composer-box` 改竖排，图标行落到输入框下方整行右对齐 |
| `GuideChatView.vue` | 检索 / 工具 / 做成题 三个按钮由顶栏文字胶囊改为输入框内 34px 图标按钮（375px 下 32px）：关闭态透明底灰图标，开启态黑底白图标；说明文字放 `data-tip`，用 `::after` 在悬停/键盘聚焦时浮出，内容随开关状态变化。删除原 `.opt` / `.make-quiz` 样式 |

为什么用 `::after` 而不是 `title`：`title` 有 1 秒以上延迟、样式不可控；伪元素能即时出现且与全站黑白灰一致。
tooltip 统一右对齐到按钮右边缘，避免最右侧按钮的气泡在窄屏溢出视口。

验收：11-20 / 11-21。

**补充（同日）：查时间常开，工具开关收窄为「联网」**

| 位置 | 改动 |
| --- | --- |
| `ToolRegistration.java` | `chatTools` 拆成两个 Bean：`chatWebTools`（网页搜索 + 网页抓取，受开关控制）与 `chatClockTool`（查时间，常挂）。副作用工具仍不进聊天 |
| `LoveApp.java` | `doChatByStream` 每轮都挂 `chatClockTool`；`useTools=true` 时把 `chatWebTools` 一并挂上 |
| `GuideChatView.vue` | 第一个图标由放大镜改**书本**（资料检索），第二个图标由扳手改**放大镜**（联网 = 网页搜索 + 抓取）；悬停说明同步，并注明「查时间始终可用」 |
| `api-chat-stream.md` §2.1 | `useTools` 语义改为只挂联网工具；补充查时间常开说明 |

为什么把查时间单独拿出来：它不联网、没有副作用、调用成本极低，让用户为了问一句「现在几点」先点开关是多余摩擦；
联网工具则会外发请求、消耗 Tavily 额度，仍需显式开启。

验收：11-22 已通过；11-23 / 11-24 需重启后端后验证。

**实测（2026-10-24 19:38，后端重启后）**

- 11-23 通过：两个开关都不点，问「现在几点」回到 `2026-10-24 19:02:49`。
- 11-24 通过：关闭联网时模型明确说只有 `getCurrentTime`、查不到实时新闻；开启后工具清单变为 `getCurrentTime、searchWeb、scrapeWebPage`，并真实检索到 HeroDevs 那篇 Spring Boot EOL 博客（含链接与 2025-05-22 日期）。
- 中途踩到的坑：在**同一会话**里先让模型说过「我没有搜索工具」，即使随后打开联网，它仍沿用上一轮的自我描述继续否认有搜索能力。
  所以验证工具集变更必须**新建会话**——历史上下文会覆盖本轮实际挂载的工具清单。这不是代码缺陷，但排查时容易误判。

## 项目介绍视频稿

新增 `docs/video-script.md`：约 900 字口播、3 分 40 秒，按「翻完笔记合上书答不上来 → 课堂按大纲走顾不上个人 → AI 答得越好人越懒 → 让 AI 当考官」的顺序立论，
正文依次演示资料切章、题面不泄答案、判分只指缺口、每题 2 次机会、求助优先复习、跳过不算对、资料按用户隔离、两个开关与「做成题」。
技术名词全部留给视频简介，避免观众被框架名劝退。

## 引导词改版：从「助手」到「授人以渔」

| 位置 | 改动 |
| --- | --- |
| `GuideChatView.vue` | 顶栏标题「AI 问题引导解决助手」→「授人以渔」；`ai-name`「引导助手」→「考官」；空态主文案改为「授人以鱼，不如授人以渔」，副文案改为「这里不直接给答案。你把思路讲出来，讲不清的地方回原文重看。」；状态行「在线，随时为你答疑」→「在听，说说你卡在哪」，流式中文案改「正在挑你思路里的漏洞…」 |
| `GuideChatView.vue` 建议提问 | 四条全部改成「我先讲、你来挑」的句式，第一条消息就确立分工，而不是「帮我做」 |
| `HomeView.vue` | 入口卡片标题「授人以渔」、徽标改为学士帽图标、说明「不直接给答案，先给方向。」，特性三条改为资料切章 / 每章 3 题判讲清楚没有 / 答不出的下次复习先问 |
| `QuizView.vue` | 求助抽屉里 AI 说话人标签「引导助手」→「考官」 |
| `ChatRoom.vue` | 新增 `BrandMark.vue` 组件（学士帽描边图标），替换头像位的文字：顶栏头像、空态大图标、每条 AI 消息的 26px 小头像统一用它；AI 说话人名固定「考官」；组件默认 `greeting` 改为「说说你卡在哪」，不再兜底成「我是你的 AI 助手」 |

取舍：产品定位是「不替用户答」，那么文案里出现「助手」就等于自我否定——助手的天职是代劳。说话人名取「考官」而不是「渔」：标题「授人以渔」是给用户看的立意，头像位再用一个「渔」字，第一次进来的人只会以为是个人名或昵称，看不出这是产品标识。图形改成学士帽，是为了让头像在 26px 下也能一眼认出「学习」这件事，而不是靠一个字去猜。

上表中「你把思路讲出来」「我先讲、你来挑」两类表述已在同日修正，见下节。

验收：11-25。

**修正（同日）：定位表述由「让用户先讲」改为「AI 不直接给答案」**

上一版把立场写成了「你先讲、我来挑」，读起来像要求用户主动输出，偏离了实际机制：对话页的系统提示词是「先给方向 → 再给关键词 → 伪代码 → 最小示例 → 完整答案」分级递进（`LoveApp.SYSTEM_PROMPT` 总原则第 3 条），主动权在 AI 手上，用户是被问、被提示的一方。

| 位置 | 改后文案 |
| --- | --- |
| `HomeView.vue` 卡片说明 | 不直接给答案，先给方向。 |
| `GuideChatView.vue` 空态副文案 | 这里不直接给答案。先给你方向，再给关键词，实在卡住才给最小示例。 |
| `GuideChatView.vue` 流式中状态 | 正在想下一步给你什么提示… |
| `GuideChatView.vue` 建议提问 | 线程池核心数怎么定？先别给答案 / 这段报错我怀疑是空指针，从哪查起 / AQS 排队那段没看懂，给个方向就行 / 配置改了没生效，我想要排查顺序 |

验收：11-27。

**命名与定位再次修正：「小思」**

「考官」这个词把关系说反了：这一页的业务是用户提问、AI 不给现成答案、最终让用户搞懂答案是怎么来的，AI 是陪跑的人不是审判的人。统一改成「小思」，并用副文案把业务说清楚。

| 位置 | 改后 |
| --- | --- |
| `GuideChatView.vue` `ai-name` | 考官 → 小思（顶栏、每条 AI 消息的说话人名都取自这里） |
| `ChatRoom.vue` 组件默认 `aiName` | 考官 → 小思，避免其他调用方漏传时又出现旧称呼 |
| `QuizView.vue` 求助抽屉说话人 | 考官 → 小思 |
| `GuideChatView.vue` 空态副文案 | 这里不直接给答案。先给方向，再给关键词，实在卡住才给最小示例——目标是让你搞懂答案是怎么来的。 |

验收：11-28。

## 修复：测验会话混进对话页左侧栏

现象：在资料页点「开始学习」出题后，对话页左侧栏多出一条会话；复习也一样。

原因：`GET /conversations` 只按 `user_id` 过滤，把 `t_conversation` 里 4 种 mode 全返回。出题写入的是 `SELF_TEST`、复习写入的是 `REVIEW`，它们本来是测验页的载体，却被当成聊天会话渲染。实测数据：该用户列表里 `SELF_TEST` 10 条、`REVIEW` 3 条。

改法：`ConversationRepository.findAllByUserId` 的 SQL 加上 `and c.mode in ('FREE_CHAT', 'PRACTICE')`。在 SQL 层过滤而不是前端过滤，理由是列表语义本身就该是「对话」——`SELF_TEST`/`REVIEW` 在任何页面都不会被当聊天打开，留在响应里只会误导所有调用方（含默认打开最近一条会话的逻辑，以前可能随机落到一条测验会话上）。

`PRACTICE` 保留在列表内：它是「怎么练」生成的可追问会话，用户需要能回到它继续提问。

契约同步：`docs/api-conversation-history.md` §3.1、§3.2。

## 修复：测验页的求助回答与判分意见未做 markdown 渲染

现象：在求助抽屉里，AI 回答直接显示 `**副本**`、`` `obj = method(obj)` ``、`- ` 列表符号；判分意见同理。聊天页早已接入 `utils/markdown.js`，这两处漏了。

原因：`QuizView.vue` 里两段 AI 产出的文本都用插值 `{{ }}` 输出，而富文本要走 `v-html` + `renderMarkdown`。

改法：

| 位置 | 改动 |
| --- | --- |
| 求助抽屉 | `m.role === 'me'` 仍用插值（用户手打的字不该被当成 markdown）；AI 回答改为 `.md` + `v-html="renderMarkdown(m.content)"` |
| 判分意见 | `<p class="feedback">{{ … }}</p>` → `<div class="feedback md" v-html="renderMarkdown(…)" />`。必须换成 `div`：渲染后的内容可能含 `<ul>`/`<pre>`，`p` 里塞块级元素会被浏览器拆散 |
| 样式 | 新增 `.md` 作用域样式（段落/标题/加粗/列表/行内代码/代码块/引用/表格/链接），并把 `.md` 的 `white-space` 改回 `normal`——`.bubble` 上的 `pre-wrap` 会让 markdown 的换行失效 |

题面 `.qtext` 保持纯文本：题面是短句，不需要排版。

验收：11-30。

## 修复：求助记录与测验题目不持久

现象两条：① 求助完关掉抽屉再打开，之前的问答没了；② 答到一半退出，再点「开始学习」会重新出题，原题和已答结论都没了。

根因：

- 求助内容**从未落库**。`QuizService.help` 只调 `HelpService.answer` 拿到回答、把 `t_question.help_used` 置 true，问答文本没有任何存储位置，关掉抽屉即丢。
- `POST /quiz/generate` **无幂等**。每次调用都走 `QuizStore.createSelfTest` 新建 `SELF_TEST` 会话，退出再进必然是一轮新题。

改法：

| 位置 | 改动 |
| --- | --- |
| [`db/schema.sql`](../db/schema.sql) | 新增 `t_help_turn`（`question_id` / `role` / `content`）+ `idx_help_turn_question`。单独建表而不复用 `t_message`：求助不是会话问答流的一部分，且必须按 `question_id` 精确回放 |
| [`HelpTurnRepository`](../src/main/java/com/lcz/aiagentstudy/repository/HelpTurnRepository.java) | 新建。`saveTurn` 在一个事务里写 USER + ASSISTANT 两行（只写一半会回放出「有问没答」）；`findByQuestion` 按 id 顺序回放 |
| [`QuestionRepository.findLatestUnfinishedSelfTest`](../src/main/java/com/lcz/aiagentstudy/repository/QuestionRepository.java) | 按「存在未作答且未跳过的题」找这份资料最近一轮未完成的自测 |
| [`QuizService.generate`](../src/main/java/com/lcz/aiagentstudy/service/QuizService.java) | 未完成轮次存在就直接返回它的 `conversationId` 与原题，不调模型、不建会话；全部答完（含跳过）才允许开新一轮 |
| [`QuizService.help`](../src/main/java/com/lcz/aiagentstudy/service/QuizService.java) | 回答生成后落库；新增 `helpHistory` 做归属校验后回放 |
| [`QuizController`](../src/main/java/com/lcz/aiagentstudy/controller/QuizController.java) | 新增 `GET /quiz/{questionId}/help` |
| [`QuizView.vue`](../ai-agent-frontend/src/views/QuizView.vue) | 打开抽屉先拉历史并显示「正在加载之前的求助记录…」；发送失败时撤掉乐观气泡、把问题填回输入框，避免界面留下服务端没有的记录 |

顺带修复：恢复出来的题（只有 `verdict`、没有 `result`）此前不显示「求助」按钮，导致复习时点不了求助。改为求助按钮恒显示，只把「补讲一次」和剩余次数收敛到 `result` 存在时。

数据库变更已在远程库执行（`CREATE TABLE` + 索引，`11 行`表清单确认）。验收记录见 `acceptance-log.md` 11-31 / 11-32。

## 修复：「把这轮对话做成题」报 knowledgeItemId 不能为空（2026-10-24）

**现象**：在 `/guide` 打开任一会话，点输入框里的剪贴板图标，右上角浮出红字「knowledgeItemId 不能为空」；
提示一直挂在那里不消失，用户接着聊天它也还在，没有任何关闭入口。

**根因两条**

1. **出题参数取错了层级**。`POST /api/knowledge-items` 的响应体是 `{ docTitle, sections: [...] }`
   （见 [`KnowledgeItemController.CreateResponse`](../src/main/java/com/lcz/aiagentstudy/controller/KnowledgeItemController.java)），
   **没有顶层 `knowledgeItemId`**。前端 `makeQuiz()` 写成 `createItem(...)` 后直接取 `item.knowledgeItemId`，
   拿到 `undefined`；JSON 序列化时该键被丢掉，服务端 `@NotNull` 校验拒绝，回
   `400 {"message":"knowledgeItemId 不能为空"}`。出题单位本来就是**章节**而不是文档，顶层根本没有这个 id。
2. **报错没有出口**。`makeError` 只在 `makeQuiz()` 开头和成功路径上被清空，失败后既无定时器也无关闭按钮；
   而 `.make-error` 是 `position: fixed` 的右上角浮层，于是「一次失败 = 永久挂一条红字」。

**改法**

| 位置 | 改动 |
| --- | --- |
| `makeQuiz()` 取参 | 改为读 `createItem(...)` 返回的 `sections`，取 `chars` 最大的一章作为出题目标：对话里若出现 `##` 小标题会被 `DocumentSplitter` 切成多章，取最长一章才能保证题目覆盖内容主体 |
| 空章节兜底 | `sections` 为空时提示「这段对话没能整理成可出题的资料」，不再带着 `undefined` 去调 `/quiz/generate` |
| 报错生命周期 | 新增 `showMakeError()`：写入文案并起 6 秒定时器自动清空；新增 `clearMakeError()` 供重试、换会话、组件卸载时调用。所有 `makeError.value = ...` 统一改走 `showMakeError()` |
| 卸载清理 | `onBeforeUnmount` 里 `clearTimeout(errorTimer)`，避免定时器回调去改已销毁组件的状态 |

**顺带修掉：重复点击撞「已存在同名文档」**

改到能取到章节后，第二次点同一个按钮会撞 `createDocument` 的 `BadRequestException("已存在同名文档，请改名后重新上传")`——
一轮对话按首条 USER 消息前 50 字定标题，标题天然不变。改法：先 `listDocuments()` 按 `docTitle` 找同名资料，
命中就复用它的 `sections`，没命中才 `createItem`。这样既躲开同名校验，也让「同一资料 = 同一轮题」的幂等口径成立。

**验证**（Playwright，登录 `lcz`，1440×900）

| 路径 | 结果 |
| --- | --- |
| 正常点击 | `/guide/e7ddf17b…` 点做成题 → 跳 `/quiz/4a1517c4-74a4-4389-b30c-f90bb81a3031`，3 道题（2 `FACT` + 1 `EXPLAIN`），`console errors = []` |
| 再点一次 | 复用已建资料 + `/quiz/generate` 幂等，不新建文档、不新建会话，无报错 |
| 失败路径 | 拦截 `/api/knowledge-items` 返回 500：提示「服务暂时不可用」在第 3 秒仍在、第 7 秒已消失，地址栏未离开 `/guide/...` |

资料总数 6 份（JUC 4 份 + 对话生成的 2 份，各 1 章），确认复用分支不会重复建文档。`npm run build` 通过。
验收记录见 [`acceptance-log.md`](./acceptance-log.md) 11-33 / 11-34。

**教训**：前端跨两个接口串联时，第二个接口的入参必须从第一个接口**实际返回的结构**里取，而不是从「它应该会返回什么」里取。
这类字段名对不上的错误不会在编译期暴露，只会在运行期以一句后端校验文案的形式出现——所以接口注释里写清响应形状、
调用方照着注释写，比事后靠报错反查便宜得多。另外，任何「失败才出现」的提示都必须自带消失机制，
否则它就成了页面上的常驻噪声，反而遮住真正的问题。

## 对话可观测性：事件化流式（工具调用 + RAG 来源，2026-10-26）

设计稿见 [`impl-chat-trace.md`](./impl-chat-trace.md)，本格按它实施 B1~B6 + F1~F4。

**产出**

| 层 | 文件 | 改动 |
| --- | --- | --- |
| model | `ChatEvent` | `sealed interface` + 5 个嵌套 record（`Delta`/`Sources`/`Tool`/`Done`/`Error`）+ `Source`；`type()` 是帧名，前后端唯一的契约字符串 |
| tools | `ToolTraceCallback` | `ToolCallback` 装饰器：start/end 两帧共享 `id`，参数/结果按 400 字截断（只截展示副本，模型拿到的仍是全文）；`ToolDefinition` 原样透出，否则模型看到的 schema 就变了 |
| app | `LoveApp.doChatByStream(5 参)` | 返回 `Flux<ChatEvent>`；工具套装饰器；`Sinks` 旁路 + `merge` 合流；来源在开流前检索一次，`concat` 保证 `sources` 早于任何 `delta` |
| service | `ChatService.stream` | `Flux<ChatEvent>`；只把 `Delta` 追加进 `StringBuilder`；`concatWith(Flux.defer(...))` 让「落库 → done」有顺序，落库失败走 `onError` |
| controller | `AiChatController` | 注入 `ObjectMapper`；`writeValueAsString` 后 `emitter.send(name, json)`，保证 `data` 是单行；错误分支先发 `error` 再 `complete()` |
| 前端 | `chatStream.js` | 从「挑 `data:` 行」升级为「读 `event:` + 每帧 `JSON.parse` 一次」；保留旧后端的降级分支 |
| 前端 | `ChatRoom.vue` | 消息加 `sources/tools/errorText`；`upsertTool` 按 id 合并 start/end；工具卡片在气泡上方、来源在下方；`finish()` 幂等（`done` 帧与流关闭都会触发） |

**两处与设计稿的差异（都是实测出来的，不是口味问题）**

1. `ChatServiceTest` 去掉了 `@Transactional`。落库挂在 `publishOn(boundedElastic)` 之后，跑在**另一个线程、另一条连接**上：它看不见测试事务里未提交的会话（`23503` 外键当场报），它插入的行也不会被测试回滚。改成显式建数据 + `@AfterEach` 按外键顺序删（`t_ai_call` → `t_message` → `t_conversation` → `t_user`）。
2. `upsertTool` 不能直接 `{ ...旧, ...新 }`。end 帧的 `args` 是 `null`，直接展开会把 start 帧拿到的参数**抹掉**，详情里只剩结果。改成只合并非 `null` 的键——设计稿 F2 给的写法有这个问题。

**验证**：后端 `Tests run: 46, Failures: 0, Errors: 0`；`npm run build` 通过；浏览器实测工具卡片（`getCurrentTime` 从「调用中…」变成「完成 · x ms」，展开可见参数与返回值）与 RAG 来源（命中已上传资料时展示片段、可跳章节页）均正常。

**教训**：①「落库跑在别的线程」会同时打破两样东西——测试事务的可见性与回滚，写测试前先想清楚回调落在哪条线程；②两半合并（start/end）时，`null` 的含义是「这一帧没有这个字段」而不是「把它清空」，展开运算符合并前必须先过滤 `null`。

