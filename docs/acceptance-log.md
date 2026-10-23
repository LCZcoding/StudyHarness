# 验收记录与复现手册

> 本文件只记录**跑过的验收**：输入、实际响应、期望、复现方法、结论。
> 业务口径见 [`product-spec.md`](./product-spec.md)，怎么落代码见 [`mvp-backend-guide.md`](./mvp-backend-guide.md)。
> 新增验收一律追加到对应阶段表的末尾，不改历史行；失败重测另起一行并注明「复测」。

***

## 0. 使用规则

1. **每行一条命令，可单独复现**。`复现方法`列里出现的 `$B`、`$d`、`$env:PGPASSWORD` 见第 1 节。
2. **`AI 回复 / 实际响应`列只放关键片段**，长文本用 `…` 截断；完整响应放临时文件，不写进本文件。
3. **期望值不许为了通过而修改**。不通过就写「不通过」，在 `结论`列写原因，修复后追加「复测」行。
4. 两类记录分开：功能完备性（第 2~6 节）、模型表现（第 7 节）。**不要用模型表现替代功能验收**。

***

## 1. 前置（一次执行）

```powershell
$env:PGPASSWORD='<数据库密码>'
$B='http://localhost:8123/api'
$d="$env:TEMP\acc"; New-Item -ItemType Directory -Force -Path $d | Out-Null
Set-Content "$d\login.json" '{"username":"<账号>","password":"<密码>"}' -NoNewline
curl.exe -s -c "$d\cookies.txt" -H "Content-Type: application/json" --data-binary "@$d\login.json" $B/auth/login
```

登录成功返回 `{"userId":"<uuid>"}`。后续命令统一带 `-b "$d\cookies.txt"`。

**PowerShell 里不要用 `-d '{"a":"b"}'` 传 JSON**：引号会被 shell 吞掉，服务端收到非法请求体并返回 `400 {"message":"请求体不合法"}`。一律先写文件，再用 `--data-binary "@文件"`。

***

## 2. 阶段 0：建表

| # | 场景 / 问题 | 输入 | 实际响应 | 接口 | 期望 | 复现方法 | 结论 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 0-1 | 空库执行建表脚本 | `db/schema.sql` 全量 | 10 张表（含 pgvector 的 `vector_store`） | — | 业务表 9 张 | `psql -h <host> -U postgres -d ai_agent_study -f db/schema.sql` | 通过 |
| 0-2 | 索引是否齐全 | — | 8 条 `idx_*` 索引 + 4 条唯一约束 | — | 每条具名查询都有索引 | `psql ... -c "select indexname from pg_indexes where schemaname='public' order by 1;"` | 通过 |
| 0-3 | 脚本可重复执行 | 再跑一次同一文件 | 无报错（全部 `IF NOT EXISTS`） | — | 幂等 | 重复上一条命令 | 通过 |

***

## 3. 阶段 1：资料接入

| # | 场景 / 问题 | 输入 | 实际响应 | 接口 | 期望 | 复现方法 | 结论 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1-1 | 未登录访问 | 不带 Cookie | `401 {"message":"未登录"}` | `GET /knowledge-items` | `401` | `curl.exe -s -w " %{http_code}" $B/knowledge-items` | 通过 |
| 1-2 | 粘贴中文正文 | `{"title":"HNSW 参数","content":"<正文>"}` | `201` + `{"knowledgeItemId":"2887a8b4…","preview":"…","summary":"…"}` | `POST /knowledge-items` | `201` + `Location` + 预览 + 摘要 | `Set-Content "$d\paste.json" '<JSON>' -NoNewline` 后 `curl.exe -s -w " %{http_code}" -b "$d\cookies.txt" -H "Content-Type: application/json" --data-binary "@$d\paste.json" $B/knowledge-items` | 通过 |
| 1-3 | 列表 | — | 3 条资料，**无 `content` 字段** | `GET /knowledge-items` | 只返回自己的资料，列表不含正文 | `curl.exe -s -b "$d\cookies.txt" $B/knowledge-items` | 通过 |
| 1-4 | 上传 `.md` | `hnsw.md`（1002 字节） | `201`，`title=hnsw`，`sourceUrl=hnsw.md` | `POST /knowledge-items/upload` | 标题取文件名去扩展名 | `curl.exe -s -w " %{http_code}" -b "$d\cookies.txt" -F "file=@hnsw.md" $B/knowledge-items/upload` | 通过 |
| 1-5 | 上传文字版 `.pdf` | `lecture.pdf`（937 字节） | `201`，`preview` 为可读正文 | 同上 | 抽出的文字不乱码 | `curl.exe ... -F "file=@lecture.pdf" $B/knowledge-items/upload` | 通过 |
| 1-6 | 扩展名不支持 | `fake.docx` | `400 {"message":"只支持 .md / .txt / .pdf"}` | 同上 | `400` + 白名单文案 | `curl.exe ... -F "file=@fake.docx" $B/knowledge-items/upload` | 通过 |
| 1-7 | 文件超过 5 MB | 6 MB 的 `big.pdf` | `400` + 体积上限文案 | 同上 | `400`，不进入解析 | `curl.exe ... -F "file=@big.pdf" $B/knowledge-items/upload` | 通过 |
| 1-8 | 扫描件 PDF | 无文字层 | 未覆盖 | 同上 | `400` + 无可提取文字文案 | — | 未测（用户要求跳过） |
| 1-9 | 加密 PDF | 带权限密码的讲义 | `400` + `BouncyCastleFactoryCreator` ERROR 日志 | 同上 | `400` | 上传后看后端日志 | 已知限制（技术债 24） |

***

## 4. 阶段 2：出题

| # | 场景 / 问题 | 提示词 / 请求体 | AI 回复 / 实际响应 | 接口 | 期望 | 复现方法 | 结论 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 2-1 | 对 `hnsw.md` 出 3 题 | `{"knowledgeItemId":"12dda403…"}` | 3 题：`FACT` HNSW 第 0 层邻居数 / `FACT` ef_construction 的作用 / `EXPLAIN` 为什么查询接近对数级 | `POST /quiz/generate` | `200`，3 题，≥1 个 `EXPLAIN` | `Set-Content "$d\gen.json" '{"knowledgeItemId":"<id>"}' -NoNewline` 后 `curl.exe -s -w " %{http_code}" -b "$d\cookies.txt" -H "Content-Type: application/json" --data-binary "@$d\gen.json" $B/quiz/generate` | 通过（`4411 ms`） |
| 2-2 | 题目落库 | — | 3 行，`knowledge_item_id` 全指向同一资料 | — | 每题一行、资料归属正确 | `psql ... -c "select id,kind,target from t_question where knowledge_item_id='<id>' order by id;"` | 通过 |
| 2-3 | 知识点入词汇表 | — | 3 行 `CANDIDATE` | — | 出题即写入，状态为 `CANDIDATE` | `psql ... -c "select name,status from t_knowledge_point order by id;"` | 通过 |
| 2-4 | 会话与消息 | — | `mode=SELF_TEST`，消息 1 条 USER + 3 条 QUESTION | — | 会话可被左侧列表识别 | `psql ... -c "select id,mode from t_conversation order by created_at desc limit 3;"` | 通过 |
| 2-5 | 资料不存在 / 无权 | `{"knowledgeItemId":"<别人的id>"}` | `403 {"message":"资料不存在或无权访问"}` | `POST /quiz/generate` | `403` | 同上，换 id | 通过 |
| 2-6 | 非法 UUID | `{"knowledgeItemId":"abc"}` | `400 {"message":"请求体不合法"}` | 同上 | `400`，不是 `500` | 同上 | 通过 |
| 2-7 | 未登录 | 不带 Cookie | `401 {"message":"未登录"}` | 同上 | `401` | 不带 `-b` | 通过 |
| 2-8 | 超前重问（`retry`） | 该资料下存在 `FAIL` 且未补讲的题 | 未触发 | 同上 | 重问 1 道 + 新出 2 道 | 需先制造 `FAIL` 后重跑 | 后续补测（阶段 3 已具备条件） |

**人工审题（2-1 的三题）**

| 题 | 题面 | 判断 |
| --- | --- | --- |
| 1 | HNSW 第 0 层每个节点最多保留多少个邻居？ | 命中材料，未给答案 |
| 2 | ef_construction 控制什么？增大它会对索引构建产生什么影响？ | 命中材料，未给答案 |
| 3 | 假设听众只会 Python、没听过向量检索，讲清为什么查询接近对数级 | 有听众设定，未泄露结论 |

***

## 5. 阶段 3：作答与判分

| # | 场景 / 问题 | 提示词 / 请求体 | AI 回复 / 实际响应 | 接口 | 期望 | 复现方法 | 结论 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 3-1 | 糊答案 | `{"answer":"大概跟层数有关系吧，具体数字我记不清了。"}` | `FAIL`，`gaps` 3 条，`retryAllowed=true`，`stage=1`，`nextDueAt`=次日 | `POST /quiz/1/attempt` | `FAIL`/`PARTIAL` + 漏洞清单 | `Set-Content "$d\a.json" '<JSON>' -NoNewline` 后 `curl.exe -s -w " %{http_code}" -b "$d\cookies.txt" -H "Content-Type: application/json" --data-binary "@$d\a.json" $B/quiz/<questionId>/attempt` | 通过 |
| 3-2 | `gaps` 是否泄题（首测） | 同 3-1 | `gaps` 第 3 条：「未说明第 0 层通常的上限规则（**如 2m 这一常见设置**）」 | — | 只描述缺口，不给数值 | 读 3-1 响应 | **不通过**（技术债 25） |
| 3-3 | 补讲 | 正确的 `2m` 讲解 | `PASS`，`attemptNo=2`，`retryAllowed=false`，`stage=3`，`nextDueAt`=3 天后 | `POST /quiz/1/attempt` | 第 2 次判完即定 | 同上 | 通过 |
| 3-4 | 第 3 次提交 | 重复 3-3 | `400 {"message":"这道题已经补讲过了，等下次复习再讲"}` | `POST /quiz/1/attempt` | `400` | 同一条命令再跑一次 | 通过 |
| 3-5 | 排期一题一行 | — | 每题 1 行，`stage=3`、`PENDING` | — | 无重复行 | `psql ... -c "select question_id,stage,status from t_review_schedule order by question_id;"` | 通过 |
| 3-6 | 求助 | `{"question":"ef_construction 和 ef_search 到底哪个影响召回？"}` | `200` + 一段解释；`t_question.help_used=true`；`t_test_record` 无新增行 | `POST /quiz/2/help` | 先回答、记求助、不产生成绩 | `Set-Content "$d\h.json" '<JSON>' -NoNewline` 后 `curl.exe ... --data-binary "@$d\h.json" $B/quiz/<id>/help` | 通过 |
| 3-7 | 空作答 | `{"answer":"   "}` | `400 {"message":"answer 不能为空"}` | `POST /quiz/2/attempt` | `400` | 同上 | 通过 |
| 3-8 | 越权 | 第二个账号提交 `questionId=1` | `403 {"message":"题目不存在或无权访问"}` | `POST /quiz/1/attempt` | `403` | 用第二个账号登录后带自己的 Cookie | 通过 |
| 3-9 | 未登录 | 不带 Cookie | `401 {"message":"未登录"}` | 同上 | `401` | 不带 `-b` | 通过 |
| 3-10 | 知识点升级 | 3-3 判 `PASS` 之后 | 「HNSW 第 0 层邻居数」「ef_construction 的作用」变 `VERIFIED` | — | `PASS` 触发升级 | `psql ... -c "select name,status from t_knowledge_point order by id;"` | 通过 |
| 3-11 | `VERIFIED` 注入新题 | 再次对同一资料出题 | 第 2 题：「假设听众只了解你已掌握的 **ef_construction** 和 **HNSW 第 0 层邻居数**，请用它们做类比…」 | `POST /quiz/generate` | 提示词里带最近 5 个已掌握概念 | 同 2-1 | 通过 |
| 3-12 | `gaps` 复测 | 改提示词后提交糊答案（`questionId=2`） | 4 条全是「没有说明…／没有讲清…」，无数值、无术语定义 | `POST /quiz/2/attempt` | 只描述缺口 | 同 3-1 | **复测通过** |

**复测前后的 `gaps` 对照**

| 版本 | 内容 |
| --- | --- |
| 复测前 | 未说明第 0 层通常的上限规则（如 **2m** 这一常见设置） |
| 复测后 | 没有说明该参数在构图阶段具体调控的是哪一类过程或量<br>没有讲清增大它会如何影响索引本身的质量<br>没有讲清增大它对构建耗时和效率的代价<br>把作用笼统说成「更准」，没有落到索引构建层面 |

***

## 6. 阶段 4：待办 + 复习 + 进度

验收日期 2026-10-22，账号 `lcz`，起始数据：资料 2 份（`12dda403` hnsw、`2887a8b4` HNSW 参数），题目 id 1~6，其中 1/2 PASS、3~6 未作答。

| # | 场景 / 问题 | 输入 | 实际响应 | 接口 | 期望 | 复现方法 | 结论 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 6-1 | 待办基线（未制造数据） | — | 只有 `UNTESTED`：`3eb7852d` count=1、`d84208b4` count=3；无 `REVIEW`、无 `RETRY` | `GET /todos` | 只列「现在能做的」 | `curl.exe -s -b "$d\cookies.txt" $B/todos` | 通过 |
| 6-2 | 答糊一道制造 `RETRY` | `{"answer":"完全不清楚，随便写点。"}` | `FAIL`、`gaps` 4 条、`retryAllowed=true`、`stage=1`、`attemptNo=1`、`nextDueAt`=次日 | `POST /quiz/3/attempt` | `FAIL` + 漏洞清单 | `--data-binary "@$d\a3.json"` | 通过 |
| 6-3 | `RETRY` 出现 | — | `RETRY` 一条：`count=1`、`knowledgeItemId` 有值、`conversationId=null`；`3eb7852d` 的 `UNTESTED` 消失 | `GET /todos` | `RETRY` 按资料聚合，已答完的会话不再列 | 同 6-1 | 通过 |
| 6-4 | 到期 → `REVIEW` | 把 q1/q2 的 `due_at` 改为昨天 | `REVIEW` 一条：`count=2`、`knowledgeItemId=null`，排在 `RETRY` / `UNTESTED` 之前 | `GET /todos` | 到期只汇总一条、排第一 | `psql -c "update t_review_schedule set due_at=now()-interval '1 day' where question_id in (1,2);"` 后跑 6-1 | 通过 |
| 6-5 | 启动复习 | — | `200`：`conversationId=e9b02c05`；q7 题面 = 旧 q2 题面、q8 题面 = 旧 q1 题面（**逐字一致**）、q9 来自另一份资料 | `POST /review/start` | 2 道到期 + 1 道交错 | `curl.exe -s -b "$d\cookies.txt" -X POST $B/review/start` | 通过 |
| 6-6 | 排期跟着题走 | — | q7/q8 各一行、`stage=3` 保留、`status=PENDING`、`due_at` 未变；q1/q2 的行已消失 | 查库 | 旧行消失、新行唯一、启动复习不作废 `stage` | `select question_id,stage,status,due_at from t_review_schedule order by 1;` | 通过 |
| 6-7 | 交错题确实来自另一份资料 | — | q9 的 `knowledge_item_id=2887a8b4`（HNSW 参数），不是 q1/q2 所属的 `12dda403` | 查库 | 混排来自另一份资料 | 同 6-6 | 通过（同主题、不同资料） |
| 6-8 | 立即再点一次复习 | 不推后 `due_at`，直接 `POST /review/start` | `200`：新建会话 `2c2aae00`，q10/q11 是 q7/q8 的再次复制，q12 又是一道交错题 | `POST /review/start` | 7.10 步骤 3 写的是 `400` | 重复 6-5 | **与文档不一致**（说明见下，已于 2026-10-23 修复，复测见 6-17） |
| 6-9 | 真正没有到期项时再点 | 把 q10/q11 的 `due_at` 推到 3 天后 | `400 {"message":"现在没有到期需要复习的题"}` | `POST /review/start` | `400` + 该文案 | `psql -c "update ... set due_at=now()+interval '3 days' where question_id in (10,11);"` 后 `POST` | 通过（2026-10-23 加幂等后，仅推后 `due_at` 不再得到 `400`，正确复现见 6-19） |
| 6-10 | 进度 | — | `masteredItems=0`、`totalItems=4`、`streakDays=1`、`dueCount=2`、`weeks` 8 项（当周 5 次） | `GET /progress` | `weeks` 恒 8 项、`streakDays` 与作答日期一致 | `curl.exe -s -b "$d\cookies.txt" $B/progress` | 通过 |
| 6-11 | `dueCount` 跟待办一致 | 执行 6-9 的 `due_at` 推后 | `dueCount=0`，其余字段不变 | `GET /progress` | 与 `GET /todos` 的 `REVIEW` 同步归零 | 同 6-10 | 通过 |
| 6-12 | 测验页恢复 | — | `3eb7852d` 返回 3 题：q1 `PASS`、q2 `PASS`、q3 `FAIL`，与 `t_test_record` 最近一行一致 | `GET /quiz/conversations/{conversationId}` | `verdict` 为 `null` / `PASS` / `PARTIAL` / `FAIL` | `curl.exe -s -b "$d\cookies.txt" $B/quiz/conversations/3eb7852d-0b72-4b41-9e1c-b25b3a8b551b` | 通过 |
| 6-13 | 不存在的会话 | 随机 UUID | `403 {"message":"会话不存在或无权访问"}` | 同上 | `403`，与「无权」同文案 | 同上，换一个不存在的 UUID | 通过 |
| 6-14 | 三个新接口未登录 | 不带 Cookie | `/todos` `401`、`/progress` `401`、`/review/start` `401` | 三者 | `401` | 三个请求都不带 `-b` | 通过 |
| 6-15 | 重问分支（补齐原 2-8） | `{"knowledgeItemId":"12dda403…"}`，库里有 q3 的 `FAIL` 且未补讲 | `200`：q13 题面 = 旧 q3 题面（逐字一致），q14/q15 新出；会话 `212ac585` | `POST /quiz/generate` | 重问 1 道 + 新出 2 道 | `--data-binary "@$d\gen.json"` | 通过 |
| 6-16 | 重问后的排期与待办 | — | 排期从 q3 迁到 q13（`stage=1`、`due_at` 不变），q3 的行消失；`RETRY` 消失，改由 `UNTESTED`（`212ac585` count=3）呈现 | 查库 + `GET /todos` | 排期跟着题走、不产生重复排期 | 同 6-6、6-1 | 通过 |
| 6-17 | 幂等复测：连点两次 | `POST /review/start` 连续两次（q10~q12 均未作答，`due_at` 已被推到未来） | 两次都返回 `conversationId=2c2aae00`、`questionId` 10/11/12；`t_question` 15 行不变、`REVIEW` 会话 2 条不变；耗时 0.74s / 0.38s（无模型调用） | `POST /review/start` | 同一轮、同一批题、不新增行 | 两次执行 `curl.exe -s -b "$d\cookies.txt" -X POST $B/review/start`，中间查 `select count(*) from t_question;` | 通过（已修 6-8） |
| 6-18 | 幂等复测：答 1 道再点 | q10 答 PASS 后立刻 `POST /review/start` | 返回同一会话 `2c2aae00`，只剩 q11、q12 两道未答题 | 同上 | 只返回未答题，不复制 | 先 `--data-binary "@$d\ans10.json" $B/quiz/10/attempt`，再 POST | 通过 |
| 6-19 | 幂等复测：答完再点 | q11、q12 也答完后 POST | `400 {"message":"现在没有到期需要复习的题"}`，`t_question` 仍 15 行 | 同上 | `400` + 该文案 | 答完 q11/q12 后 POST | 通过 |

**6-8 为什么不一致**：`ReviewScheduleRepository.moveQuestion` 只改 `question_id`，**`due_at` 刻意不动**（该类注释写明「保持 PENDING、due_at 不动」，为了让中途放弃的题仍然算欠着）。所以刚启动复习的 q10/q11 依然「已到期」，第二次点根本走不到「没有到期项」分支。7.10 步骤 3 的期望没有错，错在缺少前置条件——必须先让到期题不再到期（答完自动重排 3 天，或手工把 `due_at` 推到未来）。

**两条观察（不是失败项）**

1. **重复点复习会复制题 → 已修（2026-10-23）**：`QuizService.startReview` 先查最新一条 `mode='REVIEW'` 会话，其中有未作答题就原样返回那批题，不再新建。实测见 6-17~6-19。注意：正常复习本来就是「把旧题复制成新行」，`t_question` 会随每轮复习增长，这是设计而非缺陷；`ProgressRepository.countMasteredItems` 的分母按全部题统计，因此「已掌握」要求把复制出来的每一轮题都答到 `PASS`。
2. **交错题不可复现**：`KnowledgeItemRepository.findAnotherItemId` 用 `order by random()` 取另一份资料，同一用户资料多于两份时，同一批到期题两次复习抽到的交错题不同。不是缺陷，但做演示时别指望两次结果一样。

***

## 7. 模型表现记录（不是功能验收）

| # | 场景 / 问题 | 提示词 | AI 回复 | 期望 | 复现方法 | 结论 |
| --- | --- | --- | --- | --- | --- | --- |
| 7-1 | 出题评测第 1 轮（10 份跨领域材料） | `QuizService.SYSTEM_PROMPT` 第 1 版 | 5/10 通过；失败 5 条里 4 条是字符串锚点误报（`Proxy`、`布隆过滤器`、`预主密钥`） | ≥7/10 | `mvn -Dtest=QuizPromptEvalTest test` | 不通过，判定为评测规则有缺陷 |
| 7-2 | 出题评测第 2 轮（改用 AI 裁判 + 规则层） | 第 2 版提示词 | 规则层 10/10；裁判 0 条泄露、0 条超纲、0 条缺听众 | 规则层 10/10，语义告警人工复核 | 同上，报告在 `target/quiz-eval-report.md` | 通过（人工复审口径见本节末） |
| 7-3 | 判分措辞 | `GradingService.SYSTEM_PROMPT` 第 1 版 | `gaps` 给出 `2m` | 不给数值 | 见 3-2 | 不通过，已修 |

**语义判定口径（人工复审用）**：题面是否替用户说出了「用户本来要输出的那个答案」。
点名概念、给前提或现象、反事实提问都不算泄露；直接给出结论、数值、选择才算。

***

## 8. 未覆盖与待验

| 项 | 为什么没验 | 什么时候补 |
| --- | --- | --- |
| 扫描件 PDF | 用户明确跳过 | 需要 OCR 时 |
| 5 万字上限拒绝 | 没造出真实超长文档，仅代码审阅 | 有真实长讲义时 |
| 重复点复习的去重 | 现状是「放弃的复习仍欠着」，重复点会复制题（见 6-8、观察 1） | 拍板后再决定是前端禁用按钮还是服务端拦截 |
| 阶段 5 引导实践 | 还没实现 | 阶段 5 验收 |
