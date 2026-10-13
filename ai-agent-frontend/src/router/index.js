import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../views/HomeView.vue'

const routes = [
  {
    path: '/',
    name: 'home',
    component: HomeView
  },
  {
    path: '/love',
    name: 'love',
    // 进入页面即生成聊天室 id（组件 setup 阶段）
    component: () => import('../views/LoveChatView.vue')
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
