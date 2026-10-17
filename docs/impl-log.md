# 纵切实施记录 · yu-ai-agent

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
- 后续格：`ConversationService.assertOwned` 抛 `ForbiddenException`（归属校验会在 N 个接口重复）——见第②格

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

## 第②格纵切 · 建会话（进行中）

**目标产出**：`conversationId`（`POST /conversations` 返回 UUID）

**待做**：`CurrentUserResolver`（从 session 取 userId）+ `ConversationService.assertOwned` 抛 `ForbiddenException` + 全局异常处理器 + `ConversationController`；`ConversationRepository` 直接复用（已完成）。

***

## 第③~⑤格（待做）

| 格 | 产出 | 要点 |
| --- | --- | --- |
| ③ 前端接上 | 前端能拿到并使用 `conversationId` | `api` 加 `createConversation()`；`GuideChatView` 异步拿 id；修掉当前破损状态；`withCredentials` 带 Cookie |
| ④ 消息落库 | 一轮问答的 USER / ASSISTANT 写进 `t_message` | `POST /ai/chat/stream`；**同一轮多条消息必须同一事务按序插入** |
| ⑤ 历史恢复 | 会话可回读 | `GET /conversations/{id}/messages` |
