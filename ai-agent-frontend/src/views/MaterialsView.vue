<template>
  <div class="page">
    <PageHeader
      eyebrow="MATERIALS · 资料库"
      title="一份文档，一条学习路线"
      subtitle="按标题切成章节：先读正文，再用 3 道题验证这一章讲清楚没有。"
    >
      <template #actions>
        <button type="button" class="btn ghost" :disabled="uploading" @click="pickFile">
          {{ uploading ? '处理中…' : '上传文件' }}
        </button>
        <button type="button" class="btn primary" @click="openPaste">粘贴文本</button>
      </template>
    </PageHeader>

    <main class="content">
      <div class="inner">
        <div v-if="error" class="banner">{{ error }}</div>

        <!-- 空态 -->
        <div v-if="!loading && !docs.length" class="empty">
          <h2>还没有资料</h2>
          <p>把讲义、笔记、博客正文粘进来，或者上传 .md / .txt / .pdf。系统会按标题切成章节。</p>
        </div>

        <section v-for="doc in docs" :key="doc.docTitle" class="doc">
          <header class="doc-head">
            <div class="doc-text">
              <h2 class="doc-title">{{ doc.docTitle }}</h2>
              <p class="doc-meta">
                共 {{ doc.sectionCount }} 章 · 已通过 {{ doc.passedCount }} 章
              </p>
            </div>
            <div class="doc-progress" :title="`已通过 ${doc.passedCount} / ${doc.sectionCount}`">
              <i :style="{ width: percentOf(doc) }"></i>
            </div>
          </header>

          <ul class="sections">
            <li v-for="section in doc.sections" :key="section.knowledgeItemId" class="section">
              <span class="section-no">{{ String(section.sectionNo).padStart(2, '0') }}</span>

              <div class="section-body">
                <h3 class="section-title">{{ section.title }}</h3>
                <p class="section-summary">{{ section.summary || '本章摘要生成失败，不影响阅读与出题' }}</p>
                <p class="section-meta">
                  <span class="dot" :class="statusKey(section.status)"></span>
                  {{ statusLabel(section.status) }}
                  <span class="sep">·</span>
                  {{ formatChars(section.chars) }}
                </p>
              </div>

              <div class="section-actions">
                <button
                  type="button"
                  class="btn ghost sm"
                  :disabled="practiceBusy"
                  @click="openPractice(section)"
                >
                  怎么练
                </button>
                <button
                  type="button"
                  class="btn primary sm"
                  @click="openSection(section)"
                >
                  开始学习
                </button>
              </div>
            </li>
          </ul>
        </section>

        <p v-if="loading" class="loading">加载中…</p>
      </div>
    </main>

    <!-- 粘贴文本弹层 -->
    <div v-if="pasteOpen" class="mask" @click.self="pasteOpen = false">
      <div class="dialog">
        <h3>粘贴文本</h3>
        <label class="field">
          <span>文档名</span>
          <input v-model="pasteTitle" maxlength="100" placeholder="例如：JUC 并发编程笔记" />
        </label>
        <label class="field">
          <span>正文（{{ pasteContent.length }} / 200000）</span>
          <textarea v-model="pasteContent" maxlength="200000" placeholder="把材料正文粘到这里…" />
        </label>
        <p class="dialog-hint">保存后按标题逐章切分，每章生成摘要，章节多时需要等一会儿。</p>
        <p v-if="pasteError" class="dialog-error">{{ pasteError }}</p>
        <div class="dialog-foot">
          <button type="button" class="btn" @click="pasteOpen = false">取消</button>
          <button type="button" class="btn primary" :disabled="saving" @click="savePaste">
            {{ saving ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 怎么练：按章节生成一个可动手的练习方案，追问落在 PRACTICE 会话 -->
    <div v-if="practiceOpen" class="mask" @click.self="closePractice">
      <div class="dialog practice">
        <h3>怎么练 · {{ practiceSection?.title }}</h3>

        <div v-if="practiceBusy" class="practice-state">
          <div class="spinner"></div>
          <p>正在根据这一章生成练习方案…</p>
          <span>约 10 秒</span>
        </div>
        <template v-else>
          <p v-if="practiceError" class="dialog-error">{{ practiceError }}</p>
          <div v-else class="practice-body">
            <template v-for="(block, i) in practiceBlocks" :key="i">
              <h4 v-if="block.heading" class="practice-heading">{{ block.text }}</h4>
              <p v-else class="practice-para">{{ block.text }}</p>
            </template>
          </div>
        </template>

        <div class="dialog-foot">
          <button type="button" class="btn" @click="closePractice">关闭</button>
          <button
            type="button"
            class="btn primary"
            :disabled="!practice?.conversationId"
            @click="continuePractice"
          >
            继续追问
          </button>
        </div>
      </div>
    </div>

    <!--
      上传/粘贴都没有真实进度：后端是「全部章节写完摘要」才返回一个响应，
      中途不推送进度。这里只给已等待秒数，不给假进度条。
    -->
    <div v-if="busy" class="mask center">
      <div class="busy-card">
        <div class="spinner"></div>
        <p>正在切分并逐章生成摘要…</p>
        <div class="busy-hint">已等待 {{ elapsed }} 秒 · 章节多时最多 1~2 分钟</div>
        <div class="sk-lines"><i style="width: 92%"></i><i style="width: 76%"></i><i style="width: 84%"></i></div>
      </div>
    </div>

    <input ref="fileInput" type="file" accept=".md,.txt,.pdf" hidden @change="onFilePicked" />
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '../components/PageHeader.vue'
import { listDocuments, createItem, uploadItem } from '../api/knowledge.js'
import { guide as requestPracticeGuide } from '../api/practice.js'
import { redirectOn401, messageOf } from '../utils/apiError.js'

const route = useRoute()
const router = useRouter()

const docs = ref([])
const loading = ref(true)
const error = ref('')

const pasteOpen = ref(false)
const pasteTitle = ref('')
const pasteContent = ref('')
const pasteError = ref('')
const saving = ref(false)

const uploading = ref(false)
const fileInput = ref(null)

// 怎么练：弹层展示 PRACTICE 会话首轮内容，追问跳到对应会话
const practiceOpen = ref(false)
const practiceSection = ref(null)
const practice = ref(null)
const practiceBusy = ref(false)
const practiceError = ref('')

// 练习方案是模型输出的 markdown 文本：只做最小渲染（去 # / ** 标记，按空行分块），不引第三方 markdown 库
const practiceBlocks = computed(() => {
  const content = practice.value?.content
  if (!content) return []
  const result = []
  let buffer = []
  const flush = () => {
    if (!buffer.length) return
    result.push({ heading: false, text: buffer.join('\n').replace(/[ \t]+$/gm, '') })
    buffer = []
  }
  for (const line of content.split(/\r?\n/)) {
    if (!line.trim()) {
      flush()
      continue
    }
    const m = line.match(/^(#{1,6})\s+(.*)$/)
    if (m) {
      flush()
      result.push({ heading: true, text: m[2].trim().replace(/\*\*/g, '') })
    } else {
      buffer.push(line.replace(/\*\*/g, ''))
    }
  }
  flush()
  return result
})

// 上传与粘贴共用一套「忙碌」态：两者都在等同一个慢接口
const busy = computed(() => uploading.value || saving.value)
const elapsed = ref(0)
let elapsedTimer = null

function startElapsed() {
  elapsed.value = 0
  stopElapsed()
  elapsedTimer = setInterval(() => {
    elapsed.value += 1
  }, 1000)
}
function stopElapsed() {
  if (elapsedTimer) {
    clearInterval(elapsedTimer)
    elapsedTimer = null
  }
}

async function load() {
  try {
    docs.value = await listDocuments()
  } catch (e) {
    if (redirectOn401(e, router, route)) return
    error.value = messageOf(e, '资料加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

function openPaste() {
  pasteTitle.value = ''
  pasteContent.value = ''
  pasteError.value = ''
  pasteOpen.value = true
}

async function savePaste() {
  pasteError.value = ''
  if (!pasteTitle.value.trim() || !pasteContent.value.trim()) {
    pasteError.value = '文档名和正文都不能为空'
    return
  }
  saving.value = true
  startElapsed()
  try {
    await createItem(pasteTitle.value.trim(), pasteContent.value)
    pasteOpen.value = false
    await load()
  } catch (e) {
    if (redirectOn401(e, router, route)) return
    pasteError.value = messageOf(e, '保存失败，请稍后重试')
  } finally {
    saving.value = false
    stopElapsed()
  }
}

function pickFile() {
  fileInput.value?.click()
}

async function onFilePicked(event) {
  const file = event.target.files?.[0]
  // 先清空 value：否则连续选同一个文件不会再触发 change
  event.target.value = ''
  if (!file) return
  error.value = ''
  uploading.value = true
  startElapsed()
  try {
    await uploadItem(file)
    await load()
  } catch (e) {
    if (redirectOn401(e, router, route)) return
    error.value = messageOf(e, '上传失败，请检查文件格式与大小')
  } finally {
    uploading.value = false
    stopElapsed()
  }
}

function openSection(section) {
  router.push({ name: 'section-read', params: { knowledgeItemId: section.knowledgeItemId } })
}

async function openPractice(section) {
  practiceSection.value = section
  practice.value = null
  practiceError.value = ''
  practiceOpen.value = true
  practiceBusy.value = true
  try {
    practice.value = await requestPracticeGuide(section.knowledgeItemId)
  } catch (e) {
    if (redirectOn401(e, router, route)) {
      practiceOpen.value = false
      return
    }
    practiceError.value = messageOf(e, '练习方案生成失败，请稍后重试')
  } finally {
    practiceBusy.value = false
  }
}

function closePractice() {
  // 请求还没回来也允许关闭：不能把用户困在一个只能等结果的弹层里
  practiceOpen.value = false
  practiceBusy.value = false
}

function continuePractice() {
  if (!practice.value?.conversationId) return
  practiceOpen.value = false
  router.push({
    name: 'guide-conversation',
    params: { conversationId: practice.value.conversationId }
  })
}

// 三态只认服务端返回的枚举，前端不做二次推导
const STATUS = {
  NOT_STARTED: { label: '未开始', key: 'todo' },
  IN_PROGRESS: { label: '进行中', key: 'doing' },
  PASSED: { label: '已通过', key: 'done' }
}
const statusLabel = (status) => STATUS[status]?.label || status
const statusKey = (status) => STATUS[status]?.key || 'todo'

const percentOf = (doc) =>
  doc.sectionCount ? `${Math.round((doc.passedCount / doc.sectionCount) * 100)}%` : '0%'

const formatChars = (chars) => `${Number(chars || 0).toLocaleString('zh-CN')} 字`

onMounted(load)
onBeforeUnmount(stopElapsed)
</script>

<style scoped>
/* 设计令牌沿用首页：深色底 + 青色强调，排版节奏参考苹果官网（大留白、发丝线、层级靠字号） */
.page {
  --bg: #0e1116;
  --surface: #141b23;
  --ink: #eef4fa;
  --ink-soft: #8ba3b8;
  --ink-dim: #6b7f92;
  --line: rgba(148, 163, 184, 0.13);
  --brand: #0284c7;
  --brand-lit: #22d3ee;
  --amber: #fbbf24;
  --radius-pill: 999px;
  --ease: cubic-bezier(0.22, 1, 0.36, 1);

  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--bg);
}
.content {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}
.inner {
  max-width: 1040px;
  margin: 0 auto;
  padding: 36px 32px 72px;
}

.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 9px 18px;
  border-radius: var(--radius-pill);
  font-size: 13px;
  line-height: 1;
  border: 1px solid var(--line);
  color: var(--ink);
  background: transparent;
  transition: background 0.2s var(--ease), border-color 0.2s var(--ease), color 0.2s var(--ease);
  white-space: nowrap;
}
.btn:hover {
  background: rgba(148, 163, 184, 0.1);
}
.btn.primary {
  background: linear-gradient(140deg, var(--brand), #0ea5c9);
  border-color: transparent;
  color: #04121b;
  font-weight: 700;
}
.btn.primary:hover {
  filter: brightness(1.08);
}
.btn.ghost {
  border-color: rgba(34, 211, 238, 0.35);
  color: var(--brand-lit);
}
.btn.ghost:hover {
  background: rgba(34, 211, 238, 0.12);
}
.btn.sm {
  padding: 8px 16px;
  font-size: 12.5px;
}
.btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.banner {
  margin-bottom: 20px;
  padding: 12px 16px;
  border-radius: 12px;
  border: 1px solid rgba(248, 113, 113, 0.35);
  background: rgba(248, 113, 113, 0.08);
  color: #f9a8a8;
  font-size: 13px;
}

/* ---------- 文档分组 ---------- */
.doc + .doc {
  margin-top: 64px;
}
.doc-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--line);
}
.doc-text {
  min-width: 0;
}
.doc-title {
  margin: 0;
  font-size: 28px;
  font-weight: 800;
  letter-spacing: -0.02em;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.doc-meta {
  margin: 8px 0 0;
  font-family: 'IBM Plex Mono', monospace;
  font-size: 11.5px;
  letter-spacing: 0.5px;
  color: var(--ink-dim);
}
.doc-progress {
  flex: none;
  width: 200px;
  height: 3px;
  margin-bottom: 6px;
  border-radius: var(--radius-pill);
  background: rgba(148, 163, 184, 0.16);
  overflow: hidden;
}
.doc-progress i {
  display: block;
  height: 100%;
  border-radius: var(--radius-pill);
  background: linear-gradient(90deg, var(--brand), var(--brand-lit));
  transition: width 0.5s var(--ease);
}

/* ---------- 章节行：不用卡片，靠发丝线分隔 ---------- */
.sections {
  margin: 0;
  padding: 0;
  list-style: none;
}
.section {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr) auto;
  gap: 20px;
  align-items: start;
  padding: 26px 4px 24px;
  border-bottom: 1px solid var(--line);
  transition: background 0.2s var(--ease);
}
.section:hover {
  background: rgba(148, 163, 184, 0.035);
}
.section-no {
  padding-top: 3px;
  font-family: 'IBM Plex Mono', monospace;
  font-size: 12px;
  color: var(--ink-dim);
}
.section-body {
  min-width: 0;
}
.section-title {
  margin: 0;
  font-size: 16.5px;
  font-weight: 700;
  line-height: 1.45;
  color: var(--ink);
  transition: color 0.2s var(--ease);
}
.section:hover .section-title {
  color: #ffffff;
}
.section-summary {
  margin: 7px 0 0;
  font-size: 13px;
  line-height: 1.72;
  color: var(--ink-soft);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.section-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 11px 0 0;
  font-family: 'IBM Plex Mono', monospace;
  font-size: 11px;
  color: var(--ink-dim);
}
.sep {
  color: rgba(148, 163, 184, 0.3);
}
.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex: none;
}
.dot.todo {
  background: #55677a;
}
.dot.doing {
  background: var(--amber);
  box-shadow: 0 0 0 3px rgba(251, 191, 36, 0.14);
}
.dot.done {
  background: var(--brand-lit);
  box-shadow: 0 0 0 3px rgba(34, 211, 238, 0.16);
}
.section-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-top: 2px;
}

/* ---------- 空态与加载 ---------- */
.empty {
  padding: 88px 24px;
  text-align: center;
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
}
.empty h2 {
  margin: 0 0 10px;
  font-size: 26px;
  font-weight: 800;
  letter-spacing: -0.02em;
  color: var(--ink);
}
.empty p {
  margin: 0;
  font-size: 13.5px;
  line-height: 1.8;
  color: var(--ink-dim);
}
.loading {
  margin: 0;
  padding: 48px 0;
  text-align: center;
  color: var(--ink-dim);
  font-size: 12px;
}

/* ---------- 弹层 ---------- */
.mask {
  position: fixed;
  inset: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: rgba(6, 10, 14, 0.72);
  backdrop-filter: blur(3px);
}
.dialog {
  width: min(620px, 100%);
  max-height: 88vh;
  overflow-y: auto;
  padding: 26px;
  border-radius: 20px;
  border: 1px solid rgba(34, 211, 238, 0.18);
  background: var(--surface);
}
.dialog h3 {
  margin: 0 0 18px;
  font-size: 18px;
  font-weight: 800;
  color: var(--ink);
}
.field {
  display: flex;
  flex-direction: column;
  gap: 7px;
  margin-bottom: 16px;
}
.field span {
  font-family: 'IBM Plex Mono', monospace;
  font-size: 11px;
  color: var(--ink-dim);
}
.field input,
.field textarea {
  width: 100%;
  padding: 11px 13px;
  border-radius: 12px;
  border: 1px solid var(--line);
  background: #0f151c;
  color: var(--ink);
  outline: none;
  transition: border-color 0.2s var(--ease);
}
.field input:focus,
.field textarea:focus {
  border-color: rgba(34, 211, 238, 0.45);
}
.field textarea {
  min-height: 240px;
  resize: vertical;
  line-height: 1.7;
}
.dialog-hint {
  margin: -4px 0 12px;
  font-size: 12px;
  color: var(--ink-dim);
}
.dialog-error {
  margin: 0 0 12px;
  color: #f9a8a8;
  font-size: 12.5px;
}
.dialog-foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.dialog.practice {
  width: min(680px, 100%);
}
.practice-state {
  padding: 42px 0 34px;
  text-align: center;
}
.practice-state p {
  margin: 0;
  font-size: 13.5px;
  color: var(--ink);
}
.practice-state span {
  display: block;
  margin-top: 8px;
  font-family: 'IBM Plex Mono', monospace;
  font-size: 11px;
  color: var(--ink-dim);
}
.practice-body {
  max-height: 52vh;
  margin-bottom: 20px;
  padding-right: 6px;
  overflow-y: auto;
  font-size: 14px;
  line-height: 1.9;
  color: #c9d6e2;
}
.practice-heading {
  margin: 1.5em 0 0.5em;
  font-size: 14.5px;
  font-weight: 800;
  letter-spacing: 0.2px;
  color: var(--ink);
}
.practice-body > .practice-heading:first-child {
  margin-top: 0;
}
.practice-para {
  margin: 0 0 0.9em;
  white-space: pre-line;
}

.busy-card {
  width: 360px;
  padding: 28px 26px;
  border-radius: 20px;
  border: 1px solid rgba(34, 211, 238, 0.18);
  background: var(--surface);
  text-align: center;
}
.busy-card p {
  margin: 0;
  font-size: 13.5px;
  color: var(--ink);
}
.busy-hint {
  margin-top: 8px;
  font-family: 'IBM Plex Mono', monospace;
  font-size: 11px;
  color: var(--ink-dim);
}
.spinner {
  width: 26px;
  height: 26px;
  margin: 0 auto 16px;
  border-radius: 50%;
  border: 2px solid rgba(34, 211, 238, 0.22);
  border-top-color: var(--brand-lit);
  animation: spin 0.9s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
.sk-lines {
  margin-top: 20px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.sk-lines i {
  display: block;
  height: 8px;
  border-radius: 4px;
  background: linear-gradient(90deg, rgba(34, 211, 238, 0.08), rgba(34, 211, 238, 0.2), rgba(34, 211, 238, 0.08));
  background-size: 200% 100%;
  animation: shimmer 1.4s linear infinite;
}
@keyframes shimmer {
  to { background-position: -200% 0; }
}

@media (max-width: 820px) {
  .inner {
    padding: 28px 18px 56px;
  }
  .doc-head {
    flex-direction: column;
    align-items: flex-start;
    gap: 14px;
  }
  .doc-title {
    font-size: 22px;
    white-space: normal;
  }
  .doc-progress {
    width: 100%;
    margin-bottom: 0;
  }
  .section {
    grid-template-columns: 30px minmax(0, 1fr);
    gap: 12px;
  }
  .section-actions {
    grid-column: 2;
    padding-top: 12px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .section,
  .btn,
  .spinner,
  .sk-lines i {
    animation: none;
    transition: none;
  }
}
</style>
