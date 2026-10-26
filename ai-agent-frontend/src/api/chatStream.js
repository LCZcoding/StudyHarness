/**
 * POST /api/ai/chat/stream 的流式读取（事件版）。
 *
 * 服务端每帧形如：
 *   event:delta
 *   data:{"text":"线程池"}
 *   <空行>
 *
 * 契约见 docs/api-chat-stream.md。与旧版的区别只有两点：
 *   1) 每帧带 event: 名，必须读出来，不能只挑 data:；
 *   2) data 是单行 JSON，要 parse 成对象再交给上层。
 *
 * 失败仍是 JSON { message }：401 未登录 / 403 会话不存在或无权访问 / 400，
 * 这些都发生在「开流之前」，所以还能用 HTTP 状态码判断。
 */
export async function streamChat({
  conversationId, // 发给后端的会话 id
  message, // 发给后端的问题
  useRag = false, // 本轮是否检索本人资料（后端按 userId 过滤，topK=5）
  useTools = false, // 本轮是否允许调用只读工具（搜索 / 抓网页 / 时间）
  onEvent, // (name, payload) => void  —— 唯一的事件出口
  onDone, // 流关闭（无论是否收到 done 帧）调一次
  onError, // 网络/解码/HTTP 错误
  signal // 用于中途取消（AbortController）
}) {
  try {
    /**
     * 这里故意不用 request.js 那个 axios 实例——axios 的响应拦截器是`(response) => response.data`，
     * 它会等整个响应体到齐才给你结果，流式效果直接被抹平。
     * 而 axios 实例的`baseURL: '/api'` 也不会作用到`fetch` 上，所以`/api` 前缀得自己写。
     */
    const res = await fetch('/api/ai/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ conversationId, message, useRag, useTools }),
      credentials: 'include', // 跨域仍带cookie
      signal
    })

    // fetch 有个反直觉的设计：4xx / 5xx 不会抛异常，必须自己判 res.ok
    if (!res.ok) {
      const err = await res.json()
      throw new Error(err.message)
    }

    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      // stream: true —— 结尾那半个多字节字符先留在解码器里，等下一片凑齐
      buffer += decoder.decode(value, { stream: true })
      // 个别反向代理会把 \n 变成 \r\n，先归一化，否则按 \n\n 切帧会漏帧
      buffer = buffer.replace(/\r\n/g, '\n')

      const frames = buffer.split('\n\n')
      buffer = frames.pop() // 最后一段可能是不完整帧，退回 buffer

      for (const frame of frames) {
        const { name, data } = parseFrame(frame)
        if (data === null) continue // 心跳/注释帧

        // 兼容旧后端：没有 event: 名、data 也不是 JSON 时，按正文增量处理
        if (name === 'delta' && data.raw !== undefined) {
          onEvent?.('delta', { text: data.raw })
          continue
        }
        onEvent?.(name, data.json)
      }
    }

    onDone?.()
  } catch (e) {
    // 用户主动取消不是错误，静默收场
    if (e.name === 'AbortError') return
    onError?.(e)
  }
}

/**
 * 解析一帧：返回 { name, data }。
 * data 为 { raw } 表示"不是 JSON，当纯文本用"；为 { json } 表示解析成功；
 * 为 null 表示这一帧没有 data（心跳、注释），调用方跳过。
 */
function parseFrame(frame) {
  let name = 'delta'
  const dataLines = []

  for (const line of frame.split('\n')) {
    if (line.startsWith('event:')) {
      name = line.slice(6).trim() || 'delta'
    } else if (line.startsWith('data:')) {
      // 规范允许冒号后带一个空格，去掉它；JSON 本身不含前导空格，去掉是安全的
      dataLines.push(line.slice(5).replace(/^ /, ''))
    }
    // id: / retry: / 以 : 开头的注释行，一律忽略
  }

  if (!dataLines.length) return { name, data: null }

  const raw = dataLines.join('\n')
  try {
    return { name, data: { json: JSON.parse(raw) } }
  } catch {
    return { name, data: { raw } }
  }
}
