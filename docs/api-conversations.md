# 接口文档 · 会话（第②格纵切）

> 状态：**待实现**（2026-10-18 定稿）。实现完把结果回填到 [`impl-log.md`](./impl-log.md) 的第②格。
> 分层与写法约定见 [`impl-log.md`](./impl-log.md) 第①格；表结构见 [`data-design.md`](./data-design.md)。
> 本文档只覆盖**第②格**：`POST /conversations`。其余接口在 §7 明确划界。

***

## 1. 本格范围

**做**

- `POST /conversations` —— 建会话，返回服务端生成的 `conversationId`
- 三块**本格顺带立起来、后面三格复用**的基础设施：`CurrentUserResolver`（session → userId）、全局异常处理器、统一错误响应体

**不做（划界，别越界）**

| 项 | 留给 |
| --- | --- |
| `GET /conversations`（会话列表） | 未排期 |
| `GET /conversations/{id}/messages`（历史恢复） | 第⑤格 |
| 归属校验 `assertOwned` + 403 生效 | 第④/⑤格（那里才第一次访问"已存在的会话"）；`ForbiddenException` 本格先建好、先不触发 |
| 消息落库 | 第④格 |
| 懒创建（不再单独建会话） | 不做（YAGNI） |

**已就绪、本格直接复用**：`ConversationRepository.create(UUID, String)`、`SessionKeys.USER_ID`、`PasswordConfig`（`PasswordEncoder` bean）、`AuthController`（登录写 session）。

***

## 2. 全局约定（本格确立，后续所有接口沿用）

### 2.1 路径

- Controller 上的 `@RequestMapping` 写**相对路径**，**不含 `/api`** —— 前缀由 `server.servlet.context-path` 提供。
- 完整路径 = `/api` + 相对路径。本文档「路径」一栏写相对路径，示例中写完整路径。
- **MockMvc 测试同理**：`post("/conversations")`，不是 `/api/conversations`。

### 2.2 认证

- 认证载体是 **`HttpSession`**：登录成功时 `AuthController` 往 session 写入 `SessionKeys.USER_ID`（UUID）。
- 后续所有接口**不接收 userId 参数**，一律从 session 取——**客户端不可自报身份**（这是"越权校验"能成立的前提）。
- 前端请求必须带 Cookie（`withCredentials: true`），否则 session 丢失 → 401。
- **未登录 / session 过期 → 401**。

### 2.3 统一错误响应体

所有错误响应体固定为一个字段：

```json
{ "message": "未登录" }
```

- 只给 `message`，**不加 `code` / `traceId` / `errors[]`** —— 前端 MVP 只需要一句话能弹出来（YAGNI）。
- 业务文案**由异常自己携带**（`new UnauthorizedException("未登录")`），处理器只做 `e.getMessage()` 转发。**不要把文案堆在处理器里**——否则文案与抛异常的地方分居两处，改一处忘一处。

### 2.4 全局异常处理器

类：`com.lcz.aiagentstudy.web.GlobalExceptionHandler`（`@RestControllerAdvice`）

| 异常 | 状态码 | body |
| --- | --- | --- |
| `UnauthorizedException` | 401 | `{"message": e.getMessage()}` |
| `ForbiddenException` | 403 | `{"message": e.getMessage()}` |
| `MethodArgumentNotValidException`（`@Valid` 校验失败） | 400 | `{"message": 第一条字段错误的 defaultMessage}` |
| `HttpMessageNotReadableException`（body 不是合法 JSON / 枚举值非法） | 400 | `{"message":"请求体不合法"}` |
| 其他一切 | 交给 Spring 默认解析器 | — |

> ⚠️ **不要写 `@ExceptionHandler(Exception.class)` 兜底。** 它会把 Spring 自己产生的 `NoResourceFoundException`(404)、`HttpRequestMethodNotSupportedException`(405) 一起吞成 **500**，把一个"路径写错了"的排查变成"服务器炸了"。第①格定的"数据库炸了谁都不 catch、冒泡到 500"指的是**不 catch**，不是**兜底成 500**。

### 2.5 数据库异常的处理

`t_conversation.mode` 上有 `CHECK` 约束。**别把它当校验手段**：撞 CHECK 会返回 `23514` 并冒泡成 500，属于"参数非法"却报了 500。**参数合法性必须在边界挡住（400）**，CHECK 只是数据库侧的最后一道防线。

***

## 3. `POST /conversations` 契约

| 项 | 值 |
| --- | --- |
| 方法 | `POST` |
| 路径 | `/conversations`（完整：`/api/conversations`） |
| 请求头 | `Content-Type: application/json`；必须带有效 session Cookie |
| 认证 | **必须**，未登录 401 |
| 成功状态码 | `201 Created` |
| 成功响应头 | `Location: /conversations/{conversationId}`（相对路径） |

### 3.1 请求体

```json
{ "mode": "FREE_CHAT" }
```

| 字段 | 类型 | 必填 | 约束 | 说明 |
| --- | --- | --- | --- | --- |
| `mode` | string（枚举） | 是 | 必须是下面 4 个**大写**值之一 | 大小写敏感；非法值由 Jackson 挡在 400 |

| `mode` 取值 | 含义 | 场景 |
| --- | --- | --- |
| `SELF_TEST` | 自测会话 | 从材料出 3 道回忆题，记成绩，进复习闭环 |
| `FREE_CHAT` | 自由问答 | 复用 RAG 直接回答，不记成绩 |
| `REVIEW` | 复习会话 | 定时任务到点拉回，交错混排 |
| `PRACTICE` | 练习模式（引导式） | 自由问答的引导式入口，**不记成绩、不进闭环** |

- **只收 `mode`，不收 `title`**：理由见 §6 拍板表。
- **`mode` 必填、无服务端默认值**：理由见 §6 拍板表。

### 3.2 成功响应

```http
HTTP/1.1 201 Created
Location: /conversations/0d5a1d0e-3a1b-4d6f-9c2e-8b7f1a2c3d4e
Content-Type: application/json

{ "conversationId": "0d5a1d0e-3a1b-4d6f-9c2e-8b7f1a2c3d4e" }
```

- `conversationId` 就是 `t_conversation.id`（UUID 字符串），**由数据库 `gen_random_uuid()` 生成**，不来自前端（见 `impl-log.md`「接口约定」）。
- 字段名用 `conversationId` 而非 `id`：与第①格 `{"userId": ...}` 对齐（理由见 §6）。

### 3.3 失败响应

| 场景 | 状态码 | body |
| --- | --- | --- |
| 未登录 / session 过期 | 401 | `{"message":"未登录"}` |
| body 不是合法 JSON | 400 | `{"message":"请求体不合法"}` |
| 缺 `mode` | 400 | `{"message":"mode 不能为空"}` |
| `mode` 非法值（如 `"abc"`、`"free_chat"`） | 400 | `{"message":"请求体不合法"}` |
| 数据库异常（含撞 CHECK 约束） | 500 | 不 catch，冒泡 |

> 注：若 `@Valid` 静默失效（缺 hibernate-validator、注解被写成 `javax`、`@NotNull` 被删），"缺 mode"和"非法值"这两行就会**退化成 500 或真的写脏数据**。这是第①格确认的「静默失效家族」，务必由验收清单的用例守住。
>
> 另注：后两行都由 Jackson 在反序列化阶段抛出，处理器分不清"JSON 语法错"还是"枚举值错"（要分开得去翻 `InvalidFormatException.getTargetType()`），所以共用一句话。前端要精确区分就得自己先校验——**这是刻意的取舍，不是漏做**。

### 3.4 端到端手动示例（PowerShell）

```powershell
# 1) 登录，把 session cookie 存到 cookies.txt
curl.exe -i -c cookies.txt -X POST http://localhost:8123/api/auth/login `
  -H "Content-Type: application/json" `
  -d '{\"username\":\"你的用户名\",\"password\":\"你的密码\"}'

# 2) 带上 cookie 建会话
curl.exe -i -b cookies.txt -X POST http://localhost:8123/api/conversations `
  -H "Content-Type: application/json" `
  -d '{\"mode\":\"FREE_CHAT\"}'

# 3) 不带 cookie 再建一次 —— 应得 401
curl.exe -i -X POST http://localhost:8123/api/conversations `
  -H "Content-Type: application/json" `
  -d '{\"mode\":\"FREE_CHAT\"}'
```

- PowerShell 里 `curl` 是 `Invoke-WebRequest` 的别名，**必须写 `curl.exe`**。
- 给外部程序传 JSON 时 PowerShell 会吃掉双引号，**必须写 `\"`**。
- 也可以用 `http://localhost:8123/api/doc.html`（knife4j）点着调，但它带 session cookie 比较别扭，主验证还是走 MockMvc。

***

## 4. 分层落点与类清单

> **契约（类名 / 包 / 方法签名）按此表；方法体你自己写。**

| 层 | 类 / 位置 | 职责 | 契约 |
| --- | --- | --- | --- |
| Controller | `controller.ConversationController`<br>`@RestController` + `@RequestMapping("/conversations")` | HTTP 翻译：定 201、写 Location、拼响应体 | `ResponseEntity<?> create(@Valid @RequestBody CreateRequest req, HttpSession session)` |
| DTO | **嵌套** `record CreateRequest(@NotNull(message="mode 不能为空") ConversationMode mode) {}`（定义在 `ConversationController` 内） | web 层形状 | 沿用第①格拍板：只服务单入口的 DTO 就近定义 |
| Web 基础设施 | `web.CurrentUserResolver`<br>`@Component` | session → userId | `UUID requireUserId(HttpSession session)`；取不到 → `throw new UnauthorizedException("未登录")` |
| Web 基础设施 | `web.GlobalExceptionHandler`<br>`@RestControllerAdvice` | 异常 → HTTP | 见 §2.4 映射表 |
| 异常 | `exception.UnauthorizedException`<br>`exception.ForbiddenException`（均 `extends RuntimeException`） | 业务拒绝 | 构造器接收 message；`ForbiddenException` 本格先建好、不触发 |
| Service | `service.ConversationService`<br>`@Service` | 业务决策 | `UUID create(UUID userId, ConversationMode mode)`；**无分支判断**，落库失败让它冒泡 |
| Repository | `repository.ConversationRepository` | **已就绪，不改** | `create(UUID userId, String mode)`、`belongsTo(...)` |
| model | `model.ConversationMode`（`enum`，4 个常量名 = DB CHECK 里的字符串） | 跨层形状（Service/Controller 都用） | 常量名必须逐字等于 `'SELF_TEST','FREE_CHAT','REVIEW','PRACTICE'` |
| config | 无新增 | — | — |

### 4.1 三个实现要点

1. **`ConversationRepository.create` 收 `String`，Service 传 `mode.name()`** —— Repository 已写好、签名是 `String`，别为了"优雅"去改它。Repository 不认识领域枚举也没关系，它只负责"把两个值写进表"。
2. **`CurrentUserResolver` 用模式匹配转型，别强转**：
   ```java
   if (!(session.getAttribute(SessionKeys.USER_ID) instanceof UUID userId)) {
       throw new UnauthorizedException("未登录");
   }
   return userId;
   ```
   强转 `(UUID) obj` 一旦属性类型不是 UUID 会抛 `ClassCastException` → 冒泡成 500，而它实际是"登录态坏了"，应该是 401。`NoSuchBeanDefinitionException` 那种"类型不对报 500"的坑别在这里重演。
3. **`CurrentUserResolver` 放 `web` 包，不放 `service` / `controller`**：它只依赖 `HttpSession`（纯 web 概念）。塞进 `service` 会让 Service 层沾上 web 概念（违反"Service 不认识 HTTP"）；塞进 `controller` 又与 Controller 的职责不同。`GlobalExceptionHandler` 同理放 `web`。

### 4.2 目标调用链

```
POST /api/conversations {"mode":"FREE_CHAT"}
  → ConversationController.create(req, session)
      → CurrentUserResolver.requireUserId(session)   → UUID userId（取不到 → 401）
      → ConversationService.create(userId, req.mode())
          → ConversationRepository.create(userId, mode.name())
              → INSERT ... RETURNING id              → UUID conversationId
  ← 201 + Location: /conversations/{id} + {"conversationId":"..."}
```

***

## 5. 验收清单（Definition of Done）

全部通过才算第②格完成。测试类：`src/test/java/com/lcz/aiagentstudy/controller/ConversationControllerTest.java`，基建同第①格（`@SpringBootTest` + `@AutoConfigureMockMvc` + `@Transactional`）。

| # | 用例 | 断言 |
| --- | --- | --- |
| 1 | 未登录 + `mode=FREE_CHAT` | `401` + `jsonPath("$.message")` 有值 |
| 2 | 已登录 + `mode=FREE_CHAT` | `201`、`jsonPath("$.conversationId").exists()`、`header("Location")` 存在 |
| 3 | 用例 2 顺带查库 | `SELECT user_id FROM t_conversation WHERE id = ?` 的结果 **=** session 里的 userId（证明"身份取自 session 而非入参"） |
| 4 | 缺 `mode`（`{}`） | `400`（**这条是 `@Valid` / `@NotNull` 的看门狗**） |
| 5 | `mode="abc"` | `400` |
| 6 | `mode="free_chat"`（小写） | `400`（探明大小写敏感的边界，别让它静默换成大写） |
| 7 | 回归 | `AuthControllerTest` 3 条仍绿 |

**测试里怎么伪造登录态**：用请求构建器的 `sessionAttr`，不要去真的调一次 `/auth/login`：

```java
UUID userId = UUID.randomUUID();
jdbcTemplate.update("INSERT INTO t_user (username, password_hash) VALUES (?, ?)", "test-conv-" + userId, "x");

mockMvc.perform(post("/conversations")
        .sessionAttr(SessionKeys.USER_ID, userId)     // ← 直接伪造 session，零密码哈希开销
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"mode\":\"FREE_CHAT\"}"))
        .andExpect(status().isCreated());
```

用 `sessionAttr` 而不是走登录接口：少一次 BCrypt 计算，且**不依赖第①格的实现细节**（第①格改了登录响应不影响这条）。

***

## 6. 本格拍板

| 项 | 结论 | 理由 / 否决了什么 |
| --- | --- | --- |
| 成功状态码 | **201 Created** + `Location` | 创建资源的标准语义。否决"一律 200"：200 把"创建成功"与"读取成功"混成同一个信号，前端无法判断"重试是否安全"，也为后续幂等设计留下模糊地带 |
| `Location` 写法 | **相对路径** `/conversations/{id}` | 绝对路径需要拼 `/api`（context-path），拼错就是个安静的坏头。相对路径由客户端按当前 base 解析，天然正确 |
| 响应字段名 | `conversationId` | 与第①格 `{"userId":...}` 对齐。否决 `id`：太泛，接口一多就歧义（消息 id？会话 id？） |
| `mode` 类型 | **枚举** `ConversationMode` | 类型安全；非法值由 Jackson 挡在 400，**不会漏到 DB 撞 CHECK 变 500**。否决 `String` + `@Pattern`：正则与 DB 的 CHECK 是两份真相，会不同步 |
| `mode` 是否必填 | **必填，不给默认值** | 否决服务端默认 `FREE_CHAT`：会静默启用某模式，前端漏传就变成"行为不一致且难查" |
| 是否接收 `title` | **不接收**，保持 `NULL` | YAGNI：会话列表页还没做，没人读 `title`。等做列表页时再定"前端传 or 由首条消息/AI 生成" |
| 未登录怎么处理 | `CurrentUserResolver` 抛 `UnauthorizedException` + 全局处理器 | 未登录会在 **N 个**接口重复（建会话 / 发消息 / 读历史）→ 命中第①格定的规则：**会在 N 个入口重复的拒绝 → 异常 + 全局处理器**。否决"每个 Controller 各写一遍 session 判空"：那是 N 份重复的 if |
| 错误体形状 | 统一 `{"message": "..."}` | 前端只处理一种形状。否决 `code`/`traceId`/`errors[]`：MVP 用不上 |
| 处理器范围 | 只映射 §2.4 那 4 类，**不写 `Exception.class` 兜底** | 兜底会把 404/405 吞成 500 |
| 包结构 | 新增 `web`（Resolver + 全局处理器）与 `exception`（两个异常） | 分层要求：`controller` 包只放 Controller，`service` 包不出现 `HttpSession`，异常必须能被 Service 抛（故不能塞在 `web`） |

***

## 7. 已知坑（本格大概率会踩）

1. **MockMvc 断言不到 201 的 Cookie** —— `Set-Cookie` 是容器（Tomcat）的职责，MockMvc 没有容器（第①格已实测）。要验"session 里有 userId"就断言 session 属性；要验"HTTP 层写出去了"就断言 body/状态码。
2. **`@Valid` 静默失效家族** —— 缺 hibernate-validator / 注解写成 `javax` / 删掉 `@NotNull`，都会让用例 4 从 400 变 500 或真的写脏数据。第①格已有一次实证（`Resolved [MethodArgumentNotValidException...]` 出现在日志里才算真的生效）。
3. **枚举大小写** —— Jackson 默认按常量名精确匹配，`"free_chat"` 会直接 400。这是**特性不是 bug**（用例 6 固化它），别去加 `ACCEPT_CASE_INSENSITIVE_ENUMS` 把它改宽容——前端对自己的请求体负责。
4. **撞 CHECK 约束报的是 500** —— 看到 `23514` 说明边界校验没挡住，回去查 §3.3 那两行的 400 为什么失效，别去改 SQL。
5. **PowerShell 里 `curl` 是别名** —— 必须 `curl.exe`；JSON 双引号要写 `\"`。
6. **MockMvc 路径不带 `/api`** —— 带了就 404。

***

## 8. 完成后回填

实现并通过验收后，把以下内容补进 [`impl-log.md`](./impl-log.md) 的第②格小节：

- 实际产出（接口路径、返回样例）
- 实际拍板与本文档的差异（有差异按"版本化修订"记，不覆盖本文档）
- 踩到的坑（**预测外的**同时转记到 [`resume-assets.md`](./resume-assets.md) 的风险预案）
- 归属校验 `assertOwned` 是否顺带做了（预期：留到第④格）
