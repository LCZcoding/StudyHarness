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
        <!-- :key 用后端签发的 conversationId（稳定且唯一） -->
        <li
          v-for="c in conversations"
          :key="c.conversationId"
          :class="['side-item', { active: c.conversationId === chatId }]"
          @click="openConversation(c.conversationId)"
        >
          <!-- title 由后端从首条 USER 消息派生；该会话还没有消息时为 null，必须兜底 -->
          <span class="side-title">{{ c.title || '（暂无内容）' }}</span>
          <time class="side-time">{{ formatTime(c.updatedAt) }}</time>
        </li>
      </ul>
    </aside>

    <ChatRoom
      class="room"
      theme="guide"
      title="授人以渔"
      ai-name="考官"
      user-name="我"
      user-avatar="我"
      stream-mode="token"
      :chat-id="chatId"
      :use-rag="useRag"
      :use-tools="useTools"
      :history-messages="historyMessages"
      :resolve-chat-id="ensureChatId"
      placeholder="说出你卡住的地方…"
      idle-text="在听，说说你卡在哪"
      streaming-text="正在挑你思路里的漏洞…"
      greeting="授人以鱼，不如授人以渔"
      greeting-sub="这里不直接给答案。你把思路讲出来，讲不清的地方回原文重看。"
      :suggestions="suggestions"
      @done="onRoundDone"
    >
      <template #composer-extra>
        <!-- 本轮开关：关 = 纯模型回答；开 = 检索本人资料 / 允许联网搜索与抓取。
             查时间不受开关控制，任何一轮都可用。收进输入框右侧、只留图标，说明靠悬停浮出 -->
        <button
          type="button"
          class="tool-btn"
          :class="{ on: useRag }"
          :aria-pressed="useRag"
          aria-label="资料检索"
          :data-tip="useRag ? '资料检索 · 已开启，按你上传的资料回答' : '资料检索 · 已关闭，只用模型自身知识'"
          @click="useRag = !useRag"
        >
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor"
               stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M12 7v14" />
            <path d="M3 18a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h5a4 4 0 0 1 4 4 4 4 0 0 1 4-4h5a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1h-6a3 3 0 0 0-3 3 3 3 0 0 0-3-3z" />
          </svg>
        </button>
        <button
          type="button"
          class="tool-btn"
          :class="{ on: useTools }"
          :aria-pressed="useTools"
          aria-label="联网"
          :data-tip="useTools ? '联网 · 已开启，可搜索与抓取网页；查时间始终可用' : '联网 · 已关闭，不访问外部网页；查时间始终可用'"
          @click="useTools = !useTools"
        >
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor"
               stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <circle cx="11" cy="11" r="6.5" />
            <path d="M20 20l-4.2-4.2" />
          </svg>
        </button>
        <!-- 对话只答疑、不进待办；点这里把整轮对话沉淀成一份资料，再按资料出题 -->
        <button
          type="button"
          class="tool-btn"
          :disabled="makeBusy || !chatId"
          aria-label="把这轮做成题"
          :data-tip="makeBusy ? '正在整理…' : (chatId ? '把这轮对话做成 3 道题' : '先发一条消息，再把这轮做成题')"
          @click="makeQuiz"
        >
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor"
               stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <rect x="8" y="3" width="8" height="3.5" rx="1.5" />
            <path d="M16 5.5h1.5A1.5 1.5 0 0 1 19 7v12.5a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 19.5V7a1.5 1.5 0 0 1 1.5-1.5H8" />
            <path d="M9 13l2 2 3.5-3.5" />
          </svg>
        </button>
      </template>
    </ChatRoom>

    <p v-if="makeError" class="make-error">{{ makeError }}</p>

    <!-- 整理 + 出题约 5 秒（建资料 1 次 + 出题 1 次模型调用），给一个明确的进行中态 -->
    <div v-if="makeBusy" class="make-mask">
      <div class="make-card">
        <div class="make-spinner"></div>
        <p>正在把这段对话整理成题…</p>
        <span>保存为资料 → 出 3 道题，约 5 秒</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ChatRoom from '../components/ChatRoom.vue'
import { createConversation, listConversations, listMessages } from '../api/conversation.js'
import { createItem } from '../api/knowledge.js'
import { generate as generateQuiz } from '../api/quiz.js'
import { messageOf } from '../utils/apiError.js'

const route = useRoute()
const router = useRouter()

// 当前会话 id。空字符串 = 还没有会话（空白新对话，等第一条消息发出时才创建）
const chatId = ref('')
// 本轮检索 / 工具开关：默认都关，由用户显式打开，服务端不做「猜」的智能判断
const useRag = ref(false)
const useTools = ref(false)
// 当前会话的历史消息（喂给 ChatRoom 渲染）
const historyMessages = ref([])
// 左侧栏的会话列表
const conversations = ref([])
const listLoading = ref(true)
const listError = ref('')

// 401 统一处理：会话相关接口都要登录，被踢回登录页
// 带上 redirect：否则服务端 session 过期时，用户登录完会掉回首页，得自己再点一次进来
function toLoginIfUnauthorized(e) {
  if (e.response?.status === 401) {
    router.push({ name: 'login', query: { redirect: route.fullPath } })
    return true
  }
  return false
}

async function loadConversations() {
  try {
    const data = await listConversations()
    conversations.value = data.conversations
  } catch (e) {
    if (toLoginIfUnauthorized(e)) return
    listError.value = e.response?.data?.message || '加载失败，请稍后重试'
  } finally {
    // 成功、失败都要关掉「加载中…」；写进 try 的话，请求失败会永远卡在加载态
    listLoading.value = false
  }
}

async function loadMessages(id) {
  try {
    const data = await listMessages(id)
    historyMessages.value = data.messages
  } catch (e) {
    if (toLoginIfUnauthorized(e)) return
    historyMessages.value = []
  }
}

// 侧栏点某一条 → 换会话（先把历史清空，避免旧消息闪一下）
async function openConversation(id) {
  if (id === chatId.value) return
  chatId.value = id
  historyMessages.value = []
  await loadMessages(id)
  // 把会话 id 写进地址栏，刷新后还留在同一会话
  router.replace({ name: 'guide-conversation', params: { conversationId: id } })
}

// 「+ 新对话」：不建会话，只切到空白态；等第一条消息发出时才真正创建
function newConversation() {
  chatId.value = ''
  historyMessages.value = []
  router.replace({ name: 'guide' })
}

// 懒创建钩子（ChatRoom 在 post-stream 且 chatId 为空时调用）
// 返回 Promise<conversationId>，并把新 id 同步进地址栏与本地 state
async function ensureChatId() {
  if (chatId.value) return chatId.value
  try {
    const data = await createConversation('PRACTICE')
    chatId.value = data.conversationId
    await loadConversations()
    router.replace({ name: 'guide-conversation', params: { conversationId: chatId.value } })
    return chatId.value
  } catch (e) {
    // 401 说明登录已失效：转去登录页，并把原因回给 ChatRoom 显示在气泡里
    if (toLoginIfUnauthorized(e)) throw new Error('登录已失效，请重新登录')
    throw e
  }
}

// 一轮对话结束 → 刷新侧栏（新会话出现 / 标题更新 / 排序更新）
function onRoundDone() {
  loadConversations()
}

/* ---------- 把这轮对话做成题 ---------- */
const makeBusy = ref(false)
const makeError = ref('')

// 聊天本身不进待办（待办三条来源都必须先有 t_question）。
// 这里把整轮对话存成一份资料，再按资料出题，对话才真正进入「未测 → 判分 → 复习」闭环。
async function makeQuiz() {
  if (!chatId.value || makeBusy.value) return
  makeBusy.value = true
  makeError.value = ''
  try {
    const data = await listMessages(chatId.value)
    const msgs = data.messages || []
    if (!msgs.length) {
      makeError.value = '这段对话还没有内容，先聊一轮再试'
      return
    }
    // 标题沿用后端派生规则的口径：取首条 USER 消息前 50 字
    const firstUser = msgs.find((m) => m.role === 'USER')
    const title = (firstUser?.content || '对话整理').slice(0, 50)
    let content = msgs
      .map((m) => (m.role === 'USER' ? '我：' : '助手：') + m.content)
      .join('\n\n')
    // 后端正文上限 20000 字：超长时保留最近的部分，结论通常在后半段
    if (content.length > 20000) content = content.slice(-20000)

    const item = await createItem(title, content)
    const quiz = await generateQuiz(item.knowledgeItemId)
    router.push({ name: 'quiz', params: { conversationId: quiz.conversationId } })
  } catch (e) {
    if (toLoginIfUnauthorized(e)) return
    makeError.value = messageOf(e, '整理失败，请稍后重试')
  } finally {
    makeBusy.value = false
  }
}

// 浏览器前进/后退切到另一个 /guide/:id 时，组件不会重新挂载，靠监听路由参数换会话
watch(
  () => route.params.conversationId,
  (id) => {
    if (id && id !== chatId.value) {
      chatId.value = id
      historyMessages.value = []
      loadMessages(id)
    }
  }
)

onMounted(async () => {
  const idFromRoute = route.params.conversationId
  // 1）URL 带会话 id（含刷新）：直接开它
  if (idFromRoute) {
    chatId.value = idFromRoute
    await Promise.all([loadConversations(), loadMessages(idFromRoute)])
    return
  }
  // 2）URL 不带 id：默认打开「最近一条会话」；一条都没有则停在空白态（懒创建）
  await loadConversations()
  const latest = conversations.value[0]
  if (latest) {
    chatId.value = latest.conversationId
    await loadMessages(latest.conversationId)
    router.replace({ name: 'guide-conversation', params: { conversationId: latest.conversationId } })
  }
})

// 后端给的是带偏移的 ISO-8601（如 2026-10-19T14:44:58.518468Z）：
// new Date() 按这个偏移定位到绝对时刻，再按浏览器本地时区显示
function formatTime(iso) {
  return new Date(iso).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}

// 引导语刻意写成"我先讲、你来挑"，而不是"帮我做"——第一句话就定下这间屋子里的分工
const suggestions = [
  '线程池核心数怎么定？我先说我的理解，你看对吗',
  '这段报错我猜是空指针，你帮我验证这个判断',
  '我用自己的话讲一遍 AQS，哪里讲错了直接说',
  '这个配置我改了但没生效，帮我列排查顺序'
]
</script>

<style scoped>
/* 浅色令牌：与 ChatGPT 网页版一致（252 底、白面、0.06~0.16 的黑透边框） */
.guide-layout {
  --bg: #fcfcfc;
  --surface: #ffffff;
  --ink: #0d0d0d;
  --ink-soft: #565869;
  --ink-dim: #8e8ea0;
  --line: rgba(0, 0, 0, 0.07);
  --brand-lit: #0d0d0d;
  --accent: #4d6bfe;
  --radius-pill: 999px;
  --ease: cubic-bezier(0.22, 1, 0.36, 1);

  height: 100%;
  display: flex;
  background: var(--bg);
}

/* ===== 左侧栏：透明底、无描边，条目是圆角胶囊（参考 ChatGPT 会话列表） ===== */
.sidebar {
  flex: none;
  width: 260px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  border-right: 1px solid var(--line);
  background: #fcfcfc;
}

.side-head {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 16px 14px 10px;
}
.side-brand {
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.2px;
  color: var(--ink-dim);
}
.new-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 7px 12px;
  border-radius: var(--radius-pill);
  border: 1px solid var(--line);
  color: var(--ink);
  font-size: 12.5px;
  line-height: 1;
  background: #ffffff;
  transition: background 0.16s ease, border-color 0.16s ease;
}
.new-btn:hover {
  background: #f0f0f2;
  border-color: rgba(0, 0, 0, 0.16);
}

.side-state {
  margin: 0;
  padding: 24px 14px;
  font-size: 12.5px;
  line-height: 1.8;
  color: var(--ink-dim);
  text-align: center;
}
.side-state.error {
  color: #c2410c;
}

.side-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  list-style: none;
  margin: 0;
  padding: 2px 8px 16px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.side-list::-webkit-scrollbar {
  width: 8px;
}
.side-list::-webkit-scrollbar-thumb {
  background: rgba(0, 0, 0, 0.1);
  border-radius: 4px;
}

/* 会话条目：无分隔线，靠圆角 + hover 底色区分（ChatGPT 的做法） */
.side-item {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 9px 10px;
  border-radius: 10px;
  cursor: pointer;
  transition: background 0.14s ease;
}
.side-item:hover {
  background: #f0f0f2;
}
.side-item.active {
  background: #ececef;
}

.side-title {
  font-size: 13.5px;
  line-height: 1.45;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  /* 弹性的子元素要显式 min-width: 0，否则文字撑破容器、省略号不生效 */
  min-width: 0;
}
.side-time {
  font-size: 11px;
  color: var(--ink-dim);
}

/* 右侧聊天区占满剩余宽度 */
.room {
  flex: 1;
  min-width: 0;
}

/* 输入框右侧的操作图标：默认灰、悬停深底，开启态黑底反白；
   功能说明放 ::after，用 data-tip 的内容，悬停或键盘聚焦时浮出 */
.tool-btn {
  position: relative;
  flex: none;
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border-radius: var(--radius-pill);
  color: var(--ink-soft);
  background: transparent;
  transition: background 0.16s ease, color 0.16s ease;
}
.tool-btn svg {
  display: block;
}
.tool-btn:hover:not(:disabled) {
  background: #f0f0f2;
  color: var(--ink);
}
.tool-btn.on {
  background: #0d0d0d;
  color: #ffffff;
}
.tool-btn.on:hover:not(:disabled) {
  background: #2f2f2f;
}
.tool-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.tool-btn::after {
  content: attr(data-tip);
  position: absolute;
  right: 0;
  bottom: calc(100% + 10px);
  z-index: 6;
  width: max-content;
  max-width: 216px;
  padding: 6px 10px;
  border-radius: 8px;
  background: #0d0d0d;
  color: #ffffff;
  font-size: 11.5px;
  line-height: 1.6;
  text-align: center;
  white-space: normal;
  opacity: 0;
  transform: translateY(4px);
  transition: opacity 0.16s ease, transform 0.16s ease;
  pointer-events: none;
}
.tool-btn:hover::after,
.tool-btn:focus-visible::after {
  opacity: 1;
  transform: translateY(0);
}

.make-error {
  position: fixed;
  top: 74px;
  right: 24px;
  z-index: 30;
  max-width: 360px;
  margin: 0;
  padding: 11px 15px;
  border-radius: 12px;
  border: 1px solid rgba(0, 0, 0, 0.1);
  background: #ffffff;
  color: #b4341a;
  font-size: 12.5px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.08);
}

.make-mask {
  position: fixed;
  inset: 0;
  z-index: 40;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgba(0, 0, 0, 0.35);
  backdrop-filter: blur(2px);
}
.make-card {
  width: 320px;
  padding: 26px;
  border-radius: 18px;
  border: 1px solid rgba(0, 0, 0, 0.08);
  background: #ffffff;
  box-shadow: 0 20px 48px rgba(0, 0, 0, 0.14);
  text-align: center;
}
.make-card p {
  margin: 0;
  font-size: 13.5px;
  color: var(--ink);
}
.make-card span {
  display: block;
  margin-top: 8px;
  font-size: 11.5px;
  color: var(--ink-dim);
}
.make-spinner {
  width: 24px;
  height: 24px;
  margin: 0 auto 16px;
  border-radius: 50%;
  border: 2px solid rgba(0, 0, 0, 0.1);
  border-top-color: #0d0d0d;
  animation: make-spin 0.9s linear infinite;
}
@keyframes make-spin {
  to { transform: rotate(360deg); }
}

@media (max-width: 820px) {
  .sidebar {
    width: 216px;
  }
  .side-head {
    padding: 14px 12px 8px;
  }
  .side-item {
    padding: 9px 10px;
  }
}
/* 窄屏放不下「侧栏 + 聊天区」两列：会话列表改成顶部横向条，聊天区占满剩余高度 */
@media (max-width: 640px) {
  .guide-layout {
    flex-direction: column;
  }
  .sidebar {
    width: 100%;
    max-height: 150px;
    border-right: none;
    border-bottom: 1px solid var(--line);
  }
  .side-head {
    padding: 10px 14px 8px;
  }
  .side-list {
    flex-direction: row;
    overflow-x: auto;
    overflow-y: hidden;
    padding: 0 14px 12px;
    gap: 8px;
  }
  .side-item {
    flex: none;
    min-width: 180px;
    max-width: 220px;
    padding: 9px 11px;
    border: 1px solid var(--line);
    border-radius: 10px;
  }
  .side-item.active {
    border-color: rgba(0, 0, 0, 0.18);
  }
  .tool-btn {
    width: 32px;
    height: 32px;
  }
  .room {
    min-height: 0;
  }
}
@media (prefers-reduced-motion: reduce) {
  .side-item,
  .side-title,
  .new-btn,
  .tool-btn,
  .make-spinner {
    transition: none;
    animation: none;
  }
}
</style>
