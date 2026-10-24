<template>
  <nav class="rail">
    <RouterLink to="/" class="rail-logo" title="返回应用中心">AS</RouterLink>

    <RouterLink
      v-for="item in items"
      :key="item.to"
      :to="item.to"
      :class="['rail-item', { active: isActive(item.to) }]"
      :title="item.label"
    >
      <!-- 图标用内联 SVG：不引入图标库，避免多一个依赖 -->
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"
           stroke-linecap="round" stroke-linejoin="round" v-html="item.path" />
    </RouterLink>

    <div class="rail-foot">
      <span class="avatar">{{ avatarText }}</span>
    </div>
  </nav>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { currentUsername } from '../stores/auth.js'

const route = useRoute()

// path 里只放 <path> 片段，由 v-html 塞进 24×24 的 svg 画布
const items = [
  {
    to: '/guide',
    label: '对话',
    path: '<path d="M21 11.5a8.4 8.4 0 0 1-9 8.4 9.6 9.6 0 0 1-3.3-.6L3 21l1.7-4.7A8.4 8.4 0 0 1 12 3.1a8.4 8.4 0 0 1 9 8.4z"/>'
  },
  {
    to: '/materials',
    label: '资料库',
    path: '<path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H19v18H6.5A2.5 2.5 0 0 1 4 18.5z"/><path d="M8 7h7M8 11h7M8 15h4"/>'
  },
  {
    to: '/todo',
    label: '待办',
    path: '<path d="M9 5h9M9 12h9M9 19h9"/><path d="M4 5l1.4 1.4L7.5 4.5M4 12l1.4 1.4L7.5 10.5M4 19l1.4 1.4L7.5 17.5"/>'
  },
  {
    to: '/progress',
    label: '进度',
    path: '<path d="M4 19V9M10 19V5M16 19v-6M22 19H2"/>'
  }
]

// 前缀匹配：/quiz/:id 与 /guide/:id 都能点亮对应入口
const isActive = (to) => route.path === to || route.path.startsWith(to + '/')

const avatarText = computed(() => (currentUsername.value || '我').slice(0, 1))
</script>

<style scoped>
.rail {
  flex: none;
  width: 64px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 16px 0 14px;
  border-right: 1px solid rgba(0, 0, 0, 0.06);
  background: #fcfcfc;
}

.rail-logo {
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  border-radius: 9px;
  margin-bottom: 12px;
  background: #0d0d0d;
  color: #ffffff;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.4px;
}

.rail-item {
  position: relative;
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  border-radius: 10px;
  color: #7b7b88;
  transition: background 0.16s ease, color 0.16s ease;
}
.rail-item svg {
  width: 19px;
  height: 19px;
}
.rail-item:hover {
  background: #f0f0f2;
  color: #0d0d0d;
}
.rail-item.active {
  background: #ececef;
  color: #0d0d0d;
}

.rail-foot {
  margin-top: auto;
}
.avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: #ececef;
  color: #0d0d0d;
  font-size: 12px;
  font-weight: 600;
}

@media (max-width: 820px) {
  .rail {
    width: 54px;
  }
}
</style>
