import request from './request'

/**
 * 登录
 * POST /api/auth/login  body: { username, password }
 * 成功：200 { userId }，同时服务端通过 Set-Cookie 下发 JSESSIONID（登录态就存在这个 Cookie 里）
 * 失败：401 { message: "用户名或密码错误" }
 */
export const login = (username, password) =>
  request.post('/auth/login', { username, password })
