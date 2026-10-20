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
      ai-avatar="❓"
      user-avatar="🧑"
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
    />
  </div>
</template>

<script setup>
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ChatRoom from '../components/ChatRoom.vue'
import { createConversation, listConversations, listMessages } from '../api/conversation.js'

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
function toLoginIfUnauthorized(e) {
  if (e.response?.status === 401) {
    router.push('/login')
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
.guide-layout {
  height: 100%;
  display: flex;
  background: #0e1116;
}

/* ===== 左侧栏 ===== */
.sidebar {
  flex: none;
  width: 262px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  border-right: 1px solid rgba(34, 211, 238, 0.14);
  background: #0e1116;
}

.side-head {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 16px 14px 12px;
}
.side-brand {
  font-family: 'IBM Plex Mono', monospace;
  font-size: 13px;
  letter-spacing: 2px;
  color: #8ba3b8;
}
.new-btn {
  padding: 6px 12px;
  border-radius: 999px;
  border: 1px solid rgba(34, 211, 238, 0.35);
  color: #22d3ee;
  font-size: 12px;
  transition: background 0.2s ease;
}
.new-btn:hover {
  background: rgba(34, 211, 238, 0.12);
}

.side-state {
  margin: 0;
  padding: 24px 14px;
  font-size: 12px;
  color: #6b7f92;
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
  padding: 0 8px 16px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.side-list::-webkit-scrollbar {
  width: 6px;
}
.side-list::-webkit-scrollbar-thumb {
  background: rgba(34, 211, 238, 0.2);
  border-radius: 3px;
}

.side-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  border-radius: 10px;
  border: 1px solid transparent;
  cursor: pointer;
  transition: background 0.18s ease, border-color 0.18s ease;
}
.side-item:hover {
  background: #111b27;
  border-color: rgba(34, 211, 238, 0.12);
}
.side-item.active {
  background: #111b27;
  border-color: rgba(34, 211, 238, 0.45);
}

.side-title {
  font-size: 13px;
  color: #dbe6f0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  /* 弹性的子元素要显式 min-width: 0，否则文字撑破容器、省略号不生效 */
  min-width: 0;
}
.side-time {
  font-family: 'IBM Plex Mono', monospace;
  font-size: 11px;
  color: #6b7f92;
}

/* 右侧聊天区占满剩余宽度 */
.room {
  flex: 1;
  min-width: 0;
}

@media (max-width: 820px) {
  .sidebar {
    width: 190px;
  }
}
</style>
