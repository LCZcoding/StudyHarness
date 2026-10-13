import request from './request'

/**
 * AI 恋爱大师 - 同步对话（非流式，Axios）
 * GET /api/ai/love_app/chat/sync
 */
export const loveChatSync = (message, chatId) =>
  request.get('/ai/love_app/chat/sync', {
    params: { message, chatId }
  })
