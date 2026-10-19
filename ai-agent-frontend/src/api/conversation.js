import request from './request'

/**
 * 创建会话
 * POST /api/conversations  body: { mode }
 * 成功：201 { conversationId }  —— 会话 id 由服务端生成，前端不可自造
 * 失败：401 { message: "未登录" } / 400 { message: "mode 不能为空" 等 }
 *
 * 注意：request.js 的响应拦截器写的是 (response) => response.data，
 * 所以 await createConversation(...) 拿到的直接就是响应体，不用再 .data 一次。
 */
export const createConversation = (mode) =>
  request.post('/conversations', { mode })
