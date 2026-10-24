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
| 0-4 | 注册新账号（2026-10-23 追加接口） | `{"username":"user3","password":"123456"}` | `201 {"userId":"2c4c43b0-2721-4d6d-b410-63f38b01ac5d"}`，同时下发 `JSESSIONID`；紧接着用该 Cookie 调 `GET /knowledge-items` 返回 `[]`（说明注册即登录、且新账号数据为空） | `POST /auth/register` | `201` + `userId`，无需再登录 | `curl.exe -s -w " %{http_code}" -c "$d\c3.txt" -H "Content-Type: application/json" --data-binary "@$d\reg3.json" $B/auth/register` | 通过 |
| 0-5 | 用户名重复注册 | 同上请求体再发一次 | `400 {"message":"用户名已被占用"}` | 同上 | `400` + 该文案（唯一约束由数据库兜底） | 重复上一条命令 | 通过 |
| 0-6 | 密码太短 | `{"username":"user4","password":"123"}` | `400 {"message":"密码需 6~72 个字符"}` | 同上 | `400` + 长度校验文案 | `--data-binary "@$d\reg4.json"` | 通过 |
| 0-7 | 注册后不登录直接访问受保护接口 | 用 0-4 拿到的 Cookie | `GET /knowledge-items` 返回 `[]`（`200`，不是 `401`） | `GET /knowledge-items` | 注册写入 session，直接可访问 | `curl.exe -s -b "$d\c3.txt" $B/knowledge-items` | 通过 |

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
| 1-10 | 粘贴带 `##` 的文档切章 | `{"title":"并发编程测试","content":"8 个 ##，每节约 1950 字，共 15612 字"}` | `201`，`docTitle=并发编程测试`，`sections=2`：第 1 章「第 1 节 线程与锁」`chars=9758`、第 2 章「第 6 节 线程与锁」`chars=5854`；两章 `summary` 分别为 128 / 109 字 | `POST /knowledge-items` | `201`，`sections` 按 `##` 切分，`sectionNo` 从 1 连续递增，每章 ≤ 12000 字 | `curl.exe -s -w " %{http_code}" -b "$d\cookies.txt" -H "Content-Type: application/json" --data-binary "@$d\doc.json" $B/knowledge-items` | 通过 |
| 1-11 | 无标题、纯空行分段的长文档 | 320 句拼成的单段落（15360 字，无换行无标题） | `201`，`sections=2`：`chars=12000` / `chars=3360`，两章标题均为该段首句前 30 字「线程池的核心参数包括核心线程数、最大线程数、任务队列和拒绝策」 | 同上 | 切成多章，标题取该段首句前 30 字 | 同上，换正文 | 通过（两章标题相同是输入句子重复所致，不是切分错误） |
| 1-12 | 章节数超上限 | 31 节 × 约 12100 字、1.12MB 的 `big2.md` | `400 {"message":"文档切分后超过 30 章（当前 62 章），请拆分后分次上传"}` | `POST /knowledge-items/upload` | `400` + 超限文案 | `curl.exe -s -w " %{http_code}" -b "$d\cookies.txt" -F "title=超限测试2" -F "file=@$d\big2.md" $B/knowledge-items/upload` | 通过（拒绝生效），**但暴露切分缺陷**：12100 字的一节被硬切成 12000 + 100 两段，100 字残段单独成章，导致 31 节报成 62 章；修法见 `mvp-backend-guide.md` 3.8「改动 2 补充」 |
| 1-13 | 同名文档重复粘贴 | 与 1-10 相同的 `title` 与正文 | `400 {"message":"已存在同名文档，请改名后重新上传"}` | `POST /knowledge-items` | 同左文案 | 再跑一次 1-10 的命令 | 通过 |
| 1-14 | 同名文档重复上传 | `-F "title=并发编程测试" -F "file=@big.md"` | 同上文案 | `POST /knowledge-items/upload` | 同 1-13 文案 | `curl.exe ... -F "title=并发编程测试" -F "file=@$d\big.md" $B/knowledge-items/upload` | 通过 |
| 1-15 | 列表分组与三态 | 7 份文档、24 章 | `200`，7 个文档分组；「超限测试」16 章全 `NOT_STARTED`，「无标题长文档测试」/「并发编程测试」各 2 章 `NOT_STARTED`，`hnsw` 1 章 `IN_PROGRESS`（历史题有未作答），其余单章 `NOT_STARTED`；`passedCount` 全为 0，与 `PASSED` 数量一致 | `GET /knowledge-items` | 按文档分组、`sections` 按 `sectionNo` 升序、`passedCount` 与 `PASSED` 数量一致 | `curl.exe -s -b "$d\cookies.txt" $B/knowledge-items` | 通过（`PASSED` 态未覆盖：需要该章 3 题全部判 `PASS`，本案只验到 `NOT_STARTED` / `IN_PROGRESS`） |
| 1-16 | 章节正文 | 取「并发编程测试」第 1 章 `f1e0d203-d1e1-424a-ab43-3832cee678b6` | `200`，`docTitle=并发编程测试`、`sectionNo=1`、`title=第 1 节 线程与锁`、`chars=9758`、`content` 长度 9758 | `GET /knowledge-items/{id}` | `chars` 等于 `content` 长度 | `curl.exe -s -b "$d\cookies.txt" $B/knowledge-items/<id>` | 通过（附带发现：`content` 首行带 `## ` 标记，且与 `title` 字段重复，见下方「观察项」） |
| 1-17 | 越权读章节正文 | 第二个账号 `user2` 取第一个账号的章节 id `f1e0d203-d1e1-424a-ab43-3832cee678b6` | `403 {"message":"资料不存在或无权访问"}`；`user2` 自己的 `GET /knowledge-items` 返回 `[]` | 同上 | `403 {"message":"资料不存在或无权访问"}` | `curl.exe -s -w " %{http_code}" -b "$d\c2.txt" $B/knowledge-items/f1e0d203-d1e1-424a-ab43-3832cee678b6` | 通过（注册接口上线后可验，`user2` 为手工注册账号） |
| 1-18 | 章节 id 不是 UUID | `/knowledge-items/abc` | `400 {"message":"knowledgeItemId 不合法"}` | 同上 | `400`，不是 `500` | `curl.exe -s -w " %{http_code}" -b "$d\cookies.txt" $B/knowledge-items/abc` | 通过 |
| 1-19 | 历史资料回填 | 回填 SQL 已执行 | 列表里历史 3 条（`hnsw` / `HNSW 参数` / `TCP 三次握手`）各自成为单章文档，没有 `doc_title` 为空的分组 | 查库 | 原各行 `doc_title=title`、`section_no=1` | 跑 1-15 观察分组 | API 层通过（库内两列是否真回填未验：本次无 `psql` 凭据，`findAllByUserId` 用 `coalesce` 兜底，空列也不会暴露） |
| 1-20 | 三态 `PASSED`（补 1-15 未覆盖项） | 《JUC》第 1 章 `7a7acf52…`（704 字）出 3 题（25/26/27），3 题各答一次 | `POST /quiz/generate` 返回题 25/26/27；三次 `POST /quiz/{id}/attempt` 均 `PASS`、`attemptNo=1`、`gaps=[]`；`GET /knowledge-items` 中该章 `PASSED`、`passedCount=1`；同文档第 7 章因 3 题未答为 `IN_PROGRESS` | `GET /knowledge-items` | 3 题全 `PASS` 后章节为 `PASSED` 且 `passedCount` 加 1 | 出题→逐题作答→查列表 | 通过（三态 `NOT_STARTED` / `IN_PROGRESS` / `PASSED` 全部覆盖） |
| 1-21 | 超长单节均分切块（复测 1-12） | 2 节 × 12220 字、31 节 × 12220 字的上传各一次 | 两节版 `201`，切出 4 章：`6106 / 6136 / 6097 / 6136`，不再有 100~200 字残段；31 节版 `400 {"message":"文档切分后超过 30 章（当前 62 章），请拆分后分次上传"}` | `POST /knowledge-items/upload` | 均分切块后无碎片章，超 30 章仍拒绝 | 改 `DocumentSplitter.chunks()` 后重启；`-F "file=@splitA.md"` / `-F "file=@splitB.md"` | 通过（修复前同样输入切出 `11999 + 234`，说明必须重启才生效） |

**阶段 2 / 阶段 4 回归（切章改动后同批跑，确认没打断旧链路）**

| 场景 | 实际响应 | 结论 |
| --- | --- | --- |
| 对「并发编程测试」第 2 章调 `POST /quiz/generate` | `200`，3 题：`FACT` 线程安全触发条件 / `FACT` 管理共享状态的核心 / `EXPLAIN` 要求用已掌握的 HNSW 概念做类比 | 通过（出题、`EXPLAIN`、`VERIFIED` 知识点注入都没被切章改动影响） |
| `GET /progress` | `masteredItems=0`、`totalItems=24`、`streakDays=2`、`dueCount=0`、`weeks` 8 项 | 通过（`totalItems` 已从「资料数」变为「章节数」） |
| `GET /todos` | `RETRY` 1 条 + `UNTESTED` 5 条，`UNTESTED` 的 `title` 已是章节标题（如「第 6 节 线程与锁」） | 通过 |

**观察项（不阻塞验收，但要在写阅读页前定）**

1. **`content` 里保留了 markdown 标题标记**：1-16 返回的正文首行是 `## 第 1 节 线程与锁`，而这个字符串已经存在 `title` 字段里。纯文本渲染时用户会看到 `##`。两种改法二选一：切分时把标题行从 `content` 剥离（正文更干净，但会改变出题素材），或阅读页渲染时去掉行首 `#`。
2. **超限文案里的章数是「切块数」不是「标题节数」**：1-12 报「当前 62 章」，用户自己是 31 节；按 3.8「改动 2 补充」改成均分切块后，章数仍是 62（一节 12100 字本来就要两章），但不再有 100 字的碎片章。

**本次测试产生的数据（可用 `mvp-backend-guide.md` 13.4 的 SQL 清理）**

| 文档名 | 章节数 | 说明 |
| --- | --- | --- |
| `并发编程测试` | 2 | 1-10 用例，第 2 章已出 3 题（`conversationId=14a10f3f`） |
| `无标题长文档测试` | 2 | 1-11 用例 |
| `超限测试` | 16 | 第一次超限用例没造够字数，反而建成 16 章 |

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
| 6-20 | 跳过出口（2026-10-24 新增） | 对第 1 章出 3 题（q28/q29/q30），把 q28/q29 排期改到昨天，`POST /review/start` 生成 q31/q32，然后 `POST /quiz/31/skip`、`POST /quiz/32/skip` | 两次均 `200 {"nextDueAt":"2026-10-25T07:47:3x Z"}`（排期推后 1 天、仍 `PENDING`）；`GET /quiz/conversations/20d273f7` 两题 `verdict="SKIPPED"`；再 `POST /review/start` 返回 `400 {"message":"现在没有到期需要复习的题"}`，循环被打断；已作答的 q30 跳过返回 `400 {"message":"这道题已经作答，不能跳过"}`；`user2` 跳过 q31 返回 `403 {"message":"题目不存在或无权访问"}` | `POST /quiz/{questionId}/skip` | 跳过不产生成绩、本轮不再等它、排期顺延 1 天、无权/已作答各有独立 4xx | 出题 → `psql -c "insert into t_review_schedule (user_id,question_id,stage,due_at) values (..., now()-interval '1 day');"` → `POST /review/start` → 两次 skip → 查会话 → 再 start | 通过（重复跳过返回 400 的守卫已加进 `QuizService.skip`，需重启后复测） |

**6-8 为什么不一致**：`ReviewScheduleRepository.moveQuestion` 只改 `question_id`，**`due_at` 刻意不动**（该类注释写明「保持 PENDING、due_at 不动」，为了让中途放弃的题仍然算欠着）。所以刚启动复习的 q10/q11 依然「已到期」，第二次点根本走不到「没有到期项」分支。7.10 步骤 3 的期望没有错，错在缺少前置条件——必须先让到期题不再到期（答完自动重排 3 天，或手工把 `due_at` 推到未来）。

**两条观察（不是失败项）**

1. **重复点复习会复制题 → 已修（2026-10-23）**：`QuizService.startReview` 先查最新一条 `mode='REVIEW'` 会话，其中有未作答题就原样返回那批题，不再新建。实测见 6-17~6-19。注意：正常复习本来就是「把旧题复制成新行」，`t_question` 会随每轮复习增长，这是设计而非缺陷；`ProgressRepository.countMasteredItems` 的分母按全部题统计，因此「已掌握」要求把复制出来的每一轮题都答到 `PASS`。
2. **交错题不可复现**：`KnowledgeItemRepository.findAnotherItemId` 用 `order by random()` 取另一份资料，同一用户资料多于两份时，同一批到期题两次复习抽到的交错题不同。不是缺陷，但做演示时别指望两次结果一样。

> **2026-10-23 补正**：上面第 1 条里「`countMasteredItems` 的分母按全部题统计」已不成立。3.8「改动 7」之后，`ProgressRepository.countMasteredItems` 只统计 `mode='SELF_TEST'` 的题，`REVIEW` 复制出来的题不再计入分母，「已掌握」也不用再把每一轮复习都答到 `PASS`。原句保留为当时的实测口径。

***

## 7. 阶段 5：引导实践

验收日期 2026-10-23，账号 `lcz`，章节 `12dda403`（hnsw，404 字）与 `2887a8b4`（HNSW 参数，90 字）。

| # | 场景 / 问题 | 输入 | 实际响应 | 接口 | 期望 | 复现方法 | 结论 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 5-1 | 带目标生成练习 | `{"knowledgeItemId":"12dda403…","goal":"我想写个小脚本验证 ef_search 对召回的影响"}` | `200`，`6.58 s`；`conversationId=b3e60350…`；`content` 四段齐全（任务 / 验收标准 / 常见坑 / 对应材料），任务为「ef_search 扫 5 个值、numpy 暴力 top-10 当 ground truth、算 recall@10 与耗时」 | `POST /practice/guide` | `200` + 四段结构 | `curl.exe -s -w " %{http_code}" -b "$d\cookies.txt" -H "Content-Type: application/json" --data-binary "@$d\p1.json" $B/practice/guide` | 通过 |
| 5-2 | 不带目标 | `{"knowledgeItemId":"2887a8b4…"}`（`goal` 缺省） | `200`，`4.30 s`；仍按材料主题给单变量实验，且指出「必须回看 ef_search 控制候选集大小」 | 同上 | `goal` 可空，按材料主题设计 | `--data-binary "@$d\p2.json"` | 通过 |
| 5-3 | 越权取别人的章节 | `user2` 提交 `12dda403` | `403 {"message":"资料不存在或无权访问"}` | 同上 | `403` | 用 `user2` 的 Cookie 重跑 5-1 | 通过 |
| 5-4 | 未登录 | 不带 Cookie | `401 {"message":"未登录"}` | 同上 | `401` | 不带 `-b` | 通过 |
| 5-5 | `goal` 超 500 字 | 501 个字符 | `400 {"message":"目标最多 500 字"}` | 同上 | `400` | 构造 501 字请求体 | 通过 |
| 5-6 | 非法 UUID | `{"knowledgeItemId":"abc"}` | `400 {"message":"请求体不合法"}` | 同上 | `400`，不是 `500` | `--data-binary "@$d\p6.json"` | 通过 |
| 5-7 | 会话与消息落库 | — | 两个 `conversationId` 的 `mode` 均为 `PRACTICE`，各 2 条消息（`USER`/`ASSISTANT`，`type=CHAT`） | 查库 | 1 个会话 + 2 条消息 | `select id,mode,(select count(*) from t_message m where m.conversation_id=c.id) from t_conversation c where c.id in ('b3e60350…','d0c0d148…');` | 通过 |
| 5-8 | 不产生成绩 | — | `t_test_record` 命中 0 行 | 查库 | 必须是 0 —— 证明「不判分」不是嘴上说说 | `select count(*) from t_test_record tr where tr.question_id in (select q.id from t_question q join t_message m on m.id=q.message_id where m.conversation_id in ('b3e60350…','d0c0d148…'));` | 通过 |
| 5-9 | 超长材料截断分支（`MATERIAL_LIMIT=8000`） | 对 JUC 第 1 章（704 字）和第 2 章（11988 字）各跑一次 `/practice/guide` | 两次都 `200`；`t_ai_call` 里 `PRACTICE` 的 `prompt_tokens` 分别为 `679` 和 `4526`。长章是短章的 6.7 倍，而不是字数比的 17 倍；4526 ≈ 系统提示词 ~600 + 8000 字截断后的 ~3900，与「截到 8000 字」吻合（若不截断应约 6300） | `POST /practice/guide` | 长材料只送前 8000 字 | 两个章节各请求一次，再查 `select prompt_tokens from t_ai_call where scene='PRACTICE' order by id desc;` | 通过 |

> 查库输出的中文在 PowerShell 控制台会显示成乱码（`鎴戞兂…`），那是 `psql` 的控制台编码问题，接口返回的 JSON 中文正常，不影响结论。
> 5-7 / 5-8 用到的两个 `conversationId` 是 5-1、5-2 本次产生的；查库需先设 `$env:PGPASSWORD`，再执行 `"D:\PostgreSQL\bin\psql.exe" -h 72.155.89.172 -U postgres -d ai_agent_study -c "<SQL>"`。

***

## 8. 阶段 6：观测埋点与汇总

验收日期 2026-10-24，账号 `lcz`。起点 `t_ai_call` 为 0 行，六种场景各触发一次。

| # | 场景 / 问题 | 输入 | 实际响应 | 接口 | 期望 | 复现方法 | 结论 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 6-1 | 六种场景都落一行 | 依次跑 `PRACTICE`、`QUIZ_GENERATE`、`GRADE`、`HELP`、`SUMMARY`、`CHAT` | 6 行，`scene` 各不相同，各 1 次，`failed=0` | 六个业务接口 | 六种 `scene` 一个不缺、无重复 | `psql -c "select scene,count(*) from t_ai_call group by scene;"` | 通过 |
| 6-2 | 非流式 usage 齐全 | 同 6-1 五条非流式调用 | 实测 `prompt/completion`：`GRADE 4412/44`、`HELP 4035/62`、`SUMMARY 3548/73`、`PRACTICE 3095/705`、`QUIZ_GENERATE 5368/160`，`calls_without_usage=0` | — | 五条都有 token 数 | 同 6-1 | 通过 |
| 6-3 | 流式只记首 token | `POST /ai/chat/stream` 一句话 | `first_token_ms=946`、`latency_ms=2219`、`prompt_tokens=NULL` | 同上 | `first_token_ms` 有值、usage 为 `NULL` | 同 6-1 | 通过（符合技术债 19 的口径） |
| 6-4 | 汇总接口 | `?days=7` | `totalCalls=6`、`totalPromptTokens=20458`、`totalCompletionTokens=1044`、`failedCalls=0`、`callsWithoutUsage=1`；`CHAT` 有 `p95FirstTokenMs=946`，其余五项 `null` | `GET /metrics/summary` | 六行场景 + 汇总字段与 6-1 一致 | `curl.exe -s -b "$d\cookies.txt" "$B/metrics/summary?days=7"` | 通过 |
| 6-5 | 用户隔离 | `user2` 调汇总 | `{"scenes":[],"totalCalls":0,…}` | 同上 | 看不到 `lcz` 的 6 行 | 用 `user2` 的 Cookie 重跑 6-4 | 通过 |
| 6-6 | 未登录 | 不带 Cookie | `401 {"message":"未登录"}` | 同上 | `401` | 不带 `-b` | 通过 |
| 6-7 | `days` 边界 | `days=0` / `days=91` / `days=abc` | `400 days 必须在 1 到 90 之间` / 同左 / `400 days 不合法` | 同上 | 三种都 `400` 且不是 `500` | 换 `?days=` 重跑 6-4 | 通过 |
| 6-8 | `useRag` 开关是否真的检索 | 同一问题分别以 `useRag=false` / `true` 各问一次：「我上传的《并发编程测试》第 1 节里，讲锁的部分提到哪些具体概念？只列你从我资料里看到的。」 | 两次回答含义相同，均为「看不到你上传的文件内容，无法列出」；`vector_store` 共 15 行，全部是旧 `love_app` 演示文档（`metadata` 只有 `title`/`category`/`filename`），**没有任何一行带 `userId` 元数据** | `POST /ai/chat/stream` | 开 RAG 后回答应引用章节正文 | 两个 `FREE_CHAT` 会话各发一次，再 `psql -c "select count(*) from vector_store;"` 与 `select metadata from vector_store limit 3;` | **不通过**（复测见 6-9 ~ 6-12） |
| 6-9 | 上传即入向量库 | 上传两节小文档《RAG回归验证》 | `201`，`vector_store` 从 0 行变 1 行：`id=cd756751…`（等于 `knowledgeItemId`）、`metadata={"title":"RAG回归验证","userId":"008e1d32…"}`、`length(content)=2866` | `POST /knowledge-items` | 章节落库同时写向量，`metadata` 带 `userId` | 上传后 `psql -c "select id,metadata,length(content) from vector_store;"` | **复测通过** |
| 6-10 | `useRag` 开与关的差别 | 同一问题：「我上传的《RAG回归验证》里，corePoolSize 和 maxPoolSize 的关系是怎么说的？只按我材料里的说法回答。」 | 关：回答「看到你提到文件名，没看到正文内容，不能编造」；开：逐字引用「**corePoolSize 决定常驻线程数量，队列满后才扩容到 maxPoolSize**」，并说明「材料里只有这一句」 | `POST /ai/chat/stream` | 开 RAG 时引用原文，关时明确说看不到 | 同一会话连发两次，只改 `useRag` | 通过 |
| 6-11 | 跨用户隔离 | `user2` 开 `useRag` 问同一问题 | 回答「你提供的 Context 区域是空的，我没有看到《RAG回归验证》里的任何内容，所以无法按材料回答」 | 同上 | 检索不到 `lcz` 的向量行 | 用 `user2` 的 Cookie 重跑 6-10 的开启版 | 通过（`filterExpression` 生效） |
| 6-12 | `useTools` 能拿到实时时间 | `useTools=true` 问「现在几点了？只告诉我当前时间。」 | 回答 `2026-10-24 10:42:56`，本机实际时间 `2026-10-24 10:42:57` | 同上 | 时间与当前时间一致（`DateTimeTool` 被调用） | 创建 `FREE_CHAT` 会话后发一次 | 通过 |
| 6-13 | 聊天可见的工具只有 3 个 | 读代码 | [ToolRegistration.java:76-82](file:///d:/code/Java/ai-agent-study/src/main/java/com/lcz/aiagentstudy/tools/ToolRegistration.java#L76-L82) 的 `chatTools` 只注册 `WebSearchTool` / `WebScrapingTool` / `DateTimeTool` | — | 不含 `TerminalOperationTool` 等有副作用的工具 | 静态确认 | 通过 |
| 6-14 | 观测写库失败不影响聊天 | `alter table t_ai_call rename to t_ai_call_bak;` 后发一条聊天 | `200`，正常流式输出「线程池的作用是：预先创建并复用一组线程…」；表恢复后 `t_ai_call` 仍是 13 行，**没有新增** | `POST /ai/chat/stream` | 聊天照常返回，观测只丢一行 | 改表名 → 发消息 → 改回表名 | 通过（`AiCallRecorder` 吞异常生效） |
| 6-15 | 注释掉非流式 RAG 接口后仍可编译 | 注释 `LoveApp.doChatWithRag` 后 `mvn -DskipTests compile` | 无输出，退出码 0 | — | 编译通过 | `mvn -q -DskipTests compile` | 通过 |
| 6-16 | 长章节向量化不再超 token 上限 | 前端上传《JUC》（8 章），其中 3 章分别为 `11988` / `11960` / `9596` 字 | **修复前**：这三章各报一次 `Tokens in a single document exceeds the maximum number of allowed input tokens`，资料落库但无向量；**修复后**：8 章全部入库，`vector_store` 共 71 行，每章块数恰好等于 `ceil(字数/1000)`（13/13/13/10/13/10/5/6），日志无 WARN | `POST /knowledge-items/upload` | 任何长度的章节都能完成向量化 | 上传后 `psql -c "select metadata->>'knowledgeItemId',count(*) from vector_store group by 1;"` | 通过（修法见 [KnowledgeService.java](file:///d:/code/Java/ai-agent-study/src/main/java/com/lcz/aiagentstudy/service/KnowledgeService.java#L157-L219)：1000 字一块、10 条一批、每块独立 UUID） |
| 6-17 | RAG 召回真实章节原文 | `useRag=true` 问：「`Executors.newScheduledThreadPool` 的核心线程数默认是多少？可能带来什么风险？」 | 回答里直接引用 Context 原文：`Context（你给的材料）里对 newScheduledThreadPool 只提了一句——「核心线程数默认无界」`，并围绕该句展开引导 | `POST /ai/chat/stream` | 回答引用该章正文，而不是泛泛而谈 | 创建 `FREE_CHAT` 会话（`8962a4b4`）后发一次 | 通过（71 条向量中只有 `lcz` 自己的章节，`userId` 过滤同时生效） |
| 6-18 | 上下文预算生效（8000） | 在 `FREE_CHAT` 会话 `c3e1f1c1` 连发 12 轮，每轮 750 字 | 日志出现 5 次 `聊天历史按预算裁剪`，最后几条为 `保留 13/14 条，7701 字符`、`保留 12/16 条，7758 字符`、`保留 11/18 条，7252 字符`、`保留 11/20 条，7436 字符`、`保留 11/22 条，7647 字符`，均 ≤ 8000 | `POST /ai/chat/stream` | 历史超过 8000 字符时裁剪，且保留量贴着预算 | 连发 12 轮后 `Select-String "$env:TEMP\aiagent-backend.log" -Pattern '聊天历史按预算裁剪'` | 通过（需要 `com.lcz.aiagentstudy.chatmemory: DEBUG`，`application.yml` 已配） |
| 6-19 | 预算改 500 后保留条数下降 | 把 `app.ai.context.max-history-chars` 改成 `500`，重启，在新会话 `adb5c282` 发 3 轮、每轮 750 字 | 日志：`保留 1/2 条，430 字符（预算 500）`、`保留 1/4 条，276 字符（预算 500）`；保留条数从 11~13 掉到 1 | 同上 | 预算变小后保留条数明显变少，证明配置真的注入 | 改配置 → 重启 → 发 3 轮 → 查日志 | 通过（验完已改回 8000 并重启） |
| 6-20 | 失败调用落行 `success=false` | mock `deepSeekChatModel` 抛 `RuntimeException("mock-ai-down")`，走一次真实 `PracticeService.guide` | `guide` 按预期抛出同一异常；`t_ai_call` 新增一行：`scene=PRACTICE`、`success=false`、`error_code=RuntimeException`、`prompt_tokens=null` | — | 失败必须落行且带错误类型 | `mvn -Dtest=FailedCallRecordingTest test`（`@Transactional`，跑完回滚） | 通过（`Tests run: 1, Failures: 0`） |
| 6-21 | 跳过不算作答、不产生成绩 | 本轮 2 道未答题 `q31` / `q32` 各调一次 `POST /quiz/{questionId}/skip` | 两次均成功；`t_question.skipped=true`；本轮未答列表由 2 条变 0 条；`t_test_record` 不新增行 | `POST /quiz/{questionId}/skip` | 跳过只标 `skipped`，不落成绩 | 跳过「开始复习」里的两道题后重新打开该会话 | 通过 |
| 6-22 | 有排期的题跳过后排期推后 1 天 | 对 `t_review_schedule` 中有排期的题执行跳过 | `next_due` 由 D 推到 D+1；自测题（无排期）只标 `skipped`、排期不变 | 同上 | 复习排期推后 1 天，自测题不动 | 查 `t_review_schedule.next_due` 前后对照 | 通过 |
| 6-23 | 跳过全部到期题后复习入口关闭 | 跳完本轮题后调 `POST /review/start` | `400 {"message":"现在没有到期需要复习的题"}` | `POST /review/start` | 不再凭空新建一轮 | 同 6-21 后调一次 | 通过 |
| 6-24 | 越权跳过 | `user2` 跳过 `lcz` 的 `q31` | `403` | `POST /quiz/{questionId}/skip` | `403`，不改他人数据 | 用 `user2` 的 Cookie 调同一个接口 | 通过 |
| 6-25 | 重复跳过拦截 | 对已 `skipped` 的题再调一次 | 代码按「已跳过报 400」实现；**守卫写完未重启后端，未实测** | 同上 | `400` | 重启后重跑 6-21 的第二次调用 | 待验 |

> 6-16 / 6-17 在本次验收中重建了向量库：先 `delete from vector_store where metadata->>'userId'='008e1d32…'`、再 `delete from t_knowledge_item where doc_title='JUC'`，然后重新上传。旧版向量（整章一条）已全部清空，`vector_store` 现在只存 1000 字粒度的块。

**6-8 的根因（这段是当时推断，已被 6-9 ~ 6-12 的复测修正，保留原文以便对照）**

- 当时怀疑 `app.pgvector.enabled` 未生效，导致 [PgVectorStoreConfig.java](file:///d:/code/Java/ai-agent-study/src/main/java/com/lcz/aiagentstudy/rag/PgVectorStoreConfig.java#L32-L34) 的 `pgVectorStore` bean 没创建。
- [LoveApp.java](file:///d:/code/Java/ai-agent-study/src/main/java/com/lcz/aiagentstudy/app/LoveApp.java#L168-L169) 的 `loveAppVectorStore` 是 `@Autowired(required = false)`，bean 缺失时为 `null`，`useRag && loveAppVectorStore != null` 直接短路，**不报错、不告警，静默跳过检索**。
- [KnowledgeService.indexQuietly](file:///d:/code/Java/ai-agent-study/src/main/java/com/lcz/aiagentstudy/service/KnowledgeService.java#L162-L167) 的 `vectorStore == null` 分支与 `catch (Exception)` 分支都只打 warn，所以「没入库」和「入库失败」在接口上表现完全一样。
- **补正**：`app.pgvector.enabled: true` 实际写在 `application.yml` 里，不是 `application-local.yml`。复测时清空旧数据、注释掉启动自动灌库并重启后，同样的上传立刻写入了带 `userId` 的向量行，因此当时更可能是**运行中的实例仍是开关打开之前启动的**（配置改了没重启），而不是键名写错。原始三行保留，用来记录「静默降级让排查只能靠查库」这件事。

> 6-9 那份《RAG回归验证》写了两个 `##` 标题，但只切出 1 章（2866 字）：两节各约 1433 字，低于 3000 字下限，按 `DocumentSplitter` 的合并规则并成了一章。这是设计预期，不是切分 bug。

> 6-4 的 `CHAT` 行 `promptTokens=0` 是 `coalesce(sum(...),0)` 的结果，不是「零消耗」。前端渲染时该列要显示 `—`，并靠 `callsWithoutUsage` 说明原因。
> 代码层面已确认 `recordCall` 在取文本之前执行，所以「模型正常返回空串」不会落到 `failedCalls`；本次摘要非空，该分支未被真正触发。

***

## 9. 模型表现记录（不是功能验收）

| # | 场景 / 问题 | 提示词 | AI 回复 | 期望 | 复现方法 | 结论 |
| --- | --- | --- | --- | --- | --- | --- |
| 7-1 | 出题评测第 1 轮（10 份跨领域材料） | `QuizService.SYSTEM_PROMPT` 第 1 版 | 5/10 通过；失败 5 条里 4 条是字符串锚点误报（`Proxy`、`布隆过滤器`、`预主密钥`） | ≥7/10 | `mvn -Dtest=QuizPromptEvalTest test` | 不通过，判定为评测规则有缺陷 |
| 7-2 | 出题评测第 2 轮（改用 AI 裁判 + 规则层） | 第 2 版提示词 | 规则层 10/10；裁判 0 条泄露、0 条超纲、0 条缺听众 | 规则层 10/10，语义告警人工复核 | 同上，报告在 `target/quiz-eval-report.md` | 通过（人工复审口径见本节末） |
| 7-3 | 判分措辞 | `GradingService.SYSTEM_PROMPT` 第 1 版 | `gaps` 给出 `2m` | 不给数值 | 见 3-2 | 不通过，已修 |
| 7-4 | 判分评测（`GradingEvalTest`，2026-10-24） | `GradingService.SYSTEM_PROMPT` 当前版，6 组用例各跑 3 次 | 控制台：`判分评测：稳定 5/6，与人工标注一致 4/6，不稳定=[HNSW 的分层结构 / PASS]`；`Tests run: 1, Failures: 0`，`BUILD SUCCESS` | 稳定 ≥5/6、一致 ≥4/6（测试断言阈值） | `mvn -Dtest=GradingEvalTest test` | 通过，但只到阈值下沿：一致率 4/6，`HNSW 的分层结构` 三次判分有一次跳出 `PASS`。判分稳定性是模型能力边界，不是接线问题 |
| 7-5 | RAG 评测（`RagEvalTest`，2026-10-24） | 测试类自带的 5 条语料（HNSW / 距离度量 / 文本分片 / 重排 / IVFFlat），写入独立 `rag-eval-user`，5 个问题各查 topK=5 | 控制台：`RAG 评测：命中 5/5`；`Tests run: 1, Failures: 0`，`BUILD SUCCESS`；跑完 `vector_store` 回到 `71` 行、`1` 个用户，说明 `finally` 里的 `delete(ids)` 生效 | 命中 ≥4/5（测试断言阈值） | `mvn -Dtest=RagEvalTest test` | 通过（此前的「语料绑死 love_app」判断有误：语料在测试类里硬编码，与线上库无关） |

**语义判定口径（人工复审用）**：题面是否替用户说出了「用户本来要输出的那个答案」。
点名概念、给前提或现象、反事实提问都不算泄露；直接给出结论、数值、选择才算。

***

## 10. 未覆盖与待验

| 项 | 为什么没验 | 什么时候补 |
| --- | --- | --- |
| 扫描件 PDF | 用户明确跳过 | 需要 OCR 时 |
| `PRACTICE` 会话后续追问 | 本阶段只做首轮生成，没验证「接着追问会走通用聊天提示词」（技术债 13） | 前端练习对话页联调时 |
| P95 的分位行为 | 每个场景样本仍少，`percentile_cont(0.95)` 退化成一个普通值，等于没验分位数 | 同一场景攒够 20 行以上再抽查一次 |
| 旧章节回填向量 | `indexQuietly` 只在写入时调用；2026-10-24 已清空全部测试资料，旧章节不必回填 | 如需导入非本次上传的历史资料，再写一次性回填 |
| 重复跳过的 400 守卫 | `QuizService.skip` 的「已跳过报 400」分支写完未重启后端，未实测（6-25） | 重启后重跑一次跳过 |

> 2026-10-24 补齐并移出待验表的有：`PASSED` 三态（1-20）、超长单节均分切块（1-21）、
> 阶段 5 材料截断（5-9）、上下文预算（6-18 / 6-19）、失败调用落行（6-20）、`RagEvalTest`（7-5）。

***

## 11. 前端页面（2026-10-24，视觉改版与窄屏适配）

验收日期 2026-10-24，账号 `lcz`，后端 8123、前端 5173。桌面逐页核对用内置浏览器子代理截图；
375px 用 Playwright（`viewport 375×812`，登录后依次访问六个页面）。

| # | 场景 / 问题 | 输入 | 实际响应 | 接口 | 期望 | 复现方法 | 结论 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 11-1 | 资料页按文档分组 + 章节三态 | 浏览器打开 `/materials` | 1 份文档「JUC」、共 7 章 · 已通过 0 章；每章有两位序号、摘要、状态点（第 1 章「进行中」，其余「未开始」）与字数 | `GET /knowledge-items` | 按文档分组，章节三态齐全 | 打开 `/materials` | 通过 |
| 11-2 | 章节阅读页 | 第 1 章点「开始学习」 | 跳 `/materials/{knowledgeItemId}`；抬头 `第 1 / 7 章`；正文 11,220 字且 `##` 标记已去除；底栏有上一章/下一章与两个检测入口 | `GET /knowledge-items/{id}` | 正文可读、翻页可用、检测入口齐全 | 同上 | 通过 |
| 11-3 | 「怎么练」弹层 | 某章点「怎么练」 | 弹层展示该章练习方案；`#` 与 `**` 按标题/段落分块渲染；「继续追问」在 `PRACTICE` 会话下可点 | `POST /practice/guide` | 方案可读，追问绑定会话 | 同上 | 通过 |
| 11-4 | 待办页总览与分组 | 打开 `/todo` | 总览「共 2 项待处理 · 复习 0 道 · 补讲 0 份 · 未测 2 份」；「还没测过」组 2 行，行间发丝线、无卡片边框 | `GET /todo` | 三类分组 + 一行总览 | 打开 `/todo` | 通过 |
| 11-5 | 进度页数字栏与柱图 | 打开 `/progress` | `MASTERED 0 / 7`、`STREAK 1 天`、`DUE 0 道`；三栏竖线分隔；8 周柱图共用一条连续基线，柱顶标数值 | `GET /progress/summary` | 统计与库内一致、基线连续 | 打开 `/progress` | 通过 |
| 11-6 | 测验页序号分节 + 四态标签 | 从待办进入 `/quiz/{conversationId}` | 抬头「共 2 道 · 已完成 2」+ 进度条；每题两位序号；标签为中性的 `SKIPPED`（不再误用 FAIL 的红） | `GET /quiz/{conversationId}` | 序号分节、跳过着色中性 | 待办点「开始测验」 | 通过 |
| 11-7 | 对话页侧栏与深色聊天区 | 打开 `/guide` | 侧栏约 280px，选中会话淡青底 + 左缘 2px 亮条；用户气泡 `rgb(2,132,199)→rgb(14,165,201)` 渐变、文字 `rgb(4,18,27)`；AI 气泡 `rgb(20,27,35)` 带细边 | `GET /conversations` | 全页统一深色、气泡对比度足够 | 打开 `/guide` | 通过 |
| 11-8 | 375px 六页无横向溢出 | Playwright `375×812` 访问 `/materials`、`/todo`、`/progress`、`/guide`、`/quiz/{id}`、`/materials/{id}` | 六页均 `scrollWidth = clientWidth = 375`；`console errors = []` | — | 任何页面都不出现横向滚动 | DevTools 响应模式或 Playwright 设 375px | 通过 |
| 11-9 | 窄屏对话页上下堆叠 | 375px 打开 `/guide` | 侧栏变为顶部横向会话条（行内横向滚动），聊天区占满剩余高度 | — | 不再两列并排溢出 | 同 11-8 | 通过 |
| 11-10 | 窄屏阅读页底栏 | 375px 打开 `/materials/{id}` | 底栏实色 `#0e1116`，正文不透出；上一章/下一章标题各自省略号截断，不互相压字 | — | 底栏可读、分页标题不重叠 | 同 11-8 | 通过 |
| 11-11 | 「资料检索」开关生效 | `/guide` 新建会话，开关关 / 开各问一次「我上传的资料里，关于锁的部分讲了哪些内容？」 | 关：回答「我这边看不到你上传的资料内容」并反问资料类型；开：按资料内容列出锁升级（无锁→偏向→轻量级→重量级）、乐观/悲观锁、CAS 与 ABA、`ReentrantLock`、`ThreadLocal` | `POST /ai/chat/stream`（`useRag` true/false） | 关时不编造资料内容，开时能引用资料里的具体条目 | Playwright 登录 `lcz` 后两次提问，读 `.bubble.ai` 文本 | 通过（2026-10-24 17:29） |
| 11-12 | 「工具」开关生效 | 同 11-11，开关关 / 开各问一次「现在几点？」 | 关：回答没有实时时钟、无法告知；开：返回 `2026 年 10 月 24 日 17:31:48` | `POST /ai/chat/stream`（`useTools` true/false） | 关时不调用工具，开时能取系统时间 | 同上 | 通过（2026-10-24 17:31） |
| 11-13 | AI 回答 markdown 渲染 | `/guide` 新会话提问「用 markdown 列出 3 个 JUC 常见锁，要表格 + java 代码块 + 两个要点」 | 气泡内渲染出 1 个表格（表头「锁名 / 特点」）、1 个 java 代码块、2 个要点列表、2 处加粗；正文无 `**`、`|---` 原文残留；代码块可横向滚动 | `POST /ai/chat/stream` | 表格 / 代码块 / 列表 / 加粗正常显示 | Playwright 登录后提问，统计 `.text-seg.md` 内 `table` / `pre` / `li` / `strong` 数量 | 通过（2026-10-24 17:41） |
| 11-14 | markdown 在 375px 不撑宽页面 | 375×812 打开同一会话 | `scrollWidth == clientWidth == 375`；表格在气泡内横向滚动，不推出视口 | — | 窄屏不出现横向滚动 | Playwright `viewport 375×812` | 通过（2026-10-24 17:41） |
| 11-15 | 首页 / 登录页浅色 | 打开 `/`、`/login` | 页面底色 `rgb(252, 252, 252)`；入口卡白面 + 1px 发丝描边 + 纯黑「进入答疑区」胶囊；登录页输入框白底描边、主按钮纯黑胶囊 | — | 与 ChatGPT / DeepSeek 网页版同系的浅色中性风 | Playwright 截图 `d_home.png` / `m_home.png` | 通过（2026-10-24 18:20） |
| 11-16 | 资料 / 待办 / 进度页浅色 | 登录 `lcz` 后打开三页 | 令牌统一为 `#fcfcfc` 底 / `#0d0d0d` 主文字；主按钮纯黑白字、次按钮白底描边；资料章节状态点为灰 `#d4d4d8`，待办复习点为琥珀、进度柱体纯黑 | `GET /knowledge-items`、`GET /todo`、`GET /progress/summary` | 三页与首页同色系、无深色残留块 | 截图 `d_materials.png` / `d_todo.png` / `d_progress.png` | 通过（2026-10-24 18:20） |
| 11-17 | 对话 / 阅读 / 测验页浅色 | 打开 `/guide`、`/materials/{id}`、`/quiz/{id}` | 对话页 AI 回答无气泡平铺、用户消息 `#f4f4f5` 圆角、输入框 26px 圆角胶囊；阅读页正文 720px 列、底栏**白色实色**；测验页题号分节、`SKIPPED` 为中性灰标签 | `GET /conversations`、`GET /knowledge-items/{id}`、`GET /quiz/{id}` | 三页无深色块，层级靠描边 / 字号 / 留白 | 截图 `d_guide.png` / `d_read.png` / `d_quiz.png` | 通过（2026-10-24 18:20） |
| 11-18 | 改版后 6 页 × 2 视口无溢出、无报错 | Playwright 1440×900 与 375×812 访问 `/`、`/guide`、`/materials`、`/todo`、`/progress`、`/materials/{id}`、`/quiz/{id}` | 14 张截图全部 `scrollWidth == innerWidth`（1440 与 375），每页 `console errors = []`；顺带修掉窄屏输入框占位被裁、阅读页正文被底栏压住两处缺陷 | — | 换肤不引入布局回归 | `node shots.mjs`（临时脚本，未入库） | 通过（2026-10-24 18:20） |
| 11-19 | 发送按钮文字溢出圆形 | 打开 `/guide`，看空态与有输入两种状态 | 改前 38px 圆内塞「发送 ↑」四个字符，「发送」被挤成两行、箭头掉到第二行（用户截图反馈）；改后按钮内只剩 18px 上箭头 SVG，空态 `#e5e5e8` 灰底灰箭头、可发送态 `#0d0d0d` 黑底白箭头，`scrollWidth/scrollHeight` 均不超出 38×38，1440 与 375 一致，`console errors = []` | — | 圆形按钮内只放图标，不再出现换行溢出 | `node send.mjs`（临时脚本，未入库）截图 `send_d_empty.png` / `send_d_filled.png` | 通过（2026-10-24 18:41） |
| 11-20 | 三个操作按钮进输入框、改图标 | 打开 `/guide`，看空态、开启态、悬停态 | 顶栏不再有按钮（`.h-right .tool-btn` 数量 0）；输入框内发送箭头左侧依次为检索 / 工具 / 做成题三个 34px（375px 下 32px）图标按钮；关闭态透明底灰图标、开启态黑底白图标（打开检索与工具后 `.tool-btn.on` 数量 2）；悬停浮出黑底说明气泡，`::after` 内容随状态变化（如「资料检索 · 已开启，按你上传的资料回答」）；1440 与 375 均 `scrollWidth == innerWidth`、`console errors = []` | — | 图标常驻、说明文字只在悬停/聚焦时出现，不占输入面积 | `node icons.mjs`（临时脚本，未入库）截图 `icons_d_idle.png` / `icons_d_on.png` / `tip_d.png` / `tip_m.png` | 通过（2026-10-24 19:05） |
| 11-21 | 375px 输入框被图标挤压 | 375×812 打开 `/guide` | 改前一行放不下「输入 + 3 图标 + 发送」，textarea 只剩约 90px，占位文案换行被裁；改后 `.composer-box` 竖排，图标行落到输入框下方整行右对齐，textarea 占满整行，占位文案单行完整 | — | 窄屏不牺牲输入区宽度 | `node icons.mjs` 截图 `tip_m.png` | 通过（2026-10-24 19:05） |
| 11-22 | 图标语义对齐：书 = 资料检索，放大镜 = 联网 | 打开 `/guide` 看输入框图标组 | 从左到右为书本（资料检索）/ 放大镜（联网，网页搜索 + 抓取）/ 剪贴板（做成题）/ 发送箭头；开启后两个开关均为黑底白图标；`data-tip` 依次为「资料检索 · …」「联网 · 已开启，可搜索与抓取网页；查时间始终可用」「把这轮对话做成 3 道题」，`console errors = []` | — | 图标与功能一对一，不再用滑杆/扳手这类含糊符号 | `node icons2.mjs` 截图 `icons2_idle.png` / `icons2_on.png` / `icons2_tip.png` | 通过（2026-10-24 19:29） |
| 11-23 | 查时间默认可用（不经开关） | 登录后两个开关都不点，发「现在几点？只回答当前时间，不要解释」 | 回到 `2026-10-24 19:02:49`，未出现「没有实时时钟」；同一轮 `useTools=false`，说明 `chatClockTool` 确实常挂 | `POST /ai/chat/stream`（`useRag=false, useTools=false`） | 不点开关也能拿到真实时间 | `node verify.mjs` 截图 `v_time_off.png` | 通过（2026-10-24 19:35） |
| 11-24 | 联网开关只控制网页搜索与抓取 | 关闭联网发「请联网搜索今天的实时热点新闻…」；开启联网后重问；再新建会话发「用搜索工具查 Spring Boot 3.5 发布时间」 | 关闭态：模型明说「工具里只有 `getCurrentTime`，没有联网搜索能力，查不到」；开启态工具清单变为 `getCurrentTime、searchWeb、scrapeWebPage`（请求体 `useTools:true` 已抓到）；同一会话里开启后模型仍沿用上一轮「没有搜索工具」的自我描述，**新建会话后**真实触发搜索，回答引用 `https://www.herodevs.com/blog-posts/...` 并给出「2025-05-22」等检索结果 | `POST /ai/chat/stream`（`useTools=false` / `true`） | 开关只影响搜索与抓取，时间始终可用 | `node verify.mjs`、`node probe.mjs`、`node search.mjs`（临时脚本，未入库）截图 `v_web_off.png` / `v_web_on.png` / `v_search_on.png` | 通过（2026-10-24 19:38） |
| 11-25 | 对话页引导词改为「授人以渔」口径 | 登录后进 `/guide`，点「新对话」看空态；再回 `/` 看入口卡片 | 顶栏标题「授人以渔」、状态行「在听，说说你卡在哪」；空态主文案「授人以鱼，不如授人以渔」，副文案「这里不直接给答案。你把思路讲出来，讲不清的地方回原文重看。」；四条建议提问均以「我先说/我猜/我讲一遍」开头；全页 `innerText` 不含「助手」二字；1440 与 375 均无横向溢出、`console errors = []` | — | 文案与产品定位一致，不再是通用答疑助手口吻 | `node copy.mjs`（临时脚本，未入库）截图 `copy_d_guide.png` / `copy_m_guide.png` / `copy_d_home.png` | 通过（2026-10-24 20:05） |
| 11-26 | 头像由「渔」字改为学士帽图标 | 进 `/` 看入口卡片徽标；进 `/guide` 看顶栏头像与空态大图标；点一个历史会话看每条 AI 消息的 26px 小头像与说话人名 | 三处头像均渲染为 `BrandMark` 学士帽 SVG（`hasSvg=true`），26px 下轮廓仍可辨；说话人标签为「考官 · 19:11」；页面文本不再单独出现「渔」字（仅「授人以渔」标题内出现）；AI 说话人标签同步为「考官」；1440 与 375 无横向溢出、`console errors = []` | — | 头像可被一眼识别为学习元素，不再让人猜「渔」是什么 | `node mark.mjs`（临时脚本，未入库）截图 `mark_d_home.png` / `mark_d_guide_empty.png` / `mark_d_guide_msg.png` / `mark_m_guide_empty.png` | 通过（2026-10-24 20:25） |
| 11-27 | 定位表述由「让用户先讲」改为「AI 不直接给答案」（11-25 复测修正） | 进 `/` 看入口卡片说明；进 `/guide` 点「新对话」看空态副文案与四条建议提问 | 卡片说明为「不直接给答案，先给方向。」；空态副文案为「这里不直接给答案。先给你方向，再给关键词，实在卡住才给最小示例。」；建议提问依次为「线程池核心数怎么定？先别给答案」「这段报错我怀疑是空指针，从哪查起」「AQS 排队那段没看懂，给个方向就行」「配置改了没生效，我想要排查顺序」，均不再要求用户先自述；副文案 1440 下 1 行、375 下 2 行且无截断；1440 与 375 无横向溢出、`console errors = []` | — | 文案与 `LoveApp.SYSTEM_PROMPT` 的分级提示机制一致，不再把主动权说成在用户身上 | `node copy2.mjs`（临时脚本，未入库）截图 `copy2_d_home.png` / `copy2_d_guide.png` / `copy2_m_guide.png` | 通过（2026-10-24 20:45） |

> 说明：11-1 ~ 11-7 的数据是 2026-10-24 当天库内状态（JUC 7 章、2 道题均已跳过），不是固定断言。

**本节未覆盖**

- 真机 iOS Safari / Android Chrome 未测，只验了 Chromium 375px。
- 平板 768~820 区间只有断点代码保证，没有实测截图。
- 重复跳过的 400 守卫（6-25）写完后未重启后端，仍待验。
- 「工具」开关只验了时间工具；网页搜索（Tavily）与网页抓取未在开关开启状态下实测。
- 对话页加载 Google Fonts（`fonts.gstatic.com`）在离线/被墙环境下会失败，浏览器回退到系统字体，不影响功能。
