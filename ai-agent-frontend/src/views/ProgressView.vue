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
      <div v-if="error" class="banner">{{ error }}</div>
      <p v-if="loading" class="loading">加载中…</p>

      <div v-if="!loading && data" class="wrap">
        <div class="stats">
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
        </div>

        <div class="panel">
          <div class="panel-head">
            <h3>近 8 周作答量</h3>
            <span>按周统计 · 单位：次</span>
          </div>
          <div class="bars">
            <div v-for="week in data.weeks" :key="week.weekStart" class="bar-col">
              <div class="bar-track">
                <div
                  :class="['bar', { zero: week.attempts === 0 }]"
                  :style="week.attempts === 0 ? 'height: 2px' : `height: ${barHeight(week.attempts)}%`"
                  :title="`${week.weekStart}：${week.attempts} 次`"
                ></div>
              </div>
              <span>{{ shortWeek(week.weekStart) }}</span>
            </div>
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
.wrap { max-width: 860px; }

.btn {
  padding: 8px 16px;
  border-radius: 999px;
  font-size: 13px;
  line-height: 1;
  border: 1px solid rgba(148, 163, 184, 0.12);
  color: #dbe6f0;
  transition: background 0.18s ease;
  white-space: nowrap;
}
.btn:hover { background: rgba(148, 163, 184, 0.1); }
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

.stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 20px;
}
.stat {
  padding: 18px;
  border-radius: 16px;
  border: 1px solid rgba(148, 163, 184, 0.12);
  background: #141b23;
}
.stat .label {
  font-family: 'IBM Plex Mono', monospace;
  font-size: 11px;
  letter-spacing: 1.5px;
  color: #6b7f92;
}
.stat .value {
  margin-top: 8px;
  font-family: 'IBM Plex Mono', monospace;
  font-size: 30px;
  font-weight: 700;
  line-height: 1.2;
  color: #eef4fa;
}
.stat .value small {
  font-size: 13px;
  font-weight: 400;
  color: #6b7f92;
}
.stat .foot {
  margin-top: 6px;
  font-size: 12px;
  color: #8ba3b8;
}
.link { color: #22d3ee; }
.link:hover { text-decoration: underline; }

.panel {
  padding: 20px;
  border-radius: 16px;
  border: 1px solid rgba(148, 163, 184, 0.12);
  background: #141b23;
}
.panel-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 22px;
}
.panel-head h3 {
  margin: 0;
  font-size: 14px;
  color: #eef4fa;
}
.panel-head span {
  font-family: 'IBM Plex Mono', monospace;
  font-size: 11px;
  color: #6b7f92;
}

.bars {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  height: 150px;
}
.bar-col {
  flex: 1;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}
.bar-track {
  flex: 1;
  width: 100%;
  display: flex;
  align-items: flex-end;
}
.bar {
  width: 100%;
  border-radius: 5px 5px 2px 2px;
  background: linear-gradient(180deg, rgba(34, 211, 238, 0.85), rgba(2, 132, 199, 0.5));
  min-height: 2px;
  transition: height 0.4s cubic-bezier(0.22, 1, 0.36, 1);
}
.bar.zero { background: rgba(148, 163, 184, 0.22); }
.bar-col span {
  font-family: 'IBM Plex Mono', monospace;
  font-size: 10px;
  color: #6b7f92;
}

@media (max-width: 1080px) {
  .stats { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 820px) {
  .content { padding: 18px; }
  .stats { grid-template-columns: minmax(0, 1fr); }
}
</style>
