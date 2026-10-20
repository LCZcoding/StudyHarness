import { ref } from 'vue'

// JSESSIONID 是 HttpOnly Cookie，JS 读不到；而且"有 Cookie ≠ 已登录"
// （未登录请求也会被 Spring 发一个空会话）。所以登录态由前端用一个显式标记记住，
// 写进 localStorage，刷新后仍然有效。
const AUTH_KEY = 'ai-agent-auth'

// 用户名只用于界面展示"我是谁"，不参与任何身份判定 —— 身份权威仍在服务端 session 里。
// 所以它只是一份可随时丢弃的显示缓存，丢了也不影响鉴权。
const USERNAME_KEY = 'ai-agent-username'

export const isAuthenticated = ref(localStorage.getItem(AUTH_KEY) === '1')

export const currentUsername = ref(localStorage.getItem(USERNAME_KEY) || '')

export function setAuth(username) {
  localStorage.setItem(AUTH_KEY, '1')
  localStorage.setItem(USERNAME_KEY, username || '')
  currentUsername.value = username || ''
  isAuthenticated.value = true
}

// setAuth 的撤销出口：登出时调用
export function clearAuth() {
  localStorage.removeItem(AUTH_KEY)
  localStorage.removeItem(USERNAME_KEY)
  currentUsername.value = ''
  isAuthenticated.value = false
}
