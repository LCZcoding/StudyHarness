import request from './request'

/**
 * 问题引导解决助手 - 同步对话（非流式，Axios）
 * GET /api/ai/love_app/chat/sync
 */
export const guideChatSync = (message, chatId) =>
  request.get('/ai/love_app/chat/sync', {
    params: { message, chatId }
  })
