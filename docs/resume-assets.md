# 简历素材 · ai-agent-study

> 本文件放**亮点台账**与**风险预案**——两者必须放一起，因为风险预案的规则就是「每发现一个预测外问题 → 自动进亮点台账当素材」。
> 返回 [决策日志索引](./decision-log.md) ｜ 相关：[表设计](./data-design.md)

***

## 亮点台账（每条 = 一个 bullet 草稿 + 面试官三层追问 + 一个数字）

> 规则：**扛不住三层追问的，当场从简历砍掉。宁要 3 条立得住的，不要 8 条会塌的。**
> 数字必须在做这一步时就顺手收集（压测、对比、复现用例），不要等写简历时编。

| # | bullet 草稿（问题 → 方案 → 指标）                                                                                       | 三层追问（答案必须长在自己代码里）                | 数字从哪来                    | 状态        |
| - | ------------------------------------------------------------------------------------------------------------- | -------------------------------- | ------------------------ | --------- |
| 1 | 修复会话记忆并发损坏：`saveAll` 无锁直接覆盖写 → 加锁 / 原子替换 → 并发压测下 0 丢失（原易损坏）                                                   | 原实现为什么坏？加锁粒度怎么选？为什么不用数据库行锁？      | 同一 conversationId 多线程写压测 | 素材已有，待压测  |
| 2 | 修复路径穿越 + 不可信反序列化：`conversationId` 直接拼文件名、Kryo `setRegistrationRequired(false)` → 校验 + 白名单 / 换 JSON → 攻击用例复现失败 | 攻击怎么构造？为什么 Kryo 危险？改完性能损失多少？     | 一个复现用例 + 修复前后对比          | 素材已有，待验证  |
| 3 | 两套记忆统一落库：Kryo 文件 + 内存 Map 各自为政 → 落 PostgreSQL（与 pgvector 同库同事务）→ 多实例共享、会话可检索                                  | 为什么不用 Redis？为什么不用 MySQL？表怎么设计？   | 重启后会话仍在 / 两实例共享会话        | 进行中（Day1） |
| 4 | 从零设计学习系统数据库：user / conversation / message / knowledge\_item / test\_record / review\_schedule                 | 为什么这么拆？消息角色与工具调用怎么存？主键为什么用 UUID？ | 表设计文档 + 约束               | 待做        |
| 5 | 复习调度：定时任务 + 幂等 + 固定 1/3/7 天 vs SM-2 取舍                                                                        | 幂等怎么保证？为什么不上 SM-2？任务重复执行会怎样？     | 同用户重复触发不产生重复复习           | 待做        |
| 6 | 出题质量控制：小评测集验证「不给答案、只挑漏洞」的提示词                                                                                  | 怎么评估出题质量？失败案例是什么？                | N 道题的通过率                 | 待做        |
| 7 | JDBC 参数边界：枚举对象直传 `JdbcTemplate`（PG 驱动不认自定义类型，直接拒执行）→ 显式 `name()` → 建会话接口从 500 转 201 | 驱动为什么不猜类型？为什么不能用 `toString()` 兜底？真要传对象该怎么写？ | 报错原文 + 建会话 HTTP 从 500 → 201 的对照 | 素材已有，待验证 |
| 8 | 流式落库的阻塞点：`doOnComplete` 里的 JDBC 阻塞跑在 IO 事件循环线程上 → `publishOn(boundedElastic)` 把整条下游（含 SSE 写）挪出 event loop → 并发下不再拖垮 loop | 阻塞了谁？`publishOn` 与 `subscribeOn` 差在哪？为什么不直接甩到别的线程？ | 回调所在线程名前后对照 + 并发压测 | 素材已有，待验证 |
| 9 | 假验收排查：契约承诺 400 带文案、测试只断言状态码 → 补 `MethodArgumentNotValidException` / `HttpMessageNotReadableException` 两个处理器，400 响应体从空变成前端可直接弹出的文案 | 为什么原来没生效？为什么文案不全塞进处理器？ | 补测前后 400 响应体对照 | 素材已有，待验证 |
| 10 | 前端登录态误判修复：`JSESSIONID` 是 HttpOnly（`document.cookie` 读不到）+ 未登录请求也会被 Spring 下发空会话 Cookie → 前端改用显式登录态标记 + `router.beforeEach` 守卫 → 未登录访问 `/guide` 立刻被拦 | 为什么不读 Cookie 判断？前端标记能被伪造，安全靠什么？为什么不上 JWT？ | 未登录访问 `/guide` 的 `Set-Cookie` 实证 + 4 条守卫验收；**根因已在服务端一并消除**：4 处参数式 `HttpSession` 改 `HttpServletRequest` + `getSession(false)` → 未登录响应头 `Set-Cookie` 从「有」变「无」 | 素材已有（前端 + 服务端两侧都已收口） |

**为什么不再靠技术拿亮点**：旧简历的「RAG 检索优化」实为 Spring AI 默认用法（已在决策日志的否决记录中移除），正是它让项目二被一眼看穿。堆技术栈是把短板放大，不是补短板。

***

## 风险预案（应对「深入后出现预测外问题」）

> **规则：每发现一个预测错误 → 当场记这里（现象 / 原因 / 解法）→ 自动进亮点台账当素材。**
> 偏差不是计划失败，是面试素材的来源。全程顺利的项目反而没故事可讲。

**已经发生过的三次偏差（先例，说明偏差没炸掉计划反而成了资产）**

- 能力校准发现「讲不出 why」→ 催生亮点台账的「三层追问」规则
- 读源码发现 `saveAll` 无锁覆盖写 → 成为台账第 1 条最硬的 bullet
- 配数据源时 YAML 缩进错位（`datasource` 被顶格出现的 `tavily:` 吃掉，成了 `tavily` 的子键）→ Spring 从未读到 `spring.datasource.url`，连 `JdbcTemplate` bean 都没创建。教训：**YAML 的层级只由缩进决定，别用"看起来在一个块里"来判断**；且"配置没生效"这类问题别靠盯屏幕猜，要么让程序把值打出来，要么看 AutoConfiguration 的连锁反应

**最可能炸的三个位置**

| 位置           | 风险                                                            | 应对                                |
| ------------ | ------------------------------------------------------------- | --------------------------------- |
| 记忆统一落库       | `ChatMemory` 接口与 `MessageWindowChatMemory` 的窗口语义耦合，改造复杂度可能超预期 | 先跑通「最小落库」（存得进、读得出），再谈窗口策略         |
| 出题 / 评判提示词质量 | 第一版大概率很烂                                                      | 台账第 6 条的小评测集就是为它准备的；烂是数据，不是失败     |
| 范围蔓延         | 最大的风险不是技术，是讨论吞掉 Day1                                          | MVP 已砍到两条机制；每轮元讨论必须落到一个可验证交付物才算结束 |

**惊吓预算**：12 月底 DDL 提前留 2-3 周缓冲。偏差算预算内支出，不算延期。

**第③/⑤格带出的记账待办**

- `POST /api/auth/logout` + 前端 `clearAuth()`：`setAuth()` 目前没有撤销出口（③格遗留）——**已修**（2026-10-20）：`AuthController.logout` 拍板「204 幂等」（成功与未登录调用都 204 无 body），前端 `stores/auth.js` 加 `clearAuth()`、`HomeView.vue` 顶栏加「登出」按钮，`api/auth.js` 加 `logout()`。新增 2 条测试，全量 27/27 绿。
- `HomeView` 顶栏登录态展示：登录后主页面还看不出"我是谁"——**已修**（2026-10-20）：`stores/auth.js` 加 `currentUsername`（`localStorage` 的 `ai-agent-username`），`LoginView.vue` 登录成功时 `setAuth(username.value)`。该字段是**纯显示缓存**，不参与鉴权（身份权威仍在服务端 session），丢了也不影响登录态。
- `HttpSession` 参数导致未登录也下发空会话 `JSESSIONID`（③格预测外问题 1）——**已修**（2026-10-20）：`CurrentUserResolver.requireUserId` 与 `AiChatController` / `ConversationController` 的 4 处参数由 `HttpSession` 改 `HttpServletRequest` + `getSession(false)`（`AuthController.login` 保留参数式 `HttpSession`，它必须写 session 属性）。MockMvc 侧的代理断言是 `assertNull(result.getRequest().getSession(false))`；真机可证伪验收是**响应头 `Set-Cookie` 从「有」变「无」**。
- `t_message.conversation_id` **缺索引**：⑤格派生的会话标题用相关子查询逐会话扫 `t_message`，另外 `GET /conversations/{id}/messages` 也按此列过滤；MVP 数据量下无感，**数据量上来再加**（`CREATE INDEX ... ON t_message(conversation_id)`）。
- **`updated_at` 只在 `MessageService.appendRound` 里更新**：将来出现别的"写消息"入口（复习闭环的 `FEEDBACK`、手工导入历史等），必须也调 `ConversationRepository.touch(conversationId)`，否则列表的"按最近活动排序"会漏掉这类活动。
- **列表里大量 `title` 为 `null`**：2026-10-20 实测 24 条会话里 22 条没有标题 —— 那些是③格之前 `/guide` 每次进入就新建一条 `PRACTICE` 会话、且走无鉴权教学接口**一条消息都不落库**留下的空会话。**已修**：⑤格前端 `/guide` 左侧栏对 `title` 为 `null` 的行兜底显示「（暂无内容）」，且把「新对话」改成**懒创建**（`side bar` 的「+ 新对话」只切空白态、等第一条消息真发出去才 `POST /conversations`）→ 空会话的**源头被封住**，这类脏行不会再新增。存量 22 条是否清理另定（清不清都不影响后端契约）。

**实测纠正过我的六处判断（记档，避免以后重犯）**

- 断言「int 传 varchar 会报 `42804`」→ 实测**允许**（`int → varchar` 属解析器 I/O 转换，**不在 `pg_cast` 目录里**）；反向 `varchar → int` 才不允许。别把"PG 强类型"当成双向规则
- 断言「MockMvc 能断言 `Set-Cookie: JSESSIONID`」→ 实测失败，`Cookies = []`。**写 `Set-Cookie` 是容器（Tomcat）的职责，MockMvc 丢掉了容器**。修法是断言 session 属性而非 cookie
- 断言「枚举对象直传 `JdbcTemplate`，驱动会按 `toString()` 兜底」→ 实测**直接拒绝**：`org.postgresql.util.PSQLException: Can't infer the SQL type to use for an instance of ...ConversationMode`（`BadSqlGrammar`，两条 Repository 用例全 ERROR）。**JDBC 驱动只认标准类型，不认识的自定义类型它不猜、直接抛**。修法是显式 `mode.name()`（不可覆写、与 DB 的 CHECK 字符串逐字对齐），不是 `toString()`（可覆写，将来有人为了日志好读改一下，就会撞 CHECK 报 `23514`）
- 按④契约 §2.3 的表格以为「400 的响应体已经带文案」→ 实测**不带**：`GlobalExceptionHandler` 只实现了 401/403，两类 400 由 Spring 的 `DefaultHandlerExceptionResolver` 接走，响应体为空。证据是日志里的 `Resolved [MethodArgumentNotValidException: ...]` / `Resolved [HttpMessageNotReadableException: ...]`——**`Resolved` 二字说明拒绝者不是我们的处理器**。修法是补齐 2 个 `@ExceptionHandler`。教训：**「契约里写了 body」+「测试只断言状态码」= 假验收**；文档承诺的输出形状，必须有测试真的去读它
- 判过 `ChatService.stream` 的方法体「可以过」→ IDE 提示「在非阻塞上下文中使用阻塞调用可能会导致线程匮乏」：`doOnComplete` 里的 `appendRound` 跑在**数据源线程**（`WebClient` 场景即 event loop）上做 JDBC 阻塞。修法是 `publishOn(boundedElastic)` 把整条下游挪出 event loop；**不能直接甩到别的线程**——异常会逃逸，`onError` / `completeWithError` 再也收不到。顺带记牢 `subscribeOn`（只管订阅/request）与 `publishOn`（管下游全部信号）的区别
- 以为「前端可以直接读 `JSESSIONID` Cookie 判断登录态」→ 实测**读不到、也判断不了**：`JSESSIONID` 是 **HttpOnly**（`document.cookie` 永远为空，浏览器仍自动随请求携带）；且 **Spring MVC 解析 `HttpSession` 参数时执行 `getSession(true)` 按需创建空会话**——**未登录请求同样被下发 `Set-Cookie`**，于是「有 Cookie ≠ 已登录」。修法是前端用 `localStorage` 显式标记驱动 `router.beforeEach`，安全仍由后端 session 兜（前端标记即便被伪造也越不了权）。**服务端侧已修**（2026-10-20）：控制器参数由 `HttpSession` 换 `HttpServletRequest` + `getSession(false)`，未登录请求不再建空会话

通用教训：**每次给出可证伪的预测 + 让程序真跑一遍，比靠推理更可靠。**

***

## 纵切实施记录的规则（配合使用）

- 每一格的踩坑不写在本文件，写进 [`impl-log.md`](./impl-log.md)；其中「**预测外问题**」按上面规则转记到本文件的风险预案，并进亮点台账。
- 台账第 1/2/3 条对应第①~⑤格之外的既有资产；第 4 条对应表设计 v1；第 5/6 条对应 MVP 主干后段；第 7 条对应第②格踩坑；第 8/9 条对应第④格的两个预测外问题；第 10 条对应第③格的 HttpOnly Cookie 登录态误判。
