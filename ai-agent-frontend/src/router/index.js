import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../views/HomeView.vue'
import AppShell from '../components/AppShell.vue'
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
    // 登录后的页面统一套一层「左侧 68px 图标栏 + 右侧页面」，
    // 首页与登录页不套：首页是应用中心卡片，登录页没有导航。
    path: '/',
    component: AppShell,
    children: [
      {
        path: 'guide',
        name: 'guide',
        // 不带会话 id：进入后默认打开「最近一条会话」，没有会话则停在空白态（懒创建）
        component: () => import('../views/GuideChatView.vue'),
        meta: { requiresAuth: true }
      },
      {
        path: 'guide/:conversationId',
        name: 'guide-conversation',
        // 带会话 id：直接打开该会话，刷新后仍留在同一会话
        component: () => import('../views/GuideChatView.vue'),
        meta: { requiresAuth: true }
      },
      {
        // 笔记列表页：所有对话里生成的笔记集中在这里（FR-14）。
        // 静态段必须排在 notes/:messageId 之前，免得以后有人改成前缀匹配时被动态段吃掉。
        path: 'notes',
        name: 'notes',
        component: () => import('../views/NotesView.vue'),
        meta: { requiresAuth: true }
      },
      {
        // 笔记详情页：一条 AI 生成的笔记就是一条 NOTE 消息，路由参数用 messageId。
        // 页面与这里的动态 import 必须同一提交落盘（旧版把组件造在更早的任务里，
        // 路由 import 了一个还不存在的文件，两头互赖）。
        path: 'notes/:messageId',
        name: 'note-detail',
        component: () => import('../views/NoteDetailView.vue'),
        meta: { requiresAuth: true }
      },
      {
        path: 'materials',
        name: 'materials',
        component: () => import('../views/MaterialsView.vue'),
        meta: { requiresAuth: true }
      },
      {
        // 章节阅读页：知识单元是章节，id 用 knowledgeItemId
        path: 'materials/:knowledgeItemId',
        name: 'section-read',
        component: () => import('../views/SectionReadView.vue'),
        meta: { requiresAuth: true }
      },
      {
        path: 'quiz/:conversationId',
        name: 'quiz',
        component: () => import('../views/QuizView.vue'),
        meta: { requiresAuth: true }
      },
      {
        path: 'todo',
        name: 'todo',
        component: () => import('../views/TodoView.vue'),
        meta: { requiresAuth: true }
      },
      {
        path: 'progress',
        name: 'progress',
        component: () => import('../views/ProgressView.vue'),
        meta: { requiresAuth: true }
      }
    ]
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
    return { name: 'login', query: { redirect: to.fullPath } }
  }
})

export default router
