<template>
  <div class="page">
    <PageHeader
      eyebrow="READING · 章节阅读"
      :title="detail?.title || '加载中…'"
      :subtitle="subtitle"
    >
      <template #actions>
        <button type="button" class="btn" @click="router.push('/materials')">返回资料库</button>
      </template>
    </PageHeader>

    <main class="content">
      <div v-if="error" class="banner">{{ error }}</div>
      <p v-if="loading && !detail" class="loading">加载正文中…</p>

      <article v-if="detail" class="reader">
        <div class="reader-inner">
          <p class="chapter-line">
            {{ detail.docTitle }}
            <span class="sep">·</span>
            {{ formatChars(detail.chars) }}
          </p>

          <div class="prose">
            <template v-for="(block, i) in blocks" :key="i">
              <h3 v-if="block.heading" class="md-heading">{{ block.text }}</h3>
              <p v-else class="md-para">{{ block.text }}</p>
            </template>
          </div>
        </div>
      </article>

      <div v-if="detail" class="action-bar">
        <div class="action-inner">
          <nav class="pager">
            <button
              type="button"
              class="pager-btn"
              :disabled="!prevSection"
              @click="goSection(prevSection)"
            >
              <span class="pager-label">上一章</span>
              <span class="pager-title">{{ prevSection ? prevSection.title : '已是第一章' }}</span>
            </button>
            <button
              type="button"
              class="pager-btn right"
              :disabled="!nextSection"
              @click="goSection(nextSection)"
            >
              <span class="pager-label">下一章</span>
              <span class="pager-title">{{ nextSection ? nextSection.title : '已是最后一章' }}</span>
            </button>
          </nav>

          <div class="cta">
            <button type="button" class="btn ghost" :disabled="generating" @click="detect">
              直接检测
            </button>
            <button type="button" class="btn primary" :disabled="generating" @click="detect">
              {{ generating ? '正在出题…' : '我读完了，开始检测' }}
            </button>
          </div>
        </div>
      </div>
    </main>

    <!-- 出题约 4.4 秒（两次模型调用），必须给出进行中态 -->
    <div v-if="generating" class="mask">
      <div class="busy-card">
        <div class="spinner"></div>
        <p>正在针对这一章出 3 道题…</p>
        <div class="busy-hint">2 道回忆题 + 1 道讲解题，约 4~5 秒</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '../components/PageHeader.vue'
import { getSection, listDocuments } from '../api/knowledge.js'
import { generate as generateQuiz } from '../api/quiz.js'
import { redirectOn401, messageOf } from '../utils/apiError.js'

const route = useRoute()
const router = useRouter()

const detail = ref(null)
const docs = ref([])
const loading = ref(true)
const error = ref('')
const generating = ref(false)

const subtitle = computed(() => {
  if (!detail.value) return '正在取正文'
  const total = doc.value?.sectionCount
  return total
    ? `第 ${detail.value.sectionNo} / ${total} 章`
    : `第 ${detail.value.sectionNo} 章`
})

// 当前章节所属文档：用于「第 N / M 章」与上一章 / 下一章
const doc = computed(() =>
  docs.value.find((d) => d.docTitle === detail.value?.docTitle) || null
)
const currentIndex = computed(() => {
  if (!doc.value || !detail.value) return -1
  return doc.value.sections.findIndex(
    (s) => s.knowledgeItemId === detail.value.knowledgeItemId
  )
})
const prevSection = computed(() =>
  currentIndex.value > 0 ? doc.value.sections[currentIndex.value - 1] : null
)
const nextSection = computed(() => {
  if (!doc.value || currentIndex.value < 0) return null
  return doc.value.sections[currentIndex.value + 1] || null
})

/**
 * 正文按空行分块：
 * - 首行是 # 标记的块渲染成小标题（去掉 #）
 * - 其余块保留内部换行（white-space: pre-line），避免把硬换行的段落揉成一行
 * content 首行通常是 "## 章节标题"，与顶部标题重复，属于已知情况，这里只跳过这一行
 */
const blocks = computed(() => {
  if (!detail.value?.content) return []
  const rawLines = detail.value.content.split(/\r?\n/)
  let firstContent = true
  const result = []
  let buffer = []
  let heading = false

  const flush = () => {
    if (!buffer.length) return
    result.push({ heading, text: buffer.join('\n').replace(/[ \t]+$/gm, '') })
    buffer = []
    heading = false
  }

  for (const line of rawLines) {
    if (!line.trim()) {
      flush()
      continue
    }
    const m = line.match(/^(#{1,6})\s+(.*)$/)
    const text = m ? m[2].trim() : line
    // 跳过与页面标题重复的首行
    if (firstContent) {
      firstContent = false
      if (m && text === detail.value.title) continue
    }
    if (m) {
      flush()
      buffer.push(text)
      heading = true
      flush()
      continue
    }
    buffer.push(line)
  }
  flush()
  return result
})

async function load(id) {
  loading.value = true
  error.value = ''
  detail.value = null
  try {
    detail.value = await getSection(id)
  } catch (e) {
    if (redirectOn401(e, router, route)) return
    error.value = messageOf(e, '章节加载失败，请返回资料库重试')
    loading.value = false
    return
  }
  loading.value = false

  // 目录只用于翻页，失败不阻塞正文阅读
  try {
    docs.value = await listDocuments()
  } catch (e) {
    docs.value = []
  }
}

function goSection(section) {
  if (!section) return
  router.push({ name: 'section-read', params: { knowledgeItemId: section.knowledgeItemId } })
}

async function detect() {
  if (!detail.value) return
  error.value = ''
  generating.value = true
  try {
    const quiz = await generateQuiz(detail.value.knowledgeItemId)
    router.push({ name: 'quiz', params: { conversationId: quiz.conversationId } })
  } catch (e) {
    if (redirectOn401(e, router, route)) return
    error.value = messageOf(e, '出题失败，请稍后重试')
  } finally {
    generating.value = false
  }
}

const formatChars = (chars) => `${Number(chars || 0).toLocaleString('zh-CN')} 字`

watch(
  () => route.params.knowledgeItemId,
  (id) => {
    if (id) load(id)
  },
  { immediate: true }
)
</script>

<style scoped>
/* 浅色令牌：与 ChatGPT / DeepSeek 网页版一致 */
.page {
  --bg: #fcfcfc;
  --surface: #ffffff;
  --ink: #0d0d0d;
  --ink-soft: #565869;
  --ink-dim: #8e8ea0;
  --line: rgba(0, 0, 0, 0.08);
  --brand: #0d0d0d;
  --brand-lit: #4d6bfe;
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
  display: flex;
  flex-direction: column;
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
  border: 1px solid rgba(0, 0, 0, 0.14);
  color: var(--ink);
  background: #ffffff;
  transition: background 0.16s ease, border-color 0.16s ease, color 0.16s ease;
  white-space: nowrap;
}
.btn:hover {
  background: #f0f0f2;
}
.btn.primary {
  background: #0d0d0d;
  border-color: #0d0d0d;
  color: #ffffff;
  font-weight: 500;
}
.btn.primary:hover {
  background: #2f2f2f;
}
.btn.ghost {
  border-color: rgba(0, 0, 0, 0.14);
  color: var(--ink-soft);
  background: #ffffff;
}
.btn.ghost:hover {
  background: #f0f0f2;
  color: var(--ink);
}
.btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.banner {
  max-width: 760px;
  width: calc(100% - 64px);
  margin: 20px auto 0;
  padding: 12px 16px;
  border-radius: 12px;
  border: 1px solid rgba(0, 0, 0, 0.1);
  background: #fff7ed;
  color: #b4341a;
  font-size: 13px;
}
.loading {
  margin: 0;
  padding: 60px 0;
  text-align: center;
  color: var(--ink-dim);
  font-size: 12.5px;
}

/* ---------- 正文：窄栏 + 大行距，阅读优先 ---------- */
.reader {
  flex: 1;
}
.reader-inner {
  max-width: 720px;
  margin: 0 auto;
  padding: 40px 32px 56px;
}
.chapter-line {
  margin: 0 0 28px;
  font-size: 12px;
  color: var(--ink-dim);
}
.sep {
  margin: 0 6px;
  color: rgba(0, 0, 0, 0.18);
}
.prose {
  font-size: 15.5px;
  line-height: 1.85;
  color: #1f2023;
}
.md-para {
  margin: 0 0 1.15em;
  white-space: pre-line;
  overflow-wrap: anywhere;
}
.md-heading {
  margin: 2em 0 0.7em;
  font-size: 19px;
  font-weight: 600;
  line-height: 1.5;
  letter-spacing: -0.01em;
  color: var(--ink);
}
.prose > .md-heading:first-child {
  margin-top: 0;
}

/* ---------- 底部操作栏：吸底，翻页在左、主操作在右 ---------- */
.action-bar {
  position: sticky;
  bottom: 0;
  border-top: 1px solid var(--line);
  background: #ffffff;
}
.action-inner {
  max-width: 1040px;
  margin: 0 auto;
  padding: 14px 32px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
}
.pager {
  display: flex;
  gap: 10px;
  min-width: 0;
}
.pager-btn {
  min-width: 0;
  max-width: 240px;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 3px;
  padding: 8px 12px;
  border-radius: 10px;
  border: 1px solid transparent;
  color: var(--ink-soft);
  text-align: left;
  transition: background 0.16s ease, border-color 0.16s ease;
}
.pager-btn.right {
  align-items: flex-end;
  text-align: right;
}
.pager-btn:hover:not(:disabled) {
  background: #f0f0f2;
  border-color: transparent;
}
.pager-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.pager-label {
  font-size: 10.5px;
  color: var(--ink-dim);
}
.pager-title {
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12.5px;
  color: var(--ink-soft);
}
.cta {
  flex: none;
  display: flex;
  align-items: center;
  gap: 10px;
}

.mask {
  position: fixed;
  inset: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: rgba(0, 0, 0, 0.35);
  backdrop-filter: blur(2px);
}
.busy-card {
  width: 340px;
  padding: 26px;
  border-radius: 18px;
  background: #ffffff;
  box-shadow: 0 20px 48px rgba(0, 0, 0, 0.14);
  text-align: center;
}
.busy-card p {
  margin: 0;
  font-size: 13.5px;
  color: var(--ink);
}
.busy-hint {
  margin-top: 8px;
  font-size: 11px;
  color: var(--ink-dim);
}
.spinner {
  width: 26px;
  height: 26px;
  margin: 0 auto 16px;
  border-radius: 50%;
  border: 2px solid rgba(0, 0, 0, 0.1);
  border-top-color: #0d0d0d;
  animation: spin 0.9s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}

@media (max-width: 820px) {
  .banner,
  .reader-inner {
    width: 100%;
    padding-left: 18px;
    padding-right: 18px;
  }
  .reader-inner {
    padding-top: 28px;
    /* 窄屏底栏是两行（翻页 + 操作），留足高度，避免正文最后一段被压在栏下 */
    padding-bottom: 132px;
  }
  .action-inner {
    flex-direction: column;
    align-items: stretch;
    gap: 10px;
    padding: 12px 18px;
  }
  /* 窄屏操作栏内容更密：保持实色底，避免正文透上来 */
  .action-bar {
    background: #ffffff;
  }
  .pager {
    width: 100%;
    justify-content: space-between;
  }
  .pager-btn {
    width: 46%;
    max-width: none;
  }
  /* 窄屏标题不能超出按钮：否则左右两个分页标题会相互压字 */
  .pager-title {
    max-width: 100%;
  }
  .pager-label {
    font-size: 10.5px;
    color: var(--ink-dim);
  }
  .pager-btn:disabled {
    opacity: 0.55;
  }
  .cta {
    justify-content: stretch;
  }
  .cta .btn {
    flex: 1;
  }
  .prose {
    font-size: 15px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .btn,
  .spinner {
    animation: none;
    transition: none;
  }
}
</style>
