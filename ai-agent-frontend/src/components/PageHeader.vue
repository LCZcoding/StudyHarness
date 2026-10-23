<template>
  <header class="topbar">
    <div class="topbar-text">
      <div class="eyebrow">{{ eyebrow }}</div>
      <h1 class="title">{{ title }}</h1>
      <p class="subtitle">{{ subtitle }}</p>
    </div>
    <div class="top-actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<script setup>
// 四个页面的顶栏形状完全一致，抽成一个组件，改字号/间距只改这一处
defineProps({
  eyebrow: { type: String, required: true },
  title: { type: String, required: true },
  subtitle: { type: String, default: '' }
})
</script>

<style scoped>
.topbar {
  flex: none;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  padding: 26px 32px 18px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.12);
  background: radial-gradient(700px 200px at 88% -40%, rgba(34, 211, 238, 0.1), transparent 62%);
}
.topbar-text {
  min-width: 0;
}
.eyebrow {
  font-family: 'IBM Plex Mono', monospace;
  font-size: 11px;
  letter-spacing: 2.5px;
  color: #6b7f92;
}
.title {
  margin: 4px 0 0;
  font-size: 22px;
  font-weight: 800;
  letter-spacing: 0.4px;
  color: #eef4fa;
}
.subtitle {
  margin: 4px 0 0;
  font-size: 13px;
  color: #6b7f92;
}
.top-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: none;
}

@media (max-width: 820px) {
  .topbar {
    padding: 20px 18px 14px;
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
