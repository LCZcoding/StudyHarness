<template>
  <div class="page">
    <PageHeader
      eyebrow="NOTES · 笔记"
      title="所有对话里生成的笔记"
      subtitle="按更新时间倒序。点开就能继续批注、直接改正文。"
    >
      <template #actions>
        <button type="button" class="btn ghost" :disabled="loading" @click="load">
          {{ loading ? '刷新中…' : '刷新' }}
        </button>
      </template>
    </PageHeader>

    <main class="content">
      <div class="inner">
        <div v-if="error" class="banner">{{ error }}</div>

        <!-- 空态：笔记的唯一产出入口是对话里的开关，所以引导回对话，不引导「新建」 -->
        <div v-if="!loading && !notes.length && !error" class="empty">
          <h2>还没有笔记</h2>
          <p>
            在对话里打开「生成笔记」开关再提问，AI 会把这一轮讲清楚的东西写成一篇可批注的笔记，
            生成后就会出现在这里。
          </p>
          <RouterLink class="btn primary" :to="{ name: 'guide' }">去对话</RouterLink>
        </div>

        <ul v-if="notes.length" class="list">
          <li v-for="n in notes" :key="n.messageId" class="row">
            <RouterLink
              class="row-link"
              :to="{ name: 'note-detail', params: { messageId: n.messageId } }"
            >
              <h2 class="row-title">{{ card(n).title }}</h2>
              <p class="row-summary">{{ card(n).summary }}</p>
              <p class="row-meta">
                <span>{{ card(n).chars.toLocaleString('zh-CN') }} 字</span>
                <span class="sep">·</span>
                <span>{{ n.annotationCount }} 条批注</span>
                <span class="sep">·</span>
                <span>更新于 {{ formatTime(n.updatedAt) }}</span>
              </p>
            </RouterLink>
          </li>
        </ul>

        <p v-if="loading" class="loading">正在取你的笔记…</p>
      </div>
    </main>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '../components/PageHeader.vue'
import { list as listNotes } from '../api/notes.js'
import { noteCardOf } from '../utils/noteCard.js'
import { redirectOn401, messageOf } from '../utils/apiError.js'

/**
 * 笔记列表页（FR-14，plan.md 5.2 #8）。
 *
 * 只做三件事：拉列表、把每条渲染成一行、点开进详情页。
 * 标题 / 摘要 / 字数字段**不在后端返回**，一律走 utils/noteCard.js 的 noteCardOf() ——
 * 聊天卡片、这个列表、详情页顶栏三处标题必须逐字相同，多写一份实现迟早漂移。
 *
 * props.initialNotes 只给 SSR 自检注入数据用（与 AnnotatableDoc 的 initialAnnotations 同一做法）：
 * SSR 不执行 onMounted，没有它就只能验加载态，验不到列表长什么样。生产路径不传。
 */
const props = defineProps({
  initialNotes: { type: Array, default: null }
})

const route = useRoute()
const router = useRouter()

// 注入的数据在 setup 时就定下来，之后 load() 不再发请求
const injected = props.initialNotes
const notes = ref(injected || [])
const loading = ref(!injected)
const error = ref('')

/**
 * 每行的文案缓存，key 带正文长度：同一条笔记被保存后正文变了就自动失效。
 * 没有它，一次渲染会为每一行重扫三遍整篇 Markdown（模板里 card() 调了三次）。
 */
const cardCache = new Map()
function card(note) {
  const key = `${note.messageId}:${(note.content || '').length}`
  let cached = cardCache.get(key)
  if (!cached) {
    cached = noteCardOf(note.content)
    cardCache.set(key, cached)
  }
  return cached
}

/** '2026-11-15T05:01:23Z' → '11-15 13:01'。与详情页同一个格式，不引日期库。 */
function formatTime(iso) {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '—'
  const p = (n) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

async function load() {
  if (injected) return
  loading.value = true
  error.value = ''
  try {
    // 顺序由后端 ORDER BY updated_at DESC 决定，前端不再二次排序 —— 否则会出现
    // 「列表按 A 排、翻页按 B 排」两套口径
    notes.value = await listNotes()
  } catch (e) {
    if (redirectOn401(e, router, route)) return
    error.value = messageOf(e, '笔记列表加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
/* 与资料库、笔记详情页共用同一套浅色令牌 */
.page {
  --bg: #fcfcfc;
  --surface: #ffffff;
  --ink: #0d0d0d;
  --ink-soft: #565869;
  --ink-dim: #8e8ea0;
  --line: rgba(0, 0, 0, 0.08);
  --radius-pill: 999px;

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
  padding: 32px 32px 72px;
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
  color: var(--ink-soft);
}
.btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.banner {
  margin-bottom: 20px;
  padding: 12px 16px;
  border-radius: 12px;
  border: 1px solid rgba(0, 0, 0, 0.1);
  background: #fff7ed;
  color: #b4341a;
  font-size: 13px;
}

/* ---------- 列表：不用卡片，靠发丝线分隔（与资料库的章节行同一气质） ---------- */
.list {
  margin: 0;
  padding: 0;
  list-style: none;
  border-top: 1px solid var(--line);
}
.row {
  border-bottom: 1px solid var(--line);
}
.row-link {
  display: block;
  padding: 22px 12px 20px;
  color: inherit;
  transition: background 0.16s ease;
}
.row-link:hover {
  background: rgba(0, 0, 0, 0.02);
}
.row-title {
  margin: 0;
  font-size: 16.5px;
  font-weight: 600;
  line-height: 1.45;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row-summary {
  margin: 7px 0 0;
  font-size: 13px;
  line-height: 1.72;
  color: var(--ink-soft);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.row-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 11px 0 0;
  font-size: 11.5px;
  color: var(--ink-dim);
}
.sep {
  color: rgba(0, 0, 0, 0.2);
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
  max-width: 520px;
  margin: 0 auto 22px;
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

@media (max-width: 820px) {
  .inner {
    padding: 28px 18px 56px;
  }
  .row-title {
    white-space: normal;
  }
}
@media (prefers-reduced-motion: reduce) {
  .btn,
  .row-link {
    transition: none;
  }
}
</style>
