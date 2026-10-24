# 接口文档 · 会话历史（第⑤格纵切）

> 状态：**已实现**（2026-10-20 定稿，后端当日完成，见 [`impl-log.md`](./impl-log.md) 第⑤格）。
> 分层与写法约定见 [`impl-log.md`](./impl-log.md) 第①格；统一错误响应体与全局处理器见 [`api-conversations.md`](./api-conversations.md) §2；表结构见 [`data-design.md`](./data-design.md)。
> 本文档只覆盖**第⑤格的后端**：`GET /conversations`、`GET /conversations/{conversationId}/messages`。前端侧的改动在 [`frontend-learning.md`](./frontend-learning.md)：**练习 7**（`/guide` 左侧栏会话列表）、**练习 8**（点历史会话 → 灌历史 + 接着聊 + 懒创建），均已写入。

***

## 1. 本格范围

**做**

- `GET /conversations` —— 当前用户自己的会话列表（新到旧）
- `GET /conversations/{conversationId}/messages` —— 某个会话的全部消息（对话顺序）
- 顺带：`GlobalExceptionHandler` 补一条 `MethodArgumentTypeMismatchException` → 400（本格才第一次出现**路径变量**）
- 顺带：`MessageService.appendRound` 落库后调 `ConversationRepository.touch(conversationId)`，维护 `t_conversation.updated_at`（**2026-10-20 方案修改**，为列表按「最近活动」排序）

**不做（划界，别越界）**

| 项 | 留给 |
| --- | --- |
| 分页（`limit` / `offset`） | 不做（YAGNI）；列表超过一屏再加 |
| `t_conversation.title` 落库（前端传 / AI 生成） | 不做：列表标题由**首条 USER 消息派生**（见 §3.2），`title` 列继续 `NULL` |
| 删除 / 重命名 / 搜索会话 | 不做（MVP 外） |
| 消息分页、增量拉取 | 不做（MVP 外） |
| `POST /auth/logout` | 不做（③格遗留待办，与历史恢复无关） |

**已就绪、本格直接复用**：`CurrentUserResolver.requireUserId`、`ConversationService.assertOwned`（④格已实战生效）、`ConversationRepository`、`MessageRepository`、`MessageRole` / `MessageType` 枚举、统一错误响应体 `{"message": ...}`。

***

## 2. 本格新增的全局约定

### 2.1 路径变量非法 → 400（`MethodArgumentTypeMismatchException`）

`GET /conversations/{conversationId}/messages` 是本项目**第一次**出现路径变量。路径里的 `conversationId` 不是合法 UUID 时，Spring 在**参数绑定阶段**抛 `MethodArgumentTypeMismatchException`——它**不属于**现有 `GlobalExceptionHandler` 映射的 4 类，于是被 Spring 的 `DefaultHandlerExceptionResolver` 接走。

- 状态码是 **400**（`TypeMismatchException` 家族），但 **body 为空**——与④格「假验收」是同一型缺口：只看状态码会以为"没问题"。
- 修法：`GlobalExceptionHandler` 补一条映射 → 400 + `{"message":"conversationId 不合法"}`。
- **文案为什么是固定的**：拒绝方是框架的类型转换器，它**带不了我们的文案**，属**必要例外**（同④格 `HttpMessageNotReadableException` 的「请求体不合法」）。
- **路径变量必须命名为 `conversationId`**：`@GetMapping("/{conversationId}/messages")` + `@PathVariable("conversationId")`。这样异常 `getName()` 拿到的是 `conversationId` 而不是 `id`，文案自带字段名。

| 异常 | 状态码 | body |
| --- | --- | --- |
| `MethodArgumentTypeMismatchException` | 400 | `{"message":"conversationId 不合法"}` |

其余四类（401 / 403 / 两个 400）沿用 [`api-conversations.md`](./api-conversations.md) §2.4，不重复。

***

## 3. `GET /conversations`（会话列表）

| 项 | 值 |
| --- | --- |
| 方法 | `GET` |
| 路径 | `/conversations`（完整：`/api/conversations`） |
| 请求头 | 必须带有效 session Cookie；**无请求体** |
| 认证 | **必须**，未登录 401 |
| 成功状态码 | `200 OK` |

### 3.1 请求参数

**没有参数**。**不接收 `userId`** —— 身份只从 session 取（②格硬约束），客户端想自报也没地方报。

**服务端固定只返回对话类会话**：`mode ∈ ('FREE_CHAT', 'PRACTICE')`。理由：`SELF_TEST`（资料出题）与 `REVIEW`（到期复习）是测验流程的载体，入口在资料页 / 待办页，用户从不会在对话页继续聊这些会话；一旦返回，点一次「开始学习」就会在左侧栏多出一条标题为「第 1 题…」的假会话，把真实对话挤下去（2026-10-24 修正）。

### 3.2 成功响应

```json
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
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `conversationId` | string（UUID） | `t_conversation.id` |
| `mode` | string（枚举） | 实际只会出现 `FREE_CHAT` 与 `PRACTICE` 两种（原因见 §3.1）；`SELF_TEST` / `REVIEW` 已被服务端过滤 |
| `title` | string \| null | **派生、不落库**：该会话**首条 USER 消息**的 `content` 前 50 个字符；该会话还没有 USER 消息 → `null` |
| `updatedAt` | string（ISO-8601 带偏移） | `t_conversation.updated_at` —— **最近活动时间**。未聊过的会话 `updated_at` 等于 `created_at`（建会话时的默认值 `now()`），所以这一个字段同时覆盖"创建时间"与"最近活动时间"两种语义 |

- **排序**：`updated_at DESC`（最近活动的在前）。`updated_at` 由每条消息落库时更新，见 §6 拍板表。
- **空列表**：`200` + `{"conversations":[]}`（**不是 204**，理由见 §6）。
- `title` 的 50 与 `t_conversation.title` 的 `VARCHAR(50)` 对齐（纯巧合的对齐，不代表要写回该列）。

### 3.3 失败响应

| 场景 | 状态码 | body |
| --- | --- | --- |
| 未登录 / session 过期 | 401 | `{"message":"未登录"}` |
| 数据库异常 | 500 | 不 catch，冒泡 |

***

## 4. `GET /conversations/{conversationId}/messages`（会话消息）

| 项 | 值 |
| --- | --- |
| 方法 | `GET` |
| 路径 | `/conversations/{conversationId}/messages`（完整：`/api/conversations/{conversationId}/messages`） |
| 请求头 | 必须带有效 session Cookie；**无请求体** |
| 认证 | **必须**，未登录 401 |
| 成功状态码 | `200 OK` |

### 4.1 路径参数

| 字段 | 类型 | 必填 | 约束 | 不合法时 |
| --- | --- | --- | --- | --- |
| `conversationId` | string（UUID） | 是 | 合法 UUID 格式 | 400 `{"message":"conversationId 不合法"}`（§2.1） |

### 4.2 成功响应

```json
{
  "messages": [
    { "messageId": 23, "role": "USER",      "content": "什么是指针的别名？", "createdAt": "2026-10-19T13:58:41.001Z" },
    { "messageId": 24, "role": "ASSISTANT", "content": "# 指针的别名\n\n在 C 里……", "createdAt": "2026-10-19T13:58:45.123Z" }
  ]
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `messageId` | number | `t_message.id`（`BIGINT` identity） |
| `role` | string | `USER` / `ASSISTANT`（前端据此分左右气泡） |
| `content` | string | 原文；含换行时 JSON 里是转义的 `\n` |
| `createdAt` | string（ISO-8601 带偏移） | `t_message.created_at` |

- **排序**：`id ASC`（插入顺序 = 对话顺序）。④格已保证一轮的 USER→ASSISTANT 在**同一事务按序**插入，所以 `id` 序即时间序；**不按 `created_at`**（同一事务两行时间可能完全相同，排序不稳定）。
- **不返回 `type`**：理由见 §6 拍板表。
- **空会话**：`200` + `{"messages":[]}`。

### 4.3 失败响应

| 场景 | 状态码 | body |
| --- | --- | --- |
| 未登录 / session 过期 | 401 | `{"message":"未登录"}` |
| `conversationId` 不是合法 UUID | 400 | `{"message":"conversationId 不合法"}` |
| 会话**不存在** 或 **不属于你** | 403 | `{"message":"会话不存在或无权访问"}`（与④格**逐字同**文案） |
| 数据库异常 | 500 | 不 catch，冒泡 |

> 403 的两分支共用同一句文案是④格定的取舍：404 与 403 的差别本身就是**存在性旁信道**（枚举 UUID 时，404 说"不存在"、403 说"存在但非你的"，等于免费确认了哪些 UUID 真实存在）。**本格不许改文案**。

### 4.4 端到端手动示例（PowerShell）

```powershell
# 1) 登录，把 session cookie 存到 cookies.txt
curl.exe -i -c cookies.txt -X POST http://localhost:8123/api/auth/login `
  -H "Content-Type: application/json" `
  -d '{\"username\":\"你的用户名\",\"password\":\"你的密码\"}'

# 2) 列出我的会话
curl.exe -i -b cookies.txt http://localhost:8123/api/conversations

# 3) 读某个会话的消息（conversationId 换成上一步返回的值）
curl.exe -i -b cookies.txt http://localhost:8123/api/conversations/0d5a1d0e-3a1b-4d6f-9c2e-8b7f1a2c3d4e/messages

# 4) 路径变量不是 UUID —— 应得 400 且 body 有文案
curl.exe -i -b cookies.txt http://localhost:8123/api/conversations/abc/messages

# 5) 不带 cookie 再试 —— 应得 401
curl.exe -i http://localhost:8123/api/conversations
```

- PowerShell 里 `curl` 是 `Invoke-WebRequest` 的别名，**必须写 `curl.exe`**；传 JSON 时双引号要写 `\"`（同②格）。

***

## 5. 分层落点与类清单

> **契约（类名 / 包 / 方法签名）按此表**；逐步的落地顺序与目标代码见 **§5.2 施工单**。

| 层 | 类 / 位置 | 职责 | 契约 |
| --- | --- | --- | --- |
| Controller | `controller.ConversationController`（**追加 2 个方法**） | HTTP 翻译：取身份、拼响应体 | `ResponseEntity<?> list(HttpSession session)`<br>`ResponseEntity<?> messages(@PathVariable("conversationId") UUID conversationId, HttpSession session)` |
| Service | `service.ConversationService`（追加） | 业务决策 | `List<ConversationRow> list(UUID userId)` |
| Service | `service.MessageService`（追加） | 业务决策 | `List<MessageRow> listByConversation(UUID conversationId)` |
| Repository | `repository.ConversationRepository`（追加） | 只跟数据库说话、**无 `if`**、返回事实 | `List<ConversationRow> findAllByUserId(UUID userId)`<br>`void touch(UUID conversationId)` |
| Repository | `repository.MessageRepository`（追加） | 同上 | `List<MessageRow> findAllByConversation(UUID conversationId)` |
| model | `model.ConversationRow`（**新增 record**） | 跨层形状 | `(UUID conversationId, String mode, String title, OffsetDateTime updatedAt)` |
| model | `model.MessageRow`（**新增 record**） | 跨层形状 | `(Long messageId, String role, String content, OffsetDateTime createdAt)` |
| web | `web.GlobalExceptionHandler`（追加 1 条 `@ExceptionHandler`） | 异常 → HTTP | 见 §2.1 |
| constant | `constant.ConversationKeys`（追加 2 个常量） | 接口字符串常量集中一处 | `CONVERSATIONS = "conversations"`、`MESSAGES = "messages"` |

**`OffsetDateTime` 与 `TIMESTAMPTZ` 对齐**：`created_at` / `updated_at` 是 `TIMESTAMPTZ`，JDBC 取出来用 `OffsetDateTime`；Spring Boot 默认（`jackson-datatype-jsr310` + `WRITE_DATES_AS_TIMESTAMPS=false`）序列化成带偏移的 ISO-8601 字符串。**不要用 `LocalDateTime`**——它把时区信息丢掉，前端拿到的就是"不知道哪儿的本地时间"。

### 5.1 目标调用链

```
GET /api/conversations
  → ConversationController.list(session)
      → CurrentUserResolver.requireUserId(session)          → 取不到 → 401
      → ConversationService.list(userId)
          → ConversationRepository.findAllByUserId(userId)
              → SQL 见下方（派生 title 用相关子查询；无 USER 消息则为 NULL）
  ← 200 {"conversations":[{conversationId, mode, title, updatedAt}]}

GET /api/conversations/{conversationId}/messages
  → ConversationController.messages(conversationId, session)
      → CurrentUserResolver.requireUserId(session)          → 401
      → ConversationService.assertOwned(conversationId, userId)   → 403（复用④格）
      → MessageService.listByConversation(conversationId)
          → MessageRepository.findAllByConversation(conversationId)
              → SELECT id, role, content, created_at
                  FROM t_message WHERE conversation_id = ? ORDER BY id
  ← 200 {"messages":[{messageId, role, content, createdAt}]}
```

> 列表查询用**相关子查询**取首条 USER 消息，别用 `LEFT JOIN` + `min(id)` 那种写法（更绕且容易写错）：
>
> ```sql
> SELECT c.id, c.mode, c.updated_at,
>        (SELECT left(m.content, 50)
>           FROM t_message m
>          WHERE m.conversation_id = c.id AND m.role = 'USER'
>          ORDER BY m.id LIMIT 1) AS title
>   FROM t_conversation c
>  WHERE c.user_id = ?
>  ORDER BY c.updated_at DESC
> ```
>
> 没有 USER 消息时子查询返回 `NULL` → `title` 为 `null`。**截断放在 SQL 里**（`left`）而不是 Java 里，是为了让 Repository 保持"无 `if`"、Service 也不必为显示规则加分支。

***

### 5.2 施工单（按顺序做，每步做完可自验）

> 顺序**自底向上**：模型 → Repository → Service → Controller。这样每一步都能编译，也能单独验证。
> 每步给出**目标代码**。照抄没问题，但注释写的是"为什么"，照抄时把注释读一遍。
> 若某一步卡住，先看 §7 已知坑。

#### 第 0 步：前置（2026-10-20 方案修改带来的回退）

**0.1 `repository/MessageRepository.insert`** —— 回到"只插一条"，并删掉 `org.springframework.transaction.annotation.Transactional` 这个 import：

```java
public void insert(UUID conversationId, String role, String type, String content) {
    jdbcTemplate.update("insert into t_message (conversation_id, role, type, content) values (?,?,?,?)",
            conversationId, role, type, content);
}
```

（`@Transactional` 去掉了：事务边界在 `MessageService.appendRound`；Repository 上再标一个只是"加入"已有事务，是冗余。）

**0.2 `repository/ConversationRepository`** —— 新增 `touch`：

```java
// 消息落库后，把会话的 updated_at 推到当前时间
// now() 取的是「事务开始时间」：同一事务内调用多次返回同一个值
public void touch(UUID conversationId) {
    jdbcTemplate.update("update t_conversation set updated_at = now() where id = ?", conversationId);
}
```

**0.3 `service/MessageService`** —— 注入 `ConversationRepository`，并在 `appendRound` 末尾加一步。新增 import：`com.lcz.aiagentstudy.repository.ConversationRepository`。

```java
private final MessageRepository messageRepository;
private final ConversationRepository conversationRepository;   // 新增

@Transactional
public void appendRound(UUID conversationId, String userText, String assistantText) {
    messageRepository.insert(conversationId, MessageRole.USER.name(), MessageType.QUESTION.name(), userText);
    messageRepository.insert(conversationId, MessageRole.ASSISTANT.name(), MessageType.ANSWER.name(), assistantText);
    conversationRepository.touch(conversationId);              // 新增：两条都插完，只推一次 updated_at
}
```

**自验**：重启后端 → 在 `/guide` 发一条消息 → 跑

```sql
SELECT id, created_at, updated_at FROM t_conversation ORDER BY updated_at DESC LIMIT 3;
```

期望：刚聊过的那条会话 `updated_at > created_at`；没聊过的会话两者相等。

---

#### 第 1 步：`model/ConversationRow`（**新文件**）

路径：`src/main/java/com/lcz/aiagentstudy/model/ConversationRow.java`

```java
package com.lcz.aiagentstudy.model;

import java.time.OffsetDateTime;
import java.util.UUID;

// 一行 = 一个会话（列表用）
// updatedAt 是「最近活动时间」：没聊过时它等于 created_at，聊过之后就是最后一条消息的时间
public record ConversationRow(UUID conversationId, String mode, String title, OffsetDateTime updatedAt) {
}
```

| 字段 | 来自 | 类型为什么这么选 |
| --- | --- | --- |
| `conversationId` | `t_conversation.id` | `UUID`（PG 的 `uuid` 可以直接映射） |
| `mode` | `t_conversation.mode` | `String`——**不用枚举**：这一层是"数据库形状"，枚举转换的职责在 Service/Controller 边界之外，避免 JDBC 驱动类型推断问题 |
| `title` | **派生**（首条 USER 消息前 50 字） | `String`，**可能为 `null`**（该会话还没有 USER 消息） |
| `updatedAt` | `t_conversation.updated_at` | `OffsetDateTime`（列是 `TIMESTAMPTZ`；用 `LocalDateTime` 会丢时区） |

**自验**：编译通过即可（此时还没有调用方）。

---

#### 第 2 步：`model/MessageRow`（**新文件**）

路径：`src/main/java/com/lcz/aiagentstudy/model/MessageRow.java`

```java
package com.lcz.aiagentstudy.model;

import java.time.OffsetDateTime;

// 一行 = 一条消息（读历史用）
public record MessageRow(Long messageId, String role, String content, OffsetDateTime createdAt) {
}
```

- `messageId` 用 `Long`：`t_message.id` 是 `BIGINT`，用 `long` 也行（自动装箱），但 `Long` 与"可能为 null"的语义更一致。
- **故意没有 `type` 字段**：本格响应不返回 `type`（§6 拍板：前端只按 `role` 分左右气泡）。

**自验**：编译通过即可。

---

#### 第 3 步：`repository/ConversationRepository` 追加 `findAllByUserId`

在 `belongsTo` 后面追加（**不动** `create` / `belongsTo` / `touch`）。新增 import：`java.util.List`、`com.lcz.aiagentstudy.model.ConversationRow`。

```java
// 查某个用户的全部会话，按「最近活动」倒序；标题由首条 USER 消息派生
public List<ConversationRow> findAllByUserId(UUID userId) {
    return jdbcTemplate.query("""
            SELECT c.id, c.mode, c.updated_at,
                   (SELECT left(m.content, 50)
                      FROM t_message m
                     WHERE m.conversation_id = c.id AND m.role = 'USER'
                     ORDER BY m.id LIMIT 1) AS title
              FROM t_conversation c
             WHERE c.user_id = ?
             ORDER BY c.updated_at DESC
            """,
            (rs, rowNum) -> new ConversationRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("mode"),
                    rs.getString("title"),
                    rs.getObject("updated_at", OffsetDateTime.class)),
            userId);
}
```

三处要留意：

1. `(SELECT ... LIMIT 1) AS title` 是**相关子查询**：外层每一行会话各执行一次，取那条会话的**第 1 条 USER 消息**并截前 50 个字符；取不到就返回 `NULL` → `title` 为 `null`。
2. **被 RowMapper 读取的列必须出现在 `SELECT` 里**：所以 `updated_at` 必须在 `SELECT` 列表中；`created_at` 响应里不需要，就不查。
3. **这个方法里不写 `if`**：截断交给 SQL 的 `left()`，Repository 只把行取回来。

**自验**（psql，`user_id` 换成库里真实的值）：

```sql
SELECT c.id, c.mode, c.updated_at,
       (SELECT left(m.content, 50)
          FROM t_message m
         WHERE m.conversation_id = c.id AND m.role = 'USER'
         ORDER BY m.id LIMIT 1) AS title
  FROM t_conversation c
 WHERE c.user_id = '008e1d32-724b-4165-a089-be4b66b46f22'
 ORDER BY c.updated_at DESC;
```

期望：列是 `id / mode / updated_at / title`；`title` 是首条问句前 50 字（不到 50 字原样显示）；没聊过的会话 `title` 为空。

---

#### 第 4 步：`repository/MessageRepository` 追加 `findAllByConversation`

新增 import：`java.util.List`、`java.time.OffsetDateTime`、`com.lcz.aiagentstudy.model.MessageRow`。

```java
// 查某个会话的全部消息，按插入顺序（= 对话顺序）
public List<MessageRow> findAllByConversation(UUID conversationId) {
    return jdbcTemplate.query("""
            SELECT id, role, content, created_at
              FROM t_message
             WHERE conversation_id = ?
             ORDER BY id
            """,
            (rs, rowNum) -> new MessageRow(
                    rs.getLong("id"),
                    rs.getString("role"),
                    rs.getString("content"),
                    rs.getObject("created_at", OffsetDateTime.class)),
            conversationId);
}
```

- **`ORDER BY id`，不是 `ORDER BY created_at`**：同一事务里 USER 与 ASSISTANT 两行的 `created_at` 可能完全相同，按时间排不稳定（§6 拍板）。

**自验**（psql）：

```sql
SELECT id, role, left(content, 20), created_at
  FROM t_message
 WHERE conversation_id = '659f0163-...'
 ORDER BY id;
```

期望：先 `USER` 后 `ASSISTANT`，顺序与聊天时的顺序一致。

---

#### 第 5 步：`constant/ConversationKeys` 追加 2 个常量

这个类现有的职责是"响应体里的 key 常量集中一处"。追加：

```java
public static final String CONVERSATIONS = "conversations";
public static final String MESSAGES = "messages";
```

**自验**：编译通过即可。

---

#### 第 6 步：`service/ConversationService` 追加 `list`

```java
// 本格只是透传：业务决策（归属校验）不在这层，见 §6 拍板表
public List<ConversationRow> list(UUID userId) {
    return conversationRepository.findAllByUserId(userId);
}
```

- 新增 import：`java.util.List`、`com.lcz.aiagentstudy.model.ConversationRow`。
- `ConversationService` 里**应该已经有 `conversationRepository` 字段**（`assertOwned` 在用这个字段查 `belongsTo`）——有就用现成的，**不要重复注入**。

**自验**：编译通过即可。

---

#### 第 7 步：`service/MessageService` 追加 `listByConversation`

```java
// 同样只是透传；权限判断留在 Controller 调 assertOwned（§6 拍板：Service 不认识"调用者是谁"）
public List<MessageRow> listByConversation(UUID conversationId) {
    return messageRepository.findAllByConversation(conversationId);
}
```

新增 import：`java.util.List`、`com.lcz.aiagentstudy.model.MessageRow`。

**自验**：编译通过即可。

---

#### 第 8 步：`controller/ConversationController` 追加 2 个方法

**先补 import**：`java.util.List`、`org.springframework.web.bind.annotation.GetMapping`、`org.springframework.web.bind.annotation.PathVariable`、`com.lcz.aiagentstudy.model.ConversationRow`、`com.lcz.aiagentstudy.model.MessageRow`、`com.lcz.aiagentstudy.service.MessageService`。

**再补注入**（`@RequiredArgsConstructor` 会自动把它加进构造器）：

```java
private final MessageService messageService;
```

**然后加两个方法**：

```java
@GetMapping
public ResponseEntity<?> list(HttpSession session) {
    UUID userId = currentUserResolver.requireUserId(session);        // 401
    List<ConversationRow> conversations = conversationService.list(userId);
    return ResponseEntity.ok(Map.of(ConversationKeys.CONVERSATIONS, conversations));
}

// @PathVariable 必须显式写名字，名字就叫 conversationId（§2.1）
@GetMapping("/{conversationId}/messages")
public ResponseEntity<?> messages(@PathVariable("conversationId") UUID conversationId, HttpSession session) {
    UUID userId = currentUserResolver.requireUserId(session);        // 401
    conversationService.assertOwned(conversationId, userId);         // 403，必须在查询之前（§7 坑 6）
    List<MessageRow> messages = messageService.listByConversation(conversationId);
    return ResponseEntity.ok(Map.of(ConversationKeys.MESSAGES, messages));
}
```

要点：

1. **三行顺序不能换**：取身份（401）→ 归属校验（403）→ 查询。顺序反了，非归属者的请求会真的去扫 `t_message`。
2. `@PathVariable("conversationId")` 的**名字必须显式写**、且就叫 `conversationId`：这样第 9 步的处理器用 `ex.getName()` 能拿到 `conversationId`。
3. 返回的是 record 列表，**Jackson 直接把 record 的组件名当 JSON 字段名**（`conversationId` / `mode` / `title` / `updatedAt`），不需要任何注解。
4. `ConversationController` 已有的 `create` 方法**不要动**。

**自验**：见第 11 步的 curl（或先只跑第 11 步的第 2、3 条）。

---

#### 第 9 步：`web/GlobalExceptionHandler` 修正文案

你已经加了一条 `MethodArgumentTypeMismatchException` 的处理器，但**文案是 `"路径变量参数类型 不合法"`，与契约 §2.1 不一致**（契约要 `"conversationId 不合法"`，§8 用例 8 也按这个字符串断言）。改成：

```java
// 路径变量类型不对（如 conversationId 不是合法 UUID）：拒绝方是 Spring 的类型转换器，
// 它带不了我们的文案，所以用 getName() 拼一句
@ExceptionHandler(MethodArgumentTypeMismatchException.class)
public ResponseEntity<?> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
    return ResponseEntity.badRequest().body(Map.of("message", ex.getName() + " 不合法"));
}
```

**为什么用 `ex.getName()` 而不是把 `"conversationId"` 写死**：`ex.getName()` 取的就是 `@PathVariable` 里声明的变量名；将来出现第二个路径变量时，这一条处理器不用改也能给出正确的字段名。这是 §2.1 要求"路径变量必须命名为 `conversationId`、不能简写成 `id`"的**唯一理由**——名字写错，下面这句文案跟着一起错。

**自验**：

```powershell
curl.exe -i http://localhost:8123/api/conversations/abc/messages
```

期望：`400`，body 是 `{"message":"conversationId 不合法"}`（**不是空 body**——这条就是 §7 坑 1 说的"假验收"入口，务必看 body）。

---

#### 第 10 步：测试（按 §8 的 9 条）

测试类：`src/test/java/com/lcz/aiagentstudy/controller/ConversationControllerTest.java`（追加用例；基建同①格：`@SpringBootTest` + `@AutoConfigureMockMvc` + `@Transactional`，伪造登录态用 `sessionAttr`）。

- 断言**必须带 `jsonPath("$.message")`**，不能只断言状态码（§7 坑 1）。
- 用例 3 造排序数据时**显式 `UPDATE t_conversation SET updated_at = ...`**，别指望两次 `now()` 天然不同。

**自验**：`mvn test` 全绿，含 §8 用例 9 的 12 条回归。

---

#### 第 11 步：端到端（按 §4.4 的 5 条 curl）

PowerShell 里 `curl` 是 `Invoke-WebRequest` 的别名，**必须写 `curl.exe`**。

**自验**：5 条全部符合期望，其中第 4 条要**看 body**、第 5 条要得 401。

---

#### 第 12 步：回填文档（按 §9）

把实际产出补进 [`impl-log.md`](./impl-log.md) 的第⑤格小节；与本文档的差异按「版本化修订」记进去，**不覆盖本文档**；预测外问题转记到 [`resume-assets.md`](./resume-assets.md)。

***

## 6. 本格拍板

| 项 | 结论 | 理由 / 否决了什么 |
| --- | --- | --- |
| 列表接口的身份来源 | **只从 session 取**，接口无 `userId` 参数 | ②格硬约束：客户端不可自报身份 |
| 列表排序 | `updated_at DESC`（**2026-10-20 方案修改**） | 原否决理由「`updated_at` 无任何代码维护」已消除：`MessageService.appendRound` 在两条消息落库后调 `ConversationRepository.touch(conversationId)`（SQL：`update t_conversation set updated_at = now() where id = ?`）。否决「把这条 UPDATE 塞进 `MessageRepository.insert`」：那会让管 `t_message` 的仓库去改 `t_conversation`（职责越界），且每条消息各更新一次 |
| 列表返回的时间字段 | `updatedAt`（**2026-10-20 方案修改**） | 原拍板给 `createdAt`，理由只是"列表行显示创建时间"，没有和排序口径对齐。改为 `updatedAt` 后**排序依据与显示依据一致**；且未聊过的会话 `updated_at` 就等于 `created_at`，一个字段覆盖两种语义。否决「保持 `createdAt`」：最新活动的会话排第一行、那行却显示更早的时间；否决「两个都给」：多一个前端用不到的字段进契约，改起来更贵 |
| 列表标题 | **派生**（首条 USER 消息前 50 字，SQL `left()`），`t_conversation.title` 继续 `NULL` | 否决「建会话时前端传 title」：要改②格**已冻结**的契约；否决「把派生值写回 `title` 列」：一份能推导出来的数据存第二份，违反既定字段取舍规则 |
| 分页 | **不做** | YAGNI：这是个人学习项目的会话列表，数量在一个数量级内；超过一屏再加 `limit/offset`（接口留在 `/conversations` 上，加 `@RequestParam` 即可，不破坏契约） |
| 空列表 | `200` + `{"conversations":[]}` | 否决 `204 No Content`：前端要为"没 body"多加一条分支，`200` + 空数组一个形状吃到底 |
| 消息排序 | `id ASC` | ④格保证同事务按序插入，`id` 序即对话序；否决按 `created_at`：同一事务两行时间可能相同，排序不稳定 |
| 消息返回字段 | 只给 `messageId` / `role` / `content` / `createdAt` | 否决带上 `type`：前端只按 `role` 分左右气泡；`type`（`QUESTION`/`ANSWER`/`FEEDBACK`/`CHAT`）是**复习闭环**的语义，等它真被用到再说。**一旦返回就成契约，改起来更贵** |
| 403 文案 | 与④格**逐字同**：「会话不存在或无权访问」 | ④格已定：不存在 / 非你的归并成同一种失败，避免存在性旁信道 |
| 路径变量非法 | 400 `{"message":"conversationId 不合法"}` + 补 `MethodArgumentTypeMismatchException` 映射 | 否则 Spring 默认解析器给 400 但 **body 为空**（④格「假验收」同型）；文案固定，因拒绝方是类型转换器，带不了我们的文案 |
| 路径变量命名 | `{conversationId}`（不用 `{id}`） | 让异常 `getName()` 拿到 `conversationId`，文案自带字段名 |
| 两个接口归属 | 都进 `ConversationController` | `/conversations/{id}/messages` 是会话的**子资源**，不该另起一个 MessageController |
| 派生 title 的截断位置 | SQL `left(content, 50)` | Repository 保持"只跟数据库说话、无 `if`"；Service 也不必为显示规则加分支 |
| 归属校验落点 | Controller 调 `assertOwned`（与④格同构） | 复用④格已生效的 403；**不在 `MessageService` 里塞权限判断**——Service 不认识"调用者是谁" |

***

## 7. 已知坑（本格大概率会踩）

1. **路径变量非法 → 默认解析器给 400 但 body 为空** —— 不补 §2.1 那条映射，用例 8 就只会看到"状态码对"，是又一例**假验收**（④格已吃过这个教训：契约里写了 body + 测试只断言状态码 = 假验收）。
2. **`left(content, 50)` 按「字符」算** —— PG 的 `left` 是字符语义（不是字节）；别按 `substring` 的字节语义去理解，也别指望它和 Java 的 `substring` 在中文截断上表现一致（这里恰好一致，因为 PG 按字符）。
3. **`t_message.conversation_id` 没有索引** —— 派生 title 的相关子查询会逐会话扫描 `t_message`。MVP 数据量下无感，**数据量上来要加索引**（`CREATE INDEX ... ON t_message(conversation_id)`），记入待办即可，不要本格顺手加。
4. **`created_at` / `updated_at` 是 `TIMESTAMPTZ`** —— Jackson 序列化成**带偏移**的 ISO-8601（如 `...Z`）。前端要显示本地时间得自己转（`new Date(str)` 之后再格式化），**别指望 JSON 里就是本地时间**。
5. **MockMvc 路径仍不带 `/api`** —— 写 `get("/conversations")`，不是 `/api/conversations`；带了就 404。
6. **`assertOwned` 必须在开查询之前** —— 先校验再查消息；顺序反了，非归属者的请求也会真的去扫 `t_message`（虽然结果不返回，但做了一次无用功，等于把"存在性"探了一遍）。
7. **`updated_at` 只在 `MessageService.appendRound` 里被更新** —— 将来若出现别的"写消息"入口（④格复习闭环的 `FEEDBACK`、手工导入历史等），必须也调 `ConversationRepository.touch(conversationId)`，否则列表排序会漏掉这类活动。本格不预先铺设，只记在这里。

***

## 8. 验收清单（Definition of Done）

全部通过才算第⑤格完成。测试类：`src/test/java/com/lcz/aiagentstudy/controller/ConversationControllerTest.java`（追加用例），基建同第①格（`@SpringBootTest` + `@AutoConfigureMockMvc` + `@Transactional`；伪造登录态用 `sessionAttr`，见 `api-conversations.md` §5）。

| # | 用例 | 断言 |
| --- | --- | --- |
| 1 | `GET /conversations` 未登录 | `401` + `jsonPath("$.message")` 有值 |
| 2 | 已登录；库里有**本用户** 2 条、**另一用户** 1 条会话 | `200`；`$.conversations.length()` = 2；`$.conversations[*].conversationId` **不含**另一用户的 id；`$.conversations[0].updatedAt` 有值（非 null） |
| 3 | 用例 2 的排序 | 先 `UPDATE t_conversation SET updated_at = ...` 把两条会话的 `updated_at` **显式改成可区分的两个值**（别指望两次 `now()` 天然不同），再断言 `$.conversations[0].conversationId` = `updated_at` 较晚那条会话的 id，且 `$.conversations[0].updatedAt` 与写入的值一致 |
| 4 | 派生 `title` | 给会话 A 插一条 USER 消息（内容 > 50 字）→ A 的 `title` = 前 50 字；会话 B 无消息 → B 的 `title` 为 `null`。别写复杂的 jsonPath 过滤表达式，查库比对 + 断言长度更稳 |
| 5 | `GET /conversations/{id}/messages` 未登录 | `401` |
| 6 | 别人的会话 与 不存在的随机 UUID | 均 `403`，且两条的 `$.message` **逐字相同** |
| 7 | 自己的会话（先按④格方式落一轮消息） | `200`；`$.messages.length()` = 2；顺序 `USER` → `ASSISTANT`；`content` 与写入的一致 |
| 8 | `GET /conversations/abc/messages`（`abc` 不是 UUID） | `400` + `{"message":"conversationId 不合法"}`（**§2.1 新处理器的看门狗**） |
| 9 | 回归 | `AuthControllerTest` 3 + `ConversationControllerTest` 原 5 + `MessageServiceTest` 1 + `ChatServiceTest` 1 + `ConversationRepositoryTest` 2 = **12 条仍绿** |

**用例 6 是归属校验的看门狗**：一旦 `assertOwned` 被挪到查询之后、或被改成"查不到就返回空列表"，用例 6 立刻从 403 变成 200。

***

## 9. 完成后回填

实现并通过验收后：

- 把实际产出（两个接口的返回样例、测试条数）补进 [`impl-log.md`](./impl-log.md) 的**第⑤格**小节
- 与本文档的差异按「**版本化修订**」记进该小节，**不覆盖本文档**
- **预测外问题**同时转记到 [`resume-assets.md`](./resume-assets.md) 的风险预案，并按规则进亮点台账
- 勾掉 [`decision-log.md`](./decision-log.md) 待办里的「第⑤格纵切」一行
