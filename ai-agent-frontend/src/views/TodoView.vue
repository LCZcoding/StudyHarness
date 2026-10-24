<template>
  <div class="page">
    <PageHeader
      eyebrow="TODO · 待办"
      title="今天要做的事"
      subtitle="复习、补讲、还没测过的资料，三类按优先级排列。"
    >
      <template #actions>
        <button type="button" class="btn" :disabled="loading" @click="load">
          {{ loading ? '刷新中…' : '刷新' }}
        </button>
      </template>
    </PageHeader>

    <main class="content">
      <div class="inner">
        <div v-if="error" class="banner">{{ error }}</div>
        <p v-if="loading" class="loading">加载中…</p>

        <div v-if="!loading" class="todo-wrap">
          <!-- 一句话总览：三类任务各多少，先给结论再列明细 -->
          <p v-if="items.length" class="overview">
            共 {{ items.length }} 项待处理
            <span class="sep">·</span>
            复习 {{ review?.count || 0 }} 道
            <span class="sep">·</span>
            补讲 {{ retries.length }} 份
            <span class="sep">·</span>
            未测 {{ untested.length }} 份
          </p>

          <!-- REVIEW：后端只给一条汇总，count 是到期题数 -->
          <section v-if="review" class="tgroup">
            <div class="tgroup-head">
              <h2 class="name">需要复习</h2>
              <span class="count">{{ review.count }} 道到期</span>
            </div>
            <ul class="rows">
              <li class="row">
                <span class="dot review"></span>
                <div class="row-body">
                  <h3 class="main">到期复习</h3>
                  <p class="sub">到期题可能跨资料，点进去一次答完</p>
                </div>
                <div class="row-actions">
                  <button type="button" class="btn primary" :disabled="busy" @click="startReview">
                    {{ busy === 'review' ? '准备中…' : '开始复习' }}
                  </button>
                </div>
              </li>
            </ul>
          </section>

          <section v-if="retries.length" class="tgroup">
            <div class="tgroup-head">
              <h2 class="name">需要补讲</h2>
              <span class="count">{{ retries.length }} 份资料</span>
            </div>
            <ul class="rows">
              <li v-for="item in retries" :key="'retry-' + item.knowledgeItemId" class="row">
                <span class="dot retry"></span>
                <div class="row-body">
                  <h3 class="main">{{ item.title }}</h3>
                  <p class="sub">{{ item.count }} 道未通过 · 点进去会重问同一知识点</p>
                </div>
                <div class="row-actions">
                  <button
                    type="button"
                    class="btn ghost"
                    :disabled="busy !== null"
                    @click="retry(item)"
                  >
                    {{ busy === 'retry-' + item.knowledgeItemId ? '出题中…' : '去补讲' }}
                  </button>
                </div>
              </li>
            </ul>
          </section>

          <section v-if="untested.length" class="tgroup">
            <div class="tgroup-head">
              <h2 class="name">还没测过</h2>
              <span class="count">{{ untested.length }} 份资料</span>
            </div>
            <ul class="rows">
              <li
                v-for="item in untested"
                :key="'untested-' + item.knowledgeItemId"
                class="row"
              >
                <span class="dot untested"></span>
                <div class="row-body">
                  <h3 class="main">{{ item.title }}</h3>
                  <p class="sub">{{ item.count }} 道题未作答</p>
                </div>
                <div class="row-actions">
                  <button type="button" class="btn ghost" @click="open(item)">开始测验</button>
                </div>
              </li>
            </ul>
          </section>

          <div v-if="!items.length" class="empty">
            <h2>今天没有任务</h2>
            <p>去资料库加一份材料，或者回对话里接着聊。</p>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '../components/PageHeader.vue'
import { todos, startReview as startReviewApi } from '../api/todo.js'
import { generate } from '../api/quiz.js'
import { redirectOn401, messageOf } from '../utils/apiError.js'

const route = useRoute()
const router = useRouter()

const items = ref([])
const loading = ref(true)
const error = ref('')
// 正在执行的按钮标识：'review' | 'retry-<knowledgeItemId>' | null
const busy = ref(null)

const review = computed(() => items.value.find((i) => i.type === 'REVIEW') || null)
const retries = computed(() => items.value.filter((i) => i.type === 'RETRY'))
const untested = computed(() => items.value.filter((i) => i.type === 'UNTESTED'))

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await todos()
    items.value = data.items || []
  } catch (e) {
    if (redirectOn401(e, router, route)) return
    error.value = messageOf(e, '待办加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

async function startReview() {
  busy.value = 'review'
  error.value = ''
  try {
    // 幂等：上一轮没答完会回到同一会话，不会复制题
    const quiz = await startReviewApi()
    router.push({ name: 'quiz', params: { conversationId: quiz.conversationId } })
  } catch (e) {
    if (redirectOn401(e, router, route)) return
    error.value = messageOf(e, '现在还不能开始复习')
  } finally {
    busy.value = null
  }
}

// RETRY 只带 knowledgeItemId：点进去会按该资料出一轮（含重问那道未通过的题）
async function retry(item) {
  busy.value = 'retry-' + item.knowledgeItemId
  error.value = ''
  try {
    const quiz = await generate(item.knowledgeItemId)
    router.push({ name: 'quiz', params: { conversationId: quiz.conversationId } })
  } catch (e) {
    if (redirectOn401(e, router, route)) return
    error.value = messageOf(e, '出题失败，请稍后重试')
  } finally {
    busy.value = null
  }
}

// UNTESTED 已经带 conversationId，直接打开那轮测验，不用再出题
function open(item) {
  if (!item.conversationId) return
  router.push({ name: 'quiz', params: { conversationId: item.conversationId } })
}

onMounted(load)
</script>

<style scoped>
/* 设计令牌与资料页一致：深色底 + 青色强调，排版节奏参考苹果官网（发丝线分隔、层级靠字号） */
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
.todo-wrap {
  max-width: 860px;
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
.loading {
  margin: 0;
  padding: 48px 0;
  text-align: center;
  color: var(--ink-dim);
  font-size: 12px;
}

.overview {
  margin: 0 0 40px;
  font-family: 'IBM Plex Mono', monospace;
  font-size: 11.5px;
  letter-spacing: 0.5px;
  color: var(--ink-dim);
}
.sep {
  color: rgba(148, 163, 184, 0.3);
  margin: 0 2px;
}

.tgroup + .tgroup {
  margin-top: 48px;
}
.tgroup-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--line);
}
.tgroup-head .name {
  margin: 0;
  font-size: 17px;
  font-weight: 800;
  letter-spacing: -0.01em;
  color: var(--ink);
}
.tgroup-head .count {
  font-family: 'IBM Plex Mono', monospace;
  font-size: 11px;
  color: var(--ink-dim);
}

/* 行：不用卡片，靠发丝线分隔 */
.rows {
  margin: 0;
  padding: 0;
  list-style: none;
}
.row {
  display: grid;
  grid-template-columns: 12px minmax(0, 1fr) auto;
  gap: 18px;
  align-items: center;
  padding: 22px 4px;
  border-bottom: 1px solid var(--line);
  transition: background 0.2s var(--ease);
}
.row:hover {
  background: rgba(148, 163, 184, 0.035);
}
.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex: none;
  justify-self: center;
}
.dot.review {
  background: var(--amber);
  box-shadow: 0 0 0 3px rgba(251, 191, 36, 0.14);
}
.dot.retry {
  background: #f87171;
  box-shadow: 0 0 0 3px rgba(248, 113, 113, 0.14);
}
.dot.untested {
  background: #55677a;
}
.row-body {
  min-width: 0;
}
.row-body .main {
  margin: 0;
  font-size: 15.5px;
  font-weight: 700;
  line-height: 1.45;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row-body .sub {
  margin: 5px 0 0;
  font-family: 'IBM Plex Mono', monospace;
  font-size: 11px;
  color: var(--ink-dim);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

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

@media (max-width: 820px) {
  .inner {
    padding: 28px 18px 56px;
  }
  .overview {
    margin-bottom: 32px;
    line-height: 1.9;
  }
  .row {
    grid-template-columns: 12px minmax(0, 1fr);
    gap: 12px;
    align-items: start;
    padding: 18px 2px;
  }
  .row-body .main,
  .row-body .sub {
    white-space: normal;
  }
  .row-actions {
    grid-column: 2;
    padding-top: 12px;
  }
  .row-actions .btn {
    width: 100%;
  }
}
@media (prefers-reduced-motion: reduce) {
  .row,
  .btn {
    transition: none;
  }
}
</style>
