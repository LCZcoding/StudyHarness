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

/**
 * 我的会话列表（GET 没有请求体，身份只从 session Cookie 取，接口不收 userId）
 * GET /api/conversations
 * 成功：200 { conversations: [{ conversationId, mode, title, updatedAt }] }
 *        title 是后端从「首条 USER 消息」派生的前 50 字，该会话没有消息时为 null
 * 失败：401 { message: "未登录" }
 *
 * 排序（updated_at DESC）由后端决定，前端原样渲染，不要重排。
 */
export const listConversations = () => request.get('/conversations')

/**
 * 某个会话的历史消息
 * GET /api/conversations/{conversationId}/messages
 * 成功：200 { messages: [{ messageId, role, content, createdAt }] }
 *        role 是 'USER' | 'ASSISTANT'（前端据此分左右气泡）
 *        排序由后端定为 id ASC（插入顺序 = 对话顺序），前端原样渲染，不要重排
 * 失败：401 { message: "未登录" } / 403 { message: "会话不存在或无权访问" }
 *       400 { message: "conversationId 不合法" }（路径变量不是合法 UUID）
 */
export const listMessages = (conversationId) =>
  request.get(`/conversations/${conversationId}/messages`)
