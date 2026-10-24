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
.wrap {
  max-width: 860px;
}

.btn {
  padding: 9px 18px;
  border-radius: var(--radius-pill);
  font-size: 13px;
  line-height: 1;
  border: 1px solid var(--line);
  color: var(--ink);
  background: transparent;
  transition: background 0.2s var(--ease);
  white-space: nowrap;
}
.btn:hover {
  background: rgba(148, 163, 184, 0.1);
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
  font-family: 'IBM Plex Mono', monospace;
  font-size: 11px;
  letter-spacing: 1.5px;
  color: var(--ink-dim);
}
.stat .value {
  margin-top: 14px;
  font-family: 'IBM Plex Mono', monospace;
  font-size: 40px;
  font-weight: 700;
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
  font-size: 17px;
  font-weight: 800;
  letter-spacing: -0.01em;
  color: var(--ink);
}
.panel-head span {
  font-family: 'IBM Plex Mono', monospace;
  font-size: 11px;
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
  font-family: 'IBM Plex Mono', monospace;
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
  background: linear-gradient(180deg, rgba(34, 211, 238, 0.85), rgba(2, 132, 199, 0.45));
  min-height: 2px;
  transition: height 0.5s var(--ease);
}
.bar.zero {
  background: rgba(148, 163, 184, 0.28);
}
.bar-label {
  font-family: 'IBM Plex Mono', monospace;
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
