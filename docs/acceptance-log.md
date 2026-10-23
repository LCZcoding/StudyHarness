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

> 查库输出的中文在 PowerShell 控制台会显示成乱码（`鎴戞兂…`），那是 `psql` 的控制台编码问题，接口返回的 JSON 中文正常，不影响结论。
> 5-7 / 5-8 用到的两个 `conversationId` 是 5-1、5-2 本次产生的；查库需先设 `$env:PGPASSWORD`，再执行 `"D:\PostgreSQL\bin\psql.exe" -h 72.155.89.172 -U postgres -d ai_agent_study -c "<SQL>"`。

***

## 8. 模型表现记录（不是功能验收）

| # | 场景 / 问题 | 提示词 | AI 回复 | 期望 | 复现方法 | 结论 |
| --- | --- | --- | --- | --- | --- | --- |
| 7-1 | 出题评测第 1 轮（10 份跨领域材料） | `QuizService.SYSTEM_PROMPT` 第 1 版 | 5/10 通过；失败 5 条里 4 条是字符串锚点误报（`Proxy`、`布隆过滤器`、`预主密钥`） | ≥7/10 | `mvn -Dtest=QuizPromptEvalTest test` | 不通过，判定为评测规则有缺陷 |
| 7-2 | 出题评测第 2 轮（改用 AI 裁判 + 规则层） | 第 2 版提示词 | 规则层 10/10；裁判 0 条泄露、0 条超纲、0 条缺听众 | 规则层 10/10，语义告警人工复核 | 同上，报告在 `target/quiz-eval-report.md` | 通过（人工复审口径见本节末） |
| 7-3 | 判分措辞 | `GradingService.SYSTEM_PROMPT` 第 1 版 | `gaps` 给出 `2m` | 不给数值 | 见 3-2 | 不通过，已修 |

**语义判定口径（人工复审用）**：题面是否替用户说出了「用户本来要输出的那个答案」。
点名概念、给前提或现象、反事实提问都不算泄露；直接给出结论、数值、选择才算。

***

## 9. 未覆盖与待验

| 项 | 为什么没验 | 什么时候补 |
| --- | --- | --- |
| 扫描件 PDF | 用户明确跳过 | 需要 OCR 时 |
| `PASSED` 章节三态 | 需要同一章 3 题全部判 `PASS`，本次只验到 `NOT_STARTED` / `IN_PROGRESS` | 前端阅读页联调时一起验 |
| 超长单节均分切块 | 3.8「改动 2 补充」尚未改 `chunks()`，1-12 的碎片章问题待复测 | 改完 `chunks()` 后复测 1-12 |
| 阶段 5 超长材料截断 | `PracticeService.MATERIAL_LIMIT = 8000`，本次两份材料只有 404 / 90 字，没触发 `……（材料已截断）` 分支 | 拿一份超过 8000 字的章节跑一次 5-1 |
| `PRACTICE` 会话后续追问 | 本阶段只做首轮生成，没验证「接着追问会走通用聊天提示词」（技术债 13） | 前端练习对话页联调时 |
