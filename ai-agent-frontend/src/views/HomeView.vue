<template>
  <div class="home">
    <header class="topbar">
      <span class="brand">STUDY HARNESS</span>
      <div class="top-right">
        <span class="tag">应用中心 · APPLICATION HUB</span>
        <!-- 登录态由 stores/auth.js 的 isAuthenticated 显式标记决定，不依赖 Cookie -->
        <template v-if="isAuthenticated">
          <span class="who">{{ currentUsername }}</span>
          <button type="button" class="logout-btn" :disabled="loggingOut" @click="handleLogout">
            {{ loggingOut ? '登出中…' : '登出' }}
          </button>
        </template>
        <RouterLink v-else to="/login" class="login-link">登录</RouterLink>
      </div>
    </header>

    <main class="grid">
      <!-- 两个入口并列，一句文案对一个功能：
           左「不会的，问出来」→ 引导式对话（不给答案，只给方向和提示）；
           右「要学的，读进去」→ 资料精读（切章 + 每章 3 题验收）。
           原「AI 超级智能体」(/manus) 已下线，入口并入左侧对话卡。 -->
      <div class="entries">
        <RouterLink to="/guide" class="card">
          <div class="card-inner">
            <span class="emoji">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"
                   stroke-linecap="round" stroke-linejoin="round">
                <path d="M21 11.5a8.4 8.4 0 0 1-9 8.4 9.6 9.6 0 0 1-3.3-.6L3 21l1.7-4.7A8.4 8.4 0 0 1 12 3.1a8.4 8.4 0 0 1 9 8.4z" />
              </svg>
            </span>
            <p class="kind">GUIDE · 引导式答疑</p>
            <h2>不会的，问出来</h2>
            <p class="desc">不直接给答案。先给方向，再给关键词，实在卡住才给最小示例。</p>
            <ul class="feats">
              <li>只给提示，不给结论</li>
              <li>直到你彻底搞清楚</li>
            </ul>
            <span class="enter">说说你卡在哪 <b>→</b></span>
          </div>
        </RouterLink>

        <RouterLink to="/materials" class="card">
          <div class="card-inner">
            <span class="emoji">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"
                   stroke-linecap="round" stroke-linejoin="round">
                <path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H19v18H6.5A2.5 2.5 0 0 1 4 18.5z" />
                <path d="M8 7h7M8 11h7M8 15h4" />
              </svg>
            </span>
            <p class="kind">MATERIALS · 资料学习</p>
            <h2>要学的，传进去</h2>
            <p class="desc">上传讲义、笔记或 PDF，按标题切成章节；读完一章立刻用 3 道题验收。</p>
            <ul class="feats">
              <li>按标题自动分章</li>
              <li>每章 3 题验收</li>
            </ul>
            <span class="enter">上传我的资料 <b>→</b></span>
          </div>
        </RouterLink>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { isAuthenticated, currentUsername, clearAuth } from '../stores/auth.js'
import { logout } from '../api/auth.js'

const loggingOut = ref(false)

async function handleLogout() {
  loggingOut.value = true
  try {
    await logout()
  } catch (e) {
    // 登出接口是幂等的 204，即使请求失败也不该把用户卡在"已登录"界面：
    // 前端标记照样清掉，服务端 session 最坏靠超时兜底。
    console.error('[logout]', e?.message || e)
  } finally {
    clearAuth()
    loggingOut.value = false
  }
}
</script>

<style scoped>
/* 浅色令牌：与 ChatGPT / DeepSeek 网页版一致 */
.home {
  --bg: #fcfcfc;
  --surface: #ffffff;
  --ink: #0d0d0d;
  --ink-soft: #565869;
  --ink-dim: #8e8ea0;
  --line: rgba(0, 0, 0, 0.1);
  --radius-lg: 16px;
  --radius-pill: 999px;
  --ease: cubic-bezier(0.22, 1, 0.36, 1);

  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--bg);
}

/* ---------- 顶栏 ---------- */
.topbar {
  flex: none;
  width: 100%;
  max-width: 1080px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 24px;
  color: var(--ink);
}
.brand {
  font-size: 13.5px;
  font-weight: 600;
  letter-spacing: 0.2px;
  white-space: nowrap;
}
.top-right {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}
.tag {
  font-size: 12px;
  color: var(--ink-dim);
  white-space: nowrap;
}
.who {
  font-size: 13px;
  color: var(--ink-soft);
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 顶栏两个胶囊按钮共用一套尺寸/观感，只有配色不同 */
.login-link,
.logout-btn {
  flex: none;
  padding: 7px 16px;
  border-radius: var(--radius-pill);
  background: #ffffff;
  border: 1px solid rgba(0, 0, 0, 0.14);
  font-size: 12.5px;
  font-family: inherit;
  line-height: 1;
  cursor: pointer;
  text-decoration: none;
  white-space: nowrap;
  transition: background 0.16s ease, color 0.16s ease, border-color 0.16s ease;
}
.login-link {
  color: var(--ink);
}
.login-link:hover {
  background: #f0f0f2;
}
.logout-btn {
  color: var(--ink-soft);
}
.logout-btn:not(:disabled):hover {
  background: #f0f0f2;
  color: var(--ink);
}
.logout-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

/* ---------- 内容区：水平 + 垂直双向居中 ---------- */
.grid {
  flex: 1;
  min-height: 0;
  display: flex;
  overflow-y: auto;
  padding: 0 24px 24px;
}

/* 两张入口卡并排；撑不满时整体居中，窄屏自动落成单列 */
.entries {
  margin: auto;
  width: 100%;
  max-width: 1000px;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

/* ---------- 入口卡：白面、发丝描边、极轻阴影 ---------- */
.card {
  position: relative;
  display: flex;
  width: 100%;
  height: auto;
  border-radius: var(--radius-lg);
  overflow: hidden;
  text-decoration: none;
  background: var(--surface);
  border: 1px solid var(--line);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
  transition: border-color 0.2s var(--ease), box-shadow 0.2s var(--ease);
}

.card-inner {
  position: relative;
  z-index: 2;
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: clamp(28px, 3.4vw, 40px) clamp(22px, 2.6vw, 32px);
}

.emoji {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 46px;
  border-radius: 13px;
  background: #0d0d0d;
  color: #ffffff;
  line-height: 1;
}
.emoji svg {
  width: 22px;
  height: 22px;
}

/* 功能名：给两张卡一个统一的「是哪一类」标签，避免只靠标题猜 */
.kind {
  margin: 16px 0 0;
  color: var(--ink-dim);
  font-size: 11.5px;
  letter-spacing: 0.06em;
}

h2 {
  margin: 8px 0 10px;
  color: var(--ink);
  font-size: clamp(20px, 2vw, 23px);
  font-weight: 400;
  line-height: 1.35;
  letter-spacing: -0.02em;
}

.card .desc {
  margin: 0;
  max-width: 30ch;
  color: var(--ink-soft);
  font-size: 13px;
  line-height: 1.75;
}

.feats {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
  list-style: none;
  padding: 0;
  margin: 16px 0 0;
}
.feats li {
  padding: 5px 11px;
  border-radius: var(--radius-pill);
  border: 1px solid rgba(0, 0, 0, 0.1);
  color: var(--ink-soft);
  font-size: 11.5px;
  transition: background 0.16s ease, color 0.16s ease;
}

.enter {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 22px;
  padding: 9px 18px;
  border-radius: var(--radius-pill);
  background: #0d0d0d;
  color: #ffffff;
  font-size: 12.5px;
  font-weight: 500;
}
.enter b {
  display: inline-block;
  font-weight: 400;
  transition: transform 0.25s var(--ease);
}

/* ---------- hover / 键盘焦点 ---------- */
.card:hover {
  border-color: rgba(0, 0, 0, 0.18);
  box-shadow: 0 6px 24px rgba(0, 0, 0, 0.06);
}
.card:hover .enter b {
  transform: translateX(4px);
}
.card:focus-visible {
  outline: 2px solid var(--ink);
  outline-offset: 3px;
}

/* ---------- 响应式：桌面 / 平板 / 手机 ---------- */
@media (max-width: 820px) {
  .topbar {
    padding: 16px 20px;
  }
  .tag {
    display: none;
  }
  .grid {
    padding: 0 20px 20px;
  }
  /* 窄屏落成单列，两卡仍保持统一节奏 */
  .entries {
    grid-template-columns: 1fr;
    gap: 12px;
  }
  .card {
    min-height: 0;
  }
}

@media (max-width: 480px) {
  .brand {
    font-size: 12.5px;
  }
  .who {
    max-width: 88px;
  }
  .card-inner {
    padding: 28px 20px;
  }
}

/* 尊重系统「减少动态效果」设置 */
@media (prefers-reduced-motion: reduce) {
  .card,
  .card-inner,
  .enter b,
  .feats li {
    transition: none;
  }
}
</style>
