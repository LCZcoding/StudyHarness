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
          <span class="emoji">❓</span>
          <h2>AI 问题引导解决助手</h2>
          <p>你的专属答疑助手，引导你解决问题，陪伴你一起解决每一个问题。</p>
          <ul class="feats">
            <li>问题解决</li>
            <li>学习辅助</li>
          </ul>
          <span class="enter">进入答疑区 <b>→</b></span>
        </div>
      </RouterLink>
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
/* ===== 设计令牌：颜色 / 圆角 / 阴影 / 缓动集中定义，全页只从这里取值 ===== */
.home {
  --bg: #0e1116;
  --ink: #123a52;            /* 卡片主文字 */
  --ink-soft: #5c7f96;       /* 卡片次要文字 */
  --line: rgba(14, 165, 233, 0.22);
  --brand: #0284c7;
  --brand-lit: #22d3ee;
  --bar-text: #e8edf2;
  --bar-dim: #6b7f92;
  --radius-lg: 26px;
  --radius-pill: 999px;
  --shadow-soft: 0 12px 34px rgba(12, 46, 70, 0.1), 0 2px 8px rgba(12, 46, 70, 0.06);
  --shadow-lift: 0 30px 70px rgba(12, 58, 82, 0.22), 0 6px 18px rgba(12, 58, 82, 0.1);
  --ease: cubic-bezier(0.22, 1, 0.36, 1);

  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;          /* 页面本身不滚，滚动交给内容区 */
  background: var(--bg);
}

/* ---------- 顶栏 ---------- */
.topbar {
  flex: none;
  width: 100%;
  max-width: 1080px;         /* 与卡片同宽，左右留白与卡片对齐 */
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 22px 24px;
  color: var(--bar-text);
}
.brand {
  font-family: 'IBM Plex Mono', monospace;
  font-size: 14px;
  letter-spacing: 3px;
  white-space: nowrap;
}
.top-right {
  display: flex;
  align-items: center;
  gap: 16px;
  min-width: 0;
}
.tag {
  font-size: 12px;
  color: var(--bar-dim);
  letter-spacing: 2px;
  white-space: nowrap;
}
.who {
  font-size: 13px;
  color: var(--bar-text);
  letter-spacing: 1px;
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 顶栏两个胶囊按钮共用一套尺寸/观感，只有配色不同 */
.login-link,
.logout-btn {
  flex: none;
  padding: 7px 18px;
  border-radius: var(--radius-pill);
  background: transparent;
  font-size: 13px;
  font-family: inherit;
  line-height: 1;
  cursor: pointer;
  text-decoration: none;
  white-space: nowrap;
  transition: background 0.2s ease, color 0.2s ease, border-color 0.2s ease;
}
.login-link {
  border: 1px solid rgba(34, 211, 238, 0.35);
  color: var(--brand-lit);
}
.login-link:hover {
  background: rgba(34, 211, 238, 0.12);
}
.logout-btn {
  border: 1px solid rgba(148, 163, 184, 0.35);
  color: #9fb0c0;
}
.logout-btn:not(:disabled):hover {
  background: rgba(148, 163, 184, 0.12);
  color: var(--bar-text);
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
  overflow-y: auto;          /* 内容高于视口时在这里滚，居中不被裁掉 */
  padding: 0 24px 24px;
}

/* ---------- 卡片 ---------- */
.card {
  /* margin:auto 而不是 align-items:center —— 溢出时不会被顶到不可见处（居中 + 滚动的安全写法） */
  margin: auto;
  position: relative;
  display: flex;             /* 让 .card-inner 撑满卡片高度 */
  width: 100%;
  max-width: 1080px;
  height: clamp(320px, 66vh, 560px);
  border-radius: var(--radius-lg);
  overflow: hidden;
  text-decoration: none;
  box-shadow: var(--shadow-soft);
  transition: transform 0.35s var(--ease), box-shadow 0.35s var(--ease);
}
/* hover 时浮现的一层柔光：压在渐变之上、内容之下 */
.card::after {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background: linear-gradient(140deg, rgba(255, 255, 255, 0.55), rgba(255, 255, 255, 0));
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.35s var(--ease);
}

/* 唯一入口卡：清新蓝青 · 答疑辅导 */
.card.guide {
  background:
    radial-gradient(700px 400px at 85% 0%, rgba(14, 165, 233, 0.22), transparent 60%),
    radial-gradient(600px 380px at 0% 100%, rgba(45, 212, 191, 0.22), transparent 60%),
    linear-gradient(160deg, #f3fbff 0%, #e6f6f8 100%);
  border: 1px solid var(--line);
}

.card-inner {
  position: relative;
  z-index: 2;
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;       /* 水平居中 */
  justify-content: center;   /* 垂直居中 */
  text-align: center;
  padding: clamp(28px, 4.5vw, 56px);
}

.emoji {
  font-size: clamp(38px, 4vw, 50px);
  line-height: 1;
}

h2 {
  margin: 16px 0 12px;
  color: var(--ink);
  font-size: clamp(24px, 3vw, 40px);
  font-weight: 900;
  line-height: 1.35;
  letter-spacing: 0.5px;
}

.card p {
  margin: 0;
  max-width: 44ch;           /* 限宽：中文一行 20 字左右，读起来才舒服 */
  color: var(--ink-soft);
  font-size: 14px;
  line-height: 1.9;
}

.feats {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
  list-style: none;
  padding: 0;
  margin: 24px 0 0;
}
.feats li {
  padding: 6px 14px;
  border-radius: var(--radius-pill);
  border: 1px solid currentColor;
  color: var(--ink-soft);
  font-size: 12px;
  letter-spacing: 0.5px;
  transition: background 0.25s var(--ease), color 0.25s var(--ease);
}

.enter {
  margin-top: 30px;
  color: var(--brand);
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 0.5px;
}
.enter b {
  display: inline-block;
  transition: transform 0.25s var(--ease);
}

/* ---------- hover / 键盘焦点 ---------- */
/* 不套 @media (hover: hover)：本卡是导航入口，点下去就跳走了，触摸端不会留下"粘住的 hover"；
   规则留在顶层，浏览器里直接查样式就能验证 */
.card:hover {
  transform: translateY(-4px);
  box-shadow: var(--shadow-lift);
}
.card:hover::after {
  opacity: 1;
}
.card:hover .enter b {
  transform: translateX(8px);
}
.card:hover .feats li {
  background: rgba(255, 255, 255, 0.7);
  color: var(--brand);
}
.card:focus-visible {
  outline: 2px solid var(--brand);
  outline-offset: 3px;
}

/* ---------- 响应式：桌面 / 平板 / 手机 ---------- */
@media (max-width: 820px) {
  .topbar {
    padding: 18px 20px;
  }
  .tag {
    display: none;           /* 窄屏顶栏挤，先给登录/登出按钮让位 */
  }
  .grid {
    padding: 0 20px 20px;
  }
  .card {
    height: auto;            /* 矮屏改为按内容撑高，避免固定高度被压扁 */
    min-height: 380px;
  }
}

@media (max-width: 480px) {
  .brand {
    font-size: 12px;
    letter-spacing: 2px;
  }
  .who {
    max-width: 88px;
  }
  .card {
    min-height: 340px;
  }
}

/* 尊重系统「减少动态效果」设置 */
@media (prefers-reduced-motion: reduce) {
  .card,
  .card::after,
  .enter b,
  .feats li {
    transition: none;
  }
  .card:hover {
    transform: none;
  }
}
</style>
