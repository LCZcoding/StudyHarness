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
      <div v-if="error" class="banner">{{ error }}</div>
      <p v-if="loading" class="loading">加载中…</p>

      <div v-if="!loading && quiz" class="quiz-wrap">
        <div class="quiz-head">
          <span>{{ quiz.conversationId.slice(0, 8) }} · 本轮测验</span>
          <span class="done">共 {{ quiz.questions.length }} 道 · 已完成 {{ doneCount }}</span>
        </div>

        <article
          v-for="(q, index) in quiz.questions"
          :key="q.questionId"
          :class="['qcard', { answered: verdictOf(q) }]"
        >
          <div class="qcard-head">
            <span :class="['kind', { explain: q.kind === 'EXPLAIN' }]">{{ q.kind }}</span>
            <span class="qtarget">{{ q.target }}</span>
            <span class="qno">{{ index + 1 }}</span>
          </div>
          <p class="qtext">{{ q.text }}</p>

          <div class="qbody">
            <!-- 已判分：恢复出来的题只有 verdict，本次作答过的有完整结果 -->
            <div v-if="verdictOf(q)" class="verdict">
              <div :class="['verdict-band', verdictClass(verdictOf(q))]"></div>
              <div class="verdict-body">
                <div class="verdict-line">
                  <span :class="['tag', verdictClass(verdictOf(q))]">{{ verdictOf(q) }}</span>
                  <span v-if="stateOf(q).restored" class="restored">历史判分（本轮未作答）</span>
                </div>
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
        <strong>没有找到这一轮测验</strong>
        回资料库重新出题，或者去待办开始复习。
      </div>
    </main>

    <!-- 求助抽屉：不产生成绩，只把这题标记为「求助过」 -->
    <div v-if="helpOpen" class="drawer-mask" @click.self="helpOpen = false">
      <aside class="drawer">
        <h3>求助：{{ helpTarget?.target }}</h3>
        <p class="hint">求助不产生成绩，只把这题标记为「求助过」，复习时会优先排</p>
        <div class="qa">
          <div v-for="(m, i) in helpMessages" :key="i" :class="['bubble', m.role === 'me' ? 'me' : 'ai']">
            <span class="who">{{ m.role === 'me' ? '我' : '引导助手' }}</span>{{ m.content }}
          </div>
          <p v-if="!helpMessages.length" class="drawer-empty">先问一个具体卡住你的点，比如「这个参数作用在哪个阶段？」</p>
        </div>
        <div class="drawer-foot">
          <input v-model="helpQuestion" maxlength="500" placeholder="输入你的问题…" @keyup.enter="sendHelp" />
          <button type="button" class="btn primary" :disabled="helpSending || !helpQuestion.trim()" @click="sendHelp">
            {{ helpSending ? '回答中…' : '提问' }}
          </button>
        </div>
        <p v-if="helpError" class="qerror">{{ helpError }}</p>
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

const verdictClass = (v) => ({ PASS: 'pass', PARTIAL: 'partial', FAIL: 'fail' }[v] || 'fail')

const doneCount = computed(
  () => (quiz.value ? quiz.value.questions.filter((q) => verdictOf(q)).length : 0)
)

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

.quiz-wrap {
  max-width: 780px;
  margin: 0 auto;
}
.quiz-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 18px;
  font-family: 'IBM Plex Mono', monospace;
  font-size: 11px;
  letter-spacing: 1px;
  color: #6b7f92;
}
.quiz-head .done { color: #22d3ee; }

.qcard {
  margin-bottom: 16px;
  border-radius: 16px;
  border: 1px solid rgba(148, 163, 184, 0.12);
  background: #141b23;
  overflow: hidden;
}
.qcard-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 18px 0;
}
.kind {
  padding: 3px 9px;
  border-radius: 6px;
  font-family: 'IBM Plex Mono', monospace;
  font-size: 10px;
  letter-spacing: 1px;
  border: 1px solid rgba(34, 211, 238, 0.3);
  color: #22d3ee;
}
.kind.explain {
  border-color: rgba(168, 85, 247, 0.4);
  color: #c4a5f7;
}
.qtarget {
  font-family: 'IBM Plex Mono', monospace;
  font-size: 11px;
  color: #6b7f92;
  margin-left: auto;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.qno {
  flex: none;
  width: 20px;
  height: 20px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  border: 1px solid rgba(148, 163, 184, 0.2);
  font-family: 'IBM Plex Mono', monospace;
  font-size: 10px;
  color: #6b7f92;
}
.qtext {
  margin: 10px 18px 14px;
  font-size: 15px;
  line-height: 1.8;
  color: #eef4fa;
}
.qcard.answered .qtext { color: #8ba3b8; }

.qbody { padding: 0 18px 18px; }
.qbody textarea {
  width: 100%;
  min-height: 82px;
  resize: vertical;
  padding: 12px 14px;
  border-radius: 10px;
  border: 1px solid rgba(148, 163, 184, 0.12);
  background: #0f151c;
  color: #dbe6f0;
  line-height: 1.7;
  outline: none;
  transition: border-color 0.18s ease;
}
.qbody textarea:focus { border-color: rgba(34, 211, 238, 0.45); }
.qbody textarea::placeholder { color: #53687a; }
.qbody textarea:disabled { opacity: 0.6; }

.qrow {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 12px;
}
.qrow .left { display: flex; gap: 8px; }
.quota {
  font-family: 'IBM Plex Mono', monospace;
  font-size: 11px;
  color: #6b7f92;
}
.quota.warn { color: #fbbf24; }
.qerror {
  margin: 8px 0 0;
  font-size: 12.5px;
  color: #f9a8a8;
}

.verdict { border-top: 1px solid rgba(148, 163, 184, 0.12); }
.verdict-band { height: 3px; }
.verdict-band.pass { background: #34d399; }
.verdict-band.partial { background: #fbbf24; }
.verdict-band.fail { background: #f87171; }
.verdict-body { padding: 14px 18px 18px; }
.verdict-line {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.tag {
  padding: 2px 9px;
  border-radius: 999px;
  font-family: 'IBM Plex Mono', monospace;
  font-size: 10px;
  letter-spacing: 1px;
}
.tag.pass { background: rgba(52, 211, 153, 0.14); color: #34d399; }
.tag.partial { background: rgba(251, 191, 36, 0.14); color: #fbbf24; }
.tag.fail { background: rgba(248, 113, 113, 0.14); color: #f87171; }
.restored {
  font-family: 'IBM Plex Mono', monospace;
  font-size: 10px;
  color: #6b7f92;
}
.feedback {
  margin: 0;
  font-size: 13px;
  color: #dbe6f0;
  line-height: 1.75;
}
.gaps {
  margin: 12px 0 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.gaps li {
  display: flex;
  gap: 8px;
  padding: 7px 11px;
  border-radius: 8px;
  background: rgba(251, 191, 36, 0.06);
  border: 1px dashed rgba(251, 191, 36, 0.22);
  font-size: 12.5px;
  color: #d8c690;
}
.gaps li b {
  flex: none;
  font-weight: 400;
  color: #fbbf24;
  font-family: 'IBM Plex Mono', monospace;
  font-size: 10px;
}

.empty {
  max-width: 780px;
  margin: 0 auto;
  padding: 60px 20px;
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

/* 求助抽屉 */
.drawer-mask {
  position: fixed;
  inset: 0;
  z-index: 20;
  background: rgba(4, 8, 12, 0.6);
  backdrop-filter: blur(2px);
}
.drawer {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  width: min(420px, 88vw);
  display: flex;
  flex-direction: column;
  padding: 24px;
  background: #101820;
  border-left: 1px solid rgba(34, 211, 238, 0.14);
  overflow-y: auto;
}
.drawer h3 {
  margin: 0 0 4px;
  font-size: 15px;
  color: #eef4fa;
}
.drawer .hint {
  margin: 0 0 18px;
  font-family: 'IBM Plex Mono', monospace;
  font-size: 11px;
  color: #6b7f92;
}
.qa {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.bubble {
  padding: 10px 13px;
  border-radius: 12px;
  font-size: 13px;
  line-height: 1.75;
  white-space: pre-wrap;
}
.bubble.me {
  align-self: flex-end;
  background: rgba(2, 132, 199, 0.22);
  color: #cfe9f7;
}
.bubble.ai {
  background: #111b27;
  border: 1px solid rgba(148, 163, 184, 0.12);
  color: #dbe6f0;
}
.bubble .who {
  display: block;
  margin-bottom: 4px;
  font-family: 'IBM Plex Mono', monospace;
  font-size: 10px;
  color: #6b7f92;
}
.drawer-empty {
  margin: 0;
  font-size: 12.5px;
  color: #6b7f92;
  line-height: 1.8;
}
.drawer-foot {
  display: flex;
  gap: 8px;
  margin-top: auto;
  padding-top: 16px;
}
.drawer-foot input {
  flex: 1;
  min-width: 0;
  padding: 10px 12px;
  border-radius: 10px;
  border: 1px solid rgba(148, 163, 184, 0.12);
  background: #0f151c;
  color: #dbe6f0;
  outline: none;
}
.drawer-foot input:focus { border-color: rgba(34, 211, 238, 0.45); }

@media (max-width: 820px) {
  .content { padding: 18px; }
}
</style>
