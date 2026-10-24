<template>
  <section :class="['chat-room', `theme-${theme}`]">
    <!-- 顶部：会话信息 -->
    <header class="chat-header">
      <div class="h-left">
        <RouterLink to="/" class="back" title="返回应用中心">←</RouterLink>
        <div class="avatar"><BrandMark /></div>
        <div class="title-box">
          <h1>{{ title }}</h1>
          <p class="status">
            <i :class="['dot', { on: streaming }]"></i>
            {{ streaming ? streamingText : idleText }}
          </p>
        </div>
      </div>
      <div class="h-right">
        <div v-if="chatId" class="room-id" :title="chatId">
          <span class="rid-label">CHAT ID</span>
          <span class="rid-value">{{ shortChatId }}</span>
        </div>
      </div>
    </header>

    <!-- 中部：聊天记录（用户在右，AI 在左） -->
    <div ref="listEl" class="messages">
      <!-- 空会话：欢迎语 + 建议提问 -->
      <div v-if="messages.length === 0" class="empty">
        <div class="empty-avatar"><BrandMark /></div>
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
          <div v-if="m.role === 'ai'" class="avatar sm"><BrandMark /></div>
          <div class="col">
            <span class="name">{{ m.role === 'user' ? userName : aiName }} · {{ m.time }}</span>
            <div :class="['bubble', m.role]">
              <template v-if="m.role === 'ai' && m.content">
                <template v-for="(seg, idx) in parseAiContent(m.content)" :key="idx">
                  <div v-if="seg.type === 'step'" class="step-seg">
                    <span class="step-badge">Step {{ seg.num }}</span>
                    <span class="step-text">{{ seg.text }}</span>
                  </div>
                  <div v-else class="text-seg md" v-html="renderMarkdown(seg.text)"></div>
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
          <!-- 父组件往输入框右侧塞图标按钮（GuideChatView 放检索 / 工具 / 做成题） -->
          <slot name="composer-extra" />
          <button v-if="streaming" type="button" class="stop" @click="stop">■ 停止</button>
          <button
            type="button"
            class="send"
            :disabled="streaming || !input.trim()"
            aria-label="发送"
            @click="send()"
          >
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor"
                 stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M12 19V5" />
              <path d="M5 12l7-7 7 7" />
            </svg>
          </button>
        </div>
      </div>
      <p class="tip">Enter 发送 · Shift + Enter 换行</p>
    </footer>
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { streamChat } from '../api/chatStream.js'
import { renderMarkdown } from '../utils/markdown.js'
import BrandMark from './BrandMark.vue'

const props = defineProps({
  // 主题：guide（浅色答疑）/ manus（科技深色，当前无路由使用，留给后续工具调用接入）
  theme: { type: String, default: 'guide' },
  title: { type: String, default: '' },
  aiName: { type: String, default: '小思' },
  userName: { type: String, default: '我' },
  userAvatar: { type: String, default: '我' },
  // 流式模式：token = 逐字追加（当前唯一入口 GuideChatView 走这条）；
  //          step = 按步骤换行追加（原 /manus 入口用，入口下线后暂无调用方）
  streamMode: { type: String, default: 'token' },
  // 聊天室 id（问题引导助手需要）

  chatId: { type: String, default: '' },
  // 可选：预置的历史消息，形如 [{ messageId, role: 'USER'|'ASSISTANT', content, createdAt }]
  // 不传 = 空会话（当前唯一入口 GuideChatView 会传）
  historyMessages: { type: Array, default: () => [] },
  // 可选：懒创建会话的钩子，返回 Promise<conversationId>。
  // 只在 chatId 为空（还没建会话）时用：
  // 用户在空白会话里发出第一条消息时，才真正去 POST /conversations。
  resolveChatId: { type: Function, default: null },
  placeholder: { type: String, default: '输入你想说的话…' },
  // 本轮是否检索本人资料 / 是否允许只读工具：由父组件持有的开关控制，这里只透传给接口
  useRag: { type: Boolean, default: false },
  useTools: { type: Boolean, default: false },
  idleText: { type: String, default: '在线' },
  streamingText: { type: String, default: '正在输入…' },
  greeting: { type: String, default: '说说你卡在哪' },
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
  // 先清掉上一轮的请求（与具体内容无关）
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
    useRag: props.useRag,
    useTools: props.useTools,
    signal: controller.signal,
    onMessage,
    onDone,
    onError
  })
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
  emit('done')
  nextTick(() => inputEl.value?.focus())
}

function closeSource() {
  if (controller) {
    controller.abort()
    controller = null
  }
}

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
/* 设计令牌与资料页一致：深色底 + 青色强调，排版节奏参考苹果官网（发丝线分隔、层级靠字号） */
.chat-room {
  height: 100%;
  display: flex;
  flex-direction: column;
  /* 浅色聊天界面：白底、AI 回答平铺、用户消息浅灰气泡（参考 ChatGPT 网页版） */
  --bg: #ffffff;
  --surface: #ffffff;
  --text: #0d0d0d;
  --muted: #8e8ea0;
  --ink-soft: #565869;
  --accent: #4d6bfe;
  --panel: #ffffff;
  --ai-bubble: transparent;
  --user-bubble: #f4f4f5;
  --border: rgba(0, 0, 0, 0.08);
  --ease: cubic-bezier(0.22, 1, 0.36, 1);
  background: var(--bg);
  color: var(--text);
}

/* ===== 问题引导解决助手：浅色中性 + DeepSeek 蓝强调 ===== */
.theme-guide {
  --text: #0d0d0d;
  --muted: #8e8ea0;
  --accent: #4d6bfe;
  --panel: #ffffff;
  --ai-bubble: transparent;
  --user-bubble: #f4f4f5;
  --border: rgba(0, 0, 0, 0.08);
  background: #ffffff;
}

/* ===== 超级智能体：同一套浅色令牌（该主题当前没有路由使用） ===== */
.theme-manus {
  --text: #0d0d0d;
  --muted: #8e8ea0;
  --accent: #4d6bfe;
  --panel: #ffffff;
  --ai-bubble: transparent;
  --user-bubble: #f4f4f5;
  --border: rgba(0, 0, 0, 0.08);
  background: #ffffff;
}

/* ===== 顶栏：56px、无渐变、只留一条极浅分割线 ===== */
.chat-header {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 10px 16px;
  min-height: 56px;
  background: #ffffff;
  border-bottom: 1px solid var(--border);
}

.h-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.back {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  color: var(--ink-soft);
  font-size: 15px;
  transition: background 0.16s ease;
}
.back:hover {
  background: var(--user-bubble);
  color: var(--text);
}

.avatar {
  flex: none;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.3px;
  color: #ffffff;
  background: #0d0d0d;
}
.avatar.sm {
  width: 26px;
  height: 26px;
  font-size: 9.5px;
}
.avatar.user-av {
  background: #ececef;
  color: var(--ink-soft);
}

/* 标题占剩余宽度并允许收缩，窄屏才不会把右侧按钮挤出边界 */
.title-box {
  flex: 1 1 auto;
  min-width: 0;
}
.title-box h1 {
  margin: 0;
  font-size: 14.5px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.theme-manus .title-box h1 {
  letter-spacing: 0;
}

.status {
  margin: 2px 0 0;
  font-size: 11.5px;
  color: var(--muted);
  display: flex;
  align-items: center;
  gap: 6px;
}
.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--muted);
}
.dot.on {
  background: #10a37f;
  box-shadow: 0 0 0 0 rgba(16, 163, 127, 0.4);
  animation: pulse 1.2s infinite;
}
@keyframes pulse {
  0% { box-shadow: 0 0 0 0 rgba(16, 163, 127, 0.35); }
  70% { box-shadow: 0 0 0 6px rgba(16, 163, 127, 0); }
  100% { box-shadow: 0 0 0 0 rgba(16, 163, 127, 0); }
}

.h-right {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.room-id {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 1px;
  padding: 4px 10px;
  border-radius: 8px;
  border: 1px solid var(--border);
}
.rid-label {
  font-size: 9px;
  letter-spacing: 1.4px;
  color: var(--muted);
}
.rid-value {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11px;
  color: var(--ink-soft);
}

/* ===== 消息区：768px 居中单列 ===== */
.messages {
  flex: 1;
  overflow-y: auto;
  padding: 28px 20px 8px;
  scroll-behavior: smooth;
}
.messages::-webkit-scrollbar {
  width: 8px;
}
.messages::-webkit-scrollbar-thumb {
  background: rgba(0, 0, 0, 0.12);
  border-radius: 4px;
}

.empty {
  min-height: 100%;
  max-width: 768px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  gap: 8px;
  animation: rise 0.4s ease both;
}
@keyframes rise {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}
.empty-avatar {
  width: 52px;
  height: 52px;
  border-radius: 14px;
  display: grid;
  place-items: center;
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.3px;
  color: #ffffff;
  background: #0d0d0d;
  margin-bottom: 8px;
}
.empty h2 {
  margin: 0;
  font-size: 24px;
  font-weight: 400;
  letter-spacing: -0.02em;
  color: var(--text);
}
.empty p {
  margin: 0 0 12px;
  color: var(--ink-soft);
  font-size: 14px;
  line-height: 1.7;
}

.suggestions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
  max-width: 620px;
}
.suggestions button {
  padding: 9px 14px;
  font-size: 13px;
  color: var(--ink-soft);
  border-radius: var(--radius-pill);
  border: 1px solid rgba(0, 0, 0, 0.16);
  background: #ffffff;
  transition: background 0.16s ease, border-color 0.16s ease, color 0.16s ease;
}
.suggestions button:hover {
  background: var(--user-bubble);
  border-color: rgba(0, 0, 0, 0.22);
  color: var(--text);
}

/* 消息行 */
.msg-row {
  display: flex;
  gap: 12px;
  max-width: 768px;
  margin: 0 auto 24px;
  animation: msgIn 0.24s ease both;
}
@keyframes msgIn {
  from { opacity: 0; transform: translateY(6px); }
  to { opacity: 1; transform: translateY(0); }
}
.msg-row.user {
  flex-direction: row-reverse;
}
.col {
  display: flex;
  flex-direction: column;
  max-width: min(84%, 672px);
  min-width: 0;
}
.msg-row.user .col {
  align-items: flex-end;
}
.name {
  margin: 0 2px 4px;
  font-size: 11px;
  color: var(--muted);
}

.bubble {
  font-size: 15.5px;
  line-height: 1.78;
  white-space: pre-wrap;
  word-break: break-word;
}
/* AI 回答不套气泡，直接平铺成正文（ChatGPT 的做法） */
.bubble.ai {
  padding: 0;
  background: transparent;
  color: var(--text);
}
/* 用户消息：浅灰气泡、右侧、大圆角、不加粗 */
.bubble.user {
  padding: 10px 15px;
  border-radius: 20px;
  background: var(--user-bubble);
  color: var(--text);
  font-weight: 400;
}

/* AI 回答内的步骤段 / 最终文本段 */
.step-seg {
  display: flex;
  gap: 8px;
  padding: 8px 10px;
  margin-bottom: 10px;
  border-radius: 10px;
  border-left: 2px solid rgba(0, 0, 0, 0.2);
  background: #f7f7f8;
}
.step-seg:last-child {
  margin-bottom: 0;
}
.step-badge {
  flex: none;
  align-self: flex-start;
  padding: 1px 7px;
  font-size: 10.5px;
  font-weight: 600;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  color: #ffffff;
  background: #0d0d0d;
  border-radius: 5px;
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
/* markdown 渲染后是块级元素，不能再保留 pre-wrap，否则列表与代码块的换行会多出一层 */
.text-seg.md {
  white-space: normal;
  line-height: 1.78;
}
/* 气泡内的 markdown：只做「读得舒服」的排版，不改主题色 */
.text-seg.md :deep(p) {
  margin: 0 0 10px;
}
.text-seg.md :deep(p:last-child),
.text-seg.md :deep(ul:last-child),
.text-seg.md :deep(ol:last-child),
.text-seg.md :deep(pre:last-child) {
  margin-bottom: 0;
}
.text-seg.md :deep(h1),
.text-seg.md :deep(h2),
.text-seg.md :deep(h3),
.text-seg.md :deep(h4) {
  margin: 16px 0 8px;
  font-weight: 700;
  line-height: 1.4;
  color: var(--text);
}
.text-seg.md :deep(h1) { font-size: 18px; }
.text-seg.md :deep(h2) { font-size: 16.5px; }
.text-seg.md :deep(h3) { font-size: 15px; }
.text-seg.md :deep(h4) { font-size: 14.5px; }
.text-seg.md :deep(h1:first-child),
.text-seg.md :deep(h2:first-child),
.text-seg.md :deep(h3:first-child) {
  margin-top: 2px;
}
.text-seg.md :deep(strong) {
  color: var(--text);
  font-weight: 700;
}
.text-seg.md :deep(em) {
  color: var(--muted);
}
.text-seg.md :deep(ul),
.text-seg.md :deep(ol) {
  margin: 0 0 10px;
  padding-left: 22px;
}
.text-seg.md :deep(li) {
  margin: 4px 0;
}
.text-seg.md :deep(li > p) {
  margin: 0;
}
/* 行内代码与代码块分开处理：行内不能有外框，代码块才需要横向滚动 */
.text-seg.md :deep(code) {
  padding: 1px 5px;
  border: 0;
  border-radius: 5px;
  background: rgba(0, 0, 0, 0.05);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
}
.text-seg.md :deep(pre) {
  margin: 0 0 10px;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: #f7f7f8;
  overflow-x: auto;
}
.text-seg.md :deep(pre code) {
  padding: 0;
  border: 0;
  border-radius: 0;
  background: none;
  font-size: 12.8px;
  line-height: 1.65;
}
.text-seg.md :deep(blockquote) {
  margin: 0 0 10px;
  padding: 2px 0 2px 12px;
  border-left: 2px solid var(--accent);
  color: var(--muted);
}
.text-seg.md :deep(table) {
  display: block;
  width: 100%;
  margin: 0 0 10px;
  overflow-x: auto;
  border-collapse: collapse;
  font-size: 13.5px;
}
.text-seg.md :deep(th),
.text-seg.md :deep(td) {
  padding: 7px 10px;
  border-bottom: 1px solid var(--border);
  text-align: left;
  white-space: nowrap;
}
.text-seg.md :deep(th) {
  color: var(--text);
  font-weight: 700;
}
.text-seg.md :deep(hr) {
  margin: 14px 0;
  border: 0;
  border-top: 1px solid var(--border);
}
.text-seg.md :deep(a) {
  color: var(--accent);
  text-decoration: underline;
  text-underline-offset: 2px;
}
.text-seg.md :deep(img) {
  max-width: 100%;
  border-radius: 8px;
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

/* ===== 输入区：768px 居中、28px 圆角、52px 高的白色胶囊（参考 ChatGPT） ===== */
.composer {
  flex: none;
  padding: 12px 20px 20px;
  background: var(--bg);
}
.composer-box {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  width: 100%;
  max-width: 768px;
  margin: 0 auto;
  padding: 7px 8px 7px 18px;
  border-radius: 26px;
  background: #ffffff;
  border: 1px solid rgba(0, 0, 0, 0.1);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
  transition: border-color 0.16s ease, box-shadow 0.16s ease;
}
.composer-box:focus-within {
  border-color: rgba(0, 0, 0, 0.22);
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.07);
}
.composer-box textarea {
  flex: 1;
  resize: none;
  border: none;
  outline: none;
  background: transparent;
  color: var(--text);
  font-size: 16px;
  line-height: 1.6;
  padding: 12px 0;
  max-height: 136px;
}
.composer-box textarea::placeholder {
  color: #9b9ba8;
}
.actions {
  display: flex;
  gap: 6px;
  flex: none;
  align-items: center;
}
.send,
.stop {
  display: grid;
  place-items: center;
  border-radius: 999px;
  font-size: 12.5px;
  line-height: 1;
  transition: background 0.16s ease, opacity 0.16s ease;
}
/* 发送按钮：38px 圆形实心、只有图标（与 ChatGPT 一致） */
.send {
  width: 38px;
  height: 38px;
  flex: none;
  background: #0d0d0d;
  color: #ffffff;
}
.send svg {
  display: block;
}
.send:disabled {
  background: #e5e5e8;
  color: #a9a9b4;
  cursor: not-allowed;
}
.send:not(:disabled):hover {
  background: #2f2f2f;
}
.stop {
  height: 38px;
  padding: 0 14px;
  border: 1px solid rgba(0, 0, 0, 0.12);
  color: var(--ink-soft);
  background: #ffffff;
}
.stop:hover {
  background: var(--user-bubble);
}
.tip {
  margin: 8px 4px 0;
  font-size: 11px;
  color: var(--muted);
  text-align: center;
}

/* ===== 移动端适配 ===== */
@media (max-width: 640px) {
  /* 标题和操作按钮一行放不下：让右侧操作换到第二行，标题保留完整宽度 */
  .chat-header {
    flex-wrap: wrap;
    gap: 10px;
    padding: 12px 14px;
  }
  .h-left {
    flex: 1 1 100%;
  }
  .h-right {
    width: 100%;
    justify-content: flex-end;
    flex-wrap: wrap;
    gap: 8px;
  }
  .title-box {
    min-width: 0;
  }
  .title-box h1 {
    font-size: 16px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .avatar {
    width: 34px;
    height: 34px;
  }
  .messages {
    padding: 18px 12px 8px;
  }
  .composer {
    padding: 10px 12px 14px;
  }
  /* 窄屏一行放不下「输入 + 3 个图标 + 发送」：图标行换到输入框下方，输入区保留整行宽度 */
  .composer-box {
    flex-direction: column;
    align-items: stretch;
    padding: 4px 8px 6px 16px;
    border-radius: 22px;
  }
  .actions {
    justify-content: flex-end;
    padding-bottom: 2px;
  }
  .col {
    max-width: 88%;
  }
  .room-id {
    display: none;
  }
}
</style>
