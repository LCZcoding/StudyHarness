<template>
  <section :class="['chat-room', `theme-${theme}`]">
    <!-- 顶部：会话信息 -->
    <header class="chat-header">
      <div class="h-left">
        <RouterLink to="/" class="back" title="返回应用中心">←</RouterLink>
        <div class="avatar">{{ aiAvatar }}</div>
        <div class="title-box">
          <h1>{{ title }}</h1>
          <p class="status">
            <i :class="['dot', { on: streaming }]"></i>
            {{ streaming ? streamingText : idleText }}
          </p>
        </div>
      </div>
      <div v-if="chatId" class="room-id" :title="chatId">
        <span class="rid-label">CHAT ID</span>
        <span class="rid-value">{{ shortChatId }}</span>
      </div>
    </header>

    <!-- 中部：聊天记录（用户在右，AI 在左） -->
    <div ref="listEl" class="messages">
      <!-- 空会话：欢迎语 + 建议提问 -->
      <div v-if="messages.length === 0" class="empty">
        <div class="empty-avatar">{{ aiAvatar }}</div>
        <h2>{{ greeting }}</h2>
        <p>{{ greetingSub }}</p>
        <div class="suggestions">
          <button v-for="s in suggestions" :key="s" type="button" @click="send(s)">
            {{ s }}
          </button>
        </div>
      </div>

      <template v-else>
        <div v-for="m in messages" :key="m.id" :class="['msg-row', m.role]">
          <div v-if="m.role === 'ai'" class="avatar sm">{{ aiAvatar }}</div>
          <div class="col">
            <span class="name">{{ m.role === 'user' ? userName : aiName }} · {{ m.time }}</span>
            <div :class="['bubble', m.role]">
              <template v-if="m.role === 'ai' && m.content">
                <template v-for="(seg, idx) in parseAiContent(m.content)" :key="idx">
                  <div v-if="seg.type === 'step'" class="step-seg">
                    <span class="step-badge">Step {{ seg.num }}</span>
                    <span class="step-text">{{ seg.text }}</span>
                  </div>
                  <div v-else class="text-seg">{{ seg.text }}</div>
                </template>
              </template>
              <template v-else-if="m.content">{{ m.content }}</template>
              <span v-else-if="streaming && m.role === 'ai'" class="caret"></span>
            </div>
          </div>
          <div v-if="m.role === 'user'" class="avatar sm user-av">{{ userAvatar }}</div>
        </div>
      </template>
    </div>

    <!-- 底部：输入区 -->
    <footer class="composer">
      <div class="composer-box">
        <textarea
          ref="inputEl"
          v-model="input"
          rows="1"
          :placeholder="placeholder"
          :disabled="streaming"
          @keydown="onKeydown"
          @input="autoResize"
        ></textarea>
        <div class="actions">
          <button v-if="streaming" type="button" class="stop" @click="stop">■ 停止</button>
          <button
            type="button"
            class="send"
            :disabled="streaming || !input.trim()"
            @click="send()"
          >
            发送 ↑
          </button>
        </div>
      </div>
      <p class="tip">Enter 发送 · Shift + Enter 换行</p>
    </footer>
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { createSseChat } from '../api/sse.js'
import { streamChat } from '../api/chatStream.js'

const props = defineProps({
  // 主题：guide（清新蓝青·答疑辅导）/ manus（科技深色）
  theme: { type: String, default: 'guide' },
  title: { type: String, default: '' },
  aiName: { type: String, default: 'AI' },
  userName: { type: String, default: '我' },
  aiAvatar: { type: String, default: '🤖' },
  userAvatar: { type: String, default: '🧑' },
  // SSE 接口路径（不含 /api 前缀）——仅 event-source 模式需要
  ssePath: { type: String, default: '' },
  // 流式模式：token = 逐字追加；step = 按步骤换行追加
  streamMode: { type: String, default: 'token' },
  // 聊天室 id（问题引导助手需要，Manus 接口不需要则不传）

  // 传输方式：event-source = 原生 EventSource（GET、无 body）；post-stream = fetch POST 读流
  transport: { type: String, default: 'event-source' },

  chatId: { type: String, default: '' },
  // 可选：预置的历史消息，形如 [{ messageId, role: 'USER'|'ASSISTANT', content, createdAt }]
  // 不传 = 空会话（ManusChatView 不传，行为不变）
  historyMessages: { type: Array, default: () => [] },
  // 可选：懒创建会话的钩子，返回 Promise<conversationId>。
  // 只在 transport === 'post-stream' 且 chatId 为空（还没建会话）时用：
  // 用户在空白会话里发出第一条消息时，才真正去 POST /conversations。
  resolveChatId: { type: Function, default: null },
  placeholder: { type: String, default: '输入你想说的话…' },
  idleText: { type: String, default: '在线' },
  streamingText: { type: String, default: '正在输入…' },
  greeting: { type: String, default: '你好，我是你的 AI 助手' },
  greetingSub: { type: String, default: '' },
  suggestions: { type: Array, default: () => [] }
})

// 一轮对话结束（正常完成 / 出错 / 被停止）时通知父组件，用于刷新侧栏列表
const emit = defineEmits(['done'])

const listEl = ref(null)
const inputEl = ref(null)
const input = ref('')
const streaming = ref(false)
const messages = ref([])

let source = null
let controller = null
let msgSeq = 0

const shortChatId = computed(() => props.chatId.replace(/-/g, '').slice(0, 12))

function nowText() {
  return new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

function addMessage(role, content = '') {
  const msg = { id: ++msgSeq, role, content, time: nowText() }
  messages.value.push(msg)
  return messages.value[messages.value.length - 1]
}

async function send(picked) {
  if (streaming.value) return
  const text = (picked !== undefined ? picked : input.value).trim()
  if (!text) return

  addMessage('user', text)
  input.value = ''
  nextTick(autoResize)

  const aiMsg = addMessage('ai', '')
  streaming.value = true
  // 先清掉上一轮的连接/请求（与具体传输方式无关）
  closeSource()

  let firstChunk = true

  const onMessage = (chunk) => {
    if (props.streamMode === 'step') {
      aiMsg.content += firstChunk ? chunk : `\n${chunk}`
    } else {
      aiMsg.content += chunk
    }
    firstChunk = false
  }

  const onDone = () => finish()

  const onError = (err) => {
    aiMsg.content = firstChunk
      ? `（${err.message}）`
      : `${aiMsg.content}\n（连接中断，以上为已接收内容）`
    finish()
  }

  if (props.transport === 'post-stream') {
    // 懒创建：空白会话的第一条消息发出前，才去后端建会话拿 id
    let cid = props.chatId
    if (!cid && props.resolveChatId) {
      try {
        cid = await props.resolveChatId()
      } catch (err) {
        // 建会话失败（如 401）：把原因显示在 AI 气泡里，别静默
        // axios 的 err.message 是"Request failed with status code 400"这种技术文案，
        // 后端契约文案在 err.response.data.message 里 —— 优先用后者
        aiMsg.content = `（${err.response?.data?.message || err.message}）`
        finish()
        return
      }
      // 等待期间用户可能点了「停止」→ 已经 finish，别再发起流
      if (!streaming.value) return
    }
    // fetch 流的取消靠 AbortController，必须先造好、把 signal 交给 streamChat
    controller = new AbortController()
    streamChat({
      conversationId: cid,
      message: text,
      signal: controller.signal,
      onMessage,
      onDone,
      onError
    })
  } else {
    const params = { message: text }
    if (props.chatId) params.chatId = props.chatId
    source = createSseChat({
      path: props.ssePath,
      params,
      onMessage,
      onDone,
      onError
    })
  }
}

function stop() {
  closeSource()
  const last = messages.value[messages.value.length - 1]
  if (last && last.role === 'ai' && !last.content) {
    last.content = '（已停止生成）'
  }
  finish()
}

function finish() {
  streaming.value = false
  source = null
  emit('done')
  nextTick(() => inputEl.value?.focus())
}

function closeSource() {
  if (source) {
    source.close()
    source = null
  }
  if (controller) {
    controller.abort()
    controller = null
  }
}

// function closeSource() {
//   if (source) {
//     source.close()
//     source = null
//   }
// }

function onKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    send()
  }
}

function autoResize() {
  const el = inputEl.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, 120)}px`
}

/**
 * 解析 AI 气泡内容：把 "Step N: ..." 拆成独立步骤段，
 * 其余文本（最终回答）作为普通段落，便于视觉上明显区分。
 * 纯文本回答（无 Step 标记）会被当作一个普通段落。
 */
function parseAiContent(content) {
  if (!content) return []
  const lines = content.split('\n')
  const segs = []
  let cur = null
  for (const line of lines) {
    const m = line.match(/^Step\s+(\d+):\s?(.*)$/)
    if (m) {
      cur = { type: 'step', num: m[1], text: m[2] }
      segs.push(cur)
    } else {
      if (cur && cur.type === 'step') {
        cur.text += `\n${line}`
      } else {
        if (!cur || cur.type !== 'text') {
          cur = { type: 'text', text: '' }
          segs.push(cur)
        }
        cur.text += cur.text ? `\n${line}` : line
      }
    }
  }
  return segs
}

// 历史消息 → 界面消息：后端 role 是 USER/ASSISTANT，界面要 user/ai
function formatHistoryTime(iso) {
  return new Date(iso).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

// 父组件换会话 → 整个 messages 换掉（不是追加）
// immediate: true 让「带着历史消息进来」在首次渲染前就位
watch(
  () => props.historyMessages,
  (list) => {
    // 切会话时若上一轮还在流式，先断开，避免残流写进已经换掉的界面
    if (streaming.value) {
      closeSource()
      finish()
    }
    const items = list || []
    // 让新的本地消息 id 从「历史里最大 messageId」往后排，避免与历史行的 :key 撞号
    msgSeq = items.reduce((max, m) => Math.max(max, Number(m.messageId) || 0), 0)
    messages.value = items.map((m) => ({
      id: m.messageId,
      role: m.role === 'USER' ? 'user' : 'ai',
      content: m.content,
      time: formatHistoryTime(m.createdAt)
    }))
  },
  { immediate: true }
)

// 内容变化时自动滚动到底部
watch(
  () => messages.value.map((m) => m.content).join(''),
  async () => {
    await nextTick()
    const el = listEl.value
    if (el) el.scrollTop = el.scrollHeight
  }
)

onBeforeUnmount(closeSource)
</script>

<style scoped>
.chat-room {
  height: 100%;
  display: flex;
  flex-direction: column;
  --text: #1f3442;
  --muted: #7b93a5;
  --accent: #0ea5e9;
  --panel: rgba(246, 251, 255, 0.82);
  --ai-bubble: #ffffff;
  --user-bubble: linear-gradient(135deg, #2f80ed, #12b5c9);
  --border: rgba(15, 23, 42, 0.1);
}

/* ===== 问题引导解决助手：清新蓝青 · 答疑辅导风 ===== */
.theme-guide {
  --text: #17384d;
  --muted: #6c8ba3;
  --accent: #0ea5e9;
  --panel: rgba(238, 248, 255, 0.82);
  --ai-bubble: #ffffff;
  --user-bubble: linear-gradient(135deg, #2f80ed, #12b5c9);
  --border: rgba(14, 165, 233, 0.18);
  background:
    radial-gradient(1000px 520px at 88% -8%, rgba(14, 165, 233, 0.18), transparent 60%),
    radial-gradient(880px 480px at -10% 108%, rgba(45, 212, 191, 0.2), transparent 60%),
    linear-gradient(180deg, #f4fbff 0%, #ecf8f8 100%);
}

/* ===== 超级智能体：深色 + 电光青 ===== */
.theme-manus {
  --text: #d8e4ee;
  --muted: #6b7f92;
  --accent: #22d3ee;
  --panel: rgba(13, 20, 29, 0.82);
  --ai-bubble: #111b27;
  --user-bubble: linear-gradient(135deg, #0891b2, #2563eb);
  --border: rgba(34, 211, 238, 0.2);
  background:
    linear-gradient(rgba(34, 211, 238, 0.05) 1px, transparent 1px),
    linear-gradient(90deg, rgba(34, 211, 238, 0.05) 1px, transparent 1px),
    radial-gradient(1000px 500px at 80% -10%, rgba(34, 211, 238, 0.12), transparent 60%),
    #0b0f14;
  background-size: 36px 36px, 36px 36px, 100% 100%, 100% 100%;
}

/* ===== 顶栏 ===== */
.chat-header {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 22px;
  background: var(--panel);
  backdrop-filter: blur(14px);
  border-bottom: 1px solid var(--border);
}

.h-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.back {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  border: 1px solid var(--border);
  color: var(--text);
  font-size: 16px;
  transition: transform 0.2s ease, background 0.2s ease;
}
.back:hover {
  transform: translateX(-3px);
  background: var(--border);
}

.avatar {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 24px;
  background: var(--ai-bubble);
  border: 1px solid var(--border);
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.08);
}
.avatar.sm {
  width: 36px;
  height: 36px;
  font-size: 19px;
  flex: none;
}

.title-box h1 {
  margin: 0;
  font-size: 17px;
  font-weight: 700;
  color: var(--text);
}
.theme-manus .title-box h1 {
  letter-spacing: 1px;
}

.status {
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--muted);
  display: flex;
  align-items: center;
  gap: 6px;
}
.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--muted);
}
.dot.on {
  background: var(--accent);
  box-shadow: 0 0 0 0 var(--accent);
  animation: pulse 1.2s infinite;
}
@keyframes pulse {
  0% { box-shadow: 0 0 0 0 rgba(14, 165, 233, 0.35); }
  70% { box-shadow: 0 0 0 7px rgba(14, 165, 233, 0); }
  100% { box-shadow: 0 0 0 0 rgba(14, 165, 233, 0); }
}
.theme-manus .dot.on {
  animation: pulse-cyan 1.2s infinite;
}
@keyframes pulse-cyan {
  0% { box-shadow: 0 0 0 0 rgba(34, 211, 238, 0.35); }
  70% { box-shadow: 0 0 0 7px rgba(34, 211, 238, 0); }
  100% { box-shadow: 0 0 0 0 rgba(34, 211, 238, 0); }
}

.room-id {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  padding: 6px 12px;
  border-radius: 10px;
  border: 1px dashed var(--border);
}
.rid-label {
  font-size: 10px;
  letter-spacing: 2px;
  color: var(--muted);
}
.rid-value {
  font-family: 'IBM Plex Mono', monospace;
  font-size: 12px;
  color: var(--text);
}

/* ===== 消息区 ===== */
.messages {
  flex: 1;
  overflow-y: auto;
  padding: 30px 6vw 10px;
  scroll-behavior: smooth;
}
.messages::-webkit-scrollbar {
  width: 6px;
}
.messages::-webkit-scrollbar-thumb {
  background: var(--border);
  border-radius: 3px;
}

.empty {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  gap: 10px;
  animation: rise 0.5s ease both;
}
@keyframes rise {
  from { opacity: 0; transform: translateY(14px); }
  to { opacity: 1; transform: translateY(0); }
}
.empty-avatar {
  width: 76px;
  height: 76px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 40px;
  background: var(--ai-bubble);
  border: 1px solid var(--border);
  box-shadow: 0 14px 40px rgba(0, 0, 0, 0.1);
  margin-bottom: 6px;
}
.empty h2 {
  margin: 0;
  font-size: 22px;
  color: var(--text);
}
.empty p {
  margin: 0 0 10px;
  color: var(--muted);
  font-size: 13px;
}

.suggestions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  justify-content: center;
  max-width: 560px;
}
.suggestions button {
  padding: 9px 16px;
  font-size: 13px;
  color: var(--text);
  border-radius: 999px;
  border: 1px solid var(--border);
  background: var(--ai-bubble);
  transition: transform 0.18s ease, border-color 0.18s ease;
}
.suggestions button:hover {
  transform: translateY(-2px);
  border-color: var(--accent);
}

/* 消息行 */
.msg-row {
  display: flex;
  gap: 10px;
  max-width: 1040px;
  margin: 0 auto 22px;
  animation: msgIn 0.28s ease both;
}
@keyframes msgIn {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}
.msg-row.user {
  flex-direction: row-reverse;
}
.col {
  display: flex;
  flex-direction: column;
  max-width: min(86%, 820px);
}
.msg-row.user .col {
  align-items: flex-end;
}
.name {
  font-size: 11px;
  color: var(--muted);
  margin: 0 4px 5px;
}

.bubble {
  padding: 12px 16px;
  border-radius: 16px;
  font-size: 14.5px;
  line-height: 1.75;
  white-space: pre-wrap;
  word-break: break-word;
}
.bubble.ai {
  background: var(--ai-bubble);
  color: var(--text);
  border: 1px solid var(--border);
  border-top-left-radius: 5px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.06);
}
.bubble.user {
  background: var(--user-bubble);
  color: #fff;
  border-top-right-radius: 5px;
  box-shadow: 0 10px 26px rgba(47, 128, 237, 0.24);
}
.theme-manus .bubble.user {
  box-shadow: 0 10px 26px rgba(8, 145, 178, 0.3);
}
.theme-manus .bubble.ai {
  font-family: 'IBM Plex Mono', 'Noto Sans SC', monospace;
  font-size: 13.5px;
}

/* AI 气泡内的步骤段 / 最终文本段 */
.step-seg {
  display: flex;
  gap: 10px;
  padding: 10px 12px;
  margin-bottom: 10px;
  border-radius: 10px;
  border-left: 3px solid var(--accent);
  background: color-mix(in srgb, var(--accent) 8%, transparent);
}
.step-seg:last-child {
  margin-bottom: 0;
}
.step-badge {
  flex: none;
  align-self: flex-start;
  padding: 2px 8px;
  font-size: 11px;
  font-weight: 600;
  font-family: 'IBM Plex Mono', monospace;
  color: #fff;
  background: var(--accent);
  border-radius: 6px;
  white-space: nowrap;
}
.step-text {
  flex: 1;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13.5px;
  line-height: 1.7;
}
.text-seg {
  white-space: pre-wrap;
  word-break: break-word;
  margin-bottom: 10px;
}
.text-seg:last-child {
  margin-bottom: 0;
}
/* 最终回答段（紧随步骤之后）视觉上突出 */
.step-seg + .text-seg,
.text-seg:first-child {
  margin-top: 4px;
}

.caret {
  display: inline-block;
  width: 8px;
  height: 17px;
  vertical-align: text-bottom;
  background: var(--accent);
  animation: blink 0.9s steps(1) infinite;
}
@keyframes blink {
  50% { opacity: 0; }
}

/* ===== 输入区 ===== */
.composer {
  flex: none;
  padding: 12px 22px 16px;
  background: var(--panel);
  backdrop-filter: blur(14px);
  border-top: 1px solid var(--border);
}
.composer-box {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  padding: 10px 14px;
  border-radius: 18px;
  background: var(--ai-bubble);
  border: 1px solid var(--border);
  transition: border-color 0.2s ease;
}
.composer-box:focus-within {
  border-color: var(--accent);
}
.composer-box textarea {
  flex: 1;
  resize: none;
  border: none;
  outline: none;
  background: transparent;
  color: var(--text);
  font-size: 14.5px;
  line-height: 1.6;
  max-height: 120px;
}
.composer-box textarea::placeholder {
  color: var(--muted);
}
.actions {
  display: flex;
  gap: 8px;
  flex: none;
}
.send,
.stop {
  padding: 9px 18px;
  border-radius: 12px;
  font-size: 13.5px;
  font-weight: 500;
  transition: transform 0.15s ease, opacity 0.15s ease;
}
.send {
  background: var(--user-bubble);
  color: #fff;
}
.send:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.send:not(:disabled):hover {
  transform: translateY(-1px);
}
.stop {
  border: 1px solid var(--border);
  color: var(--text);
}
.stop:hover {
  background: var(--border);
}
.tip {
  margin: 8px 4px 0;
  font-size: 11px;
  color: var(--muted);
  text-align: center;
}

/* ===== 移动端适配 ===== */
@media (max-width: 640px) {
  .chat-header {
    padding: 12px 14px;
  }
  .messages {
    padding: 18px 12px 8px;
  }
  .composer {
    padding: 10px 12px 14px;
  }
  .col {
    max-width: 82%;
  }
  .room-id {
    display: none;
  }
}
</style>
