# Plan · V2 可批注笔记

| 项 | 内容 |
|---|---|
| 对应 | `spec.md` v1.0（FR / AT 编号以该文为准） |
| 下游 | `tasks.md`（按本文拆任务） |
| 状态 | **v1.1 · 任务顺序重排后同步** · 2026-11-14 |

> 本文回答 **怎么做**：技术上下文、设计决策、数据、接口契约、关键算法、测试策略。
> **整段实现代码不写进本文**——那是 `tasks.md` 落盘时直接产出的东西，抄进文档只会腐烂。
> 本文保留的是可以直接复制执行的资产：迁移 SQL、接口契约、两个前端算法、提示词全文。

---

## 1. 技术上下文（实测基线，非假设）

| 层 | 事实 |
|---|---|
| 后端 | Java 21、Spring Boot 3.4.4、Spring AI 1.1.0、PostgreSQL 16 |
| 数据访问 | `JdbcTemplate`，事务边界在 Service；无 JPA / MyBatis / Flyway / Liquibase |
| 鉴权 | Servlet `HttpSession`，`CurrentUserResolver.requireUserId(HttpServletRequest)` 返回 `UUID`，session key `userId` |
| 异常 | `BadRequestException` / `UnauthorizedException` / `ForbiddenException` → `GlobalExceptionHandler` 统一 `{"message":"..."}`，400 / 401 / 403；模型失败抛 `IllegalStateException` → Spring 默认 500；**无 Result 包装类** |
| 模型 | 业务代码按参数名注入 `ChatModel deepSeekChatModel`（多模型 bean 按名匹配）；`AiCallRecorder` 统一埋点 |
| 服务 | `server.port=8123`，`context-path=/api`；SSE 超时 `new SseEmitter(180000L)` |
| 前端 | Vue 3.5.12 `<script setup>` + `<style scoped>`、Vite 5.4.10、vue-router 4.4.5、axios 1.7.7、`marked` 18.1.1 + `DOMPurify` 3.4.17；无 Pinia / UI 库，原生 CSS 变量 |
| 请求 | `api/request.js`：`baseURL '/api'`、`withCredentials: true`、响应拦截器脱壳；SSE 走 `api/chatStream.js` 原生 `fetch` |
| 代理 | Vite `5173` → `/api` → `http://localhost:8123` |

---

## 2. 项目约束（Constitution）

实施中任何任务不得违反：

1. **不引入任何新依赖**（后端、前端均不加库）。
2. **不新建 `ChatModel` bean、不引入用户级 API Key**；所有 AI 调用复用 `deepSeekChatModel` 与服务端默认配置。
3. `db/schema.sql` 是「空库一次性建全」脚本，**不改**；结构变更交付独立 `db/migration-v2.sql`，用 `psql -f` 手工执行。
4. 新建 Controller 一律遵循 `requireUserId(request)` → `assertOwned(...)` → 业务 的顺序。
5. 新增前端路由挂在 `AppShell` 父路由下并带 `meta: { requiresAuth: true }`。
6. 批注的 `answer` 用 Vue `{{ }}` 插值渲染，**不走 `v-html`**；正文继续走 `renderMarkdown`（DOMPurify 已配置）。

---

## 3. 总体设计

### 3.1 模块与数据流

```
浏览器
  ├─ GuideChatView / ChatRoom   ← 生成笔记开关 + NOTE 卡片
  ├─ NoteDetailView             ← 笔记全文 + 编辑（新页）
  └─ SectionReadView            ← 章节只读正文
        │ 共用
        ▼
  AnnotatableDoc.vue            ← 两栏 / 块切分 / 选区 / 编辑预览 / 分组 / stale
        ▼
  AnnotationCard.vue            ← 递归卡片 / 卡内选区 / 编辑删除
        │ axios(request.js)            SSE(chatStream.js)
        ▼                             ▼
  NoteController  AnnotationController   AiChatController
        │                │                    │
  NoteService      AnnotationService    ChatService → LoveApp
        │                │                    │
  MessageRepository AnnotationRepository  MessageService / AiCallRecorder
        └──────── JdbcTemplate ────► PostgreSQL
```

**两套实现合并为一刀**：笔记页与阅读页只有「正文可不可编辑」这一个差别，其余选区、批注、追问、降级逻辑全部共用同一个组件。这是否决「全局助手」与「两处各写各的」之后的结论。

### 3.2 两种正文、一套抽象

| | 笔记（NOTE） | 章节（SECTION） |
|---|---|---|
| 正文来源 | `t_message.content`，`type='NOTE'` | `t_knowledge_item.content` |
| `targetId` 类型 | `BIGINT` → 字符串 | `UUID` → 字符串 |
| `bodyEditable` | `true` | `false` |
| 归属校验路径 | `t_message → t_conversation.user_id` | `t_knowledge_item.user_id` |
| 批注能力 | 完全相同 | 完全相同 |

`target_id` 用 `VARCHAR(64)` 存两类 ID，**数据库层无法建外键**，归属校验必须在应用层（5.5）。

### 3.3 设计决策（含被否方案）

| # | 决策 | 理由 / 被否方案 |
|---|---|---|
| D1 | 笔记 = `t_message` 里一条 `type='NOTE'` 的 ASSISTANT 消息 | 省一张表和一套 CRUD、历史回放天然可用。被否：独立 `t_note`（会与消息流双写、还要自己管会话归属） |
| D2 | 批注独立表 `t_annotation`，不写进 `t_message` | 否则会污染 `ChatMemory`，还会被「把这轮做成题」当材料存进知识库。见 spec FR-07 |
| D3 | 锚点 = 段落 `blockKey` + `quote` 快照 | 字符偏移一编辑全乱；块编辑器要重写整条渲染链。见 spec FR-10 |
| D4 | 子批注用 `parent_id` 自引用一列 | 不另建表、不用递归 SQL 取树；成本只有一列 + 一个可选请求参数 |
| D5 | 批注在右侧独立栏，按 `blockKey` 分组 | 用户草图确认；不做像素级对齐（编辑 / 窄屏 / 滚动都会崩） |
| D6 | 解释 60~120 字、单段、非流式；子批注携带父链上下文 | 短才能当批注读；脱离语境会把「余弦相似度」答成百科 |
| D7 | 所有模型调用走默认 `ChatModel`，观测走 `AiCallRecorder` | spec FR-12 / NFR-01；本版冻结 Key 自定义 |

### 3.4 现状阻碍：动手前必须同步修改的点

调研发现以下既有实现会**直接挡住**本功能，`spec.md` 未覆盖，实施时逐项处理。按 v1.1 顺序：C1/C2/C3 → **T004**（共享底座，最先做齐）、C4 → T008、C5 → T009~T011、C6/C7/C12 → T001（迁移）、C8~C10 → T016/T017、C11 → T021。

| # | 现状 | 必须做的改动 |
|---|---|---|
| C1 | `MessageRow` 只有 `messageId/role/content/createdAt`；历史消息 SQL 不 SELECT `type` | 加 `type` 字段、SQL 加 `m.type`；否则历史里认不出 NOTE，卡片渲染不出来 |
| C2 | `MessageService.appendRound(...)` 写死 `QUESTION/ANSWER`，`insert` 不返回 id | 新增 `appendNoteRound(...)` 返回 `long`，靠 `insertReturningId` 拿 id 给 SSE `note` 帧 |
| C3 | `MessageType` 枚举只有 4 个值 | 加 `NOTE` |
| C4 | `ChatEvent` sealed 接口只有 `Delta/Sources/Tool/Done/Error` | 加 `record Note(long noteId)`，事件名 `note` |
| C5 | `StreamRequest` 只有 4 个字段；`ChatService.stream` 是 5 参 | 请求体加 `Boolean asNote`；`stream(...)` 加第 6 个 boolean；`LoveApp` 加笔记分支 |
| C6 | `t_ai_call.scene` 有 CHECK 只允许 6 个值 | **新增 3 个 scene 必须同步 ALTER**，否则 `recordCall` 抛 `23514` |
| C7 | `t_message` 没有 `updated_at` | 加列，笔记编辑时更新 |
| C8 | `SectionReadView` 是 `renderMarkdown(整篇)` + 单个 `v-html` | **整篇 HTML 无法按段落定位**，必须按顶层块逐块渲染并包 `data-block-key`（7.2）。前端最大改动 |
| C9 | 阅读页单栏 `.reader-inner{max-width:720px}` + 吸底 `.action-bar` | 两栏需调容器宽度；吸底操作栏保留不动 |
| C10 | 阅读页**没有**选中高亮样式 | 新增 `.quote-hit` / `.block-has-annotation`（历史草稿里「已有选中遮罩可复用」是误判） |
| C11 | `chatStream.js` 请求体写死 4 字段；事件分发在 `ChatRoom.onEvent` | 加 `asNote` 透传；`note` 帧在 `onEvent` 转成 NOTE 卡片数据 |
| C12 | `db/` 只有 `schema.sql`，无 migration、无 Flyway | 交付 `db/migration-v2.sql` 手工执行，执行记录写 `docs/impl-log.md` |

### 3.5 对 spec 的实现细化（外部行为不变）

| 细化 | 理由 |
|---|---|
| `GET /annotations` 返回**扁平列表**（按 `created_at` 升序），前端按 `blockKey` 分组、`parentId` 组树并算 `stale` | `stale` 本来就要前端按 `quote` 匹配算；后端组树是重复劳动，一次查询、零递归 SQL |
| 跨段落选区直接忽略并提示 | spec FR-13；避免为罕见场景引入 Range 合并 |
| `quote` 高亮做 best-effort：被 markdown 标记切开匹配不到时，降级为「段落左侧色条 + 批注数角标」 | 保证批注永远可读 |
| 笔记生成复用同一 `ChatMemory`（同 `chatId`） | spec FR-01；副作用见第 9 节 R1 |

### 3.6 任务排布原则（v1.1）

旧版按「演示画面」切 M1/M2（M1=阅读页批注、M2=笔记生成），但批注从第一天就统一支持 SECTION / NOTE 两种正文，NOTE 的读写底座却被推到 Phase 2，导致用它的服务只能靠临时 SQL 绕过、且多处任务成环。v1.1 改为按**代码依赖分层**排任务（`tasks.md` 第 7 节）：

1. **共用底座先做齐**：两种正文都要用的 `MessageRepository` / `MessageService` NOTE 能力（T004）排在 `AnnotationService`（T005）之前，Service 里不再出现 SQL。
2. **先有被调用方，再改调用方**：`LoveApp.doNoteByStream`（T009）→ `ChatService` 分流（T010）→ `AiChatController` 第 6 参（T011），每步单独编译通过。
3. **前端不跨阶段 import**：`AnnotatableDoc` 编辑态只 `emit('save')`，保存行为由笔记详情页注入；路由与它动态 import 的页面同一次提交。
4. **闸门只跑能力已具备的 AT**：需要笔记详情页的 AT-07 / AT-12 / AT-10 笔记半边统一后移到 M2。

`tasks.md` 第 0 节给出旧 → 新编号映射表，本文第 10 节矩阵已按新编号重列。

---

## 4. 数据设计

### 4.1 表关系

```
t_user ──< t_conversation ──< t_message(type=NOTE) ──< t_annotation(target_type=NOTE)
  │                                                        │
  └──< t_knowledge_item ────────────< t_annotation(target_type=SECTION)
                                             └─ parent_id 自引用（追问链）
t_ai_call ← 所有模型调用（scene 扩展 3 个值）
```

### 4.2 迁移脚本 `db/migration-v2.sql`（全文 · 可直接执行）

```sql
-- V2 可批注笔记迁移。手工执行：psql -d ai_agent_study -f db/migration-v2.sql
-- 前置：db/schema.sql 已在空库执行过（PG16）。
-- schema.sql 的 CHECK 是列级内联约束，PG 自动命名为
-- t_message_type_check / t_ai_call_scene_check，故可直接 DROP。
BEGIN;

-- 1) 消息类型增加 NOTE（C3）
ALTER TABLE t_message DROP CONSTRAINT IF EXISTS t_message_type_check;
ALTER TABLE t_message ADD CONSTRAINT t_message_type_check
    CHECK (type IN ('QUESTION','ANSWER','FEEDBACK','CHAT','NOTE'));

-- 2) 笔记正文编辑需要更新时间（C7）
ALTER TABLE t_message
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- 3) 观测场景扩展（C6，不扩会让 recordCall 抛 23514）
ALTER TABLE t_ai_call DROP CONSTRAINT IF EXISTS t_ai_call_scene_check;
ALTER TABLE t_ai_call ADD CONSTRAINT t_ai_call_scene_check
    CHECK (scene IN ('CHAT','QUIZ_GENERATE','GRADE','HELP','SUMMARY','PRACTICE',
                     'NOTE_GENERATE','ANNOTATION','ANNOTATION_CHILD'));

-- 4) 唯一新增表
CREATE TABLE IF NOT EXISTS t_annotation (
    id          BIGINT       PRIMARY KEY GENERATED BY DEFAULT AS IDENTITY,
    user_id     UUID         NOT NULL REFERENCES t_user(id),
    target_type VARCHAR(10)  NOT NULL CHECK (target_type IN ('SECTION','NOTE')),
    target_id   VARCHAR(64)  NOT NULL,   -- SECTION: UUID 字符串 / NOTE: BIGINT 字符串
    parent_id   BIGINT       NULL REFERENCES t_annotation(id) ON DELETE CASCADE,
    block_key   VARCHAR(32)  NOT NULL,   -- 顶层=选中段落；子批注继承父的 block_key
    quote       TEXT         NOT NULL,   -- 顶层=正文选段；子批注=父答案里选中的词
    question    TEXT         NOT NULL,
    answer      TEXT         NOT NULL,
    edited      BOOLEAN      NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_annotation_target
    ON t_annotation(user_id, target_type, target_id, block_key, created_at);
CREATE INDEX IF NOT EXISTS idx_annotation_parent
    ON t_annotation(parent_id);

COMMIT;
```

**回滚**（仅在未写入业务数据前使用）：

```sql
BEGIN;
DROP TABLE IF EXISTS t_annotation;
ALTER TABLE t_ai_call DROP CONSTRAINT IF EXISTS t_ai_call_scene_check;
ALTER TABLE t_ai_call ADD CONSTRAINT t_ai_call_scene_check
    CHECK (scene IN ('CHAT','QUIZ_GENERATE','GRADE','HELP','SUMMARY','PRACTICE'));
ALTER TABLE t_message DROP COLUMN IF EXISTS updated_at;
ALTER TABLE t_message DROP CONSTRAINT IF EXISTS t_message_type_check;
ALTER TABLE t_message ADD CONSTRAINT t_message_type_check
    CHECK (type IN ('QUESTION','ANSWER','FEEDBACK','CHAT'));
COMMIT;
```

### 4.3 字段语义与不变量

| 字段 | 语义 / 约束 |
|---|---|
| `parent_id` | `NULL` = 顶层批注；非 `NULL` = 子批注 |
| `block_key` | 顶层为选区起点所在段落 key；子批注**继承父的 `block_key`**（仅用于分组） |
| `quote` | 不可改。顶层 ≤300 字；子批注为父 `answer` 里选中的词 |
| `answer` | 唯一可编辑字段；AI 生成 60~120 字，用户改写上限 2000 字 |
| `edited` | `PATCH` 后置 `true`，不记版本 |

不变量（应用层保证，不建触发器）：

- **I1** `parent_id` 非空时，父必须存在、`user_id` 相同、`target_type`/`target_id` 相同。
- **I2** 链深（含自身）≤ 8。
- **I3** 单个 `(target_type, target_id)` 下批注总数（含子批注）≤ 200。
- **I4** 删父批注由 `ON DELETE CASCADE` 删除全部后代，不留孤儿。

### 4.4 关键 SQL（归属校验 / 链深 / 后代计数）

```sql
-- SECTION 归属
SELECT EXISTS (SELECT 1 FROM t_knowledge_item WHERE id = ? AND user_id = ?);

-- NOTE 归属（跨两层，FR-12）
SELECT EXISTS (
    SELECT 1 FROM t_message m
      JOIN t_conversation c ON c.id = m.conversation_id
     WHERE m.id = ? AND m.type = 'NOTE' AND c.user_id = ?
);

-- 批注本身 / 父批注合法性（含 I1 所需字段）
SELECT id, user_id, target_type, target_id, block_key FROM t_annotation WHERE id = ?;

-- 链深（I2）
WITH RECURSIVE chain AS (
    SELECT id, parent_id, 1 AS depth FROM t_annotation WHERE id = ?
    UNION ALL
    SELECT a.id, a.parent_id, c.depth + 1
      FROM t_annotation a JOIN chain c ON a.id = c.parent_id
)
SELECT COALESCE(MAX(depth), 0) FROM chain;

-- 后代数量（DELETE 响应用）
WITH RECURSIVE tree AS (
    SELECT id, parent_id FROM t_annotation WHERE id = ?
    UNION ALL
    SELECT a.id, a.parent_id FROM t_annotation a JOIN tree t ON a.parent_id = t.id
)
SELECT COUNT(*) FROM tree;
```

---

## 5. 接口契约

### 5.1 通用约定

- 前缀 `/api` 由 `context-path` 提供，Cookie 带 `JSESSIONID`（`withCredentials: true`）。
- 成功直接返回裸对象 / 数组 / `Map`，**不包 `code/data`**。
- 失败由 `GlobalExceptionHandler` 返回 `{"message":"..."}`：400 参数/限额/非法 `parentId`/目标非 NOTE；401 未登录；403 归属失败；500 模型调用失败。

### 5.2 清单

| # | 方法 | 路径 | 说明 |
|---|---|---|---|
| 1 | POST | `/ai/chat/stream` | 改造：`StreamRequest` 加 `asNote`，SSE 加 `note` 帧 |
| 2 | GET | `/notes/{messageId}` | 读笔记 |
| 3 | PUT | `/notes/{messageId}` | 保存笔记正文 |
| 4 | GET | `/annotations` | 批注扁平列表 |
| 5 | POST | `/annotations/ask` | 生成批注（`parentId` 可选） |
| 6 | PATCH | `/annotations/{id}` | 改答案 |
| 7 | DELETE | `/annotations/{id}` | 删除（级联） |
| 8 | GET | `/notes` | 我的笔记列表（2026-11-15 新增，`FR-14`） |

**#1** 请求体新增 `Boolean asNote`（`null` 视为 `false`）；新增事件 `ChatEvent.Note(long noteId)`，事件名 `note`。帧序（仅 `asNote=true`）：`sources? → delta* → tool* → note → done`，`note` 必须在 `done` 之前。

**#2** 响应 `{messageId, conversationId, type:"NOTE", content, createdAt, updatedAt}`。校验顺序：`requireUserId` → JOIN 查归属 → 非本人 403 → `type != 'NOTE'` 400。

**#3** 请求 `{"content":"…markdown…"}`（≤20000 字），响应 `{messageId, updatedAt}`。先做 #2 的归属校验再 `UPDATE t_message SET content=?, updated_at=now() WHERE id=? AND type='NOTE'`。**不重算后端锚点**——`blockKey`/`quote` 存在批注里，重定位由前端加载时算（7.2）。

**#4** Query `targetType=SECTION|NOTE`、`targetId=...`。响应扁平数组（`created_at` 升序）：

```json
[
  { "id": 12, "parentId": null, "blockKey": "p-1", "quote": "召回 topK 片段",
    "question": "topK 是什么？", "answer": "…", "edited": false, "createdAt": "…" },
  { "id": 13, "parentId": 12, "blockKey": "p-1", "quote": "余弦相似度",
    "question": "相似度怎么算？", "answer": "…", "edited": false, "createdAt": "…" }
]
```

**#5** 请求体（Bean Validation）：

```java
public record AskRequest(
    @NotNull TargetType targetType,          // enum SECTION / NOTE，非法值自动 400
    @NotBlank @Size(max = 64)  String targetId,
    @NotBlank @Size(max = 32)  String blockKey,
    @NotBlank @Size(max = 300) String quote,
    @NotBlank @Size(max = 500) String question,
    Long parentId                            // 可空
) {}
```

> 该 record **定义在 `AnnotationService` 内部**（T005 采用的写法），`AnnotationController`（T006）按 `AnnotationService.AskRequest` 引用并用 `@Valid` 触发校验。

响应为新建的单条批注（同 #4 的元素形状）。处理顺序（**不在事务里调模型**）：

1. `requireUserId` → 参数校验。
2. `targetId` 解析：SECTION 用 `UUID.fromString`、NOTE 用 `Long.parseLong`，失败 → 400。
3. 归属校验（4.4）→ 403。
4. `parentId != null`：查父，校验 I1，查链深 I2；不满足 → 400 / 403。
5. 查该目标批注总数，≥200 → 400。
6. 组装上下文（7.1）→ `AiCallRecorder.start()` → `ChatClient.prompt().user(...).call().chatResponse()` → `recordCall(...)`；异常 `recordFailure(...)` 后抛 500。
7. 答案按 200 字硬截断兜底 → `INSERT` → 返回。

**#6** 请求 `{"answer":"…"}`（≤2000 字，只接受该字段，多传 `question` → 400）；`UPDATE ... SET answer=?, edited=true WHERE id=?`，先校验归属。

**#7** 先校验归属 → 4.4 递归 CTE 查后代数量 → `DELETE FROM t_annotation WHERE id=?`（FK 级联）→ 响应 `{"id":12,"deletedCount":3}`（含自身，仅用于结果提示）。删除前的 N 由前端用 #4 已组好的树计算：**算 N → 确认 → DELETE**，不加预检接口。

**#8** `GET /notes?limit=50`（`limit` 可省，默认且上限都是 50）。响应按 `updated_at DESC` 的数组：

```json
[
  { "messageId": 534, "conversationId": "…", "content": "…markdown 原文…",
    "annotationCount": 3, "createdAt": "…", "updatedAt": "…" }
]
```

- 归属只靠 `t_conversation.user_id`，一条 SQL：`t_message m JOIN t_conversation c ON c.id = m.conversation_id WHERE m.type='NOTE' AND c.user_id=? ORDER BY m.updated_at DESC LIMIT ?`；批注数用 `LEFT JOIN t_annotation a ON a.target_type='NOTE' AND a.target_id = m.id::text` + `COUNT(a.id)`。**`target_id` 是 varchar，必须显式 cast**，否则 join 走不了索引也不会报错、只是慢。
- **返回正文原文，标题 / 摘要 / 字数都不在后端算**：前端 `utils/noteCard.js` 已有同一份实现，Java 里再写一份就是第二份真相 —— 卡片、列表、详情页三处标题迟早不一致（这个坑 T021 已经踩过一次）。
- 空列表返回 `[]`，不用 204 / 404：没有笔记是正常状态。
- 不返回 `type`：能进这个列表的必然是 `NOTE`，恒等字段只会让人以为它可能变。
- **不做游标分页**：首屏 50 条够用，真长到需要翻页再说。

> **T006 落地口径**（2026-11-14）：`GET` 的两个 query 参数在 Controller 做形状校验（非法枚举 / 缺失 / 超 64 字 → 400 + `message`）；`PATCH` 用 `@RequestBody Map<String,Object>` 并要求「恰好一个 `answer` 字符串键」，因为 Jackson 默认忽略未知字段，DTO + `@Valid` 会把 #6 要求拒绝的多传 `question` 静默吃掉。`@Valid` 在参数绑定阶段触发、早于方法体的 `requireUserId`，所以「未登录 **且** body 非法」这一组合返回 400（单独未登录仍是 401）；与项目既有 `QuizController` 写法一致，T014 的前端封装按此处理。

---

## 6. 关键实现要点

> 只写契约与易错点；完整代码在 `tasks.md` 对应任务里落盘。

### 6.1 后端

**笔记链路**：`AiChatController.stream` 把 `Boolean.TRUE.equals(req.asNote())` 作为第 6 参传给 `ChatService.stream`。`ChatService` 用 `asNote ? loveApp.doNoteByStream(...) : loveApp.doChatByStream(...)` 分流；在 `concatWith(Flux.defer(...))` 里，`asNote` 分支调 `messageService.appendNoteRound(...)` 拿 id，发 `new ChatEvent.Note(noteId)` 再发 `Done`，观测 scene 用 `NOTE_GENERATE`；普通分支保持原样。`appendNoteRound` 标 `@Transactional`，一个事务内插 USER/QUESTION + ASSISTANT/NOTE 两条并 `touch` 会话，靠 `insertReturningId` 返回 id。`LoveApp.doNoteByStream` 与 `doChatByStream` 同构，但**不挂工具、不挂 RAG**，只挂 `MessageChatMemoryAdvisor` + `MyLoggerAdvisor`。

**批注服务**：`AnnotationService` 注入 `ChatModel deepSeekChatModel`（参数名不可改，按名匹配 bean）。模型调用用方法内构造 `ChatClient.builder(deepSeekChatModel).defaultSystem(system).build()`，与现有 `HelpService` / `QuizService` 同一写法，避免污染 `LoveApp` 单例提示词。scene = `parentId == null ? "ANNOTATION" : "ANNOTATION_CHILD"`。全文不整体截断，只有用户消息里的 `【全文】` 段截前 2000 字，**不做 RAG**（讲义 1000~3000 字直接塞）。取数分两路：SECTION 用 `KnowledgeItemRepository.findById`；NOTE 用 T004 提供的 `MessageRepository.findNoteById`。**`AnnotationService` 不注入 `JdbcTemplate`、不写 SQL** —— v1.1 把 Message 层 NOTE 能力提前到 T004，T005 据此重做，旧版那两条临时 SQL 不再存在（见第 9 节 R7）。

上下文组装：

| 场景 | 用户消息内容 |
|---|---|
| 顶层批注 | `【全文（前 2000 字）】` + `【所在段落】` + `【选中片段】quote` + `【问题】question` |
| 子批注 | `【正文原句】顶层祖先.quote` + `【前文解释】祖先链答案（最多 3 层，由父到子）` + `【父批注答案】parent.answer` + `【本次选中的词】quote` + `【问题】question` |

顶层批注的 `【所在段落】` 由 `locateBlock(fullText, quote)` 生成：以 `quote` 在全文中的 `indexOf` 位置为中心，取前 300 字 + quote + 后 300 字（两端补「……」）；`indexOf < 0` 才退化为全文前 2000 字。后端没有前端的 `splitBlocks`，**不按 `blockKey` 取段落** —— 这样选中句位于长资料末尾时模型仍看得到原句，且上下文约 600 字而非 2000 字。

提示词全文见附录 A；输出硬约束「一段话、60~120 字、中文、不分点、不列清单、不加标题、不复述问题、不用 Markdown 列表、只解释给定语境、不确定直说」写进 system。

**归属校验**（`assertTargetOwned`）：SECTION 用 `UUID.fromString` → `knowledgeItemRepository.belongsTo`；NOTE 用 `Long.parseLong` → T004 提供的 `messageRepository.noteBelongsToUser`（内部即 4.4 的 JOIN SQL）。两条路径都走 Repository，Service 只调方法。解析失败 400，校验失败 `ForbiddenException`。`PATCH`/`DELETE` 用 `repo.belongsTo(id,userId)`；`parentId` 额外校验父的 `user_id` 与 `target_type`/`target_id` 全部与本次一致。

**事务边界**：`appendNoteRound` 用 `@Transactional`；`ask` **不标**（远程 IO 放进事务会占连接），校验 / 模型调用 / 单条 INSERT 各自独立；`updateAnswer`/`deleteById` 单条 SQL；`ChatService.stream` 保持现状不能标（Flux 是装配说明）。

**新增 / 修改类**：新增 `AnnotationRepository`、`AnnotationService`、`AnnotationController`、`NoteService`、`NoteController`、`AnnotationRow`、`TargetType`；改 `MessageType`、`MessageRow`、`ChatEvent`、`MessageRepository`、`MessageService`、`ChatService`、`LoveApp`、`AiChatController`。其中 `MessageType` / `MessageRow` / `MessageRepository` / `MessageService` 的 NOTE 能力在 **T004 先行落盘**，供后续所有任务复用。**其余 Service 一行不动。**

**T004 已交付的 Message 层签名**（2026-11-14 落盘；T005 / T012 直接调用，不要再写临时 SQL）：

| 位置 | 签名 | 说明 |
|---|---|---|
| `model/MessageRow` | `record MessageRow(Long messageId, String role, String content, OffsetDateTime createdAt, String type)` | `type` 追加在末位，旧 4 字段顺序不变 |
| `model/MessageType` | `QUESTION / ANSWER / FEEDBACK / CHAT / NOTE` | C3 |
| `MessageRepository.findAllByConversation` | `List<MessageRow>`，SQL SELECT `m.type` | C1 / TC-08 |
| `MessageRepository.NoteMessage` | `record NoteMessage(long messageId, UUID conversationId, String type, String content, OffsetDateTime createdAt, OffsetDateTime updatedAt)` | 5.2 #2 响应所需字段 |
| `MessageRepository.findNoteById` | `Optional<NoteMessage> findNoteById(long messageId)` | `where id=? and type='NOTE'`；T005 取全文用 `.map(NoteMessage::content)` |
| `MessageRepository.noteBelongsToUser` | `boolean noteBelongsToUser(long messageId, UUID userId)` | 4.4 的跨两层 JOIN SQL，含 `type='NOTE'` |
| `MessageRepository.updateContent` | `Optional<OffsetDateTime> updateContent(long messageId, String content)` | 5.2 #3；同时写 `updated_at`，非 NOTE / 不存在 → empty |
| `MessageRepository.insertReturningId` | `long insertReturningId(UUID, String role, String type, String content)` | **V1 已有**，T004 复用，未重复新增 |
| `MessageService.appendNoteRound` | `@Transactional long appendNoteRound(UUID conversationId, String userText, String noteText)` | USER/QUESTION + ASSISTANT/NOTE + `touch`，返回 NOTE 行 id |

> 注：4.4 的 NOTE 归属 SQL 内含 `m.type='NOTE'`，因此「把本人会话里的普通消息当笔记 id」会在 `noteBelongsToUser` 处得到 false，按 5.2 #2 的归属失败返回 **403**；#2 中「`type != 'NOTE'` → 400」只覆盖归属已通过后的兜底分支。T012 沿用此顺序，不另写 SQL。

### 6.2 前端

**文件清单**：新增 `components/AnnotatableDoc.vue`、`components/AnnotationCard.vue`、`views/NoteDetailView.vue`、`api/notes.js`、`api/annotations.js`；改 `components/ChatRoom.vue`、`views/GuideChatView.vue`、`views/SectionReadView.vue`、`api/chatStream.js`、`utils/markdown.js`、`router/index.js`。

**块切分**（`utils/markdown.js` 追加；用 `marked.lexer` 取顶层 token，避免 `\n\n` 硬切把代码块/列表切坏）：

```js
export function splitBlocks(md) {
  if (!md) return []
  return marked.lexer(md)
    .filter(t => t.type !== 'space')
    .map((t, i) => ({
      key: `p-${i}`,
      text: t.raw,
      html: DOMPurify.sanitize(marked.parser([t]))
    }))
}
```

渲染时每个块包 `<div :data-block-key="b.key" v-html="highlighted(b)">`。`highlighted(b)` 把 `escapeHtml(quote)` 在该块已消毒的 HTML 里替换一次为 `<mark class="quote-hit">…</mark>`；匹配不到返回原 HTML，靠 `.block-has-annotation` 左侧色条提示（3.5 的降级）。

**选区捕获**（`@mouseup` 挂最外层 `.doc-layout` 做事件委托）：取 `getSelection().toString().trim()`；起点用 `closest('[data-annotation-id]')` 判断是否在批注卡片内 → 是则 `parentId = 卡片id`、`blockKey` 继承父；否则取 `closest('[data-block-key]')`，并校验 anchor 与 focus 落在同一块，不同则提示「请只选一段内的文字」并忽略。跨卡片 DOM 的选区同样由最外层委托处理。

**锚点重定位与 stale**（每次加载 / 保存正文后执行，对应 spec FR-10）：

```js
function resolve(blocks, flat) {
  const byId = new Map(flat.map(a => [a.id, { ...a, children: [], stale: false }]))
  for (const a of byId.values()) {
    if (a.parentId && byId.has(a.parentId)) byId.get(a.parentId).children.push(a)
    else if (a.parentId) a.parentId = null   // 父被删但数据残留：按顶层兜底，不丢内容
  }
  for (const a of byId.values()) {
    if (!a.parentId) {
      const hit = blocks.find(b => b.key === a.blockKey && b.text.includes(a.quote))
                  || blocks.find(b => b.text.includes(a.quote))
      a.stale = !hit
      a.groupKey = hit?.key ?? null
    } else {
      const p = byId.get(a.parentId)
      a.stale = !p || !p.answer.includes(a.quote)
      a.groupKey = p?.groupKey ?? null       // 跟随父分组
    }
  }
  return byId
}
```

`stale===true` 统一进「原文已修改」分组，仍显示 `quote/question/answer`；`children` 按 `created_at` 升序保证链顺序稳定。

**两栏布局**：

```css
.doc-layout { display: grid; grid-template-columns: minmax(0, 1.5fr) minmax(280px, 1fr);
              gap: 24px; align-items: start; }
.doc-body { max-width: 720px; }
.note-rail { border-left: 1px solid var(--line); padding-left: 20px; }
@media (max-width: 1024px) {
  .doc-layout { grid-template-columns: 1fr; }
  .note-rail { border-left: none; border-top: 1px solid var(--line); padding: 16px 0 0; }
}
```

复用 `global.css` 的 `--bg/--surface/--ink/--ink-soft/--line/--accent` 与 `.btn`/`.tool-btn`；阅读页 `.action-bar` 保持不动。

**编辑/预览两态**：`bodyEditable=true` 才渲染「编辑」按钮 → `editing=true` 用 `<textarea v-model="draft">` 替换 `.doc-body`（整篇 markdown 原文）→ 点保存时组件 **`emit('save', draft)`**，由父页面注入保存行为（T020 的 `NoteDetailView` 调 `PUT /notes/{id}`）→ 成功后 `markdown=draft`、`editing=false`、重跑 `resolve()`。`bodyEditable=false` 完全不渲染该按钮与 textarea，其余逻辑一字不改。**组件内不 import `api/notes.js`、不写 URL**：M1 阶段（T016/T017）该文件尚不存在，这样前端组件不跨阶段依赖。

**批注卡片**：`AnnotationCard.vue` 用多根 fragment 自引用渲染 `children` —— 第一根是卡片本体，第二根是子卡片容器，所以展开后子卡片是**独立平铺**的兄弟节点，不套在父卡片边框里、不缩进；层级靠 `depth>0` 时显示的「第 N 层」标签表达。追问默认折叠成「展开 N 条追问」小横条，用 `v-if` 控制子卡片渲染（不用 `v-show`，避免 T016 选区委托 `closest('[data-annotation-id]')` 命中看不见的卡片）；卡片带 `data-annotation-id`；`answer` 用 `{{ }}`。

> **T015 视觉口径变更**（2026-11-14 用户看预览图后决定）：原「层层缩进套娃」视觉密度太高，改成「默认折叠、手动打开、展开后卡片独立」。上面原 6.2 的 `marginLeft` 公式与「第 3 层后不再右移」整段作废，`spec.md` FR-08 / AT-05 已同步改成折叠口径。**引用在原文中的位置高亮配色**仍按 FR-06 / C10 的 `.quote-hit` 归 T016 / T017，卡片顶部的引用词标签不改配色。
> 折叠状态按批注 id 存在 `utils/annotationExpand.js`（`createExpandedIds()`）；T016 在 setup 里调一次 `provideExpandedIds()`，整棵树共享同一个 Set，编辑 / 删除后重新 `get()` + `resolve()` 重建列表时只要 id 不变，展开状态就不丢。卡片 inject 不到时各自回落成局部状态，可独立渲染。**自检脚本直接 import 与 Vite SSR 是两份模块实例，所以 key 用字符串常量而不是 `Symbol()`**，否则 provide / inject 对不上。
> 组件契约：props `{ annotation（含 children / stale）, depth }`；emits `changed`（编辑/删除成功后父组件重新 `get()` + `resolve()`）、`error`（中文文案）。**编辑 / 删除由卡片自己调 `api/annotations.js` 的 `patch` / `remove`**，`AnnotatableDoc` 只监听事件、不重复实现；「原文已修改」分组归 `AnnotatableDoc`，卡片只负责显示标签。删除前用 `confirm` 提示「将同时删除 N 条追问」，N 由卡片递归数 `children` 得出。

> **T016 落地口径**（2026-11-14）：上面 `resolve()` / `highlighted(b)` 两段代码抽到 `utils/annotationResolve.js` 作为纯函数导出（`resolve` / `groupAnnotations` / `highlightBlock` / `escapeHtml` / `depthOf` / `normalizeForMatch`），组件只负责渲染，TC-02 得以在 Node 里直接跑。两处对原算法的精确化：
> ① 顶层定位的 `b.text.includes(a.quote)` 改成 `blockContains()` —— 先试原文，再用 `normalizeForMatch()` 去掉 `**` / 反引号 / 链接语法重试。否则带格式的段落（选中文本是渲染后的纯文本，块 raw 却带标记）会被误判「原文已修改」。
> ② 原文「`stale===true` 统一进『原文已修改』分组」只对**顶层**成立。子批注 stale 时仍挂在父卡片的追问链里、只显示标签；把它拎出来单独成组会断链，也与 spec AT-08「子批注显示『原文已修改』且内容保留」不符。
> `highlightBlock()` 在已消毒的块 HTML 里把 `escapeHtml(quote)` 替换**一次**为 `<mark class="quote-hit">`；多个引用按长度降序、用占位符隔离，避免短引用命中已插入的标记文本。匹配不到时返回原 HTML，组件给该块加 `.block-has-annotation` 左侧色条 + 批注数角标（3.5 的降级）。**C10 的 `.quote-hit` / `.block-has-annotation` 已写在组件 scoped 样式里**（荧光黄 `#faeeda` / `#854f0b` / `#ef9f27`），T017 只需调容器宽度、不要重复加样式。
> 组件契约：props `{ markdown, targetType, targetId, bodyEditable=false, initialAnnotations=null, initialExpandedIds=null }`；emits `save(draft)` / `cancel` / `unauthorized`（401 由父页面跳登录，组件不引 router）；`defineExpose({ finishSave, reload })`。两个 `initial*` 是可控渲染入口，供 SSR 自检与预览页注入数据，生产路径不传、组件自己 `onMounted` 拉真接口。编辑态保存只交草稿，**父页面 PUT 成功后须调 `finishSave()` 退出**（markdown 变化时 watch 兜底），失败时草稿不丢。提问面板**就地浮在选区旁**（原「放右栏顶部、不做定位浮层」的口径已被 T017 后的实测反馈推翻，见下方修补注记）。FR-13 的 300/500 字、200 条、链深 8 在前端先拦一道，后端仍按 5.2 在模型调用前兜底。
>
> **T017 落地口径**（2026-11-14）：阅读页只改 `SectionReadView.vue` 一个文件。`contentHtml`（整篇 `renderMarkdown` + 单个 `v-html`）改成 `contentMarkdown`——**交原始 markdown 文本**给 `splitBlocks`，仍跳过与页面标题重复的首行；C8 要求逐块挂 `data-block-key`，整篇 HTML 做不到。页面级 `.prose` 与整组 `:deep(markdown)` 排版规则一并删除，避免与组件 scoped 样式重复定义；C10 高亮样式也不在页面重复加。`.reader-inner` 由 720px 放宽到 **1200px**（C9），窄屏右栏下移仍由组件内 `@media(max-width:1024px)` 负责；`.action-bar`、翻页、检测、`watch(immediate)`、出错与 401 处理全部保留（FR-11）。两个本步新增判断：① 组件加 `:key="detail.knowledgeItemId"`，翻页时重建以清空上一章的批注列表与折叠 id 集合（否则组件复用、状态残留）；② `targetId` 取 `knowledgeItemId` 的 UUID 字符串，与 4.4 `assertTargetOwned(SECTION)` 的 `UUID.fromString` 一致；组件 401 只 `emit('unauthorized')`，父页面用 `redirectOn401` 跳登录。生产路径不传两个 `initial*`。T017 自检用 Vite SSR **真实加载整页**验证 import 链，但只渲染加载态（页面不加测试用 prop，保持生产代码干净），带正文的两栏由组件 SSR 单独渲染；黑盒 AT 仍归 T018 浏览器验收。
>
> **T017 后实测修补 · 提问框就地弹出**（2026-11-14 用户截图反馈「每次提问都要到第一行开头才能输入问题」）：原「面板放右栏顶部、不定位浮层」被推翻。面板改为 `<Teleport to="body">` + `position: fixed`，用 `utils/annotationAskPosition.js` 的 `computeAskPosition()` 按选区矩形定位：宽屏横向贴右栏、纵向与选区齐平；窄屏（右栏已下移）优先落在选区下方、放不下翻到上方；四边 clamp 保证不越视口、不产生横向滚动。打开时 `cloneRange()` 留住原选区并 `focus({ preventScroll: true })` 直接聚焦输入框，scroll / resize 重算位置，取消与卸载解绑；Esc 取消、Ctrl/Cmd+Enter 提交合并进 `onAskKeydown`。**不改 spec 的 FR/AT 编号与 T018 闸门覆盖**，只在 T018 通过标准里加一条「提问框就地弹出、可直接打字」的观察项。
>
> **T018 前第二次实测修补 · 批注分组与正文段落对齐**（2026-11-15 用户截图反馈「生成后的批注跑到第一行了，应该做对齐」）：右栏分组原本按文档流从 `.note-rail` 顶部堆叠，选第 39 段生成的批注仍显示在第一行。改为按正文块的实际 y 坐标给对应分组补 `margin-top`：`alignGroups()` 量 `[data-block-key]` 与 `[data-group-key]` 的位置 / 高度，交给 `utils/annotationRailLayout.js` 的 `computeRailMargins()` 算分组 margin；`.rail-groups` 用 `display:flex; flex-direction:column; gap:18px`（flex 里 margin 不折叠，cursor 递推才精确），组间距由 gap 提供。**不采用绝对定位**：那会让右栏塌陷、相邻段落的批注互相盖住。目标位置被前一组占住时顺序顺延；`__rest__` / `__stale__` 无对应块，紧接上一组；窄屏（≤1024px）与编辑态回退普通流（CSS `margin-top: 0 !important` 双保险）。`onMounted` / `watch(markdown, flat, editing)` / `resize` / 折叠条点击后 `nextTick` 重算。仍**不改 spec 的 FR/AT 编号与 T018 闸门覆盖**，只在 T018 通过标准补「批注卡片与对应段落对齐」。
>
> **T018 前第三次实测修补 · 浏览器白屏与验收口径**（2026-11-15 用户反馈「正文加载不出来了」）：第二次修补把 `watch(() => [props.markdown, flat.value, editing.value], ...)` 写在了 `const editing = ref(false)` 之前。Vue 的 `watch` 在 setup 阶段会**立即跑一次 getter 收集依赖**，浏览器端因此命中 `const` 的暂时性死区（TDZ），`AnnotatableDoc` 的 setup 抛错，阅读页只剩 PageHeader、正文整栏空白；SSR 下 watch 不执行 getter，45/45 的 Node 自检完全测不到。修法是把 `editing` / `draft` 声明提到该 watch 之前，并在 `verify-AnnotatableDoc.mjs` 加静态断言防复发（45 → 46/46）。**据此次事故确立验收口径**：前端任何改动跑完 Node 自检与 `npm run build` 之后，必须用真实浏览器打开生产页面复验，检查控制台有无 Vue runtime 报错、正文是否真渲染、关键交互是否可点；SSR 自检只覆盖结构与纯函数，**不能替代浏览器**。本次真实浏览器结果：控制台零报错、141 个正文块 / 11421 字、`Minor GC` 原文高亮可见、选区浮层 `fixed` 且自动聚焦、1440px 下 `p-38` 分组与正文块偏差 0px / 无横向滚动。编号 / 依赖 / FR 标签 / 闸门 AT-TC 覆盖仍未改动。

> **T018 前第四次实测修补 · 卡片内追问锚点高亮**（2026-11-15 用户截图反馈「批注中被引用的部分没有被高亮标记？」）：`highlightBlock()` 只标了顶层引用在**正文**里的位置；用户红框指的是在**父批注解释里选中的词**（子批注的 `quote`，例如 `Full GC`）没有任何标记，追问生成后看不出针对哪儿问的。口径定为**只标子批注的引用词（追问锚点），不标本卡片自己的引用词**，避免解释文字被标得满屏黄；追问折叠时也照标（`children` 数据始终在）。实现：`annotationResolve.js` 新增纯函数 `splitAnswerByQuotes(answer, quotes)` 把答案切成 `[{ text, hit }]`，匹配规则与 `resolve()` 的子批注 stale 判定一致（原样 `indexOf`，不 normalize / trim / 正则），多个引用按长度降序占区间、重叠时短词顺延到下一次出现、找不到就跳过；`AnnotationCard.vue` 用 `v-for` + `<mark class="quote-hit">` / `{{ seg.text }}` 分段渲染，**只切纯文本、不拼 HTML 字符串、不引入 `v-html`**，NFR-03 转义口径不变；卡片 scoped 样式补一份同配色（正文那条 `.doc-body :deep(mark.quote-hit)` 管不到右栏）。验证：T015 自检 18 → **26/26**（纯函数切分 / 长词优先 / 正则元字符 / 脏输入 / SSR 折叠态 / 只标子不标己 / XSS / 样式作用域），T016 46/46、T017 17/17、T014 14/14、TC-01 8/8 与 `npm run build` 全绿，生产 CSS 含 `.answer mark.quote-hit[data-v-…]`；ego lite 真实浏览器：控制台零报错、141 块 / 11421 字、父答案里 `Full GC` 标黄（`#faeeda` / `#ef9f27`）、正文 `Minor GC` 高亮仍在、选中已被 `<mark>` 包住的词仍能弹出就地提问浮层并自动聚焦、1440px 无横向滚动。**不改编号 / 依赖 / FR 标签 / 闸门 AT-TC 覆盖**，只在 T018 通过标准补一条观察项。

**聊天侧**：`chatStream.js` 的 `streamChat` 加 `asNote = false` 并写进请求体；`ChatRoom.onEvent` 加 `note` 分支 → `aiMsg.type='NOTE'; aiMsg.noteId=payload.noteId`；消息模板按 `m.type==='NOTE'` 渲染卡片（标签 + 首行标题 + 前 80 字 + 「打开笔记 →」→ `router.push('/notes/'+noteId)`）；历史消息映射加 `type: m.type`（依赖 C1）。`GuideChatView` 加 `const asNote = ref(false)` 与第 4 个 `button.tool-btn`，发送后复位。

**路由**：`AppShell.children` 加 `{ path: 'notes/:messageId', name: 'note-detail', component: () => import('../views/NoteDetailView.vue'), meta: { requiresAuth: true } }`。**路由与 `NoteDetailView.vue` 在 T020 同一次提交落盘** —— 旧版把它放在更早的 T018、页面却在更晚的 T019，动态 import 形成半个环；`api/notes.js` 单独在 T019 先做。

---

## 7. 安全与异常

| 风险 / 边界 | 处理 |
|---|---|
| 未登录 | 每个新接口首行 `requireUserId(request)` |
| 越权读改他人笔记 / 批注 | 4.4 的 JOIN / `belongsTo`，先校验再查询；`target_id` 是字符串必须解析后比对 |
| 伪造 `parentId` 挂他人批注 | 校验 I1（父的 `user_id` 与 `target_type`/`target_id` 与本次一致） |
| SQL 注入 / XSS | 全部 `JdbcTemplate` 占位符；正文 DOMPurify；批注文本一律插值；`highlighted()` 只对已消毒 HTML 做转义后精确替换 |
| 提示词注入（正文写「忽略以上指令」） | 上下文用明确分隔标记包裹，输出约束写 system；v0 不做更强隔离 |
| 刷接口 | spec FR-13 的限额全部在模型调用**之前**校验；答案按 200 字硬截断兜底 |
| 模型超时 / 报错 | `recordFailure` → `IllegalStateException`（Spring 默认 **500**），前端展示 `message`；模型故障不是用户输入错误，与 5.2 #5 第 6 步、第 5 节错误码清单一致 |
| 超限 / 跨段落选区 | 400 / 前端拦截，**不发模型调用** |
| 答案超 120 字 | 后端 200 字硬截断，不重试（避免双倍成本） |
| 并发重复提交 | 前端提交期间禁用按钮；后端不做幂等 |
| SSE 中断 | 沿用现有 `error` 帧与 `doOnError` 埋点；笔记未落库则无 `note` 帧，前端按普通回答收尾 |
| SSRF / 命令执行 | 本版不引入 URL 抓取、文件操作、终端工具 |

---

## 8. 测试策略

**自动 / 集成**（沿用现有 `@SpringBootTest` + `@Transactional` 回滚风格）

| # | 用例 |
|---|---|
| TC-01 | `splitBlocks` 对含代码块 / 列表 / 表格的 markdown 不切坏 |
| TC-02 | `resolve()` 三分支：原段落命中 / 段落错位后 `quote` 重定位 / 找不到置 `stale` |
| TC-03 | `POST /annotations/ask` 顶层与子批注各一条，落库字段正确，子批注继承 `block_key` |
| TC-04 | 伪造 `targetId` → 403；伪造他人 `parentId` → 403；链深 9 → 400 |
| TC-05 | `PATCH` 只改 `answer` 与 `edited`；传 `question` → 400 |
| TC-06 | 删父 → 后代全删、无孤儿、`deletedCount` 正确 |
| TC-07 | `asNote=true` 的 SSE 帧序含 `note`（在 `done` 前），落库 ASSISTANT 行 `type='NOTE'` |
| TC-08 | 历史消息接口返回 `type`，NOTE 行可被前端识别（C1） |
| TC-09 | 新增 3 个 scene 均能写入 `t_ai_call`（验证 C6 ALTER 已执行） |
| TC-10 | 未执行 migration 时接口全部失败且有明确报错（防漏跑脚本） |

**手工验收**：直接按 `spec.md` 第 6 节 AT-01~AT-14 跑，不另写一份。`tasks.md` 的 T007（G1 后端闸门）/ T018（M1）/ T022（M2）/ T023（全量回归）分阶段承担；每条 AT 只在所需能力已具备的那个闸门跑，见 `tasks.md` 验收映射表。

---

## 9. 风险与已知限制

| # | 风险 | 缓解 |
|---|---|---|
| R1 | 笔记全文（1000~2000 字）写进同一 `ChatMemory`，可能挤掉历史上下文 | v0 接受；明显影响下一轮时再在 `appendNoteRound` 后截断记忆 |
| R2 | `marked.lexer` 顶层 token 粒度不等于视觉段落（列表/表格整体算一块） | 只影响分组粗细，不影响批注可读性；不做更细拆分 |
| R3 | `blockKey` 按索引 `p-N` 生成，增删段落会整体错位 | `quote` 重定位兜底（6.2 `resolve`）；刻意的降级设计 |
| R4 | 非流式批注在弱网下按钮转圈较久 | 解释短、单段；体感不够再升 SSE |
| R5 | 无 Flyway，迁移可能漏跑 | TC-10 覆盖；执行记录写 `docs/impl-log.md` |
| R6 | 编辑正文后，同一会话历史里 AI 回答与编辑后内容不一致 | spec FR-05 已接受「覆盖写、不保留版本」 |
| R7 | ~~任务排布遗留的技术债~~ **已消除（v1.1）**：旧 T004 的 NOTE 路径曾在 `AnnotationService` 内直接写两条只读 SQL（注入 `JdbcTemplate`），成因是 NOTE 读写底座被排到 Phase 2、隐藏依赖未登记 | 不再靠「后续偿还」兜底：Message 层 NOTE 能力提前为 **T004**，`AnnotationService` 打回 **T005** 未完成并按新前置重做（移除 `JdbcTemplate` 与临时 SQL，测试同步改）。`tasks.md` 7.2 登记了全部 7 项排布缺陷及处理 |

---

## 10. 追踪矩阵（FR → 设计 → 任务）

> v1.1 已按 `tasks.md` 第 0 节映射表重列全部任务编号。

| spec | 本文章节 | `tasks.md`（v1.1） |
|---|---|---|
| FR-01 消息级开关 | 3.5、5.2 #1、6.1 | T009 T010 T011 T021 T023 |
| FR-02 成篇输出 | 6.1、附录 A1 | T009 |
| FR-03 会话内卡片 | 3.4 C1/C4、5.2 #1、6.2 | T004 T008 T021 |
| FR-04 笔记详情页 | 5.2 #2、6.2 | T012 T019 T020 |
| FR-05 正文编辑 | 3.4 C7、5.2 #3、6.2 两态 | T012 T016 T020 |
| FR-06 两栏布局 | 3.3 D5、6.2 | T013 T016 T017 |
| FR-07 顶层批注 | 4、5.2 #5、6.1、6.2 | T005 T006 T014 T016 |
| FR-08 子批注链 | 4.3 I1/I2、5.2 #5、6.1、附录 A3 | T002 T005 T015 T016 |
| FR-09 编辑删除级联 | 4.3 I4、5.2 #6/#7 | T006 T015 |
| FR-10 锚点降级 | 3.3 D3、6.2 `resolve` | T016 T020 |
| FR-11 阅读页复用 | 3.2、3.4 C8~C10、6.2 | T017 |
| FR-12 归属 | 2(4)、4.4、7 | T003 T004 T005 T006 T012 |
| FR-13 边界与限额 | 3.5、5.2 #5、7 | T005 T006 T016 |
| NFR-01 观测 | 3.4 C6、4.2、6.1 | T001 T005 T010 |
| NFR-02 无新依赖 / 无 Key | 2 | 全部任务 |
| NFR-03 注入与 XSS | 7 | T015 T016 |
| NFR-04 迁移可回滚 | 4.2、4.4 | T001 T023 |

---

## 附录 A · 提示词模板（全文，可直接复制）

**A1 `NOTE_PROMPT`（`LoveApp.noteChatClient` 的 `defaultSystem`）**

```
你是一位技术笔记撰写者。用户给出一个问题，你直接写成一篇结构完整的 Markdown 笔记。

要求：
- 结论前置，第一段就给出核心答案。
- 用 ## 划分 2~4 个小节，覆盖：是什么 / 为什么需要 / 大致流程或关键参数 / 常见误区。
- 1000~2000 字，可用代码块、表格、公式，但不要写成问答对话。
- 不要反问用户、不要引导用户自己思考、不要留「下一步你试试」这类话。
- 不确定的内容明确写「不确定」，不伪造文档、链接、版本号。
```

**A2 `EXPLAIN_SYSTEM`（顶层批注）**

```
你是嵌在笔记右侧的批注助手。用户选中了正文里的一句话并提了一个问题。

输出要求（必须严格遵守）：
- 一段话，60~120 字，中文。
- 不分点、不列清单、不加标题、不使用 Markdown 列表、不复述用户的问题。
- 只解释「选中片段」在「给定全文语境」里的意思，不要写成百科词条。
- 用最少的术语；必须出现术语时，顺带用半句话解释它。
- 不确定就说不确定，不编造事实。
```

**A3 `EXPLAIN_CHILD_SYSTEM`（子批注）**

```
你是嵌在笔记右侧的批注助手。用户正在读上一条批注的解释，又选中了其中一个陌生词继续追问。

输出要求（必须严格遵守）：
- 一段话，60~120 字，中文。
- 不分点、不列清单、不加标题、不使用 Markdown 列表、不复述用户的问题。
- 必须结合「正文原句」和「前文解释」的语境来解释这个词，不要泛泛给百科定义。
- 不要重复前文解释已经说过的内容。
- 不确定就说不确定。
```

**A4 用户消息模板**

```
# 顶层
【全文（前 2000 字）】
{fullTextTruncated}

【所在段落】
{blockText}

【选中片段】
{quote}

【问题】
{question}
```

`{fullTextTruncated}` = 全文截前 2000 字；`{blockText}` = `locateBlock(fullText, quote)`，即 quote 命中位置前后各 300 字（两端补「……」），未命中时为全文前 2000 字。模板标签 `【所在段落】` 沿用不改，实际内容不是按 `blockKey` 切的段落。

```
# 子批注
【正文原句】
{rootQuote}

【前文解释】
{ancestorAnswers，最多 3 层，由父到子，用 --- 分隔}

【父批注答案】
{parentAnswer}

【本次选中的词】
{quote}

【问题】
{question}
```
