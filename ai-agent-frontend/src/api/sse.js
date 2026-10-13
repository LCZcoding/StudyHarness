/**
 * SSE 流式对话工具
 *
 * 后端两个聊天接口均为 GET + text/event-stream，且不依赖自定义请求头，
 * 因此使用浏览器原生 EventSource（Axios 在浏览器端无法原生消费 SSE）。
 *
 * @param {string} path  接口路径，如 /ai/love_app/chat/sse
 * @param {object} params 查询参数，如 { message, chatId }
 * @param {function} onMessage 每收到一条消息（分片）触发
 * @param {function} onOpen    连接建立
 * @param {function} onDone    流式结束（正常结束 / 服务端关闭）
 * @param {function} onError   建立连接前即失败
 * @returns {EventSource}
 */
export function createSseChat({ path, params = {}, onMessage, onOpen, onDone, onError }) {
  const search = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      search.append(key, String(value))
    }
  })

  const source = new EventSource(`/api${path}?${search.toString()}`)
  let received = false

  source.onopen = () => onOpen?.()

  source.onmessage = (event) => {
    if (!event.data) return
    received = true
    onMessage?.(event.data)
  }

  source.onerror = () => {
    // 服务端正常结束流时浏览器同样会触发 error。
    // 必须主动 close()，否则 EventSource 会自动重连，导致重复发起提问。
    source.close()
    if (received) {
      onDone?.()
    } else {
      onError?.(new Error('无法连接到流式服务，请确认后端已启动'))
    }
  }

  return source
}
