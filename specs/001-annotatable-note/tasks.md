# Tasks · V2 可批注笔记

| 项 | 内容 |
|---|---|
| 输入 | `spec.md`（FR / AT）、`plan.md`（决策 / 契约 / 算法） |
| 状态 | **v1.1 · 顺序重排** · 2026-11-14 |
| 里程碑 | **G1** 后端批注链路可独立测（T007）· **M1** 阅读页批注可演示（T018）· **M2** 全量可演示（T022） |
| 执行节奏 | 一次只做一个任务 → 跑该任务的测试 → 交给用户 review 验收 → 再开下一个 |

> 格式：`- [ ] Tnnn [P] [FR-xx] 标题`。`[P]` = 与本阶段其它 `[P]` 任务文件不冲突、可并行落盘；仍须各自跑测试。
> **完成定义**：代码落盘 + 对应 AT / TC 通过 + 关闭开关的行为无回归。勾选任务时把对应 AT 编号写在提交信息里。
> 开工前：确认当前 git 分支（**只在 `vibe`**，不建分支 / 不 PR / 不 push），并确认 T001 迁移已执行。

---

## 0. 本版重排说明（v1.1）

业务与 `spec.md` **一字未改**，只调整任务顺序、编号与阶段边界。起因见 7.2 的排布缺陷登记：旧版按「演示画面」切 M1/M2，而批注从第一天就统一支持 SECTION / NOTE 两种正文，NOTE 的读写底座却被推到 Phase 2，导致 T004 只能靠临时 SQL 绕过、多处任务出现环与隐藏依赖。

**新的排布原则**（`plan.md` 3.6）：

1. 两种正文共用的读写底座先做齐，再写用它的服务。
2. 先有被调用的方法，再改调用方；改签名的一方排在最后。
3. 前端组件不 import 后续阶段才创建的模块，跨阶段能力由父组件注入。
4. 闸门只跑「该阶段能力已经具备」的 AT，跨阶段的 AT 后移。

**旧 → 新编号映射**（历史汇报、`docs/impl-log.md` 里的旧编号按此表换算）：

| 旧 | 新 | 变化 |
|---|---|---|
| T001 | **T001** | 迁移（已完成，不变） |
| T002 | **T002** | 领域模型（已完成，不变） |
| T003 | **T003** | `AnnotationRepository`（已完成，不变） |
| T012 | **T004** | ⬆ **Message 层 NOTE 能力提前到共享底座** |
| T004 | **T005** | ⬇ `AnnotationService` **打回未完成**，按新前置重做 |
| T005 | **T006** | `AnnotationController` |
| — | **T007** | 新增 G1 后端闸门 |
| T013 | **T008** | `ChatEvent.Note` |
| T016 | **T009** | ⬆ `LoveApp.doNoteByStream` 提前到 `ChatService` 之前 |
| T015 | **T010** | `ChatService` 分流 |
| T014 | **T011** | ⬇ `AiChatController` 第 6 参后移（签名由 T010 提供） |
| T017 | **T012** | `NoteService` / `NoteController` |
| T006 | **T013** | `splitBlocks` |
| T007 | **T014** | `api/annotations.js` |
| T009 | **T015** | ⬆ `AnnotationCard.vue` 提到 `AnnotatableDoc.vue` 之前 |
| T008 | **T016** | `AnnotatableDoc.vue` |
| T010 | **T017** | 阅读页接入 |
| T011 | **T018** | M1 闸门（AT 清单已按阶段重切） |
| T018 | **T019** | `api/notes.js`（路由拆出，避免与 T020 成环） |
| T019 | **T020** | `NoteDetailView.vue` **＋路由**（页面与动态 import 同一次提交） |
| T020 | **T021** | 聊天侧开关与 NOTE 卡片 |
| T021 | **T022** | M2 闸门 |
| T022 + T023 | **T023** | 全量回归 + 执行记录归档（合并为收尾一步） |

> **已完成代码的处理**：T001~T003 的产物是干净底座，保持完成、不返工。原 T004 落盘的 `service/AnnotationService.java` 与 `AnnotationServiceTest.java` 含 `JdbcTemplate` 临时 SQL，属于「绕过去」的实现——**文件先留着不删**，但在 T005 标为未完成；做到 T005 时重构替换（移除临时 SQL 与 `JdbcTemplate` 注入），并按新的 `plan.md` 6.1 重新验收。在此之前不把它当作已完成资产。

### 0.1 执行方式约定（2026-11-14 用户决定，v1.2 补前端优先）

- **当前正在做的批注接口链不改**：T005 → T006 → T007 维持 v1.1 的分层顺序，已于 2026-11-14 全部完成（G1 PASS）。
- **从后面的新接口起改成垂直切片（vertical slice / API-first）**：同一条接口链所需的 Controller → Service → Repository 在**一次落盘**里打通，验收物是「真实 HTTP 请求打到真实库」的端到端测试（模型用 `@MockitoBean` 顶掉），而不是只有 Service 单元测试。
- **前端优先（用户 2026-11-14 新决定）**：原话「如果这个阶段适合写前端，优先写前端，然后接入后端，这样我可以低成本的通过图形化，看到效果。以后前后端并行开发，开发完联调。」判断标准是**该阶段前端的跨组前置是否已就绪**；就绪就先写前端，让人能早点在浏览器里点开看到东西，后端随后按同一份契约接上。
- **每完成一次前端任务，汇报里必须提醒用户去看效果**（用户 2026-11-14 要求）。若该步只是纯函数 / 页面尚无变化，要如实说「现在还看不到画面」，写清当前能验什么、并指出第一个能看到画面的任务编号，不许含糊带过。
- **只改执行与验收方式，不改编号、依赖、FR 标签、闸门覆盖的 AT/TC、旧→新映射表**：组内仍按编号顺序落盘（保留「依赖编号小于自身、按序必能编译」的保证）；**组的推进顺序改为「依赖就绪 + 前端优先」，不再按组编号顺序**。G4 提前不破坏编译保证：它的跨组前置只有 T006 / T007，均已完成，组内依赖也全部指向更小编号。

| 组 | 包含任务 | 打通的接口 | 该组的验收物 | 推进顺序 |
|---|---|---|---|---|
| G1（不切片，已完成） | T005 → T006 → T007 | `GET/POST/PATCH/DELETE /annotations` | 四接口 MockMvc 可调、越权与限额在模型调用前被拒（G1 闸门 PASS） | ① 已完成 |
| **G4** | T013 → T018 | 阅读页选区批注（**直接接已通的批注四接口，无需 mock**） | **M1：浏览器可演示**——选中正文提问 → 右栏短解释 → 选词追问 → 默认折叠的追问链（点开独立平铺）→ 刷新层级还在 | ② ✅ 已完成（2026-11-15 M1 闸门 PASS） |
| G2 | T008 → T009 → T010 → T011 | `POST /ai/chat/stream`（`asNote=true`） | SSE 帧序含 `note → done`、落库 `type=NOTE`、`scene=NOTE_GENERATE` | ③ ✅ 已完成（2026-11-15） |
| G3 | T012 | `GET/PUT /notes/{id}` | 读笔记 / 改正文 / 越权 403 / 非 NOTE 拒绝 | ④ ✅ 已完成（2026-11-15） |
| G5 | T019 → T022 | 笔记详情页与聊天卡片 | M2 浏览器可演示 | ⑤ **全组 ✅（2026-11-15，T022 闸门 7 条 AT + 2 条 TC 全过）** |
| G6 | T023 | — | 全量回归 + 执行记录 | ⑥ |

**「前后端并行 + 联调」在本项目的可操作定义**（单人 + AI 不是真并发，是交替推进、每一步尽量先交能看见画面的那块）：

1. 契约唯一真源是 `plan.md` 5.2；前端只按契约写，**不改字段名 / 形状**，后端按同一份契约实现。
2. 后端还没就绪时，前端在 `api/*.js` 一层放 mock/stub（不散落到组件里），后端就绪后只删这一层就切成真接口。G4 阶段后端已通，这一步直接省掉。
3. 前端验证手段：`npm run dev` + 浏览器手工点（本项目前端无单测框架）；纯函数 `splitBlocks` / `resolve()` 用 Node 脚本跑 TC-01 / TC-02；`npm run build` 保编译。
4. 两边都完成后在 **M1（T018）/ M2（T022）** 闸门做一次联调，闸门通过标准不变。

> 「端到端」在本项目的边界：HTTP 请求 → Controller → Service → Repository → 真实库；浏览器 UI 归 G4/G5。前端组件之间仍有编译依赖，G4/G5 继续按组件顺序落盘。

---

## Phase 0 · 数据基线

- [x] **T001** `[NFR-01][NFR-04]` 落地迁移脚本并手工执行 · ✅ 2026-11-14
  - 文件：`db/migration-v2.sql`（含回滚段，已用块注释包住，不随 `psql -f` 执行）
  - 依赖：无 · 覆盖 C3/C6/C7 · 验收：`psql -f` 成功；`t_annotation` 存在；两个 CHECK 已放宽；`t_message.updated_at` 存在
  - **实际结果**：已在 `ai_agent_study`（PG 16.15）执行成功；`t_annotation` 11 列 / 3 索引齐全；回滚事务内实测 `NOTE` 消息与 3 个新 scene 均可写（等价 TC-09）；自引用 `ON DELETE CASCADE` 生效（删父 `DELETE 2`、剩 0）；迁移前后 `t_message` 189 行、`t_ai_call` 171 行未变。旧系统（master 同份代码）全部读写路径实测兼容。**TC-10 未跑**（需模拟未迁移环境），见 T023。
  - 闸门已解除，T002 可开始。

---

## Phase 1 · 后端共享底座（G1）

> 本阶段把「两种正文都靠它」的能力一次做齐。T004 是整条链的关键前置：`AnnotationService` 的 NOTE 分支、`NoteService`、笔记生成落库、历史消息卡片都依赖它。

- [x] **T002** `[P]` `[FR-08]` 新增批注领域模型 · ✅ 2026-11-14
  - 文件：`model/TargetType.java`（`SECTION` / `NOTE`）、`model/AnnotationRow.java`
  - 依赖：T001 · 完成：`AnnotationRow` 字段与 `plan.md` 5.2 #4 响应元素一致
  - **实际结果**：另加 `AnnotationRowTest.java`（纯 POJO 契约测试）。3 个用例全过、BUILD SUCCESS。断言：枚举恰为 SECTION/NOTE 且非法值反序列化失败；JSON 恰 8 字段且不含 `userId/targetType/targetId`；顶层 `parentId=null`、子批注 `parentId` 与 `edited` 正确。归属字段留给 T003 在 Repository 内定义内部 record。

- [x] **T003** `[P]` `[FR-12]` 新增 `AnnotationRepository` · ✅ 2026-11-14
  - 文件：`repository/AnnotationRepository.java` + `AnnotationRepositoryTest.java`
  - 依赖：T001 · 完成：`plan.md` 4.4 全部 SQL 落盘，9 个方法全实现，全部 `JdbcTemplate` 占位符
  - **实际结果**：内部 record `AnnotationEntity`（全字段）与 `NewAnnotation`（insert 入参），`toRow()` 转 T002 响应 DTO。`findByTarget` / `countByTarget` 额外带 `user_id` 纵深防御；列表排序用 `created_at, id`（同一事务内 `created_at` 可能完全相同，只按它排会让子批注排到父前面）。集成测试 4 用例，与 T002 一起 `Tests run: 7, Failures: 0`；只读复查库回滚干净。祖先链上下文用 `findById` 逐级上溯即可，未加额外方法。

- [x] **T004** `[FR-03][FR-12]` **Message 层 NOTE 能力（关键前置，原 T012 提前）** · ✅ 2026-11-14
  - 文件：`model/MessageType.java`、`model/MessageRow.java`、`repository/MessageRepository.java`、`service/MessageService.java`
  - 依赖：T001 · 对应 C1 / C2 / C3
  - 完成：枚举加 `NOTE`；`MessageRow` 加 `type`；历史 SQL SELECT 加 `m.type`；`MessageRepository` 加 `findNoteById` / `noteBelongsToUser` / `updateContent` / `insertReturningId`；`MessageService` 加 `@Transactional appendNoteRound(...)` 返回 `long`
  - **这一步同时是 T005、T010、T012、T021 的前置**：NOTE 归属与全文取数、笔记落库、笔记读写接口、历史消息卡片都从这里取。TC-08（历史接口返回 `type`）必须通过，否则 NOTE 卡片刷新后认不出来
  - **实际结果**：`MessageRow` 追加末位 `type`（旧 4 字段顺序不变）；`findAllByConversation` 改表别名 `m` 并 SELECT `m.type`。`MessageRepository` 新增内部 record `NoteMessage(messageId, conversationId, type, content, createdAt, updatedAt)` 与三个方法：`findNoteById(long) → Optional<NoteMessage>`（只认 `type='NOTE'`）、`noteBelongsToUser(long, UUID) → boolean`（plan 4.4 的跨两层 JOIN）、`updateContent(long, String) → Optional<OffsetDateTime>`（同时 `updated_at=now()`，0 行返回 empty）。**`insertReturningId` 是 V1 就有的方法，直接复用，未重复新增**。`appendNoteRound` 按 USER/QUESTION + ASSISTANT/NOTE 两条 + `touch` 组织，返回 NOTE 行 id。旧 `AnnotationService.java` 一行未动（按约定留到 T005 重做）。方法签名已同步写进 `plan.md` 6.1，T005 / T012 直接调用即可。
  - **测试**：新增 `MessageRepositoryTest`（4 用例：历史带 type、跨两层归属含「非 NOTE 不算」、笔记全字段读取、正文保存与非 NOTE 拒写）；扩展 `MessageServiceTest`（2 用例，`appendRound` 原用例补 TC-08 断言，新用例验证两行 + 返回 id + `touch` + `@Transactional` 注解）；扩展 `ConversationControllerTest`（14 用例，新增 `messages_returnsNoteType` 把 **TC-08 端到端** 覆盖到历史接口 JSON）。
  - **验证**：`-Dtest=AnnotationRowTest,AnnotationRepositoryTest,MessageServiceTest,MessageRepositoryTest,ConversationControllerTest` → **27/27 全绿**（T002/T003 无回归）；另跑旧链路 `-Dtest=AnnotationServiceTest,ChatServiceTest,AiChatControllerTest` → **12/12 全绿**，关闭开关时的问答 / SSE / 旧批注服务行为不变。数据库测试均 `@Transactional` 回滚。

- [x] **T005** `[FR-07][FR-08][FR-12][FR-13][NFR-01]` **`AnnotationService`（打回未完成，按新前置重做）** · ✅ 2026-11-14
  - 文件：`service/AnnotationService.java`、`service/AnnotationServiceTest.java`（替换现有临时实现）
  - 依赖：**T002、T003、T004**（旧版漏登记 T004 对应的能力，此处补齐）
  - 完成：`plan.md` 5.2 #5 的 7 步顺序、I1~I4 校验、6.1 上下文组装与埋点、附录 A2/A3 提示词、200 字硬截断；`ask()` 不标 `@Transactional`；另含 `list` / `updateAnswer` / `delete`，使 T006 是纯薄 Controller
  - **重做的唯一目标**：NOTE 归属改调 `messageRepository.noteBelongsToUser`、全文改调 `messageRepository.findNoteById`（均由 T004 提供），**移除 `JdbcTemplate` 注入与 Service 内的两条 SQL**；测试同步改为 mock / 真实走 Repository。其余行为（7 步顺序、限额前置、`locateBlock` 前后各 300 字、500 错误码、`AskRequest` 定义在 Service 内）**保持不变**
  - 测试：沿用 `@SpringBootTest @Transactional` + `@MockitoBean(name="deepSeekChatModel")`，覆盖顶层/子批注成功、200 字截断、两个 scene 埋点、父 `blockKey` 继承、非法 id / 越权 / I1 / I2 / I3 在模型调用前拦截（`verify never call`）、**SECTION 与 NOTE 两条归属路径**、list/update/delete、上下文纯函数。旧版 6 个用例 + 13/13 全量回归结果作为基线
  - **实际结果**：删掉 `JdbcTemplate` 字段与 import，改注入 `MessageRepository`；`assertTargetOwned` 的 NOTE 分支改调 `noteBelongsToUser`，`loadFullText` 改调 `findNoteById(...).map(NoteMessage::content).orElse("")`。7 步顺序、I1~I4、提示词、`locateBlock` 前后各 300 字、200 字截断、500 错误码、`AskRequest` 定义在 Service 内、`ask()` 不标 `@Transactional` **一字未改**；类注释与测试注释里「Phase 1 临时 SQL」的措辞已清除。
  - **测试**：`AnnotationServiceTest` 由 6 → **8 用例**：NOTE 用例改名为「走 T004 的 MessageRepository」，用 `ArgumentCaptor<Prompt>` 断言模型收到的全文确实来自 SECTION / NOTE 正文（不是空串）；新增「非 NOTE 消息与非法 id 在模型调用前被拒」（403 / 400 + `verify never call`）、「Service 不再注入 JdbcTemplate」（反射检查字段类型）。旧 6 用例全部保留通过。
  - **验证**：单独跑 `AnnotationServiceTest` **8/8**；全组回归 `-Dtest=AnnotationRowTest,AnnotationRepositoryTest,MessageServiceTest,MessageRepositoryTest,ConversationControllerTest,AnnotationServiceTest,ChatServiceTest,AiChatControllerTest` → **41/41 全绿**（含 T002/T003/T004、历史接口、ChatService、AiChatController）。数据库测试均 `@Transactional` 回滚。

- [x] **T006** `[FR-07][FR-09][FR-12][FR-13]` 实现 `AnnotationController` · ✅ 2026-11-14
  - 文件：`controller/AnnotationController.java`、`controller/AnnotationControllerTest.java`
  - 依赖：T005 · 完成：`plan.md` 5.2 #4~#7 四个接口；每个方法体首行 `requireUserId`；`PATCH` 只接受 `answer`；`DELETE` 返回 `{id, deletedCount}`；`AskRequest` 按 **`AnnotationService.AskRequest`** 引用（定义在 Service 内，见 `plan.md` 5.2 #5），用 `@Valid` 触发
  - **实现取舍**：`GET` 用 `@RequestParam(required=false)` + 手写 `parseTargetType` / `requireTargetId`（非法枚举、缺失、超 64 字统一 400 + `message`）；`PATCH` 用 `@RequestBody Map<String,Object>` 且要求「恰好一个 `answer` 字符串键」——Jackson 默认忽略未知字段，若用 DTO + `@Valid` 会把多传的 `question` 静默吃掉，满足不了 #6；`answer` 的非空 / ≤2000 字仍由 Service 的 `updateAnswer` 校验
  - **口径注记**：`@Valid` 在参数绑定阶段先于方法体执行，因此「未登录 + body 非法」返回 400 而非 401；单独未登录、单独非法 body 都按契约返回 401 / 400。与项目既有 Controller（如 `QuizController`）一致
  - **测试**：新增 `AnnotationControllerTest`（MockMvc → 真实库、`@MockitoBean` 顶掉模型、`@Transactional` 回滚）**15 用例**：四接口未登录 401、GET 参数形状 / 扁平列表字段形状（不下发 `userId`/`targetType`/`targetId`）/ 越权 403、POST 顶层与子批注（TC-03，含 `blockKey` 继承、两个 scene 埋点）、越权 `targetId` / 他人 `parentId` / 链深 8 在模型调用前被拒（TC-04 + `verify never call`）、`@Valid` 三类 400、PATCH 只改 `answer`/`edited`、多传 `question` 与空体 400、越权 403（TC-05）、DELETE 级联三代 + `deletedCount=3`、越权 403（TC-06）
  - **验证**：单独 **15/15**；全组 9 个测试类 `-Dtest=AnnotationRowTest,AnnotationRepositoryTest,MessageServiceTest,MessageRepositoryTest,ConversationControllerTest,AnnotationServiceTest,ChatServiceTest,AiChatControllerTest,AnnotationControllerTest` → **56/56 全绿**（T002~T005 与历史链路无回归）。未跑含真实模型调用的全量 `mvn test`（留 T023）
  - **第五次实测修补 · 浏览器编辑保存 403（2026-11-15 用户截图「编辑后无法保存」）**：缺陷在 T006 之外的基础设施，但落在同一条 HTTP 链上。`config/CorsConfig.java` 的 `allowedMethods("GET","POST","PUT","DELETE","OPTIONS")` **漏了 PATCH**：Vite proxy 转发时保留 `Origin: http://localhost:5173`，Spring `DefaultCorsProcessor` 在 Controller / 鉴权之前按白名单校验，PATCH 与 OPTIONS 预检都被拒成裸的 `403 Invalid CORS request`；GET / POST / DELETE 都在白名单内一直正常，所以只有编辑保存炸。**MockMvc 默认不带 Origin，CORS 校验根本不触发**，旧 16/16 乃至 G1 闸门全绿也测不到，与 T016 白屏事故同属测试环境结构性盲区。修法：白名单加 `"PATCH"` + 注释；`AnnotationControllerTest` 新增 `patch_带浏览器Origin_预检和实际请求都不被CORS拦截`（OPTIONS 预检断言 `Access-Control-Allow-Methods` 含 PATCH、带 Origin 的真实 PATCH 200 且库内答案已改），red `Status expected:<200> but was:<403>` → green **17/17**。**`CorsConfig` 启动时加载，用户自行在 IDEA 重启 8123 后生效**（本轮未代用户结束 IDE 进程）：预检 200 且 `Access-Control-Allow-Methods` 含 PATCH；ego lite 浏览器真实点「编辑 → 保存」**两次 PATCH 均 200**、控制台零报错、编辑态自动退出、卡片出现「已编辑」标签并显示新答案；随后再编辑存回原文，库内答案与原答案逐字一致、无探测标记残留（`edited=true` 是 PATCH 的正常副作用，会留下「已编辑」标签）。截图 `preview/browser-save-fixed.png`。编号 / 依赖 / FR 标签 / 闸门 AT-TC 覆盖未动

- [x] **T007** **G1 后端闸门** · ✅ 2026-11-14 **PASS**
  - 依赖：T006 · 跑 `TC-03、TC-04、TC-05、TC-06、TC-08`（纯后端集成测试）
  - 通过标准：四个批注接口全部可调、越权与限额在模型调用前被拒、历史消息返回 `type`；T002~T006 全量测试通过。**此闸门只跑集成测试，AT 黑盒统一留给 T018，不需要任何前端文件**
  - **执行**：`-Dtest=AnnotationRowTest,AnnotationRepositoryTest,MessageServiceTest,MessageRepositoryTest,ConversationControllerTest,AnnotationServiceTest,ChatServiceTest,AiChatControllerTest,AnnotationControllerTest` → **57/57 全绿**（0 失败 / 0 错误 / 0 跳过）。真实库 + `@Transactional` 回滚，模型用 `@MockitoBean` 顶掉；**没有新增生产代码**
  - **开工前补的缺口**：通过标准点名的「限额在模型调用前被拒」此前只有 Service 单测证据，新增 `AnnotationControllerTest#ask_限额I3满200_400且模型与埋点均未发生`（HTTP 层 400 + 模型未调用 + `t_ai_call` 无记账 + 批注数仍 200）；并给 TC-03 顶层用例补上库内 9 字段断言。用例数 15 → 16
  - **TC → 用例映射**（闸门结论的可追溯证据）

    | TC | 覆盖用例 | 结论 |
    |---|---|---|
    | TC-03 | `ask_顶层批注_落库埋点与响应形状`（响应形状 + 库内 9 字段 + `ANNOTATION` 埋点）、`ask_子批注_继承父blockKey且scene为CHILD`（`blockKey` 继承 + `ANNOTATION_CHILD`） | PASS |
    | TC-04 | `ask_越权targetId_403且模型未被调用`、`ask_他人parentId_403且模型未被调用`、`ask_链深达8_400且模型未被调用`、`ask_限额I3满200_400且模型与埋点均未发生` | PASS |
    | TC-05 | `patch_只改answer与edited_quote和question不动`、`patch_多传question或空体_400且库未变` | PASS |
    | TC-06 | `delete_级联删后代且deletedCount含自身`（三代 + 无孤儿 + `deletedCount=3`） | PASS |
    | TC-08 | `ConversationControllerTest#messages_returnsNoteType`（历史接口 JSON 里 `type=NOTE`） | PASS |

  - **通过标准逐条**：① 四接口可调 —— 成功路径各有一条 200 用例，另有未登录 401、越权 403、参数 400；② 越权与限额在模型调用前被拒 —— 上述 4 个用例均带 `verify(chatModel, never()).call(...)`，限额用例额外验证 `AiCallRecorder` 未记账；③ 历史消息返回 `type` —— TC-08；④ T002~T006 全量通过 —— 9 个测试类 57/57
  - **本闸门明确不覆盖**：AT-01~14 黑盒（留 T018 / T022 / T023）；TC-09 此刻只验了 `ANNOTATION` / `ANNOTATION_CHILD` 两个 scene，`NOTE_GENERATE` 要等 G2 的 TC-07；TC-10 未跑；全量 `mvn test`（`LiManusTest` / RAG 评测类真实调模型）留 T023
  - **里程碑状态**：**G1 通过，可在此停** —— 后端批注链路已能被集成测试完整覆盖，不需要任何前端文件。下一组 G2（T008~T011）按 0.1 节约定改走垂直切片

---

## Phase 2 · 笔记生成后端链

> 顺序按编译依赖排：先建帧类型与客户端方法 → 再写调用方 → 最后改请求签名。旧版 `T014─T015─T016` 成环（先改 `ChatService` 会 `cannot find symbol`），此处解开。

- [x] **T008** `[P]` `[FR-03]` `ChatEvent` 增加 `note` 帧 · ✅ 2026-11-15
  - 文件：`model/ChatEvent.java`
  - 依赖：T001（约定） · 完成：`record Note(long noteId) implements ChatEvent`，`type()` 返回 `"note"`
  - **验证**：加帧后全量编译通过（无 `switch` / 模式匹配需要穷尽，故不影响既有分支）；帧序由 T010 的 `ChatServiceTest#stream_asNote_savesNoteRowAndEmitsNoteBeforeDone` 断言 `done` 前一帧必须是 `Note`

- [x] **T009** `[P]` `[FR-01][FR-02]` `LoveApp` 加笔记客户端 · ✅ 2026-11-15
  - 文件：`app/LoveApp.java`
  - 依赖：无（只改 `LoveApp` 自身） · 完成：构造器内多建 `noteChatClient`（同 `deepSeekChatModel`、挂同一 `ChatMemory` advisor，**不挂工具与 RAG**）；新增 `doNoteByStream(message, chatId)`；`NOTE_PROMPT` = `plan.md` 附录 A1
  - **实现口径**：`NOTE_PROMPT` 用 Java 文本块逐字照抄附录 A1；advisor 复用构造函数里同一个 `chatMemory` 实例（笔记的原料就是这轮对话，换新记忆等于看不见上下文）；刻意不挂 `ToolCallback` / `QuestionAnswerAdvisor`
  - **验证**：单独编译通过。**如实说明**：`NOTE_PROMPT` 的实际输出质量（是否真按 2~4 个 `##` 小节、1000~2000 字）**本轮没验**，需要真实模型调用，留到 T022 联调时人工判读

- [x] **T010** `[FR-01][NFR-01]` `ChatService` 分流与落库 · ✅ 2026-11-15
  - 文件：`service/ChatService.java`
  - 依赖：**T004**（`appendNoteRound`）、**T008**（`Note` 帧）、**T009**（`doNoteByStream`）
  - 完成：`asNote` 分支调 `doNoteByStream`；`concatWith(Flux.defer(...))` 内先 `appendNoteRound` 拿 id、发 `Note(noteId)` 再发 `Done`；scene 用 `NOTE_GENERATE`；普通分支与 `doOnError` 行为一字不改；不标 `@Transactional`；`stream(...)` 扩为 6 参
  - **实现口径**：两条链路只在「事件从哪来」分叉——笔记链路 `.map(ChatEvent::delta)` 后并进同一条管道，累积全文 / 首 token 计时 / 落库时机全部复用，避免第二份 `concatWith` 出现漏改。普通分支仍记 `CHAT`，笔记记 `NOTE_GENERATE`（同一个 `scene` 变量，取值由 `asNote` 决定）
  - **验证**：`ChatServiceTest` 1 → **2/2**。新用例断言：两行落库且第二行 `type=NOTE`、正文为 delta 拼接结果、倒数第二帧是 `Note` 且 id 等于刚插入行 id、`t_ai_call.scene=NOTE_GENERATE`、`loveApp.doChatByStream` **一次未被调用**、`useRag/useTools` 传 true 也不影响分流

- [x] **T011** `[FR-01]` 请求体接上 `asNote` · ✅ 2026-11-15
  - 文件：`controller/AiChatController.java`（`StreamRequest` 与 `stream` 方法）
  - 依赖：**T010**（6 参签名此时才存在） · 完成：加 `Boolean asNote`（`null` 视为 false），作为第 6 参传给 `ChatService.stream`
  - **验证**：新增 `AiChatControllerAsNoteTest` **1/1** —— 请求体不带 `asNote` 时透传 `false`（不 400、不 NPE），带 `asNote:true` 时透传 `true`；`@MockitoBean ChatService` 只替换这一个协作者，其余走真实 Spring 上下文
  - **如实说明（排布缺陷）**：T010 改签名会让既有调用方（`AiChatController`、`ChatServiceTest`）立刻编译失败，**T010 单独落盘无法编译通过**，与本节阶段自检「每个任务单独编译通过」冲突——这正是 0.1 节「改签名的一方排最后」要防的中间态，但 T011 的完成标准又要求 6 参先存在。实际做法是 **T010/T011 作为一对同时落盘**，之后一次编译 + 跑测试。编号 / 依赖 / FR 标签 / 闸门覆盖未改，映射关系仍成立

- [x] **T012** `[P]` `[FR-04][FR-05][FR-12]` 笔记读写接口 · ✅ 2026-11-15
  - 文件：`service/NoteService.java`、`controller/NoteController.java`
  - 依赖：**T004**（`findNoteById` / `updateContent` / `noteBelongsToUser`） · 完成：`plan.md` 5.2 #2/#3；归属校验在前；`PUT` 写 `updated_at`，不重算后端锚点
  - **实现口径**：`NoteService` 只注入 `MessageRepository`，**不注入 `JdbcTemplate`**（与 `AnnotationService` 同一纪律，避免 `type='NOTE'` 的 JOIN 出现第二份）。读和改共用私有 `requireOwnNote()`，保证两条路径拒绝文案逐字相同（都是「笔记不存在或无权访问」），否则「读 403 / 改 404」的差异会泄露 id 是否存在。`GET` 归属通过后仍查不到 → 400 兜底（两次查询之间被删）；`PUT` 先归属、再 `updateContent`（内部已带 `where type='NOTE'`），返回 empty 同样 400。两个方法都不标 `@Transactional`（各一条 SQL，没有需要一起回滚的中间态）
  - **两处契约取舍**：① `PUT` 允许提交空串——覆盖写就是覆盖写，用户把整篇删空再保存是他的权利，只挡 null 与超 20000 字；② `PUT` 请求体用 DTO（多传字段被 Jackson 忽略），不像 #6 的 `PATCH /annotations` 那样严格拒收——`plan.md` 只对 #6 提了「只接受该字段」
  - **验证**：新增 `NoteControllerTest` **11/11**（HTTP → Controller → Service → Repository → 真实库）：未登录 401；读自己的笔记 200 且 `type=NOTE` / `content` / `conversationId` / `createdAt` / `updatedAt` 齐全；读别人的、读不存在的、把自己会话里的普通 `ANSWER` 消息当笔记 id → **三条全是 403 且文案逐字相同**；`PUT` 成功后库内正文变、`updated_at` 严格晚于改前、`created_at` 不动；越权 `PUT` 403 且库内正文一字未改；超 20000 字 → 400「笔记正文最多 20000 字」；缺 `content` → 400「content 不能为空」；空串 → 200 且库内清空
  - **如实说明**：用例**没有**用 `@Transactional` 回滚，因为 PostgreSQL 的 `now()` 取事务开始时间，测试事务里插入与更新会拿到同一时间戳，「`updated_at` 往前走」根本断不出来；改成显式建数据 + `@AfterEach` 删 `t_message` → `t_conversation` → `t_user`。另：运行中的 8123 是用户在 IDEA 启动的 JVM，**`/notes` 两个接口要重启后端才生效**

- 阶段自检：T008~T012 各自落盘后跑 `./mvnw -q -DskipTests compile`，**每个任务单独编译通过**，不得出现中间态编译失败；TC-07 留到 T022 跑（需要前端开关配合）

---

## Phase 3 · 前端共享组件（M1）

> 组件不 import 后续阶段模块：`AnnotatableDoc` 的编辑态只 `emit('save', draft)`，由父页面注入保存行为；M1 的阅读页 `bodyEditable=false`，根本不需要 `api/notes.js`。

- [x] **T013** `[P]` `[FR-06]` `utils/markdown.js` 追加 `splitBlocks` · ✅ 2026-11-14（G4 第一小步，前端先行）
  - 文件：`utils/markdown.js`、`scripts/verify-splitBlocks.mjs`（新增，TC-01 可重复验证脚本）
  - 依赖：无 · 完成：`plan.md` 6.2 算法原样落地（`marked.lexer` 取顶层 token → 过滤 `space` → `{key:'p-N', text:t.raw, html: ...}`）；**TC-01 在这一步就跑**（代码块 / 列表 / 表格不切坏）
  - **对原任务栏「`renderMarkdown` 保持不变」的更正**：为能在 Node 里跑纯函数 TC-01，把 `DOMPurify` 的 hook 注册与消毒收进 `canPurify` 判断（Node 下 `dompurify` 只导出 factory、没有 `sanitize`），`renderMarkdown` 改调同一个 `sanitize()` 包装。**浏览器行为与消毒强度一字不变**，仅 Node 下跳过消毒；NFR-02 无新依赖
  - **验证**：`node scripts/verify-splitBlocks.mjs` → TC-01 **8/8 断言全过**（空输入、key 连续、代码块/列表/表格各成一块、段落互不串、稀疏空行不产生空块、Node 下 `renderMarkdown` 不抛错），终端打印 6 个块的分块预览；`npm run build` 通过
  - **如实说明**：`html` 的 XSS 消毒只能在有 DOM 的浏览器里验，本步未覆盖；NFR-03 由 T015/T016 的 `{{ }}` 渲染与已消毒 HTML 承担。**这一步是纯函数、界面还没有变化**，能看到画面要到 T017

- [x] **T014** `[P]` `[FR-07]` 新增批注请求封装 · ✅ 2026-11-14（G4 第二小步，前端先行）
  - 文件：`api/annotations.js`、`scripts/verify-annotations.mjs`（新增，请求形状自检脚本） · 依赖：**T006**（接口已存在） · 完成：`get / ask / patch / remove` 四个薄封装，基于 `request.js`，不缓存 / 不重试 / 不吞错 / 不拼业务文案（错误交 `utils/apiError.js`）
  - **验证**：`node scripts/verify-annotations.mjs` → **14/14 断言全过**，用 axios 自定义 adapter 拦下 7 次请求、零出网：method / url / params / body 逐项对齐 `plan.md` 5.2 #4~#7；`parentId` 有值才发；组件派生字段 `children/stale/groupKey/answer` 不透传；`PATCH` 请求体恰好一个 `answer` 键；响应已被 `request.js` 脱壳；失败原样抛出且 `messageOf` 能取到服务端 `message`。`npm run build` 通过（1.66s）
  - **三处如实说明**：① 本步仍是纯封装、**界面无变化**，第一个能看到画面的任务是 T017；② `annotations.js` 此刻还没被任何组件 import，`npm run build` 的产物里不含它，语法与形状由上面的 Node 脚本实际 import 覆盖，**别把 build 通过当成它已被打包**；③ 唯一风格偏差是 `import request from './request.js'` 带 `.js` 后缀（其余 `api/*.js` 不带），只为让 Node 直接 import 做自检，Vite / 浏览器行为不变，`request.js` 一行未动

- [x] **T015** `[FR-08][FR-09][NFR-03]` `AnnotationCard.vue` · ✅ 2026-11-14（G4 第三小步，前端先行；同日按用户看图意见做**视觉返工**）
  - 文件：`components/AnnotationCard.vue`、`utils/annotationExpand.js`（新增，折叠状态）、`scripts/verify-AnnotationCard.mjs`（Vite SSR 真渲染自检）、`preview/annotation-card.html`（脚本生成的**可点击**预览页，生成物、不进生产包） · 依赖：T014
  - 完成（返工后口径）：多根 fragment 自引用渲染；**追问默认折叠**成「展开 N 条追问」小横条，`v-if` 控制子卡片（不在 DOM 里）；点开后子卡片**独立平铺**、不缩进、不套在父卡片边框里，层级靠「第 N 层」标签表达；折叠状态按 id 共享；编辑/删除/「已编辑」/「原文已修改」/删除前 N 提示/`data-annotation-id`/全文本 `{{ }}` 均保留
  - **交给 T016 的确切契约**：props = `{ annotation: resolve() 后的节点（含 children / stale）, depth: Number(顶层 0) }`；emits = `changed`（编辑/删除成功，父组件须重新 `get()` + `resolve()`）、`error`（已处理好的中文文案）。**T016 必须调一次 `provideExpandedIds()`**（`utils/annotationExpand.js`）并持有返回值，否则每次列表重建后追问会全部合上；key 用字符串常量，别改回 `Symbol()`。卡片自己调 T014 的 `patch` / `remove`，T016 不要再写编辑删除逻辑；「原文已修改」分组归 T016，卡片只显示标签
  - **需求口径变更**：用户看第一版预览后否掉「层层缩进套娃」，改为折叠 + 独立平铺。`spec.md` FR-08 / AT-05、`plan.md` 6.2 已同步，原 `marginLeft` 公式整段作废。**「引用词高亮配色」经用户澄清指正文里引用原句的位置高亮**，即 FR-06 / C10 的 `.quote-hit`，归 T016 / T017，卡片顶部引用词标签不改配色
  - **验证**：`node scripts/verify-AnnotationCard.mjs` → **18/18 断言全过**。同一条五层链分别渲染折叠态（不 provide）与展开态（`app.provide` 全展开）：折叠态 13~16 均不在 DOM、展开态 13~16 与 4 层 `data-children-of` 全在；无 `margin-left` / `is-child`；第 2~5 层标签齐、无第 1 层；折叠条文案与 `aria-expanded` 两态切换；状态模块 `toggle/openAll/clear`；折叠用 `v-if` 无 `v-show`；N=4 提示、状态标签、XSS 转义、模板区无 `v-html`、事件逐层转发；预览页 scoped 样式命中且含逐层折叠 JS。`npm run build` 通过（1.57s）；`TC-01 8/8`、`T014 14/14` 回归无影响
  - **三处如实说明**：① 预览页的点击是**演示用原生 JS**，不是组件交互；编辑/删除/选词追问仍要 T017 手工验；② 组件此刻没被页面 import，`build` 产物不含它，语法与渲染由 SSR 脚本覆盖；③ 预览页左侧正文与黄色高亮是**配色示意**，真实正文渲染与 `.quote-hit` 归 T016 / T017，勿当成已实现
  - **第四次实测修补 · 卡片内追问锚点高亮**（2026-11-15 用户截图反馈「批注中被引用的部分没有被高亮标记？」）：正文里的引用位置高亮已有，缺的是**子批注选中的词在父批注答案里的标记**。口径：只标子批注 `quote`（追问锚点），不标本卡片自己的 `quote`；追问折叠也照标（`children` 数据始终在）。`annotationResolve.js` 加纯函数 `splitAnswerByQuotes()` 切 `[{text, hit}]`（原样 `indexOf`、长词优先占区间、不用正则，与 `resolve()` 的子批注 stale 判定同源）；卡片答案区改 `v-for` + `<mark class="quote-hit">` / `{{ }}` 分段渲染，**只切纯文本、不引入 `v-html`**，NFR-03 不变；卡片 scoped 样式补同配色（正文的 `.doc-body :deep()` 管不到右栏）。`verify-AnnotationCard.mjs` 18 → **26/26**，预览页 hint 同步；T016 46/46、T017 17/17、T014 14/14、TC-01 8/8 与 `npm run build` 回归全绿，生产 CSS 实测含 `.answer mark.quote-hit[data-v-…]`。**ego lite 真实浏览器复验**：控制台零报错、141 块 / 11421 字、父答案 `Full GC` 标黄、正文 `Minor GC` 高亮仍在、选中被 `<mark>` 包住的词仍能弹出就地浮层并自动聚焦、1440px 无横向滚动，截图 `preview/browser-answer-highlight.png`。编号 / 依赖 / FR 标签 / 闸门 AT-TC 覆盖未动

- [x] **T016** `[FR-05][FR-06][FR-07][FR-08][FR-10][FR-13][NFR-03]` 实现 `AnnotatableDoc.vue` · ✅ 2026-11-14（G4 第四小步，前端先行）
  - 文件：`components/AnnotatableDoc.vue`、`utils/annotationResolve.js`（新增纯函数）、`scripts/verify-AnnotatableDoc.mjs`（新增，TC-02 + SSR 自检）、`preview/annotatable-doc.html`（脚本生成的**可点击两栏预览页**，生成物、不进生产包） · 依赖：**T013、T014、T015**（旧版依赖栏漏 T015，且 T015 编号在它之后）
  - 完成：两栏布局（<1024px 右栏下移）、逐块 `data-block-key` 渲染、选区委托（跨段落拦截 / 卡内识别 / 起止同块）、`resolve()` 原文降级、编辑预览两态、best-effort 原文高亮、批注文本用 `{{ }}`；渲染 T015 的 `AnnotationCard`，setup 调 `provideExpandedIds()` 按 id 共享折叠状态
  - **原文高亮配色已落地**：用户澄清指**引用在原文中的位置**，`<mark class="quote-hit">` 用荧光黄（`#faeeda` 底 / `#854f0b` 字 / `#ef9f27` 下划线）；匹配不到时降级成 `.block-has-annotation` 左侧色条 + 批注数角标。卡片顶部引用词标签不改配色。C10 的样式写在组件 scoped 里，T017 不必再重复加
  - **新增纯函数模块 `utils/annotationResolve.js`**：`resolve()`（组树 + 三分支重定位）、`groupAnnotations()`（按正文顺序分组）、`highlightBlock()`（转义后精确替换一次）、`escapeHtml()`、`depthOf()`、`normalizeForMatch()`。拆出来是为了让 TC-02 能在 Node 里直接跑，不靠浏览器点。**两处对 plan 6.2 的精确化**：① `blockContains()` 先试原文、再用 `normalizeForMatch()` 去掉 markdown 标记重试，否则 `**粗体**` 段落会被误判 stale；② **子批注 stale 只显示卡片标签、不单独进「原文已修改」组**（进组会断链），顶层 stale 才进组 —— 与 spec AT-08 一致，`plan.md` 6.2 已注记
  - **交给 T017 / T020 的确切契约**：props `markdown / targetType / targetId / bodyEditable`；emits `save(draft)`、`cancel`、`unauthorized`（401 时组件不引 router，由父页面跳登录）；`defineExpose({ finishSave, reload })`。`bodyEditable=false` 完全不渲染编辑按钮；保存只交草稿，**父页面 PUT 成功后调 `docRef.finishSave()` 退出编辑态**（markdown 变化时 watch 也会兜底）。另有 `initialAnnotations` / `initialExpandedIds` 两个可控渲染入口，专供 SSR 自检与预览页注入数据，**生产路径都不传**，组件自己 `onMounted` 拉真接口
  - **跨阶段解耦**：不 import `api/notes.js`、不写 `PUT /notes/{id}`；提问面板**就地浮在选区旁**（原「放右栏顶部、不定位浮层」口径已被实测反馈推翻，见本条目末的修补记录）；FR-13 的字数 / 200 条 / 链深 8 在前端先拦一道，后端 T005 仍在模型调用前兜底
  - **验证**：`node scripts/verify-AnnotatableDoc.mjs` → **29/29 全过**，其中 **TC-02 三分支**（原段落命中 / 错位重定位 / 找不到置 stale 且内容不丢）、子批注与父被删兜底、markdown 标记匹配、链深、分组、高亮命中与降级、XSS 转义，以及 Vite SSR 真渲染两栏（`mark` 高亮、6 个 `data-block-key`、分组、卡片链、`bodyEditable` 显隐）。`npm run build` 通过（1.67s）；`TC-01 8/8`、`T014 14/14`、`T015 18/18` 回归无影响
  - **三处如实说明**：① 预览页的折叠与选区提示是**演示 JS**，真实提问 / 编辑 / 删除要 T017 接入阅读页后才可点；② 组件此刻仍未被任何页面 import，`build` 产物不含它，语法与渲染由 SSR 脚本覆盖；③ 正文 DOMPurify 消毒只能在有 DOM 的浏览器验，Node 下 `sanitize()` 跳过，本步未覆盖（NFR-03 的批注文本转义已验）
  - **同日实测反馈修补（不改编号 / 依赖 / FR 标签 / 闸门 AT）**：用户截图反馈「每次提问都要到第一行开头才能输入问题」——面板固定在右栏顶部，选区在页面中段 / 底部时要滚回第一行。改为 `<Teleport to="body">` + `fixed` 就地浮层，新增 `utils/annotationAskPosition.js` 纯函数 `computeAskPosition()`（宽屏贴右栏且纵向与选区齐平 / 窄屏选区下方或上方 / 四边 clamp）；打开即 `focus({ preventScroll: true })`，`cloneRange()` 保存原选区，scroll / resize 跟随，关闭与卸载解绑，Esc 取消、Ctrl/Cmd+Enter 提交合并进 `onAskKeydown`。预览页加了同款演示浮层（注入同一个定位函数的源码快照，仍是演示 JS，不调后端）。验证：T016 自检 29 → **38/38**（新增 6 条定位几何 + 2 条组件契约 + 1 条预览页断言），T015 18/18、T017 17/17、T014 14/14、TC-01 8/8 回归全绿，`npm run build` 通过
  - **第二次实测反馈修补（同样不改编号 / 依赖 / FR 标签 / 闸门 AT）**：用户截图反馈「生成后的批注跑到第一行了，应该做对齐」——右栏分组按文档流从顶部堆，选第 39 段生成的卡片仍显示在第一行。新增 `utils/annotationRailLayout.js` 纯函数 `computeRailMargins()`；组件按 `[data-block-key]` 的实际 y 坐标给 `[data-group-key]` 分组补 `margin-top`，`.rail-groups` 改 `flex column + gap:18px`（flex 内 margin 不折叠），**不用绝对定位**（会塌陷右栏、相邻组互相盖住）；目标位置被占则顺序顺延，`__rest__` / `__stale__` 紧接上一组，窄屏 ≤1024px / 编辑态回退普通流；`onMounted` / `watch(markdown, flat, editing)` / `resize` / 折叠条点击后 `nextTick` 重算。预览页加了同款对齐演示（注入同一个 margin 函数源码快照）。验证：T016 自检 38 → **45/45**（新增 5 条对齐几何 + 1 条组件契约 + 1 条预览页断言），T015 18/18、T017 17/17、T014 14/14、TC-01 8/8 回归全绿，`npm run build` 通过，dist 实测含 `rail-groups` / `data-group-key` / `marginTop`
  - **第三次修补 · 浏览器白屏（2026-11-15 用户反馈「正文加载不出来了」）**：上一轮把 `watch(() => [props.markdown, flat.value, editing.value], ...)` 写在 `const editing = ref(false)` 之前；浏览器端 Vue 在 setup 阶段**立即执行一次 watch getter 收集依赖**，命中 `const` 的 TDZ → `AnnotatableDoc` 的 setup 抛错，阅读页只剩标题、正文整栏空白。**SSR 下 watch 不跑 getter，45/45 的 Node 自检全绿也测不到**。修法：`editing` / `draft` 声明提到该 watch 之前；`verify-AnnotatableDoc.mjs` 新增静态断言「watch getter 引用的 ref 必须先声明」，自检 45 → **46/46**。**首次用 ego lite 真实浏览器验收**：登录 → `/materials/{UUID}`，控制台零报错、`.doc-body` 141 块 / 11421 字正常渲染、`Minor GC` 原文黄色高亮可见；选区浮层 `position:fixed` 且自动聚焦；1440px 下 `p-38` 分组 `top=3554` 与正文块 `top=3554` 偏差 **0px**、横向滚动 0。**据此确立：前端改动跑完 Node 自检与 build 后，必须再用真实浏览器复验，SSR 自检不能替代**（同样不改编号 / 依赖 / FR 标签 / 闸门 AT-TC）

- [x] **T017** `[FR-06][FR-11]` 阅读页接入 · ✅ 2026-11-14（G4 第五小步，前端先行；**第一个真可交互界面**）
  - 文件：`views/SectionReadView.vue`（唯一生产改动）、`scripts/verify-SectionReadView.mjs`（新增自检）、`preview/section-read.html`（脚本生成的阅读页全貌预览，生成物、不进生产包） · 依赖：T015、T016
  - 完成：整篇 `v-html` 的 `.prose` 换成 `AnnotatableDoc`，六个接线属性 `:key="detail.knowledgeItemId"` / `:markdown="contentMarkdown"` / `:target-type="'SECTION'"` / `:target-id="detail.knowledgeItemId"` / `:body-editable="false"` / `@unauthorized="onUnauthorized"`；`contentHtml` 改成 `contentMarkdown`（仍跳过与页面标题重复的首行，但交**原始 markdown** 给 `splitBlocks`，C8）；删掉整组 `.prose :deep(...)` 排版与窄屏 `.prose` 规则；`.reader-inner` 720px → **1200px**（C9）；**保留** `.action-bar` / 翻页 / 检测 / `watch(immediate)` / 出错与 401 处理
  - **两个本步新加的判断**：① `:key` 按章节 id 重建组件 —— 否则翻页时组件不卸载，上一章的批注列表与折叠 id 集合会残留；② 组件 401 只 `emit('unauthorized')`，父页面 `onUnauthorized()` 调 `redirectOn401({response:{status:401}}, router, route)`，组件仍不引 router
  - **按 T016 契约执行**：C10 的 `.quote-hit` / `.block-has-annotation` 与全部 markdown 排版都在组件 scoped 样式里，页面**没有重复定义**（自检 `doesNotMatch` 断言）；`initialAnnotations` / `initialExpandedIds` 不传，组件自己 `onMounted` 拉 `GET /annotations`；`targetId` 用 `knowledgeItemId` 的 UUID 字符串，与 T005 `assertTargetOwned(SECTION)` 的 `UUID.fromString` 一致
  - **验证**：`node scripts/verify-SectionReadView.mjs` → **17/17 全过**：10 条 SFC 静态契约（六个属性、只读、无页面级 `v-html`、action-bar/翻页保留、import 链、markdown 原文、401、watch、C9 宽度、C10 不重复样式）+ Vite SSR **真实加载整页**（连带编译 AnnotatableDoc / AnnotationCard / api / utils 整条 import 链）+ SSR 加载态 + 两栏真渲染（块锚点、黄色高亮、降级色条、分组、追问链、XSS 转义）+ 预览页骨架与 scoped 样式命中。`npm run build` 通过（1.67s），`SectionReadView` chunk 约 18 kB，**dist 里实测含 `doc-layout` / `data-block-key` / `quote-hit` / `/annotations/ask`** —— 与前四步「组件未被 import、产物不含它」不同，这次真的打包了；TC-01 8/8、T014 14/14、T015 18/18、T016 29/29 回归无影响
  - **三处如实说明**：① 预览页折叠与选区提示仍是**演示 JS**，真实提问 / 编辑 / 删除必须启动后端 + `npm run dev` 在浏览器验，自检没有代替黑盒 AT；② 页面 SSR 只渲染了**加载态**（detail 为 null），带正文的整页 SSR 没做（页面无测试用 prop，生产代码保持干净），两栏部分由真实组件 SSR 提供；③ 数据库、后端 Java、组件文件一行未动

- [x] **T018** **M1 验收闸门**（此切片可独立停、独立演示） · ✅ 2026-11-15 **PASS**
  - 依赖：**T007（G1）、T017** · 跑 `AT-04、AT-05、AT-06、AT-08、AT-09、AT-10（批注半边）、AT-11` 与 `TC-01、TC-02、TC-03、TC-04、TC-05、TC-06`
  - 通过标准：阅读页选一句话提问 → 右栏短解释 → 在解释里选词追问 → 追问默认折叠、点开后独立平铺 → 刷新层级还在 → 换账号看不到 → 900px 下阅读页右栏下移无横向滚动；另观察：提问框**就地浮在选区旁、打开后可直接打字**（不用滚回第一行）；批注卡片**与对应正文段落对齐**（不再全挤在右栏第一行），相邻段落都有批注时顺延不重叠；父批注解释里被追问的词有黄色高亮（追问折叠时也标），选中该词仍能继续追问
  - **后移的 AT**（M1 阶段能力不具备，移到 T022）：`AT-07` / `AT-12` 需要笔记详情页（T020），`AT-10` 的「访问 A 的笔记」半边需要 T012 的笔记接口
  - **执行方式**：自动化侧跑前端 5 个自检 + `npm run build` + 后端约定的 9 类回归（TC-01~06 沿用既有覆盖，本轮只跑不新写用例）；黑盒侧用 ego lite 真实浏览器登录 `lcz` 打开 `/materials/99baddae-515f-4328-952a-dc99b3ef8e85`，全程挂 XHR 抓包、`window.confirm` 钩子与控制台错误钩子，**每一步都看真实请求状态码**
  - **TC → 证据**（全部沿用既有用例，无新增测试代码）

    | TC | 证据 |
    |---|---|
    | TC-01 | `node scripts/verify-splitBlocks.mjs` → 8/8 |
    | TC-02 | `node scripts/verify-AnnotatableDoc.mjs` → 46/46（含 `resolve()` 三分支） |
    | TC-03~06 | `AnnotationControllerTest` **17/17**（映射见 T007 表）；本轮 9 类合计 **90/90**，0 失败 / 0 错误 / 0 跳过 |

    另：`verify-annotations` 14/14、`verify-AnnotationCard` 26/26、`verify-SectionReadView` 17/17；`npm run build` 通过（2.33s），生产 CSS/JS 实测含 `.answer mark.quote-hit` 与分段渲染
  - **AT 逐条实测**

    | AT | 黑盒实测 | 结论 |
    |---|---|---|
    | AT-04 | p-1 选 37 字提问 → `POST /annotations/ask` **200**，新建批注 3545，答案 **103 字**（口径 60~120）；同期 `chat/stream` 调用 **0 次**、会话消息数 **0 → 0** | PASS |
    | AT-05 | 在 1778 答案里选「在这篇文章里」→ 200 生成子批注；默认 `aria-expanded=false` 且子卡片不在 DOM；点开后 `.annotation-children` 是父卡片的**兄弟节点**，子卡左边缘偏差 **0px**、宽度同为 **349px**、带「第 2 层」标签；刷新后折叠条与 `rail-count` 不变，再展开仍能取到子批注 | PASS |
    | AT-06 | 带 2 条追问的父批注：删除按钮 `title` 与 `confirm` 文案均为「将同时删除 **2** 条追问」；确认后 `DELETE` **200**，列表 **7 → 4**，父与两条子一起消失、`kidsLeft=0`、无孤儿、控制台零报错 | PASS |
    | AT-08 | 编辑 1779 把答案里的「新生代」改成「新世代X」→ `PATCH` **200** → 刷新 → 子批注 1780 出现「原文已修改」标签，答案 **122 字**与 `question` / `quote` 一字不少；改回「新生代」后标签消失 | PASS |
    | AT-09 | 阅读页无 `.edit-body-btn`、无 `.doc-toolbar`；正文 **141 块 / 11421 字**；选句提问、选词追问、编辑答案全部可用 | PASS |
    | AT-10 | A 伪造 `targetId` → **403**；A 用伪造 target + 真 `parentId` → **403**。临时注册账号 B（201）后：B 读 A 的批注列表 **403**、用 A 的批注 1778 当 `parentId` 提交 **403**、顶层提交 **403**、资料库 **0 条**且不含 A 的材料；`t_ai_call` **178 → 178**（拦截发生在模型调用之前） | PASS |
    | AT-11 | SQL 造 8 层链后在第 8 层答案里选词 → 「追问链最多 8 层」；跨段落选 88 字 → 「请只选一段内的文字」；单段选 320 字（该块 684 字）→ 「选中的文字最多 300 字，请选短一点」；**三次均无 `.ask-panel`、零新增请求** | PASS |

  - **观察项实测**：① 1440px 下 `[data-group-key="p-38"]` 与 `[data-block-key="p-38"]` 顶部偏差 **0px**，相邻组顺延不重叠；② 父批注答案里 `mark.quote-hit` **10 处**（两条子批注的引用词各命中），背景 `rgb(250, 238, 218)`，正文高亮 1 处仍在；③ 提问框 `position: fixed`、打开即聚焦（`activeElement` 是 `.ask-input`），1440px 下面板左边缘 1180 贴住右栏、纵向与选区齐平；④ **900px** 无横向滚动（`scrollWidth - innerWidth = 0`），`.note-rail` 顶部 9024 > `.doc-col` 底部 9000（右栏落到正文下方），两者宽度同为 757px；⑤ 全流程控制台 `errs: []`
  - **数据清理**：8 层测试链（3549~3556）、探测账号 `t018probe`、验收期间新增的 3545/3546/3547/3548 全部删除；库内批注恢复为原有 3 条（1778 → 1779 → 1780），`t_ai_call` 保持 178，用户表恢复 3 个账号
  - **四处如实说明**：① AT-11 的链深 8 用 SQL 直接造链（不调模型），只验**前端**拦截；后端 `ask_链深达8_400且模型未被调用` 仍由 T007 覆盖，本轮没有再用真实生成造一条 8 层链；② AT-04 的「60~120 字」是实测字数核对（103 字），AT-05 / AT-08 的「明显承接上文」属人工判读答案内容，不是自动断言；③ 换账号用临时注册的 `t018probe`（用后即删），没用库里已有的 user2 / user3（口令未知）；④ 选区由脚本用 Range + `mouseup` 模拟，未用真实鼠标拖拽，其余点击 / 输入 / `confirm` / 滚动都是真实 DOM 交互
  - **里程碑状态**：**M1 通过，可在此停** —— 阅读页批注 + 追问链已能完整演示。下一组按依赖只能走 **G2（T008~T011，笔记生成后端链）**，四步全是后端（SSE `note` 帧 / 落库 / 请求体），**做完看不到界面变化**，前端要到 T020 才重新有画面
  - **后续进展（2026-11-15）**：G2 已完成 —— `ChatServiceTest` 扩到 2/2、新增 `AiChatControllerAsNoteTest` 1/1，`note → done` 帧序 / `type=NOTE` / `scene=NOTE_GENERATE` 三条验收物均有自动化断言。G3 也已完成 —— 新增 `NoteControllerTest` 11/11，读/改/越权/非 NOTE 全覆盖。**后端侧 G1/G2/G3 三组全通**，下一步进 **G5（T019~T022 笔记详情页与聊天卡片）**，**T019/T020 是第一个能看到界面的任务**。**2026-11-15 更新**：T019 / T020 已完成 —— 笔记详情页可在真实浏览器里打开、编辑正文、保存并刷新复验（`AT-07` 的前置条件已具备）。**T021 也已完成** —— 聊天侧第 4 个开关、`note` 帧、NOTE 卡片与「卡片 → 详情页」全链路在真实浏览器里点通（`AT-01`/`AT-02` 的核心路径已验），M2 只剩 **T022 闸门**。**T022 已完成（2026-11-15）**：`AT-01/02/03/07/10/12/14` 七条在真实浏览器里全过，`TC-07`（自动化帧序）+ `TC-09`（`t_ai_call` 四行 `CHAT/NOTE_GENERATE/ANNOTATION/ANNOTATION_CHILD`）均坐实，**M2 PASS**；`TC-10` 按原文留作可选、**未跑**，T023 必须如实标注未覆盖


---

## Phase 4 · 笔记页与聊天卡片（M2）

- [x] **T019** `[FR-04]` 前端笔记请求封装 · ✅ 2026-11-15（G5 第一小步，纯封装、界面无变化）
  - 文件：`api/notes.js`、`scripts/verify-notes.mjs`（新增，请求形状自检） · 依赖：**T012**（`GET` / `PUT /notes/{id}` 已存在）
  - 完成：`get / save` 两个薄封装；**路由不在本任务加**（避免与 T020 的动态 import 成环）
  - 两处口径：① `save` **只发 `{ content }`**（后端请求体只声明这一个字段）；② **空串原样提交** —— 覆盖写语义下把正文删空再保存是用户权利，封装层不做非空校验，只由后端挡 `null` 与 >20000 字；失败原样抛出，文案交 `utils/apiError.js`，403 的三合一文案（别人的 / 不存在 / 非 NOTE）不拆开猜
  - **验证**：`node scripts/verify-notes.mjs` → **10/10 断言全过**，axios 自定义 adapter 拦 4 次请求、零出网：`get` 路径 / 无 query 无 body / 响应已脱壳、`save` method+url / body 恰好一个 `content` 键 / 不回读正文、空串原样提交、403 原样抛出且 `messageOf` 取到服务端文案。既有 `verify-annotations` 14/14 无影响；`npm run build` 通过

- [x] **T020** `[FR-04][FR-05][FR-10]` 笔记详情页 **＋路由** · ✅ 2026-11-15（G5 第二小步，**M2 第一个能看到界面的任务**）
  - 文件：`views/NoteDetailView.vue`（新增）、`router/index.js`、`scripts/verify-NoteDetailView.mjs`（新增自检） · 依赖：**T016**（`AnnotatableDoc`）、**T019**（`api/notes.js`）、**T012**（接口）
  - 完成：取笔记 → 挂 `AnnotatableDoc(:bodyEditable="true", @save="save")` → `save` 调 `PUT /notes/{id}` → 成功后 `markdown=draft`、**显式调 `docRef.finishSave()` 退出编辑态**（T016 暴露的方法；markdown 变化时组件 watch 也会兜底）、重跑 `resolve()`；`AppShell.children` 同一提交内加 `notes/:messageId` 路由（带 `meta.requiresAuth`），**页面与动态 import 一起落盘**
  - 页面自身三个判断：① **保存失败不退出编辑态** —— catch 里刻意不动 `markdown`、不调 `finishSave`，草稿留在 textarea 里等重试；② 「返回对话」用 `NoteView.conversationId` 跳 `guide-conversation`，不依赖浏览器历史（贴链接进来也能回会话）；③ 路径参数非 `/^\d+$/` 直接报「笔记地址无效」，不白跑一趟请求；`:key="note.messageId"` 让换笔记时组件重建、批注与折叠状态不串
  - **验证**：`node scripts/verify-NoteDetailView.mjs` → **16/16 全过**：10 条 SFC 静态契约（六个接线属性、`docRef`、`:key`、页面无保存按钮/无 `v-html`、import 链、成功路径先换 markdown 再 `finishSave`、失败路径两不动、不重复实现 `resolve`）+ 路由块（name / 动态 import / `requiresAuth` / 位于 `children` 内）+ Vite SSR 真实加载整页（连带组件 import 链）+ 加载态 + `bodyEditable=true` 的组件形态（「编辑正文」按钮、逐块渲染）。既有 5 个前端自检全绿（TC-01 8、T014 14、T015 26、T016 46、T017 17）；`npm run build` 通过，产物含 `NoteDetailView-*.js`（2.86 kB）
  - **ego lite 真实浏览器复验（2026-11-15，临时账号 + SQL 造数据，用后即删）**
    - 加载：`GET /api/notes/527` **200** + `GET /api/annotations?...` **200**；6 块正文、正文 2 处 `mark.quote-hit`（「把问题变成向量」「切分粒度」）、右栏 3 条批注分 2 组、`rail-count=3`、答案里「向量距离」高亮、控制台 `errs: []`
    - 编辑保存：点「编辑正文」→ textarea 186 字 → 追加 `## 补充` 段落 → 点保存 → **`PUT /api/notes/527` 200**（带 Origin 的真实 XHR，顺带复验 CORS）→「已保存」出现、编辑态退出、副标题 `141 字` → `163 字`、`更新于 13:21`、块数 **6 → 8**
    - 刷新：正文持久化（8 块、末块是「重叠窗口…」）、**旧批注仍 3 条全可读**（`AT-07`）、原文高亮与分组不变、**无 stale 组**（引用词都还在）、再次可进编辑态
    - 追问：点「展开 1 条追问」→ 子卡片 4434 出现、`.annotation-children` 存在、「选中词 向量距离 第 2 层」标签在，未嵌套
    - 403：`/notes/999999` → `GET` **403** → 横幅「笔记不存在或无权访问」+ 重试按钮，无空白页
    - 截图：`preview/t020-note-page.png`（加载后）、`t020-note-expanded.png`（展开追问）、`t020-note-edit.png`（编辑态）、`t020-note-403.png`
  - **三处如实说明**：① 保存失败路径**只在代码与自检断言层面验过**（catch 里不动状态），浏览器里没法在不弄停后端的情况下可靠触发；② 此轮验收数据是 SQL 直接造的 NOTE（id 527）+ 3 条批注，不走模型生成，真实 `asNote` 链路要到 T021/T022 才有开关可点；③ 8123 上的 JVM 是加 `NoteController` 之前启动的，首次访问 `/api/notes/*` 返回 404，**请用户重启后端后**才验通 —— 以后凡新增 Controller/配置类，浏览器验收前先确认后端已重启
  - 刷新后旧批注仍可读（`AT-07` 在这里才具备条件）

- [x] **T021** `[FR-01][FR-03]` 聊天侧开关与 NOTE 卡片 · ✅ 2026-11-15（M2 最后一个能看到新画面的任务）
  - 文件：`api/chatStream.js`、`components/ChatRoom.vue`、`views/GuideChatView.vue`、`utils/noteCard.js`（新增）、`scripts/verify-noteCard.mjs`、`scripts/verify-chatNoteCard.mjs`
  - 依赖：**T008**（`note` 帧）、**T011**（`asNote` 请求体）、**T004**（历史消息带 `type`，C1）、**T020**（卡片跳转的目的页与路由）
  - 完成：`streamChat` 透传 `asNote`；`onEvent` 处理 `note` 帧；消息模板按 `m.type==='NOTE'` 渲染卡片（标签 + 标题 + 前 80 字 + 打开按钮）；历史映射带 `type`；第 4 个 `tool-btn` 开关，发送后复位
  - **卡片文案抽成纯函数** `utils/noteCard.js`：`noteCardOf(markdown) → {title, summary, chars}`。标题 = 第一个 `#` 标题（没有就退化第一行正文，代码块里的 `#` 不算）；摘要 = **跳过标题**取正文、剥掉强调 / 链接 / 行内代码 / 列表记号、去掉分割线与表格分隔行、按**字**截 80 加省略号。只裁纯文本、不拼 HTML（NFR-03）。聊天卡片与笔记页顶栏标题共用这一份，避免「卡片说 A、点进去标题是 B」
  - **开关只作用于一条消息的具体做法**：`ChatRoom.send()` **开头**先 `const wantNote = props.asNote`（早于懒创建会话的 `await`），消息入列后立刻 `emit('sent')`，`GuideChatView.onSent` 只把 `asNote` 置 false。放在「发送时」而不是「本轮结束时」——生成一篇笔记要十几秒，其间不该留一个还亮着但已不生效的开关；复位也不动 `useRag` / `useTools`
  - **NOTE 消息的四种形态**（都在 `ChatRoom` 模板里）：① 拿到 `noteId` → 卡片；② 没有 `noteId` 且还在流式 → 「正在写成一篇笔记…」一句话（**不把半篇 Markdown 摊在聊天里**）；③ 流中断 / `error` 帧 → 把已收到的内容降级成正文，别让内容白丢；④ 非 NOTE 消息走原来的气泡。卡片文案按 `noteId + 正文长度` 缓存，流式期间别的消息变化导致的重渲染不会反复重扫整篇 Markdown
  - **验证**：`node scripts/verify-noteCard.mjs` → **18/18**（标题 / 摘要 / 字数 / 代码块 / CRLF / 脏输入；另用假 `fetch` 返回一段 SSE 文本，断言请求体多出 `asNote`、`note` 帧被解析成 `{noteId}` 且帧序 `delta → note → done` 不变）；`node scripts/verify-chatNoteCard.mjs` → **23/23**（16 条 SFC 静态接线 + Vite SSR 真渲染一条 NOTE 历史消息：卡片三要素 / `href="/notes/527"` / 同一条正文没有铺成聊天正文 / 普通消息不受影响）。既有 7 个前端自检全绿（8 / 14 / 26 / 46 / 17 / 10 / 16+1）；`npm run build` 通过
  - **ego lite 真实浏览器（2026-11-15，临时账号 + 真实模型，用后即删）**
    - 开关：默认 `aria-pressed=false`；点开后 `tool-btn on`、提示文案切到「已开启，这一轮直接写成一篇笔记」；**点发送后立刻回到 false**
    - 请求：`POST /api/ai/chat/stream` **200**，body 为 `{conversationId, message, useRag:false, useTools:false, asNote:true}`
    - 生成中：聊天里只有「正在写成一篇笔记…」，无半篇正文；**14 秒**后出现卡片 → `NOTE` 标签 + `2227 字 · 可批注` + 标题「线程池核心数怎么定」+ 80 字摘要 + 「打开笔记 →」→ `href="/notes/529"`
    - 点卡片：进 `/notes/529`，顶栏 `NOTE · 笔记`、标题「线程池核心数怎么定」、`2,319 字 · 更新于 11-15 13:31`、**32 块正文**、右栏空态、可进编辑态
    - 刷新：回到 `/guide/{id}` 刷新后**卡片仍在**（`AT-02`）
    - 不干扰：同一会话不开开关再问一句 → 26 秒的引导式回答（正文 + 反问），**没有产生第二张卡片**（`AT-01` 的笔记半边、`AT-13` 的“关掉开关行为不变”）
    - 截图：`preview/t021-chat-note-card.png`（卡片与普通回答同屏）、`t021-note-switch-on.png`、`t021-note-pending.png`、`t021-note-detail.png`
  - **顺带修的一处不一致**：`NoteDetailView` 顶栏原本写死「AI 生成的笔记」，从卡片点进来标题对不上。改为 `noteTitle(markdown)`（与卡片同源），`verify-NoteDetailView` 16 → **17/17**
  - **如实说明**：① 第一次跑完第二轮的普通问答后，`t_message` 里只多了笔记那两条 —— 那条普通回答落库时 `commit` 抛 `TransactionSystemException`（`t_ai_call` 记 `success=false`），**用 curl 重跑一次即正常入库**，同时本机 `psql` 也间歇连不上 72.155.89.172，判断是远程库网络抖动而非代码缺陷（详见 `docs/impl-log.md`）；② 那条失败的事务在库侧留下一个 **17 分钟 idle in transaction** 的会话、持锁把后续删除全堵住，已 `pg_terminate_backend` 清掉；以后遇到「DELETE 一直不返回」先查 `pg_stat_activity` 的 `idle in transaction`

- [x] **T022** **M2 验收闸门** · ✅ 2026-11-15（7 条 AT + 2 条 TC 全过，TC-10 未跑）
  - 依赖：**T021** + **T018（M1 必须通过）** + **T012** · 跑 `AT-01、AT-02、AT-03、AT-07、AT-10（笔记半边）、AT-12、AT-14` 与 `TC-07、TC-09、TC-10（可选项，见下）`
  - 通过标准：同一会话先普通问、再开开关生成笔记，两者互不干扰；卡片可进详情页；正文可编辑；编辑后旧批注仍可读；`t_ai_call` 三条记录可辨
  - **自动化**：后端约定 11 类 **71/71**（`failures=0 errors=0`，含 `ChatServiceTest` 的 `TC-07` 帧序「`delta → note → done`」与 `NoteControllerTest` 11 条）；前端 9 个自检全绿（8 / 14 / 46 / 26 / 17 / 10 / 17 / 18 / 23）
  - **ego lite 真实浏览器（临时账号 `t-gate-566825` + 真实模型，用后即删）**
    - `AT-01`：关开关问「线程池核心数怎么定？」→ 4,594ms 的**引导式**回答（正文 + 「现在轮到你：你的任务是哪一类？」反问），`cards=0`；开开关 → `aria-pressed=true`、发送后**立刻回到 false**、生成中只有「正在写成一篇笔记…」→ **10.2 秒**出卡片 `2440 字 · 可批注`，标题「ThreadPoolExecutor 中 corePoolSize 与 maximumPoolSize 的关系」。两条互不干扰
    - `AT-02`：刷新 `/guide/{id}` → **卡片仍在**（1 张）→ 点「打开笔记」→ `/notes/573`，顶栏标题与卡片一致、`2,827 字`、**28 块正文**、右栏空态
    - `AT-03`：点「编辑正文」→ 改掉一个数字（`3` → `4`）+ 末尾追加「## 校验段 / T022 闸门追加的一段」→「已保存」→ 刷新 → **30 块**、新段落渲染出来、`textarea` 已退出、`[contenteditable=true]` 数量 **0**、富文本工具栏选择器命中 **0**
    - `AT-07`：先造一句正文里唯一存在的「量子纠缠不传递信息」并批注它 → 编辑删掉整段 → 刷新 → **「原文已修改 1」分组出现**、卡片带 `原文已修改` 标签、答案 143 字**一字不少**；另一条引用 `ThreadPoolExec` 的批注仍留在「第 4 段」，因为正文别处还有同名子串（见下方如实说明）
    - `AT-10`（笔记半边）：新注册 B 账号 → `GET /api/notes/573` **403**、`GET /api/annotations?targetType=NOTE&targetId=573` **403**、把 A 的批注 id `4875` 当 `parentId` 提交 → **403**「无权访问该正文」
    - `AT-12`：900px 视口 → `.note-rail` 的 `top (3227) ≥ .doc-col` 的 `bottom (3203)`（批注栏**移到正文下方**）、`scrollWidth 900 == clientWidth 900`（**无横向滚动**）
    - `AT-14`：`t_ai_call` 四行 **272~275** 分别 `CHAT` / `NOTE_GENERATE` / `ANNOTATION` / `ANNOTATION_CHILD`，`success=t`、`error_code` 全空、分别 4594 / 10153 / 1487 / 1267 ms
    - 截图：`preview/t022-at01-card.png`（普通回答 + 笔记卡片同屏）、`t022-at02-detail.png`、`t022-at12-narrow.png`、`t022-at07-stale.png`
  - **`TC-07` / `TC-09`**：`TC-07` 由 `ChatServiceTest` 覆盖（帧序含 `note` 且在 `done` 前），库里 `t_message` 的 NOTE 行（`id=573`、`type='NOTE'`）与 `t_ai_call.scene='NOTE_GENERATE'` 双向印证；`TC-09` 由上一条四行埋点直接证伪——三个新 scene 全部写得进、无约束报错
  - **`TC-10` 未跑**：需另建「未执行 migration」的空库，按本任务原文留作可选，**T023 的执行记录里必须标注未覆盖，不得写成通过**
  - **如实说明（AT-07 第一次没过，值得记）**：第一次只删了引用文本 `ThreadPoolExec`（一个词的前 14 字），刷新后批注**没有**进「原文已修改」，而是被重定位到「第 4 段」。原因是 `resolve()` 的降级顺序是「原 `blockKey` 命中 → 按 `quote` 全文 `includes` 搜索 → 都没有才 stale」，而正文别处还有五次 `ThreadPoolExecutor`，`ThreadPoolExec` 作为子串仍能命中 —— **这不是实现缺陷，是 `FR-10` 明确允许的降级（宁可丢「贴得准」，不得丢内容）**，但用户观感上会是「批注跟着词跑到别的段了」。要让它进 stale 分组，引用必须是正文里唯一的片段（如上条用「量子纠缠不传递信息」），或整段被删。此点已记入 `docs/impl-log.md` 的已知行为，不另开任务

---

## Phase 5 · 收尾

- [x] **T023** `[FR-01][NFR-02][NFR-04]` 全量回归 + 执行记录归档 · ✅ 2026-11-15
  - 文件：`docs/impl-log.md`（追加）、`docs/decision-log.md`（可选）
  - 依赖：T022 · 跑 `AT-13` 与 `spec.md` 第 6 节全部 AT 复跑一遍
  - 完成：关闭开关时问答 / 出题 / 判分 / RAG / 工具链路行为与改动前一致；无新增依赖、无 Key 相关改动；记迁移脚本执行时间与库名、G1/M1/M2 三个闸门的结论、已知限制 R1~R7、TC-10 是否覆盖
  - **全量 `mvn test` 104/104 全绿（`BUILD SUCCESS`，14 分 20 秒）**：这是第一次在改造后跑含真实模型调用的全量套件 —— `RagAnswerEvalTest` 346.8s、`RagBaselineEvalTest` 154.2s、`RagEvalTest`、`QuizPromptEvalTest` 58.6s（出题）、`GradingEvalTest` 22s（判分）、`LiManusTest` 72.5s（Agent/工具）、`LoveAppTest` 19.7s（问答）、7 类 tool 单测。上一轮约定 11 类 **76/76** 另行跑过。**此前该套件只在 2026-10-26（改造前基线）跑过**，所以这一跑同时是「行为与改动前一致」的实证。
  - **`AT-13` 五条链路实测**（临时账号 + 真实模型，用后即删）：关开关 → 886 字引导式反问、零卡片、`scene=CHAT`；开「资料检索」→ 回答逐句引用上传资料原文（含 `allowCoreThreadTimeOut` 等只在该资料出现的词）、`参考了 4 段资料`；查时间 → `getCurrentTime` 工具行 1 条、答案带真实时间；「做成题」→ 遮罩 8 秒 → `QUIZ_GENERATE` → 3 道题；提交作答 → `GRADE` → `FAIL` + 4 条缺口（内容判定正确，链路通）。
  - **`AT-01`~`AT-16` 全部复跑通过**，其中 `AT-07` / `AT-08` 都是**第二次才过**，两次失败都是验收构造问题（引用被改成了「仍包含原子串」的形态，`resolve()` 正确地重新命中而非进 stale），不是实现缺陷 —— 详见 `docs/impl-log.md`。
  - **依赖与 Key 零改动**：`pom.xml`、`package.json`、`package-lock.json`、`application.yml`、`application-local.yml` 全部 `git diff` 为空；无 Key 相关文件改动。后端改动面仅 `LoveApp`（纯新增 `noteChatClient` / `NOTE_PROMPT` / `doNoteByStream`）、`ChatService`（`asNote=false` 分支逐行等价）、`AiChatController`（`Boolean asNote`，null → false）、`CorsConfig`（补 PATCH）与 4 个向后兼容的模型 / 仓储文件；`QuizService` / `GradingService` / `HelpService` / `KnowledgeService` / `PracticeService` 等**一行未动**。
  - **`TC-10` 仍未覆盖**：需另建「未执行 migration」的空库并起第二个实例，本轮未做。**不是通过，是未覆盖**；R5 的兜底仍是「执行记录写 `docs/impl-log.md`」（本条即为该记录）。

---

## Phase 6 · 追加需求：笔记列表（2026-11-15 用户提出）

> 起因：用户 2026-11-15 在浏览器里自己生成了一篇笔记（`t_message.id=534`），发现笔记的唯一入口是聊天里那张卡片，会话一多就找不回来。已按纪律先改 `spec.md`（新增 `FR-14`、移出 Non-goals 第 7 条、加 `AT-15`/`AT-16`）与 `plan.md`（`5.2` 加接口 #8），再排任务。
> **不改 T001~T023 的任何编号、依赖、FR 标签与闸门覆盖**；这两步是 M2 之后新增的，不影响已 PASS 的里程碑。

- [x] **T024** `[FR-14]` 后端笔记列表接口（垂直切片） · ✅ 2026-11-15
  - 文件：`repository/MessageRepository.java`、`service/NoteService.java`、`controller/NoteController.java`、`test/.../NoteControllerTest.java`
  - 依赖：**T004**（`MessageRepository` 的 NOTE 读写底座）、**T012**（`NoteService` / `NoteController` 已存在）
  - 完成：`GET /notes?limit=50` 返回 `[{messageId, conversationId, content, annotationCount, createdAt, updatedAt}]`，按 `updated_at DESC`；归属只认 `t_conversation.user_id`；空列表 `[]`
  - 口径（`plan.md` 5.2 #8）：**后端不算标题 / 摘要**，正文原文交给前端；`annotationCount` 用 `LEFT JOIN t_annotation a ON a.target_type='NOTE' AND a.target_id = m.id::text` 聚合（`target_id` 是 varchar，**必须 cast**）；不做游标分页
  - 测试：同用户两篇的排序、`annotationCount` 计数、别人（含 0 篇）看不到、`limit` 生效、未登录 401
  - **落地补充**：`limit` 走**夹取**不报错（null / >50 → 50，≤0 → 1），少一个错误码分支；排序补 `m.id DESC` 作次级键，避免同一 `now()` 写入的多篇顺序不确定；批注数不额外过滤 `a.user_id`（笔记归属单一用户 + 只有所有者能批注，FR-12），plan 已注明将来共享笔记需补这一条
  - **验证**：`NoteControllerTest` **16/16**（原有 11 条 + 新增 5 条：401 / 空数组 / 倒序与批注数含子批注 + 普通消息不入列 / 归属隔离 / limit 与越界夹取）；后端子集 10 类 **75/75** 全绿

- [x] **T025** `[FR-14]` 前端笔记列表页 **＋导航入口 ＋路由** · ✅ 2026-11-15（AT-15 / AT-16 真实浏览器通过）
  - 文件：`api/notes.js`、`views/NotesView.vue`（新增）、`components/NavRail.vue`、`router/index.js`、`scripts/verify-NotesView.mjs`
  - 依赖：**T024**（接口）、**T021**（`utils/noteCard.js` 的 `noteCardOf`）、**T020**（`note-detail` 路由与详情页）
  - 完成：左侧导航第 3 项「笔记」（排在「资料库」与「待办」之间，图标与资料库的书本区分开）；列表按更新时间倒序，每条 = 标题 + 前 80 字摘要 + 字数 + `N 条批注` + 更新时间，点击跳 `note-detail`；空态引导「在对话里打开『生成笔记』开关」；`api/notes.js` 加 `list()`
  - **文案复用 `noteCardOf()`**，不在页面里另写一套标题 / 摘要提取 —— 保证卡片、列表、详情页三处标题一致
  - 自检：`scripts/verify-NotesView.mjs`（adapter 拦请求断言 URL / 空列表 / 倒序渲染 / 跳转 href / 无 `v-html`），并回归既有 9 个前端脚本
  - 验收：`AT-15`（两篇倒序 + 批注数 + 点进详情页）、`AT-16`（空态 + 看不到别人的）—— 真实浏览器里跑，数据用用户自己的 `534` 那篇 + 另造一篇，用后即删
  - **落地补充**：① 页面加 `initialNotes` prop（默认 `null`）仅供 SSR 自检注入 —— SSR 不跑 `onMounted`，没有它就只验得到加载态；与 `AnnotatableDoc` 的 `initialAnnotations` 同一做法，生产路径不传。② 行文案用 `Map` 缓存、key 带正文长度，模板里 `card(n)` 调三次不会重扫三遍 Markdown。③ 列表顺序完全交后端，页面里断言过没有 `.sort(`。
  - **验证**：`verify-NotesView.mjs` **27/27**；既有 9 个前端脚本全绿（8 / 18 / 10 / 23 / 18 / 14 / 46 / 26 / 17，其中 `verify-notes` 的「恰好两个导出」改为三个、`verify-NoteDetailView` 从 17 增至 18）；`npm run build` 通过
  - **真实浏览器（ego lite，临时账号 `t-m3-0qzt0a` + 库里造的两篇，用后即删）**
    - `AT-15`：`/notes` 两行 —— 「线程池核心数怎么定」在前、「Kubernetes 入门笔记」在后（列表顺序 = 后端 `updated_at DESC`）；meta 分别 `133 字 · 2 条批注 · 更新于 11-15 14:25` 与 `117 字 · 0 条批注 · 更新于 11-15 13:25`；`href` 是 `/notes/646` / `/notes/647`；点第一行 → `/notes/646`，6 块正文、顶栏标题与列表一致、右栏「批注 2」两条都贴在对的段落上；再点导航「笔记」回到列表仍是两行、导航项高亮
    - `AT-16`：同一个账号在造数据前打开 `/notes` → 「还没有笔记」+ 「在对话里打开「生成笔记」开关…」+「去对话」（`/guide`），列表 0 行；而库里此时 lcz 名下已有笔记 → **别人的笔记一条都看不到**
    - 控制台钩子（`error` / `unhandledrejection` / `console.error`）三轮全部为空
    - 截图：`preview/t025-at15-list.png`、`t025-at15-detail.png`、`t025-at16-empty.png`
  - **验收中顺手修的一处不一致（值得记）**：列表显示 `133 字`、点进详情页显示 `128 字`。原因是详情页顶栏用的是 `markdown.replace(/\s/g,'').length`（去掉空白但**留着 `#`**），而卡片 / 列表用 `noteChars()`（去掉 `#` 但**留着行内空格**）。两个数都对得上各自的算法，但用户看到的就是「同一篇笔记两个字数」。已把 `NoteDetailView` 的副标题改成 `noteChars(markdown.value)`，三处同源；`verify-NoteDetailView` 加一条断言防复发（17 → 18）。复验后列表与详情页都是 `133 字`。**这条不属于 T020/T021 的编号或闸门范围变更**，只是同一「文案同源」纪律的延伸

---

## 7. 依赖与并行

### 7.1 分层依赖图（无环）

```
L0  T001
     │
L1  T002   T003   T004        ← 共享底座：三者互不依赖，各自只靠 T001
     └────────┬────────┘
L2          T005 → T006 → T007 (G1 后端闸门)
                              （T004 另供 L3 / L5 使用）
L3  T008  T009 → T010 → T011   （全部另依赖 T004；T010 另依赖 T008/T009）
     T012                       （依赖 T004）
              │
L4  T013  T014 → T015 → T016 → T017 → T018 (M1 阅读页闸门)
                              （T014 另依赖 T006；T018 另依赖 T007）
              │
L5  T019 → T020 → T021         （T019 依赖 T012；T020 依赖 T016；
                                 T021 依赖 T008/T011/T004/T020）
              │
L6  T022 (M2 全量闸门) → T023  （T022 另依赖 T018 + T012）
```

分层读法（跨层的额外前置写在每个任务的「依赖」栏里）：

| 层 | 任务 | 前置 |
|---|---|---|
| L0 数据 | T001 | — |
| L1 共享底座 | T002 / T003 / **T004** | T001 |
| L2 批注服务 | **T005** → T006 → T007(G1) | L1 |
| L3 笔记生成后端 | T008 / T009 → T010 → T011，T012 | T004 + T008/T009 |
| L4 前端组件 | T013 / T014 → T015 → T016 → T017 → T018(M1) | T006 + T007 |
| L5 笔记页与卡片 | T019 → T020 → T021 | T012 + T016 + T011 |
| L6 闸门与收尾 | T022(M2) → T023 | T021 + T018 |

- **按编号顺序推进即可**：所有依赖都指向更小的编号（见 7.1 校验结论），因此严格按 T001 → T023 逐个落盘、逐个测试，不会出现「用到还没创建的方法」。若想并行，须自行核对各任务的依赖栏与文件是否冲突（例如 T014 依赖 T006，不能与 T002/T003/T004 同时起步）。
- **三个闸门**：T001（没跑迁移后面全报错）、**T007 G1**（后端链路未通不要开前端）、**T018 M1**（M1 没跑通不要开 M2）、T022 M2。
- **每个里程碑都能停**：只做到 T007 后端批注链路可被集成测试完整覆盖；只做到 T018 有完整可演示的功能（阅读页批注 + 追问链）。

### 7.2 排布缺陷登记（2026-11-14，v1.1 已修复）

| # | 旧版问题 | v1.1 处理 |
|---|---|---|
| 1 | 旧 T004 需要 NOTE 全文/归属，能力却在旧 T012（Phase 2），只能靠 `JdbcTemplate` 临时 SQL 绕过 | **T004 提前到 L1**；T005 打回未完成、按新前置重做，移除临时 SQL |
| 2 | 旧 T014/T015/T016 成环：`ChatService` 调 `doNoteByStream`（更晚才建）、6 参签名更晚才改 | 拆成 T009 → T010 → T011，每步单独编译通过 |
| 3 | 旧 T011 依赖栏漏 T005 的四个 HTTP 接口；依赖图没汇入 | T007 先做后端闸门，T018 明确依赖 T007 |
| 4 | 旧 T011 要跑 AT-07 / AT-10 / AT-12，都需要 Phase 2 才有的笔记页 | AT-07 / AT-12 / AT-10 笔记半边移到 T022 |
| 5 | 旧 T008 编辑态 import `api/notes.js`（T018 才建）、渲染 `AnnotationCard`（T009 编号更晚） | T015 提到 T016 之前；编辑态改 `emit('save')`，保存由 T020 注入 |
| 6 | 旧 T018 路由动态 import `NoteDetailView`（T019 才建），两者互赖 | 路由并入 T020，与页面同一次提交 |
| 7 | `[P]` 定义说「无依赖」，但带 `[P]` 的旧 T017 依赖 T012、T009 依赖 T007；「第一批互不依赖」与事实矛盾 | 重定义 `[P]` 为「文件不冲突、可并行落盘」，并按真实前置重列第一批 |

`plan.md` 的 R7 随之从「待偿还技术债」改为「已由顺序重排消除」。

---

## 验收映射

| 闸门 | 覆盖 AT | 覆盖 TC |
|---|---|---|
| T007 (G1) | —（仅集成测试，AT 黑盒留 T018） | TC-03 / 04 / 05 / 06 / 08 |
| T018 (M1) | AT-04 / 05 / 06 / 08 / 09 / 10（批注半边）/ 11 | TC-01 / 02 / 03 / 04 / 05 / 06 |
| T022 (M2) | AT-01 / 02 / 03 / 07 / 10（笔记半边）/ 12 / 14 | TC-07 / 09 / 10（可选） |
| T023 | AT-13 + 全量 AT 复跑 | — |
| T024 / T025 (M3) | AT-15 / AT-16 | — |

## 明确不动的文件

`db/schema.sql`、`application.yml`、任何 Key 相关代码、`request.js` 拦截器、Vite 配置、除 `ChatService` 之外的 6 个既有 Service、`SectionReadView` 的 `.action-bar` 与翻页逻辑。
另：**T005 之前不新建 / 不删除任何前端文件，也不改动已落盘的 `AnnotationService.java`**——它在 T005 才被替换。
