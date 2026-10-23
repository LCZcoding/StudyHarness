import request from './request'

/**
 * 登录
 * POST /api/auth/login  body: { username, password }
 * 成功：200 { userId }，同时服务端通过 Set-Cookie 下发 JSESSIONID（登录态就存在这个 Cookie 里）
 * 失败：401 { message: "用户名或密码错误" }
 */
export const login = (username, password) =>
  request.post('/auth/login', { username, password })

/**
 * 注册
 * POST /api/auth/register  body: { username, password }
 * 成功：201 { userId }，服务端同时写入 session（注册即登录，不用再调一次登录）
 * 失败：400 { message: "用户名已被占用" } / 400 { message: "用户名需 2~50 个字符" }
 */
export const register = (username, password) =>
  request.post('/auth/register', { username, password })

/**
 * 登出
 * POST /api/auth/logout  无请求体
 * 成功：204 无响应体（服务端销毁 session，JSESSIONID 随之作废）
 * 幂等：未登录调用同样返回 204，不会报错
 */
export const logout = () => request.post('/auth/logout')
