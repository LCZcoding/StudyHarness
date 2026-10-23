# MVP 主干 · 后端实施手册（按 product-spec.md 重写）

> 业务口径的唯一来源是 [product-spec.md](./product-spec.md)。本文件只回答「怎么落代码」。
> 底座（登录 / 会话 / 消息 / 历史）已完成，除阶段 6 给聊天加 RAG / 工具开关外，本手册不改动已通过的 5 格纵切。
> 返回 [决策日志索引](./decision-log.md) ｜ [表设计](./data-design.md) ｜ [纵切实施记录](./impl-log.md)

***

## 0. 怎么用这份文档

1. **按阶段顺序敲**，每阶段末尾都有「验收」，跑通了再进下一阶段。
2. 每阶段给的是**完整类代码**，整文件照抄；注释保留「为什么」，值得读。
3. **前端归我**。第 11 节列出每个阶段对应的前端改动，后端一通，我就能接上。
4. 评测集**插在阶段 2 之后**（第 5 节），不放到最后。
5. 全文遵守已确立的硬约束：
   - 身份只从 session 取（`CurrentUserResolver.requireUserId`），请求体里没有 `userId`
   - 归属校验失败一律 `ForbiddenException` → 403，同一句文案（不暴露「存在与否」）
   - 枚举转 DB 一律 `name()`，与 `CHECK` 字符串逐字对应
   - Repository 里不出现 `if`；业务拒绝在 Service
   - `@Transactional` 只放在**另一个 bean** 的写方法上（自调用会静默失效）
   - 输入校验在 DTO 边界（`@NotBlank` / `@NotNull` / `@Size`）
   - SQL 里加天数一律用 `make_interval(days => ?)`，不拼 `(? || ' days')::interval`

### 0.1 协作工作流（节奏就按这张表走）

| 步  | 你做                      | 我做                                            | 交接时你给我什么                                          |
| --- | ------------------------- | ----------------------------------------------- | --------------------------------------------------------- |
| 1   | 阶段 0 建表               | 不动                                            | `\d t_question` 的输出                                    |
| 2   | 阶段 1 资料接入           | 读你的代码 → 写 `/materials` 页                 | 3 条真实响应 JSON：粘贴 201、上传 PDF 201、非法扩展名 400 |
| 3   | 阶段 2 出题               | 等（不写前端）                                  | 1 条出题的响应 JSON                                       |
| 4   | 跑评测集（第 5 节）       | 等                                              | 通过率数字                                                |
| 5   | 阶段 3 作答判分           | 测验页一次写完（出题 / 作答 / 反馈 / 补讲四态） | 1 条作答的响应 JSON                                       |
| 6   | 阶段 4 待办 + 复习 + 进度 | 待办侧栏、`/review` 页、`/progress` 页          | 3 条 JSON：`/todos`、`/review/start`、`/progress`         |
| 7   | 阶段 5 引导实践           | 资料页「怎么练」按钮 + 练习对话页               | 1 条 JSON：`/practice/guide`                              |
| 8   | 阶段 6 工具 + RAG + 观测  | 聊天框两个开关 + 观测面板                       | 3 条 JSON：`useRag` 开/关各一条、`/metrics/summary` 一条  |
| 9   | 阶段 7 真实闭环           | 收口文档 + 台账回填                             | 一句话「闭环跑通了」                                      |

**五条规则**

1. **交接物是响应 JSON，不是口头「通了」**。文档里的字段名是我拟的；你手敲时把 `sourceUrl` 写成 `source_url`、
   把 `knowledgeItemId` 写成 `id` 都很正常。我按你**实际返回的 JSON** 写前端，不按文档猜。
   登录后的接口请你在浏览器里先登录，用 DevTools 的 Network 面板复制响应。
2. **阶段 2 完成先跑评测集，通过率 ≥ 70% 再进阶段 3**。出题的结构化输出不稳定，后面的判分逻辑就建在流沙上。
3. **阶段 2 与阶段 3 的前端合并做**。出题 / 作答 / 反馈是同一个测验页的三种状态，拆开等于同一份文件写两遍。
   所以阶段 2 通了**不要停下来等我**，直接敲阶段 3。
4. 每阶段结束我会回填 [impl-log.md](./impl-log.md)，并把预测外问题记进 [resume-assets.md](./resume-assets.md)。
5. **前后端进程一律你自己启**（后端 8123 / 前端 5173），我不代起 —— 后台进程的日志你看不到，出错时无法自查。

***

## 1. 本次范围与数据模型

### 1.1 保留什么、不做什么

| 能力       | MVP 形态                                                                                        | product-spec 对应 |
| ---------- | ----------------------------------------------------------------------------------------------- | ----------------- |
| 资料接入   | 粘贴文本 + 上传 `.md` / `.txt` / `.pdf`（仅文字层），上传时生成 200 字摘要                      | 第 10 节          |
| 出题       | 一次 3 个任务，类型 `FACT` / `EXPLAIN`，每题带 `target`                                         | 第 5 节           |
| 作答判分   | `PASS` / `PARTIAL` / `FAIL`，返回漏洞清单，`PARTIAL` 允许补讲 1 次                              | 第 6 节           |
| 复习       | 1 / 3 / 7 天递进，`REVIEW` 会话 = 2 道到期题 + 1 道交错题                                       | 第 7 节           |
| 待办       | `GET /todos` 三类：到期复习 / 需要重讲 / 还没测过                                               | 第 7 节           |
| 知识点     | `CANDIDATE` → `VERIFIED`，出题时注入最近 5 个 `VERIFIED` 做类比                                 | 第 8 节           |
| 引导实践   | `PRACTICE` 会话，提示词产出练习指引，**不判分、不进闭环**                                       | 第 4、5 节        |
| 进度       | 已掌握资料数 / 连续学习天数 / 待复习数 + 近 8 周讲解次数                                        | 第 9 节           |
| 自由问答   | 保留 RAG（按 `userId` 过滤自己的资料）与工具（联网搜索等）                                      | 第 4 节           |
| 运行观测   | 每次模型调用落一行：场景 / 延迟 / 首 token / token 数 / 成败；`GET /metrics/summary` 按场景汇总 | 第 12 节          |
| 上下文预算 | 聊天历史按字符预算裁剪，超预算从最旧的消息丢                                                    | 第 12 节          |
| 离线评测   | 出题 / 判分 / RAG 三套评测，数字抄进 `impl-log.md`                                              | 第 12 节          |

**不做**：网页抓取导入、知识图谱可视化、积分徽章排行榜、SM-2 / FSRS 拟合、OCR、语音、JWT、多用户协作。
理由见 [product-spec.md 第 11 节](./product-spec.md)。

**总表数：9 张**。已有 3 张（`t_user` / `t_conversation` / `t_message`），本阶段新增 6 张
（`t_knowledge_item` / `t_knowledge_point` / `t_question` / `t_test_record` / `t_review_schedule` / `t_ai_call`）。

### 1.2 表关系（先看懂这张再敲 SQL）

```
t_user 1 ─ n t_knowledge_item 1 ─ n t_question n ─ 1 t_message
                                      │
                                      ├─ n ─ 1 t_knowledge_point
                                      ├─ 1 ─ 1 t_review_schedule
                                      └─ 1 ─ n t_test_record
```

三句话记住这版设计：

1. **资料归属挂在题目上，不挂在会话上**。`REVIEW` 会话会混入另一份资料的题，会话级归属会记错账。
2. **复习排期跟着「当前那道题」走，一题一行**。补讲 / 重问 / 复习都会生成新的 `t_question` 行，
   排期行通过 `UPDATE question_id` 迁到新题上，不会出现「一次作答三条排期、补讲又来三条」。
3. **知识点一题一个**。`t_question.target` 是名词短语，`t_question.knowledge_point_id` 指向词汇表；
   一道题考多个知识点时只记主考点（写进技术债）。

### 1.3 索引规则：一条索引对应一个具名查询

统一规则：**被 `WHERE` / `JOIN` / `ORDER BY` 用到的外键列建索引；找不到具名查询的不建。**
每条索引在下表都能对上一条本手册里真实存在的 SQL。 

| 索引 / 约束                            | 服务的查询                                                                   | 出现在         |
| -------------------------------------- | ---------------------------------------------------------------------------- | -------------- |
| `idx_conversation_user_updated`        | 左侧会话列表：`where user_id = ? order by updated_at desc`                   | 已完成（基线） |
| `idx_message_conversation`             | 历史消息：`where conversation_id = ? order by id`                            | 已完成（基线） |
| `idx_knowledge_item_user`              | `where user_id = ?`（资料列表 / 进度分母）                                   | 阶段 1、4      |
| `uq_knowledge_point_user_name`         | `on conflict (user_id, name)` 的冲突目标；`where user_id = ?` 也用它的左前缀 | 阶段 2         |
| `t_question.message_id UNIQUE`         | 一题对应一条消息；按消息回查题目                                             | 阶段 2、3      |
| `idx_question_item`                    | `where knowledge_item_id = ?`（该资料下答错过的题 / 还没测过）               | 阶段 2、4      |
| `idx_test_record_question`             | `distinct on (question_id) ... order by question_id, id desc`                | 阶段 3、4      |
| `idx_test_record_user_time`            | 连续天数、近 8 周统计：`where user_id = ?`                                   | 阶段 4         |
| `t_review_schedule.question_id UNIQUE` | `on conflict (question_id)`；按题查当前排期                                  | 阶段 3、4      |
| `idx_review_due`（部分索引）           | 到期待办与复习：`where user_id = ? and status='PENDING' and due_at <= now()` | 阶段 3、4      |

**不建**：`t_knowledge_point.knowledge_point_id` 反向索引、`t_test_record.verdict` 索引、`t_conversation.mode` 索引。
这些都找不到高频具名查询，建了只会拖慢写入。

***

## 2. 阶段 0：建表

> 对应 [product-spec.md 第 3、4、7、8 节](./product-spec.md)。

### 2.1 追加到 `db/schema.sql` 末尾

整段可以直接追加：**空库**执行会从 `t_knowledge_item` 一路建到 `t_ai_call`，
并在已有 3 张基线表上补两条索引。**旧库不会被升级**：本段没有 `ALTER`，
已存在的表会因 `CREATE TABLE IF NOT EXISTS` 直接跳过，缺列请手工改。
**执行顺序不可颠倒**：`t_question` 依赖 `t_message` / `t_knowledge_item` / `t_knowledge_point`，
`t_test_record` 与 `t_review_schedule` 依赖 `t_question`。

```sql
-- ============================================================
-- 表设计 v3（MVP 主干，按 docs/product-spec.md 重写）
-- 依赖顺序：t_knowledge_item -> t_knowledge_point -> t_question
--           -> t_test_record -> t_review_schedule -> t_ai_call
-- 面向空库：不含补列用的 ALTER
-- ============================================================

-- 0) 基线表索引（t_conversation / t_message 已存在，只补查询要用的两条）
--    会话列表：where user_id = ? order by updated_at desc
CREATE INDEX IF NOT EXISTS idx_conversation_user_updated
    ON t_conversation(user_id, updated_at DESC);
--    历史消息：where conversation_id = ? order by id
CREATE INDEX IF NOT EXISTS idx_message_conversation
    ON t_message(conversation_id, id);

-- 1) 资料：summary 是上传时生成的一句话摘要，模型失败时可为空
CREATE TABLE IF NOT EXISTS t_knowledge_item (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID         NOT NULL REFERENCES t_user(id),
    title      VARCHAR(100) NOT NULL,
    source_url VARCHAR(500),
    content    TEXT         NOT NULL,
    summary    VARCHAR(300),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_knowledge_item_user ON t_knowledge_item(user_id);

-- 2) 知识点：出题时写 CANDIDATE，拿到 PASS / PARTIAL 后升 VERIFIED
CREATE TABLE IF NOT EXISTS t_knowledge_point (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID         NOT NULL REFERENCES t_user(id),
    name       VARCHAR(120) NOT NULL,
    status     VARCHAR(10)  NOT NULL DEFAULT 'CANDIDATE'
                            CHECK (status IN ('CANDIDATE','VERIFIED')),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_knowledge_point_user_name UNIQUE (user_id, name)
);

-- 3) 题目：一题一行，指向所在的对话消息、取材资料、主知识点
CREATE TABLE IF NOT EXISTS t_question (
    id                 BIGINT      PRIMARY KEY GENERATED BY DEFAULT AS IDENTITY,
    message_id         BIGINT      NOT NULL UNIQUE REFERENCES t_message(id),
    knowledge_item_id  UUID        NOT NULL REFERENCES t_knowledge_item(id),
    knowledge_point_id UUID        REFERENCES t_knowledge_point(id),
    kind               VARCHAR(10) NOT NULL CHECK (kind IN ('FACT','EXPLAIN')),
    target             VARCHAR(200) NOT NULL,
    -- 这道题是否点过「我卡住了」。放在题目上，因为求助可能发生在作答之前（那时还没有成绩行）
    help_used          BOOLEAN     NOT NULL DEFAULT false,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_question_item ON t_question(knowledge_item_id);

-- 4) 成绩：一次提交一行。首答 attempt_no=1，补讲 attempt_no=2
--    是否求助过记在 t_question.help_used，这里不再冗余一列
CREATE TABLE IF NOT EXISTS t_test_record (
    id             BIGINT      PRIMARY KEY GENERATED BY DEFAULT AS IDENTITY,
    user_id        UUID        NOT NULL REFERENCES t_user(id),
    question_id    BIGINT      NOT NULL REFERENCES t_question(id),
    answer         TEXT        NOT NULL,
    feedback       TEXT        NOT NULL,
    verdict        VARCHAR(10) NOT NULL CHECK (verdict IN ('PASS','PARTIAL','FAIL')),
    attempt_no     INT         NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_test_record_question ON t_test_record(question_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_test_record_user_time ON t_test_record(user_id, created_at DESC);

-- 5) 复习排期：一题一行。重复作答 / 复习复制题时，这一行跟着 question_id 迁移
CREATE TABLE IF NOT EXISTS t_review_schedule (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES t_user(id),
    question_id BIGINT      NOT NULL UNIQUE REFERENCES t_question(id),
    stage       INT         NOT NULL CHECK (stage IN (1,3,7)),
    due_at      TIMESTAMPTZ NOT NULL,
    status      VARCHAR(10) NOT NULL DEFAULT 'PENDING'
                            CHECK (status IN ('PENDING','DONE','CANCELED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    done_at     TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_review_due
    ON t_review_schedule(user_id, due_at) WHERE status = 'PENDING';

-- 6) AI 调用观测：一次模型调用一行（流式调用也一行，token 可能为 null）
CREATE TABLE IF NOT EXISTS t_ai_call (
    id                BIGINT      PRIMARY KEY GENERATED BY DEFAULT AS IDENTITY,
    user_id           UUID        NOT NULL REFERENCES t_user(id),
    scene             VARCHAR(20) NOT NULL
                      CHECK (scene IN ('CHAT','QUIZ_GENERATE','GRADE','HELP','SUMMARY','PRACTICE')),
    latency_ms        INT         NOT NULL,
    first_token_ms    INT,
    prompt_tokens     INT,
    completion_tokens INT,
    success           BOOLEAN     NOT NULL DEFAULT true,
    error_code        VARCHAR(60),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ai_call_user_time ON t_ai_call(user_id, created_at DESC);
```

### 2.2 为什么这么设计（面试会被问的九处）

| 设计                                                             | 理由                                                                                                                                                                            |
| ---------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `t_conversation` 不存 `title`                                    | 标题取该会话首条 `USER` 消息的前 50 个字符，一条子查询就能算出来。存一列就要在每条消息写入时维护，改一次消息还得回头改标题，必然出现不一致                                      |
| 资料归属放 `t_question.knowledge_item_id`，不放 `t_conversation` | `REVIEW` 会话按「2 道到期题 + 1 道其他资料的题」混排，同一会话天然跨资料。挂在会话上会让资料 A 的掌握度虚高、资料 B 永远「没测过」。挂在题目上，每次判分都能回溯到准确的资料    |
| 复习排期用 `question_id` 唯一，不用 `test_record_id`             | 一题可以被答多次（首答 + 补讲 + 复习）。按成绩建排期会「答一次排三条、补讲再排三条」；按题建排期一行就够，重答时 `ON CONFLICT (question_id) DO UPDATE` 原地改期                 |
| 知识点一题一个（`t_question.knowledge_point_id`）                | `product-spec.md` 定义每题只有一个 `target`。用关联表是一对多建模，多出来的灵活性本轮用不上，反而要多维护一张表                                                                 |
| `summary` 允许为空                                               | 摘要是一次额外模型调用。调用失败不能阻断「资料已保存」；列表没摘要也能显示标题，出题仍用 `content`                                                                              |
| `t_test_record.user_id` 是冗余列（可从题目反查）                 | 连续天数、近 8 周统计每次都要 `where user_id`，走三表 JOIN 才能拿到人，会拖慢进度页。这一列**写入一次、永不修改**，不存在与题目归属不一致的可能                                 |
| 求助记在 `t_question.help_used`，不在 `t_test_record` 再存一份   | 求助可能发生在**作答之前**（那时还没有成绩行），挂成绩上就记不到。一题一个布尔值足够表达「这题被求助过，复习时优先重问」                                                        |
| `t_ai_call` 的 token 列允许 `null`                               | 流式调用能不能拿到 usage 由模型服务端决定（DeepSeek 需要 `stream_options.include_usage`）。拿不到就存 `null`，前端显示「未返回」；**估算出来的 token 是假数据**，不能拿去写简历 |
| `t_ai_call` 不建 `scene` 索引                                    | 查询永远带 `user_id` + 时间窗，`idx_ai_call_user_time` 已经能筛到很少的行；`scene` 只是 `group by` 维度，单独建索引等于白付写入成本                                             |

### 2.3 验收

```powershell
# 1) 整段重放两次，第二次必须不报错（全是 IF NOT EXISTS，会跳过已有对象）
\i db/schema.sql
\i db/schema.sql

# 2) 看外键与 CHECK 是否都建上（应列出 5 张表的约束）
select conrelid::regclass as tbl, conname, pg_get_constraintdef(oid) as def
  from pg_constraint
 where conrelid in ('t_question'::regclass,
                    't_test_record'::regclass,
                    't_review_schedule'::regclass,
                    't_knowledge_point'::regclass,
                    't_ai_call'::regclass)
 order by tbl, conname;

# 3) 看索引（应看到 idx_question_item / idx_test_record_question /
#    idx_test_record_user_time / idx_review_due / idx_ai_call_user_time 与两个 UNIQUE）
\d t_question
\d t_review_schedule
\d t_ai_call
```

**四条断言**：

| 断言                                                           | 验的是什么                                                                                  |
| -------------------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| 第二次执行 `\i` 无报错                                         | 全是 `CREATE ... IF NOT EXISTS`，重放会跳过；**但跳过不等于升级结构**，缺列仍需手工 `ALTER` |
| `t_question` 有 `kind` 的 `CHECK (kind IN ('FACT','EXPLAIN'))` | 任务类型在数据库层兜底                                                                      |
| `t_review_schedule` 有 `question_id` 的 `UNIQUE`               | 一题一行排期，`ON CONFLICT (question_id)` 才成立                                            |
| `t_ai_call` 有 `scene` 的 `CHECK`                              | 场景枚举在数据库层兜底，脏字符串进不来                                                      |

> 不要用「插入一条非法 stage 看是否报 23514」来验收：没有题目行时外键会先报 23503，
> 分不清是 CHECK 还是 FK 拦的。直接看 `pg_get_constraintdef` 更准。

***

## 3. 阶段 1：资料接入（第一层）

> 对应 [product-spec.md 第 10 节](./product-spec.md)。
>
> **2026-10-23 变更**：产品形态改为「文档 → 章节」。3.1 ~ 3.7 记录的是**改之前**的实现（你已经写完的版本，原样保留）；
> 要动手改的地方全部列在 [3.8 增量改动](#38-增量改动文档--章节2026-10-23)，先看那一节。

### 3.1 契约

| 方法 | 路径                      | 请求                                              | 成功                                                                  | 失败                                                         |
| ---- | ------------------------- | ------------------------------------------------- | --------------------------------------------------------------------- | ------------------------------------------------------------ |
| POST | `/knowledge-items`        | `{"title":"...", "content":"..."}`                | `201` + `Location` + `{"knowledgeItemId","preview","summary"}`        | 401 / 400 字段为空或超长                                     |
| POST | `/knowledge-items/upload` | `multipart/form-data`：`file` + `title`（可省略） | 同上                                                                  | 401 / 400 扩展名不支持、没有可提取的文字、文档太长、文件过大 |
| GET  | `/knowledge-items`        | —                                                 | `200 [{"knowledgeItemId","title","sourceUrl","summary","createdAt"}]` | 401                                                          |

`preview` = 正文前 200 字符，给用户一眼确认「抽出来的文字是不是乱的」；
`summary` = 一句话摘要（≤200 字），给列表展示；摘要生成失败时为 `null`，不阻断保存。

**支持的文件类型（按扩展名判断，不看 MIME —— 浏览器给的 MIME 不可靠）**

| 扩展名         | 处理                                                      |
| -------------- | --------------------------------------------------------- |
| `.md` / `.txt` | 字节按 UTF-8 解码成正文                                   |
| `.pdf`         | `PdfTextExtractor` 逐页抽文字（只支持**有文字层**的 PDF） |
| 其他           | 400「只支持 .md / .txt / .pdf」                           |

**四条硬约束（不满足就 400，不静默放行）**

| 约束             | 阈值                           | 不满足时的文案                                                           |
| ---------------- | ------------------------------ | ------------------------------------------------------------------------ |
| 文件大小         | ≤ 5MB（`multipart` 限制）      | 「文件太大，请控制在 5MB 以内」                                          |
| 抽取后正文非空   | ≥ 100 字符                     | 「这个文件没有可提取的文字（PDF 可能是扫描件），请换成文字版或转成 .md」 |
| 抽取后正文不太长 | ≤ 50000 字符                   | 「文档太长（约 N 字），请拆分后分次导入」                                |
| 粘贴正文         | ≤ 20000 字符（DTO 的 `@Size`） | 「正文最多 20000 字」                                                    |

`application.yml` 的 `spring` 节点下加：

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 5MB       # PDF 带图很容易上 MB，100KB 会连正常讲义都传不进来
      max-request-size: 6MB
```

### 3.2 异常 + 文本抽取

**① `exception/BadRequestException.java`**（本阶段第一次需要「400 + 自定义文案」）

现有 `UnauthorizedException` / `ForbiddenException` 只管 401 / 403。这里的拒绝都是 400、文案各不相同，
所以新增一个同类异常，而不是在 Controller 里拼 `ResponseEntity`：

```java
package com.lcz.aiagentstudy.exception;

/** 请求本身不合法（不是未登录、也不是越权）。文案由抛出的地方决定。 */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
```

**② `web/GlobalExceptionHandler` 追加两个处理器**

```java
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<?> handleBadRequest(BadRequestException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }

    // 超过 multipart 上限时 Tomcat 抛这个。不映射会落到 500，前端只能提示"服务异常"，
    // 用户不知道自己只是文件太大了。
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> handleTooLarge(MaxUploadSizeExceededException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", "文件太大，请控制在 5MB 以内"));
    }
```

导入：

```java
import com.lcz.aiagentstudy.exception.BadRequestException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
```

> `MaxUploadSizeExceededException` 的 message 带着字节数等内部细节，**不要直接转发**。
> 这里硬编码一句给用户看的话是刻意的例外：它说的是「限额是多少」，不是「哪条业务规则拒绝了你」。

**③ `service/DocumentTextExtractor.java`**

```java
package com.lcz.aiagentstudy.service;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import com.lcz.aiagentstudy.exception.BadRequestException;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 把上传的文件转成纯文本。只管「字节 -> 文本」，不碰数据库、不碰 session。
 * 三条边界都在这里拒绝：扩展名不支持、抽不出字、太长。
 */
@Component
public class DocumentTextExtractor {

    private static final int MIN_CHARS = 100;
    private static final int MAX_CHARS = 50_000;

    public String extract(String filename, byte[] bytes) {
        String lower = (filename == null ? "" : filename).toLowerCase(Locale.ROOT);

        String text;
        if (lower.endsWith(".md") || lower.endsWith(".txt")) {
            text = new String(bytes, StandardCharsets.UTF_8);
        } else if (lower.endsWith(".pdf")) {
            text = extractPdf(bytes);
        } else {
            throw new BadRequestException("只支持 .md / .txt / .pdf");
        }

        if (text.length() < MIN_CHARS) {
            // 扫描件 PDF 抽出来是空串；这个判断同时兜住"传了个空文件"
            throw new BadRequestException("这个文件没有可提取的文字（PDF 可能是扫描件），请换成文字版或转成 .md");
        }
        if (text.length() > MAX_CHARS) {
            throw new BadRequestException("文档太长（约 " + text.length() + " 字），请拆分后分次导入");
        }
        return text;
    }

    private String extractPdf(byte[] bytes) {
        try (PdfDocument pdf = new PdfDocument(new PdfReader(new ByteArrayInputStream(bytes)))) {
            StringBuilder sb = new StringBuilder();
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                sb.append(PdfTextExtractor.getTextFromPage(pdf.getPage(page))).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            // 文件不是合法 PDF（改了扩展名）、或加了密码
            throw new BadRequestException("PDF 解析失败，请确认文件未加密且格式正常");
        }
    }
}
```

> **iText 9 的包路径要你实测一次**：`itext-core 9.1.0` 已在 pom 里，`PdfTextExtractor` 属于 kernel 模块。
> 若 import 报错，用 IDEA 自动补全确认真实包名（8.x 到 9.x 有过模块整理），别照文档硬敲。
>
> 抽出来的文本**不修排版**（双栏、页眉页脚、公式都会留噪声）。理由是修排版是另一个量级的工作，
> 而用户能在 `preview` 里一眼看到 —— **把质量判断交给能用眼睛判断的人**，比自己写清洗规则划算。

### 3.3 `model/KnowledgeItemRow.java`

```java
package com.lcz.aiagentstudy.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 知识条目（用户自己的学习材料）。列表接口不取 content，避免一次拉太多正文。 */
public record KnowledgeItemRow(UUID id, String title, String sourceUrl, String content,
                               String summary, OffsetDateTime createdAt) {
}
```

### 3.4 `repository/KnowledgeItemRepository.java`

```java
package com.lcz.aiagentstudy.repository;

import com.lcz.aiagentstudy.model.KnowledgeItemRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class KnowledgeItemRepository {
    private final JdbcTemplate jdbcTemplate;

    public UUID create(UUID userId, String title, String sourceUrl, String content, String summary) {
        return jdbcTemplate.queryForObject("""
                        insert into t_knowledge_item (user_id, title, source_url, content, summary)
                        values (?, ?, ?, ?, ?) returning id
                        """,
                UUID.class, userId, title, sourceUrl, content, summary);
    }

    // 列表不取 content：正文可能很长，列表只需要标题与摘要
    public List<KnowledgeItemRow> findAllByUserId(UUID userId) {
        return jdbcTemplate.query("""
                        select id, title, source_url, summary, created_at
                          from t_knowledge_item
                         where user_id = ?
                         order by created_at desc
                        """,
                (rs, rowNum) -> new KnowledgeItemRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("title"),
                        rs.getString("source_url"),
                        null,
                        rs.getString("summary"),
                        rs.getObject("created_at", OffsetDateTime.class)),
                userId);
    }

    public Optional<KnowledgeItemRow> findById(UUID id) {
        List<KnowledgeItemRow> rows = jdbcTemplate.query("""
                        select id, title, source_url, content, summary, created_at
                          from t_knowledge_item
                         where id = ?
                        """,
                (rs, rowNum) -> new KnowledgeItemRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("title"),
                        rs.getString("source_url"),
                        rs.getString("content"),
                        rs.getString("summary"),
                        rs.getObject("created_at", OffsetDateTime.class)),
                id);
        return rows.stream().findFirst();
    }

    // 归属校验用：只回答"是不是这个人的"，不泄露条目是否存在
    public boolean belongsTo(UUID itemId, UUID userId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "select exists(select 1 from t_knowledge_item where id = ? and user_id = ?)",
                Boolean.class, itemId, userId));
    }

    // 交错复习用：从"另一份资料"里随机取一条；没有第二条资料时返回空
    public Optional<UUID> findAnotherItemId(UUID userId, UUID excludeItemId) {
        List<UUID> rows = jdbcTemplate.query("""
                        select id from t_knowledge_item
                         where user_id = ? and id <> ?
                         order by random()
                         limit 1
                        """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                userId, excludeItemId);
        return rows.stream().findFirst();
    }
}
```

### 3.5 `service/KnowledgeService.java`

```java
package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.exception.ForbiddenException;
import com.lcz.aiagentstudy.model.KnowledgeItemRow;
import com.lcz.aiagentstudy.repository.KnowledgeItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeService {
    private final KnowledgeItemRepository knowledgeItemRepository;

    // 参数名必须叫 deepSeekChatModel：项目里配了多个模型，Spring 按参数名匹配 bean
    private final ChatModel deepSeekChatModel;

    // 用 ObjectProvider：pgvector 未启用（app.pgvector.enabled=false）时容器里没有 VectorStore，
    // 直接注入会启动失败。向量是增强项，不该成为"存资料"的前置条件。
    private final ObjectProvider<VectorStore> vectorStoreProvider;

    /** 创建结果带上摘要，Controller 直接回给前端，省一次列表请求。 */
    public record CreatedItem(UUID id, String summary) {
    }

    public CreatedItem create(UUID userId, String title, String sourceUrl, String content) {
        String summary = summarizeQuietly(content);
        UUID id = knowledgeItemRepository.create(userId, title, sourceUrl, content, summary);
        indexQuietly(id, userId, title, content);
        return new CreatedItem(id, summary);
    }

    public List<KnowledgeItemRow> list(UUID userId) {
        return knowledgeItemRepository.findAllByUserId(userId);
    }

    /** 出题/复习/判分用：先验归属，再取正文。两种情况同一句文案，不泄露"存在与否"。 */
    public KnowledgeItemRow requireOwned(UUID itemId, UUID userId) {
        if (!knowledgeItemRepository.belongsTo(itemId, userId)) {
            throw new ForbiddenException("资料不存在或无权访问");
        }
        return knowledgeItemRepository.findById(itemId)
                .orElseThrow(() -> new ForbiddenException("资料不存在或无权访问"));
    }

    /**
     * 一句话摘要。失败只记日志、返回 null：资料已经存下来了，列表没有摘要也能用，
     * 不该因为模型抖动就让用户重传。
     */
    private String summarizeQuietly(String content) {
        try {
            ChatClient client = ChatClient.builder(deepSeekChatModel).build();
            String summary = client.prompt()
                    .system("用不超过 200 字概括这份学习材料的主题与覆盖范围，只输出摘要本身，不要加标题。")
                    .user(content)
                    .call()
                    .content();
            if (summary == null || summary.isBlank()) {
                return null;
            }
            String trimmed = summary.trim();
            return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300);
        } catch (RuntimeException e) {
            log.warn("生成资料摘要失败，summary 置空: cause={}", e.getMessage());
            return null;
        }
    }

    /**
     * 向量化。文档 id 直接用 knowledgeItemId —— "一条资料"与"一条向量"一一对应，
     * 将来要删/要更新，凭同一个 id 就能定位，不用再存映射。
     * 入库失败只记日志：文本已经落库，出题可以退化为直接读正文，不该因为向量库抖动就 500。
     */
    private void indexQuietly(UUID itemId, UUID userId, String title, String content) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            log.warn("VectorStore 未启用，跳过向量化: knowledgeItemId={}", itemId);
            return;
        }
        try {
            Document document = Document.builder()
                    .id(itemId.toString())
                    .text(content)
                    // 这两个 key 就是将来 filterExpression 能用的全部依据
                    .metadata("userId", userId.toString())
                    .metadata("title", title)
                    .build();
            vectorStore.add(List.of(document));
        } catch (Exception e) {
            log.warn("向量化失败，资料已保存但暂不参与 RAG: knowledgeItemId={}, cause={}",
                    itemId, e.getMessage());
        }
    }
}
```

### 3.6 `controller/KnowledgeItemController.java`

```java
package com.lcz.aiagentstudy.controller;

import com.lcz.aiagentstudy.service.DocumentTextExtractor;
import com.lcz.aiagentstudy.service.KnowledgeService;
import com.lcz.aiagentstudy.web.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/knowledge-items")
@RequiredArgsConstructor
public class KnowledgeItemController {
    private static final int PREVIEW_CHARS = 200;

    private final CurrentUserResolver currentUserResolver;
    private final KnowledgeService knowledgeService;
    private final DocumentTextExtractor documentTextExtractor;

    public record CreateRequest(
            @NotBlank(message = "title 不能为空")
            @Size(max = 100, message = "title 过长") String title,
            @NotBlank(message = "content 不能为空")
            @Size(max = 20000, message = "正文最多 20000 字") String content) {
    }

    public record CreateResponse(String knowledgeItemId, String preview, String summary) {
    }

    public record ItemView(UUID knowledgeItemId, String title, String sourceUrl,
                           String summary, OffsetDateTime createdAt) {
    }

    @PostMapping
    public ResponseEntity<CreateResponse> create(@Valid @RequestBody CreateRequest req,
                                                 HttpServletRequest request) {
        UUID userId = currentUserResolver.requireUserId(request);
        KnowledgeService.CreatedItem created = knowledgeService.create(userId, req.title(), null, req.content());
        return ResponseEntity.created(URI.create("/knowledge-items/" + created.id()))
                .body(new CreateResponse(created.id().toString(), preview(req.content()), created.summary()));
    }

    @PostMapping("/upload")
    public ResponseEntity<CreateResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            HttpServletRequest request) throws IOException {

        UUID userId = currentUserResolver.requireUserId(request);

        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        // 扩展名、空文本、超长三处拒绝都在这里抛 BadRequestException（-> 400）
        String content = documentTextExtractor.extract(filename, file.getBytes());

        // 标题没填就用文件名（去扩展名）；空文件名兜底成"未命名资料"
        String finalTitle = (title == null || title.isBlank()) ? defaultTitle(filename) : title;

        KnowledgeService.CreatedItem created = knowledgeService.create(userId, finalTitle, filename, content);
        return ResponseEntity.created(URI.create("/knowledge-items/" + created.id()))
                .body(new CreateResponse(created.id().toString(), preview(content), created.summary()));
    }

    @GetMapping
    public List<ItemView> list(HttpServletRequest request) {
        UUID userId = currentUserResolver.requireUserId(request);
        return knowledgeService.list(userId).stream()
                .map(item -> new ItemView(item.id(), item.title(), item.sourceUrl(),
                        item.summary(), item.createdAt()))
                .toList();
    }

    private static String defaultTitle(String filename) {
        int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        String base = slash >= 0 ? filename.substring(slash + 1) : filename;
        int dot = base.lastIndexOf('.');
        base = dot > 0 ? base.substring(0, dot) : base;
        return base.isBlank() ? "未命名资料" : base.substring(0, Math.min(100, base.length()));
    }

    /** 前 200 字，给前端确认"抽出来的文字是不是乱的"。换行压成空格，避免预览框里空行太多。 */
    private static String preview(String content) {
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= PREVIEW_CHARS ? flat : flat.substring(0, PREVIEW_CHARS) + "…";
    }
}
```

> `Location` 用 `/knowledge-items/{id}`，与 ②格 `POST /conversations` 的写法一致（不带 context-path）。
> 目前没做 `GET /knowledge-items/{id}`，所以这个 `Location` 暂不可解引用 —— **这是有意的**：
> 列表已经有标题与摘要，正文只在出题/判分时用；真要加详情接口，再补一条即可。

### 3.7 验收

```powershell
# 1) 粘贴（浏览器里先登录，复制 JSESSIONID）
curl.exe -i -b "JSESSIONID=<你的>" -H "Content-Type: application/json" `
  -d '{\"title\":\"HNSW 参数\",\"content\":\"m 控制每层最大连接数，ef_construction 控制建图时的候选集大小...\"}' `
  http://localhost:8123/api/knowledge-items
# 期望：201 + Location + {"knowledgeItemId":"...","preview":"m 控制每层最大连接数..."}

# 2) 列表
curl.exe -b "JSESSIONID=<你的>" http://localhost:8123/api/knowledge-items
# 期望：200，数组里能看到刚建的标题与摘要（摘要可能是 null）

# 3) 未登录
curl.exe -i http://localhost:8123/api/knowledge-items
# 期望：401 {"message":"未登录"}

# 4) 上传 .md（正文要 >100 字）
curl.exe -i -b "JSESSIONID=<你的>" -F "file=@test.md" `
  http://localhost:8123/api/knowledge-items/upload
# 期望：201；标题自动取 "test"；列表里 sourceUrl = test.md

# 5) 上传 .pdf（找一份文字版讲义，不要扫描件）
curl.exe -i -b "JSESSIONID=<你的>" -F "title=讲义第 1 章" -F "file=@讲义.pdf" `
  http://localhost:8123/api/knowledge-items/upload
# 期望：201 + preview 里能看到抽出来的中文（乱不乱一眼就知道）

# 6) 拒绝对照组（每条都应 400 + 文案）
# 6a 扫描件/空文件 -> {"message":"这个文件没有可提取的文字（PDF 可能是扫描件），请换成文字版或转成 .md"}
# 6b 传 .docx      -> {"message":"只支持 .md / .txt / .pdf"}
# 6c 传 6MB 的 pdf -> {"message":"文件太大，请控制在 5MB 以内"}
```

**四条断言，缺一条就不算验收**：

| 断言                               | 验的是什么                     |
| ---------------------------------- | ------------------------------ |
| `.md` 与 `.pdf` 都能 201           | 主路径通                       |
| `preview` 里的文字**是人类可读的** | PDF 抽取出的是正文，不是乱码   |
| 6a / 6b 返回 400 且文案对上        | 拒绝在入口处，不是落到下游 500 |
| 未登录 401                         | 身份校验没被上传接口漏掉       |

**交接给我**：把「创建成功」「上传 `.pdf` 成功」「6a 的 400」这三条响应的 **JSON 原文**发我（字段名以你实际返回为准），
我再写 `/materials` 页。

**实测结果（2026-10-22，后端 8123 实跑）**

| #   | 实测                                                                    | 结论 |
| --- | ----------------------------------------------------------------------- | ---- |
| 1   | `201` + `Location: /knowledge-items/2887a8b4-...` + `preview`/`summary` | 通过 |
| 2   | `200`，列表 3 条，`sourceUrl` 粘贴为 `null`、上传为文件名               | 通过 |
| 3   | `201`，`title` 自动取 `hnsw`，`sourceUrl = hnsw.md`                     | 通过 |
| 4   | `201`，`preview` 为完整英文正文（iText 现场生成的 781 字符 PDF）        | 通过 |
| 5   | `400 {"message":"只支持 .md / .txt / .pdf"}`                            | 通过 |
| 6   | `400 {"message":"文件太大，请控制在 5MB 以内"}`                         | 通过 |
| +   | 未登录 `GET` → `401 {"message":"未登录"}`                               | 通过 |

两条**不是 bug** 的坑，以后别再误判：

- `tmp/pdf` 下 4 份 PDF 抽出来只有 1 个换行符 —— `PDFGenerationTool` 生成的 PDF 缺 ToUnicode 映射，
  不是 `DocumentTextExtractor` 的问题。验收要用真实讲义，或现场用 iText 生成带拉丁字体的样本。
- 加密 PDF 会走到 `PDF 解析失败，请确认文件未加密且格式正常`。这条文案与「扫描件」文案是两条不同分支，
  、加密的归加密、抽不出字的归扫描件，不要合并。

**已知文案边界（有意不改）**：正文 <100 字时，`.md` 与 `.pdf` 共用「没有可提取的文字（PDF 可能是扫描件）」这条文案。
`DocumentTextExtractor` 不按扩展名分支，**由前端在提交前拦住并提示「至少 100 字」**。

***

### 3.8 增量改动：文档 → 章节（2026-10-23）

**读法**：3.1 ~ 3.7 是你已经写完的代码，**一个字都不改**。这一节只列要动的 8 处，
按「数据库 → 新文件 → 改文件 → 验收」的顺序做；每处都给「改成什么」，不给「整段重写」。
产品口径见 [product-spec.md 第 10 节](./product-spec.md)，两列的取舍见 [data-design.md](./data-design.md) 文末。

**改动总览（8 处，其中 2 处只新增不改旧代码）**

| #   | 位置                                    | 现状                                                            | 要变成                                                           | 工作量                           |
| --- | --------------------------------------- | --------------------------------------------------------------- | ---------------------------------------------------------------- | -------------------------------- |
| 1   | `t_knowledge_item`                      | 5 列，一行 = 一份资料                                           | 加 `doc_title` / `section_no`，一行 = 一个章节                   | 2 条 `ALTER` + 1 条回填 `UPDATE` |
| 2   | `service/DocumentSplitter.java`         | 不存在                                                          | 新增，纯函数，整篇 → 章节列表                                    | 新文件                           |
| 3   | `DocumentTextExtractor.extract`         | 三条拒绝（含 `MAX_CHARS = 50_000`）                             | 删掉 `MAX_CHARS` 那两处，长度交给切分器                          | 删 3 行                          |
| 4   | `KnowledgeItemRepository`               | `create` / `findAllByUserId` / `findById` / `findAnotherItemId` | 改名 1 个、改 SQL 2 个、改 1 个参数、新增 2 个方法               | 5 段片段                         |
| 5   | `KnowledgeService`                      | `create` 建 1 条、`list` 直接返回                               | 改名 `createDocument` 建 N 条、新增 `listDocuments` + `statusOf` | 3 段片段                         |
| 6   | `KnowledgeItemController`               | 返回 `{knowledgeItemId,preview,summary}`，3 个方法              | 返回 `{docTitle,sections[]}`，4 个方法（多一个 `GET /{id}`）     | 3 段片段                         |
| 7   | `ProgressRepository.countMasteredItems` | 统计该资料下所有题                                              | 只统计 `SELF_TEST` 题（阶段 4 的文件）                           | 1 段片段                         |
| 8   | `GlobalExceptionHandler`                | 已有 `MaxUploadSizeExceededException`                           | 再加 `MultipartException` → 400                                  | **你已在 2026-10-23 做完**，跳过 |

**改动 1：加两列 + 回填**

```sql
ALTER TABLE t_knowledge_item ADD COLUMN IF NOT EXISTS doc_title  VARCHAR(100);
ALTER TABLE t_knowledge_item ADD COLUMN IF NOT EXISTS section_no INT;

-- 历史 3 条资料回填成"单章文档"，否则资料页会出现 doc_title 为空的分组
UPDATE t_knowledge_item
   SET doc_title  = COALESCE(doc_title, title),
       section_no = COALESCE(section_no, 1)
 WHERE doc_title IS NULL OR section_no IS NULL;
```

不建 `t_document`，不加唯一约束，原因见 `data-design.md`。

**改动 2：新增 `service/DocumentSplitter.java`**

规则：每章 8000~12000 字（上限对齐 `QuizService.MATERIAL_LIMIT = 12000`）、
不足 3000 字的末段并入前一段、有 `#` / `##` 就按标题切、没有就按空行段落切、
代码块内不切、单份文档最多 30 章。**纯函数，先写单元测试再接 Controller。**

```java
package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.exception.BadRequestException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 整篇文档 -> 有序章节。纯函数：文本进、章节列表出，不碰数据库、不调模型。
 * 有 `#` / `##` 就按标题切；完全没有这类标题就按空行段落切。
 */
@Component
public class DocumentSplitter {

    /** 段内正文到这个量级就可以断开。 */
    static final int TARGET_MIN = 8_000;
    /** 单章硬上限，对齐 QuizService.MATERIAL_LIMIT。 */
    static final int SECTION_MAX = 12_000;
    /** 末尾残段小于这个值就并进前一段。 */
    static final int TAIL_MIN = 3_000;
    static final int MAX_SECTIONS = 30;

    private static final int TITLE_MAX = 100;
    private static final int TITLE_PROBE = 30;
    private static final int TITLE_PROBE_MIN = 10;
    private static final String SENTENCE_MARKS = "。！？；.!?;\n";

    public record Section(String title, String content) {
    }

    public List<Section> split(String docTitle, String text) {
        List<Section> packed = pack(toUnits(text));

        if (packed.size() > MAX_SECTIONS) {
            throw new BadRequestException("文档切分后超过 " + MAX_SECTIONS + " 章（当前 "
                    + packed.size() + " 章），请拆分后分次上传");
        }
        if (packed.size() == 1) {
            // 单章文档不编标题，直接用文档名
            return List.of(new Section(truncate(docTitle, TITLE_MAX), packed.get(0).content()));
        }

        List<Section> result = new ArrayList<>(packed.size());
        for (int i = 0; i < packed.size(); i++) {
            Section section = packed.get(i);
            String title = section.title() == null || section.title().isBlank()
                    ? fallbackTitle(section.content(), i + 1)
                    : section.title();
            result.add(new Section(truncate(title, TITLE_MAX), section.content()));
        }
        return result;
    }

    // ---------------- 第一步：原文 -> 小块 ----------------

    /** 中间态。标题可能为 null：段落模式下没有标题可用。 */
    private record Unit(String title, String content) {
    }

    private List<Unit> toUnits(String text) {
        List<String> lines = List.of(text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
        boolean byHeading = hasLevel12Heading(lines);

        List<Unit> units = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        String parentTitle = null;
        String title = null;
        boolean inFence = false;

        for (String line : lines) {
            String trimmed = line.trim();
            boolean fence = trimmed.startsWith("```");

            if (byHeading && !inFence && !fence && isLevel12Heading(trimmed)) {
                flush(units, title, body);
                String heading = cleanHeading(trimmed);
                if (headingLevel(trimmed) == 1) {
                    parentTitle = heading;
                    title = heading;
                } else {
                    // 二级标题带上父级标题，避免「第 2 节」在列表里重名
                    title = parentTitle == null ? heading : parentTitle + " · " + heading;
                }
            }
            body.append(line).append('\n');

            if (fence) {
                inFence = !inFence;
            } else if (!byHeading && !inFence && trimmed.isEmpty()) {
                // 段落模式：空行即边界。代码块里的空行不算
                flush(units, null, body);
            }
        }
        flush(units, title, body);
        return hardCut(units);
    }

    private boolean hasLevel12Heading(List<String> lines) {
        boolean inFence = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                inFence = !inFence;
            } else if (!inFence && isLevel12Heading(trimmed)) {
                return true;
            }
        }
        return false;
    }

    private int headingLevel(String line) {
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') {
            level++;
        }
        return level;
    }

    /** 只认 `# 标题` 与 `## 标题`；`###` 及更深留在正文里，不切段。 */
    private boolean isLevel12Heading(String line) {
        int level = headingLevel(line);
        if (level < 1 || level > 2 || level >= line.length()) {
            return false;
        }
        char sep = line.charAt(level);
        return sep == ' ' || sep == '\t';
    }

    private String cleanHeading(String line) {
        return line.substring(headingLevel(line)).strip();
    }

    private void flush(List<Unit> units, String title, StringBuilder body) {
        String content = body.toString().strip();
        body.setLength(0);
        if (!content.isEmpty()) {
            units.add(new Unit(title, content));
        }
    }

    /** 单个块超过 SECTION_MAX（超长代码块、表格、没有空行的长段落）时按句子边界硬切。 */
    private List<Unit> hardCut(List<Unit> units) {
        List<Unit> result = new ArrayList<>(units.size());
        for (Unit unit : units) {
            List<String> chunks = chunks(unit.content());
            for (int i = 0; i < chunks.size(); i++) {
                String title = unit.title();
                if (title != null && chunks.size() > 1) {
                    title = title + "（" + (i + 1) + "）";
                }
                result.add(new Unit(title, chunks.get(i)));
            }
        }
        return result;
    }

    private List<String> chunks(String content) {
        List<String> chunks = new ArrayList<>();
        String rest = content.strip();
        while (rest.length() > SECTION_MAX) {
            int cut = lastSentenceEnd(rest);
            if (cut <= 0) {
                cut = SECTION_MAX;   // 整段没有句末标点，只能按字符切
            }
            chunks.add(rest.substring(0, cut).strip());
            rest = rest.substring(cut).strip();
        }
        if (!rest.isEmpty()) {
            chunks.add(rest);
        }
        return chunks;
    }

    /** 从 SECTION_MAX 往前找最近的句末标点；找不到返回 -1。 */
    private int lastSentenceEnd(String text) {
        int floor = SECTION_MAX / 2;
        for (int i = SECTION_MAX - 1; i >= floor; i--) {
            if (SENTENCE_MARKS.indexOf(text.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        return -1;
    }

    // ---------------- 第二步：小块 -> 章节 ----------------

    private List<Section> pack(List<Unit> units) {
        List<Unit> merged = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        String title = null;

        for (Unit unit : units) {
            // 两条断开条件：装不下（硬上限）优先，其次才看"够不够长（TARGET_MIN）"
            boolean overflow = body.length() + unit.content().length() > SECTION_MAX;
            boolean longEnough = body.length() >= TARGET_MIN;

            if (body.length() > 0 && (overflow || longEnough)) {
                merged.add(new Unit(title, body.toString().strip()));
                body.setLength(0);
                title = null;
            }
            if (title == null) {
                title = unit.title();
            }
            if (body.length() > 0) {
                body.append("\n\n");
            }
            body.append(unit.content());
        }
        if (body.length() > 0) {
            merged.add(new Unit(title, body.toString().strip()));
        }

        // 末尾残段：放得下并进前一段，放不下就自己成一章（宁可短，不要超 12000）
        if (merged.size() > 1) {
            Unit last = merged.get(merged.size() - 1);
            Unit prev = merged.get(merged.size() - 2);
            if (last.content().length() < TAIL_MIN
                    && prev.content().length() + last.content().length() <= SECTION_MAX) {
                merged.remove(merged.size() - 1);
                merged.remove(merged.size() - 1);
                merged.add(new Unit(prev.title(), prev.content() + "\n\n" + last.content()));
            }
        }
        return merged.stream().map(u -> new Section(u.title(), u.content())).toList();
    }

    /** 没有标题的段落：取首行前 30 字；太短或为空时回退成「第 N 部分」。 */
    private String fallbackTitle(String content, int sectionNo) {
        String firstLine = content.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .orElse("")
                .replaceFirst("^#+\\s*", "");
        String probe = truncate(firstLine, TITLE_PROBE);
        return probe.length() < TITLE_PROBE_MIN ? "第 " + sectionNo + " 部分" : probe;
    }

    private String truncate(String text, int max) {
        String flat = text == null ? "" : text.strip();
        return flat.length() <= max ? flat : flat.substring(0, max);
    }
}
```

三个容易写错的地方：代码块里的 `#` 不是标题（`inFence` 必须在判标题之前生效）；
`MAX_SECTIONS` 要在 `pack` 之后判（按小块数量判没有意义）；`flush` 里先 `strip` 再判空（全空行不能产出空章节）。

单元测试五条（不依赖数据库与模型，先跑通这个再接 Controller）：

| 输入                                       | 断言                                                        |
| ------------------------------------------ | ----------------------------------------------------------- |
| 12 个 `##` 小节、每节 1200 字              | 切成 2 章左右，每章 ≤ 12000 字，标题取该章第一个小节标题    |
| 一段 30000 字、无空行、无标题              | 切成 3 章，每章 ≤ 12000 字，标题为「第 N 部分」             |
| 只有 1 个 `#` 标题、正文 100 字            | 1 章，标题 = 文档名                                         |
| 含 ```` ``` ```` 代码块，块内出现 `# 注释` | 代码块不被切开，`# 注释` 不产生新章节                       |
| 30 个 `#` 标题                             | 30 章；31 个标题 → 抛 `BadRequestException`，文案带实际章数 |

**改动 2 补充（2026-10-23 实测后必改）**：上面 `hardCut` 里 `chunks()` 的切法是「每次切满 12000」，
实测一份 31 节 × 12100 字的文档被切成 62 章，其中一半是 100 字左右的残段（验收记录 1-12）。
改成**按段数均分**，每个切块长度接近，就不会出现碎片章：

```java
    private List<String> chunks(String content) {
        List<String> chunks = new ArrayList<>();
        String rest = content.strip();
        while (rest.length() > SECTION_MAX) {
            // 先算这一节一共要分成几段，再按均分长度下刀，避免切出几十字的残段
            int pieces = (rest.length() + SECTION_MAX - 1) / SECTION_MAX;
            int target = rest.length() / pieces;
            int cut = lastSentenceEnd(rest, target);
            if (cut <= 0) {
                cut = target;   // 均分位置附近没有句末标点，只能按字符切
            }
            chunks.add(rest.substring(0, cut).strip());
            rest = rest.substring(cut).strip();
        }
        if (!rest.isEmpty()) {
            chunks.add(rest);
        }
        return chunks;
    }

    /** 从 target 往前找最近的句末标点；找不到返回 -1。 */
    private int lastSentenceEnd(String text, int target) {
        int floor = Math.max(1, target / 2);
        for (int i = Math.min(target, text.length()) - 1; i >= floor; i--) {
            if (SENTENCE_MARKS.indexOf(text.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        return -1;
    }
```

原来那个无参 `lastSentenceEnd(String text)` 删掉（被上面带 `target` 的版本取代）。
改完 31 × 12100 字仍然得到 62 章 —— **这是对的**：一节 12100 字超过单章上限，本来就需要两章，
文案报的章数就是真实章节数。要区别的是每章约 6050 字，而不是 12000 + 100。

**改动 3：`DocumentTextExtractor` 删掉长度限制**

只删这两处，其余不动：

```java
    private static final int MAX_CHARS = 50_000;     // ← 删掉这一行
```

```java
        if (text.length() > MAX_CHARS) {              // ← 删掉这 3 行
            throw new BadRequestException("文档太长（约 " + text.length() + " 字），请拆分后分次导入");
        }
```

同时把类注释里的「三条边界……太长」改成「两条边界：扩展名不支持、抽不出字。长度上限交给 `DocumentSplitter`」。

**改动 4：`KnowledgeItemRepository` 五段**

4a. `create` → `createSection`（多两个参数，列多两列）：

```java
    public UUID createSection(UUID userId, String docTitle, int sectionNo, String title,
                              String sourceUrl, String content, String summary) {
        return jdbcTemplate.queryForObject("""
                        insert into t_knowledge_item
                            (user_id, doc_title, section_no, title, source_url, content, summary)
                        values (?, ?, ?, ?, ?, ?, ?) returning id
                        """,
                UUID.class, userId, docTitle, sectionNo, title, sourceUrl, content, summary);
    }
```

4b. 新增同名检查：

```java
    /** 同名检查：同一用户下 doc_title 不能重复。历史行 doc_title 为空，用 title 兜底。 */
    public boolean existsDocTitle(UUID userId, String docTitle) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                        select exists(
                            select 1 from t_knowledge_item
                             where user_id = ? and coalesce(doc_title, title) = ?)
                        """,
                Boolean.class, userId, docTitle));
    }
```

4c. `findAllByUserId` 换成带计数的查询（原来只查元数据，现在要算三态，**必须在这里一次算完**，
否则 Service 里要 N+1 次查询）：

```java
    /** 资料页列表的一行：章节元数据 + 出题/作答计数，三态由 Service 推导。 */
    public record SectionRow(UUID id, String docTitle, int sectionNo, String title, String sourceUrl,
                             String summary, int chars, int quizCount, int answeredCount,
                             boolean allPassed, OffsetDateTime createdAt) {
    }

    /**
     * 列表不取 content。quizCount / answeredCount / allPassed 只统计 SELF_TEST 会话产生的题，
     * REVIEW 会话产生的题不参与章节三态（product-spec.md 10.5）。
     */
    public List<SectionRow> findAllByUserId(UUID userId) {
        return jdbcTemplate.query("""
                        with quiz as (
                            select q.id as question_id, q.knowledge_item_id
                              from t_question q
                              join t_message m      on m.id = q.message_id
                              join t_conversation c on c.id = m.conversation_id
                             where c.mode = 'SELF_TEST'
                        ),
                        latest as (
                            select distinct on (tr.question_id) tr.question_id, tr.verdict
                              from t_test_record tr
                             order by tr.question_id, tr.id desc
                        )
                        select ki.id,
                               coalesce(ki.doc_title, ki.title) as doc_title,
                               coalesce(ki.section_no, 1)       as section_no,
                               ki.title, ki.source_url, ki.summary, ki.created_at,
                               length(ki.content)    as chars,
                               count(qz.question_id) as quiz_count,
                               count(l.question_id)  as answered_count,
                               coalesce(bool_and(l.verdict = 'PASS'), false) as all_passed
                          from t_knowledge_item ki
                          left join quiz qz  on qz.knowledge_item_id = ki.id
                          left join latest l on l.question_id = qz.question_id
                         where ki.user_id = ?
                         group by ki.id
                         order by max(ki.created_at) over (partition by coalesce(ki.doc_title, ki.title)) desc,
                                  doc_title, section_no
                        """,
                (rs, rowNum) -> new SectionRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("doc_title"),
                        rs.getInt("section_no"),
                        rs.getString("title"),
                        rs.getString("source_url"),
                        rs.getString("summary"),
                        rs.getInt("chars"),
                        rs.getInt("quiz_count"),
                        rs.getInt("answered_count"),
                        rs.getBoolean("all_passed"),
                        rs.getObject("created_at", OffsetDateTime.class)),
                userId);
    }
```

排序先按「文档最近创建时间」倒序（新传的文档排最前），文档内按 `section_no` 升序 —— 章节必须按阅读顺序排，
不能按创建时间。

4d. `findById` 多查两列（`coalesce` 兜历史行，避免 `getInt` 拿到 `null` 变成 0）：

```java
        List<KnowledgeItemRow> rows = jdbcTemplate.query("""
                        select id,
                               coalesce(doc_title, title) as doc_title,
                               coalesce(section_no, 1)    as section_no,
                               title, source_url, content, summary, created_at
                          from t_knowledge_item
                         where id = ?
                        """,
                (rs, rowNum) -> new KnowledgeItemRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("doc_title"),
                        rs.getInt("section_no"),
                        rs.getString("title"),
                        rs.getString("source_url"),
                        rs.getString("content"),
                        rs.getString("summary"),
                        rs.getObject("created_at", OffsetDateTime.class)),
                id);
```

4e. `findAnotherItemId` 改成「排除同一份文档」（原来是排除同一条资料，切章后同文档的兄弟章节会被当成交错题）：

```java
    // 交错复习用：从"另一份文档"的章节里随机取一条；只有一份文档时返回空
    public Optional<UUID> findAnotherItemId(UUID userId, UUID excludeItemId) {
        List<UUID> rows = jdbcTemplate.query("""
                        select id from t_knowledge_item
                         where user_id = ?
                           and coalesce(doc_title, title) <> (
                               select coalesce(doc_title, title)
                                 from t_knowledge_item
                                where id = ?)
                         order by random()
                         limit 1
                        """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                userId, excludeItemId);
        return rows.stream().findFirst();
    }
```

参数签名不变（仍是 `excludeItemId`），**调用方 `QuizService` 不用改**。

**改动 5：`KnowledgeService` 三段**

5a. `CreatedItem create` → 两个 record + `createDocument`：

```java
    public record CreatedSection(UUID id, int sectionNo, String title, int chars, String summary) {
    }

    public record CreatedDocument(String docTitle, List<CreatedSection> sections) {
    }

    /**
     * 粘贴与上传共用：一份文档 -> N 个章节。
     * 同名文档抛 400；切分超过 30 章也抛 400；某章摘要失败只把该章 summary 置空，不阻断落库。
     */
    public CreatedDocument createDocument(UUID userId, String docTitle, String sourceUrl, String content) {
        if (knowledgeItemRepository.existsDocTitle(userId, docTitle)) {
            throw new BadRequestException("已存在同名文档，请改名后重新上传");
        }

        List<DocumentSplitter.Section> sections = documentSplitter.split(docTitle, content);
        List<CreatedSection> created = new ArrayList<>(sections.size());

        for (int i = 0; i < sections.size(); i++) {
            DocumentSplitter.Section section = sections.get(i);
            int sectionNo = i + 1;
            // 每章一次模型调用。失败只让这一章没有摘要，不影响其他章节
            String summary = summarizeQuietly(section.content());
            UUID id = knowledgeItemRepository.createSection(
                    userId, docTitle, sectionNo, section.title(), sourceUrl, section.content(), summary);
            indexQuietly(id, userId, section.title(), section.content());
            created.add(new CreatedSection(id, sectionNo, section.title(), section.content().length(), summary));
        }
        return new CreatedDocument(docTitle, created);
    }
```

5b. `list` → `listDocuments` + `statusOf`：

```java
    public record SectionView(UUID id, int sectionNo, String title, int chars,
                              String summary, String status) {
    }

    public record DocumentGroup(String docTitle, int sectionCount, int passedCount,
                                List<SectionView> sections) {
    }

    /**
     * 资料页：按文档分组只在这里做一次。进度页与交错复习复用同一套分组口径，
     * 前端不再按 docTitle 二次分组。
     */
    public List<DocumentGroup> listDocuments(UUID userId) {
        Map<String, List<KnowledgeItemRepository.SectionRow>> grouped = new LinkedHashMap<>();
        for (KnowledgeItemRepository.SectionRow row : knowledgeItemRepository.findAllByUserId(userId)) {
            grouped.computeIfAbsent(row.docTitle(), key -> new ArrayList<>()).add(row);
        }

        List<DocumentGroup> result = new ArrayList<>(grouped.size());
        grouped.forEach((docTitle, rows) -> {
            List<SectionView> sections = rows.stream()
                    .map(row -> new SectionView(row.id(), row.sectionNo(), row.title(),
                            row.chars(), row.summary(), statusOf(row)))
                    .toList();
            int passed = (int) sections.stream().filter(s -> "PASSED".equals(s.status())).count();
            result.add(new DocumentGroup(docTitle, sections.size(), passed, sections));
        });
        return result;
    }

    /** 三态判定只写这一处：未开始 / 进行中 / 已通过。规则见 product-spec.md 10.5。 */
    private String statusOf(KnowledgeItemRepository.SectionRow row) {
        if (row.quizCount() == 0) {
            return "NOT_STARTED";
        }
        return row.answeredCount() == row.quizCount() && row.allPassed() ? "PASSED" : "IN_PROGRESS";
    }
```

5c. 依赖与 import：字段加 `private final DocumentSplitter documentSplitter;`；
import 加 `BadRequestException`、`ArrayList`、`LinkedHashMap`、`Map`；`KnowledgeItemRow` 保留（`requireOwned` 还在用）。

`summarizeQuietly` 的提示词从「概括这份学习材料」改成「概括这一章讲什么」，其余（失败返回 `null`、截 300 字）不动。

**改动 6：`KnowledgeItemController` 三段**

6a. 三个 record 换成四个（`CreateRequest` 只改 `content` 的 `@Size` 上限）：

```java
    public record CreateRequest(
            @NotBlank(message = "title 不能为空")
            @Size(max = 100, message = "文档名过长") String title,
            @NotBlank(message = "content 不能为空")
            @Size(max = 200000, message = "正文最多 20 万字") String content) {
    }

    public record SectionView(UUID knowledgeItemId, int sectionNo, String title, int chars, String summary) {
    }

    public record CreateResponse(String docTitle, List<SectionView> sections) {
    }

    public record SectionItem(UUID knowledgeItemId, int sectionNo, String title, int chars,
                              String summary, String status) {
    }

    public record DocumentView(String docTitle, int sectionCount, int passedCount, List<SectionItem> sections) {
    }

    public record SectionDetail(UUID knowledgeItemId, String docTitle, int sectionNo, String title,
                                int chars, String content) {
    }
```

6b. `create` / `upload` 的返回体（`Location` 指向**第一章**，一份文档只给一个 `Location`）：

```java
    @PostMapping
    public ResponseEntity<CreateResponse> create(@Valid @RequestBody CreateRequest req,
                                                 HttpServletRequest request) {
        UUID userId = currentUserResolver.requireUserId(request);
        KnowledgeService.CreatedDocument created =
                knowledgeService.createDocument(userId, req.title(), null, req.content());
        return ResponseEntity.created(URI.create("/knowledge-items/" + created.sections().get(0).id()))
                .body(toResponse(created));
    }
```

`upload` 同样只换两行：`knowledgeService.create(...)` → `createDocument(...)`；返回体 → `toResponse(created)`。
`documentTextExtractor.extract(...)` 那行不动。

6c. `list` 换返回类型，并新增正文接口：

```java
    @GetMapping
    public List<DocumentView> list(HttpServletRequest request) {
        UUID userId = currentUserResolver.requireUserId(request);
        return knowledgeService.listDocuments(userId).stream()
                .map(doc -> new DocumentView(doc.docTitle(), doc.sectionCount(), doc.passedCount(),
                        doc.sections().stream()
                                .map(s -> new SectionItem(s.id(), s.sectionNo(), s.title(),
                                        s.chars(), s.summary(), s.status()))
                                .toList()))
                .toList();
    }

    /** 章节正文。阅读页与"直接检测"之前都走这里。 */
    @GetMapping("/{knowledgeItemId}")
    public SectionDetail detail(@PathVariable UUID knowledgeItemId, HttpServletRequest request) {
        UUID userId = currentUserResolver.requireUserId(request);
        KnowledgeItemRow row = knowledgeService.requireOwned(knowledgeItemId, userId);
        return new SectionDetail(row.id(), row.docTitle(), row.sectionNo(), row.title(),
                row.content().length(), row.content());
    }

    private static CreateResponse toResponse(KnowledgeService.CreatedDocument created) {
        return new CreateResponse(created.docTitle(), created.sections().stream()
                .map(s -> new SectionView(s.id(), s.sectionNo(), s.title(), s.chars(), s.summary()))
                .toList());
    }
```

同时删掉 `PREVIEW_CHARS` 常量与 `preview(...)` 方法，`defaultTitle` 里的兜底文案「未命名资料」改成「未命名文档」。
import 加 `com.lcz.aiagentstudy.model.KnowledgeItemRow`，删 `java.time.OffsetDateTime`。

`/{knowledgeItemId}` 传非 UUID 时由已有的 `MethodArgumentTypeMismatchException` 处理器返回
`400 {"message":"knowledgeItemId 不合法"}`，与 `/conversations/{id}/messages` 同一口径，不用额外写。

**改动 7：`ProgressRepository.countMasteredItems` 换口径**（阶段 4 的文件）

```java
    /**
     * 已掌握章节 = 该章节由主动测验（SELF_TEST）出的题"全部答过且最近一次都是 PASS"。
     * 两道口径都不能省：
     * - 只要有一道没答，就不是已掌握，否则资料页说「已通过」、待办说「还没测过」，两边打架；
     * - REVIEW 会话复制出的题不参与，否则到期复习抽中已通过的章节，进度会周期性下降。
     */
    public int countMasteredItems(UUID userId) {
        Integer n = jdbcTemplate.queryForObject("""
                        with latest as (
                            select distinct on (tr.question_id) tr.question_id, tr.verdict
                              from t_test_record tr
                             where tr.user_id = ?
                             order by tr.question_id, tr.id desc
                        ),
                        quiz as (
                            select q.id as question_id, q.knowledge_item_id
                              from t_question q
                              join t_message m      on m.id = q.message_id
                              join t_conversation c on c.id = m.conversation_id
                             where c.mode = 'SELF_TEST'
                        ),
                        per_item as (
                            select qz.knowledge_item_id,
                                   count(*) as total,
                                   count(*) filter (where l.verdict = 'PASS') as passed
                              from quiz qz
                              join t_knowledge_item ki on ki.id = qz.knowledge_item_id
                              left join latest l on l.question_id = qz.question_id
                             where ki.user_id = ?
                             group by qz.knowledge_item_id
                        )
                        select count(*) from per_item where total > 0 and total = passed
                        """,
                Integer.class, userId, userId);
        return n == null ? 0 : n;
    }
```

**改动 8：验收**

新建 10 条用例已写进 [acceptance-log.md 阶段 1](./acceptance-log.md) 的 **1-10 ~ 1-19**，照着跑。
跑之前必须先执行改动 1 的回填 `UPDATE`，否则 1-15 会看到 `doc_title` 为空的分组。

**改完的自检顺序**（错了按这个顺序排查，不要跳）：

```
1. DocumentSplitterTest 5 条全绿            → 切分逻辑对不对
2. 粘贴一份带 ## 的文档，看 201 响应的 sections  → 切分器接进 Controller 了没
3. GET /knowledge-items 看分组与 status     → 计数 SQL 对不对
4. GET /knowledge-items/{id}                 → 正文接口通不通
5. 同名再传 → 400                            → 同名检查生效
6. 跑一条 /quiz/generate（阶段 2 的代码没改） → 出题链路没被切章改动打断
```

第 6 步是关键：`QuizService`、`GradingService`、`AttemptStore`、`TodoService`、`ConversationService`
**一行都不用改**，它们的回归就靠这一步一次确认。

**本节之外还有 4 处仍按旧形态写**（技术债第 5 / 11 / 12 条已于 2026-10-23 随验收一并改掉）：

| 位置                     | 旧写法                                         | 何时改            |
| ------------------------ | ---------------------------------------------- | ----------------- |
| 11 前端清单 ①            | `MaterialsView` 弹 `preview`、卡片上「考考我」 | 写前端前          |
| 13.3 接口总表阶段 1 四行 | 少一个 `GET /knowledge-items/{id}`             | 阶段 1 验收通过后 |
| 13.4 手工清理 SQL        | 按 `<资料id>` 删                               | 需要清库时        |
| 10 阶段 7 第 1 步        | 「`preview` 可读」                             | 跑闭环前          |

***

## 4. 阶段 2：出题（`FACT` / `EXPLAIN` + 知识点）

> 对应 [product-spec.md 第 5、8 节](./product-spec.md)。

### 4.1 契约

| 方法 | 路径             | 请求                           | 成功                                                                                                                    |
| ---- | ---------------- | ------------------------------ | ----------------------------------------------------------------------------------------------------------------------- |
| POST | `/quiz/generate` | `{"knowledgeItemId":"<uuid>"}` | `200 {"conversationId":"<uuid>","questions":[{"questionId":123,"kind":"FACT","target":"HNSW 的 m 参数","text":"..."}]}` |

规则：

1. 默认出 **3 个任务**，其中**至少 1 个 `EXPLAIN`**；每个任务必须带 `kind` / `target` / `text`。
2. 如果这份资料下有「上次 `FAIL` / `PARTIAL` 且还没补讲」的题，**第 1 个任务重问那道题**，
   模型只补 2 个新任务（总数仍是 3）。
3. 新任务的 `target` 会写入 `t_knowledge_point`（`CANDIDATE`），同一用户同名知识点只留一行。
4. 出题时注入该用户最近 5 个 `VERIFIED` 知识点，要求模型用已掌握概念做类比。
5. 别人的资料 → `403`，与「不存在」同一句文案。

### 4.2 `MessageRepository` 追加一个方法

```java
    // 插一条并返回自增 id：出题要把"消息 id"回填到 t_question.message_id
    public long insertReturningId(UUID conversationId, String role, String type, String content) {
        return jdbcTemplate.queryForObject(
                "insert into t_message (conversation_id, role, type, content) values (?, ?, ?, ?) returning id",
                Long.class, conversationId, role, type, content);
    }
```

### 4.3 `repository/KnowledgePointRepository.java`

```java
package com.lcz.aiagentstudy.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class KnowledgePointRepository {
    private final JdbcTemplate jdbcTemplate;

    /**
     * 出题时把 target 写成知识点。冲突时只把 name 赋回自身，
     * 目的是"拿到已有 id"且**不把 VERIFIED 降级回 CANDIDATE**（DO NOTHING 不会返回 id）。
     */
    public UUID upsertCandidate(UUID userId, String name) {
        return jdbcTemplate.queryForObject("""
                        insert into t_knowledge_point (user_id, name, status)
                        values (?, ?, 'CANDIDATE')
                        on conflict (user_id, name) do update set name = excluded.name
                        returning id
                        """,
                UUID.class, userId, name);
    }

    // 判分 PASS / PARTIAL 时调用；已经是 VERIFIED 就不再动
    public void markVerified(UUID knowledgePointId) {
        jdbcTemplate.update(
                "update t_knowledge_point set status = 'VERIFIED' where id = ? and status = 'CANDIDATE'",
                knowledgePointId);
    }

    // 出题时注入"已掌握的概念"，让模型要求用户做类比
    public List<String> findRecentVerified(UUID userId, int limit) {
        return jdbcTemplate.query("""
                        select name from t_knowledge_point
                         where user_id = ? and status = 'VERIFIED'
                         order by created_at desc
                         limit ?
                        """,
                (rs, rowNum) -> rs.getString("name"),
                userId, limit);
    }
}
```

### 4.4 `repository/QuestionRepository.java`

```java
package com.lcz.aiagentstudy.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class QuestionRepository {
    private final JdbcTemplate jdbcTemplate;

    /** 待重问的旧题 + 它自带的归属信息。 */
    public record RetryQuestion(long questionId, UUID knowledgeItemId, UUID knowledgePointId,
                                String kind, String target, String text) {
    }

    /** 判分时反查：题目 + 会话 + 会话所属用户 + 资料/知识点 + 材料正文。一条查询拿到判分所需的一切。 */
    public record QuestionContext(long questionId, String text, String kind, String target,
                                  UUID conversationId, UUID userId, UUID knowledgeItemId,
                                  UUID knowledgePointId, String material) {
    }

    public long insert(long messageId, UUID knowledgeItemId, UUID knowledgePointId,
                       String kind, String target) {
        return jdbcTemplate.queryForObject("""
                        insert into t_question (message_id, knowledge_item_id, knowledge_point_id, kind, target)
                        values (?, ?, ?, ?, ?) returning id
                        """,
                Long.class, messageId, knowledgeItemId, knowledgePointId, kind, target);
    }

    /** 用户点了「我卡住了」。之后复习时这题排前面。 */
    public void markHelpUsed(long questionId) {
        jdbcTemplate.update("update t_question set help_used = true where id = ?", questionId);
    }

    public List<QuestionContext> findWithOwner(long questionId) {
        return jdbcTemplate.query("""
                        select q.id            as question_id,
                               m.content       as question_text,
                               q.kind, q.target,
                               m.conversation_id,
                               c.user_id,
                               q.knowledge_item_id,
                               q.knowledge_point_id,
                               ki.content      as material
                          from t_question q
                          join t_message m       on m.id = q.message_id
                          join t_conversation c  on c.id = m.conversation_id
                          join t_knowledge_item ki on ki.id = q.knowledge_item_id
                         where q.id = ?
                        """,
                (rs, rowNum) -> new QuestionContext(
                        rs.getLong("question_id"),
                        rs.getString("question_text"),
                        rs.getString("kind"),
                        rs.getString("target"),
                        rs.getObject("conversation_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("knowledge_item_id", UUID.class),
                        rs.getObject("knowledge_point_id", UUID.class),
                        rs.getString("material")),
                questionId);
    }

    /**
     * 「上次答得不完整、还没补讲、复习也还没到期」的那道题。
     * 条件与待办里的 RETRY 完全一致，避免两处口径漂移。
     */
    public Optional<RetryQuestion> findRetryQuestion(UUID userId, UUID knowledgeItemId) {
        List<RetryQuestion> rows = jdbcTemplate.query("""
                        with latest as (
                            select distinct on (tr.question_id)
                                   tr.question_id, tr.verdict, tr.attempt_no
                              from t_test_record tr
                              join t_question q on q.id = tr.question_id
                             where tr.user_id = ? and q.knowledge_item_id = ?
                             order by tr.question_id, tr.id desc
                        )
                        select q.id, q.knowledge_item_id, q.knowledge_point_id,
                               q.kind, q.target, m.content as text
                          from latest l
                          join t_question q       on q.id = l.question_id
                          join t_message m        on m.id = q.message_id
                          join t_review_schedule rs on rs.question_id = q.id
                         where l.verdict <> 'PASS' and l.attempt_no = 1
                           and rs.status = 'PENDING' and rs.due_at > now()
                         order by q.id desc
                         limit 1
                        """,
                (rs, rowNum) -> new RetryQuestion(
                        rs.getLong("id"),
                        rs.getObject("knowledge_item_id", UUID.class),
                        rs.getObject("knowledge_point_id", UUID.class),
                        rs.getString("kind"),
                        rs.getString("target"),
                        rs.getString("text")),
                userId, knowledgeItemId);
        return rows.stream().findFirst();
    }
}
```

### 4.5 `repository/ReviewScheduleRepository.java`（一次写完，阶段 3/4 只调用不再改）

```java
package com.lcz.aiagentstudy.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ReviewScheduleRepository {
    private final JdbcTemplate jdbcTemplate;

    public record ScheduleRow(UUID id, long questionId, int stage, String status) {
    }

    public record DueItem(long questionId, UUID knowledgeItemId, String title,
                          String kind, String target, UUID knowledgePointId, String text, int stage) {
    }

    public Optional<ScheduleRow> findByQuestion(long questionId) {
        return jdbcTemplate.query("""
                        select id, question_id, stage, status
                          from t_review_schedule
                         where question_id = ?
                        """,
                (rs, rowNum) -> new ScheduleRow(
                        rs.getObject("id", UUID.class),
                        rs.getLong("question_id"),
                        rs.getInt("stage"),
                        rs.getString("status")),
                questionId).stream().findFirst();
    }

    /**
     * 重问 / 复习：排期行跟着题走，**保持 PENDING、due_at 不动**。
     * 为什么不在启动复习时就标 DONE：DONE 会把 stage 一起作废，
     * 用户答对后就没法从 1 → 3 递进了。保持 PENDING 还有个好处：
     * 用户中途放弃，这题仍然算"到期未复习"，待办不会假装已经复习过。
     */
    public void moveQuestion(long fromQuestionId, long toQuestionId) {
        jdbcTemplate.update(
                "update t_review_schedule set question_id = ? where question_id = ?",
                toQuestionId, fromQuestionId);
    }

    /**
     * 一题一行排期，重复作答原地改期（ON CONFLICT (question_id)）。
     * 不用"先查再插"：并发下两个请求会同时查到"没有"，然后一条撞唯一约束。
     */
    public OffsetDateTime upsert(UUID userId, long questionId, int stage, int days) {
        return jdbcTemplate.queryForObject("""
                        insert into t_review_schedule (user_id, question_id, stage, due_at)
                        values (?, ?, ?, now() + make_interval(days => ?))
                        on conflict (question_id) do update
                           set stage = excluded.stage,
                               due_at = excluded.due_at,
                               status = 'PENDING',
                               done_at = null
                        returning due_at
                        """,
                OffsetDateTime.class, userId, questionId, stage, days);
    }

    /** 1 -> 3 -> 7 走完后再 PASS：不再排期。 */
    public void markDoneByQuestion(long questionId) {
        jdbcTemplate.update("""
                        update t_review_schedule
                           set status = 'DONE', done_at = now()
                         where question_id = ? and status = 'PENDING'
                        """,
                questionId);
    }

    public List<DueItem> findDue(UUID userId, int limit) {
        return jdbcTemplate.query("""
                        select rs.question_id, rs.stage,
                               q.knowledge_item_id, q.knowledge_point_id, q.kind, q.target,
                               ki.title, m.content as text
                          from t_review_schedule rs
                          join t_question q      on q.id = rs.question_id
                          join t_message m       on m.id = q.message_id
                          join t_knowledge_item ki on ki.id = q.knowledge_item_id
                         where rs.user_id = ? and rs.status = 'PENDING' and rs.due_at <= now()
                         order by q.help_used desc, rs.due_at
                         limit ?
                        """,
                (rs, rowNum) -> new DueItem(
                        rs.getLong("question_id"),
                        rs.getObject("knowledge_item_id", UUID.class),
                        rs.getString("title"),
                        rs.getString("kind"),
                        rs.getString("target"),
                        rs.getObject("knowledge_point_id", UUID.class),
                        rs.getString("text"),
                        rs.getInt("stage")),
                userId, limit);
    }

    public int countDue(UUID userId) {
        Integer n = jdbcTemplate.queryForObject("""
                        select count(*) from t_review_schedule
                         where user_id = ? and status = 'PENDING' and due_at <= now()
                        """,
                Integer.class, userId);
        return n == null ? 0 : n;
    }
}
```

### 4.6 `service/QuizStore.java`（所有写操作集中在这里）

```java
package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.model.ConversationMode;
import com.lcz.aiagentstudy.model.KnowledgeItemRow;
import com.lcz.aiagentstudy.model.MessageRole;
import com.lcz.aiagentstudy.model.MessageType;
import com.lcz.aiagentstudy.repository.ConversationRepository;
import com.lcz.aiagentstudy.repository.KnowledgePointRepository;
import com.lcz.aiagentstudy.repository.MessageRepository;
import com.lcz.aiagentstudy.repository.QuestionRepository;
import com.lcz.aiagentstudy.repository.ReviewScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 出题 / 复习的写入口。单独一个 bean：出题要先调模型（慢、会失败），再写库；
 * 把 @Transactional 和调用方放同一个类里，自调用绕过代理，注解会静默失效。
 */
@Service
@RequiredArgsConstructor
public class QuizStore {
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final QuestionRepository questionRepository;
    private final KnowledgePointRepository knowledgePointRepository;
    private final ReviewScheduleRepository reviewScheduleRepository;

    public record CreatedQuestion(long id, String kind, String target, String text) {
    }

    public record CreatedQuiz(UUID conversationId, List<CreatedQuestion> questions) {
    }

    @Transactional
    public CreatedQuiz createSelfTest(UUID userId, KnowledgeItemRow item,
                                      QuestionRepository.RetryQuestion retry,
                                      List<QuizService.TaskDraft> newTasks) {
        UUID conversationId = conversationRepository.create(userId, ConversationMode.SELF_TEST.name());
        // 一条 USER 消息：让左侧会话列表有标题可派生
        messageRepository.insert(conversationId, MessageRole.USER.name(), MessageType.CHAT.name(),
                "请考考我：" + item.title());

        List<CreatedQuestion> created = new ArrayList<>();
        if (retry != null) {
            created.add(appendExistingQuestion(conversationId, retry));
        }
        for (QuizService.TaskDraft task : newTasks) {
            created.add(appendNewQuestion(conversationId, userId, item.id(), task));
        }
        conversationRepository.touch(conversationId);
        return new CreatedQuiz(conversationId, created);
    }

    @Transactional
    public CreatedQuiz createReview(UUID userId, List<ReviewScheduleRepository.DueItem> due,
                                    UUID interleavedItemId, QuizService.TaskDraft interleaved) {
        UUID conversationId = conversationRepository.create(userId, ConversationMode.REVIEW.name());
        messageRepository.insert(conversationId, MessageRole.USER.name(), MessageType.CHAT.name(), "开始复习");

        List<CreatedQuestion> created = new ArrayList<>();
        for (ReviewScheduleRepository.DueItem item : due) {
            // 原题重问：复制成新消息 + 新题，排期行迁到新题（stage 保留，答完才重排）
            long messageId = messageRepository.insertReturningId(conversationId,
                    MessageRole.ASSISTANT.name(), MessageType.QUESTION.name(), item.text());
            long questionId = questionRepository.insert(messageId, item.knowledgeItemId(),
                    item.knowledgePointId(), item.kind(), item.target());
            reviewScheduleRepository.moveQuestion(item.questionId(), questionId);
            created.add(new CreatedQuestion(questionId, item.kind(), item.target(), item.text()));
        }
        if (interleaved != null) {
            created.add(appendNewQuestion(conversationId, userId, interleavedItemId, interleaved));
        }
        conversationRepository.touch(conversationId);
        return new CreatedQuiz(conversationId, created);
    }

    private CreatedQuestion appendExistingQuestion(UUID conversationId,
                                                   QuestionRepository.RetryQuestion retry) {
        long messageId = messageRepository.insertReturningId(conversationId,
                MessageRole.ASSISTANT.name(), MessageType.QUESTION.name(), retry.text());
        long questionId = questionRepository.insert(messageId, retry.knowledgeItemId(),
                retry.knowledgePointId(), retry.kind(), retry.target());
        // 排期跟着题走：旧题的 PENDING 排期迁到新题，到期时间不变
        reviewScheduleRepository.moveQuestion(retry.questionId(), questionId);
        return new CreatedQuestion(questionId, retry.kind(), retry.target(), retry.text());
    }

    private CreatedQuestion appendNewQuestion(UUID conversationId, UUID userId,
                                              UUID knowledgeItemId, QuizService.TaskDraft task) {
        UUID knowledgePointId = knowledgePointRepository.upsertCandidate(userId, task.target());
        long messageId = messageRepository.insertReturningId(conversationId,
                MessageRole.ASSISTANT.name(), MessageType.QUESTION.name(), task.text());
        long questionId = questionRepository.insert(messageId, knowledgeItemId,
                knowledgePointId, task.kind(), task.target());
        return new CreatedQuestion(questionId, task.kind(), task.target(), task.text());
    }
}
```

### 4.7 `service/QuizService.java`

```java
package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.model.KnowledgeItemRow;
import com.lcz.aiagentstudy.repository.KnowledgePointRepository;
import com.lcz.aiagentstudy.repository.QuestionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class QuizService {

    /** 评测集（test 包）也要用同一段提示词，所以不给 private。 */
    static final String SYSTEM_PROMPT = """
            你是出题官，只出题、不答题。
            规则：
            1. 必须严格基于用户给出的材料出题，不得引入材料以外的知识点。
            2. 只输出任务，绝不包含答案、提示、解释。
            3. 任务分两类：
               - FACT：一个明确的回忆问题（是什么 / 为什么 / 怎么做），不要选择题。
               - EXPLAIN：讲解任务。必须给听众设定（例如"只会 Python、没听过向量检索的人"），
                 要求用户用自己的话讲清楚因果，而不是背结论。
            4. 每个任务给三个字段：
               - kind：FACT 或 EXPLAIN（大写）
               - target：这道题考的知识点，一个名词短语（例如"HNSW 的 m 参数"），不超过 40 字
               - text：题面，一句话，20 到 80 字
            5. 每个任务的 target 不能重复。
            """;

    private final ChatModel deepSeekChatModel;
    private final KnowledgeService knowledgeService;
    private final KnowledgePointRepository knowledgePointRepository;
    private final QuestionRepository questionRepository;
    private final QuizStore quizStore;

    // 出题只看材料前 12000 字：再长会顶到模型上下文上限，
    // 且「一次出 3 题」本来就覆盖不了整份讲义（写进技术债第 7 条）
    private static final int MATERIAL_LIMIT = 12_000;

    public record TaskDraft(String kind, String target, String text) {
    }

    public record QuizDraft(List<TaskDraft> tasks) {
    }

    public QuizStore.CreatedQuiz generate(UUID userId, UUID knowledgeItemId) {
        KnowledgeItemRow item = knowledgeService.requireOwned(knowledgeItemId, userId);

        Optional<QuestionRepository.RetryQuestion> retry =
                questionRepository.findRetryQuestion(userId, knowledgeItemId);

        // 有旧题就重问 1 道 + 新出 2 道；没有就新出 3 道
        int newCount = retry.isPresent() ? 2 : 3;
        boolean requireExplain = retry.map(r -> !"EXPLAIN".equals(r.kind())).orElse(true);

        List<String> verified = knowledgePointRepository.findRecentVerified(userId, 5);
        List<TaskDraft> tasks = draft(item.content(), newCount, requireExplain, verified);

        return quizStore.createSelfTest(userId, item, retry.orElse(null), tasks);
    }

    /** 复习的交错题：只出 1 个。出不来就返回 empty，不阻断这次复习。 */
    public Optional<TaskDraft> draftOne(KnowledgeItemRow item, List<String> verified) {
        try {
            return draft(item.content(), 1, false, verified).stream().findFirst();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private List<TaskDraft> draft(String content, int count, boolean requireExplain,
                                  List<String> verified) {
        ChatClient client = ChatClient.builder(deepSeekChatModel).defaultSystem(SYSTEM_PROMPT).build();
        QuizDraft draft = client.prompt()
                .user(buildUserMessage(truncate(content), count, requireExplain, verified))
                .call()
                .entity(QuizDraft.class);

        if (draft == null || draft.tasks() == null || draft.tasks().size() != count) {
            throw new IllegalStateException("模型未按要求返回 " + count + " 个任务，请重试");
        }

        List<TaskDraft> normalized = new ArrayList<>(count);
        for (TaskDraft t : draft.tasks()) {
            if (t == null || t.kind() == null || t.target() == null || t.text() == null
                    || t.target().isBlank() || t.text().isBlank()) {
                throw new IllegalStateException("模型返回的任务缺少 kind/target/text，请重试");
            }
            String kind = t.kind().trim().toUpperCase(Locale.ROOT);
            if (!"FACT".equals(kind) && !"EXPLAIN".equals(kind)) {
                throw new IllegalStateException("模型返回了未知任务类型: " + t.kind());
            }
            normalized.add(new TaskDraft(kind, t.target().trim(), t.text().trim()));
        }

        if (requireExplain && normalized.stream().noneMatch(t -> "EXPLAIN".equals(t.kind()))) {
            throw new IllegalStateException("模型没有给出 EXPLAIN 任务，请重试");
        }
        return normalized;
    }

    private String buildUserMessage(String content, int count, boolean requireExplain,
                                    List<String> verified) {
        StringBuilder sb = new StringBuilder();
        sb.append("材料如下：\n\n").append(content).append("\n\n");
        sb.append("请出 ").append(count).append(" 个任务。");
        sb.append(requireExplain ? "其中至少 1 个是 EXPLAIN。" : "类型 FACT / EXPLAIN 不限。");
        if (!verified.isEmpty()) {
            sb.append("\n\n用户已掌握这些概念：").append(String.join("、", verified))
              .append("。如果本次任务里有 EXPLAIN，请让它要求用户用已掌握的概念做类比。");
        }
        return sb.toString();
    }

    private static String truncate(String material) {
        if (material == null) {
            return "";
        }
        return material.length() <= MATERIAL_LIMIT
                ? material : material.substring(0, MATERIAL_LIMIT) + "\n……（材料已截断）";
    }
}
```

> `entity()` 会要求模型按 JSON 返回并反序列化。**它可能失败**（模型返回多余文字）。
> 失败时抛 `IllegalStateException` → 500，前端提示重试。这是本轮有意接受的粗糙：先跑通，再谈重试与兜底解析。
> 评测集（第 5 节）就是用来量化这个失败率的。

### 4.8 `controller/QuizController.java`

```java
package com.lcz.aiagentstudy.controller;

import com.lcz.aiagentstudy.service.QuizService;
import com.lcz.aiagentstudy.service.QuizStore;
import com.lcz.aiagentstudy.web.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/quiz")
@RequiredArgsConstructor
public class QuizController {
    private final CurrentUserResolver currentUserResolver;
    private final QuizService quizService;

    public record GenerateRequest(@NotNull(message = "knowledgeItemId 不能为空") UUID knowledgeItemId) {
    }

    @PostMapping("/generate")
    public QuizResponse generate(@Valid @RequestBody GenerateRequest req,
                                 HttpServletRequest request) {
        UUID userId = currentUserResolver.requireUserId(request);
        return QuizResponse.of(quizService.generate(userId, req.knowledgeItemId()));
    }
}
```

> 响应形状**不放进 `QuizController`**：阶段 4 的 `/review/start` 要返回同一个形状，两个 Controller 共用
> `model.QuizResponse`（`record` + 静态 `of(CreatedQuiz)`），而不是让 `TodoController` 去依赖 `QuizController`
> ——那是 web 层里 Controller 反向依赖 Controller，改一个接口的响应会悄悄改到另一个。
> 恢复接口 `GET /quiz/conversations/{conversationId}` 比这个形状多一个 `verdict`，形状不同，单独定义（见 7.8）。

> 阶段 3 往这个类追加 `attempt` 接口时，顶部再补三个 import（`NotBlank` / `Size` / `OffsetDateTime`）；
> 阶段 4 追加 `conversation` 接口时，再补 `QuestionRepository`。文件是逐步长大的，不要一次全写完。

### 4.9 验收

```powershell
curl.exe -b "JSESSIONID=<你的>" -H "Content-Type: application/json" `
  -d '{\"knowledgeItemId\":\"<上一步的 id>\"}' http://localhost:8123/api/quiz/generate
# 期望：200，3 个任务，至少 1 个 kind=EXPLAIN

# 查库 1：题目落库与类型
select q.id, q.kind, q.target, left(m.content, 40) as text
  from t_question q
  join t_message m on m.id = q.message_id
 where m.conversation_id = '<上一步的 conversationId>'
 order by q.id;
# 期望：3 行，至少 1 行 EXPLAIN

# 查库 2：知识点是 CANDIDATE
select name, status from t_knowledge_point
 where user_id = '<你的 userId>' order by created_at desc limit 5;
# 期望：3 行 CANDIDATE（若有同名知识点合并，行数可能少 1）
```

**必须人工看一遍题目**：有没有偷偷给答案、有没有跑出材料范围、`EXPLAIN` 有没有给听众设定。
这三条是「出题质量」的真实验收，评测集只覆盖前两条。

**实测结果（2026-10-22，后端 8123 实跑，`hnsw.md` 那份资料）**

请求耗时 `4411 ms`，响应原文：

```json
{"conversationId":"3eb7852d-0b72-4b41-9e1c-b25b3a8b551b","questions":[
 {"questionId":1,"kind":"FACT","target":"HNSW 第 0 层邻居数","text":"HNSW 索引中，第 0 层的每个节点最多保留多少个邻居？"},
 {"questionId":2,"kind":"FACT","target":"ef_construction 的作用","text":"ef_construction 参数控制什么？增大它会对索引构建产生什么影响？"},
 {"questionId":3,"kind":"EXPLAIN","target":"HNSW 查询复杂度为何接近对数级","text":"假设听众只会 Python、没听过向量检索，请用自己的话讲清楚：为什么 HNSW 的查询在数据量大时接近对数级别，而不是线性扫描？"}]}
```

| #   | 断言                                                                  | 实测                                                            |
| --- | --------------------------------------------------------------------- | --------------------------------------------------------------- |
| 1   | `200`，3 个任务，至少 1 个 `EXPLAIN`                                  | 通过（2 `FACT` + 1 `EXPLAIN`）                                  |
| 2   | `t_question` 3 行，`kind` 正确，`knowledge_item_id` 指向本资料        | 通过（id 1~3，全部指向 `12dda403-...`）                         |
| 3   | `t_knowledge_point` 3 行 `CANDIDATE`                                  | 通过                                                            |
| 4   | 会话 `mode = SELF_TEST`，消息 1 条 `USER` + 3 条 `ASSISTANT/QUESTION` | 通过（消息 id 54~57）                                           |
| 5   | 别人的资料或根本不存在的资料 → `403`                                  | 通过：`403 {"message":"资料不存在或无权访问"}`                  |
| 6   | 非法 UUID → `400`                                                     | 通过：`400 {"message":"请求体不合法"}`                          |
| 7   | 未登录 → `401`                                                        | 通过                                                            |
| 8   | 人工看题：不泄露答案、不跑出材料、`EXPLAIN` 有听众设定                | 通过（3 题全部命中材料里的 m / ef_construction / 对数级复杂度） |

**本轮验不到的**：`retry` 重问分支（需要先有一道 `FAIL`/`PARTIAL`，阶段 3 才有判分）、
注入最近 5 个 `VERIFIED` 知识点做类比（当前知识点全是 `CANDIDATE`）。这两条等阶段 3 跑完再补验。

***

## 5. 阶段 2.5：出题评测集（两层：规则硬断言 + AI 裁判 + 人工复审）

> 这就对应 [product-spec.md 第 5 节](./product-spec.md) 的「一次专项突破默认 3 个任务、至少 1 个 `EXPLAIN`」。

出题靠提示词，提示词改一个字就可能崩。**没有评测数字，「出题质量好」这句话就是自我感觉。**

### 5.1 三层结构

| 层       | 判什么                                                                        | 谁来判                                                        | 是否阻断                      |
| -------- | ----------------------------------------------------------------------------- | ------------------------------------------------------------- | ----------------------------- |
| 规则层   | 题数 = 3、字段非空、`kind` 合法、至少 1 个 `EXPLAIN`、`target` ≤40 字且不重复 | 代码                                                          | 是（失败 ≤1 份材料）          |
| 语义层   | 题面是否泄露答案、是否超纲、`EXPLAIN` 是否给了听众                            | AI 裁判（`dashScopeChatModel` 判 `deepSeekChatModel` 出的题） | 否，只写报告                  |
| 人工复审 | 确认 AI 判定是真泄露还是误报                                                  | 人                                                            | 决定改提示词还是改裁判 rubric |

**为什么不再用字符串锚点**：2026-10-22 第一版用 `answerAnchors` + `text.contains(anchor)` 判泄露，
10 份材料只过 5/10，其中 3 条是误报 —— 锚点写的是**术语**（`Proxy`、`布隆过滤器`、`预主密钥`），
术语出现在题面是正常的，只有**结论**出现才算泄露。字符串判不了"这是术语还是结论"。

### 5.2 实现文件

`src/test/java/com/lcz/aiagentstudy/service/QuizPromptEvalTest.java`，关键结构：

```java
@SpringBootTest
class QuizPromptEvalTest {

    @Autowired private ChatModel deepSeekChatModel;    // 出题，与线上一致
    @Autowired private ChatModel dashScopeChatModel;   // 裁判，bean 名是 dashScopeChatModel（大写 S）

    record Case(String name, String material, List<String> keyPoints) {}
    record TaskJudge(int index, boolean leaked, boolean outOfScope,
                     boolean explainHasAudience, String reason) {}
    record JudgeVerdict(List<TaskJudge> tasks) {}

    @Test void 出题评测_规则硬断言_语义交AI裁判并生成报告() throws IOException { ... }
    private List<String> ruleCheck(List<QuizService.TaskDraft> tasks) { ... }
    private List<TaskJudge> judge(ChatClient judgeClient, Case c, List<QuizService.TaskDraft> tasks) { ... }
    private void appendDetail(...) { ... }
}
```

裁判提示词（`JUDGE_PROMPT`）的四条要求：

1. **判定主线只有一条**：题面是否替用户说出了「用户本来要输出的那个答案」。
   - 点名概念不算泄露（「布隆过滤器在缓存穿透中起什么作用」）；
   - 给出前提或现象、让用户解释原因不算泄露（「范围查询之后的列还能用于索引查找吗」）；
   - 反事实提问不算泄露（「如果只用两次握手会引发什么问题」）—— 用户仍要自己说出问题在哪；
   - 直接给出结论、数值或选择算泄露（「应选 REQUIRES_NEW 而不是 NESTED」「隐式类型转换会导致索引失效」「m 的默认值是 16」）。
2. 三个判定分开：`leaked` / `outOfScope` / `explainHasAudience`；`FACT` 的 `explainHasAudience` 一律 `true`。
3. 先写 `reason`（≤40 字）再给布尔值，`index` 必须与输入顺序一致。
4. 裁判用不同模型、温度 0，避免模型偏袒自己的输出。

### 5.3 怎么跑

```powershell
mvn -Dtest=QuizPromptEvalTest test
```

- 控制台逐条打出 `[规则PASS]` / `[规则FAIL]`，最后打印 `评测报告: ...\target\quiz-eval-report.md`。
- 报告含三部分：汇总、待人工复审清单、逐题明细表（`leaked` / `outOfScope` / `audience` / AI 理由），
  每张明细表下面留一行「人工复审结论」。
- 报告每次运行覆盖，不进 git；人工结论只记 [impl-log.md](./impl-log.md)。

### 5.4 人工复审怎么做

1. 打开 `target/quiz-eval-report.md`，逐条看「待人工复审」。
2. 三类结论：
   - **真泄露** → 改 `QuizService.SYSTEM_PROMPT`；
   - **误报** → 改 `JUDGE_PROMPT` 的 rubric（不是改测试的断言）；
   - **超纲但合理** → 改该案例的 `keyPoints`。
3. 结论写进 [impl-log.md](./impl-log.md)，并注明日期与轮次。
4. **连续两轮 AI 判定与人工结论完全一致后**，才把语义层升级成硬断言
   （`judgeFailed == 0 && leaked == 0`）。在那之前硬断言会让每次改提示词都被裁判误判卡住。

### 5.5 2026-10-22 实测（两轮）

| 轮次   | 规则层 | 泄露 | 超纲 | 缺听众 | 说明                                                          |
| ------ | ------ | ---- | ---- | ------ | ------------------------------------------------------------- |
| 第一轮 | 10/10  | 6    | 1    | 1      | 裁判把「给出前提 / 反事实提问」也算泄露，人工复审判为口径错误 |
| 第二轮 | 10/10  | 0    | 0    | 0      | 按「是否替用户说出答案主体」重写 rubric，同一批题全部合格     |

**人工复审的结论（口径修正）**：这个产品的唯一目的是让用户自己输出所学内容，
题面给出前提、现象、反事实**不算泄露**；只有替用户说出「要输出的那个结论/数值/选择」才算泄露。
第一轮 8 条告警里，「TCP 第 3 题只两次握手会引发什么问题」这类全部属于有效问题。

> 注意：第二轮的 `0 条` 是在上述口径下的结论，不代表客观无泄露。
> 后续改提示词时仍需人工抽查报告，不能只看计数。

> 这 10 个材料是有意跨领域的（向量检索 / 网络 / 数据库 / JVM / 框架 / 数据结构 / 缓存 / 前端 / 安全）。
> 只在「自己熟悉的领域」测，题面看起来都会不错；跨领域才能暴露提示词的漏洞。

***

## 6. 阶段 3：作答判分（让用户真的讲出来）

> 对应 [product-spec.md 第 6 节](./product-spec.md)。

### 6.1 契约

| 方法 | 路径                         | 请求                                                    | 成功                                                                               |
| ---- | ---------------------------- | ------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| POST | `/quiz/{questionId}/attempt` | `{"answer":"..."}`                                      | `200 {"verdict","feedback","gaps","retryAllowed","stage","nextDueAt","attemptNo"}` |
| POST | `/quiz/{questionId}/help`    | `{"question":"我还是没懂 m 和 ef_construction 的区别"}` | `200 {"answer":"..."}`                                                             |

规则：

1. `verdict` 只取 `PASS` / `PARTIAL` / `FAIL`；`gaps` 是**漏洞清单**，`PASS` 时为空。
2. `PARTIAL` / `FAIL` 且是**第 1 次作答**（`attemptNo = 1`）时 `retryAllowed = true`，补讲只给 1 次。
   同一 `questionId` 最多提交 2 次；第 3 次返回 `400 {"message":"这道题已经补讲过了，等下次复习再讲"}`。
3. 补讲（`attemptNo = 2`）无论结果如何，`nextDueAt` 都会按规则重排，不再允许第 3 次。
4. 判分成功且非 `FAIL` → 该题的知识点升为 `VERIFIED`。
5. **求助先回答**（`/help`），回答后把 `t_question.help_used` 置 `true`；该题在复习队列里排前面。
   作答接口不再接收 `askedForHelp` —— 前端不必自己维护标记，服务端以库为准。
6. 排期规则：

| verdict   | 现有排期         | 新排期                          |
| --------- | ---------------- | ------------------------------- |
| `FAIL`    | 任意             | 1 天后，`stage = 1`（重置阶梯） |
| `PARTIAL` | 任意             | 3 天后，`stage = 1`（重置阶梯） |
| `PASS`    | 没有 / 已 `DONE` | 1 天后，`stage = 1`             |
| `PASS`    | `stage = 1`      | 3 天后，`stage = 3`             |
| `PASS`    | `stage = 3`      | 7 天后，`stage = 7`             |
| `PASS`    | `stage = 7`      | 不再排期（`status = 'DONE'`）   |

**为什么不直接给答案**：`feedback` 和 `gaps` 只描述「缺了什么、哪一步跳了」，
绝不复述正确答案。用户必须自己补上讲 —— 这是费曼式复述的核心，也是本产品与「AI 讲题」的分界线。

### 6.2 `service/HelpService.java`（求助 = 先回答，但不动判分）

```java
package com.lcz.aiagentstudy.service;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class HelpService {

    private static final String SYSTEM_PROMPT = """
            用户正在凭记忆回答一道题，卡住了，向你提问。
            规则：
            1. 直接回答他的问题，讲清楚即可，不要长篇大论。
            2. 不要替他把整道题答完，也不要给出这道题的完整答案。
            3. 回答完提醒一句："回到题目，用你自己的话接着讲。"
            """;

    private static final int MATERIAL_LIMIT = 8000;

    private final ChatModel deepSeekChatModel;

    public String answer(String material, String question, String userQuestion) {
        ChatClient client = ChatClient.builder(deepSeekChatModel).defaultSystem(SYSTEM_PROMPT).build();
        String userMessage = "材料：\n" + truncate(material)
                + "\n\n题目：" + question
                + "\n\n我的问题：" + userQuestion;
        String content = client.prompt().user(userMessage).call().content();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("求助回答生成失败，请重试");
        }
        return content;
    }

    private static String truncate(String material) {
        if (material == null) {
            return "";
        }
        return material.length() <= MATERIAL_LIMIT
                ? material : material.substring(0, MATERIAL_LIMIT) + "\n……（材料已截断）";
    }
}
```

### 6.3 `repository/TestRecordRepository.java`

```java
package com.lcz.aiagentstudy.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class TestRecordRepository {
    private final JdbcTemplate jdbcTemplate;

    // attemptNo 由此推导：1 是首答，2 是补讲。不额外存"作答次数"字段，避免两处不一致
    public int countByQuestion(long questionId) {
        Integer n = jdbcTemplate.queryForObject(
                "select count(*) from t_test_record where question_id = ?",
                Integer.class, questionId);
        return n == null ? 0 : n;
    }

    public void insert(UUID userId, long questionId, String answer, String feedback,
                       String verdict, int attemptNo) {
        jdbcTemplate.update("""
                        insert into t_test_record
                            (user_id, question_id, answer, feedback, verdict, attempt_no)
                        values (?, ?, ?, ?, ?, ?)
                        """,
                userId, questionId, answer, feedback, verdict, attemptNo);
    }
}
```

### 6.4 `service/GradingService.java`

```java
package com.lcz.aiagentstudy.service;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class GradingService {

    private static final String SYSTEM_PROMPT = """
            你是判分官。用户刚凭记忆作答，你要判断他讲清楚了多少。
            只输出 JSON，字段固定为 verdict、feedback、gaps。

            判分标准：
            - PASS：核心要点齐全、因果正确、用自己的话讲明白（不是背原句）。
            - PARTIAL：方向对，但缺关键一环、某一步跳过了、或者用了没解释的术语。
            - FAIL：结论错误、答非所问、只重复题面。

            铁律：
            1. feedback 一到两句，只指出"问题出在哪"，绝对不给出正确答案、不复述材料原文。
            2. gaps 是漏洞清单：PARTIAL / FAIL 给 2 到 4 条，每条一句话，描述缺了什么；
               PASS 给空数组。
            3. 不许因为答案短就判 FAIL；短但讲对了就是 PASS。
            """;

    // 材料可能几万字，判分只看和这道题相关的一段，截断以控制成本
    private static final int MATERIAL_LIMIT = 8000;

    private final ChatModel deepSeekChatModel;

    public record Grading(String verdict, String feedback, List<String> gaps) {
    }

    private record GradingDraft(String verdict, String feedback, List<String> gaps) {
    }

    public Grading grade(String material, String target, String kind, String question,
                         String answer) {
        ChatClient client = ChatClient.builder(deepSeekChatModel).defaultSystem(SYSTEM_PROMPT).build();

        String userMessage = "材料：\n" + truncate(material)
                + "\n\n考点：" + target
                + "\n任务类型：" + kind
                + "\n题目：" + question
                + "\n\n用户的作答：\n" + answer;

        GradingDraft draft = client.prompt().user(userMessage).call().entity(GradingDraft.class);
        if (draft == null || draft.verdict() == null) {
            throw new IllegalStateException("判分失败，请重试");
        }

        String verdict = draft.verdict().trim().toUpperCase(Locale.ROOT);
        if (!"PASS".equals(verdict) && !"PARTIAL".equals(verdict) && !"FAIL".equals(verdict)) {
            throw new IllegalStateException("判分返回了未知结论: " + draft.verdict());
        }

        String feedback = draft.feedback() == null || draft.feedback().isBlank()
                ? "已收到作答。" : draft.feedback().trim();

        List<String> gaps = new ArrayList<>();
        if (draft.gaps() != null) {
            for (String gap : draft.gaps()) {
                if (gap != null && !gap.isBlank()) {
                    gaps.add(gap.trim());
                }
            }
        }
        if (!"PASS".equals(verdict) && gaps.isEmpty()) {
            // 模型偶尔只给结论不给清单。这里不造漏洞（造出来的会误导用户），
            // 只放一条兜底提示，前端仍能正常展示。
            gaps.add("没看到你的推理过程，试着把因果一步步讲出来。");
        }
        return new Grading(verdict, feedback, gaps);
    }

    private static String truncate(String material) {
        if (material == null) {
            return "";
        }
        return material.length() <= MATERIAL_LIMIT
                ? material : material.substring(0, MATERIAL_LIMIT) + "\n……（材料已截断）";
    }
}
```

### 6.5 `service/AttemptStore.java`（落库 + 排期，一个事务）

```java
package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.exception.BadRequestException;
import com.lcz.aiagentstudy.repository.ConversationRepository;
import com.lcz.aiagentstudy.repository.KnowledgePointRepository;
import com.lcz.aiagentstudy.repository.QuestionRepository;
import com.lcz.aiagentstudy.repository.ReviewScheduleRepository;
import com.lcz.aiagentstudy.repository.TestRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 一次作答的写入口：成绩 + 排期 + 知识点 + 会话时间，四件事必须同生共死。
 * 单独一个 bean，理由同 QuizStore：判分要调模型（慢），不能让事务包住模型调用。
 */
@Service
@RequiredArgsConstructor
public class AttemptStore {
    private final TestRecordRepository testRecordRepository;
    private final ReviewScheduleRepository reviewScheduleRepository;
    private final KnowledgePointRepository knowledgePointRepository;
    private final ConversationRepository conversationRepository;

    public record AttemptResult(String verdict, String feedback, List<String> gaps,
                                boolean retryAllowed, int stage, OffsetDateTime nextDueAt,
                                int attemptNo) {
    }

    private record Plan(int stage, int days, boolean done) {
    }

    @Transactional
    public AttemptResult record(UUID userId, QuestionRepository.QuestionContext question,
                                String answer, GradingService.Grading grading) {
        int attemptNo = testRecordRepository.countByQuestion(question.questionId()) + 1;
        // 补讲只有一次：第 3 次提交直接拒绝，避免同一道题被无限重答
        if (attemptNo > 2) {
            throw new BadRequestException("这道题已经补讲过了，等下次复习再讲");
        }
        testRecordRepository.insert(userId, question.questionId(), answer, grading.feedback(),
                grading.verdict(), attemptNo);

        // 讲过一遍并且不是完全跑偏，知识点就算"用过"。knowledge_point_id 允许为空（历史数据）
        if (!"FAIL".equals(grading.verdict()) && question.knowledgePointId() != null) {
            knowledgePointRepository.markVerified(question.knowledgePointId());
        }

        Optional<ReviewScheduleRepository.ScheduleRow> current =
                reviewScheduleRepository.findByQuestion(question.questionId());
        Plan plan = planFor(grading.verdict(), current);

        OffsetDateTime nextDueAt = null;
        if (plan.done()) {
            reviewScheduleRepository.markDoneByQuestion(question.questionId());
        } else {
            nextDueAt = reviewScheduleRepository.upsert(
                    userId, question.questionId(), plan.stage(), plan.days());
        }

        conversationRepository.touch(question.conversationId());

        boolean retryAllowed = !"PASS".equals(grading.verdict()) && attemptNo == 1;
        return new AttemptResult(grading.verdict(), grading.feedback(), grading.gaps(),
                retryAllowed, plan.stage(), nextDueAt, attemptNo);
    }

    /**
     * 排期状态机。只看"当前这道题自己的排期"，不跨题推断。
     * FAIL / PARTIAL 一律重置到 stage 1：答崩了还继续拉长间隔，等于假装学会了。
     */
    private Plan planFor(String verdict, Optional<ReviewScheduleRepository.ScheduleRow> current) {
        if (!"PASS".equals(verdict)) {
            return new Plan(1, "PARTIAL".equals(verdict) ? 3 : 1, false);
        }
        if (current.isEmpty() || "DONE".equals(current.get().status())) {
            return new Plan(1, 1, false);
        }
        return switch (current.get().stage()) {
            case 1 -> new Plan(3, 3, false);
            case 3 -> new Plan(7, 7, false);
            default -> new Plan(7, 0, true);
        };
    }
}
```

### 6.6 `service/QuizService` 追加求助与判分

```java
    // 注入这三个字段（加在构造注入的字段列表里）
    private final GradingService gradingService;
    private final HelpService helpService;
    private final AttemptStore attemptStore;

    /** 求助：先回答用户的疑问，再把这题标记为"求助过"。不改判分、不产生成绩。 */
    public String help(UUID userId, long questionId, String userQuestion) {
        QuestionRepository.QuestionContext question = requireOwnedQuestion(userId, questionId);
        String answer = helpService.answer(question.material(), question.text(), userQuestion);
        questionRepository.markHelpUsed(questionId);
        return answer;
    }

    public AttemptStore.AttemptResult attempt(UUID userId, long questionId, String answer) {
        QuestionRepository.QuestionContext question = requireOwnedQuestion(userId, questionId);
        GradingService.Grading grading = gradingService.grade(
                question.material(), question.target(), question.kind(),
                question.text(), answer);
        return attemptStore.record(userId, question, answer, grading);
    }

    private QuestionRepository.QuestionContext requireOwnedQuestion(UUID userId, long questionId) {
        QuestionRepository.QuestionContext question = questionRepository.findWithOwner(questionId)
                .stream().findFirst()
                // 与"不存在"同一句文案：不让攻击者用状态码探测题目是否存在
                .orElseThrow(() -> new ForbiddenException("题目不存在或无权访问"));
        if (!question.userId().equals(userId)) {
            throw new ForbiddenException("题目不存在或无权访问");
        }
        return question;
    }
```

> `QuizService` 已有 5 个依赖，这里再加 3 个（`GradingService` / `HelpService` / `AttemptStore`），
> 构造器由 Lombok 生成，不需要手改。别忘顶部补 `import com.lcz.aiagentstudy.exception.ForbiddenException;`。

### 6.7 `QuizController` 追加接口

```java
    public record AttemptRequest(
            @NotBlank(message = "answer 不能为空")
            @Size(max = 5000, message = "作答最多 5000 字") String answer) {
    }

    public record AttemptResponse(String verdict, String feedback, List<String> gaps,
                                  boolean retryAllowed, int stage,
                                  OffsetDateTime nextDueAt, int attemptNo) {
    }

    public record HelpRequest(
            @NotBlank(message = "question 不能为空")
            @Size(max = 500, message = "问题最多 500 字") String question) {
    }

    public record HelpResponse(String answer) {
    }

    @PostMapping("/{questionId}/attempt")
    public AttemptResponse attempt(@PathVariable long questionId,
                                   @Valid @RequestBody AttemptRequest req,
                                   HttpServletRequest request) {
        UUID userId = currentUserResolver.requireUserId(request);
        AttemptStore.AttemptResult r = quizService.attempt(userId, questionId, req.answer());
        return new AttemptResponse(r.verdict(), r.feedback(), r.gaps(),
                r.retryAllowed(), r.stage(), r.nextDueAt(), r.attemptNo());
    }

    @PostMapping("/{questionId}/help")
    public HelpResponse help(@PathVariable long questionId,
                             @Valid @RequestBody HelpRequest req,
                             HttpServletRequest request) {
        UUID userId = currentUserResolver.requireUserId(request);
        return new HelpResponse(quizService.help(userId, questionId, req.question()));
    }
```

> 这些 record 要用 `NotBlank` / `Size` / `OffsetDateTime`，记得在 `QuizController` 顶部补上：
> `import jakarta.validation.constraints.NotBlank;`、`import jakarta.validation.constraints.Size;`、
> `import java.time.OffsetDateTime;`。`AttemptStore` 与 `QuizService` 同包，不需要 import。

### 6.8 验收

```powershell
# 1) 先故意答得很糊（拿 stage 2 给的第一个 questionId）
curl.exe -i -b "JSESSIONID=<你的>" -H "Content-Type: application/json" `
  -d '{\"answer\":\"就是控制图的参数吧，调大一点效果更好\"}' `
  http://localhost:8123/api/quiz/<questionId>/attempt
# 期望：200；verdict 是 PARTIAL 或 FAIL；gaps 有 2~4 条；retryAllowed=true；
#       nextDueAt ≈ 现在 + 3 天（PARTIAL）或 + 1 天（FAIL）

# 2) 补讲一次（同一个 questionId 再来一遍）
curl.exe -i -b "JSESSIONID=<你的>" -H "Content-Type: application/json" `
  -d '{\"answer\":\"m 是每个节点在每层保留的最大邻居数，调大召回更高但内存和构建时间都上升……\"}' `
  http://localhost:8123/api/quiz/<questionId>/attempt
# 期望：attemptNo=2；retryAllowed=false（补讲只有一次）

# 3) 再交第三次（同一题）
curl.exe -i -b "JSESSIONID=<你的>" -H "Content-Type: application/json" `
  -d '{\"answer\":\"再讲一遍\"}' http://localhost:8123/api/quiz/<questionId>/attempt
# 期望：400 {"message":"这道题已经补讲过了，等下次复习再讲"}

# 4) 空作答
curl.exe -i -b "JSESSIONID=<你的>" -H "Content-Type: application/json" `
  -d '{\"answer\":\"  \"}' http://localhost:8123/api/quiz/<questionId>/attempt
# 期望：400 {"message":"answer 不能为空"}

# 5) 求助一次（先回答，再把该题标记为「求助过」）
curl.exe -i -b "JSESSIONID=<你的>" -H "Content-Type: application/json" `
  -d '{\"question\":\"m 和 ef_construction 到底谁影响召回？\"}' `
  http://localhost:8123/api/quiz/<questionId>/help
# 期望：200，返回 answer；再查 t_question.help_used = true

# 6) 别人的题（用另一个账号登录后的 cookie）
# 期望：403 {"message":"题目不存在或无权访问"}
```

```sql
-- 查库 1：两次作答都落库，attempt_no 递增
select id, question_id, verdict, attempt_no, left(feedback, 30)
  from t_test_record where question_id = <questionId> order by id;

-- 查库 2：一题只有一行排期，stage 与 due_at 是最后一次判分的结果
select question_id, stage, status, due_at from t_review_schedule
 where question_id = <questionId>;

-- 查库 3：知识点已升级（若 verdict 非 FAIL）
select name, status from t_knowledge_point where id = '<该题的 knowledge_point_id>';
```

**断言（这六条缺一条就是假验收）**：

| 断言                                                        | 验的是什么                                             |
| ----------------------------------------------------------- | ------------------------------------------------------ |
| 糊答案拿到 `PARTIAL` / `FAIL`，且 `gaps` 非空               | 判分不是走过场；漏洞清单有内容                         |
| `feedback` 里**没有正确答案**                               | 「只挑漏洞、不给答案」这条产品承诺真的成立             |
| 第二次作答 `attemptNo = 2` 且 `retryAllowed = false`        | 补讲上限 1 次                                          |
| 第三次作答返回 `400` 且提示「已经补讲过了」                 | 上限是服务端强制的，不只是前端藏按钮                   |
| 同一 `question_id` 在 `t_review_schedule` 只有 1 行         | `ON CONFLICT (question_id)` 生效，没有「答一次排三条」 |
| `/help` 后 `t_question.help_used = true`，且 `verdict` 不变 | 求助只记事实，不污染判分                               |

### 6.9 2026-10-22 实测（`questionId = 1`，后端 8123）

| 断言                                               | 实测                                                                                                                  | 结论                     |
| -------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------- | ------------------------ |
| 糊答案被判 `PARTIAL` / `FAIL`，`gaps` 非空         | `FAIL`，`gaps` 3 条，`retryAllowed=true`，`nextDueAt` = 次日                                                          | 通过                     |
| `feedback` / `gaps` 里没有正确答案                 | 首测 `gaps` 第 3 条写了「如 2m 这一常见设置」；改提示词后复测（`questionId=2`）4 条全部只描述缺口，无数值、无术语定义 | 首测不通过，**复测通过** |
| 第二次作答 `attemptNo = 2`、`retryAllowed = false` | `PASS`，`stage=3`，`nextDueAt` = 3 天后                                                                               | 通过                     |
| 第三次作答 `400`                                   | `400 {"message":"这道题已经补讲过了，等下次复习再讲"}`                                                                | 通过                     |
| `t_review_schedule` 一题一行                       | 每题 1 行，`stage=3`、`status=PENDING`                                                                                | 通过                     |
| `/help` 后 `help_used=true` 且不产生成绩           | `help_used=t`；`t_test_record` 里没有该题的行；知识点仍为 `CANDIDATE`                                                 | 通过                     |
| 空作答                                             | `400 {"message":"answer 不能为空"}`                                                                                   | 通过                     |
| 别人的题                                           | `403 {"message":"题目不存在或无权访问"}`                                                                              | 通过                     |
| 未登录                                             | `401 {"message":"未登录"}`                                                                                            | 通过                     |
| `PASS` 把知识点升为 `VERIFIED`                     | 「HNSW 第 0 层邻居数」「ef_construction 的作用」两行变 `VERIFIED`                                                     | 通过                     |
| `VERIFIED` 注入下一次出题                          | 新会话第 2 题：「假设听众只了解你已掌握的 **ef_construction** 和 **HNSW 第 0 层邻居数**，请用它们做类比」             | 通过                     |

**已修缺陷（技术债第 25 条）**：`GradingService.SYSTEM_PROMPT` 原先只约束了 `feedback` 不给答案，
`gaps` 可以把答案说出来。第 2 条已改为：

> `gaps` 每条只描述「缺了哪一环」或「哪一步没说清」，可以给思考方向，
> 但不得出现材料中的数值、公式、术语定义、结论，也不要举例。

***

## 7. 阶段 4：待办列表 + 复习 + 进度

> 对应 [product-spec.md 第 7、9 节](./product-spec.md)。

这一阶段一次交付三个只读接口 + 一个复习入口，前端正好三块：侧栏待办、`/review` 页、`/progress` 页。

### 7.1 契约

| 方法 | 路径                                   | 成功响应                                                                               |
| ---- | -------------------------------------- | -------------------------------------------------------------------------------------- |
| GET  | `/todos`                               | `200 {"items":[...]}`，见下                                                            |
| POST | `/review/start`                        | `200 {"conversationId","questions":[...]}`，与 `/quiz/generate` 同形                   |
| GET  | `/progress`                            | `200 {"masteredItems","totalItems","streakDays","dueCount","weeks":[...]}`             |
| GET  | `/quiz/conversations/{conversationId}` | `200 {"conversationId","questions":[{"questionId","kind","target","text","verdict"}]}` |

**`/todos` 只返回三类，且只返回「现在能做的」**：

| `type`     | 含义                                              | `count`  | `knowledgeItemId`    | `conversationId`         |
| ---------- | ------------------------------------------------- | -------- | -------------------- | ------------------------ |
| `REVIEW`   | 已到期需要复习的题                                | 到期题数 | `null`（一条汇总项） | `null`                   |
| `RETRY`    | 上次 `PARTIAL` / `FAIL`、没补讲、复习还没到期的题 | 题数     | 有                   | `null`（点进去重新出题） |
| `UNTESTED` | 已经出了但还没作答的题                            | 题数     | 有                   | 有（点进去继续那次测验） |

- **没有资料的「开始学习」不在这里**。那是资料页的事；待办列表只列「已经欠下的动作」。
- 到期复习**优先**：`REVIEW` 排第一。产品规则「到期了就别再练新题」靠前端把第一项置顶来体现。
- 不返回「全部资料」，也不返回「所有历史题」，否则列表会变成第二个资料库。

`GET /quiz/conversations/{conversationId}` 是测验页刷新恢复用的：返回题目 + 每题最近一次结论。
别人的会话 → `403`「会话不存在或无权访问」，复用 `ConversationService.assertOwned`。

### 7.2 `repository/TodoRepository.java`

```java
package com.lcz.aiagentstudy.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class TodoRepository {
    private final JdbcTemplate jdbcTemplate;

    public record RetryRow(UUID knowledgeItemId, String title, int count) {
    }

    public record UntestedRow(UUID knowledgeItemId, UUID conversationId, String title, int count) {
    }

    /** 上次没讲清、还没补讲、且复习未到期（到期后归 REVIEW，不重复出现）。 */
    public List<RetryRow> findRetry(UUID userId) {
        return jdbcTemplate.query("""
                        with latest as (
                            select distinct on (tr.question_id) tr.question_id, tr.verdict, tr.attempt_no
                              from t_test_record tr
                             where tr.user_id = ?
                             order by tr.question_id, tr.id desc
                        )
                        select q.knowledge_item_id, ki.title, count(*) as pending
                          from latest l
                          join t_question q          on q.id = l.question_id
                          join t_review_schedule rs  on rs.question_id = q.id
                          join t_knowledge_item ki   on ki.id = q.knowledge_item_id
                         where l.verdict <> 'PASS' and l.attempt_no = 1
                           and rs.status = 'PENDING' and rs.due_at > now()
                         group by q.knowledge_item_id, ki.title
                         order by max(q.id) desc
                        """,
                (rs, rowNum) -> new RetryRow(
                        rs.getObject("knowledge_item_id", UUID.class),
                        rs.getString("title"),
                        rs.getInt("pending")),
                userId);
    }

    /** 出了题一次都没作答的。按「会话 + 资料」聚合，点进去继续那一次测验。 */
    public List<UntestedRow> findUntested(UUID userId) {
        return jdbcTemplate.query("""
                        select q.knowledge_item_id,
                               m.conversation_id,
                               ki.title,
                               count(*) as pending
                          from t_question q
                          join t_message m        on m.id = q.message_id
                          join t_knowledge_item ki on ki.id = q.knowledge_item_id
                         where ki.user_id = ?
                           and not exists (select 1 from t_test_record tr where tr.question_id = q.id)
                         group by q.knowledge_item_id, m.conversation_id, ki.title
                         order by max(m.id) desc
                        """,
                (rs, rowNum) -> new UntestedRow(
                        rs.getObject("knowledge_item_id", UUID.class),
                        rs.getObject("conversation_id", UUID.class),
                        rs.getString("title"),
                        rs.getInt("pending")),
                userId);
    }
}
```

### 7.3 `QuestionRepository` 追加按会话查题

```java
    public record QuestionRow(long id, String kind, String target, String text, String verdict) {
    }

    /** 测验页刷新恢复：每题带最近一次结论（null = 还没作答）。 */
    public List<QuestionRow> findByConversation(UUID conversationId) {
        return jdbcTemplate.query("""
                        select q.id, q.kind, q.target, m.content as text,
                               (select tr.verdict from t_test_record tr
                                 where tr.question_id = q.id
                                 order by tr.id desc limit 1) as verdict
                          from t_question q
                          join t_message m on m.id = q.message_id
                         where m.conversation_id = ?
                         order by q.id
                        """,
                (rs, rowNum) -> new QuestionRow(
                        rs.getLong("id"),
                        rs.getString("kind"),
                        rs.getString("target"),
                        rs.getString("text"),
                        rs.getString("verdict")),
                conversationId);
    }

    /** 复习幂等：这一轮还没作答的题（按 id 升序）。空 = 这一轮已经做完。 */
    public List<QuestionRow> findUnanswered(UUID conversationId) {
        return jdbcTemplate.query("""
                        select q.id, q.kind, q.target, m.content as text, null::text as verdict
                          from t_question q
                          join t_message m on m.id = q.message_id
                         where m.conversation_id = ?
                           and not exists (select 1 from t_test_record tr where tr.question_id = q.id)
                         order by q.id
                        """,
                (rs, rowNum) -> new QuestionRow(
                        rs.getLong("id"),
                        rs.getString("kind"),
                        rs.getString("target"),
                        rs.getString("text"),
                        rs.getString("verdict")),
                conversationId);
    }
```

### 7.3b `ConversationRepository` 追加「最新一轮某种模式的会话」

```java
    /** 复习幂等用：只认最新一条，避免几个月前没答完的那轮一直劫持「开始复习」。 */
    public Optional<UUID> findLatestIdByMode(UUID userId, String mode) {
        return jdbcTemplate.query(
                        "select id from t_conversation where user_id = ? and mode = ? " +
                                "order by created_at desc limit 1",
                        (rs, i) -> rs.getObject("id", UUID.class), userId, mode)
                .stream().findFirst();
    }
```

### 7.4 `service/QuizService` 追加复习与恢复

```java
    // 追加依赖（连同顶部 import：KnowledgeItemRepository / ReviewScheduleRepository /
    // ConversationRepository / model.ConversationMode / BadRequestException）
    private final ConversationService conversationService;
    private final KnowledgeItemRepository knowledgeItemRepository;
    private final ReviewScheduleRepository reviewScheduleRepository;
    private final ConversationRepository conversationRepository;

    public List<QuestionRepository.QuestionRow> listByConversation(UUID userId, UUID conversationId) {
        conversationService.assertOwned(conversationId, userId);
        return questionRepository.findByConversation(conversationId);
    }

    /**
     * 2 道到期 + 1 道交错。交错题来自「另一份资料」，这是产品里唯一一处跨资料混排，
     * 也是"知识网络"能落地的最小形态。拿不到交错题就全用到期题（最多 3 道）。
     */
    public QuizStore.CreatedQuiz startReview(UUID userId) {
        // 幂等：最近一轮复习没答完，就回到那一轮，不新建、不复制题
        Optional<UUID> latest = conversationRepository.findLatestIdByMode(
                userId, ConversationMode.REVIEW.name());
        if (latest.isPresent()) {
            List<QuestionRepository.QuestionRow> rest = questionRepository.findUnanswered(latest.get());
            if (!rest.isEmpty()) {
                List<QuizStore.CreatedQuestion> questions = rest.stream()
                        .map(r -> new QuizStore.CreatedQuestion(r.id(), r.kind(), r.target(), r.text()))
                        .toList();
                return new QuizStore.CreatedQuiz(latest.get(), questions);
            }
        }

        List<ReviewScheduleRepository.DueItem> due =
                reviewScheduleRepository.findDue(userId, 3);
        if (due.isEmpty()) {
            throw new BadRequestException("现在没有到期需要复习的题");
        }

        UUID interleavedItemId = null;
        TaskDraft interleaved = null;
        Optional<UUID> anotherItemId =
                knowledgeItemRepository.findAnotherItemId(userId, due.get(0).knowledgeItemId());
        if (anotherItemId.isPresent()) {
            KnowledgeItemRow item = knowledgeService.requireOwned(anotherItemId.get(), userId);
            List<String> verified = knowledgePointRepository.findRecentVerified(userId, 5);
            Optional<TaskDraft> draft = draftOne(item, verified);
            if (draft.isPresent()) {
                interleaved = draft.get();
                interleavedItemId = item.id();
            }
        }

        // 交错题出不来（只有一份资料 / 模型失败）就把第 3 道到期题补上，不阻断这次复习
        List<ReviewScheduleRepository.DueItem> picked =
                interleaved == null ? due : due.subList(0, Math.min(2, due.size()));
        return quizStore.createReview(userId, picked, interleavedItemId, interleaved);
    }
```

> `reviewScheduleRepository`、`conversationRepository` 需要作为字段注入到 `QuizService`（第 4.5 节已经建好这两个类）。
> 幂等分支必须放在「查到到期题」之前：先回同一轮，才轮得到判断「有没有到期项」。

### 7.5 `service/TodoService.java`

```java
package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.repository.ReviewScheduleRepository;
import com.lcz.aiagentstudy.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TodoService {
    private final TodoRepository todoRepository;
    private final ReviewScheduleRepository reviewScheduleRepository;

    public record TodoItem(String type, UUID knowledgeItemId, UUID conversationId,
                           String title, int count) {
    }

    public List<TodoItem> list(UUID userId) {
        List<TodoItem> items = new ArrayList<>();

        // REVIEW 一条汇总：到期题可能跨资料，按资料拆反而让用户要挨个点
        int dueCount = reviewScheduleRepository.countDue(userId);
        if (dueCount > 0) {
            items.add(new TodoItem("REVIEW", null, null, "到期复习", dueCount));
        }

        for (TodoRepository.RetryRow row : todoRepository.findRetry(userId)) {
            items.add(new TodoItem("RETRY", row.knowledgeItemId(), null,
                    row.title(), row.count()));
        }
        for (TodoRepository.UntestedRow row : todoRepository.findUntested(userId)) {
            items.add(new TodoItem("UNTESTED", row.knowledgeItemId(), row.conversationId(),
                    row.title(), row.count()));
        }
        return items;
    }
}
```

### 7.6 `repository/ProgressRepository.java`

```java
package com.lcz.aiagentstudy.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ProgressRepository {
    private final JdbcTemplate jdbcTemplate;

    public record WeekStat(LocalDate weekStart, int attempts) {
    }

    public int countItems(UUID userId) {
        Integer n = jdbcTemplate.queryForObject(
                "select count(*) from t_knowledge_item where user_id = ?", Integer.class, userId);
        return n == null ? 0 : n;
    }

    /**
     * 已掌握 = 该资料下"所有已出过的题，最近一次都是 PASS"。
     * 用"全部通过"而不是"有一道通过"：后者答对一题就点亮整份资料，是不可信的进度。
     */
    public int countMasteredItems(UUID userId) {
        Integer n = jdbcTemplate.queryForObject("""
                        with latest as (
                            select distinct on (tr.question_id) tr.question_id, tr.verdict
                              from t_test_record tr
                             where tr.user_id = ?
                             order by tr.question_id, tr.id desc
                        ),
                        per_item as (
                            select q.knowledge_item_id,
                                   count(*) as total,
                                   count(*) filter (where l.verdict = 'PASS') as passed
                              from t_question q
                              join t_knowledge_item ki on ki.id = q.knowledge_item_id
                              left join latest l on l.question_id = q.id
                             where ki.user_id = ?
                             group by q.knowledge_item_id
                        )
                        select count(*) from per_item where total > 0 and total = passed
                        """,
                Integer.class, userId, userId);
        return n == null ? 0 : n;
    }

    /** 有作答记录的日期（按东八区切日）。只取一年，连续天数不需要更久的历史。 */
    public List<LocalDate> findActiveDates(UUID userId) {
        return jdbcTemplate.query("""
                        select distinct (created_at at time zone 'Asia/Shanghai')::date as d
                          from t_test_record
                         where user_id = ? and created_at >= now() - interval '365 days'
                         order by d
                        """,
                (rs, rowNum) -> rs.getObject("d", LocalDate.class),
                userId);
    }

    /** 近 8 周的作答次数，按周一为周首（与 Java 的 DayOfWeek.MONDAY 对齐）。 */
    public List<WeekStat> findWeeklyAttempts(UUID userId) {
        return jdbcTemplate.query("""
                        select date_trunc('week', created_at at time zone 'Asia/Shanghai')::date as week_start,
                               count(*) as attempts
                          from t_test_record
                         where user_id = ? and created_at >= now() - interval '8 weeks'
                         group by week_start
                         order by week_start
                        """,
                (rs, rowNum) -> new WeekStat(
                        rs.getObject("week_start", LocalDate.class),
                        rs.getInt("attempts")),
                userId);
    }
}
```

### 7.7 `service/ProgressService.java`

```java
package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.repository.ProgressRepository;
import com.lcz.aiagentstudy.repository.ReviewScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProgressService {
    private final ProgressRepository progressRepository;
    private final ReviewScheduleRepository reviewScheduleRepository;

    public record WeekView(LocalDate weekStart, int attempts) {
    }

    public record ProgressView(int masteredItems, int totalItems, int streakDays,
                               int dueCount, List<WeekView> weeks) {
    }

    public ProgressView load(UUID userId) {
        int totalItems = progressRepository.countItems(userId);
        int masteredItems = progressRepository.countMasteredItems(userId);
        int dueCount = reviewScheduleRepository.countDue(userId);
        int streakDays = streakDays(progressRepository.findActiveDates(userId));

        // 缺的周补 0：前端拿到固定 8 根柱子，不用自己去对齐日期
        Map<LocalDate, Integer> byWeek = new HashMap<>();
        for (ProgressRepository.WeekStat stat : progressRepository.findWeeklyAttempts(userId)) {
            byWeek.put(stat.weekStart(), stat.attempts());
        }
        LocalDate currentMonday = LocalDate.now().with(DayOfWeek.MONDAY);
        List<WeekView> weeks = new ArrayList<>(8);
        for (int i = 7; i >= 0; i--) {
            LocalDate weekStart = currentMonday.minusWeeks(i);
            weeks.add(new WeekView(weekStart, byWeek.getOrDefault(weekStart, 0)));
        }

        return new ProgressView(masteredItems, totalItems, streakDays, dueCount, weeks);
    }

    /**
     * 连续学习天数。今天还没学就从昨天开始数 —— 否则用户早上打开页面会看到"连续 0 天"，
     * 明明昨天刚学过，等于凭空惩罚一次。
     */
    private int streakDays(List<LocalDate> activeDates) {
        Set<LocalDate> days = new HashSet<>(activeDates);
        LocalDate cursor = LocalDate.now();
        if (!days.contains(cursor)) {
            cursor = cursor.minusDays(1);
        }
        int streak = 0;
        while (days.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }
}
```

### 7.8 `controller/TodoController.java`

```java
package com.lcz.aiagentstudy.controller;

import com.lcz.aiagentstudy.service.QuizService;
import com.lcz.aiagentstudy.service.QuizStore;
import com.lcz.aiagentstudy.service.TodoService;
import com.lcz.aiagentstudy.web.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class TodoController {
    private final CurrentUserResolver currentUserResolver;
    private final TodoService todoService;
    private final QuizService quizService;

    public record TodoItemView(String type, UUID knowledgeItemId, UUID conversationId,
                               String title, int count) {
    }

    public record TodoResponse(List<TodoItemView> items) {
    }

    @GetMapping("/todos")
    public TodoResponse todos(HttpServletRequest request) {
        UUID userId = currentUserResolver.requireUserId(request);
        List<TodoItemView> views = new ArrayList<>();
        for (TodoService.TodoItem item : todoService.list(userId)) {
            views.add(new TodoItemView(item.type(), item.knowledgeItemId(),
                    item.conversationId(), item.title(), item.count()));
        }
        return new TodoResponse(views);
    }

    @PostMapping("/review/start")
    public QuizResponse startReview(HttpServletRequest request) {
        UUID userId = currentUserResolver.requireUserId(request);
        return QuizResponse.of(quizService.startReview(userId));
    }
}
```

> 这里用 `QuizResponse.of(...)`，与 `QuizController.generate` 是同一个形状、同一份代码，见 4.8 的说明。

`QuizController` 再追加恢复接口：

```java
    public record QuestionStateView(long questionId, String kind, String target,
                                    String text, String verdict) {
    }

    public record ConversationQuizResponse(UUID conversationId, List<QuestionStateView> questions) {
    }

    @GetMapping("/conversations/{conversationId}")
    public ConversationQuizResponse conversation(@PathVariable UUID conversationId,
                                                 HttpServletRequest request) {
        UUID userId = currentUserResolver.requireUserId(request);
        List<QuestionStateView> views = new ArrayList<>();
        for (QuestionRepository.QuestionRow row : quizService.listByConversation(userId, conversationId)) {
            views.add(new QuestionStateView(row.id(), row.kind(), row.target(),
                    row.text(), row.verdict()));
        }
        return new ConversationQuizResponse(conversationId, views);
    }
```

### 7.9 `controller/ProgressController.java`

```java
package com.lcz.aiagentstudy.controller;

import com.lcz.aiagentstudy.service.ProgressService;
import com.lcz.aiagentstudy.web.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ProgressController {
    private final CurrentUserResolver currentUserResolver;
    private final ProgressService progressService;

    public record WeekView(String weekStart, int attempts) {
    }

    public record ProgressResponse(int masteredItems, int totalItems, int streakDays,
                                   int dueCount, List<WeekView> weeks) {
    }

    @GetMapping("/progress")
    public ProgressResponse progress(HttpServletRequest request) {
        UUID userId = currentUserResolver.requireUserId(request);
        ProgressService.ProgressView p = progressService.load(userId);
        return new ProgressResponse(p.masteredItems(), p.totalItems(), p.streakDays(),
                p.dueCount(),
                p.weeks().stream()
                        .map(w -> new WeekView(w.weekStart().toString(), w.attempts()))
                        .toList());
    }
}
```

### 7.10 验收

```powershell
# 1) 待办（先制造数据：答糊一道、再出一套题不答）
curl.exe -b "JSESSIONID=<你的>" http://localhost:8123/api/todos
# 期望：REVIEW（若有到期）、RETRY、UNTESTED 三类都能出现；没有的类不出现

# 2) 到期复习：先把某条排期改成已到期
```

```sql
update t_review_schedule set due_at = now() - interval '1 day'
 where question_id = <questionId>;
```

```powershell
curl.exe -i -b "JSESSIONID=<你的>" -X POST http://localhost:8123/api/review/start
# 期望：200；2 道来自到期项（题面与原来相同）+ 1 道新题（来自另一份资料）；
#       查库：该排期行只有一条，question_id 已迁到新题，status 仍是 PENDING（未作答就算欠着）

# 3) 已有未答完的复习时再点：应当回到同一轮（幂等），不新增行
curl.exe -i -b "JSESSIONID=<你的>" -X POST http://localhost:8123/api/review/start
# 期望：200；conversationId 与第 2 步相同，返回的全是「还没作答」的题；
#       查库：select count(*) from t_question; 行数不变

# 3b) 没有到期项、且最新一轮复习已答完 → 400
#     前置：把第 2 步这一轮的题全部答完（答完排期自动推到未来），
#     注意只把 due_at 推后拿不到 400：幂等分支先于到期判断，仍会返回同一轮未答题
curl.exe -i -b "JSESSIONID=<你的>" -X POST http://localhost:8123/api/review/start
# 期望：400 {"message":"现在没有到期需要复习的题"}

# 4) 进度
curl.exe -b "JSESSIONID=<你的>" http://localhost:8123/api/progress
# 期望：weeks 固定 8 项；streakDays 与真实作答日期对得上

# 5) 测验页恢复
curl.exe -b "JSESSIONID=<你的>" http://localhost:8123/api/quiz/conversations/<conversationId>
# 期望：每题的 verdict 是 null / PASS / PARTIAL / FAIL
```

**断言**：

| 断言                                              | 验的是什么                                                 |
| ------------------------------------------------- | ---------------------------------------------------------- |
| `REVIEW` 只出现一条、`count = 到期题数`           | 待办不是「按题罗列」，用户一眼知道欠多少                   |
| 复习会话里旧题的题面与新题**完全一致**            | 「重问」是真的重问，不是重新生成一道相近的题               |
| 旧排期行**只有一条**，且 `question_id` 已迁到新题 | 排期跟着题走，不产生重复排期                               |
| 复习答对后 `stage` 从 1 变 3                      | 启动复习时没有把 `stage` 一起作废（所以不能提前标 `DONE`） |
| `weeks` 长度恒为 8                                | 前端不用处理缺周                                           |
| 没有到期项时 `POST /review/start` 返回 400        | 不静默造一次空复习                                         |

***

## 8. 阶段 5：引导实践（`PRACTICE`，不判分）

> 对应 [product-spec.md 第 4 节](./product-spec.md)：`PRACTICE` 只用提示词，不产生成绩、不进复习闭环。

### 8.1 契约

| 方法 | 路径              | 请求                                                                        | 成功                               |
| ---- | ----------------- | --------------------------------------------------------------------------- | ---------------------------------- |
| POST | `/practice/guide` | `{"knowledgeItemId":"<uuid>","goal":"我想用它做个小工具"}`（`goal` 可省略） | `200 {"conversationId","content"}` |

`content` 是一份**可执行的练习指引**：一个小任务、验收标准、常见坑、材料里对应的章节。
它落库在 `mode = 'PRACTICE'` 的会话里（USER 消息 + ASSISTANT 消息），用户之后还能在这个会话里继续追问。

**为什么不判分**：实践的结果发生在系统外部（编译器、终端、真实用户），服务端看不见。
硬造一个「练习得分」等于编数据 —— 这条与 [decision-log.md 版本化修订 ①](./decision-log.md) 一致。

### 8.2 `service/PracticeStore.java`

```java
package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.model.ConversationMode;
import com.lcz.aiagentstudy.model.MessageRole;
import com.lcz.aiagentstudy.model.MessageType;
import com.lcz.aiagentstudy.repository.ConversationRepository;
import com.lcz.aiagentstudy.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PracticeStore {
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    public record Guide(UUID conversationId, String content) {
    }

    @Transactional
    public Guide save(UUID userId, String userMessage, String guideContent) {
        UUID conversationId = conversationRepository.create(userId, ConversationMode.PRACTICE.name());
        messageRepository.insert(conversationId, MessageRole.USER.name(), MessageType.CHAT.name(), userMessage);
        messageRepository.insert(conversationId, MessageRole.ASSISTANT.name(), MessageType.CHAT.name(), guideContent);
        conversationRepository.touch(conversationId);
        return new Guide(conversationId, guideContent);
    }
}
```

### 8.3 `service/PracticeService.java`

```java
package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.model.KnowledgeItemRow;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PracticeService {

    private static final String SYSTEM_PROMPT = """
            你是实践教练。用户学完一份材料，想把它用起来。你的任务不是讲知识，而是给一个能动手做的小任务。

            输出结构（用 markdown，四段，不要多）：
            1. 任务：一个 1 到 2 小时内能做完的具体动作，必须用到材料里的至少一个知识点。
            2. 验收标准：做完怎么判断成功，写成 2 到 3 条可勾选的检查项。
            3. 常见坑：2 到 3 条，写清楚会踩到什么、为什么。
            4. 对应材料：指出材料里哪一部分是完成任务必须回看的。

            规则：
            - 不写教程全文，不贴完整代码，让用户自己动手。
            - 任务要小到今晚就能开始；不要"做一个完整系统"这种空话。
            - 用户给了目标就围绕目标设计，没给就基于材料主题设计。
            """;

    // 材料太长时只截取前 8000 字做任务设计
    private static final int MATERIAL_LIMIT = 8000;

    private final ChatModel deepSeekChatModel;
    private final KnowledgeService knowledgeService;
    private final PracticeStore practiceStore;

    public PracticeStore.Guide guide(UUID userId, UUID knowledgeItemId, String goal) {
        KnowledgeItemRow item = knowledgeService.requireOwned(knowledgeItemId, userId);

        String userMessage = "材料标题：" + item.title()
                + "\n\n材料内容：\n" + truncate(item.content())
                + (goal == null || goal.isBlank() ? "" : "\n\n我的目标：" + goal.trim());

        ChatClient client = ChatClient.builder(deepSeekChatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .build();
        String content = client.prompt().user(userMessage).call().content();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("生成练习指引失败，请重试");
        }

        String storedUserMessage = (goal == null || goal.isBlank())
                ? "给我一个练习任务：" + item.title()
                : "我想这样练：" + goal.trim();
        return practiceStore.save(userId, storedUserMessage, content);
    }

    private static String truncate(String material) {
        if (material == null) {
            return "";
        }
        return material.length() <= MATERIAL_LIMIT
                ? material : material.substring(0, MATERIAL_LIMIT) + "\n……（材料已截断）";
    }
}
```

### 8.4 `controller/PracticeController.java`

```java
package com.lcz.aiagentstudy.controller;

import com.lcz.aiagentstudy.service.PracticeService;
import com.lcz.aiagentstudy.service.PracticeStore;
import com.lcz.aiagentstudy.web.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/practice")
@RequiredArgsConstructor
public class PracticeController {
    private final CurrentUserResolver currentUserResolver;
    private final PracticeService practiceService;

    public record GuideRequest(
            @NotNull(message = "knowledgeItemId 不能为空") UUID knowledgeItemId,
            @Size(max = 500, message = "目标最多 500 字") String goal) {
    }

    public record GuideResponse(UUID conversationId, String content) {
    }

    @PostMapping("/guide")
    public GuideResponse guide(@Valid @RequestBody GuideRequest req, HttpServletRequest request) {
        UUID userId = currentUserResolver.requireUserId(request);
        PracticeStore.Guide guide = practiceService.guide(userId, req.knowledgeItemId(), req.goal());
        return new GuideResponse(guide.conversationId(), guide.content());
    }
}
```

### 8.5 验收

```powershell
curl.exe -i -b "JSESSIONID=<你的>" -H "Content-Type: application/json" `
  -d '{\"knowledgeItemId\":\"<id>\",\"goal\":\"我想写个小脚本验证它\"}' `
  http://localhost:8123/api/practice/guide
# 期望：200；content 含四段（任务 / 验收标准 / 常见坑 / 对应材料）

# 查库
select mode from t_conversation where id = '<conversationId>';   -- PRACTICE
select role, type, left(content, 20) from t_message
 where conversation_id = '<conversationId>' order by id;          -- USER, ASSISTANT 两条
select count(*) from t_test_record where question_id in (
    select q.id from t_question q join t_message m on m.id = q.message_id
     where m.conversation_id = '<conversationId>');                -- 必须是 0
```

**断言**：`mode = PRACTICE`、消息两条、**没有产生任何成绩**。第三条最重要 —— 它证明「不判分」不是嘴上说说。

***

## 9. 阶段 6：工具 + RAG + 观测 + 评测

> 对应 [product-spec.md 第 4 节、第 12 节](./product-spec.md) 的「自由问答保留 RAG 与工具」「运行观测与离线评测」。
> 现有 `/api/ai/chat/stream` 调的是 `LoveApp.doChatByStream(message, chatId)` —— **既没有 RAG 也没有工具**。
> 本阶段做四件事：给流式接口加两个开关、把工具收敛到安全集合、给所有模型调用埋点、补两套离线评测。

### 9.1 契约（只改请求体，路径不变）

```json
POST /ai/chat/stream
{"conversationId":"...","message":"...","useRag":true,"useTools":false}
```

| 字段       | 默认    | 作用                                                       |
| ---------- | ------- | ---------------------------------------------------------- |
| `useRag`   | `false` | 检索该用户自己的资料（按 `userId` 元数据过滤），拼进上下文 |
| `useTools` | `false` | 允许调用**安全工具集**（联网搜索 / 网页抓取 / 当前时间）   |

**两个开关默认关**：默认路径仍是纯对话，成本最低、行为最可预测。前端把用户的选择记在本地。

### 9.2 **先做安全收口**：不要把 `allTools` 直接交给模型

`ToolRegistration.allTools` 里包含 `TerminalOperationTool`（真的会执行 `cmd.exe /c <命令>`）
和 `FileOperationTool`（真的会读写服务器文件）。把它们交给模型，等于给对话开了一个服务器 shell。
`allTools` 保留给本地实验用，**聊天主流程只注入一个新的安全集合**。

`ToolRegistration` 追加一个 bean：

```java
    /**
     * 聊天可用的安全工具集：只读外部信息，不碰服务器。
     * 刻意不包含 TerminalOperationTool / FileOperationTool / ResourceDownloadTool / PDFGenerationTool：
     * 这几个都会在服务器上产生副作用（执行命令、写文件），不该由一次聊天触发。
     */
    @Bean(name = "chatTools")
    public ToolCallback[] chatTools() {
        WebSearchTool webSearchTool = new WebSearchTool(tavilyApiKey);
        WebScrapingTool webScrapingTool = new WebScrapingTool();
        DateTimeTool dateTimeTool = new DateTimeTool();
        return ToolCallbacks.from(webSearchTool, webScrapingTool, dateTimeTool);
    }
```

### 9.3 `LoveApp` 追加带开关的流式方法

```java
    @Resource(name = "chatTools")
    private ToolCallback[] chatTools;

    /**
     * 带 RAG / 工具开关的流式对话。开关由前端传入，服务端不做"智能判断"——
     * 猜用户要不要检索，比让用户自己点一下更不可控。
     */
    public Flux<String> doChatByStream(String message, String chatId,
                                       UUID userId, boolean useRag, boolean useTools) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, chatId));

        if (useRag && loveAppVectorStore != null) {
            // 只检索这个用户自己的资料：向量入库时写了 userId 元数据（见 KnowledgeService.indexQuietly）
            spec = spec.advisors(QuestionAnswerAdvisor.builder(loveAppVectorStore)
                    .searchRequest(SearchRequest.builder()
                            .filterExpression("userId == '" + userId + "'")
                            .topK(5)
                            .build())
                    .build());
        }
        if (useTools) {
            spec = spec.toolCallbacks(chatTools);
        }
        return spec.stream().content();
    }
```

需要的 import：

```java
import org.springframework.ai.vectorstore.SearchRequest;
import java.util.UUID;
```

> 原有的两参 `doChatByStream(message, chatId)` **保留**，因为 `AiController` 的旧演示接口还在调它。
> 新方法只给 `/ai/chat/stream` 用。
>
> `LoveApp` 里 `@Autowired(required = false) private VectorStore loveAppVectorStore;` 这个字段名是历史遗留：
> `LoveAppVectorStoreConfig` 已整体注释，容器里只有 `PgVectorStoreConfig` 的 `pgVectorStore`。
> 按类型注入仍然拿得到 **`PgVectorStore`**。**不要改字段名** —— 改了会连带 `doChatWithRag` 一起动，
> 与本阶段无关。

**风险提示（写进技术债）**：DeepSeek 的流式 + 工具调用在 Spring AI 里依赖模型对 `tool_calls` 的分片返回。
如果实测发现「开着 `useTools` 流不完整」，就把该路径退回同步调用再流式转发，不要硬调参数。

### 9.4 `ChatService` 改签名

```java
    // 身份要透传下去：RAG 的过滤条件靠 userId，不能用 conversationId 代替
    public Flux<String> stream(UUID conversationId, String message, UUID userId,
                               boolean useRag, boolean useTools) {
        StringBuilder full = new StringBuilder();
        return loveApp.doChatByStream(message, conversationId.toString(), userId, useRag, useTools)
                .doOnNext(full::append)
                .doOnComplete(() -> messageService.appendRound(conversationId, message, full.toString()));
    }
```

> 这一版**先不接观测**，目的是让阶段 6 的开关先跑通。最终版在 9.8 第 ⑥ 条（会**替换掉本方法**），
> 到那一步再加 `AiCallRecorder`。不要先在 9.4 里加埋点，会编译不过（那时 `AiCallRecorder` 还没建）。

> **`ChatServiceTest` 里的两处 mock 要一起改**：
> `when(loveApp.doChatByStream(anyString(), anyString()))` 改成带 `any(UUID.class), anyBoolean(), anyBoolean()` 的五参版本，
> 调用处 `chatService.stream(conversationId, "问题")` 也要补上 `userId, false, false`。
> 测试不改会 NPE —— 这不是「测试过时」，是你的改动真的改变了契约，测试在提醒你。

### 9.5 `AiChatController` 改请求体与调用

```java
    public record StreamRequest(
            @NotNull(message = "conversationId 不能为空") UUID conversationId,
            @NotBlank(message = "message 不能为空")
            @Size(max = 4000, message = "message 过长") String message,
            Boolean useRag,
            Boolean useTools) {
    }

    // 方法体里改这一行：
    Disposable subscription = chatService.stream(
                    conversationId, req.message(), userId,
                    Boolean.TRUE.equals(req.useRag()),
                    Boolean.TRUE.equals(req.useTools()))
            .subscribe(...);
```

> `Boolean` 而不是 `boolean`：字段省略时是 `null`，`Boolean.TRUE.equals(null)` 为 `false`，
> 不需要在 record 里写默认值，也不会因为漏传字段抛反序列化错误。

### 9.6 观测表 `t_ai_call`

阶段 0 的 `db/schema.sql` 已经包含这张表（第 2.1 节第 6 段）。如果数据库是先跑过旧版
`schema.sql` 建的，单独把第 2.1 节第 6 段再执行一次即可建出这张表（旧表不会因此补列）。

三个字段的边界：

| 字段                                  | 谁能填              | 说明                                                         |
| ------------------------------------- | ------------------- | ------------------------------------------------------------ |
| `latency_ms`                          | 所有调用            | 必填，非流式 = 整次调用耗时；流式 = 从发请求到最后一个 chunk |
| `first_token_ms`                      | 只有流式            | 首 token 到达时间，这是用户真正感知的「卡不卡」              |
| `prompt_tokens` / `completion_tokens` | 能拿到 usage 的调用 | 拿不到就写 `NULL`，**不估算**                                |

### 9.7 `repository/AiCallRepository.java` + `service/AiCallRecorder.java`

```java
package com.lcz.aiagentstudy.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class AiCallRepository {
    private final JdbcTemplate jdbcTemplate;

    public record SceneMetrics(String scene,
                               long calls,
                               int avgMs,
                               int p95Ms,
                               Integer p95FirstTokenMs,
                               long promptTokens,
                               long completionTokens,
                               long failedCalls,
                               long callsWithoutUsage) {
    }

    public void insert(UUID userId, String scene, int latencyMs, Integer firstTokenMs,
                       Integer promptTokens, Integer completionTokens,
                       boolean success, String errorCode) {
        jdbcTemplate.update("""
                        insert into t_ai_call (user_id, scene, latency_ms, first_token_ms,
                                               prompt_tokens, completion_tokens, success, error_code)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                userId, scene, latencyMs, firstTokenMs,
                promptTokens, completionTokens, success, errorCode);
    }

    /**
     * 近 days 天、按场景聚合。percentile_cont 自动忽略 NULL：
     * 流式行没有 usage 也能参与延迟统计，不会被 token 缺失拖累。
     */
    public List<SceneMetrics> summary(UUID userId, int days) {
        return jdbcTemplate.query("""
                        select scene,
                               count(*)                                                              as calls,
                               coalesce(avg(latency_ms), 0)::int                                     as avg_ms,
                               coalesce(percentile_cont(0.95) within group (order by latency_ms), 0)::int as p95_ms,
                               percentile_cont(0.95) within group (order by first_token_ms)::int     as p95_first_token_ms,
                               coalesce(sum(prompt_tokens), 0)                                       as prompt_tokens,
                               coalesce(sum(completion_tokens), 0)                                   as completion_tokens,
                               count(*) filter (where not success)                                   as failed_calls,
                               count(*) filter (where prompt_tokens is null)                         as calls_without_usage
                          from t_ai_call
                         where user_id = ?
                           and created_at >= now() - make_interval(days => ?)
                         group by scene
                         order by calls desc
                        """,
                (rs, rowNum) -> {
                    Number p95First = (Number) rs.getObject("p95_first_token_ms");
                    return new SceneMetrics(
                            rs.getString("scene"),
                            rs.getLong("calls"),
                            rs.getInt("avg_ms"),
                            rs.getInt("p95_ms"),
                            p95First == null ? null : p95First.intValue(),
                            rs.getLong("prompt_tokens"),
                            rs.getLong("completion_tokens"),
                            rs.getLong("failed_calls"),
                            rs.getLong("calls_without_usage"));
                },
                userId, days);
    }
}
```

```java
package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.repository.AiCallRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 模型调用埋点：只做计时和把 usage 落库。
 * 观测失败绝不能影响业务 —— 写库异常在这里吞掉，只打日志。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiCallRecorder {
    private final AiCallRepository aiCallRepository;

    public long start() {
        return System.nanoTime();
    }

    /** 评测集传 userId = null：评测不是某个用户的使用行为，不写观测表。 */
    public void recordCall(UUID userId, String scene, long startNanos, ChatResponse response) {
        if (userId == null) {
            return;
        }
        Usage usage = response == null || response.getMetadata() == null
                ? null : response.getMetadata().getUsage();
        record(userId, scene, startNanos, null,
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(),
                true, null);
    }

    public void recordFailure(UUID userId, String scene, long startNanos, RuntimeException error) {
        if (userId == null) {
            return;
        }
        record(userId, scene, startNanos, null, null, null, false,
                error.getClass().getSimpleName());
    }

    /** 流式：首 token 时间由 doOnNext 首次触发时记录；拿不到 usage 就存 NULL。 */
    public void recordStream(UUID userId, String scene, long startNanos, Long firstTokenNanos,
                             boolean success, String errorCode) {
        if (userId == null) {
            return;
        }
        Integer firstTokenMs = firstTokenNanos == null
                ? null : (int) ((firstTokenNanos - startNanos) / 1_000_000);
        record(userId, scene, startNanos, firstTokenMs, null, null, success, errorCode);
    }

    private void record(UUID userId, String scene, long startNanos, Integer firstTokenMs,
                        Integer promptTokens, Integer completionTokens,
                        boolean success, String errorCode) {
        int latencyMs = (int) ((System.nanoTime() - startNanos) / 1_000_000);
        try {
            aiCallRepository.insert(userId, scene, latencyMs, firstTokenMs,
                    promptTokens, completionTokens, success, errorCode);
        } catch (RuntimeException e) {
            log.warn("写入 AI 调用观测失败，忽略: scene={}, cause={}", scene, e.getMessage());
        }
    }
}
```

> `Usage.getPromptTokens()` 返回的是可空的 `Integer`。DeepSeek 的非流式响应通常有 usage，
> 流式响应只有在请求带 `stream_options.include_usage=true` 时才有；**先跑一次看结果，不要假设**。

### 9.8 六处调用点接上埋点

每个文件先加一个依赖字段 `private final AiCallRecorder aiCallRecorder;`（`@RequiredArgsConstructor` 会自动进构造器），
再按下表改调用。**`entity()` 必须换成 `responseEntity()`**，否则拿不到 usage。

| 文件                                                                              | 场景名          | 改法                                                                       |
| --------------------------------------------------------------------------------- | --------------- | -------------------------------------------------------------------------- |
| [GradingService](file:///d:/code/Java/ai-agent-study/docs/mvp-backend-guide.md)   | `GRADE`         | `grade(...)` 增加 `UUID userId` 首参；`entity` → `responseEntity`          |
| [HelpService](file:///d:/code/Java/ai-agent-study/docs/mvp-backend-guide.md)      | `HELP`          | `answer(...)` 增加 `UUID userId` 首参；`content()` → `chatResponse()`      |
| [QuizService](file:///d:/code/Java/ai-agent-study/docs/mvp-backend-guide.md)      | `QUIZ_GENERATE` | `draft` / `draftOne` 增加 `UUID userId` 首参；`entity` → `responseEntity`  |
| [KnowledgeService](file:///d:/code/Java/ai-agent-study/docs/mvp-backend-guide.md) | `SUMMARY`       | `summarizeQuietly` 增加 `UUID userId` 首参；`content()` → `chatResponse()` |
| [PracticeService](file:///d:/code/Java/ai-agent-study/docs/mvp-backend-guide.md)  | `PRACTICE`      | `guide` 里 `content()` → `chatResponse()`                                  |
| [ChatService](file:///d:/code/Java/ai-agent-study/docs/mvp-backend-guide.md)      | `CHAT`          | 流式：记首 token 与总时长                                                  |

**① `GradingService.grade` 换掉整段方法**

```java
    // 只新增这一行；deepSeekChatModel 原本就有
    private final AiCallRecorder aiCallRecorder;

    public Grading grade(UUID userId, String material, String target, String kind,
                         String question, String answer) {
        ChatClient client = ChatClient.builder(deepSeekChatModel).defaultSystem(SYSTEM_PROMPT).build();

        String userMessage = "材料：\n" + truncate(material)
                + "\n\n考点：" + target
                + "\n任务类型：" + kind
                + "\n题目：" + question
                + "\n\n用户的作答：\n" + answer;

        long startedAt = aiCallRecorder.start();
        ResponseEntity<ChatResponse, GradingDraft> result;
        try {
            result = client.prompt().user(userMessage).call().responseEntity(GradingDraft.class);
        } catch (RuntimeException e) {
            aiCallRecorder.recordFailure(userId, "GRADE", startedAt, e);
            throw e;
        }
        aiCallRecorder.recordCall(userId, "GRADE", startedAt, result.getResponse());

        GradingDraft draft = result.getEntity();
        // ↓ 从这里开始与原来一致，不用改
        if (draft == null || draft.verdict() == null) {
            throw new IllegalStateException("判分失败，请重试");
        }
        // ……（原方法剩下的归一化逻辑原样保留）
        return new Grading(verdict, feedback, gaps);
    }
```

顶部补 import：

```java
import org.springframework.ai.chat.client.ResponseEntity;   // 注意不是 org.springframework.http.ResponseEntity
import org.springframework.ai.chat.model.ChatResponse;
import java.util.UUID;
```

**② `HelpService.answer` 换掉整段方法**

```java
    // 只新增这一行；deepSeekChatModel 原本就有
    private final AiCallRecorder aiCallRecorder;

    public String answer(UUID userId, String material, String question, String userQuestion) {
        ChatClient client = ChatClient.builder(deepSeekChatModel).defaultSystem(SYSTEM_PROMPT).build();
        String userMessage = "材料：\n" + truncate(material)
                + "\n\n题目：" + question
                + "\n\n我的问题：" + userQuestion;

        long startedAt = aiCallRecorder.start();
        ChatResponse response;
        try {
            response = client.prompt().user(userMessage).call().chatResponse();
        } catch (RuntimeException e) {
            aiCallRecorder.recordFailure(userId, "HELP", startedAt, e);
            throw e;
        }
        aiCallRecorder.recordCall(userId, "HELP", startedAt, response);

        String content = response == null || response.getResult() == null
                ? null : response.getResult().getOutput().getText();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("求助回答生成失败，请重试");
        }
        return content;
    }
```

**③ `QuizService`：`draft` / `draftOne` 加 `userId`，`entity` 换 `responseEntity`**

```java
    public Optional<TaskDraft> draftOne(UUID userId, KnowledgeItemRow item, List<String> verified) {
        try {
            return draft(userId, item.content(), 1, false, verified).stream().findFirst();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private List<TaskDraft> draft(UUID userId, String content, int count, boolean requireExplain,
                                  List<String> verified) {
        ChatClient client = ChatClient.builder(deepSeekChatModel).defaultSystem(SYSTEM_PROMPT).build();

        long startedAt = aiCallRecorder.start();
        ResponseEntity<ChatResponse, QuizDraft> result;
        try {
            result = client.prompt()
                    .user(buildUserMessage(truncate(content), count, requireExplain, verified))
                    .call()
                    .responseEntity(QuizDraft.class);
        } catch (RuntimeException e) {
            aiCallRecorder.recordFailure(userId, "QUIZ_GENERATE", startedAt, e);
            throw e;
        }
        aiCallRecorder.recordCall(userId, "QUIZ_GENERATE", startedAt, result.getResponse());

        QuizDraft draft = result.getEntity();
        // ↓ 原方法剩下的校验与归一化原样保留
        if (draft == null || draft.tasks() == null || draft.tasks().size() != count) {
            throw new IllegalStateException("模型未按要求返回 " + count + " 个任务，请重试");
        }
        // ……
        return normalized;
    }
```

连带改三处调用：

```java
// generate(...) 里
List<TaskDraft> tasks = draft(userId, item.content(), newCount, requireExplain, verified);

// startReview(...) 里
Optional<TaskDraft> draft = draftOne(userId, item, verified);

// attempt(...) 里
GradingService.Grading grading = gradingService.grade(
        userId, question.material(), question.target(), question.kind(),
        question.text(), answer);

// help(...) 里
String answer = helpService.answer(userId, question.material(), question.text(), userQuestion);
```

**④ `KnowledgeService.summarizeQuietly`**

```java
    public CreatedItem create(UUID userId, String title, String sourceUrl, String content) {
        String summary = summarizeQuietly(userId, content);
        // ……原逻辑不变
    }

    private String summarizeQuietly(UUID userId, String content) {
        long startedAt = aiCallRecorder.start();
        try {
            ChatClient client = ChatClient.builder(deepSeekChatModel).build();
            ChatResponse response = client.prompt()
                    .system("用不超过 200 字概括这份学习材料的主题与覆盖范围，只输出摘要本身，不要加标题。")
                    .user(content)
                    .call()
                    .chatResponse();
            aiCallRecorder.recordCall(userId, "SUMMARY", startedAt, response);

            String summary = response == null || response.getResult() == null
                    ? null : response.getResult().getOutput().getText();
            if (summary == null || summary.isBlank()) {
                return null;
            }
            String trimmed = summary.trim();
            return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300);
        } catch (RuntimeException e) {
            aiCallRecorder.recordFailure(userId, "SUMMARY", startedAt, e);
            log.warn("生成资料摘要失败，summary 置空: cause={}", e.getMessage());
            return null;
        }
    }
```

> 注意：`recordCall` 要在 `return` 之前执行。如果摘要为空直接 `return null`，这一行已经记过了，
> 不能把「模型正常返回空串」误记成失败。

**⑤ `PracticeService.guide` 里换掉调用两行**

```java
        long startedAt = aiCallRecorder.start();
        ChatResponse response;
        try {
            response = client.prompt().user(userMessage).call().chatResponse();
        } catch (RuntimeException e) {
            aiCallRecorder.recordFailure(userId, "PRACTICE", startedAt, e);
            throw e;
        }
        aiCallRecorder.recordCall(userId, "PRACTICE", startedAt, response);

        String content = response == null || response.getResult() == null
                ? null : response.getResult().getOutput().getText();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("生成练习指引失败，请重试");
        }
```

**⑥ `ChatService.stream` 记首 token 与总时长**

```java
package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.app.LoveApp;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class ChatService {
    private final LoveApp loveApp;
    private final MessageService messageService;
    private final AiCallRecorder aiCallRecorder;

    // 本方法不能标 @Transactional：Flux 是"装配说明"，标了方法返回时事务就提交，chunk 还在路上
    public Flux<String> stream(UUID conversationId, String message, UUID userId,
                               boolean useRag, boolean useTools) {
        StringBuilder full = new StringBuilder();
        AtomicReference<Long> firstTokenAt = new AtomicReference<>();
        long startedAt = aiCallRecorder.start();

        return loveApp.doChatByStream(message, conversationId.toString(), userId, useRag, useTools)
                .doOnNext(chunk -> {
                    firstTokenAt.compareAndSet(null, System.nanoTime());
                    full.append(chunk);
                })
                .doOnComplete(() -> {
                    messageService.appendRound(conversationId, message, full.toString());
                    aiCallRecorder.recordStream(userId, "CHAT", startedAt, firstTokenAt.get(), true, null);
                })
                .doOnError(e -> aiCallRecorder.recordStream(
                        userId, "CHAT", startedAt, firstTokenAt.get(), false, e.getClass().getSimpleName()));
    }
}
```

> `recordStream` 放在 `appendRound` 之后：落库失败时整条流已经完成，但 `doOnComplete` 抛异常后
> 后面的 `recordStream` 不会执行，于是观测表里少一行。这是有意的 —— 宁可少记，不记假的成功。

### 9.9 `service/MetricsService` + `controller/MetricsController`

```java
package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.repository.AiCallRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MetricsService {
    private final AiCallRepository aiCallRepository;

    public record Summary(int days,
                          List<AiCallRepository.SceneMetrics> scenes,
                          long totalCalls,
                          long totalPromptTokens,
                          long totalCompletionTokens,
                          long failedCalls,
                          long callsWithoutUsage) {
    }

    public Summary summary(UUID userId, int days) {
        List<AiCallRepository.SceneMetrics> scenes = aiCallRepository.summary(userId, days);
        return new Summary(
                days,
                scenes,
                scenes.stream().mapToLong(AiCallRepository.SceneMetrics::calls).sum(),
                scenes.stream().mapToLong(AiCallRepository.SceneMetrics::promptTokens).sum(),
                scenes.stream().mapToLong(AiCallRepository.SceneMetrics::completionTokens).sum(),
                scenes.stream().mapToLong(AiCallRepository.SceneMetrics::failedCalls).sum(),
                scenes.stream().mapToLong(AiCallRepository.SceneMetrics::callsWithoutUsage).sum());
    }
}
```

```java
package com.lcz.aiagentstudy.controller;

import com.lcz.aiagentstudy.exception.BadRequestException;
import com.lcz.aiagentstudy.service.MetricsService;
import com.lcz.aiagentstudy.web.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/metrics")
@RequiredArgsConstructor
public class MetricsController {
    private final CurrentUserResolver currentUserResolver;
    private final MetricsService metricsService;

    /** 只读汇总，直接回 Service 的记录，不再拷贝一层 DTO。 */
    @GetMapping("/summary")
    public MetricsService.Summary summary(@RequestParam(defaultValue = "7") int days,
                                          HttpServletRequest request) {
        UUID userId = currentUserResolver.requireUserId(request);
        if (days < 1 || days > 90) {
            throw new BadRequestException("days 必须在 1 到 90 之间");
        }
        return metricsService.summary(userId, days);
    }
}
```

### 9.10 上下文预算：别再按「条数」截聊天历史

现有 `MessageWindowChatMemory.maxMessages(10)` 的问题：10 条短消息会过早丢掉上下文，10 条长代码块又能到 2 万 token。

新增 `chatmemory/BudgetedChatMemory.java`：

```java
package com.lcz.aiagentstudy.chatmemory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * 按字符预算裁剪历史，而不是按条数。
 * 从最新一条往前累加，超预算就停；最新那一條无论多长都保留。
 */
@Slf4j
public class BudgetedChatMemory implements ChatMemory {

    private final ChatMemory delegate;
    private final int maxChars;

    public BudgetedChatMemory(ChatMemory delegate, int maxChars) {
        this.delegate = delegate;
        this.maxChars = maxChars;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        delegate.add(conversationId, messages);
    }

    @Override
    public void clear(String conversationId) {
        delegate.clear(conversationId);
    }

    @Override
    public List<Message> get(String conversationId) {
        List<Message> history = delegate.get(conversationId);
        if (history.isEmpty()) {
            return history;
        }
        List<Message> kept = new ArrayList<>(history.size());
        int chars = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            Message message = history.get(i);
            String text = message.getText();
            int length = text == null ? 0 : text.length();
            if (!kept.isEmpty() && chars + length > maxChars) {
                break;
            }
            kept.add(0, message);
            chars += length;
        }
        if (kept.size() < history.size()) {
            log.debug("聊天历史按预算裁剪：保留 {}/{} 条，{} 字符（预算 {}）",
                    kept.size(), history.size(), chars, maxChars);
        }
        return kept;
    }
}
```

`LoveApp` 构造器改三行（`maxMessages` 放宽到 30，真正的闸门交给预算）：

```java
    public LoveApp(ChatModel deepSeekChatModel,
                   @Value("${app.ai.context.max-history-chars:8000}") int maxHistoryChars) {
        String fileDir = System.getProperty("user.dir") + "/tmp/chat-memory";

        ChatMemory base = MessageWindowChatMemory
                .builder()
                .chatMemoryRepository(new FileBasedChatMemory(fileDir))
                .maxMessages(30)
                .build();
        ChatMemory chatMemory = new BudgetedChatMemory(base, maxHistoryChars);

        chatClient = ChatClient.builder(deepSeekChatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new MyLoggerAdvisor())
                .build();
    }
```

补 import：

```java
import com.lcz.aiagentstudy.chatmemory.BudgetedChatMemory;
import org.springframework.beans.factory.annotation.Value;
```

配置（`application.yml` 或 `application.properties` 选你现有格式）：

```yaml
app:
  ai:
    context:
      max-history-chars: 8000
```

> **不要**把 8000 理解成 token。字符预算只是保守近似：中文约 1 字 ≈ 1 token，英文约 4 字符 ≈ 1 token。
> 真要按 token 精确裁剪，得引入分词器，本轮不做（写进技术债）。

### 9.11 离线评测：判分与 RAG

出题评测在阶段 2.5 已经有了。这里再补两套，都是**测试类**，不新增线上接口。

**① `GradingEvalTest`：判分稳定性和与人工标注的一致率**

```java
package com.lcz.aiagentstudy.service;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 判分评测：每条跑 3 次，看同一答案是否稳定得到同一个 verdict，
 * 以及这个 verdict 是否与人工标注一致。
 * 跑一次 = 6 条 × 3 次 = 18 次模型调用，别在每次构建里跑。
 */
@SpringBootTest
class GradingEvalTest {

    @Resource
    private GradingService gradingService;

    private static final String MATERIAL = """
            HNSW 是分层可导航小世界图索引。每个节点在每一层最多保留 m 个邻居，
            第 0 层邻居数通常是 2m。m 越大，图的连通性越好、召回越高，
            但内存占用和构图时间也线性上升。ef_construction 控制构图时每层候选队列的长度，
            ef_search 控制查询时候选队列的长度；前者影响索引质量，后者影响查询召回与延迟。
            """;

    record Case(String target, String kind, String question, String answer, String expected) {
    }

    private static final List<Case> CASES = List.of(
            new Case("HNSW 的 m 参数", "FACT", "HNSW 的 m 参数控制什么？",
                    "控制每个节点在每层保留的最大邻居数。m 越大图越连通，召回更高，"
                            + "但内存和构图时间都会上升；第 0 层一般是 2m。", "PASS"),
            new Case("HNSW 的 m 参数", "FACT", "HNSW 的 m 参数控制什么？",
                    "控制邻居数量。", "PARTIAL"),
            new Case("HNSW 的 m 参数", "FACT", "HNSW 的 m 参数控制什么？",
                    "控制查询时候选队列的长度，调大召回更高。", "FAIL"),
            new Case("HNSW 的分层结构", "EXPLAIN",
                    "假设对方只会 Python、没听过向量检索，把 HNSW 为什么快讲给他听。",
                    "它先建好几层图，最上层很稀疏，像高速公路；越往下节点越多，像市区小路。"
                            + "查询时从最上层随便一个点出发，每次只走到当前点附近更近的邻居，"
                            + "走到走不动了就下一层继续找。因为每层只走几条边，总步数很少，"
                            + "所以比把所有向量逐个算距离快。m 决定每个点最多连几个邻居，"
                            + "连得多不容易走丢，但占内存。", "PASS"),
            new Case("HNSW 的分层结构", "EXPLAIN",
                    "假设对方只会 Python、没听过向量检索，把 HNSW 为什么快讲给他听。",
                    "它用了分层图和贪心搜索，所以快。", "PARTIAL"),
            new Case("HNSW 的分层结构", "EXPLAIN",
                    "假设对方只会 Python、没听过向量检索，把 HNSW 为什么快讲给他听。",
                    "因为 HNSW 是近似最近邻算法，复杂度比暴力搜索低。", "FAIL")
    );

    @Test
    void gradingShouldBeStableAndMatchLabels() {
        List<String> unstable = new ArrayList<>();
        int matched = 0;

        for (Case c : CASES) {
            String first = null;
            boolean same = true;
            for (int i = 0; i < 3; i++) {
                String verdict = gradingService.grade(
                        null, MATERIAL, c.target(), c.kind(), c.question(), c.answer()).verdict();
                if (first == null) {
                    first = verdict;
                } else if (!first.equals(verdict)) {
                    same = false;
                }
            }
            if (!same) {
                unstable.add(c.target() + " / " + c.expected());
            }
            if (same && first.equals(c.expected())) {
                matched++;
            }
        }

        System.out.printf("判分评测：稳定 %d/%d，与标注一致 %d/%d，不稳定=%s%n",
                CASES.size() - unstable.size(), CASES.size(), matched, CASES.size(), unstable);

        assertTrue(CASES.size() - unstable.size() >= 5, "判分稳定性不足: " + unstable);
        assertTrue(matched >= 4, "与人工标注一致率不足 4/6");
    }
}
```

> 这个测试**故意传 `userId = null`**：评测不是用户的使用行为，不写 `t_ai_call`。

**② `RagEvalTest`：检索命中率（需要 PostgreSQL + pgvector 已启动）**

```java
package com.lcz.aiagentstudy.rag;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 评测：用固定语料 + userId 过滤，检查 topK=5 是否命中预期文档。
 * 用完把测试向量删掉，避免污染真实资料的检索结果。
 */
@SpringBootTest(properties = "app.pgvector.enabled=true")
class RagEvalTest {

    @Resource
    private VectorStore vectorStore;

    private static final UUID EVAL_USER =
            UUID.nameUUIDFromBytes("rag-eval-user".getBytes(StandardCharsets.UTF_8));

    record Doc(String title, String text) {
    }

    record Case(String query, String expectedTitle) {
    }

    private static final List<Doc> DOCS = List.of(
            new Doc("HNSW", "HNSW 是分层可导航小世界图索引，每个节点每层保留 m 个邻居，"
                    + "查询从上层往底层贪心走。"),
            new Doc("距离度量", "余弦距离只看方向不看长度，适合文本向量；欧氏距离受向量长度影响。"),
            new Doc("文本分片", "分片时相邻片段要保留重叠字符，否则跨片段的句子会被切断，检索时丢上下文。"),
            new Doc("重排", "重排是在向量召回之后，用更贵的模型对少量候选重新打分，提高 top 结果的相关性。"),
            new Doc("IVFFlat", "IVFFlat 先把向量聚成若干簇，查询时只扫最近的几个簇，建索引比 HNSW 快但召回略低。")
    );

    private static final List<Case> CASES = List.of(
            new Case("HNSW 每个节点保留多少个邻居", "HNSW"),
            new Case("文本相似度为什么用余弦而不是欧氏距离", "距离度量"),
            new Case("切片的时候为什么要让相邻块重叠", "文本分片"),
            new Case("召回之后为什么还要 rerank", "重排"),
            new Case("pgvector 里 IVFFlat 索引适合什么场景", "IVFFlat")
    );

    @Test
    void retrievalShouldHitExpectedDocument() {
        List<String> ids = DOCS.stream()
                .map(doc -> UUID.nameUUIDFromBytes(("rag-eval-" + doc.title()).getBytes(StandardCharsets.UTF_8)))
                .map(UUID::toString)
                .toList();

        for (int i = 0; i < DOCS.size(); i++) {
            Doc doc = DOCS.get(i);
            vectorStore.add(List.of(Document.builder()
                    .id(ids.get(i))
                    .text(doc.text())
                    .metadata("userId", EVAL_USER.toString())
                    .metadata("title", doc.title())
                    .build()));
        }

        try {
            int hit = 0;
            for (Case c : CASES) {
                List<Document> found = vectorStore.similaritySearch(SearchRequest.builder()
                        .query(c.query())
                        .topK(5)
                        .filterExpression("userId == '" + EVAL_USER + "'")
                        .build());
                boolean ok = found != null && found.stream()
                        .anyMatch(doc -> c.expectedTitle().equals(doc.getMetadata().get("title")));
                if (ok) {
                    hit++;
                } else {
                    System.out.println("未命中: " + c.query());
                }
            }
            System.out.printf("RAG 评测：命中 %d/%d%n", hit, CASES.size());
            assertTrue(hit >= 4, "RAG top5 命中率不足 4/5");
        } finally {
            vectorStore.delete(ids);
        }
    }
}
```

> 这个测试只验证「检索能不能找到」，不验证「回答好不好」。回答质量靠阶段 7 的人工读。
> 如果 `vectorStore.delete` 在你的 Spring AI 版本里签名不同，按 IDE 提示改成 `delete(List<String>)`
> 或 `delete(FilterExpression)` 即可 —— 这是唯一需要现场看签名的地方。

### 9.12 验收

```powershell
# 1) 两个开关关：与旧行为一致
curl.exe -N -b "JSESSIONID=<你的>" -H "Content-Type: application/json" `
  -d '{\"conversationId\":\"<id>\",\"message\":\"HNSW 的 m 参数是什么\"}' `
  http://localhost:8123/api/ai/chat/stream

# 2) RAG 开：同一个问题，回答应该带上你上传的材料里的说法
curl.exe -N -b "JSESSIONID=<你的>" -H "Content-Type: application/json" `
  -d '{\"conversationId\":\"<id>\",\"message\":\"HNSW 的 m 参数是什么\",\"useRag\":true}' `
  http://localhost:8123/api/ai/chat/stream
# 期望：回答内容与你材料里的表述一致（不是通用百科腔）

# 3) 工具开：问一个必须查时间的问题
curl.exe -N -b "JSESSIONID=<你的>" -H "Content-Type: application/json" `
  -d '{\"conversationId\":\"<id>\",\"message\":\"现在几点\",\"useTools\":true}' `
  http://localhost:8123/api/ai/chat/stream
# 期望：回答里的时间与当前时间一致（DateTimeTool 被调用）

# 4) 跨用户隔离：用 B 账号开 useRag 问 A 的资料主题
# 期望：回答里不出现 A 资料的具体表述（filterExpression 生效）

# 5) 观测：连发 3 次聊天后看汇总（days 默认 7）
curl.exe -b "JSESSIONID=<你的>" "http://localhost:8123/api/metrics/summary?days=7"
# 期望：scenes 里有 CHAT；calls >= 3；p95FirstTokenMs 不为 null；
#      promptTokens 可能为 0（流式没 usage），callsWithoutUsage 会等于 calls

# 6) 非流式场景：出一次题、答一次，再看汇总
# 期望：QUIZ_GENERATE / GRADE 两行；promptTokens > 0；callsWithoutUsage = 0
```

```sql
-- 观测表逐行看一眼：CHAT 行 first_token_ms 有值、token 为空；GRADE 行反过来
select scene, latency_ms, first_token_ms, prompt_tokens, completion_tokens,
       success, error_code, created_at
  from t_ai_call
 where user_id = '<用户id>'
 order by id desc
 limit 20;
```

**上下文预算验收**：连着聊 12 轮（每轮回答让它多写点），看日志出现
`聊天历史按预算裁剪：保留 x/y 条`，并且第 12 轮仍能答上第 1 轮提到的事实之外的内容。
把 `app.ai.context.max-history-chars` 调到 `500` 再聊一次，日志里的保留条数应明显变少 —— 这证明预算真的生效，不是恰好没触发。

**离线评测验收**：

```powershell
# 出题评测（阶段 2.5 已有）
mvn -Dtest=QuizPromptEvalTest test
# 判分评测：18 次真实模型调用，看控制台打印
mvn -Dtest=GradingEvalTest test
# RAG 评测：需要 PostgreSQL + pgvector 已启动
mvn -Dtest=RagEvalTest test
```

**断言**：

| 断言                                                           | 验的是什么                                            |
| -------------------------------------------------------------- | ----------------------------------------------------- |
| 开关全关时行为与改动前一致                                     | 没有破坏 ④格已验收的流式落库                          |
| `useRag=true` 的回答能引用材料原话                             | 过滤表达式与元数据 key 对得上（`userId`）             |
| B 账号检索不到 A 的资料                                        | 用户隔离，向量库不是公共库                            |
| `useTools=true` 能答出当前时间                                 | 安全工具集真的被注册进去了                            |
| 只有 3 个工具（`chatTools`）                                   | 服务器上能执行命令的 `TerminalOperationTool` 没被暴露 |
| `t_ai_call` 里六种 `scene` 都能出现至少一次                    | 六处调用点全接上了，没有漏                            |
| `CHAT` 行的 `first_token_ms` 有值                              | 首 token 计时真的触发在第一个 chunk                   |
| 流式行 `prompt_tokens` 为 `NULL` 时 `callsWithoutUsage` 会计数 | 没 usage 就是没 usage，没有拿估算值充数               |
| 观测写库失败不影响聊天                                         | `AiCallRecorder.record` 吞异常只打日志                |
| 预算调到 500 后保留条数明显变少                                | 裁剪逻辑真的执行，配置真的注入                        |
| `GradingEvalTest` 稳定 ≥ 5/6、一致 ≥ 4/6                       | 判分不是「每次说的都不一样」                          |
| `RagEvalTest` 命中 ≥ 4/5                                       | 检索链路（embedding → 检索 → 过滤）可用               |

***

## 10. 阶段 7：用真实材料跑一遍闭环（唯一的完工标准）

> 前面每个阶段都只是「接口通了」。这一阶段用**你自己真的要学的一份材料**从头走到尾，
> 每一步都把要看的数据贴出来。跑通一次，产品才成立。

### 10.1 脚本（按顺序做，不要跳）

| 步  | 动作                                       | 看什么                                                      |
| --- | ------------------------------------------ | ----------------------------------------------------------- |
| 1   | 上传一份你真的想学的材料（`.md` / `.pdf`） | `preview` 可读；`summary` 说得像人话                        |
| 2   | `POST /quiz/generate`                      | 3 个任务、至少 1 个 `EXPLAIN`；题面**没有答案**             |
| 3   | 第 1 题**故意答糊**（只说结论、跳过因果）  | `verdict` = `PARTIAL` / `FAIL`；`gaps` 指出缺口而不是给答案 |
| 4   | 对同一题**补讲一次**（把因果讲清）         | `attemptNo = 2`；`retryAllowed = false`；`verdict` 上升     |
| 5   | 把该题排期改成已到期（SQL）                | 见 10.2                                                     |
| 6   | `GET /todos`                               | 出现 `REVIEW`，`count` 与到期题数一致                       |
| 7   | `POST /review/start`                       | 2 道重问的题面与第 3 步**逐字相同** + 1 道交错题            |
| 8   | 答对复习题                                 | `stage` 从 1 → 3，`nextDueAt` ≈ 3 天后                      |
| 9   | `GET /progress`                            | `masteredItems` 只有在该资料下所有题都 PASS 后才 +1         |
| 10  | `GET /metrics/summary`                     | 六种 `scene` 都出现；`CHAT` 有 `p95FirstTokenMs`            |

### 10.2 改排期到期（模拟「过了 3 天」）

**不要**真的等 3 天，也不要改 `stage` 伪造 —— 直接把 `due_at` 挪到过去，这是最接近真实的一次调度触发：

```sql
update t_review_schedule
   set due_at = now() - interval '1 hour'
 where question_id = <第 4 步那道题>
   and status = 'PENDING';
```

### 10.3 一次跑完后的数据核对

```sql
-- 资料 / 题目 / 成绩 / 排期，四张表对得上
select ki.title,
       count(distinct q.id)                                    as questions,
       count(tr.id)                                            as attempts,
       count(distinct q.id) filter (where tr.verdict='PASS')   as passed_questions
  from t_knowledge_item ki
  left join t_question q       on q.knowledge_item_id = ki.id
  left join t_test_record tr   on tr.question_id = q.id
 where ki.user_id = '<你的 userId>'
 group by ki.title;

-- 每道题只有一行排期
select count(*) as total,
       count(distinct question_id) as distinct_questions
  from t_review_schedule where user_id = '<你的 userId>';   -- 两列必须相等

-- 知识点是否升级
select name, status from t_knowledge_point where user_id = '<你的 userId>';
```

### 10.4 完工判据（全部满足才算 MVP 结束）

- [ ] 一次闭环里 `t_knowledge_item` → `t_question` → `t_test_record` → `t_review_schedule` 四张表都有数据
- [ ] 补讲只允许 1 次（`attempt_no` 最大为 2）
- [ ] 复习重问的题面与首答时逐字一致
- [ ] 每道题在 `t_review_schedule` 只有一行
- [ ] 判分从不给答案（人工读 5 条 `feedback` 确认）
- [ ] `PRACTICE` 会话没有成绩
- [ ] 两个账号之间资料与题目互相不可见
- [ ] 出题评测通过率 ≥ 7/10 并已抄进 [impl-log.md](./impl-log.md)
- [ ] 判分评测稳定性 ≥ 5/6、与人工标注一致 ≥ 4/6，数字已抄进 [impl-log.md](./impl-log.md)
- [ ] RAG 评测 top5 命中 ≥ 4/5
- [ ] `t_ai_call` 六种 `scene` 都有行，且流式行的 token 要么有值、要么明确为 `NULL`（不许填估算值）

***

## 11. 前端清单（我写，你只需要给 JSON）

按依赖顺序，每块前端只依赖它上面那一阶段的接口。**不要同时开三块**。

| #   | 页面 / 组件         | 路由                               | 依赖接口                                                                                          | 关键交互                                                                                                                                                                                                                                                                    |
| --- | ------------------- | ---------------------------------- | ------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| ①   | `MaterialsView.vue` | `/materials`                       | `GET /knowledge-items`、`POST /knowledge-items`、`POST /knowledge-items/upload`                   | 粘贴框 + 拖拽上传（`.md`/`.txt`/`.pdf`）；创建成功后弹出 `preview` 前 200 字，让用户确认「抽出来的字对不对」；**上传前前端先拦一次 <100 字与 >5 MB**（后端同文案见 3.2，别让用户传完才被打回）；卡片上两个按钮：「考考我」→ `/quiz/generate`、「怎么练」→ `/practice/guide` |
| ②   | `QuizView.vue`      | `/quiz/:conversationId`            | `GET /quiz/conversations/{id}`、`POST /quiz/{questionId}/attempt`、`POST /quiz/{questionId}/help` | 一屏一题；`EXPLAIN` 题显示听众设定；作答框下方「我卡住了，给点提示」→ 弹输入框调 `/help` 拿回答（不把答案塞进作答框）；提交后展示 `feedback` + `gaps` 清单；`retryAllowed` 为真时出现「我再讲一遍」                                                                         |
| ③   | `TodoSidebar.vue`   | 挂在 `/guide` 左侧栏，会话列表上方 | `GET /todos`                                                                                      | 三类分组；`REVIEW` 置顶且高亮；点 `REVIEW` → `/review`，点 `RETRY` → 调 `/quiz/generate`，点 `UNTESTED` → `/quiz/{conversationId}`                                                                                                                                          |
| ④   | `ProgressView.vue`  | `/progress`                        | `GET /progress`                                                                                   | 三个数字卡 + 近 8 周柱状图；不做徽章、不做排行榜                                                                                                                                                                                                                            |
| ⑤   | `ChatRoom.vue` 改造 | `/guide/:conversationId`           | `POST /ai/chat/stream` 加两个字段                                                                 | 输入框上方两个开关（检索我的资料 / 联网搜索），状态存 `localStorage`；请求体带上 `useRag` / `useTools`                                                                                                                                                                      |
| ⑥   | `MetricsView.vue`   | `/metrics`                         | `GET /metrics/summary?days=7`                                                                     | 六行场景表：调用次数 / 平均延迟 / P95 延迟 / P95 首 token / token 消耗 / 失败数；`callsWithoutUsage > 0` 的区域标注「流式调用未返回用量」；不做图表                                                                                                                         |

**交接物只有一件事**：把每个接口**真实返回的 JSON 原文**发我（至少 1 条）。
我按你的字段名写前端，不按本文档的命名猜 —— 你手敲时把 `retryAllowed` 写成 `canRetry` 都行，我照抄。

> **左侧会话列表要按 `mode` 分流**：`GET /conversations` 的每一项已经带 `mode`。
> `SELF_TEST` / `REVIEW` 点进去是 `/quiz/{conversationId}`，`FREE_CHAT` / `PRACTICE` 才是 `/guide/{conversationId}`。
> 不分流会出现「点测验会话却进了聊天页，只看到一句『请考考我』」。

***

## 12. 技术债（明知不做，写在这里防止面试被问倒）

| #   | 债                                                                                                                                        | 为什么现在不还                                                                                                                                                                                                                                             | 触发还债的条件                                                                                  |
| --- | ----------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| 1   | 出题与判分依赖 `entity()` 结构化输出，解析失败直接 500                                                                                    | 先跑通、先有通过率数字                                                                                                                                                                                                                                     | 评测集通过率 < 70%，或线上出现 3 次解析失败                                                     |
| 2   | 流式 + 工具调用（`useTools=true`）未验证兼容性                                                                                            | DeepSeek 的分片 `tool_calls` 行为要先实测                                                                                                                                                                                                                  | 实测流不完整 → 改同步调用后转发                                                                 |
| 3   | 一道题只记一个主知识点                                                                                                                    | 产品上 `target` 就是一个名词短语                                                                                                                                                                                                                           | 出现「一题多考点」的真实场景                                                                    |
| 4   | 知识点只升不降：`FAIL` 不把 `VERIFIED` 降回 `CANDIDATE`                                                                                   | 降级会让「已掌握」反复跳变，误导用户                                                                                                                                                                                                                       | 需要更细的掌握度分数时                                                                          |
| 5   | PDF 正文不修排版（双栏 / 页眉页脚 / 公式都会留噪声）                                                                                      | 修排版是另一个量级的工作                                                                                                                                                                                                                                   | 章节摘要与阅读页明显不可读且用户真实反馈                                                        |
| 6   | 扫描件 PDF 不支持（无 OCR）                                                                                                               | OCR 要额外依赖与模型                                                                                                                                                                                                                                       | 用户真的只有扫描件                                                                              |
| 7   | 单节正文超过 12000 字时按句末标点硬切成多个章节，可能把一个论证切断                                                                       | 保「每章 ≤ 12000 字」优先，规则再细就要引入语义分段                                                                                                                                                                                                        | 用户反馈「题目考的是后半段，但前半段没读完」                                                    |
| 8   | 上传的原文件不落盘，`source_url` 只存文件名                                                                                               | 只存抽取后的文本已够出题                                                                                                                                                                                                                                   | 需要「下载原文」或「重新解析」                                                                  |
| 9   | RAG 固定 `topK=5`，无重排、无相似度阈值调优                                                                                               | 无评测集，调参等于凭感觉                                                                                                                                                                                                                                   | 有一个「检索命中」评测集                                                                        |
| 10  | 复习一次只取 2 道到期题，其余顺延                                                                                                         | 产品规则就是一次 3 题 / 降认知负荷                                                                                                                                                                                                                         | 用户积压严重时给「一次清空」                                                                    |
| 11  | 交错题只出 1 道，且「另一份文档」是随机取的                                                                                               | 知识网络的最小形态，不求准                                                                                                                                                                                                                                 | 需要按知识点距离选交错章节                                                                      |
| 12  | 进度里的「已掌握章节」= 该章 `SELF_TEST` 题全部答过且最近一次全 `PASS`                                                                    | 一章只有 3 道题，口径已经比「答对一题就点亮」严得多                                                                                                                                                                                                         | 单章题目数 > 5 时再校准                                                                         |
| 13  | `PRACTICE` 会话的后续追问走通用聊天提示词                                                                                                 | 引导语只在首轮注入                                                                                                                                                                                                                                         | 用户反馈「练着练着又变成讲知识」                                                                |
| 14  | 出题会话的标题是「请考考我：<资料名>」，来自首条 USER 消息                                                                                | 复用既有派生标题逻辑，不新增字段                                                                                                                                                                                                                           | 左侧列表的测验会话与聊天会话混得看不清                                                          |
| 15  | 连点两次「开始复习」会生成两套同一批题（排期行仍是 `PENDING`，会被再取一次）                                                              | 前端在请求返回前禁用按钮就能挡住正常用户                                                                                                                                                                                                                   | 出现真实的重复复习数据                                                                          |
| 16  | 复习会话中途放弃，排期行已迁到新题、`due_at` 仍停在过去                                                                                   | 这样待办不会假装「已复习」，是诚实的状态                                                                                                                                                                                                                   | 同一题被复制超过 3 次                                                                           |
| 17  | 作答与判分只存 `t_test_record`，不写进 `t_message`（`ANSWER` / `FEEDBACK` 两个枚举值暂时没人用）                                          | 测验页有自己的恢复接口 `/quiz/conversations/{id}`，不需要混进聊天记录；写进去反而会让左侧会话列表的聊天流里混着问答                                                                                                                                        | 需要在同一个会话里连续看到「题 → 答 → 判分」的完整时间线                                        |
| 18  | 没有删除资料 / 作废题目的接口                                                                                                             | 个人项目，上传错材料或不想做的题直接跑 SQL 清理（见 13.4）                                                                                                                                                                                                 | 要给别人用、或数据量大了再补接口                                                                |
| 19  | 流式聊天的 token 用量可能拿不到（DeepSeek 需 `stream_options.include_usage`），于是 `t_ai_call` 里 `CHAT` 行的 token 为 `NULL`            | 不估算、不造假；延迟和首 token 仍然准确                                                                                                                                                                                                                    | 需要精确按 token 计费时，改用非流式或直连 HTTP 客户端补参数                                     |
| 20  | 上下文按「字符」而非「token」裁剪，`max-history-chars=8000` 只是近似                                                                      | 引入分词器要多一个依赖，收益在本项目规模下不明显                                                                                                                                                                                                           | 出现「明明没超预算却超上下文」的错误                                                            |
| 21  | 不做滚动摘要：早期对话直接被裁掉，不做压缩留存                                                                                            | 每次压缩都要多一次模型调用，与降成本目标冲突                                                                                                                                                                                                               | 用户抱怨「AI 忘了很久以前说过的设定」                                                           |
| 22  | 评测只测「稳定性」和「检索命中」，不测回答正确性                                                                                          | 判分没有客观标准答案，只能靠人工标注做参照                                                                                                                                                                                                                 | 有第二个人愿意一起标注时才扩展                                                                  |
| 23  | `t_ai_call` 只记生成调用，不记 RAG 的 embedding 调用                                                                                      | embedding 走 `EmbeddingModel`，没有 `ChatResponse`，要单独埋点；且按字符计费、单次很便宜                                                                                                                                                                   | 需要把成本算到「每一分钱」时                                                                    |
| 24  | 不引入 `com.itextpdf:bouncy-castle-adapter`，**带权限加密的 PDF 一律解析失败**                                                            | 目标场景是「用户自己整理好的讲义」，优先控制依赖体积；失败文案已覆盖（`PDF 解析失败，请确认文件未加密且格式正常`）。代价是出版社导出的、用阅读器能正常打开但带权限密码的 PDF 会被挡下。日志里 `BouncyCastleFactoryCreator` 的 ERROR 属于这一分支，不是崩溃 | 真实用户开始上传讲义并反馈被拒时，加 `bouncy-castle-adapter:9.1.0`（约 9 MB）即可解决           |
| 25  | `gaps` 可能把答案说出来：`GradingService.SYSTEM_PROMPT` 只约束了 `feedback`，实测 `gaps` 出现过「如 2m 这一常见设置」                     | **已修（2026-10-22）**：第 2 条改为「只描述缺了哪一环或哪一步没说清，可以给思考方向，不得出现数值、公式、术语定义、结论，也不要举例」；口径依据见 `product-spec.md` 第 6 节                                                                                | 每轮改提示词后，人工看一条 `FAIL` 响应的 `gaps`                                                 |
| 26  | 「放弃的那一轮复习」没有出口：幂等分支只认最新一条 `REVIEW` 会话，只要其中有未作答题，`POST /review/start` 就一直返回那一轮，用户没法跳过 | 断网重试不丢题的价值更高，跳过会带来「到期题被无限推迟」的新问题；先接受「必须答完当前一轮」                                                                                                                                                               | 用户抱怨被一道题卡住时，加「放弃本轮」（把该轮未答题的排期状态改掉，或给会话加 `abandoned_at`） |
| 27  | `DocumentSplitter.chunks()` 每次切满 `SECTION_MAX`（12000），超长单节的尾块可能只有几十字，单独成一章；实测 31 节 × 12100 字被算成 62 章，整份文档因此撞上「30 章」上限被 `400` 拒绝 | 真实材料里单节超过 12000 字的情况很少（12100 字只出现在专为造超限而拼的测试文档里），为一个边界重启后端不划算；尾块本身不影响出题正确性                                                                                                                                                                     | 出现真实材料因碎片章被拒时，按 3.8「改动 2 补充」改 `chunks()`（3 行版：尾块 < `SECTION_MIN` 就并回上一块；严格版：按段数均分） |

> 第 15、16 条是同一个取舍的两面：**排期在「点开复习」时就跟着题走了，而不是等答完再走**。
> 好处是答完能正确递进、放弃不会丢账。原先的代价是重复触发会留下重复题，已用幂等解决：
> `QuizService.startReview` 先查最新一条 `mode='REVIEW'` 会话，其中有未作答题就原样返回那批题。
> 剩余代价是**放弃的那一轮没有出口**——必须把那批题答完才能开新一轮（见第 26 条）。

***

## 13. 速查

### 13.1 接口总表

| 阶段 | 方法 | 路径                                   | 一句话                                   |
| ---- | ---- | -------------------------------------- | ---------------------------------------- |
| 1    | POST | `/knowledge-items`                     | 粘贴导入                                 |
| 1    | POST | `/knowledge-items/upload`              | 上传 `.md` / `.txt` / `.pdf`             |
| 1    | GET  | `/knowledge-items`                     | 我的资料列表                             |
| 2    | POST | `/quiz/generate`                       | 从资料出 3 道题                          |
| 3    | POST | `/quiz/{questionId}/attempt`           | 交作答、拿判分                           |
| 3    | POST | `/quiz/{questionId}/help`              | 卡住时先答疑（记 `help_used`，不改判分） |
| 4    | GET  | `/todos`                               | 三类待办                                 |
| 4    | POST | `/review/start`                        | 开始一次复习                             |
| 4    | GET  | `/quiz/conversations/{conversationId}` | 测验页刷新恢复                           |
| 4    | GET  | `/progress`                            | 三个数字 + 近 8 周                       |
| 5    | POST | `/practice/guide`                      | 生成练习指引（不判分）                   |
| 6    | POST | `/ai/chat/stream`                      | 自由问答（`useRag` / `useTools`）        |
| 6    | GET  | `/metrics/summary`                     | 近 N 天调用次数 / 延迟 / token           |

### 13.2 九张表各管一件事

| 表                             | 一行代表                     | 谁写入                  |
| ------------------------------ | ---------------------------- | ----------------------- |
| `t_knowledge_item`             | 一份材料                     | 阶段 1                  |
| `t_knowledge_point`            | 一个知识点的掌握状态         | 阶段 2 建、阶段 3 升级  |
| `t_question`                   | 一道题（含它取材于哪份材料） | 阶段 2、阶段 4 复习复制 |
| `t_test_record`                | 一次作答 + 结论              | 阶段 3                  |
| `t_review_schedule`            | 一道题的下次复习时间         | 阶段 3 写、阶段 4 消耗  |
| `t_ai_call`                    | 一次模型调用的耗时与用量     | 阶段 6（六处调用点）    |
| `t_conversation` / `t_message` | 一次会话 / 一条消息          | 已有实现                |

### 13.3 与其它文档的关系

| 想知道                         | 看                                     |
| ------------------------------ | -------------------------------------- |
| 产品为什么这么设计、哪些不做   | [product-spec.md](./product-spec.md)   |
| 产品决策的来龙去脉、版本化修订 | [decision-log.md](./decision-log.md)   |
| 表设计的过程与（更早的）讨论   | [data-design.md](./data-design.md)     |
| 已完成 5 格纵切的实施细节      | [impl-log.md](./impl-log.md)           |
| 简历怎么写                     | [resume-assets.md](./resume-assets.md) |

> 9 张表里 `t_user` 不算在「各管一件事」的清单里，因为 `t_user` 只管账号本身，不参与学习闭环。

> **口径冲突时以 product-spec.md 为准**，本文件跟着改；两处不一致属于本文件的 bug，提出来我修。

### 13.4 手工清理 SQL（没有删除接口时用）

**删除一份资料（连同它出的题、成绩、排期、题目消息）**。外键顺序不能反：先删引用方，再删被引用方。

```sql
begin;

-- 先把要删的题与消息收进临时表（删完 t_question 就查不到 message_id 了）
create temp table _doomed on commit drop as
select q.id as question_id, q.message_id
  from t_question q
 where q.knowledge_item_id = '<资料id>';

delete from t_test_record      where question_id in (select question_id from _doomed);
delete from t_review_schedule  where question_id in (select question_id from _doomed);
delete from t_question         where id          in (select question_id from _doomed);
delete from t_message          where id          in (select message_id  from _doomed);
delete from t_knowledge_item   where id = '<资料id>';

commit;
```

**只作废「还没测过」的某道题**（不删资料，让待办不再挂它）：

```sql
begin;
delete from t_review_schedule where question_id = <questionId>;
delete from t_test_record     where question_id = <questionId>;
delete from t_question        where id = <questionId>;
delete from t_message         where id = <messageId>;   -- 可选，留着不影响待办
commit;
```

**清理孤儿知识点**（可选：题删了以后，词汇表里留下的 `CANDIDATE`）：

```sql
delete from t_knowledge_point kp
 where kp.user_id = '<用户id>'
   and not exists (select 1 from t_question q where q.knowledge_point_id = kp.id);
```

> 上面两段清理 SQL 只是本地开发的自救手段，不是产品功能。**不要在验收里用它伪造「待办已清空」** ——
> 正常闭环里，待办是答完自动消失的。

### 13.5 验收与复现

功能完备性的验收记录（输入、实际响应、期望、复现命令、结论）见独立文件 [`acceptance-log.md`](./acceptance-log.md)。
模型输出质量（出题好不好、判分措辞准不准）见第 5 节的 `QuizPromptEvalTest`，两者不混。
