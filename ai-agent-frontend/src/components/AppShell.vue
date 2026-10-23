<template>
  <div class="shell">
    <NavRail />
    <div class="shell-body">
      <RouterView />
    </div>
  </div>
</template>

<script setup>
import NavRail from './NavRail.vue'
</script>

<style scoped>
/* 只有登录后的页面走这套布局：左侧 68px 图标栏 + 右侧页面自己滚 */
.shell {
  height: 100%;
  display: flex;
  background: #0e1116;
}
.shell-body {
  flex: 1;
  min-width: 0;
  min-height: 0;
}
</style>
