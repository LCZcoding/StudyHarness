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
    // 会话 id 由后端 POST /conversations 签发，见 GuideChatView 的 onMounted
    component: () => import('../views/GuideChatView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/manus',
    name: 'manus',
    component: () => import('../views/ManusChatView.vue'),
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
    return { name: 'login' }
  }
})

export default router
