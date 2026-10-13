<template>
  <div class="home">
    <header class="topbar">
      <span class="brand">YU · AI AGENT</span>
      <span class="tag">应用中心 · APPLICATION HUB</span>
    </header>

    <main class="grid">
      <!-- 应用 1：AI 恋爱大师 -->
      <RouterLink to="/love" class="card love">
        <div class="card-inner">
          <span class="num">01</span>
          <span class="emoji">💌</span>
          <h2>AI 恋爱大师</h2>
          <p>你的专属情感顾问，倾听心事、答疑解惑，<br />陪伴你走过每一段心动与纠结。</p>
          <ul class="feats">
            <li>情感咨询</li>
            <li>约会攻略</li>
            <li>话术支招</li>
          </ul>
          <span class="enter">进入聊天室 <b>→</b></span>
        </div>
      </RouterLink>

      <span class="divider" aria-hidden="true">VS</span>

      <!-- 应用 2：AI 超级智能体 -->
      <RouterLink to="/manus" class="card manus">
        <div class="card-inner">
          <span class="num">02</span>
          <span class="emoji">🧠</span>
          <h2>AI 超级智能体</h2>
          <p>能思考、会用工具的通用 Agent，<br />复杂任务拆解执行，步骤实时可见。</p>
          <ul class="feats">
            <li>工具调用</li>
            <li>多步规划</li>
            <li>联网搜索</li>
          </ul>
          <span class="enter">进入控制台 <b>→</b></span>
        </div>
      </RouterLink>
    </main>
  </div>
</template>

<script setup></script>

<style scoped>
.home {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #0e1116;
}

.topbar {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 30px;
  color: #e8edf2;
}
.brand {
  font-family: 'IBM Plex Mono', monospace;
  font-size: 14px;
  letter-spacing: 3px;
}
.tag {
  font-size: 12px;
  color: #6b7f92;
  letter-spacing: 2px;
}

.grid {
  flex: 1;
  position: relative;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 18px;
  padding: 0 18px 18px;
  min-height: 0;
}

.divider {
  position: absolute;
  z-index: 3;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  width: 52px;
  height: 52px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: #0e1116;
  border: 1px solid rgba(255, 255, 255, 0.18);
  color: #9fb0c0;
  font-family: 'IBM Plex Mono', monospace;
  font-size: 12px;
  letter-spacing: 1px;
  pointer-events: none;
}

.card {
  position: relative;
  border-radius: 22px;
  overflow: hidden;
  display: block;
  min-height: 0;
}

.card-inner {
  position: relative;
  z-index: 2;
  height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 6vh 8%;
}

/* 左卡：浪漫暖调 */
.card.love {
  background:
    radial-gradient(700px 400px at 85% 0%, rgba(255, 160, 185, 0.5), transparent 60%),
    linear-gradient(160deg, #fff2ee 0%, #ffe0e8 100%);
  border: 1px solid rgba(224, 68, 109, 0.18);
}
.card.love h2 {
  font-family: 'Noto Serif SC', serif;
  color: #5a2a3a;
}
.card.love p,
.card.love .feats {
  color: #9c6674;
}

/* 右卡：科技冷调 */
.card.manus {
  background:
    linear-gradient(rgba(34, 211, 238, 0.06) 1px, transparent 1px),
    linear-gradient(90deg, rgba(34, 211, 238, 0.06) 1px, transparent 1px),
    radial-gradient(700px 400px at 15% 100%, rgba(34, 211, 238, 0.14), transparent 60%),
    linear-gradient(160deg, #10161f 0%, #0a0e14 100%);
  background-size: 34px 34px, 34px 34px, 100% 100%, 100% 100%;
  border: 1px solid rgba(34, 211, 238, 0.18);
}
.card.manus h2 {
  color: #e6f6fb;
  letter-spacing: 1px;
}
.card.manus p,
.card.manus .feats {
  color: #7d93a6;
}

.num {
  font-family: 'IBM Plex Mono', monospace;
  font-size: 12px;
  letter-spacing: 3px;
  opacity: 0.7;
}
.card.love .num {
  color: #c75a78;
}
.card.manus .num {
  color: #22d3ee;
}

.emoji {
  font-size: 44px;
  margin: 14px 0 4px;
}

h2 {
  margin: 6px 0 10px;
  font-size: clamp(26px, 3.4vw, 42px);
  font-weight: 900;
}

.card p {
  margin: 0;
  font-size: 14px;
  line-height: 1.9;
}

.feats {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  list-style: none;
  padding: 0;
  margin: 18px 0 0;
}
.feats li {
  font-size: 12px;
  padding: 5px 12px;
  border-radius: 999px;
  border: 1px solid currentColor;
  opacity: 0.75;
}

.enter {
  margin-top: 26px;
  font-size: 14px;
  font-weight: 700;
}
.card.love .enter {
  color: #e0446d;
}
.card.manus .enter {
  color: #22d3ee;
  font-family: 'IBM Plex Mono', 'Noto Sans SC', monospace;
}
.enter b {
  display: inline-block;
  transition: transform 0.25s ease;
}
.card:hover .enter b {
  transform: translateX(8px);
}
.card {
  transition: transform 0.35s cubic-bezier(0.22, 1, 0.36, 1), box-shadow 0.35s ease;
}
.card:hover {
  transform: translateY(-4px);
}
.card.love:hover {
  box-shadow: 0 24px 60px rgba(224, 68, 109, 0.22);
}
.card.manus:hover {
  box-shadow: 0 24px 60px rgba(34, 211, 238, 0.16);
}

@media (max-width: 820px) {
  .grid {
    grid-template-columns: 1fr;
    overflow-y: auto;
  }
  .card {
    min-height: 420px;
  }
  .divider {
    display: none;
  }
}
</style>
