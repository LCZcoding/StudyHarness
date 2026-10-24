<template>
  <div class="page">
    <PageHeader
      eyebrow="PROGRESS · 进度"
      title="你积累了多少"
      subtitle="已掌握的资料、连续天数、到期数量与近 8 周作答量。"
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

        <div v-if="!loading && data" class="wrap">
          <section class="stats">
            <div class="stat">
              <div class="label">MASTERED · 已掌握资料</div>
              <div class="value">{{ data.masteredItems }}<small> / {{ data.totalItems }}</small></div>
              <div class="foot">该资料下所有题最近一次都判 PASS</div>
            </div>
            <div class="stat">
              <div class="label">STREAK · 连续学习</div>
              <div class="value">{{ data.streakDays }}<small> 天</small></div>
              <div class="foot">按作答日期连续计算</div>
            </div>
            <div class="stat">
              <div class="label">DUE · 待复习</div>
              <div class="value">{{ data.dueCount }}<small> 道</small></div>
              <div class="foot">
                <RouterLink to="/todo" class="link">去待办清掉 →</RouterLink>
              </div>
            </div>
          </section>

          <section class="panel">
            <div class="panel-head">
              <h2>近 8 周作答量</h2>
              <span>按周统计 · 单位：次</span>
            </div>
            <div class="bars">
              <div v-for="week in data.weeks" :key="week.weekStart" class="bar-col">
                <span class="bar-num">{{ week.attempts }}</span>
                <div class="bar-track">
                  <div
                    :class="['bar', { zero: week.attempts === 0 }]"
                    :style="week.attempts === 0 ? 'height: 2px' : `height: ${barHeight(week.attempts)}%`"
                    :title="`${week.weekStart}：${week.attempts} 次`"
                  ></div>
                </div>
                <span class="bar-label">{{ shortWeek(week.weekStart) }}</span>
              </div>
            </div>
          </section>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '../components/PageHeader.vue'
import { summary } from '../api/progress.js'
import { redirectOn401, messageOf } from '../utils/apiError.js'

const route = useRoute()
const router = useRouter()

const data = ref(null)
const loading = ref(true)
const error = ref('')

// 用 8 周里的最大值做归一化：本周是 0 次时不能除 0，兜底成 1
const maxAttempts = computed(() =>
  Math.max(1, ...(data.value?.weeks || []).map((w) => w.attempts))
)

function barHeight(attempts) {
  return Math.max(2, Math.round((attempts / maxAttempts.value) * 100))
}

// 2026-10-20 → 10-20
function shortWeek(weekStart) {
  return weekStart ? weekStart.slice(5) : ''
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    data.value = await summary()
  } catch (e) {
    if (redirectOn401(e, router, route)) return
    error.value = messageOf(e, '进度加载失败，请稍后重试')
  } finally {
    loading.value = false
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
.wrap {
  max-width: 860px;
}

.btn {
  padding: 9px 18px;
  border-radius: var(--radius-pill);
  font-size: 13px;
  line-height: 1;
  border: 1px solid rgba(0, 0, 0, 0.14);
  color: var(--ink);
  background: #ffffff;
  transition: background 0.16s ease;
  white-space: nowrap;
}
.btn:hover {
  background: #f0f0f2;
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

/* ---------- 三个数字：竖线分隔的一栏，不用卡片 ---------- */
.stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
}
.stat {
  padding: 34px 28px;
}
.stat + .stat {
  border-left: 1px solid var(--line);
}
.stat:first-child {
  padding-left: 4px;
}
.stat .label {
  font-size: 11.5px;
  letter-spacing: 0.2px;
  color: var(--ink-dim);
}
.stat .value {
  margin-top: 12px;
  font-size: 38px;
  font-weight: 500;
  line-height: 1.1;
  letter-spacing: -0.02em;
  color: var(--ink);
}
.stat .value small {
  font-size: 13px;
  font-weight: 400;
  letter-spacing: 0;
  color: var(--ink-dim);
}
.stat .foot {
  margin-top: 12px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-soft);
}
.link {
  color: var(--brand-lit);
}
.link:hover {
  text-decoration: underline;
}

/* ---------- 柱状图：底部基线 + 发丝线，柱顶标数值 ---------- */
.panel {
  margin-top: 56px;
}
.panel-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--line);
}
.panel-head h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--ink);
}
.panel-head span {
  font-size: 11.5px;
  color: var(--ink-dim);
}

.bars {
  position: relative;
  display: flex;
  align-items: flex-end;
  gap: 12px;
  height: 200px;
  padding-top: 28px;
}
/* 贯穿所有柱子的同一条基线：用一条伪元素线，避免每列各自断开 */
.bars::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: 24px;
  height: 1px;
  background: var(--line);
}
.bar-col {
  flex: 1;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}
.bar-num {
  font-size: 10.5px;
  color: var(--ink-dim);
}
.bar-track {
  flex: 1;
  width: 100%;
  display: flex;
  align-items: flex-end;
  justify-content: center;
}
.bar {
  width: min(28px, 100%);
  border-radius: 3px 3px 0 0;
  background: #0d0d0d;
  min-height: 2px;
  transition: height 0.5s var(--ease);
}
.bar.zero {
  background: #e5e5e8;
}
.bar-label {
  font-size: 10.5px;
  line-height: 16px;
  color: var(--ink-dim);
}

@media (max-width: 820px) {
  .inner {
    padding: 28px 18px 56px;
  }
  .stats {
    grid-template-columns: minmax(0, 1fr);
  }
  .stat {
    padding: 22px 4px;
  }
  .stat + .stat {
    border-left: none;
    border-top: 1px solid var(--line);
  }
  .stat .value {
    font-size: 32px;
  }
  .panel {
    margin-top: 40px;
  }
  .bars {
    gap: 6px;
    height: 170px;
  }
  .bar-label {
    font-size: 9.5px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .bar,
  .bar-num,
  .btn {
    transition: none;
  }
}
</style>
