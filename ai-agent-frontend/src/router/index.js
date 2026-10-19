import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../views/HomeView.vue'

const routes = [
  {
    path: '/',
    name: 'home',
    component: HomeView
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/LoginView.vue')
  },
  {
    path: '/guide',
    name: 'guide',
    // 进入页面即生成聊天室 id（组件 setup 阶段）
    component: () => import('../views/GuideChatView.vue')
  },
  {
    path: '/manus',
    name: 'manus',
    component: () => import('../views/ManusChatView.vue')
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

export default router
