# 验收记录与复现手册

> 本文件只记录**跑过的验收**：输入、实际响应、期望、复现方法、结论。
> 业务口径见 [`product-spec.md`](./product-spec.md)，怎么落代码见 [`mvp-backend-guide.md`](./mvp-backend-guide.md)。
> 新增验收一律追加到对应阶段表的末尾，不改历史行；失败重测另起一行并注明「复测」。

***

## 0. 使用规则

1. **每行一条命令，可单独复现**。`复现方法`列里出现的 `$B`、`$d`、`$env:PGPASSWORD` 见第 1 节。
2. **`AI 回复 / 实际响应`列只放关键片段**，长文本用 `…` 截断；完整响应放临时文件，不写进本文件。
3. **期望值不许为了通过而修改**。不通过就写「不通过」，在 `结论`列写原因，修复后追加「复测」行。
4. 两类记录分开：功能完备性（第 2~5 节）、模型表现（第 6 节）。**不要用模型表现替代功能验收**。

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

## 6. 模型表现记录（不是功能验收）

| # | 场景 / 问题 | 提示词 | AI 回复 | 期望 | 复现方法 | 结论 |
| --- | --- | --- | --- | --- | --- | --- |
| 6-1 | 出题评测第 1 轮（10 份跨领域材料） | `QuizService.SYSTEM_PROMPT` 第 1 版 | 5/10 通过；失败 5 条里 4 条是字符串锚点误报（`Proxy`、`布隆过滤器`、`预主密钥`） | ≥7/10 | `mvn -Dtest=QuizPromptEvalTest test` | 不通过，判定为评测规则有缺陷 |
| 6-2 | 出题评测第 2 轮（改用 AI 裁判 + 规则层） | 第 2 版提示词 | 规则层 10/10；裁判 0 条泄露、0 条超纲、0 条缺听众 | 规则层 10/10，语义告警人工复核 | 同上，报告在 `target/quiz-eval-report.md` | 通过（人工复审口径见第 6 节末） |
| 6-3 | 判分措辞 | `GradingService.SYSTEM_PROMPT` 第 1 版 | `gaps` 给出 `2m` | 不给数值 | 见 3-2 | 不通过，已修 |

**语义判定口径（人工复审用）**：题面是否替用户说出了「用户本来要输出的那个答案」。
点名概念、给前提或现象、反事实提问都不算泄露；直接给出结论、数值、选择才算。

***

## 7. 未覆盖与待验

| 项 | 为什么没验 | 什么时候补 |
| --- | --- | --- |
| 扫描件 PDF | 用户明确跳过 | 需要 OCR 时 |
| 5 万字上限拒绝 | 没造出真实超长文档，仅代码审阅 | 有真实长讲义时 |
| 出题重问分支（2-8） | 首轮出题时没有 `FAIL` 题 | 阶段 4 复习流程跑起来后 |
| 交错题（`REVIEW` 的 1 道混排） | 阶段 4 才实现 | 阶段 4 验收 |
| 待办三类与进度三指标 | 阶段 4 才实现 | 阶段 4 验收 |
