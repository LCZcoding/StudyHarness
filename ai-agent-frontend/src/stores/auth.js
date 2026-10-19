import { ref } from 'vue'

// JSESSIONID 是 HttpOnly Cookie，JS 读不到；而且"有 Cookie ≠ 已登录"
// （未登录请求也会被 Spring 发一个空会话）。所以登录态由前端用一个显式标记记住，
// 写进 localStorage，刷新后仍然有效。
const AUTH_KEY = 'ai-agent-auth'

export const isAuthenticated = ref(localStorage.getItem(AUTH_KEY) === '1')

export function setAuth() {
  localStorage.setItem(AUTH_KEY, '1')
  isAuthenticated.value = true
}