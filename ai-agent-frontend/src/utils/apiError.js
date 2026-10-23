/**
 * 统一处理接口错误，避免每个页面各写一遍 401 判断。
 *
 * 401 说明登录已失效（session 过期或压根没登录）→ 跳登录页并带 redirect，
 * 登录完能回到原页面。其余状态码把服务端 message 原样返回给调用方展示，
 * 不在此处拼接业务文案 —— 文案归属仍在产生拒绝的那一端。
 */
export function redirectOn401(error, router, route) {
  if (error?.response?.status !== 401) return false
  router.push({ name: 'login', query: { redirect: route.fullPath } })
  return true
}

/** 取服务端 message；拿不到才用兜底文案 */
export function messageOf(error, fallback) {
  return error?.response?.data?.message || fallback
}
