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
      title="AI 问题引导解决助手"
      ai-name="引导助手"
      user-name="我"
      ai-avatar="AI"
      user-avatar="我"
      stream-mode="token"
      :chat-id="chatId"
      :history-messages="historyMessages"
      :resolve-chat-id="ensureChatId"
      placeholder="描述你遇到的问题，我们一起拆解…"
      idle-text="在线，随时为你答疑"
      streaming-text="正在梳理思路，引导你一步步解决…"
      greeting="你好，我是你的问题引导解决助手"
      greeting-sub="无论是报错排查、代码问题还是概念学习，我们一起找到答案"
      :suggestions="suggestions"
      @done="onRoundDone"
    >
      <template #header-extra>
        <!-- 对话只答疑、不进待办；点这里把整轮对话沉淀成一份资料，再按资料出题 -->
        <button
          type="button"
          class="make-quiz"
          :disabled="makeBusy || !chatId"
          :title="chatId ? '把这段对话保存为资料并出 3 道题' : '先发一条消息，再把这轮做成题'"
          @click="makeQuiz"
        >
          {{ makeBusy ? '整理中…' : '把这轮做成题' }}
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

const suggestions = [
  '我的代码报错了，帮我理一下排查思路',
  '讲讲什么是线程池，我该从哪学起？',
  '这个报错我不认识，该去哪查官方文档？',
  '帮我判断这段配置哪里可能有问题'
]
</script>

<style scoped>
/* 设计令牌与资料页一致：深色底 + 青色强调，排版节奏参考苹果官网（发丝线分隔、层级靠字号） */
.guide-layout {
  --bg: #0e1116;
  --surface: #141b23;
  --ink: #eef4fa;
  --ink-soft: #8ba3b8;
  --ink-dim: #6b7f92;
  --line: rgba(148, 163, 184, 0.13);
  --brand-lit: #22d3ee;
  --radius-pill: 999px;
  --ease: cubic-bezier(0.22, 1, 0.36, 1);

  height: 100%;
  display: flex;
  background: var(--bg);
}

/* ===== 左侧栏：发丝线列表，选中项靠左侧亮条标识 ===== */
.sidebar {
  flex: none;
  width: 280px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  border-right: 1px solid var(--line);
  background: var(--bg);
}

.side-head {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 22px 18px 16px;
}
.side-brand {
  font-family: 'IBM Plex Mono', monospace;
  font-size: 11px;
  letter-spacing: 2px;
  color: var(--ink-dim);
}
.new-btn {
  padding: 7px 14px;
  border-radius: var(--radius-pill);
  border: 1px solid rgba(34, 211, 238, 0.35);
  color: var(--brand-lit);
  font-size: 12px;
  line-height: 1;
  transition: background 0.2s var(--ease);
}
.new-btn:hover {
  background: rgba(34, 211, 238, 0.12);
}

.side-state {
  margin: 0;
  padding: 28px 18px;
  font-size: 12px;
  line-height: 1.8;
  color: var(--ink-dim);
  text-align: center;
}
.side-state.error {
  color: #f87171;
}

.side-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  list-style: none;
  margin: 0;
  padding: 0 0 16px;
  display: flex;
  flex-direction: column;
}
.side-list::-webkit-scrollbar {
  width: 6px;
}
.side-list::-webkit-scrollbar-thumb {
  background: rgba(148, 163, 184, 0.18);
  border-radius: 3px;
}

.side-item {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 13px 18px;
  border-bottom: 1px solid var(--line);
  cursor: pointer;
  transition: background 0.2s var(--ease);
}
.side-item:hover {
  background: rgba(148, 163, 184, 0.04);
}
.side-item.active {
  background: rgba(34, 211, 238, 0.06);
}
/* 选中态：左缘一条亮线，不用描边卡片 */
.side-item.active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 12px;
  bottom: 12px;
  width: 2px;
  border-radius: var(--radius-pill);
  background: var(--brand-lit);
}

.side-title {
  font-size: 13px;
  line-height: 1.5;
  color: var(--ink-soft);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  /* 弹性的子元素要显式 min-width: 0，否则文字撑破容器、省略号不生效 */
  min-width: 0;
  transition: color 0.2s var(--ease);
}
.side-item:hover .side-title,
.side-item.active .side-title {
  color: var(--ink);
}
.side-time {
  font-family: 'IBM Plex Mono', monospace;
  font-size: 10.5px;
  color: var(--ink-dim);
}

/* 右侧聊天区占满剩余宽度 */
.room {
  flex: 1;
  min-width: 0;
}

/* 顶栏右侧的「把这轮做成题」：与 ChatRoom 的闲聊色系保持一致 */
.make-quiz {
  padding: 8px 16px;
  border-radius: var(--radius-pill);
  border: 1px solid rgba(34, 211, 238, 0.35);
  color: var(--brand-lit);
  font-size: 12px;
  line-height: 1;
  white-space: nowrap;
  transition: background 0.2s var(--ease), opacity 0.2s var(--ease);
}
.make-quiz:not(:disabled):hover {
  background: rgba(34, 211, 238, 0.12);
}
.make-quiz:disabled {
  opacity: 0.4;
  cursor: not-allowed;
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
  border: 1px solid rgba(248, 113, 113, 0.35);
  background: #1b1417;
  color: #f9a8a8;
  font-size: 12.5px;
}

.make-mask {
  position: fixed;
  inset: 0;
  z-index: 40;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgba(6, 10, 14, 0.72);
  backdrop-filter: blur(3px);
}
.make-card {
  width: 320px;
  padding: 26px;
  border-radius: 20px;
  border: 1px solid rgba(34, 211, 238, 0.18);
  background: var(--surface);
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
  font-family: 'IBM Plex Mono', monospace;
  font-size: 11px;
  color: var(--ink-dim);
}
.make-spinner {
  width: 26px;
  height: 26px;
  margin: 0 auto 16px;
  border-radius: 50%;
  border: 2px solid rgba(34, 211, 238, 0.22);
  border-top-color: var(--brand-lit);
  animation: make-spin 0.9s linear infinite;
}
@keyframes make-spin {
  to { transform: rotate(360deg); }
}

@media (max-width: 820px) {
  .sidebar {
    width: 200px;
  }
  .side-head {
    padding: 18px 14px 12px;
  }
  .side-item {
    padding: 12px 14px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .side-item,
  .side-title,
  .new-btn,
  .make-quiz,
  .make-spinner {
    transition: none;
    animation: none;
  }
}
</style>
