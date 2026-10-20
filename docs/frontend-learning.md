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

### 练习 7（⑤格新增）：在 `/guide` 里加左侧栏，把会话列表画出来

- **为什么**：⑤格后端的两个接口已经实测通过（`GET /api/conversations`、`GET /api/conversations/{conversationId}/messages`，契约见 [api-conversation-history.md](./api-conversation-history.md)），但前端**没有任何地方能看到它们**。这是③格之后第一个"能看见的新功能"，也是本练习的全部意义
- **为什么是左侧栏、不是独立的列表页**：最初做了一版独立的 `/conversations` 页 + 首页「我的会话」入口，被拍板推翻。三条理由：①用户要的是「**点一下就能接着聊**」，独立页面还得先跳回去；②`/guide` 每次进入都会 `POST /conversations` 新建一条空会话（库里已攒 22 条），把列表放进同页才能顺手掐掉这个源头；③`/manus` 走无鉴权教学接口、不落库，侧栏只对 `/guide` 有意义，不该做进公共组件 `ChatRoom.vue`
- **和③格那几步的关键区别**：③格打的是**流式**接口（只能用 `fetch` + `ReadableStream`）；⑤格这两个接口是**普通 JSON 接口**，用 [request.js](../ai-agent-frontend/src/api/request.js) 的 axios 实例就够 —— **不要再为它写一套 `fetch`**
- **范围**：本练习只做「侧栏布局 + 把列表画出来」。点某一行进去看历史消息、接着聊留给练习 8（一个练习只装一个能自己验收的成果）

#### 7.1 加一个 api 函数（1 个文件）

[api/conversation.js](../ai-agent-frontend/src/api/conversation.js) 里追加：

```js
// GET /conversations —— 响应拦截器已经把 response.data 剥出来了，拿到的就是 { conversations: [...] }
export const listConversations = () => request.get('/conversations')
```

- **和 `createConversation` 的唯一区别**：这个是 `GET`（没有 body），那个是 `POST`（要 body）
- **验收**（沿用练习 2 的 Console 手法，不用改任何界面）：先在 `/login` 登录，然后在 Console 里

  ```js
  const { listConversations } = await import('/src/api/conversation.js')
  const data = await listConversations()
  data.conversations.length
  data.conversations[0]
  ```

  → 打印出条数（2026-10-20 实测是 `24`）和第一条的形状；Network 里出现 `GET /api/conversations` → **200**

#### 7.2 改 `GuideChatView.vue`：把左侧栏摆进布局（1 个文件）

`GuideChatView.vue` 现在的 template 只有一行 `<ChatRoom ... />`。要变成「左边一条侧栏 + 右边聊天区」，做法是**外面包一层 flex 容器**：

```vue
<template>
  <div class="guide-layout">
    <!-- 左侧栏：历史会话（只在 /guide 出现） -->
    <aside class="sidebar">
      <div class="side-head">
        <span class="side-brand">会话</span>
        <button type="button" class="new-btn" @click="newConversation">+ 新对话</button>
      </div>

      <p v-if="listLoading" class="side-state">加载中…</p>
      <p v-else-if="listError" class="side-state error">{{ listError }}</p>
      <p v-else-if="!conversations.length" class="side-state">还没有会话，发一条消息开始吧</p>

      <ul v-else class="side-list">
        <!-- 7.3 再填这里 -->
      </ul>
    </aside>

    <!-- 原来的 ChatRoom 原样保留（练习 8 会再多传两样东西） -->
    <ChatRoom class="room" theme="guide" ... />
  </div>
</template>
```

配套样式（`.room` 是打在 **ChatRoom 根元素**上的类，原理见下表）：

```css
.guide-layout {
  height: 100%;
  display: flex;          /* 两栏并排 */
  background: #0e1116;    /* 侧栏沿用首页的深色底 + 青色强调 */
}
.sidebar {
  flex: none;             /* 不参与伸缩：宽度由 width 定死 */
  width: 262px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  border-right: 1px solid rgba(34, 211, 238, 0.14);
}
.room {
  flex: 1;                /* 吃满剩余宽度 */
  min-width: 0;           /* 弹性容器必须显式写，否则内部长文本会把它撑破 */
}
```

三个新概念：

| 概念 | 是什么 | 用你熟悉的东西类比 |
| --- | --- | --- |
| `display: flex` + `flex: 1` | 父容器按行排子项；`flex: 1` 的子项吃掉「剩余空间」 | 一个「固定宽 + 自适应宽」的两栏布局，跟后端拼 SQL 时 `sum over` 那种"拿走剩下的"是同一个直觉 |
| `.room` 类打在组件根上 | 给 `<ChatRoom class="room" />` 传的 `class` 会合并到它渲染出的根元素 `<section class="chat-room">` 上 | 相当于给复合组件再挂一个"样式钩子" |
| `flex: none` vs `flex: 1` | 侧栏 `flex: none` = 宽度我定死；聊天区 `flex: 1` = 剩下的都给我 | 左右两栏各领一个角色，别都去抢剩余空间 |

- **为什么不把侧栏做进 `ChatRoom.vue`**：`ChatRoom.vue` 是公共组件，[ManusChatView.vue](../ai-agent-frontend/src/views/ManusChatView.vue) 也在用，而 Manus 没有会话列表可显示。侧栏属于 `GuideChatView.vue` 这一层——**放对层次比放对文件更重要**
- **四种状态一个都别省**：`listLoading` / `listError` / **空列表** / 有数据。第一个用 `v-if`、后三个用 `v-else-if` —— 这是"同一时刻只有一个分支"的写法（区别于几个 `v-if` 各自独立判断，可能同时命中）
- **`v-else-if="!conversations.length"` 测的是"成功但空"**：契约定了空列表返回 `200` + `{"conversations":[]}`（不是 `204`），所以这是**正常结果**，不是错误
- **`finally` 里的 `listLoading.value = false` 不能写进 `try`**：写进 `try` 的话，一旦请求失败就永远停在"加载中…"
- **不需要加新路由**：这里是改已有的 `/guide` 页面，不是新建页面。所以**不要**再建 `ConversationsView.vue`、也不要往路由表里加 `/conversations`
- **验收**：登录后地址栏敲 `http://localhost:5173/guide` → 左侧出现一条深色侧栏（顶部「会话 / + 新对话」），右侧仍是原来的聊天界面；把窗口拉窄，聊天区变窄、侧栏宽度不变

#### 7.3 把列表渲染出来（还是同一个文件，3 个新概念）

把 `<ul v-else>` 里面填上（**点击事件先不写**，那是练习 8 的事）：

```vue
<!-- v-for：把数组里每一条渲染成一行；:key 是每行的唯一身份证，Vue 靠它复用 DOM -->
<li v-for="c in conversations" :key="c.conversationId" class="side-item">
  <span class="side-title">{{ c.title || '（暂无内容）' }}</span>
  <time class="side-time">{{ formatTime(c.updatedAt) }}</time>
</li>
```

`script` 里加一个 `formatTime`：

```js
// 后端给的是带偏移的 ISO-8601 字符串（如 2026-10-19T14:44:58.518468Z）
// new Date() 能直接解析它；传第二参数筛出「月/日 时:分」，避免侧栏太长
function formatTime(iso) {
  return new Date(iso).toLocaleString('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit'
  })
}
```

三个新概念，逐个说：

| 概念 | 是什么 | 用你熟悉的东西类比 |
| --- | --- | --- |
| `v-for="c in conversations"` | 遍历数组，每条数据渲染一份 `<li>`；`c` 是循环变量，名字随便取 | Java 的 `for (ConversationRow c : list)` |
| `:key="c.conversationId"` | 给每行一个**稳定且唯一**的标识，Vue 靠它判断"这行是原来的哪一行"，从而正确复用 DOM | 相当于给每个元素一个主键 |
| `c.title \|\| '（暂无内容）'` | `title` 为 `null` 时取右边的兜底文案（JS 里 `null` / `undefined` / `''` 都算"假值"，`\|\|` 会短路到右边） | `Optional.ofNullable(c.title).orElse("（暂无内容）")` |

- **`title` 兜底不是可选项，是必需的**：2026-10-20 实测那份数据里 **24 条会话有 22 条 `title` 是 `null`** —— 那些是③格之前 `/guide` 每次进入就新建、且**一条消息都不落库**的空会话。不兜底，列表就是一片空白行（记录在 [resume-assets.md](./resume-assets.md) 的待办里）
- **不要在前端重排序**：后端 SQL 已经按 `updated_at DESC` 排好（最近活动的在最上面），前端原样渲染。**别去干后端已经干好的事**——排序规则两边各写一份，改起来必然不一致
- **时间为什么能显示对**：契约 §7 坑 4 说过"别指望 JSON 里就是本地时间"。后端给的是带 `Z`（UTC）的 ISO-8601，`new Date(iso)` 按这个偏移定位到**绝对时刻**，`toLocaleString()` 再按浏览器时区显示。所以 `14:44:58Z` 在你机器上会显示成 **22:44**（东八区）
- **验收**：
  - 列表按时间倒序（最上面那条 `updatedAt` 最新）
  - 有标题的两条（"你好"、"什么是指针的别名？"）正常显示，其余显示"（暂无内容）"
  - 显示的时间是**北京时间**，且与库里的 `updated_at` 相差整 8 小时（即同一个绝对时刻）

#### 7.4 侧栏顶部那个「+ 新对话」（概念，不写代码）

按钮本身只有一句 `@click="newConversation"`，但它背后是本格最重要的一条拍板：**新对话懒创建**。

- **点击时什么也不建**：`newConversation()` 只把 `chatId.value = ''`、`historyMessages.value = []`，然后 `router.replace({ name: 'guide' })`
- **什么时候才真建**：等用户在空白会话里发出**第一条消息**，由 `ChatRoom.vue` 调 `resolveChatId()` → 父组件的 `ensureChatId()` 才 `POST /api/conversations`
- **为什么这么绕**：以前 `/guide` 一进来就建会话，于是库里攒了 22 条「一条消息都没有」的空会话（列表里全是"（暂无内容）"）。懒创建把「会话」的定义收紧成「**真的聊过**」——列表干净了，也少一堆无意义的 INSERT
- **代价（练习 8 要接的活）**：`chatId` 在页面生命周期内从「一定有值」变成「可能为空」。好消息是 `ChatRoom.vue` 早就有 `v-if="chatId"` 兜住顶部显示、`send()` 里也是 `if (props.chatId) params.chatId = ...`，**空 id 不会把页面搞崩**
- **验收**：点「+ 新对话」→ 右侧回到欢迎语；先在 psql 里 `select count(*) from t_conversation` 记个数，然后什么消息都不发、来回点几下 → 再查 count **一个字不变**；发一条消息 → count 才 +1

#### 练习 7 的边界（做完这些**不要**顺手做）

| 不做 | 留给 |
| --- | --- |
| 点某一行 → 进那个会话、把历史消息渲染出来 | **练习 8**（打 `GET /api/conversations/{conversationId}/messages`） |
| 路由带会话 id（`/guide/:conversationId`）、刷新不丢会话 | **练习 8** |
| `resolveChatId` 懒创建钩子、聊完刷新侧栏 | **练习 8** |
| 分页 / 搜索 / 删除会话 | 后端契约明确不做（api-conversation-history.md §6 拍板） |
| 首页顶栏显示"我是谁" | resume-assets 的待办 |
| 清理那 22 条历史空会话 | 待定，前后端契约都不受影响 |

### 练习 8（⑤格收尾）：点历史会话 → 灌历史 + 接着聊 + 懒创建

- **为什么**：练习 7 只把会话「**列出来**」了，点上去没反应。本练习补完用户真正要的那一下：**点一条 → 历史消息铺出来 → 接着聊，且续写落回同一条会话**
- **一句话概括三个动作**：用 `GET .../messages` 把历史读进来 → 交给 `ChatRoom.vue` 渲染 → 下次发消息时把**同一个 `conversationId`** 传回 `POST /api/ai/chat/stream`。**"接着聊"不需要新接口，只需要把 id 传回去**

#### 8.1 再加一个 api 函数（1 个文件）

[api/conversation.js](../ai-agent-frontend/src/api/conversation.js) 里追加：

```js
// GET /conversations/{conversationId}/messages
export const listMessages = (conversationId) =>
  request.get(`/conversations/${conversationId}/messages`)
```

- 三条契约要点：`role` 是 `'USER' | 'ASSISTANT'`（前端据此分左右气泡）；顺序是 `id ASC`（**不要**在前端重排）；`403` 的文案逐字是「会话不存在或无权访问」
- **验收**（Console 手法，沿用练习 2）：`const { listMessages } = await import('/src/api/conversation.js')` → `await listMessages('<真实 id>')` → 打印 `messages`，USER / ASSISTANT 交替出现

#### 8.2 让 `ChatRoom.vue` 能「带着历史消息进来」（1 个文件，核心）

`ChatRoom.vue` 加一个**可选** prop：

```js
// 不传 = 空会话（ManusChatView 不传，行为不变）
historyMessages: { type: Array, default: () => [] },
```

再加一个 `watch` 把「后端的消息」翻译成「界面的消息」——这是**唯一**一处新旧格式对不上：

```js
watch(
  () => props.historyMessages,
  (list) => {
    const items = list || []
    // 本地新消息的 id 从「历史里最大 messageId」往后排，避免与历史行的 :key 撞号
    msgSeq = items.reduce((max, m) => Math.max(max, Number(m.messageId) || 0), 0)
    messages.value = items.map((m) => ({
      id: m.messageId,
      role: m.role === 'USER' ? 'user' : 'ai',   // 后端 role → 界面 role
      content: m.content,
      time: formatHistoryTime(m.createdAt)
    }))
  },
  { immediate: true }
)
```

四个必须理解的点：

| 点 | 为什么 |
| --- | --- |
| `watch` 里是「整个换掉」不是「追加」 | 换会话 = 换一批消息，直接 `messages.value = ...` 赋值。写成 `push` 的话，点第二条会话时上一条的历史会粘着不走 |
| `immediate: true` | 带着 `historyMessages` 挂载（或首屏为空）时，也要走一遍这个映射，否则首次渲染没有消息 |
| `:key` 会撞号 | 历史行的 key 是数据库 `messageId`（如 `25`），本地新消息原本从 `1` 自增，一碰就重。所以先把 `msgSeq` 抬到历史最大值再自增 |
| `role` 为什么要翻译 | 后端只认 `USER / ASSISTANT`（契约 §4.2），界面只管 `user / ai`（决定气泡在左还是右）。**两边各用各的词，只在边界上翻译一次** |

- **验收（Console 手法）**：`const { default: ChatRoom } = ...` 不好直接调，这一步改用「练习 8.5 的第 1 条」在浏览器里看
- **别动 `addMessage()`**：新消息仍旧用 `++msgSeq` 自增；只要 `watch` 先把 `msgSeq` 抬起，两套 id 就井水不犯河水

#### 8.3 路由带会话 id（2 个文件，但要理解为什么加）

[router/index.js](../ai-agent-frontend/src/router/index.js) 加一条：

```js
{
  path: '/guide/:conversationId',
  name: 'guide-conversation',
  component: () => import('../views/GuideChatView.vue'),
  meta: { requiresAuth: true }
}
```

- **为什么要带 id**：③格时你抱怨过「刷新就丢」——不带 id 的 `/guide` 刷新后只能"猜"最近一条；带上 id，刷新还停在**同一条会话**
- **一条路由两个名字**：`/guide`（`name: 'guide'`，新对话空白态）与 `/guide/:conversationId`（`name: 'guide-conversation'`）。两者**用同一个组件**，vue-router 按「更具体的路径优先」匹配，声明顺序无所谓
- **同一个组件不会重新挂载**：从 `/guide` 切到 `/guide/:id` 或 `/guide/a` 切到 `/guide/b`，`onMounted` **都不会**再跑。所以必须补一个 `watch(() => route.params.conversationId)` 接住浏览器前进/后退

[GuideChatView.vue](../ai-agent-frontend/src/views/GuideChatView.vue) 里的三处分工：

- `onMounted`：**有 id 就用 id**；**没 id 就取列表第一条**（`conversations.value[0]`，后端已按 `updated_at DESC` 排好）并 `router.replace` 把 id 写进地址栏；列表也是空 → 停在空白态等懒创建
- `openConversation(id)`：点侧栏某条 → 换 `chatId` + 清空旧历史 + 重新 `listMessages` + `router.replace`
- `watch(route.params.conversationId)`：接住前进/后退（`id !== chatId.value` 才加载，避免和 `openConversation` 重复请求）

#### 8.4 懒创建 + 聊完刷新列表（1 个文件，两个小改动）

**改动 A：`ChatRoom.vue` 接受一个"建会话"的钩子**

```js
// 可选：返回 Promise<conversationId>；只在 post-stream 且 chatId 为空时用
resolveChatId: { type: Function, default: null },
```

`send()` 的 POST 流分支最前面插一段：id 为空且有钩子 → `await props.resolveChatId()` 换来 id，再去 `streamChat`。

- **`send` 因此从同步函数变成 `async`**（模板里 `@click="send()"` 照旧，`async` 不影响绑定）
- **`await` 期间用户可能点「停止」**：`stop()` 已经 `finish()` 了，所以 `await` 回来要再判一次 `if (!streaming.value) return`，否则会在已停止的会话上又发起一条流
- **建会话失败（如 401）**：把 `err.response?.data?.message || err.message` 写进 AI 气泡，别静默（和 `onError` 一个套路）。**注意 axios 的 `err.message` 只会是"Request failed with status code 400"这种技术文案**，后端契约文案在 `err.response.data.message` 里；401 时父组件 `ensureChatId()` 还会顺手 `router.push('/login')`

**改动 B：一轮结束通知父组件刷新侧栏**

```js
const emit = defineEmits(['done'])
// finish() 里（正常完成 / 出错 / 被停止都走这里）
emit('done')
```

`GuideChatView.vue` 里 `<ChatRoom ... @done="onRoundDone" />` → `onRoundDone()` 调 `loadConversations()`：刚聊过的会话冒到最上面、`title` 从 `null` 变成首条消息前 50 字。

- **为什么挂在 `finish()` 而不是只挂在 `onDone`**：出错、被停止时也该刷新列表——只要那一轮有落库，列表就该更新

#### 8.5 验收（这一步必须**手动点**，Console 验不了交互）

先在**你自己终端**起好后端（8123）与前端（5173），登录后按顺序走：

1. 首页点「AI 问题引导解决助手」→ 进 `/guide` → **自动打开最近一条会话**，地址栏跳成 `/guide/<uuid>`，历史消息按对话顺序铺出来
2. 侧栏点**另一条**会话 → 消息换成那一条的，选中态（高亮边框）跟着走；地址栏换成新 uuid
3. 在历史会话里发一句新消息 → 回答正常流出；`psql` 查 `t_message` 能看到新的一轮 USER / ASSISTANT **落在同一个 `conversation_id`** 上
4. 点「+ 新对话」→ 回到欢迎语；`select count(*) from t_conversation` **不变**
5. 在这个空白会话里发第一条消息 → `POST /api/conversations` 出现（**201**），地址栏变成新 uuid，侧栏顶部多出这条新会话
6. **F5 刷新** → 仍停在同一条会话，历史完整
7. `title` 兜底：那 22 条空会话显示「（暂无内容）」而不是空白行
8. 浏览器**后退** → 回到上一条会话，消息也跟着换（验证 8.3 的 `watch`）

#### 练习 8 的边界

| 不做 | 留给 |
| --- | --- |
| 侧栏的删除 / 重命名 / 搜索 | 后端契约明确不做 |
| 消息分页（滚动加载更多） | MVP 外 |
| 切会话时让上一轮的流式请求"优雅收尾" | 现在只在换会话时直接断开（不报错、不落半截）——够用 |
| 未登录态的侧栏提示 | 路由守卫已把未登录挡在 `/login` |

### 练习 9（③格遗留收口）：登出 + 顶栏显示「我是谁」

**前置**：后端 `POST /api/auth/logout` 已就绪 → **204 无 body**（未登录调用也是 204，幂等）。契约见 [`impl-log.md`](./impl-log.md) 文末「③格遗留收口」。

#### 9.1 `stores/auth.js`：给 `setAuth` 配一个撤销出口（1 个文件）

- `isAuthenticated` 是 `ref`（**响应式变量**）：模板里用到它的地方**自动**跟着变，不需要手动通知任何组件。这就是练习 0.5 那个标记能直接驱动 `HomeView` 顶栏和路由守卫的原因。
- `setAuth(username)` 多收一个参数 → 存进 `currentUsername`（`ref` + `localStorage` 的 `ai-agent-username`）。**为什么不是 `userId`**：UUID 对人没有意义，且存下来容易让人误以为它是"第二份身份真相"——它只是**显示缓存**，丢了不影响鉴权。
- `clearAuth()` 要**同时**做两件事：删 `localStorage` 的两个 key + 把两个 `ref` 置回空/`false`。只改一半会得到"刷新前没登出、刷新后又变已登录"。

#### 9.2 `api/auth.js`：加 `logout`（1 个文件）

一行：`export const logout = () => request.post('/auth/logout')`。

注意 `request.js` 的响应拦截器写的是 `(response) => response.data`，而 **204 的 `data` 是空字符串** —— 所以 `await logout()` 的返回值没有内容可解构，别去读它。

#### 9.3 `HomeView.vue`：顶栏按登录态切换（1 个文件，4 个新概念）

1. **`v-if` / `v-else`**：`v-if` 为假时这块**根本不在 DOM 里**（不是隐藏）。对比 `v-show`——`v-show` 元素一直在 DOM 里，只切 `display`。判据：要"有没有这个元素"用 `v-if`；只是频繁开关显示、且元素本身较重（比如带图表的 div），用 `v-show`。
2. **`v-if` 与 `v-else` 必须相邻**：中间夹了别的元素就断链，`v-else` **静默失效**（不报错，但永远不渲染）。
3. **`<script setup>` 里 import 的 `ref` 在模板中直接用名字**：`import { isAuthenticated } from '../stores/auth.js'` 之后，模板写 `isAuthenticated` 就是它 —— **模板里不用 `.value`，`<script>` 里才要 `.value`**。这条最反直觉，记牢。
4. **`disabled` 绑 `loggingOut`**：防连点。`@click="handleLogout"` **不要加括号** —— 写成 `@click="handleLogout()"` 会变成"渲染时立刻调用一次"。

`handleLogout` 里最关键的一行是 `finally { clearAuth() }`：**请求失败也照样清标记**。理由见 impl-log「登出失败怎么办」—— 登出接口是幂等的 204，把用户卡在"已登录"界面（还看不到错误）是比"服务端 session 多活一会儿"更坏的结果。

#### 9.4 验收（必须手动点）

1. 未登录打开首页 → 顶栏是「登录」链接
2. 登录 → 自动回首页 → 顶栏显示**你输入的那个用户名** + 「登出」
3. **F5 刷新** → 仍是已登录态（证明 `localStorage` 生效）
4. 点「登出」→ Network 出现 `POST /api/auth/logout` 且状态码 **204**；顶栏立刻变回「登录」
5. **可证伪的关键一条**（③格预测外问题 1 的验收）：登出后在 Console 敲 `await fetch('/api/conversations', { credentials: 'include' })` → 状态码 **401**、body `{"message":"未登录"}`；再到 Network 点开这条请求的 **Response Headers** → **不应再有 `Set-Cookie: JSESSIONID`**。以前未登录请求也会被塞一个新 Cookie，现在不会了
6. 登出后 F5 → 仍是未登录态（`clearAuth()` 真的清了 `localStorage`，不是只改了内存里的 `ref`）

#### 练习 9 的边界

| 不做 | 留给 |
| --- | --- |
| 服务端 session 过期后前端自动弹回 `/login` | 要在 axios 拦截器里统一处理 401，属主干 |
| 记住上次登录的用户名做预填 | 产品没要求 |
| 顶层 `/manus` 页也显示登录态 | `/manus` 已于 2026-10-20 下线（入口并入 `/guide`），不再需要 |

#### 版本化修订（2026-10-20）——`/manus` 入口下线

- 主页原来两个入口（`/guide` 与 `/manus`）合并为一个：只留 `/guide`。删除 `ManusChatView.vue`、`router/index.js` 的 `/manus` 路由、`HomeView.vue` 的第二张卡与 `VS` 分隔符（`grid` 改单列）。
- **连带删掉 4 处「零引用」**：`utils/chatId.js`（只有 `ManusChatView` 用）、`api/sse.js`、`api/chat.js`，以及 `ChatRoom.vue` 里的 `event-source` 分支 + `ssePath` / `transport` 两个 prop。组件现在**只支持 `post-stream`** 一种传输。
- 为什么能一次清理干净：`/manus` 是唯一走原生 `EventSource`、且由前端自报 `chatId` 的入口；入口一撤，整条链路没有调用方。**注意顺序**：先删 `api/sse.js` 会让 `ChatRoom.vue` 直接构建失败——这是练习 3 那条「改完别急着删，先 `grep` 确认真没人用」的反向案例（先断引用、再删文件）。
- **练习 3 / 练习 6 里那句「不要删 `api/sse.js` 与 `utils/chatId.js`，ManusChatView 仍在用」自本日起过期**：原文保留作历史判断留档，现状以本节为准。
- 后端不动：`/api/ai/manus/chat`、`LiManus`、`agent/`、`tools/` 全部保留 —— `tools/`（`WebSearchTool` 等）是主干「资料接入第二层」的原料。

### 练习 10：首页视觉打磨（居中 / 卡片质感 / 响应式）

**只改一个文件**：[`HomeView.vue`](../ai-agent-frontend/src/views/HomeView.vue) 的 `<style scoped>`。目标三件事：内容双轴居中、卡片有质感、三档屏幕都不崩。

#### 10.1 双轴居中 + 溢出安全（最容易踩的坑）

居中本身很简单：父容器 `display: flex`，子元素 `margin: auto` 就同时吃下水平 + 垂直居中。**但为什么不用 `align-items: center / justify-content: center`**：

- 那样写，当内容高度超过容器（小屏、长文案）时，子元素会被顶到容器上边界之外，**顶部那截滚不到**——因为溢出发生在滚动原点的上方。这是 flex 居中 + 滚动的经典坑。
- `margin: auto` 不会：一旦空间不够，auto 外边距自动塌成 0，内容从顶部开始，老老实实可滚。

所以正确组合是：

```css
.grid { display: flex; overflow-y: auto; }   /* 滚动容器 */
.card { margin: auto; }                       /* 居中 + 溢出安全 */
```

另外滚动**不要**放在 `.home`（整个页面）上，要放在内容区 `.grid` 上——否则顶栏会跟着一起滚走。

#### 10.2 设计令牌：把视觉常量收进 CSS 变量

颜色 / 圆角 / 阴影 / 缓动曲线全部定义在 `.home` 的 `--xxx` 变量里，下面所有规则只引用变量名。三个好处：

1. 改一处、全页生效（比如主色从 `#0284c7` 换一个，只改 `--brand`）；
2. 阴影这种"一长串数值"不会散落各处，同一层次感保持统一；
3. 读代码时 `box-shadow: var(--shadow-soft)` 直接告诉你这是"常驻柔影"还是 `--shadow-lift`"悬停抬升影"，比一串 rgba 好懂。

阴影的层次感就靠**两层叠**：一层大而淡（扩散）、一层小而实（贴边），比单层生硬的黑影高级得多：

```css
--shadow-soft: 0 12px 34px rgba(12,46,70,.10), 0 2px 8px rgba(12,46,70,.06);
--shadow-lift: 0 30px 70px rgba(12,58,82,.22), 0 6px 18px rgba(12,58,82,.10);
```

**注意阴影别用纯黑**：`rgba(0,0,0,.2)` 压在浅蓝卡上会发灰发脏；用主色的深色调（这里是深蓝 `12,46,70`）阴影会跟着画面走。

#### 10.3 响应式的三档写法

- **流体尺寸用 `clamp()`**，别写死像素：`height: clamp(320px, 66vh, 560px)` = 矮屏不挤、高屏不空、超大屏不失控；标题 `clamp(24px, 3vw, 40px)` 同理。
- **断点只留两个**：`≤820px`（平板竖屏 / 窄窗）和 `≤480px`（手机）。断点越少越好维护，每个断点只改"真的需要变"的东西。
- 窄屏做两件实事：① `.tag`（"应用中心 · APPLICATION HUB"）`display: none`，给登录/登出按钮让位；② 卡片从固定高度改 `height: auto` + `min-height`，避免被压扁。
- 顶栏和卡片用**同一个** `max-width: 1080px` + `margin: 0 auto`，左右留白才会对齐——原来顶栏 `padding: 18px 30px`、内容区 `18px`，两边对不上，看起来就是"没居中"。

#### 10.4 顺手做的两件事

- `prefers-reduced-motion: reduce` 里关掉所有 transition/transform：系统开了"减少动态效果"的人不该被动画打扰。
- `.card:focus-visible` 加描边：这是键盘 Tab 到卡片时的可视焦点。**只能写 `:focus-visible`，不能写 `:focus`**——后者鼠标点一下也留描边，很难看。

#### 10.5 怎么验证（不靠"我觉得好看"）

1. `npm run build` 先过一遍，确认没写坏。
2. 浏览器 DevTools 打开**响应模式**，依次切 `390×844`（手机）→ `834×1112`（平板）→ `1440×900`（桌面），每档看：卡片左右留白是否相等、有没有被裁切、滚动条是否正常。
3. 悬停卡片，在 Elements 面板看 computed 的 `transform` / `box-shadow` 是否跟着变（比肉眼更准）。
4. 键盘 Tab 一遍，确认焦点描边出现。

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
