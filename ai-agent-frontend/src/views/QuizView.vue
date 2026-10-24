<template>
  <div class="page">
    <PageHeader
      eyebrow="QUIZ · 答题"
      title="写出你的理解"
      subtitle="每题最多提交 2 次：初答 + 补讲。判完即定，第 3 次由服务端拒绝。"
    >
      <template #actions>
        <button type="button" class="btn" @click="router.push('/materials')">返回资料库</button>
        <button type="button" class="btn ghost" @click="load">刷新</button>
      </template>
    </PageHeader>

    <main class="content">
      <div class="inner">
        <div v-if="error" class="banner">{{ error }}</div>
        <p v-if="loading" class="loading">加载中…</p>

        <div v-if="!loading && quiz" class="quiz-wrap">
          <div class="quiz-head">
            <span class="mono">{{ quiz.conversationId.slice(0, 8) }} · 本轮测验</span>
            <span class="mono done">共 {{ quiz.questions.length }} 道 · 已完成 {{ doneCount }}</span>
          </div>
          <div class="quiz-progress">
            <i :style="{ width: donePercent }"></i>
          </div>

          <article v-for="(q, index) in quiz.questions" :key="q.questionId" class="q">
            <span class="q-no">{{ String(index + 1).padStart(2, '0') }}</span>

            <div class="q-body">
              <div class="q-meta">
                <span :class="['kind', { explain: q.kind === 'EXPLAIN' }]">{{ q.kind }}</span>
                <span class="qtarget">{{ q.target }}</span>
                <span v-if="verdictOf(q)" :class="['tag', verdictClass(verdictOf(q))]">
                  {{ verdictOf(q) }}
                </span>
              </div>
              <p class="qtext">{{ q.text }}</p>

              <!-- 已判分：恢复出来的题只有 verdict，本次作答过的有完整结果 -->
              <div v-if="verdictOf(q)" class="verdict">
                <div v-if="stateOf(q).restored" class="restored">历史判分（本轮未作答）</div>
                <p v-if="stateOf(q).result?.feedback" class="feedback">
                  {{ stateOf(q).result.feedback }}
                </p>
                <ul v-if="stateOf(q).result?.gaps?.length" class="gaps">
                  <li v-for="(gap, i) in stateOf(q).result.gaps" :key="i">
                    <b>缺口 {{ String(i + 1).padStart(2, '0') }}</b>{{ gap }}
                  </li>
                </ul>
                <div v-if="stateOf(q).result" class="qrow">
                  <div class="left">
                    <button
                      v-if="stateOf(q).result.retryAllowed"
                      type="button"
                      class="btn ghost"
                      @click="startRetry(q)"
                    >
                      补讲一次
                    </button>
                    <button type="button" class="btn" @click="openHelp(q)">求助</button>
                  </div>
                  <span :class="['quota', { warn: stateOf(q).result.retryAllowed }]">
                    {{ stateOf(q).result.retryAllowed ? '剩余 1 次' : '已用完' }}
                  </span>
                </div>
              </div>

              <!-- 未作答 / 补讲中 -->
              <template v-else>
                <textarea
                  v-model="stateOf(q).answer"
                  maxlength="5000"
                  :disabled="stateOf(q).submitting"
                  :placeholder="stateOf(q).retrying ? '补齐上一轮缺口，再提交一次…' : '用自己的话讲清楚，不要只写结论…'"
                ></textarea>
                <p v-if="stateOf(q).submitError" class="qerror">{{ stateOf(q).submitError }}</p>
                <div class="qrow">
                  <div class="left">
                    <button
                      type="button"
                      class="btn primary"
                      :disabled="stateOf(q).submitting || !stateOf(q).answer.trim()"
                      @click="submit(q)"
                    >
                      {{ stateOf(q).submitting ? '判分中…' : '提交作答' }}
                    </button>
                    <button type="button" class="btn" @click="openHelp(q)">求助</button>
                  </div>
                  <span class="quota">剩余 {{ 2 - stateOf(q).attemptNo }} 次</span>
                </div>
              </template>
            </div>
          </article>
        </div>

        <div v-if="!loading && !quiz" class="empty">
          <h2>没有找到这一轮测验</h2>
          <p>回资料库重新出题，或者去待办开始复习。</p>
        </div>
      </div>
    </main>

    <!-- 求助抽屉：不产生成绩，只把这题标记为「求助过」 -->
    <div v-if="helpOpen" class="drawer-mask" @click.self="helpOpen = false">
      <aside class="drawer">
        <h3>求助：{{ helpTarget?.target }}</h3>
        <p class="hint">求助不产生成绩，只把这题标记为「求助过」，复习时会优先排</p>
        <div class="qa">
          <div v-for="(m, i) in helpMessages" :key="i" :class="['bubble', m.role === 'me' ? 'me' : 'ai']">
            <span class="who">{{ m.role === 'me' ? '我' : '渔' }}</span>{{ m.content }}
          </div>
          <p v-if="!helpMessages.length" class="drawer-empty">先问一个具体卡住你的点，比如「这个参数作用在哪个阶段？」</p>
        </div>
        <p v-if="helpError" class="qerror">{{ helpError }}</p>
        <div class="drawer-foot">
          <input v-model="helpQuestion" maxlength="500" placeholder="输入你的问题…" @keyup.enter="sendHelp" />
          <button type="button" class="btn primary" :disabled="helpSending || !helpQuestion.trim()" @click="sendHelp">
            {{ helpSending ? '回答中…' : '提问' }}
          </button>
        </div>
      </aside>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '../components/PageHeader.vue'
import { conversation, attempt, help } from '../api/quiz.js'
import { redirectOn401, messageOf } from '../utils/apiError.js'

const route = useRoute()
const router = useRouter()

const quiz = ref(null)
const loading = ref(true)
const error = ref('')

// 每题的本地作答状态，key 是 questionId。
// 不用 ref 数组下标：题目列表刷新后顺序可能变，按下标存会错位。
const states = reactive({})

function stateOf(q) {
  if (!states[q.questionId]) {
    states[q.questionId] = {
      answer: '',
      submitting: false,
      submitError: '',
      attemptNo: 0,
      retrying: false,
      // restored 为 true 时只有 verdict 一个字段，没有 feedback / gaps
      restored: false,
      result: null
    }
  }
  return states[q.questionId]
}

function verdictOf(q) {
  const local = stateOf(q).result
  return local?.verdict || q.verdict || null
}

// SKIPPED 是跳过，不是判失败：给中性灰，避免和 FAIL 的红混在一起
const verdictClass = (v) =>
  ({ PASS: 'pass', PARTIAL: 'partial', FAIL: 'fail', SKIPPED: 'skipped' }[v] || 'skipped')

const doneCount = computed(
  () => (quiz.value ? quiz.value.questions.filter((q) => verdictOf(q)).length : 0)
)
const donePercent = computed(() => {
  const total = quiz.value?.questions.length || 0
  return total ? `${Math.round((doneCount.value / total) * 100)}%` : '0%'
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await conversation(route.params.conversationId)
    quiz.value = data
    // 恢复出来的题：有 verdict 但没有 feedback / gaps，标成历史判分只读
    for (const q of data.questions) {
      const s = stateOf(q)
      if (q.verdict && !s.result) {
        s.restored = true
        s.attemptNo = 2
      }
    }
  } catch (e) {
    if (redirectOn401(e, router, route)) return
    error.value = messageOf(e, '测验加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

async function submit(q) {
  const s = stateOf(q)
  s.submitting = true
  s.submitError = ''
  try {
    const result = await attempt(q.questionId, s.answer.trim())
    s.result = result
    s.attemptNo = result.attemptNo
    s.retrying = false
    s.answer = ''
  } catch (e) {
    if (redirectOn401(e, router, route)) return
    s.submitError = messageOf(e, '提交失败，请稍后重试')
  } finally {
    s.submitting = false
  }
}

// 补讲：清空旧答案并聚焦输入框，提交次数由服务端判定
function startRetry(q) {
  const s = stateOf(q)
  s.result = null
  s.restored = false
  s.retrying = true
  s.answer = ''
}

/* ---------- 求助抽屉 ---------- */
const helpOpen = ref(false)
const helpTarget = ref(null)
const helpMessages = ref([])
const helpQuestion = ref('')
const helpSending = ref(false)
const helpError = ref('')

function openHelp(q) {
  helpTarget.value = q
  helpMessages.value = []
  helpQuestion.value = ''
  helpError.value = ''
  helpOpen.value = true
}

async function sendHelp() {
  const question = helpQuestion.value.trim()
  if (!question || helpSending.value) return
  helpMessages.value.push({ role: 'me', content: question })
  helpQuestion.value = ''
  helpError.value = ''
  helpSending.value = true
  try {
    const data = await help(helpTarget.value.questionId, question)
    helpMessages.value.push({ role: 'ai', content: data.answer })
  } catch (e) {
    if (redirectOn401(e, router, route)) return
    helpError.value = messageOf(e, '求助失败，请稍后重试')
  } finally {
    helpSending.value = false
  }
}

onMounted(load)
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
  --good: #10a37f;
  --warn: #d97706;
  --bad: #b4341a;
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
  padding: 32px 32px 72px;
}
.quiz-wrap {
  max-width: 800px;
  margin: 0 auto;
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
  margin-bottom: 20px;
  padding: 12px 16px;
  border-radius: 12px;
  border: 1px solid rgba(0, 0, 0, 0.1);
  background: #fff7ed;
  color: #b4341a;
  font-size: 13px;
}
.loading {
  margin: 0;
  padding: 48px 0;
  text-align: center;
  color: var(--ink-dim);
  font-size: 12px;
}

/* ---------- 轮次抬头 + 进度线 ---------- */
.mono {
  font-size: 11.5px;
  letter-spacing: 0.2px;
  color: var(--ink-dim);
}
.quiz-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}
.quiz-head .done {
  color: var(--ink-soft);
}
.quiz-progress {
  height: 3px;
  margin: 14px 0 8px;
  border-radius: var(--radius-pill);
  background: rgba(0, 0, 0, 0.07);
  overflow: hidden;
}
.quiz-progress i {
  display: block;
  height: 100%;
  border-radius: var(--radius-pill);
  background: #0d0d0d;
  transition: width 0.5s var(--ease);
}

/* ---------- 每题一节：编号 + 发丝线分隔 ---------- */
.q {
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr);
  gap: 20px;
  padding: 32px 4px 30px;
  border-bottom: 1px solid var(--line);
}
.q-no {
  padding-top: 4px;
  font-size: 12px;
  color: var(--ink-dim);
}
.q-body {
  min-width: 0;
}
.q-meta {
  display: flex;
  align-items: center;
  gap: 10px;
}
.kind {
  padding: 3px 9px;
  border-radius: 6px;
  font-size: 10.5px;
  letter-spacing: 0.4px;
  border: 1px solid rgba(0, 0, 0, 0.14);
  color: var(--ink-soft);
}
.kind.explain {
  border-color: rgba(77, 107, 254, 0.28);
  color: #4d6bfe;
}
.qtarget {
  flex: 1 1 0;
  min-width: 0;
  font-size: 11.5px;
  color: var(--ink-dim);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tag {
  flex: none;
  margin-left: auto;
  padding: 3px 10px;
  border-radius: var(--radius-pill);
  font-size: 10.5px;
  letter-spacing: 0.4px;
}
.tag.pass {
  background: rgba(16, 163, 127, 0.1);
  color: var(--good);
}
.tag.partial {
  background: rgba(217, 119, 6, 0.1);
  color: var(--warn);
}
.tag.fail {
  background: rgba(180, 52, 26, 0.1);
  color: var(--bad);
}
.tag.skipped {
  background: rgba(0, 0, 0, 0.05);
  color: var(--ink-soft);
}
.qtext {
  margin: 14px 0 18px;
  font-size: 16.5px;
  font-weight: 500;
  line-height: 1.75;
  letter-spacing: -0.01em;
  color: var(--ink);
}

.q-body textarea {
  width: 100%;
  min-height: 96px;
  resize: vertical;
  padding: 13px 15px;
  border-radius: 12px;
  border: 1px solid rgba(0, 0, 0, 0.12);
  background: #ffffff;
  color: var(--ink);
  font-size: 14px;
  line-height: 1.8;
  outline: none;
  transition: border-color 0.16s ease;
}
.q-body textarea:focus {
  border-color: rgba(0, 0, 0, 0.32);
}
.q-body textarea::placeholder {
  color: #9b9ba8;
}
.q-body textarea:disabled {
  opacity: 0.6;
}

.qrow {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 14px;
}
.qrow .left {
  display: flex;
  gap: 8px;
}
.quota {
  font-size: 11.5px;
  color: var(--ink-dim);
}
.quota.warn {
  color: var(--warn);
}
.qerror {
  margin: 10px 0 0;
  font-size: 12.5px;
  color: #b4341a;
}

/* ---------- 判分结果 ---------- */
.verdict {
  padding-top: 18px;
  border-top: 1px solid var(--line);
}
.restored {
  margin-bottom: 10px;
  font-size: 11px;
  color: var(--ink-dim);
}
.feedback {
  margin: 0;
  font-size: 13.5px;
  line-height: 1.85;
  color: #33343a;
}
.gaps {
  margin: 14px 0 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.gaps li {
  display: flex;
  gap: 10px;
  padding: 9px 12px;
  border-left: 2px solid rgba(217, 119, 6, 0.45);
  font-size: 12.5px;
  line-height: 1.75;
  color: #5c4a22;
}
.gaps li b {
  flex: none;
  font-weight: 500;
  color: var(--warn);
  font-size: 10.5px;
}

.empty {
  max-width: 800px;
  margin: 0 auto;
  padding: 88px 24px;
  text-align: center;
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
}
.empty h2 {
  margin: 0 0 10px;
  font-size: 24px;
  font-weight: 400;
  letter-spacing: -0.01em;
  color: var(--ink);
}
.empty p {
  margin: 0;
  font-size: 13.5px;
  line-height: 1.8;
  color: var(--ink-dim);
}

/* ---------- 求助抽屉 ---------- */
.drawer-mask {
  position: fixed;
  inset: 0;
  z-index: 20;
  background: rgba(0, 0, 0, 0.35);
  backdrop-filter: blur(2px);
}
.drawer {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  width: min(420px, 100vw);
  display: flex;
  flex-direction: column;
  padding: 24px;
  background: #ffffff;
  border-left: 1px solid var(--line);
  overflow-y: auto;
}
.drawer h3 {
  margin: 0 0 6px;
  font-size: 17px;
  font-weight: 600;
  color: var(--ink);
}
.drawer .hint {
  margin: 0 0 20px;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--ink-dim);
}
.qa {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.bubble {
  padding: 11px 14px;
  border-radius: 14px;
  font-size: 13px;
  line-height: 1.8;
  white-space: pre-wrap;
}
.bubble.me {
  align-self: flex-end;
  background: #f4f4f5;
  color: #0d0d0d;
}
.bubble.ai {
  background: #ffffff;
  border: 1px solid var(--line);
  color: #33343a;
}
.bubble .who {
  display: block;
  margin-bottom: 4px;
  font-size: 10.5px;
  color: var(--ink-dim);
}
.drawer-empty {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.8;
  color: var(--ink-dim);
}
.drawer-foot {
  display: flex;
  gap: 8px;
  margin-top: auto;
  padding-top: 18px;
}
.drawer-foot input {
  flex: 1;
  min-width: 0;
  padding: 10px 14px;
  border-radius: var(--radius-pill);
  border: 1px solid rgba(0, 0, 0, 0.12);
  background: #ffffff;
  color: var(--ink);
  outline: none;
  transition: border-color 0.16s ease;
}
.drawer-foot input:focus {
  border-color: rgba(0, 0, 0, 0.32);
}

@media (max-width: 820px) {
  .inner {
    padding: 28px 18px 56px;
  }
  .q {
    grid-template-columns: 28px minmax(0, 1fr);
    gap: 12px;
    padding: 26px 2px 24px;
  }
  .qtext {
    font-size: 16px;
  }
  .q-meta {
    flex-wrap: wrap;
  }
  .qrow {
    align-items: flex-start;
    flex-direction: column;
  }
  .qrow .left {
    width: 100%;
  }
  .qrow .left .btn {
    flex: 1;
  }
  .drawer {
    width: 100vw;
    border-left: none;
  }
}
@media (prefers-reduced-motion: reduce) {
  .btn,
  .quiz-progress i {
    transition: none;
  }
}
</style>
