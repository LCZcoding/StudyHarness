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
      <div v-if="error" class="banner">{{ error }}</div>
      <p v-if="loading" class="loading">加载中…</p>

      <div v-if="!loading" class="todo-wrap">
        <!-- REVIEW：后端只给一条汇总，count 是到期题数 -->
        <section v-if="review" class="tgroup">
          <div class="tgroup-head">
            <span class="name">需要复习</span>
            <span class="count">{{ review.count }} 道到期</span>
          </div>
          <div class="trow">
            <div class="ticon review">↻</div>
            <div class="ttext">
              <div class="main">到期复习</div>
              <div class="sub">到期题可能跨资料，点进去一次答完</div>
            </div>
            <button type="button" class="btn primary" :disabled="busy" @click="startReview">
              {{ busy === 'review' ? '准备中…' : '开始复习' }}
            </button>
          </div>
        </section>

        <section v-if="retries.length" class="tgroup">
          <div class="tgroup-head">
            <span class="name">需要补讲</span>
            <span class="count">{{ retries.length }} 份资料</span>
          </div>
          <div v-for="item in retries" :key="'retry-' + item.knowledgeItemId" class="trow">
            <div class="ticon retry">!</div>
            <div class="ttext">
              <div class="main">{{ item.title }}</div>
              <div class="sub">{{ item.count }} 道未通过 · 点进去会重问同一知识点</div>
            </div>
            <button
              type="button"
              class="btn ghost"
              :disabled="busy !== null"
              @click="retry(item)"
            >
              {{ busy === 'retry-' + item.knowledgeItemId ? '出题中…' : '去补讲' }}
            </button>
          </div>
        </section>

        <section v-if="untested.length" class="tgroup">
          <div class="tgroup-head">
            <span class="name">还没测过</span>
            <span class="count">{{ untested.length }} 份资料</span>
          </div>
          <div v-for="item in untested" :key="'untested-' + item.knowledgeItemId" class="trow">
            <div class="ticon untested">○</div>
            <div class="ttext">
              <div class="main">{{ item.title }}</div>
              <div class="sub">{{ item.count }} 道题未作答</div>
            </div>
            <button type="button" class="btn ghost" @click="open(item)">开始测验</button>
          </div>
        </section>

        <div v-if="!items.length" class="empty">
          <strong>今天没有任务</strong>
          去资料库加一份材料，或者回对话里接着聊。
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
.page {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #0e1116;
}
.content {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 24px 32px 44px;
}
.todo-wrap { max-width: 860px; }

.btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  border-radius: 999px;
  font-size: 13px;
  line-height: 1;
  border: 1px solid rgba(148, 163, 184, 0.12);
  color: #dbe6f0;
  transition: background 0.18s ease, border-color 0.18s ease;
  white-space: nowrap;
}
.btn:hover { background: rgba(148, 163, 184, 0.1); }
.btn.primary {
  background: linear-gradient(140deg, #0284c7, #0ea5c9);
  border-color: transparent;
  color: #04121b;
  font-weight: 700;
}
.btn.primary:hover { filter: brightness(1.08); }
.btn.ghost { border-color: rgba(34, 211, 238, 0.35); color: #22d3ee; }
.btn.ghost:hover { background: rgba(34, 211, 238, 0.12); }
.btn:disabled { opacity: 0.45; cursor: not-allowed; }

.banner {
  margin-bottom: 16px;
  padding: 10px 14px;
  border-radius: 10px;
  border: 1px solid rgba(248, 113, 113, 0.35);
  background: rgba(248, 113, 113, 0.08);
  color: #f9a8a8;
  font-size: 13px;
}
.loading {
  margin: 0;
  padding: 40px 0;
  text-align: center;
  color: #6b7f92;
  font-size: 12px;
}

.tgroup { margin-bottom: 18px; }
.tgroup-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
  padding: 0 4px;
}
.tgroup-head .name {
  font-size: 13px;
  font-weight: 700;
  color: #eef4fa;
}
.tgroup-head .count {
  font-family: 'IBM Plex Mono', monospace;
  font-size: 11px;
  color: #6b7f92;
}

.trow {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 15px 16px;
  margin-bottom: 8px;
  border-radius: 12px;
  border: 1px solid rgba(148, 163, 184, 0.12);
  background: #141b23;
  transition: border-color 0.18s ease, background 0.18s ease;
}
.trow:hover {
  border-color: rgba(34, 211, 238, 0.3);
  background: #16202a;
}
.ticon {
  flex: none;
  width: 36px;
  height: 36px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  font-size: 16px;
  background: #111b27;
  border: 1px solid rgba(148, 163, 184, 0.12);
}
.ticon.review {
  color: #fbbf24;
  border-color: rgba(251, 191, 36, 0.3);
  background: rgba(251, 191, 36, 0.07);
}
.ticon.retry {
  color: #f87171;
  border-color: rgba(248, 113, 113, 0.3);
  background: rgba(248, 113, 113, 0.07);
}
.ticon.untested {
  color: #22d3ee;
  border-color: rgba(34, 211, 238, 0.3);
  background: rgba(34, 211, 238, 0.07);
}
.ttext { flex: 1; min-width: 0; }
.ttext .main {
  font-size: 14px;
  color: #eef4fa;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ttext .sub {
  margin-top: 2px;
  font-family: 'IBM Plex Mono', monospace;
  font-size: 11px;
  color: #6b7f92;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.empty {
  padding: 40px 20px;
  text-align: center;
  border: 1px dashed rgba(148, 163, 184, 0.12);
  border-radius: 16px;
  color: #6b7f92;
  font-size: 13px;
}
.empty strong {
  display: block;
  margin-bottom: 6px;
  color: #dbe6f0;
  font-size: 15px;
}

@media (max-width: 820px) {
  .content { padding: 18px; }
  .trow { flex-wrap: wrap; }
}
</style>
