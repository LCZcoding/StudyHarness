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
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
  background: #fcfcfc;
}
.topbar-text {
  min-width: 0;
}
.eyebrow {
  font-size: 11px;
  letter-spacing: 2px;
  color: #8e8ea0;
}
.title {
  margin: 6px 0 0;
  font-size: 22px;
  font-weight: 600;
  letter-spacing: -0.2px;
  color: #0d0d0d;
}
.subtitle {
  margin: 5px 0 0;
  font-size: 13.5px;
  color: #6e6e80;
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
