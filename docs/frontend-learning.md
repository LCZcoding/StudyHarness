# 前端学习指引 · 面向「能读懂、写不出」的后端视角

> 前提：你走全栈路线，前端目前的状态是「能看懂大概代码」。这份文档不教你成为前端工程师，只帮你把前端拉到「能独立改一个页面、对接一个接口、自己排查问题」这条线上——**用本项目当教材，不开新教程项目**。
> 返回 [决策日志索引](./decision-log.md) ｜ 相关：[纵切实施记录](./impl-log.md) ｜ [简历素材](./resume-assets.md)

***

## 0. 先校准目标（换个更狠的判据）

大多数人的前端学习目标是模糊的「学好前端」。模糊目标没法验收，也就没法判断自己有没有进步。

**这里换成一个可证伪的判据**：随便挑一个页面，你能不看文档答出下面三条链路，就算合格：

1. **数据从哪来** —— 界面上某个会变的东西，它的源头是哪一行 `ref()`？
2. **点击后发生什么** —— 点一个按钮，函数调用链是什么，谁发了请求？
3. **界面为什么变了** —— 数据变了以后，是哪个绑定 / 哪个 `watch` 让画面跟着变？

这三条答不上来，说明你还是在「看代码像看天书」的阶段；答得上来，你就已经能改代码了——**因为改代码的本质就是在这三条链路上插一脚**。

**明确不做的事**（明年再看也不迟）：webpack / Vite 打包原理、React、微前端、CSS 预处理器、TypeScript 进阶。对实习简历零收益，还会挤掉后端亮点的时间。

***

## 1. 知识地图：只学这个项目真正用到的

按「项目倒推」列清单，不按教程章节顺序。每一项都标了本项目哪里用到——**看到用到的再去学，学完立刻能用，这才不白学**。

| 知识块 | 只学到这个程度就够 | 本项目在哪里用到 |
| --- | --- | --- |
| HTML | 语义标签：`header` / `section` / `footer` / `textarea` / `button` | [ChatRoom.vue](../ai-agent-frontend/src/components/ChatRoom.vue) 的 template |
| CSS | 选择器、盒模型、`display:flex`、CSS 变量 `var(--x)`、`@media` 媒体查询、`:hover` / `:focus-within` 伪类、`@keyframes` 动画 | 同上，整个 `<style scoped>` |
| JS 基础 | 模块 `import` / `export`、箭头函数、解构、模板字符串、数组 `map` / `filter`、`Promise` / `async-await`、可选链 `?.` | [sse.js](../ai-agent-frontend/src/api/sse.js)、[ChatRoom.vue](../ai-agent-frontend/src/components/ChatRoom.vue) 的 script |
| Vue 模板语法 | `{{ }}` 插值、`v-if` / `v-else`、`v-for`、`v-model`、`:属性` 动态绑定、`@事件` | ChatRoom.vue 全文 |
| Vue 响应式（**核心**） | `ref()` 存值、`computed()` 派生值、`watch()` 副作用 | ChatRoom.vue 的 `input` / `messages` / `streaming` / `shortChatId` |
| Vue 组件通信 | `defineProps`（父传子）、`emit`（子传父） | ChatRoom.vue 的 `defineProps`；GuideChatView 传 `sse-path` / `chat-id` |
| Vue 生命周期 | `onMounted`（挂载后）、`onBeforeUnmount`（卸载前清理） | ChatRoom.vue 的 `onBeforeUnmount(closeSource)` |
| vue-router | routes 表、`RouterLink`、懒加载 `() => import(...)` | [router/index.js](../ai-agent-frontend/src/router/index.js) |
| axios | 实例 + `baseURL`、响应拦截器、`params`、`withCredentials` | [api/request.js](../ai-agent-frontend/src/api/request.js) |
| Vite | dev server、proxy、build；**改 proxy 必须重启 dev** | [vite.config.js](../ai-agent-frontend/vite.config.js) |

**关于 ref 的「为什么」**（这是 Vue 最容易被当成玄学的一点）：`ref(x)` 返回的不是 `x` 本身，而是一个**带拦截器的盒子**，你读写要走 `.value`。当你写 `messages.value.push(...)` 时，Vue 拦截到了这次写操作，知道「依赖这块数据的界面需要重画」。**框架的"自动更新"不是魔法，是它劫持了你对 `.value` 的读写**。理解了这句，`ref` / `computed` / `watch` 就都不玄了。

***

## 2. 读懂一个单文件组件：三步读法（以 ChatRoom.vue 为标本）

单文件组件（SFC）把三样东西塞进一个 `.vue` 文件：`<template>`（长什么样）、`<script setup>`（怎么动）、`<style scoped>`（好不好看）。**别从头顺读，按下面顺序读**：

**第一步：读 template，画 UI 地图**（先看不认识的标签和绑定，不纠结 CSS）
- 这个页面就三块：顶栏 `chat-header`、消息区 `messages`、输入区 `composer`
- 顺手记下所有 `:`（动态绑定）和 `@`（事件）——它们是「界面和代码的接口」

**第二步：读 script setup，找出四类东西**

| 类别 | 在 ChatRoom.vue 里 | 作用 |
| --- | --- | --- |
| `ref()` 状态 | `input` / `streaming` / `messages` | 会变的数据 |
| `computed()` 派生 | `shortChatId` | 由状态算出来的值（不额外存一份） |
| `watch()` 副作用 | 监听 `messages` 内容 → 自动滚到底 | 数据变了要「顺便做点别的事」 |
| 函数 | `send` / `stop` / `finish` / `closeSource` / `parseAiContent` | 事件过来时执行的逻辑 |

**第三步：对账**——把第一步记的 `:` 和 `@`，逐个在第二步里找到对应物。找不到的，就是你没读懂的地方。

**做完对账后你应该能画出这条数据流**（这就是第 0 节判据的答案）：

```text
textarea --v-model--> input(ref)
点击"发送" --@click--> send()
  ├─ addMessage('user', text)      → messages.value 多一条 → 界面多一个气泡
  ├─ streaming.value = true        → 出现光标 caret、按钮变"停止"、输入框 disabled
  └─ createSseChat({...})           → 发起 EventSource 请求
        onMessage(chunk) → aiMsg.content += chunk → 气泡文字逐字变长
        onDone/onError   → finish() → streaming=false → 光标消失、按钮复原
messages 内容变化 --watch--> nextTick 后 scrollTop = scrollHeight  → 自动滚到底
```

**看到没**：界面上每一个会变的东西，源头都能追到某一个 `ref`；每一个能点的东西，都能追到某一个函数。**这张图就是"读懂"的证明**。

***

## 3. 练习路线：把③格拆成你能自己验收的小步

每一步都写了**验收标准**——你自己就能判断做没做对，不需要问我。**先说清为什么，再动手**。

### 练习 0（热身，不改代码）：把它跑起来 + 打开工具箱

- 前端目录 `npm install` → `npm run dev`，浏览器开 `http://localhost:5173`
- 按 `F12` 打开开发者工具，切到 **Network** 面板，发一条消息
- **验收**：Network 里出现一条 `/api/ai/love_app/chat/sse?...&chatId=xxx`，点开能看到 **EventStream** 标签，里面逐条刷新数据

**这一步的意义**：先建立「我发出去的请求长什么样」的直觉。以后所有前端问题，第一反应都是「去 Network 看」。

### 练习 0.5（③格新增，必须先做）：让前端拿到「登录态」

- **为什么必须先做**：③格要打的接口是 `POST /api/ai/chat/stream`，它的第一道门就是「从 session 取 userId，取不到 → 401 `未登录`」（见 [api-chat-stream.md](./api-chat-stream.md) §2.3）。前端当前**没有任何登录入口**（[HomeView.vue](../ai-agent-frontend/src/views/HomeView.vue) / [GuideChatView.vue](../ai-agent-frontend/src/views/GuideChatView.vue) 里一次都没调过登录），session 是空的 → ③格每一步都会 401。后端 `POST /api/auth/login`（body `{"username","password"}` → 200 `{"userId":"..."}`，靠 `Set-Cookie` 下发 `JSESSIONID`）已经就绪，缺的只是前端这一侧
- **要加的三样东西**：
  1. `../ai-agent-frontend/src/api/auth.js`：`login(username, password)`，内部 `request.post('/auth/login', { username, password })`
  2. `../ai-agent-frontend/src/views/LoginView.vue`：两个输入框 + 一个按钮 → 调 `login` → 成功 `router.push('/')`
  3. [router/index.js](../ai-agent-frontend/src/router/index.js) 加一条 `/login` 路由；[HomeView.vue](../ai-agent-frontend/src/views/HomeView.vue) 顶栏加一个「登录」入口（否则只能手敲 URL）
- **顺手要理解的 Vue 概念（登录页一次性用到三样）**：
  - `v-model="username"`：**双向绑定**——输入框里敲的字会自动写进 `username` 这个 `ref`，反过来 `username.value` 变了输入框的值也跟着变（这就是第 1 节说的「带拦截器的盒子」在表单上的用法）
  - `@submit.prevent="submit"`：`@submit` 是事件，`.prevent` 是**修饰符**，等于帮你调了 `event.preventDefault()`——表单默认提交会导致整页刷新，刷新就把 Vue 的状态全清了
  - `try / catch / finally` + `:disabled="loading"`：请求是异步的，按钮要在请求期间禁用来防重复提交
- **错误怎么显示**：后端失败时返回 `{"message":"用户名或密码错误"}`，全局处理器已经保证**所有错误响应体都是这一个形状**。所以前端只要 `catch (e) { error.value = e.response?.data?.message || '登录失败' }` 一句话就够，不需要为每种错误写分支
- **验收**：
  - 浏览器开 `http://localhost:5173/login`，填 `lcz` / `testhash` 提交 → Network 里 `POST /api/auth/login` 是 **200**，**Response Headers 里有 `Set-Cookie: JSESSIONID=...`**
  - Application → Cookies 里能看到 `JSESSIONID` 这一条
  - 故意输错密码 → 页面显示「用户名或密码错误」，不是白屏、不是 500

### 练习 1（最小改动）：给 axios 补上 `withCredentials`

- 现状：[request.js](../ai-agent-frontend/src/api/request.js) 的实例**没有** `withCredentials`
- **为什么**：后端身份靠 `HttpSession`（Cookie 里的 `JSESSIONID`）承载，只有请求**带上 Cookie**，后端才认得你是"已登录的用户"。axios 默认不往"跨源"请求里塞凭据，`withCredentials: true` 就是明确告诉它「带上」
- **诚实提醒（别制造假需求）**：dev 环境走 Vite proxy，浏览器眼里是**同源**，Cookie 本来就会自动带；所以这一步在本地**不是必需的**。但它是**显式意图**，且一旦 `build` 后前端和后端部署到不同域（或换端口），少了它就会 401。**这就是"现在加上、以后不踩坑"的改动**
- **验收**：先调后端的 `POST /api/auth/login` 登录，再随便发一个 `/api` 请求，在 Network 的 **Request Headers** 里看到 `Cookie: JSESSIONID=...`；同时 Application → Cookies 里能看到这个值

### 练习 2（写下第一个 api 函数）：`createConversation`

- 新建 `../ai-agent-frontend/src/api/conversation.js`（或加进 `chat.js`），写一个 `createConversation(mode)`，内部是 `request.post('/conversations', { mode })`
- **注意一个坑（拦截器已经在替你干活）**：[request.js](../ai-agent-frontend/src/api/request.js) 的响应拦截器写的是 `(response) => response.data`，所以你 `await createConversation(...)` 拿到的**直接就是响应体** `{ conversationId: "..." }`，不用再 `.data` 一次
- **验收（不用改任何界面就能验）**：先在 `/login` 登录，然后在浏览器 Console 里敲两行（Vite dev 会按路径直接提供模块，所以 `Console` 里能 `import` 到源文件；**直接写 `createConversation(...)` 会报未定义**，因为模块导出不会挂到 `window` 上）：
  ```js
  const { createConversation } = await import('/src/api/conversation.js')
  await createConversation('PRACTICE')
  ```
  → 打印出 uuid；同时 Network 里出现 `POST /api/conversations` → **201**
- **这一步的意义**：**Console 里直接调 api 函数**是前端最高效的契约验证手段——不用为了验一个接口去改界面。这是你以后自己写前端时最常用的招

### 练习 3（真正的「写」）：让 GuideChatView 从后端拿会话 id

- **现状与为什么**：[GuideChatView.vue](../ai-agent-frontend/src/views/GuideChatView.vue) 里是 `const chatId = createChatId()` ——**前端自己造会话主键**。这是要改掉的核心问题（依据见[接口约定](./impl-log.md)：服务端才是会话 id 的唯一权威，前端造 id 在第二个用户那里会撞、刷新就丢）
- **目标形态**：
  1. `chatId` 从「同步生成的普通变量」改成 `ref('')`
  2. 在 `onMounted` 里 `await createConversation('PRACTICE')`，拿到后写进 `chatId.value`
  3. 处理两种非正常态：请求中（可先放个 loading）、请求失败（给个提示，别静默）
- **好消息**：ChatRoom.vue 里已有 `v-if="chatId"` 兜住顶部显示，`send()` 里也是 `if (props.chatId) params.chatId = ...`——**空的 chatId 不会把页面搞崩**，你可以安心分步改
- **范围**：本练习只迁 GuideChatView（学习助手这条链路）。`ManusChatView.vue` **也在用** `createChatId()`，但它当前不落库（没有对应的 `t_conversation` 记录），先不动
- **验收**：
  - 刷新页面 → Network 里出现 `POST /conversations`（能证明 id 是后端给的，不再是 `crypto.randomUUID()`）
  - 再发一条消息 → SSE 请求的 `chatId` 参数，等于上一步后端返回的那个 uuid
  - 顶部 `CHAT ID` 显示的是 `shortChatId`（去横线取前 12 位），不必逐字相等，**对得上前缀即可**
- **改完别急着删**：`utils/chatId.js` **仍有 `ManusChatView` 在用**。删除无用代码的前提是「先搜确认真没人用」——这正是你在后端删 import 时的习惯（`grep` 一下，别凭印象）

### 练习 4（认识「框架也有天花板」）

- 现状：[sse.js](../ai-agent-frontend/src/api/sse.js) 用的是浏览器**原生 `EventSource`**
- **它的硬限制**：只能发 **GET**、不能自定义请求头、不能带 POST body（这是 `EventSource` 规范定的，不是没写对）；另外服务端关流时浏览器也会触发 `error`，所以代码里必须主动 `close()` 防止自动重连——这段注释已经在 [sse.js](../ai-agent-frontend/src/api/sse.js) 里写着了
- **④格落地后它就出局了**：④格后端已实现为 `POST /api/ai/chat/stream`（消息体是 JSON）。**POST + body 的接口，`EventSource` 直接出局**，必须换成 `fetch` + 读 `response.body` 的 `ReadableStream`，手动切 SSE 帧（`TextDecoder`）
- **这一步的意义**：这是"前端不只是画画界面"的最好例子——**一个框架 API 的边界，会直接决定后端接口能怎么写**。面试讲全栈时，这条比"我会写 Vue"值钱得多

### 练习 5（③格核心）：实现 `chatStream.js`（`fetch` + `ReadableStream`）

- 新建 `../ai-agent-frontend/src/api/chatStream.js`，导出 `streamChat({ conversationId, message, onMessage, onDone, onError, signal })`：
  - `fetch('/api/ai/chat/stream', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({ conversationId, message }), signal })`
  - **失败响应是 JSON 不是流**：`if (!res.ok) { const err = await res.json(); throw new Error(err.message) }` —— `res.ok` 为真时打开的才是 SSE 流
  - `const reader = res.body.getReader()`，循环 `await reader.read()` 拿 `{ done, value }`，`done === true` 就是流结束（④格契约：**不发结束帧**，流关闭即结束）
- **三个必须理解的点**：
  - `fetch` 返回的 `res` 只是**响应头**，body 还没下载完；`res.body.getReader()` 才能一片一片读——这是"流式"的物理基础
  - `new TextDecoder()` 要带 `{ stream: true }`：一个汉字占 3 字节，有可能被切在两片之间；`stream: true` 让解码器记住上一片剩下的半个字符，等下一片到齐再吐出来，否则会出乱码
  - 停顿按钮要靠 **`AbortController`**：`fetch` 没有 `close()` 方法，只能 `controller.abort()` 真正断开连接
- **验收**：浏览器 Console 里 `await streamChat({ conversationId: '<真实 id>', message: '什么是指针的别名？', onMessage: (c) => console.log(c) })` → 看到文字**一片一片**打印，而不是最后一次性出现

### 练习 6（收尾）：让 `ChatRoom.vue` 支持 POST 流

- `ChatRoom.vue` 现在只会「拼 query 串 → `EventSource`」。加一个 `transport` prop 区分两种传输：`'event-source'`（ManusChatView 继续用的 GET 教学接口）与 `'post-stream'`（GuideChatView 走的④格接口，需要 `conversationId`）
- `stop()` / `closeSource()` 里对 `EventSource` 调 `.close()`，对 `fetch` 流调 `.abort()`
- **不要删 `sse.js` 和 `utils/chatId.js`**：[ManusChatView.vue](../ai-agent-frontend/src/views/ManusChatView.vue) 仍在用（它打的 `/ai/manus/chat` 是 GET + 无鉴权的教学接口，不落库）
- **验收**：GuideChatView 里发一条消息 → Network 里是 **POST** `/api/ai/chat/stream`（不再是 GET），回答逐字出现；发完后用 psql 查 `t_message` 能看到这一轮的 USER / ASSISTANT 两行

***

## 4. 调试工具箱：比背 API 重要十倍

| 工具 | 看什么 | 典型用途 |
| --- | --- | --- |
| Network 面板 | 请求头 / 响应头 / 状态码 / 请求体 / EventStream | 判断"请求发出去没、后端回什么" |
| Console | 直接敲代码（含 `await`） | 不改界面验接口契约（练习 2） |
| Application → Cookies | `JSESSIONID` | 确认登录态带上了没（练习 0.5 / 1 / 3） |
| Sources → 断点 / `debugger` | 函数怎么一步步走 | 看不懂代码时，让它停在你想看的那行 |
| Vue Devtools（浏览器插件） | 组件树、props、每个 `ref` 的实时值 | **学 Vue 最强的工具**：值变了立刻看得见 |

**前端问题的分类法**（先分类，再动手，能省一半时间）：

| 现象 | 大概率原因 | 先看哪里 |
| --- | --- | --- |
| 点了没反应 | 事件没绑上 / 函数里提前 return | Console 报错 + `@` 绑定 |
| Network 里根本没有请求 | 请求压根没发（被条件挡住 / 函数没走到） | `send()` 里的判断分支 |
| 请求发了但状态码不对 | 后端或契约问题（401/400/500） | Network → Response |
| 状态码 200，界面没变 | 响应式 / 绑定问题（改的不是 `ref` 的 `.value`、或少了 `v-if`） | `ref` 是不是漏了 `.value` |

**最后一条最常见也最难查**：直接给普通变量赋值（`let x = 1; x = 2`）界面**不会**变——因为那压根不是 `ref`。记住第 1 节那句「`ref` 是带拦截器的盒子」，这类 bug 就有一半不用查了。

***

## 5. 全栈路线的取舍与口才

**什么时候算够了**：能改页面、能加页面、能对接接口、能自己定位问题 → **停手，别往下沉**。

**什么时候该深**：只有目标岗位明确写「前端」或「全栈」时，才值得投入更多。否则前端是你的「表达能力」，后端才是你的「竞争力」。

**面试怎么讲全栈才不塌**：别说「我精通前端」（一句追问 `EventSource` 和 `fetch` 的区别就露馅）。说：

> 「这条路是我从数据表设计、接口契约、到前端联调自己跑通的。比如会话 id 一开始是前端生成的，我发现第二个用户会撞 id、刷新还会丢，所以把它改成服务端签发，前端只负责调接口拿 id——**这条链路上前后端的角色划分是我自己定的**。」

**为什么这样说立得住**：它不吹能力，只陈述一个你真的做过的、能画图能复现的事实——正好符合你简历素材里那条规则：**扛不住三层追问的亮点，当场砍掉**。

***

## 6. 节奏建议

- **教材就用这个项目**，别开新教程。每改一处都是往简历里加一块砖
- **一天一个练习**，改完立刻用 Network / Console 验收——**没有验收标准的改动不算学习**
- **卡住时的求助姿势**（贴这三样，比"我这报错了"有用得多）：① 我想做什么 ② Network / Console 里看到什么 ③ 期望 vs 实际

***

## 7. 自检清单（做完了自己打钩）

- [ ] 我能说出 ChatRoom.vue 里 AI 气泡的文字是怎么随每个 chunk 变长的（挂到哪个 `ref`、哪个 watch）
- [ ] 我能不看教程，给这个页面加一个「清空对话」按钮（用 `messages.value = []`）
- [ ] 我能用 Console 直接调 api 函数，验证一个还没接界面的后端接口
- [ ] 我能把前端问题先分成「没发 / 发错 / 没渲染」三类再去查
- [ ] 我能解释 `withCredentials` 为什么在 dev 下可有可无、部署到别的域就必需
