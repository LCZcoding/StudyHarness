import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../views/HomeView.vue'
import { isAuthenticated } from '../stores/auth.js'

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
    // 不带会话 id：进入后默认打开「最近一条会话」，没有会话则停在空白态（懒创建）
    component: () => import('../views/GuideChatView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/guide/:conversationId',
    name: 'guide-conversation',
    // 带会话 id：直接打开该会话，刷新后仍留在同一会话
    component: () => import('../views/GuideChatView.vue'),
    meta: { requiresAuth: true }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

// 全局前置守卫：目标路由标了 requiresAuth 且未登录 → 转去 /login
router.beforeEach((to) => {
  if (to.meta.requiresAuth && !isAuthenticated.value) {
    return { name: 'login' , query: { redirect: to.fullPath } }
  }
})

export default router
