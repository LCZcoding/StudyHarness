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

---

## 2026-11-14 · V2 可批注笔记 T001：数据库结构迁移

**分支**：`vibe`（未建分支、未 PR、未 push）。**交付**：`db/migration-v2.sql`。设计依据 `specs/001-annotatable-note/plan.md` 4.2 / 3.4 的 C3、C6、C7。

**执行**：`psql -f` 打到远程开发库 `ai_agent_study`（PostgreSQL 16.15），`BEGIN … COMMIT` 一次通过（5×`ALTER TABLE` / `CREATE TABLE` / 2×`CREATE INDEX`）。

**迁移前只读预检**：两个 CHECK 的真实名确为 `t_message_type_check` / `t_ai_call_scene_check`（schema.sql 里是列级内联约束，由 PG 自动命名，与 plan 4.2 的判断一致）；`t_annotation` 不存在、`t_message.updated_at` 不存在；库里已有 `t_message` 189 行、`t_ai_call` 171 行。这次只放宽约束 / 加带默认值的列 / 建新表，不改任何现有行。

**验收**（写入自检全部放在会 `ROLLBACK` 的事务里，不留脏数据）：

- 两个 CHECK 定义已含 `NOTE` 与 `NOTE_GENERATE` / `ANNOTATION` / `ANNOTATION_CHILD`。
- `t_annotation` 11 列齐全，索引为 `t_annotation_pkey` / `idx_annotation_target` / `idx_annotation_parent`。
- 事务内插入 `type='NOTE'` 的 ASSISTANT 消息成功且 `updated_at` 自动填充；3 个新 scene 依次写入 `t_ai_call` 成功 —— 等价于 TC-09，证明 `AiCallRecorder.recordCall` 不会撞 `23514`。
- 自引用 `parent_id … ON DELETE CASCADE` 生效：插 1 父 1 子，删父返回 `DELETE 2`，剩余 0（对应 plan 4.3 的不变量 I4）。
- ROLLBACK 后行数仍是 189 / 171，现有数据一行未动。
- **TC-10 未跑**：它要求模拟「未执行迁移」的环境，留到 Phase 1 集成测试覆盖。

**三个踩坑**：

1. **回滚段必须整体注释掉**，否则 `psql -f` 会在 `COMMIT` 之后紧接着把迁移撤销。脚本里用 `/* … */` 包住，并在文件头写明「手动去掉块注释再单独执行」。
2. 从 `application-local.yml` 提口令时，含 `$` 的正则放进 bash 双引号会被 shell 吃掉，`PGPASSWORD` 变空，psql 挂在交互式密码提示上直到超时。改成逐行 `startswith('password:')` 解析，并一律带 `-w`（禁止交互）后正常。
3. 远程库 16.15、本机 psql 客户端 18.1 可连；脚本含中文注释，执行时带 `PGCLIENTENCODING=UTF8`。

**回滚**：`db/migration-v2.sql` 末尾块注释里的 10 行，去掉 `/* */` 单独执行；仅在尚未写入批注业务数据前使用。

**当前进度**：T001 完成，Phase 0 闸门解除，T002 可开始；尚未写任何 Java / Vue 代码。

---

## 2026-11-14 · `db/schema.sql` 同步到 V1 基线（不含 V2 改动）

**背景**：全量只读核对发现 `schema.sql` 落后于真实库 —— `t_knowledge_item` 缺 `doc_title` / `section_no`。这两列由提交 `53123e9`「embedding 按标题语义切块」ALTER 追加，真实库 9 条资料章全部有值，`KnowledgeItemRepository.createSection` 明确往这两列 INSERT，代码里有 `coalesce(doc_title, title)` / `coalesce(section_no, 1)` 兜底历史行，但建库脚本一直没同步。按用户要求：**本文件只同步 V1 基线，V2 结构一律留在 `db/migration-v2.sql`**。

**改动**（`db/schema.sql`，3 处）：

1. 文件头加「基线版本」说明：完整链路 = 空库 → `schema.sql`(V1) → `migration-v2.sql`(V2)。
2. `t_knowledge_item` 补 `doc_title VARCHAR(100)` / `section_no INT`（均可空、无默认值，附来历与兜底说明）。
3. 末尾自检加 ④（检查这两列存在）；①② 注明跑完 V2 迁移后表数 10→11、索引 9→11。

**明确未带入**：`t_annotation`、`t_message.updated_at`、`idx_annotation_target` / `idx_annotation_parent`、`t_message.type` 的 `NOTE`、`t_ai_call.scene` 的 3 个新值 —— 全部只在 `migration-v2.sql` 里。

**验证**（只读）：解析脚本 DDL 正文（剔除注释），与真实库扣除 V2 四项后逐项比对，缺表 / 多表 / 列 / 类型 / 索引**全部为「无」**；两个 CHECK 仍是 V1 的 4 值 / 6 值。脚本里唯一出现的 `updated_at` 是 `t_conversation` 本来就有的列（第 42 行）及其索引，`t_message` 定义里没有 —— 隔离脚本里这一项报 True 是误报。

**注意**：没有重跑 `schema.sql`。它全是 `CREATE ... IF NOT EXISTS`，跑成功只代表没报错、不补列也不改约束，不能用来验证一致性；本次校验全程 `SELECT`，未改数据库任何对象或数据。

---

## 2026-11-14 · V2 可批注笔记 T004：Message 层 NOTE 能力（v1.1 新编号）

**分支**：`vibe`（未建分支、未 PR、未 push）。**依据**：`tasks.md` T004、`plan.md` C1/C2/C3、4.4、5.2 #2/#3、6.1。这是 v1.1 重排后的第一个新任务，也是 T005（重做 `AnnotationService`）、T010、T012、T021 的共享前置。

**改动**（4 个 Java 文件 + 3 个测试文件，业务行为不增加，只把 NOTE 需要的读写能力补齐）：

| 文件 | 内容 |
|---|---|
| `model/MessageType.java` | 枚举加 `NOTE` |
| `model/MessageRow.java` | record 追加末位 `type`，旧 4 字段顺序不变 |
| `repository/MessageRepository.java` | 历史 SQL 改表别名 `m` 并 SELECT `m.type`；新增内部 record `NoteMessage`（6 字段，对齐 5.2 #2 响应）与 `findNoteById` / `noteBelongsToUser` / `updateContent` 三个方法 |
| `service/MessageService.java` | 新增 `@Transactional appendNoteRound(...)`：USER/QUESTION + ASSISTANT/NOTE 两条 + `touch`，返回 NOTE 行 id |

**与 tasks.md 描述的一处出入**：任务栏写「加 `insertReturningId`」，实际这个方法 **V1 就有**（`QuizStore` 用它回填 `t_question.message_id`），所以本轮只是复用，没有重复造方法。`tasks.md` 的「实际结果」与 `plan.md` 6.1 已如实标注，并把五个方法的确切签名写进契约，避免 T005 / T012 再出现隐藏依赖。

**三个设计取舍**：

1. `findNoteById` 只读 `type='NOTE'` 的行。归属校验之外再加一层纵深防御，普通聊天消息不会被当成笔记全文交给模型。T005 取全文用 `findNoteById(...).map(NoteMessage::content).orElse("")`。
2. `updateContent` 返回 `Optional<OffsetDateTime>`（新的 `updated_at`），正好对应 5.2 #3 的 `{messageId, updatedAt}`；非 NOTE / 不存在返回 empty，不抛 `EmptyResultDataAccessException`。
3. 4.4 的 NOTE 归属 SQL 自带 `m.type='NOTE'`，所以「拿本人普通消息的 id 当笔记」会走 403 而不是 5.2 #2 写的 400；这一处文档口径已在 `plan.md` 6.1 注记，T012 沿用归属校验在前的顺序，不另写 SQL。

**验证**（均为真实库、`@Transactional` 自动回滚）：

- `-Dtest=AnnotationRowTest,AnnotationRepositoryTest,MessageServiceTest,MessageRepositoryTest,ConversationControllerTest` → **27 用例全绿**（T002 / T003 无回归）。新增 `MessageRepositoryTest` 4 用例覆盖历史带 type、跨两层归属（含「非 NOTE 不算」）、笔记全字段读取、正文保存与非 NOTE 拒写；`MessageServiceTest` 新增 1 用例验证两行 + 返回 id + `touch` + `@Transactional`；`ConversationControllerTest` 新增 `messages_returnsNoteType`，把 **TC-08 从 Service 一路验到历史接口 JSON**。
- 旧链路回归 `-Dtest=AnnotationServiceTest,ChatServiceTest,AiChatControllerTest` → **12 用例全绿**：未开开关时的普通问答、SSE 帧、旧批注服务行为不变。
- 没跑全量 `mvn test`：其中 `LiManusTest` / RAG 评测类会真实调模型（单类 238s），与本任务无关；留到 T023 全量回归。

**明确未动**：数据库（T001 迁移后无新 DDL）、任何前端文件、旧 `service/AnnotationService.java` 与 `AnnotationServiceTest.java`（按 v1.1 约定留到 T005 重构替换，移除两条临时 SQL 与 `JdbcTemplate` 注入）。

**当前进度**：T001~T004 完成，G1 之前只剩 T005 / T006 / T007。下一步 **T005：按新前置重做 `AnnotationService`**。

---

## 2026-11-14 · 执行方式约定：当前接口不改，后续新接口走垂直切片

**用户决定**（原话）：「当前正在做的接口就不改了，随后写新接口按照垂直接口切片」。起因是用户质疑「一般不是按接口开发吗，现在先写 repo 层是不是顺序不对」。

**结论**：**编译依赖顺序 ≠ 交付/验收顺序**。上一轮为修「隐藏依赖」把共享底座一次做完、把 AT 黑盒全推到 T018，属于把「刚好够用的底座」做成了「全部底座」，导致中间六个任务只能靠单测自证、review 时看不到可调用的接口。但依赖编号单调这条硬约束仍要保留，否则会回到「Service 靠临时 SQL 绕过去」的老路。

**落地**（只改执行与验收方式，**没动任何任务编号、依赖、FR 标签、闸门 AT/TC 覆盖、旧→新映射表**）：`tasks.md` 新增 0.1 节「执行方式约定」，把后续任务分成 G1~G6 六组；`MEMORY.md` 同步登记为项目长期约定。

| 组 | 任务 | 打通的接口 |
|---|---|---|
| G1（当前，保持分层） | T005 → T006 → T007 | 批注四接口 |
| G2 | T008 → T011 | `POST /ai/chat/stream`（`asNote=true`） |
| G3 | T012 | `GET/PUT /notes/{id}` |
| G4 / G5 | T013 → T018 / T019 → T022 | 阅读页 M1 / 笔记页 M2 |
| G6 | T023 | 全量回归 |

组内仍按编号顺序落盘，组按编号顺序推进，所以「按编号做必能编译」依然成立。G2/G3 的验收物改为「真实 HTTP 请求打到真实库」的 MockMvc 端到端测试（模型 mock），浏览器 UI 仍归 G4/G5。

---

## 2026-11-14 · V2 可批注笔记 T005：`AnnotationService` 按新前置重做

**分支**：`vibe`（未建分支、未 PR、未 push）。**目标**：移除旧实现里「绕过去」的两条临时 SQL 与 `JdbcTemplate` 注入，NOTE 归属/全文统一走 T004 提供的 `MessageRepository`；其余行为一字不改。

**改动**（2 个文件）：

| 文件 | 内容 |
|---|---|
| `service/AnnotationService.java` | 删 `JdbcTemplate` import + 字段，改注入 `MessageRepository`；`assertTargetOwned` 的 NOTE 分支 → `noteBelongsToUser(id, userId)`；`loadFullText` 的 NOTE 分支 → `findNoteById(...).map(NoteMessage::content).orElse("")`；类注释清掉「Phase 1 临时 SQL」措辞 |
| `service/AnnotationServiceTest.java` | 6 → 8 用例，见下 |

**明确未改**：7 步处理顺序、I1~I4 校验、`EXPLAIN_SYSTEM` / `EXPLAIN_CHILD_SYSTEM` 提示词、`locateBlock` 前后各 300 字、200 字硬截断、两个 scene 埋点、模型失败抛 `IllegalStateException`(500)、`AskRequest` 定义在 Service 内、`ask()` 不标 `@Transactional`、`list` / `updateAnswer` / `delete` 行为。

**测试新增的三个证据**（这是重做是否真的去掉绕过的判据）：

1. `ArgumentCaptor<Prompt>` 捕获模型入参，断言 SECTION 路径的全文来自资料章、NOTE 路径的全文来自那条 `type=NOTE` 的消息 —— 证明 `findNoteById` 真的被用上，而不是传了空串。
2. 「本人会话里的普通 ANSWER 不能当笔记」→ 403，「`targetId` 非数字」→ 400，且 `verify(chatModel, never()).call(...)`；同时验证 `list()` 走同一条 NOTE 归属路径。
3. 反射检查 `AnnotationService` 的字段类型：含 `MessageRepository`、**不含 `JdbcTemplate`**。

**验证**：单独 `AnnotationServiceTest` **8/8**；全组 8 个测试类（含 T002/T003/T004、历史接口、ChatService、AiChatController） **41/41 全绿**，无回归。真实库 + `@Transactional` 回滚。

**当前进度（T005 结束时）**：T001~T005 完成。下一步 **T006 `AnnotationController`**（四个薄接口，`AskRequest` 按 `AnnotationService.AskRequest` 引用），按用户决定仍按分层顺序逐个做，G1 闸门 T007 之后才开始垂直切片。

---

## 2026-11-14 · V2 可批注笔记 T006：`AnnotationController` 四接口打通

**分支**：`vibe`（未建分支、未 PR、未 push）。**范围**：只新增 Controller 与其 MockMvc 测试，`AnnotationService` / Repository / 数据库 / 前端一行未动。这是 G1 里第一次有「真实 HTTP 请求可调」的产物。

**改动**（2 个新文件）：

| 文件 | 内容 |
|---|---|
| `controller/AnnotationController.java` | `GET /annotations`（扁平列表）、`POST /annotations/ask`、`PATCH /annotations/{id}`、`DELETE /annotations/{id}`；方法体首行一律 `requireUserId`；归属 / 限额 / 模型 / 落库全交给 T005 的 Service |
| `controller/AnnotationControllerTest.java` | 15 个 MockMvc 用例，真实 HTTP → 真实库（`@Transactional` 回滚），`@MockitoBean(name="deepSeekChatModel")` 顶掉模型 |

**两个实现取舍**：

1. `GET` 用 `@RequestParam(required=false)` + 手写 `parseTargetType` / `requireTargetId`，让非法枚举、缺参、超 64 字统一落到 `BadRequestException` → 400 + `{"message":...}`，不依赖 Spring 的缺参默认响应（无 `message`）。
2. `PATCH` 用 `@RequestBody Map<String,Object>`，要求「恰好一个 `answer` 字符串键」。`plan.md` 5.2 #6 要求「多传 `question` → 400」，但 Jackson 默认忽略未知字段，DTO + `@Valid` 会静默吃掉多余字段；改用 Map 才能真的拒绝。`answer` 非空 / ≤2000 字仍由 Service 统一校验。

**口径注记**：`@Valid` 在参数绑定阶段先于方法体执行，因此「未登录且 body 非法」返回 400 而非 401；单独未登录、单独非法 body 分别按 401 / 400。与项目既有 `QuizController` 一致，已写进 `plan.md` 5.2 供 T014 前端封装参照。

**测试覆盖**（TC-03 / 04 / 05 / 06 的 HTTP 面）：四接口 401；GET 参数形状、扁平列表字段形状（不下发 `userId`/`targetType`/`targetId`）、越权 403；POST 顶层 + 子批注（继承 `blockKey`、两个 scene）、越权 `targetId` / 他人 `parentId` / 链深 8 在模型调用前被拒（`verify never call`）、`@Valid` 三类 400；PATCH 只改 `answer`/`edited`、多传字段与空体 400、越权 403；DELETE 三代级联 `deletedCount=3`、越权 403。

**验证**：单独 `AnnotationControllerTest` **15/15**；全组 9 个测试类 **56/56 全绿**（T002~T005、历史接口、ChatService、AiChatController 无回归）。未跑含真实模型调用的全量 `mvn test`（留 T023）。

**当前进度（T006 结束时）**：T001~T006 完成。下一步 **T007 G1 后端闸门**（跑 TC-03/04/05/06/08，纯后端集成测试，不需要任何前端文件）；闸门通过后，G2 起按用户决定改走垂直切片。

---

## 2026-11-14 · G1 后端闸门（T007）结论：**PASS**

**分支**：`vibe`。闸门不写功能代码，只做「逐条核对 TC 是否真被测到 → 跑一次 → 记结论」。

**开工前审计发现的缺口**：通过标准写的是「越权与**限额**在模型调用前被拒」，但 I3（单目标 ≤200 条）此前只有 `AnnotationServiceTest` 的 Service 单测证据，HTTP 层没有。补了两条测试（**无新增生产代码**）：

1. 新增 `AnnotationControllerTest#ask_限额I3满200_400且模型与埋点均未发生`：`generate_series` 批量插满 200 条 → `POST /annotations/ask` 返回 400；断言 `verify(chatModel, never()).call(...)`、`t_ai_call` 里该用户无 `ANNOTATION` 记账（证明拦在 `start()` 之前）、批注数仍为 200。
2. 给 TC-03 顶层用例补库内 9 字段断言（`user_id` / `target_type` / `target_id` / `parent_id` / `block_key` / `quote` / `question` / `answer` / `edited`），此前只验了响应形状与埋点。

`AnnotationControllerTest` 用例数 15 → 16。

**执行结果**：`-Dtest=AnnotationRowTest,AnnotationRepositoryTest,MessageServiceTest,MessageRepositoryTest,ConversationControllerTest,AnnotationServiceTest,ChatServiceTest,AiChatControllerTest,AnnotationControllerTest` → **57/57 全绿**（0 失败 / 0 错误 / 0 跳过）。TC-03 / 04 / 05 / 06 由 `AnnotationControllerTest` 的 9 条用例覆盖，TC-08 由 `ConversationControllerTest#messages_returnsNoteType` 覆盖；逐条映射表写在 `tasks.md` T007 栏。真实库 + `@Transactional` 回滚，模型全部 mock。

**通过标准四条全中**：四接口成功路径均有 200 用例；越权 / 链深 / 限额 4 条均带 `verify never call`；历史接口 JSON 返回 `type=NOTE`；T002~T006 九个测试类全绿。

**明确不覆盖（不冒充闸门已过的东西）**：AT-01~14 黑盒留 T018 / T022 / T023；TC-09 此刻只验了 `ANNOTATION` / `ANNOTATION_CHILD` 两个 scene，第三个 `NOTE_GENERATE` 要等 G2 的 TC-07（T001 迁移时只在回滚事务内实测过三个 CHECK 可写）；TC-10 未跑；全量 `mvn test` 留 T023。

**里程碑**：**G1 通过，可在此独立停** —— 后端批注链路已能被集成测试完整覆盖，不需要任何前端文件。按当时的计划，下一组是 G2（T008~T011，`POST /ai/chat/stream` 的 `asNote`）；**该计划在下一节被用户调整**。

---

## 2026-11-14 · 执行方向再调整：前端优先、前后端并行开发后联调

**用户决定**（原话）：「如果这个阶段适合写前端，优先写前端，然后接入后端，这样我可以低成本的通过图形化，看到效果。以后前后端并行开发，开发完联调。」

**核对结论：当前不只是「适合」写前端，而是前端所需的后端已经全通。**

- G4（T013 `splitBlocks` → T014 `api/annotations.js` → T015 `AnnotationCard.vue` → T016 `AnnotatableDoc.vue` → T017 阅读页接入 → T018 M1 闸门）的跨组前置只有 **T006**（批注四接口）和 **T007**（G1 闸门），两者都已完成；组内依赖也全部指向更小编号。
- 阅读页 T017 用 `:bodyEditable="false"`，编辑态保存只 `emit('save')`，不 import `api/notes.js` —— 所以 **G4 完全不依赖 G3 的笔记接口，也不需要任何 mock**。
- 前端基建现成：`ai-agent-frontend` 有 Vite dev + proxy 到 `localhost:8123`，`api/request.js` 已带 `withCredentials`，`utils/markdown.js` 与 `views/SectionReadView.vue`（`.prose`）都在。做完 T017 直接 `npm run dev` 就能在浏览器里点出效果。

**因此把 G4 提到 G2/G3 之前**。好处是把用户最想看的那条交互（选中正文 → 右侧短解释 → 在解释里选词追问 → 缩进子批注 → 刷新层级还在 → 换账号看不到）提前到下一组就能演示，而不是等到 T018 之后才第一次看到画面；G4 直接打已通的真接口，连 mock 都省了。G2/G3 的后端随后按 `plan.md` 5.2 同一份契约补上，在 M1/M2 闸门联调。

| 组 | 调整前顺序 | 调整后顺序 |
|---|---|---|
| G1 批注后端 | ① 已完成 | ① 已完成 |
| G4 阅读页批注 UI | ④ | **② 下一步（前端先行）** |
| G2 SSE 笔记生成 | ② | ③ |
| G3 笔记读写接口 | ③ | ④ |
| G5 笔记页与聊天卡片 | ⑤ | ⑤（UI 可先用契约 mock 提前，联调等 G2/G3） |
| G6 收尾 | ⑥ | ⑥ |

**「并行 + 联调」的可操作定义**（单人 + AI 不是真并发，是交替推进、每一步尽量先交能看见画面的那块）：契约唯一真源 `plan.md` 5.2，前后端都不改字段形状；后端未就绪时 mock 只放 `api/*.js` 一层、不散进组件，后端就绪后删掉这层就切真接口；两边完成后在 M1(T018)/M2(T022) 闸门联调一次。前端验证靠 `npm run dev` + 浏览器手工 + `npm run build`，纯函数 `splitBlocks` / `resolve()` 用 Node 脚本跑 TC-01/TC-02（本项目前端无单测框架）。

**风险与缓解**：前端先行时后端契约若变会返工 —— 靠「契约唯一真源 + mock 只在一层」控制；`G5` 的笔记详情页依赖 G2/G3 的真实接口，所以它的 AT 仍留在 M2 跑，不提前冒充已过。

**只改执行与验收方式**：编号、依赖、FR 标签、闸门 AT/TC 覆盖、旧→新映射表全部未动；组内仍按编号落盘，`tasks.md` 0.1 的组表新增「推进顺序」列。项目长期记忆 `MEMORY.md` 已登记这条约定。

**当前进度（调整时）**：T001~T007 完成，G1 PASS。下一步改为 **T013（`utils/markdown.js` 追加 `splitBlocks`）**，由此进入 G4 前端链。

---

## 2026-11-14 · V2 可批注笔记 T013：`splitBlocks`（G4 前端第一小步）

**分支**：`vibe`。用户确认「可以的，下一步」，并追加约定「**每完成一次前端，就提醒我去看效果**」（已写进 `tasks.md` 0.1 与 `MEMORY.md`）。这是前端先行的第一步。

**改动**（2 个文件，均在 `ai-agent-frontend/`）：

| 文件 | 内容 |
|---|---|
| `src/utils/markdown.js` | 追加 `splitBlocks(md)`：`marked.lexer` 取顶层 token → 过滤 `space` → 逐块 `{key:'p-N', text:t.raw, html: 已消毒的 marked.parser([t])}`；`renderMarkdown` 保持原语义 |
| `scripts/verify-splitBlocks.mjs` | 新增 TC-01 可重复验证脚本，`node:assert/strict`，零新依赖 |

**一处对任务栏的更正**：原任务栏写「`renderMarkdown` 保持不变」，实际为了能在 Node 里跑纯函数测试，把 `DOMPurify` 的 hook 注册与消毒收进 `canPurify` 判断 —— Node 下 `dompurify` 只导出 factory（实测 `addHook` / `sanitize` 均为 `undefined`），没有 DOM 就没有消毒能力。`renderMarkdown` 改调同一个 `sanitize()` 包装。**浏览器行为与消毒强度一字不变**，仅无 DOM 环境跳过；NFR-02 无新依赖。`tasks.md` T013 栏已同步更正，不留文档与代码不一致。

**验证**：
- `node scripts/verify-splitBlocks.mjs` → **TC-01 8/8 断言全过**：空输入、key 连续 `p-0..p-5`、代码块整体一块且没混入后续段落、列表（含嵌套项）整体一块、GFM 表格整体一块、标题与两段互不串内容、稀疏空行不产生空块、Node 下 `renderMarkdown` 不抛错。脚本末尾打印 6 个块的分块预览。
- `npm run build` → **通过**（2.08s）。

**如实说明**：`html` 的 XSS 消毒只能在有 DOM 的浏览器里验，本步未覆盖；NFR-03 由 T015/T016 承担。**本步是纯函数，页面没有任何视觉变化** —— 已按新约定提醒用户看效果，同时明确告知「现在还看不到画面，第一个能看到界面的是 T017（阅读页接入）」。

**当前进度**：T001~T007 + **T013** 完成（任务勾选不连续是因为 G4 提前执行，编号与依赖未动）。下一步 **T014 `api/annotations.js`**（四个薄封装，接已通的批注四接口）。

---

## 2026-11-14 · V2 可批注笔记 T014：`api/annotations.js`（G4 第二小步）

**分支**：`vibe`。用户「下一步」，延续 T013 的方向：前端先行、每完成一次前端就提醒看效果。本步接的是 G1 已通的真接口，**没有加任何 mock/stub**。

**改动**（2 个新文件，均在 `ai-agent-frontend/`，其余代码一行未动）：

| 文件 | 内容 |
|---|---|
| `src/api/annotations.js` | `get(targetType,targetId)` / `ask(payload)` / `patch(id,answer)` / `remove(id)` 四个薄封装，对应 `plan.md` 5.2 #4~#7 |
| `scripts/verify-annotations.mjs` | 请求形状自检：axios 自定义 adapter 拦请求、零出网，`node:assert/strict` |

**两个实现取舍**：

1. `ask` **显式挑六个契约字段**再发，而不是整个 payload 透传：`resolve()` 组树后组件对象上会挂 `children / stale / groupKey`，混进请求体轻则脏数据、重则触发后端字段校验；`parentId` 仅在有值时加入，顶层批注不带这个键。
2. `patch` 只发 `{answer}`：T006 的落地口径要求「恰好一个 answer 字符串键」，多带 `question` 会被 400 拒。

`import request from './request.js'` 带后缀是唯一与既有 `api/*.js` 不同的写法，目的是让 Node 能直接 import 做形状自检；Vite 与浏览器行为一致，`request.js` 的拦截器 / `baseURL` / `withCredentials` 均未改动。

**验证**：

- `node scripts/verify-annotations.mjs` → **14/14 断言全过**，拦下 7 次请求全部未出网。覆盖：四个导出名、#4 的 GET query（含 NOTE 目标按字符串传 id）、#5 六个字段与派生字段不透传 / 子批注 `parentId`、#6 恰好一个 `answer` 键、#7 无请求体、响应脱壳、失败原样抛出且 `messageOf` 能取到服务端 `message`；另断言 `request.js` 的 `baseURL='/api'` / `withCredentials=true` 没被改坏。
- `npm run build` → **通过**（1.66s）。

**如实说明**：本步是纯封装，**页面没有任何视觉变化**；`annotations.js` 此刻尚未被组件 import，所以 `npm run build` 产物里不含它，其语法与形状由 Node 脚本实际 import 覆盖，不能把 build 通过当成它已打包。已按约定提醒用户看效果并说明第一个能看到界面的是 T017。

**当前进度**：T001~T007 + **T013、T014** 完成。下一步 **T015 `AnnotationCard.vue`**（递归卡片）。

---

## 2026-11-14 · V2 可批注笔记 T015：递归批注卡片（G4 第三小步）

**分支**：`vibe`。用户「下一步」。这是 G4 里**第一个能看见卡片长相**的任务，按「每完成一次前端就提醒看效果」的约定，本轮额外产出一份可直接打开的静态预览页。

**改动**（3 个新文件，均在 `ai-agent-frontend/`，其余代码一行未动）：

| 文件 | 内容 |
|---|---|
| `src/components/AnnotationCard.vue` | 递归卡片，SFC 按文件名隐式自引用；props `{annotation, depth}`，emits `changed / error` |
| `scripts/verify-AnnotationCard.mjs` | Vite `ssrLoadModule` 加载真实 SFC + `vue/server-renderer` 真渲染，零新依赖（用的是 Vite / Vue 自带包） |
| `preview/annotation-card.html` | 自检脚本生成的**静态预览产物**：SSR HTML + `compileStyle` 编译出的 scoped CSS + 原样内联的 `global.css`，可直接在浏览器打开看卡片长相 |

**实现要点**：卡片自己调 T014 的 `patch` / `remove`，成功后 `emit('changed')` 让父组件重新拉列表并 `resolve()`，不直接改 prop；失败 `emit('error', 中文文案)`，且**保留编辑态**不弄丢草稿。删除前 `window.confirm` 提示「将同时删除 N 条追问」，N 由递归数 `children` 得到。根元素带 `data-annotation-id` 供 T016 的选区委托 `closest()` 识别，嵌套时命中最内层即正确的 `parentId`。`answer / question / quote` 全部 `{{ }}` 插值，NFR-03 不插入原始 HTML。

**一处对 `plan.md` 6.2 的修正**：原公式 `marginLeft = Math.min(depth,3)*16px` 在嵌套 DOM 下会累加（depth3 已 96px、depth4 144px），继续右移，违背 FR-08「第 3 层后不再右移」。改成**相对父级的增量**：depth 1/2/3 各 +16px，depth≥4 加 0，累计封顶 48px。`plan.md` 6.2 已加「T015 落地口径」注记，`tasks.md` T015 栏同步登记。

**验证**：

- `node scripts/verify-AnnotationCard.mjs` → **14/14 断言全过**。五层链（12→13→14→15→16）真渲染：递归出五张卡、`margin-left:16px` 恰好 3 次 / `0` 恰好 2 次、第 4/5 层有层级标签而第 3 层没有、删除按钮提示「将同时删除 4 条追问」、已编辑 / 原文已修改标签、两个 `changed / error` emit 声明、事件逐层转发、组件确实 import T014 封装、模板区无 `v-html`、XSS 探针 `<img src=x onerror=...>` 被转义为 `&lt;img`；另断言预览页的 scoped 样式能命中渲染元素。
- `npm run build` → **通过**（1.57s）。
- 过程中两次自检失败都是脚本自身问题，非组件缺陷：① 四层链最深只到 depth3，验不到封顶，补成五层；② 模板注释里写了「v-html」字样被 `doesNotMatch` 抓到，改注释措辞并把断言收窄到 `descriptor.template.content`。

**如实说明**：自检是 **SSR 静态渲染**，点击编辑 / 删除 / 确认框只能等 T017 在浏览器手工验；组件此刻没被页面 import，`build` 产物不含它，语法与渲染由 SSR 脚本覆盖；预览页**不可点击、不是功能演示**，仅用于低成本看图形化效果。

---

## T015 视觉返工：折叠 + 展开后独立平铺（2026-11-14，同日第二轮）

**起因**：用户看第一版 `preview/annotation-card.html` 后觉得「层层缩进套娃」丑，要求「可折叠隐藏、手动打开，展开后卡片独立」；并澄清「引用词高亮配色」指**引用在原文中的位置高亮**，不是卡片顶部标签。分析结论：三项都是纯前端渲染层，后端 / 接口 / DB 零改动；T016 尚未开工，此刻改不产生跨任务返工。

**改动**（4 个文件，全在 `ai-agent-frontend/`）：

| 文件 | 内容 |
|---|---|
| `src/components/AnnotationCard.vue` | 改成多根 fragment：第一根卡片本体、第二根子卡片容器；追问默认折叠成「展开 N 条追问」小横条，`v-if` 控制；去掉 `marginLeft` 缩进与 `is-child` 蓝竖线；`depth>0` 一律显示「第 N 层」标签 |
| `src/utils/annotationExpand.js` | 新增。`createExpandedIds()` 按 id 管折叠状态，`provideExpandedIds()` 给 T016 用；key 用字符串常量（自检脚本与 Vite SSR 是两份模块实例，`Symbol()` 会不相等） |
| `scripts/verify-AnnotationCard.mjs` | 重写。折叠态 / 展开态两套 SSR 断言 + 状态模块单测 + 生成可点击预览页 |
| `preview/annotation-card.html` | 重新生成。全展开 SSR DOM + 约 30 行演示 JS，可逐层点开 / 收起 / 全部展开；左侧加正文与 `.quote-hit` 高亮配色示意 |

**两个实现取舍**：① 折叠状态按 id 提到父组件共享，避免 `emit('changed')` 后重新拉列表把展开的链合上；T016 必须调 `provideExpandedIds()`，卡片 inject 不到时回落局部状态（可独立渲染，但不是生产路径）。② 折叠用 `v-if` 而非 `v-show`，隐藏卡片不留在 DOM 里，T016 的 `closest('[data-annotation-id]')` 选区委托不会命中看不见的卡片。

**需求口径同步**：`spec.md` FR-08 / AT-05 由「缩进挂在该批注下方」改成「默认折叠、点开后独立平铺」；`plan.md` 6.2 原 `marginLeft` 公式与「第 3 层后不再右移」整段作废。正文原句高亮仍按 FR-06 / C10 的 `.quote-hit` 归 T016 / T017，卡片顶部引用词标签不改配色。

**验证**：`node scripts/verify-AnnotationCard.mjs` → **18/18 全过**（折叠态 13~16 不在 DOM、展开态全在且 4 层容器齐、无 `margin-left` / `is-child`、第 2~5 层标签、两态文案与 `aria-expanded`、状态模块 `toggle/openAll/clear`、`v-if` 无 `v-show`、N=4 提示、状态标签、XSS 转义、事件转发、预览页样式命中与折叠 JS）；`npm run build` 通过（1.57s）；`TC-01 8/8`、`T014 14/14` 回归无影响。过程中一次自检失败：Vite SSR 与脚本直接 import 的模块各生成一个 `Symbol`，provide / inject 对不上，改字符串 key 后解决。

**如实说明**：预览页的点击是演示 JS，不是组件真实交互；左侧正文与黄色高亮只是配色示意，真实正文渲染与 `.quote-hit` 要等 T016 / T017；组件仍未被页面 import，build 产物不含它。

---

## T016：可批注正文组件（2026-11-14，G4 第四小步 / 本切片最大件）

**范围**：纯前端，4 个新文件；后端 Java、数据库、任何 `.vue` 页面文件一行未动。按「前端优先」约定，这一步仍是 G4，G2/G3 的笔记后端链未开工。

| 文件 | 内容 |
|---|---|
| `src/components/AnnotatableDoc.vue` | 两栏（<1024px 右栏下移）、逐块 `data-block-key`、选区委托、`resolve()` 降级、编辑/预览两态、best-effort 原文高亮、右栏提问面板；渲染 T015 卡片并 `provideExpandedIds()` |
| `src/utils/annotationResolve.js` | 新增纯函数：`resolve` / `groupAnnotations` / `highlightBlock` / `escapeHtml` / `depthOf` / `normalizeForMatch` |
| `scripts/verify-AnnotatableDoc.mjs` | TC-02 + 纯函数 + Vite SSR 真渲染自检，兼生成预览页 |
| `preview/annotatable-doc.html` | 真实 SSR DOM + 编译后 scoped 样式 + 演示 JS（逐层折叠、选中文字提示） |

**用户点名的高亮配色已落地**：「引用词高亮配色」经澄清指**引用在原文中的位置**，即 `<mark class="quote-hit">` 荧光黄（`#faeeda` 底 / `#854f0b` 字 / `#ef9f27` 下划线）；被 `<strong>` / `<code>` 切开匹配不到时降级为段落左侧色条 + 批注数角标。C10 样式写在组件 scoped 内，T017 只调容器宽度即可。

**两处对 `plan.md` 6.2 的精确化**（已同步注记）：① `blockContains()` 用 `normalizeForMatch()` 去掉 markdown 标记再匹配，避免带格式段落被误判 stale；② **子批注 stale 只显示卡片标签、不单独进「原文已修改」组**（进组会断链，也与 spec AT-08 不符），顶层 stale 才进组。

**给 T017 / T020 的契约**：emits `save(draft)` / `cancel` / `unauthorized`；`defineExpose({ finishSave, reload })`；`initialAnnotations` / `initialExpandedIds` 两个可控渲染入口仅供 SSR 与预览页，生产路径不传。父页面保存成功后须调 `finishSave()` 退出编辑态。组件不 import `api/notes.js`、不写 `PUT /notes/{id}`。

**验证**：`node scripts/verify-AnnotatableDoc.mjs` → **29/29 全过**（TC-02 三分支、子批注与父被删兜底、markdown 标记匹配、链深、分组、高亮命中/降级、XSS 转义、SSR 两栏渲染 6 个块、`bodyEditable` 显隐、组件契约与选区委托源码断言、预览页样式命中）。`npm run build` 通过（1.67s）；`TC-01 8/8`、`T014 14/14`、`T015 18/18` 回归无影响。过程中一次自检失败：源码注释里出现「api/notes.js」文件名被 `doesNotMatch` 抓到，断言收窄成只查 import 语句（与 T015 的 `v-html` 注释同类问题）。

**如实说明**：① 预览页的折叠与选区提示是演示 JS，真实提问 / 编辑 / 删除要 T017 接入阅读页后才可点；② 组件未被任何页面 import，`build` 产物不含它，语法与渲染由 SSR 脚本覆盖；③ 正文 DOMPurify 消毒只能在有 DOM 的浏览器验，Node 下跳过，本步未覆盖。已按约定提醒用户去看效果并打开预览页。

## T017 · 阅读页接入（2026-11-14，G4 第五小步 / 第一个真可交互界面）

**只改一个生产文件** `ai-agent-frontend/src/views/SectionReadView.vue`，另新增自检脚本与预览页：

| 文件 | 内容 |
|---|---|
| `src/views/SectionReadView.vue` | 整篇 `v-html` 的 `.prose` → `<AnnotatableDoc>` 两栏；`contentHtml` → `contentMarkdown`（原始 markdown，仍跳过重复首行标题）；删整组 `.prose :deep(...)` 排版；`.reader-inner` 720px → 1200px；`.action-bar` / 翻页 / 检测 / `watch(immediate)` / 401 全部保留 |
| `scripts/verify-SectionReadView.mjs` | 17 条断言：SFC 静态契约 + Vite SSR 真实加载整页 + 两栏组件真渲染 + 预览页结构 |
| `preview/section-read.html` | 阅读页全貌静态预览（顶栏 + 两栏 + 底栏，真实组件 SSR DOM + 编译后 scoped 样式 + 演示 JS），生成物、不进生产包 |

**六个接线属性**：`:key="detail.knowledgeItemId"` / `:markdown="contentMarkdown"` / `:target-type="'SECTION'"` / `:target-id="detail.knowledgeItemId"` / `:body-editable="false"` / `@unauthorized="onUnauthorized"`。`targetId` 是 `knowledgeItemId` 的 UUID 字符串，与 T005 `assertTargetOwned(SECTION)` 的 `UUID.fromString` 对齐。

**两个本步新增判断**：① 按章节 id 加 `:key`，翻页时重建组件，否则组件复用会让上一章的批注列表与折叠 id 集合残留；② 组件 401 只 emit，父页面 `redirectOn401({response:{status:401}}, router, route)` 跳登录，组件仍不引 router。C10 高亮与 markdown 排版样式**没有在页面重复定义**（自检有 `doesNotMatch` 断言），按 T016 契约执行。

**验证**：`node scripts/verify-SectionReadView.mjs` → **17/17 全过**（10 条接线契约 / SSR 真实加载页面 / 加载态渲染 / 两栏高亮与分组 / XSS 转义 / `bodyEditable=false` 无编辑按钮 / 预览页骨架与 scoped 样式命中）。`npm run build` 通过（1.67s）；**dist 里实测 `SectionReadView` chunk 含 `doc-layout`、`data-block-key`、`quote-hit`、`/annotations/ask`** —— 与前四步「组件未被页面 import、产物不含它」不同，这次真正打包进生产产物。回归：TC-01 8/8、T014 14/14、T015 18/18、T016 29/29 全绿。

**如实说明**：① 预览页的折叠与选区提示仍是**演示 JS**，真实提问 / 编辑 / 删除需启动后端 + `npm run dev` 在浏览器验，自检不能代替 T018 的黑盒 AT；② 页面 SSR 只渲染加载态（不给生产页面加测试用 prop），带正文的两栏由真实组件 SSR 单独提供；③ 数据库、后端 Java、组件文件一行未动。已按约定提醒用户去看效果。

**当前进度**：T001~T007 + **T013、T014、T015、T016、T017** 完成（勾选不连续是 G4 提前执行，编号与依赖未动）。下一步 **T018 M1 验收闸门** —— 浏览器黑盒跑 AT-04/05/06/08/09/10（批注半边）/11 与 TC-01~06；G4 到此可独立停、独立演示。

---

## T017 后 · 提问框就地弹出（2026-11-14 用户实测反馈修补）

**触发**：用户截图反馈「每次提问都要到第一行开头才能输入问题」——提问面板固定在右栏最顶部，正文滚到中段或底部后，选完文字必须滚回页面第一行才能看到输入框。T016 当初为「窄屏不炸、不引入定位逻辑」特意选了不做浮层，实测证明这个取舍牺牲了主流程手感。

**改法**（`components/AnnotatableDoc.vue` + 新增 `utils/annotationAskPosition.js`，后端 / DB / 页面文件一行未动）：

| 点 | 做法 |
|---|---|
| 浮层 | `<Teleport to="body">` + `position: fixed`，避开祖先 `transform` / `backdrop-filter` 影响定位；z-index 60 |
| 定位 | 纯函数 `computeAskPosition()`：宽屏横向贴右栏、纵向与选区齐平；窄屏（右栏已下移）优先选区下方、放不下翻到上方；四边 clamp 不越视口、不产生横向滚动 |
| 打开即能打字 | `getRangeAt(0).cloneRange()` 留住原选区、`nextTick` 后 `focus({ preventScroll: true })`，不让浏览器把页面滚走 |
| 跟随与清理 | `scroll`（capture）/ `resize` 时重算位置；`cancelAsk()` 与 `onUnmounted` 解绑；Esc 取消、Ctrl/Cmd+Enter 提交合并进 `onAskKeydown` |
| 预览 | `preview/annotatable-doc.html` 加了同款演示浮层，注入的是组件**同一个定位函数的源码快照**；仍是演示 JS，不调后端 |

**验证**：T016 自检 29 → **38/38**（新增 6 条定位几何断言 + 2 条组件契约 + 1 条预览页断言）；T015 18/18、T017 17/17、T014 14/14、TC-01 8/8 回归全绿；`npm run build` 通过，dist 的 `SectionReadView` chunk 实测含 `ask-panel` / `preventScroll` / `getBoundingClientRect` / `cloneRange`。
**排错记录**：自检脚本必须在 `ai-agent-frontend` 目录下跑；从项目根用绝对路径跑会让 Vite SSR 与脚本直接 import 解析到两份 Vue 实例，表现为 `useRoute` 注入找不到、卡片自引用变成 `<Anonymous>`。这不是代码缺陷，串行 + 正确 cwd 即恢复。

**口径同步**：`plan.md` 6.2 与 `tasks.md` T016 的原「右栏顶部、不定位浮层」表述已改写并追加修补注记；T018 通过标准加一条「提问框就地弹出、可直接打字」。**不改编号 / 依赖 / FR 标签 / 闸门 AT-TC 覆盖**，也不改 spec 的 FR/AT 编号。

---

## T018 前 · 批注分组与正文段落对齐（2026-11-15 用户第二次实测反馈）

**触发**：用户截图反馈「生成后的批注跑到第一行了，应该做对齐」——提问框已就地弹出，但生成后的卡片仍按右栏文档流从顶部堆叠，选第 39 段生成的批注显示在右栏第一行。

**难度判断**：不算大，但不能用绝对定位。绝对定位会让 `.note-rail` 高度塌陷、`border-left` 只剩标题高度，而且相邻段落都有批注时卡片会互相盖住。改用「补 `margin-top` 把分组推到对应块高度」，分组仍在流内，右栏高度与分隔线自然撑开。

**改法**（`components/AnnotatableDoc.vue` + 新增 `utils/annotationRailLayout.js`，后端 / DB / 页面文件一行未动）：

| 点 | 做法 |
|---|---|
| 对齐 | `alignGroups()` 量 `[data-block-key]` 的 y 坐标与 `[data-group-key]` 的高度，交给纯函数 `computeRailMargins()` 算出每组 `margin-top` |
| 不折叠 | `.rail-groups` 改 `display:flex; flex-direction:column; gap:18px`，flex 里相邻 margin 不折叠，`cursor` 递推才精确；组间距由 gap 提供 |
| 防重叠 | 目标位置被前一组占住时 `Math.max(0, desired - cursor)` 顺延，绝不把分组往上拽；`__rest__` / `__stale__` 无对应块，紧接上一组只留一个 gap |
| 回退 | 窄屏 `≤1024px` 与编辑态清空 margin 回普通流；CSS `@media` 里 `margin-top: 0 !important` 双保险 |
| 重算时机 | `onMounted`、`watch(markdown / flat / editing)`、`resize`、折叠条点击后 `nextTick`；卸载解绑 |
| 预览 | `preview/annotatable-doc.html` 注入**同一个 margin 函数的源码快照**，加载 / 折叠 / resize 时按真实 DOM 量算，演示对齐（仍是演示 JS，不调后端） |

**验证**：T016 自检 38 → **45/45**（新增 5 条对齐几何断言：精确对齐、相邻顺延、目标占满、`__stale__` 兜底、脏输入；1 条组件契约；1 条预览页）；T015 18/18、T017 17/17、T014 14/14、TC-01 8/8 回归全绿；`npm run build` 通过，dist 的 `SectionReadView` chunk 实测含 `rail-groups` / `data-group-key` / `marginTop`。

**如实说明**：纯函数与 SSR 自检只能验算法与结构，**真实浏览器里第 39 段卡片是否精确贴住**，仍归 T018 黑盒验收；相邻段落都有批注时按设计会顺序顺延（不重叠但不严格贴齐每一段）；展开 / 收起追问改变卡片高度后组件会在下一帧重算。

**口径同步**：`plan.md` 6.2、`tasks.md` T016 追加第二次修补注记，T018 通过标准补「批注卡片与对应段落对齐」；编号 / 依赖 / FR 标签 / 闸门 AT-TC 覆盖未动。

---

## T018 前 · 真实浏览器白屏修复与首次浏览器验收（2026-11-15 用户第三次实测反馈）

**触发**：用户反馈「正文加载不出来了」，并质疑「你不会浏览器调用一下验证吗」——前两次修补只跑了 Node SSR 自检与 `npm run build`，没在真实浏览器里点开过生产页面。

**浏览器取证**（ego lite / `ego-browser`，导航前注入 `window.onerror` / `console.error` / `unhandledrejection` 钩子）：登录 → `/materials/{UUID}`，抓到
```
[Vue warn]: Unhandled error during execution of watcher getter   at <AnnotatableDoc>
[Vue warn]: Unhandled error during execution of setup function  at <AnnotatableDoc>
```
页面只剩 PageHeader，`.doc-body` 不存在、正文块数 0，与用户描述一致。

**根因**：第二次修补新增的 `watch(() => [props.markdown, flat.value, editing.value], ...)` 被放在 `const editing = ref(false)` **之前**。浏览器端 Vue 的 `watch` 在 setup 阶段会立即执行一次 getter 收集依赖，命中 `const` 的 TDZ → 组件 setup 抛错 → 整个正文栏渲染失败。**SSR 下 watch 不跑 getter**，所以 45/45 的 Node 自检、SSR 真渲染、`npm run build` 全部通过却照样白屏。这是自检手段的结构性盲区，不是断言写漏了。

**修法**（只动 `components/AnnotatableDoc.vue` 一行代码位置 + `scripts/verify-AnnotatableDoc.mjs`）：把 `editing` / `draft` 声明提到该 watch 之前，并在源码旁写明原因；自检加一条静态断言「watch getter 引用的 ref 必须在 watch 之前声明」（扫描 `scriptSetup` 的 `const/let` 声明行与 `watch(() => [...])` 行），45 → **46/46**。后端 / 数据库 / 页面文件一行未动。

**真实浏览器复验**（同一页面，修后）：控制台 `errs: []`；`.doc-body` 存在、**141 个块 / 11421 字**；右栏「第 39 段」批注正常显示、`Minor GC` 在原文中有黄色 `.quote-hit` 高亮；模拟选中「大家好，我是小林。」后提问浮层 `position:fixed`、`left=1181px / top=197px`、输入框自动聚焦；1440px 视口下 `p-38` 分组 `top=3554`、对应正文块 `top=3554`、**偏差 0px**，横向滚动 0。截图 `preview/browser-read-page.png`、`preview/browser-rail-align.png`。

**结论与口径**：**前端改动跑完 Node 自检 + build 后必须用真实浏览器复验**，SSR 自检不能替代（不执行 watch getter、不跑客户端生命周期）。已同步 `plan.md` 6.2、`tasks.md` T016 第三次修补注记与项目长期记忆；编号 / 依赖 / FR 标签 / 闸门 AT-TC 覆盖未动。下一步仍是 **T018 M1 验收闸门**。

---

## T018 前 · 卡片内「追问锚点」高亮（2026-11-15 用户第四次实测反馈）

**触发**：用户截图圈出右侧父批注解释里的 `Full GC`，问「批注中被引用的部分没有被高亮标记？这个做起来难度怎么样」。正文里的 `Minor GC` 已有黄色高亮，缺的是**在解释里选词追问后，那个词在父答案里不留痕迹**。用户确认「改吧」后实施，口径取「只标子批注的引用词，不标本卡片自己的引用词」。

**实现**：`utils/annotationResolve.js` 加纯函数 `splitAnswerByQuotes(answer, quotes)` → `[{text, hit}]`，匹配与 `resolve()` 的子批注 stale 判定同源（原样 `indexOf`、不 normalize/trim/正则），长词优先占区间、短词顺延、找不到跳过。`AnnotationCard.vue` 答案区改 `v-for` 分段渲染 `<mark class="quote-hit">` + `{{ seg.text }}`，**只切纯文本、零 `v-html`**，NFR-03 不变；卡片 scoped 样式补同配色（`.doc-body :deep()` 管不到右栏）。后端 / 数据库 / 页面文件一行未动。

**验证**：T015 18 → **26/26**（新增纯函数 4 条 + SSR/口径/XSS/样式 4 条 + 预览页 1 条）；T016 46/46、T017 17/17、T014 14/14、TC-01 8/8、`npm run build` 全绿，生产 CSS 实测含 `.answer mark.quote-hit[data-v-5cdf3db2]`。**ego lite 真实浏览器**：控制台 `errs: []`、141 块 / 11421 字、父答案 `Full GC` 标黄（`rgb(250,238,218)` 底 / `rgb(239,159,39)` 下划线）、正文 `Minor GC` 高亮仍在；选中已被 `<mark>` 包住的词 → 就地浮层 `fixed`、自动聚焦、quote 正确（选区委托没被高亮标签破坏）；1440px 横向滚动 0。截图 `preview/browser-answer-highlight.png`。

**口径同步**：`plan.md` 6.2、`tasks.md` T015 与 T018 通过标准、项目记忆；编号 / 依赖 / FR 标签 / 闸门 AT-TC 覆盖未动。下一步仍是 **T018 M1 验收闸门**。

---

## T018 前 · 编辑保存 403（2026-11-15 用户第五次实测反馈）

**触发**：用户截图「编辑后无法保存」，卡片停在编辑态、提示「保存失败，请稍后重试」。

**浏览器取证**（ego lite，注入 XHR 钩子抓请求与响应）：`PATCH /api/annotations/1779` → **`403 Invalid CORS request`**；`OPTIONS` 预检同样 403；控制台 `[request error] Request failed with status code 403`。同一页面 `GET /api/annotations` 200 正常。

**根因**：`config/CorsConfig.java` 的 `allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")` **漏了 PATCH**。Vite proxy 把浏览器的 `Origin: http://localhost:5173` 原样透传给 8123，Spring 的 `DefaultCorsProcessor` 据此把请求当作跨域请求、按方法白名单校验，且校验发生在 Controller 与鉴权之前；PATCH 不在白名单 → 直接返回裸文本 `Invalid CORS request`，所以看不到业务的「无权修改该批注」。列表(GET) / 提问(POST) / 删除(DELETE) 都在白名单里，因此**只有编辑保存炸**。PATCH + `application/json` 属非简单请求，浏览器先发 OPTIONS 预检，预检同样被拒。**MockMvc 默认不带 `Origin`，CORS 校验根本不触发**，旧 16/16 与 G1 闸门全绿都测不到——与上次白屏事故同属测试环境的结构性盲区，不是断言写漏。

**修法**：白名单加 `"PATCH"` 并写明来龙去脉；`AnnotationControllerTest` 新增 `patch_带浏览器Origin_预检和实际请求都不被CORS拦截`（OPTIONS 预检断言 `Access-Control-Allow-Methods` 含 PATCH；带 Origin 的真实 PATCH 断言 200 且库内答案已改）。red `Status expected:<200> but was:<403>`、响应体 `Invalid CORS request` → 改完 green，**16 → 17/17**。数据库未被探测请求改动（PATCH 403，标记没写进去）。

**重启与浏览器复验**：`CorsConfig` 是启动时加载的配置类，8123 是用户在 **IDEA 里启动的 JVM**（带 `idea_rt.jar`），本轮**未代用户结束该进程**。用户自行重启后 curl 探测：OPTIONS 预检 200、`Access-Control-Allow-Methods: GET,POST,PUT,DELETE,PATCH,OPTIONS`、带 Origin 的 PATCH 只返回业务 401（CORS 已放行）。随后 ego lite 真实浏览器点「编辑 → 保存」：**两次 PATCH 均 200**、控制台零报错、编辑态自动退出、卡片出现「已编辑」标签并显示新答案；随后再编辑去掉临时标记保存，**库内答案与原答案逐字一致、无标记残留**（`edited=true` 是 PATCH 的正常副作用，会留下「已编辑」标签）。截图 `preview/browser-save-fixed.png`。编号 / 依赖 / FR 标签 / 闸门 AT-TC 覆盖未动。

---

## T018 · M1 验收闸门（2026-11-15）**PASS**

**范围**：`AT-04 / AT-05 / AT-06 / AT-08 / AT-09 / AT-10（批注半边）/ AT-11` + `TC-01~06`。本轮**没有改任何生产代码**，只跑验收、清数据、归档。

**自动化**：前端 5 个自检全绿（TC-01 8/8、T014 14/14、T015 26/26、T016 46/46 含 TC-02 三分支、T017 17/17），`npm run build` 通过；后端约定的 9 类回归 **90/90**（0 失败 / 0 错误 / 0 跳过），TC-03~06 由 `AnnotationControllerTest` 17 条承载。

**黑盒**（ego lite 真实浏览器，登录 `lcz`，材料 `99baddae…`，挂 XHR 抓包 + `confirm` 钩子 + 控制台错误钩子）：

- **AT-04**：选 37 字提问 → `POST /annotations/ask` 200，答案 103 字；同期 `chat/stream` 0 次、会话消息 0→0。
- **AT-05**：在父答案里选词 → 200 生成子批注；默认折叠（`aria-expanded=false`、子卡不在 DOM）；点开后 `.annotation-children` 是父卡片的**兄弟节点**，子卡左边缘偏差 0px、宽同为 349px、带「第 2 层」；刷新后折叠条与 `rail-count` 不变，再展开仍能取到子批注。
- **AT-06**：删除按钮 `title` 与 `confirm` 都是「将同时删除 2 条追问」，确认后 DELETE 200、列表 7→4、父子三条一起消失、无孤儿。
- **AT-08**：把父答案里的「新生代」改成「新世代X」→ PATCH 200 → 刷新后子批注带「原文已修改」标签、答案 122 字与问题/引用词一字不少；改回后标签消失。
- **AT-09**：阅读页无「编辑正文」按钮，正文 141 块 / 11421 字，提问/追问/编辑答案均可用。
- **AT-10**：A 伪造 `targetId` 与伪造 parentId → 403；临时注册账号 B 后，B 读列表 / 用 A 的批注当 `parentId` / 顶层提交**全部 403**，资料库 0 条看不到 A 的材料；`t_ai_call` 178→178，证明拦截在模型调用之前。
- **AT-11**：SQL 造 8 层链 → 在最深层选词提示「追问链最多 8 层」；跨段 88 字 → 「请只选一段内的文字」；单段 320 字 → 「选中的文字最多 300 字，请选短一点」；**三次均无面板、零新增请求**。

**观察项**：1440px 分组与 p-38 顶部偏差 0px、父答案 `mark.quote-hit` 10 处（`rgb(250,238,218)`）、正文高亮 1 处、横向滚动 0；提问框 `fixed` 贴右栏且打开即聚焦；900px 横向滚动 0 且右栏落到正文下方（railTop 9024 > colBottom 9000）；全程控制台零报错。

**数据清理**：8 层测试链（3549~3556）、账号 `t018probe`、验收期间新增的 3545/3546/3547/3548 全部删除；库内恢复原有 3 条（1778 → 1779 → 1780），`t_ai_call` 保持 178，用户表恢复 3 个账号。

**如实说明**：① 链深 8 用 SQL 造链只验前端拦截，后端 `ask_链深达8_400` 仍由 T007 覆盖；② 「60~120 字」「明显承接上文」属实测字数核对与人工判读，不是自动断言；③ 换账号用临时注册的 `t018probe`（用后即删），没用既有的 user2 / user3；④ 选区由 Range + `mouseup` 模拟，其余交互均为真实 DOM 操作。

**结论**：**M1 通过，可在此停**。下一组按依赖走 G2（T008~T011，笔记生成后端链），四步全是后端，做完看不到界面变化，前端要到 T020 才重新有画面。截图 `preview/t018-at04-at05.png`、`preview/t018-at06-at08.png`、`preview/t018-chain-depth8.png`、`preview/t018-narrow-900.png`、`preview/t018-at10.png`。


---

## 2026-11-15 · G2 笔记生成后端链（T008~T011）

**背景**：M1 闸门通过后按依赖推进 G2。这一组四步全是后端，**界面没有任何变化**，验收物是「SSE 帧序 / 落库类型 / 观测 scene」三件事能被自动化断言。

**改动**
- `model/ChatEvent.java`（T008）：新增 `record Note(long noteId)`，`type()` = `"note"`；放在 `Done` 之前。sealed 接口加成员后无任何地方需要穷尽匹配，编译一次通过。
- `app/LoveApp.java`（T009）：新增 `noteChatClient` 与 `NOTE_PROMPT`（附录 A1 逐字）、`doNoteByStream(message, chatId)`。advisor 复用构造函数里同一个 `chatMemory` 实例（笔记的原料就是这轮对话），**刻意不挂 ToolCallback / QuestionAnswerAdvisor**。
- `service/ChatService.java`（T010）：`stream(...)` 扩为 6 参。分叉只在「事件从哪来」——笔记链路 `.map(ChatEvent::delta)` 之后并进同一条管道，累积全文 / 首 token 计时 / `publishOn(boundedElastic)` / 落库时机全部复用，避免第二份 `concatWith` 出现漏改。`asNote` 分支先 `appendNoteRound` 拿 id、发 `Note`、再发 `Done`；scene 记 `NOTE_GENERATE`。未标 `@Transactional`。
- `controller/AiChatController.java`（T011）：`StreamRequest` 加 `Boolean asNote`，`Boolean.TRUE.equals(req.asNote())` 作为第 6 参。用包装类型而非基本类型：老前端不发这个字段时必须当作 false，改成 `boolean` 会让 Jackson 直接 400。

**验证**
- `ChatServiceTest` 1 → **2/2**：新用例断言「两行落库且第二行 `type=NOTE`」「正文 = delta 拼接结果」「倒数第二帧是 `Note` 且 id 等于刚插入行 id」「`t_ai_call.scene=NOTE_GENERATE`」「`loveApp.doChatByStream` 一次未被调用」「`useRag/useTools` 传 true 也不影响分流」。
- 新增 `AiChatControllerAsNoteTest` **1/1**：不带 `asNote` 透传 `false`（不 400、不 NPE），带 `asNote:true` 透传 `true`。
- 项目约定的 9 类回归 **59/59** 全绿（`ChatServiceTest` 由 1 条变 2 条，其余不变），零失败 / 零错误 / 零跳过。

**如实说明**
1. **排布缺陷**：T010 改签名会让 `AiChatController`、`ChatServiceTest` 立刻编译失败，**T010 单独落盘无法编译**，与阶段自检「每个任务单独编译通过」冲突。这正是 0.1 节「改签名的一方排最后」要防的中间态，但 T011 的完成标准又要求 6 参先存在。实际做法是 **T010/T011 成对落盘后一次编译 + 跑测试**；编号 / 依赖 / FR 标签 / 闸门覆盖未改。
2. `NOTE_PROMPT` 的实际输出质量（是否真给 2~4 个 `##` 小节的 1000~2000 字成篇笔记）**本轮未验**，需要真实模型调用，留到 T022 联调时人工判读。
3. 本轮**没跑**含真实模型调用的全量 `mvn test`（约 T023），也没起浏览器（没有可看的界面改动）。

**结论**：G2 完成。下一组 **G3（T012，`GET/PUT /notes/{id}`）**，同样只有后端；**第一个能看到界面的任务是 T019/T020**。

---

## 2026-11-15 · G3 笔记读写接口（T012）

**背景**：G2 打通了「生成笔记」的生产端，这一组补上「读它 / 改它」的消费端，即 `plan.md` 5.2 #2 / #3。仍然是纯后端，**界面无变化**。

**改动**（两个新文件，未改任何既有类）
- `service/NoteService.java`：只注入 `MessageRepository`，**不注入 `JdbcTemplate`**（与 `AnnotationService` 同一纪律，避免 `type='NOTE'` 的 JOIN 出现第二份）。内嵌 `NoteView` / `UpdateRequest` / `UpdateResult` 三个 record。读和改共用私有 `requireOwnNote()`，两条路径的拒绝文案逐字相同（「笔记不存在或无权访问」）。
- `controller/NoteController.java`：`GET /notes/{id}`、`PUT /notes/{id}`。只做鉴权 + `@Valid` + 裸返回。

**两处契约取舍**
1. `PUT` **允许提交空串**：覆盖写就是覆盖写，用户把整篇删空再保存是他的权利；只挡 null 与超 20000 字。不替用户猜「空是不是误操作」。
2. `PUT` 请求体用 DTO（多传字段由 Jackson 忽略），不像 #6 的 `PATCH /annotations` 那样严格拒收——`plan.md` 只对 #6 提了「只接受该字段」，#3 没这个要求。

**验证**：新增 `NoteControllerTest` **11/11**，全程打真实库。
- 未登录 → 401。
- 读自己的笔记 → 200，`messageId` / `conversationId` / `type=NOTE` / `content` / `createdAt` / `updatedAt` 全齐。
- 读别人的、读不存在的、把本人会话里的普通 `ANSWER` 消息当笔记 id → **三条一律 403 且文案逐字相同**（否则拿一串 id 试一遍就能探出哪些笔记真实存在）。
- `PUT` 成功 → 库内正文变、`updated_at` 严格晚于改前、`created_at` 不动。
- 越权 `PUT` → 403 且库内正文一字未改。
- 超 20000 字 → 400「笔记正文最多 20000 字」；缺 `content` → 400「content 不能为空」；空串 → 200 且库内清空。
- 约定回归 9 类 + 本轮两个新类共 **71/71** 全绿。

**如实说明**
1. 用例**没有**用 `@Transactional` 回滚：PostgreSQL 的 `now()` 取的是事务开始时间，测试事务里插入与更新拿到同一时间戳，「`updated_at` 往前走」这条根本断不出来。改成显式建数据 + `@AfterEach` 按 `t_message` → `t_conversation` → `t_user` 倒序删。
2. 第一次跑挂了 2 条，原因是用例里 `"t-note-intruder-" + UUID` 共 52 字符，撞 `t_user.username varchar(50)`，已把前缀缩到 `"t-intr-"`。不是业务代码问题。
3. 运行中的 8123 是用户在 IDEA 启动的 JVM，**`/notes` 两个接口要重启后端才生效**；本轮只用 MockMvc 验，没起浏览器（无可看界面）。

**结论**：**后端侧 G1 / G2 / G3 三组全部打通**，`plan.md` 5.2 的七个接口只剩 #1 前端未接。下一步 **G5（T019~T022）**，**T019/T020 是第一个能看到界面的任务**。

---

## T019 + T020 · 笔记请求封装与笔记详情页（2026-11-15）

**背景**：G5 的前两步，M2 里**第一个能看到画面**的任务。前四组把后端和共享组件都做齐了，这一组第一次把「一条 NOTE 消息」渲染成一个可读、可编辑、可批注的页面。

**改动**
- `api/notes.js`（新增）：`get(messageId)` / `save(messageId, content)` 两个薄封装，`PUT` 只发 `{ content }`，空串原样提交。
- `views/NoteDetailView.vue`（新增）：`AnnotatableDoc` + `bodyEditable=true`。保存链路拆两半——组件只 `emit('save', draft)`，页面 `PUT` 成功后才 `markdown = draft` 并 `docRef.finishSave()`。
- `router/index.js`：`notes/:messageId`（name `note-detail`，`meta.requiresAuth`）与页面动态 import 同一次落盘。
- 自检脚本：`scripts/verify-notes.mjs`、`scripts/verify-NoteDetailView.mjs`。

**几个具体判断**
1. **保存失败必须保住草稿**：`catch` 里刻意不动 `markdown`、不调 `finishSave`，输入框停在编辑态，用户改完能直接重试。只有成功路径才退编辑态。
2. **「返回对话」用 `conversationId` 而不是 `router.back()`**：从聊天卡片点进来、直接贴链接进来、刷新后再点，三种入口都要能回到同一条会话；`NoteView` 里正好有这个字段。
3. **路径参数先自查**：`/^\d+$/` 不通过就直接报「笔记地址无效」，不白跑一趟请求去换后端 400。
4. **`:key="note.messageId"`**：换笔记时组件重建，否则上一张笔记的批注列表与折叠 id 集合会残留（与阅读页翻页同一处理）。

**验证**
- `node scripts/verify-notes.mjs` → **10/10**（adapter 拦 4 次请求、零出网）。
- `node scripts/verify-NoteDetailView.mjs` → **16/16**（10 条 SFC 契约 + 路由块 + Vite SSR 真实加载 + `bodyEditable=true` 的组件形态）。
- 既有 5 个前端自检全绿：TC-01 8、T014 14、T015 26、T016 46、T017 17；`npm run build` 通过，产物含 `NoteDetailView-*.js`。
- **ego lite 真实浏览器**（临时账号 + SQL 造的 NOTE 527 + 3 条批注，用后即删）：
  - 加载 `GET /api/notes/527` 200、`GET /api/annotations?...` 200；6 块正文、2 处原文高亮、右栏 3 条批注分 2 组、控制台零报错。
  - 编辑正文追加一段 → **`PUT /api/notes/527` 200**（带 `Origin` 的真实 XHR，顺带复查 CORS 白名单）→「已保存」、编辑态退出、`141 字 → 163 字`、`更新于 13:21`、块数 6 → 8。
  - 刷新：正文持久化、**旧批注 3 条仍全可读**（AT-07）、无 stale 组、再次可进编辑态。
  - 展开追问：子卡片出现、「选中词 向量距离 第 2 层」标签在、仍是独立平铺。
  - `/notes/999999` → `GET` 403 → 横幅「笔记不存在或无权访问」+ 重试，无空白页。
  - 截图归档 `preview/t020-note-page.png` / `-expanded` / `-edit` / `-403`。

**如实说明**
1. 保存**失败**路径只在自检断言层面验过（catch 里两不动），浏览器里没法在不弄停后端的情况下可靠触发。
2. 本轮验收数据是 SQL 直接造的 NOTE 与批注，**不走模型生成**；真实 `asNote` 链路要到 T021 有了开关才点得到。
3. 首次浏览器访问 `/api/notes/*` 得到 **404**：8123 上跑的是加 `NoteController` 之前的 JVM（`/api/annotations` 返回 401 证明它存在）。请用户重启后端后才验通——**以后新增 Controller / 配置类，浏览器验收前先确认后端已重启**。

**结论**：`plan.md` 5.2 的七个接口，前端侧只差 #1（`POST /ai/chat/stream` 的 `asNote` 开关）。M2 剩 **T021 聊天侧开关与 NOTE 卡片** → **T022 闸门** → T023 收尾。

---

## T021 · 聊天侧开关与 NOTE 卡片（2026-11-15）

**背景**：M2 最后一个能看到新画面的任务。做完它，`plan.md` 5.2 的七个接口在前端侧全部接通 —— 用户可以在对话里勾一下「生成笔记」、让模型直接写成一篇、再从卡片点进 T020 做好的详情页。

**改动**
- `api/chatStream.js`：`streamChat` 加 `asNote = false`，写进请求体。api 层不解读 `note` 帧，只按 `event:` 名原样抛给上层。
- `utils/noteCard.js`（新增）：`noteCardOf(markdown) → {title, summary, chars}`，纯函数。标题取第一个 `#` 标题（没有就退化第一行正文，围栏代码块里的 `#` 不算）；摘要**跳过标题**取正文，剥掉强调 / 链接 / 行内代码 / 列表记号，丢掉分割线与表格分隔行，按字截 80 加省略号。
- `components/ChatRoom.vue`：`asNote` prop、`sent` 事件、`note` 帧写回 `type/noteId`、NOTE 消息四种形态、卡片文案缓存、历史映射带 `type`。
- `views/GuideChatView.vue`：第 4 个 `tool-btn`（排在「联网」之后、「做成题」之前）+ `onSent` 复位。
- `views/NoteDetailView.vue`：顶栏标题改 `noteTitle(markdown)`，与卡片同源。
- 自检：`scripts/verify-noteCard.mjs`（18 条）、`scripts/verify-chatNoteCard.mjs`（23 条）。

**几个具体判断**
1. **开关在 `send()` 开头取值，不是开流时取值**：空白会话的第一条消息要先 `await resolveChatId()` 建会话，而父组件收到 `sent` 就把它复位了；等开流再读 `props.asNote` 一定是 false，「这一轮」就丢了。
2. **复位放在「发送时」而不是「本轮结束时」**：生成一篇笔记要十几秒，流式期间留一个亮着但已不生效的开关，比冒一次“没生成笔记”的投诉更糟。
3. **生成中不摊正文**：半篇 Markdown 铺在聊天里既不能读也不能点，只留一句「正在写成一篇笔记…」，`note` 帧到了才换成卡片。反过来，流中断拿不到 `noteId` 时**降级成普通正文**显示已收到的内容 —— 宁可难看，也不能让用户觉得白等了 20 秒。
4. **卡片文案缓存 key 用 `noteId + 正文长度`**：模板要取标题 / 摘要 / 字数三样，流式期间任何一条消息变化都会让整列重渲染，每条都重扫一遍 2000 字的 Markdown 是白烧 CPU。
5. **卡片与详情页标题共用 `noteTitle`**：先做完卡片再开详情页，一眼看到「卡片写线程池核心数怎么定，点进去标题是 AI 生成的笔记」—— 同一个东西在两处说法不同，用户会怀疑点错了。

**验证**
- `verify-noteCard.mjs` **18/18**：标题 / 摘要 / 字数 / 代码块 / 分割线 / CRLF / 脏输入；另用假 `fetch` 返回一段 SSE，断言请求体多出 `asNote`、默认 false、`note` 帧解析成 `{noteId:527}`、帧序 `delta → delta → note → done`。
- `verify-chatNoteCard.mjs` **23/23**：16 条 SFC 静态接线（取值早于 await、`emit(sent)` 早于开流、note 分支、历史映射、四种形态、无 `v-html`、缓存、reduced-motion）+ Vite SSR 真渲染一条 NOTE 历史消息（`NOTE` 标签、标题去掉井号、摘要剥掉 `**`、`href="/notes/527"`、正文没被铺开、普通消息照旧、字数 meta）。
- 既有 7 个前端自检全绿：TC-01 8、T014 14、T015 26、T016 46、T017 17、T019 10、T020 **17**（新增一条顶栏标题断言）；`npm run build` 通过。
- **ego lite 真实浏览器（临时账号 + 真实模型，用后即删）**：开关默认关 → 点开 → 发送后立刻复位；`POST /api/ai/chat/stream` 200 且 body `asNote:true`；14 秒后卡片出现（`2227 字 · 可批注` / 标题 / 80 字摘要 / 打开笔记）；点进 `/notes/529` 顶栏标题与卡片一致、32 块正文、可编辑；刷新后卡片仍在（AT-02）；同会话不开开关再问一句，26 秒引导式回答、**没有第二张卡片**（AT-01 / AT-13 的核心路径）。截图归档 `preview/t021-chat-note-card.png` / `-switch-on` / `-pending` / `-detail`。

**过程中撞到的一个环境问题（不是代码缺陷，但值得记）**
第二轮普通问答答完了却**没入库**，`t_ai_call` 记 `CHAT success=false error_code=TransactionSystemException`（`commit()` 抛 SQLException）。用 curl 重跑同一句立刻正常入库（`CHAT success=true`，`t_message` 多出 USER/QUESTION + ASSISTANT/ANSWER 两条），同时本机 `psql` 对 72.155.89.172 也间歇 `connection timeout`（约一半失败）。判断是**远程库网络抖动**导致的提交失败，而非本轮改动。

副作用更值得警惕：那次失败的提交在库侧留下一个 **idle in transaction 1023 秒**的会话（最后一条语句正是 `insert into t_message ...`），持着事务 id 锁，把后续清理用的 `DELETE` 全堵在 `wait_event_type=Lock` 上，表现为「psql 连上了但一直不返回」。`pg_terminate_backend` 掉它和被阻塞的清理会话后，删除一次通过。**以后遇到 DELETE / UPDATE 长时间不返回，先查 `select pid, state, xact_start, wait_event from pg_stat_activity where datname='ai_agent_study'`。**

**结论**：`plan.md` 5.2 七个接口前后端全部接通。M2 剩 **T022 闸门**（AT-01/02/03/07/10 笔记半边/12/14 与 TC-07/09）→ T023 收尾。

---

## T022 · M2 验收闸门（2026-11-15）

**这一轮没有新代码**，只做一件事：确认 G1（后端链路）+ M1（阅读页批注）+ T019~T021（笔记详情页与聊天卡片）拼起来是一条真的能走通的链路。

### 自动化

| 侧 | 结果 |
|---|---|
| 后端约定 11 类 | **71/71**，`failures=0 errors=0`（比 M1 时的 58 条多出 `NoteControllerTest` 11 + `AiChatControllerAsNoteTest` 1 + `ChatServiceTest` 1） |
| 前端 9 个自检 | 8 / 14 / 46 / 26 / 17 / 10 / 17 / 18 / 23 全绿 |
| `TC-07` | `ChatServiceTest` 断言帧序 `delta → note → done`、`note` 帧带 `noteId`、落库行 `type='NOTE'`、`scene=NOTE_GENERATE` |

### 真实浏览器（临时账号 + 真实模型，用后即删）

一次脚本跑完 6 个场景，全部 `ok`：

1. **AT-01**：关开关 → 4.6 秒引导式回答（结尾「现在轮到你：你的任务是哪一类？」），零卡片；开开关 → `aria-pressed` 真、发送后立刻回 false → **10.2 秒**出卡片（`2440 字 · 可批注`）。
2. **AT-02**：刷新后卡片仍在 → 点进 `/notes/573`，28 块正文，顶栏标题与卡片一致。
3. **批注两条**：顶层引用 14 字 → 答案 200 字；在答案里选 4 字追问 → `rail-count=2`，库里 `t_annotation` 新增行的 `parent_id` 指向前一条（**子批注挂对了，不是又一条顶层**）。
4. **AT-12**：900px → 批注栏 `top 3227 ≥ doc-col bottom 3203`（移到下方）、`scrollWidth == clientWidth`（无横向滚动）。
5. **AT-03**：改数字 `3→4` + 追加一段 → 保存 → 刷新：块数 28→30、`textarea` 已退出、`[contenteditable]` 0 个、富文本工具栏 0 个。
6. **AT-14**：`t_ai_call` **272~275** = `CHAT` / `NOTE_GENERATE` / `ANNOTATION` / `ANNOTATION_CHILD`，四行 `success=t`、`error_code` 全空。
7. **AT-10**：另注册 B → 读 A 的笔记 403、拉 A 的批注 403、把 A 的批注 id 当 `parentId` → 403「无权访问该正文」。

### AT-07 第一次没过，是验收构造的问题，值得留档

第一次的写法：选中 `ThreadPoolExec`（一个词的前 14 字）→ 批注 → 编辑正文把这个词删掉 → 刷新。预期进「原文已修改」，实际**没有**：批注被挪到了「第 4 段」，标签也没变。

原因在 `resolve()` 的降级顺序——「原 `blockKey` 命中 → 按 `quote` 全文 `includes` 搜索 → 都没有才 stale」。正文里还有五处 `ThreadPoolExecutor`，`ThreadPoolExec` 作为**子串**依然能命中，于是它被"成功地"重定位到了别处。

结论：**这不是缺陷，是 FR-10 写明的降级**（宁可丢「贴得准」，不得丢内容）。但用户感受会是「批注跟着一个词跑掉了」，所以：
- 让引用进 stale 的前提是**引用片段在正文里唯一**，或它所在的整段被删；
- 补验时改成先在正文末尾插一句独一无二的「量子纠缠不传递信息」→ 批注它 → 删掉整段 → 刷新 → **「原文已修改 1」分组出现、卡片带 `原文已修改` 标签、答案 143 字一字不少**（PASS）。
- 这条限制记在这里，不改实现、不另开任务。真要改（比如要求 quote 匹配时检查唯一性、多处命中就当 stale），会牵动「引用是一句话还是一段」的产品口径，不适合在收尾阶段动。

### 顺带发现

用户自己账号（`lcz`）名下已经有 1 篇真笔记：`t_message.id=534`「Kubernetes 入门笔记」4340 字（13:52 生成，伴随一条 `ANNOTATION`）。**做笔记列表页时可以直接拿它当第一条真数据，不必再造。**

---

## T024 / T025 · 追加需求：笔记列表页（FR-14，M3）

> 起因：用户 2026-11-15 自己生成了一篇笔记后发现「笔记的唯一入口是聊天里那张卡片，会话一多就找不回来」，要求在左侧导航加一个类似资料库的笔记页。按纪律先改 `spec.md`（新增 `FR-14`、`AT-15`/`AT-16`、移出 Non-goals 第 7 条）与 `plan.md`（5.2 加接口 #8），再排 T024 / T025。**T001~T023 的编号、依赖、FR 标签与闸门覆盖一律没动。**

### T024 · 后端 `GET /notes?limit=50`

三处改动，一条 SQL：

- `MessageRepository.findNotesByUser`：`t_message m JOIN t_conversation c` + `LEFT JOIN t_annotation a`，
  `count(a.id)` 算批注数，`order by m.updated_at desc, m.id desc limit ?`。
- `NoteService.list`：只做 limit 夹取 + 行转换，不注入 `JdbcTemplate`（与 `update` / `get` 同一条纪律，归属 JOIN 只能有一份）。
- `NoteController`：`@GetMapping` 裸返回 `List<NoteListItem>`。

三个刻意的决定：

1. **`limit` 夹取而不报错**：null / >50 → 50，≤0 → 1。这不是需要用户纠错的输入错误，取边界值即可，少一个错误码分支。
2. **排序补 `m.id DESC`**：同一 `now()` 写入的多篇（批量造数、测试）`updated_at` 完全相同，只按时间排结果不确定。
3. **`target_id` 必须 `m.id::text`**：`t_annotation.target_id` 是 varchar、`t_message.id` 是 bigint。漏 cast 在 PG 里是直接报 `operator does not exist`，不是「只是慢」；反过来把 `target_id` cast 成 bigint 则会因脏数据整条查询崩。

批注数**不额外过滤 `a.user_id`**：笔记归属单一用户，只有正文所有者能批注（FR-12），JOIN 天然只汇到本人。plan 里已注明将来支持共享笔记要补 `a.user_id = c.user_id`。

验证：`NoteControllerTest` 16/16（新增 5 条：401 / 空数组 / 倒序 + 批注数含子批注 + 普通消息不入列 / 归属隔离 / limit 与越界夹取）。其中「倒序」用**先建两篇、再 PUT 保存第一篇**来验 —— 只靠插入顺序无法区分「按 updated_at 排」与「按 id 排」。

一处踩坑：测试用户名用 `"t-note-list-order-" + UUID` 超了 `t_user.username varchar(50)`，四条用例一起报 `value too long`。前缀改短即可，与实现无关。

### T025 · 前端列表页

- `api/notes.js` 加 `list(limit = 50)`，固定带 limit（默认值只在后端存一份）。
- `views/NotesView.vue`：PageHeader + 发丝线行列表（不用卡片，与资料库的章节行同一气质）；每行标题 / 摘要 / 字数 / `N 条批注` / 更新时间；整行 `RouterLink` 到 `note-detail`。
- `NavRail` 第 3 项「笔记」（折角纸 + 两条文字，避开资料库那本书）；`router` 加 `notes` 静态路由，**排在 `notes/:messageId` 之前**。
- **文案全部走 `noteCardOf()`**，页面里没有第二套标题 / 摘要提取 —— 这是 T021 已经踩过的坑（卡片说 A、点进去标题是 B）。自检里直接断言页面内不存在 `^#{1,6}` 正则与 `slice(0, 80)`。

两个实现细节：

1. 页面加了 `initialNotes` prop（默认 `null`）**只给 SSR 自检注入数据**：SSR 不执行 `onMounted`，没有它就只能验加载态。与 `AnnotatableDoc` 的 `initialAnnotations` 同一做法，生产路径不传。
2. 行文案用 `Map` 缓存、key 带正文长度（`messageId:len`）：模板里 `card(n)` 调了三次，没缓存会为每行重扫三遍 Markdown。key 带长度是为了「同一条笔记保存后正文变了自动失效」。

验证：`verify-NotesView.mjs` **27/27**（adapter 拦 2 次请求断言 URL / limit；SFC 静态断言五字段、复用 `noteCardOf`、无 `v-html`、无二次排序；SSR 真渲染两篇断言倒序与 href、空数组断言空态、XSS 内容断言 `<script>` 被转义）。既有 9 个前端脚本全绿，`npm run build` 通过。`verify-notes.mjs` 有一条「导出恰好两个薄封装」随之改成三个（`get` / `list` / `save`），这是新增导出必然要改的断言，不是放宽标准。

### T025 真实浏览器验收（ego lite，2026-11-15 14:2x）

后端重启后 `/api/notes` 从 404 变 401（说明 handler 已上，只是没登录）—— 记一笔：后端 context-path 是 `/api`，直接 `curl /notes` 永远是 404，别误判成「接口没生效」。

浏览器会话每轮都掉（ego lite 的空间不稳定，JSESSIONID 不跨轮保留），于是把「登录 + 全部断言」压进同一轮脚本，开头统一 `if (location.pathname.startsWith('/login'))` 就地重新登录。密码不掌握用户账号的，改用临时账号 `t-m3-0qzt0a`（脚本里自建、验完删），完全不碰 `lcz` 的数据。

| 项 | 结果 |
|---|---|
| `AT-15` 倒序 | 「线程池核心数怎么定」(646) 在前、「Kubernetes 入门笔记」(647) 在后 —— 与 `updated_at DESC` 一致 |
| `AT-15` 批注数 | `133 字 · 2 条批注` / `117 字 · 0 条批注`，与库里 count 一致 |
| `AT-15` 跳转 | `href=/notes/646`、`/notes/647`；点第一行 → 6 块正文、标题一致、右栏「批注 2」两条各自贴在对的段落 |
| `AT-15` 返回 | 点导航「笔记」回列表仍两行、导航项高亮 |
| `AT-16` 空态 | 同一账号造数据前：0 行 +「还没有笔记」+ 引导开「生成笔记」开关 + 「去对话」→ `/guide` |
| `AT-16` 隔离 | 此时 lcz 名下有笔记，临时账号一条都看不到 |
| 控制台 | `error` / `unhandledrejection` / `console.error` 钩子三轮全空 |

清理：临时账号 1 个（含 2 会话 2 消息 2 批注）+ 早先给 lcz 造的验收笔记 `645`（含 2 批注 1 会话）全部删除；复查后库里只剩 lcz 的 `534`，`t-m3-%` 用户数为 0。

**顺手修的一处不一致**：列表 `133 字` vs 详情页 `128 字`。详情页顶栏原来用 `markdown.replace(/\s/g,'').length`（去空白但留着 `#`），卡片 / 列表用 `noteChars()`（去 `#` 但留着行内空格）—— 两个数各自都算得对，用户看到的是同一篇笔记两个字数。已统一到 `noteChars`，`verify-NoteDetailView` 加断言防复发（17 → 18）。复验：两处都是 `133 字`。

---

## T023 · 全量回归与执行记录归档（2026-11-15）**PASS**

> 这一轮同样没有生产代码，只有两件事：把「改造有没有弄坏别的链路」用能拿到的证据钉死，把整个 001 的执行结果写成一份可复查的记录。

### 1. 自动化：全量 `mvn test` 首次在改造后跑完

`mvn test` → **`Tests run: 104, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS` / 14 分 20 秒**。

这套件里含真实模型调用的评测类，此前只在 **2026-10-26（改造前基线）** 跑过，所以这一跑本身就是「行为与改动前一致」的证据：

| 类 | 耗时 | 覆盖的既有能力 |
|---|---|---|
| `RagAnswerEvalTest` | 346.8s | RAG 回答质量 |
| `RagBaselineEvalTest` | 154.2s | RAG 基线 |
| `RagEvalTest` | 5.2s | 检索 |
| `QuizPromptEvalTest` | 58.6s | **出题** |
| `GradingEvalTest` | 22.0s | **判分** |
| `LiManusTest` | 72.5s | **Agent / 工具链路** |
| `LoveAppTest` | 19.7s | **问答** |
| `tools/*`（7 类） | 4.8s | 时间、文件、PDF、下载、终端、抓取、搜索 |

另有约定 11 类（批注四接口 + 笔记 + 会话 + Chat）**76/76** 单独跑过。

### 2. `AT-13`：五条链路逐条实测（临时账号 + 真实模型）

| 链路 | 实测 |
|---|---|
| 普通问答（开关关） | 886 字，通篇「我们一步步来」「你可以先给任务归个类」，**零卡片**，`scene=CHAT` |
| RAG | 回答逐句引用上传资料原文（`allowCoreThreadTimeOut`、队列扩容顺序、拒绝策略），`参考了 4 段资料` + 4 条来源，`scene=CHAT` |
| 工具 | 问「现在几点」→ `.tool-row` 1 条 `getCurrentTime` → 答「2026年11月15日 14:49:35」，`scene=CHAT` |
| 出题 | 点「做成题」→ 遮罩 8 秒 → `/quiz/…` 3 道题，`scene=QUIZ_GENERATE` |
| 判分 | 提交作答 → 4 秒出 `FAIL` + 4 条缺口（题问「核心线程数指什么」，我答的是「怎么定」，判定正确），`scene=GRADE` |

### 3. `AT-01`~`AT-16` 复跑结果

| AT | 结果 | 关键证据 |
|---|---|---|
| AT-01 | PASS | 开开关 → `2693 字 · 可批注` 卡片，`href=/notes/747`，发送后 `aria-pressed` 立刻回 false，无普通气泡 |
| AT-02 | PASS | 刷新后 10 行消息、1 张卡片仍在 → 点进 `/notes/747` 32 块正文，`h1` 与卡片标题一致 |
| AT-03 | PASS | `/notes/750` 改 `0.75→0.8` → 保存 → 刷新：新值在、旧值无、编辑态已退出；工具栏只有「编辑正文」，`[contenteditable]` **0** |
| AT-04 | PASS（含一条软口径偏差） | 选 24 字提问 → 提问框 `position: fixed` 就地弹出且**自动聚焦** → 3 秒出答案；`rail-count` 0→1。**答案 158 字，超出 60~120 口径**（T018 时同构造为 103 字）—— 属内容长度波动，不改实现 |
| AT-05 | PASS | 在答案里选 8 字 → 标题变「在解释里选词追问」→ 子批注**默认不在 DOM**（`aria-expanded=false`）→ 点开 `data-children-of=6208`、`第 2 层`、`leftDelta=0` `widthDelta=0`、`nestedInsideParent=false`（独立平铺）→ 刷新后 `rail-count=2`、仍默认折叠 |
| AT-06 | PASS | `title`「将同时删除 1 条追问，删除后不可恢复」；`confirm`「将同时删除 1 条追问，确定删除吗？」→ `rail-count` 2→0、卡片 0、子批注 0、右栏回空态 |
| AT-07 | PASS（第二次） | 笔记末尾追加唯一句「量子纠缠不传递信息…」→ 保存 → 选它提问（120 字）→ 删掉该句 → 保存 → 刷新：`__stale__` 组「原文已修改 1」、卡片带「原文已修改」、答案 120 字与问题一字不少 |
| AT-08 | PASS（第三次） | 把子批注 quote **整体**替换掉 → 刷新：子卡片「原文已修改」、`is-stale`、答案 129 字保留 |
| AT-09 | PASS | 阅读页 7 块正文；`.edit-body-btn` / `.doc-toolbar` / `.body-textarea` 全为 **0**；选句提问、选词追问、编辑答案全部可用 |
| AT-10 | PASS | B 读 A 的笔记 403「笔记不存在或无权访问」；拉 A 的批注 403「无权访问该正文」；把 A 的批注 id 当 `parentId` 403「无权访问该正文」；读 A 的资料章节 403「资料不存在或无权访问」 |
| AT-11 | PASS | 跨 `p-1 → p-3` 选区 → 提示「请只选一段内的文字」、**不弹提问框**、`rail-count` 不变；链深 8 与限额满 200 由 `AnnotationControllerTest` 覆盖（均带「模型未被调用」断言） |
| AT-12 | PASS | 900px：`railTop 857 ≥ colBottom 833`（批注栏移到正文下方）、`scrollWidth == clientWidth == 900` |
| AT-13 | PASS | 见上表 |
| AT-14 | PASS | `t_ai_call` 7 种 scene 全覆盖且全 `success`：`CHAT` 5 / `NOTE_GENERATE` 1 / `QUIZ_GENERATE` 1 / `GRADE` 1 / `ANNOTATION` 3 / `ANNOTATION_CHILD` 2 / `SUMMARY` 2；`error_code` 全空，无约束报错 |
| AT-15 | PASS | 两行倒序：「Kubernetes 中 Pod 与 Deployment 的关系」`2,693 字·1 条批注·更新于 11-15 14:55` 在前，「Java 集合框架速记」`151 字·0 条批注·更新于 11-15 12:55` 在后；`href` `/notes/747`、`/notes/750`；导航「笔记」高亮；点进 32 块、右栏「原文已修改 1」 |
| AT-16 | PASS | 新账号 `/notes` 0 行 +「还没有笔记」+ 引导开「生成笔记」+「去对话」→ `/guide`；此时 A 名下已有 2 篇，**一条都看不到** |

### 4. `AT-07` / `AT-08` 各失败两次，都是验收构造的问题

这跟 T022 那次 `ThreadPoolExec` 是同一个坑，值得单独记一笔：**`resolve()` 的降级是「原 `blockKey` 命中 → 按 `quote` 全文 `indexOf` → 都没有才 stale」，所以只要引用片段的字面上还是子串，它就应当继续命中。**

两次踩法：

1. `AT-08` 第一次：子批注引用「话说的是核心线程」，父答案里把「核心线程」改成「核心线程**X**」。听起来是「改掉了被追问的词」，但**插入一个字符不会破坏原子串** —— 「话说的是核心线程X」里仍含「话说的是核心线程」，于是正确地没有 stale。
2. `AT-08` 第二次：改成替换父答案里另一处的「显式→手动地」。这次改的位置和子批注引用**根本不重叠**，当然也没 stale。
3. 第三次改成「读出子批注的 `quote`，在父答案里把这个字符串整体替换」，一次通过。

`AT-07` 同构：第一次删的句子与引用所在位置不完全重合，引用仍是子串；改成「先追加唯一句、批注它、再删掉整句」才进 stale。

**结论：实现没问题，是这个降级设计的必然表现。** 验收时要构造「引用片段的字符本身被破坏」，而不是「附近内容变了」。这条已写进 `spec.md` `AT-07` 的判定边界，不另开任务。

### 5. 顺带发现两件事

**（1）RAG 第一次回答「上下文是空的」，是资料形态问题，不是缺陷。**
`t_knowledge_item` 带 `user_id`，资料按用户隔离：临时账号刚建时名下没有资料，`QuestionAnswerAdvisor` 拿不到上下文是**正确行为**。后来「做成题」流程自动存了一份资料，而那份资料是**对话原文**（含「我：/助手：」标记），模型看过之后说「两条分隔线之间没有任何内容」—— 它不认这段对话是资料。换一份结构正常的手册再问，回答逐句引用原文、`mentionsEmptyCtx=false`，RAG 完全正常。

**（2）从对话生成的资料只切出 1 章。**
`POST /knowledge-items` 传一份带 3 个 `##` 小节的 markdown，返回 `sections: 1`。切章规则按一级标题分文档、二级标题分章节之外还有别的条件，本轮没深究 —— 不影响本次验收（那 1 章 455 字照样被 RAG 检索到），记在这里备查。

### 6. 执行记录（本条即 R5 的兜底）

- **迁移脚本**：`db/migration-v2.sql`，**2026-11-14 19:40** 以 `psql -f` 打到 **`ai_agent_study`（PostgreSQL 16.15）**，`BEGIN … COMMIT` 一次通过（5×`ALTER TABLE` / `CREATE TABLE` / 2×`CREATE INDEX`）。
- **三个闸门**：`G1`（T007）**PASS** —— 9 类 57/57，四接口越权与限额在模型调用前被拒；`M1`（T018）**PASS** —— 阅读页选区批注 → 追问 → 折叠平铺 → 刷新层级仍在；`M2`（T022）**PASS** —— 7 条 AT + `TC-07` / `TC-09`；`M3`（T024/T025）**PASS** —— 笔记列表页。
- **已知限制 R1~R7 状态**：R1（笔记挤占 ChatMemory）接受未处理；R2（`marked` token 粒度）接受；R3（`blockKey` 按索引）由 `quote` 重定位兜底，本轮 `AT-07` / `AT-08` 再次实证；R4（非流式批注转圈）接受；R5（无 Flyway 可能漏跑）**由本条记录兜底，`TC-10` 未覆盖**；R6（编辑正文后历史回答不一致）接受；R7（旧 T004 的临时 SQL 技术债）**已由 v1.1 重排消除**。
- **`TC-10` 明确未覆盖**：需要另建一个「只跑 `schema.sql`、不跑 `migration-v2.sql`」的空库，再把应用指过去验证「接口全部失败且有明确报错」。本轮未做，**不得写成通过**。
- **依赖与 Key**：`pom.xml` / `package.json` / `package-lock.json` / `application*.yml` 的 `git diff` 全为空，无 Key 相关改动。
- **清理**：临时账号 2 个（A 用于全链路、B 用于越权）及其 17 条消息 / 3 个会话 / 3 条批注 / 2 份资料 / 4 条向量 / 15 条 `t_ai_call` / 3 个知识点全部删除；复查后用户只剩 `lcz` / `user2` / `user3`，笔记只剩 `lcz` 的 `534`（4340 字，未变），资料 9 条（原有）。
