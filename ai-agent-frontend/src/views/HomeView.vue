<template>
  <div class="home">
    <header class="topbar">
      <span class="brand">AI AGENT STUDY</span>
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
      <!-- 唯一入口：原「AI 超级智能体」(/manus) 已下线，入口并入这里。
           后端 /api/ai/manus/chat 与 LiManus 保留，待后续作为「工具调用」接入本页。 -->
      <RouterLink to="/guide" class="card guide">
        <div class="card-inner">
          <span class="emoji"><BrandMark /></span>
          <h2>授人以渔</h2>
          <p>不替你答，让你自己讲。</p>
          <ul class="feats">
            <li>把你自己的资料切成章节</li>
            <li>每章 3 道题，答完判你讲清楚没有</li>
            <li>答不出的，下次复习先问你</li>
          </ul>
          <span class="enter">进去讲两句 <b>→</b></span>
        </div>
      </RouterLink>
    </main>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { isAuthenticated, currentUsername, clearAuth } from '../stores/auth.js'
import { logout } from '../api/auth.js'
import BrandMark from '../components/BrandMark.vue'

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

/* ---------- 入口卡：白面、发丝描边、极轻阴影 ---------- */
.card {
  margin: auto;
  position: relative;
  display: flex;
  width: 100%;
  max-width: 720px;
  height: auto;
  border-radius: var(--radius-lg);
  overflow: hidden;
  text-decoration: none;
  background: var(--surface);
  border: 1px solid var(--line);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
  transition: border-color 0.2s var(--ease), box-shadow 0.2s var(--ease);
}
.card.guide {
  background: var(--surface);
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
  padding: clamp(32px, 5vw, 56px);
}

.emoji {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 52px;
  height: 52px;
  border-radius: 14px;
  background: #0d0d0d;
  color: #ffffff;
  font-size: 17px;
  font-weight: 600;
  letter-spacing: 0.5px;
  line-height: 1;
}

h2 {
  margin: 20px 0 12px;
  color: var(--ink);
  font-size: clamp(24px, 3vw, 30px);
  font-weight: 400;
  line-height: 1.35;
  letter-spacing: -0.02em;
}

.card p {
  margin: 0;
  max-width: 44ch;
  color: var(--ink-soft);
  font-size: 14px;
  line-height: 1.8;
}

.feats {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
  list-style: none;
  padding: 0;
  margin: 22px 0 0;
}
.feats li {
  padding: 6px 13px;
  border-radius: var(--radius-pill);
  border: 1px solid rgba(0, 0, 0, 0.1);
  color: var(--ink-soft);
  font-size: 12px;
  transition: background 0.16s ease, color 0.16s ease;
}

.enter {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 28px;
  padding: 10px 20px;
  border-radius: var(--radius-pill);
  background: #0d0d0d;
  color: #ffffff;
  font-size: 13px;
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
    padding: 36px 22px;
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
