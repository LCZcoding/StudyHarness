/**
 * 生成聊天室 id，用于区分不同会话
 * 优先使用 UUID，非安全上下文下降级生成
 */
export function createChatId() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `chat_${Date.now().toString(36)}_${Math.random().toString(16).slice(2, 10)}`
}
