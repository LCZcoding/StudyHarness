/**
 * fetch + 流式读取：POST /api/ai/chat/stream
 * 契约见 docs/api-chat-stream.md：
 *   成功 200 text/event-stream，每帧形如 "data:<文本>\n\n"，流关闭即结束（不发结束帧）
 *   失败是 JSON { message }：401 未登录 / 403 会话不存在或无权访问 / 400
 */
export async function streamChat({
  conversationId, // 发给后端的会话 id
  message,        // 发给后端的问题
  onMessage, // 后端每吐出一小段文本，就调一次
  onDone,         // 流结束调一次
  onError,     // 出错调一次
  signal                     // 用于中途取消（AbortController）
}){
    try{
    /**
     * 这里 故意不用 request.js 那个 axios 实例——axios 的响应拦截器是`(response) => response.data` ，
     * 它会 等整个响应体到齐 才给你结果，流式效果直接被抹平。
     * 而 axios 实例的`baseURL: '/api'` 也不会作用到`fetch` 上，所以`/api` 前缀得自己写。
     */
    const res = await fetch('/api/ai/chat/stream',{
        method: 'POST',
        headers: {'Content-Type': 'application/json' },
        body: JSON.stringify({ conversationId, message }),
        credentials: 'include',// 跨域仍带cookie
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
    // `{ stream: true }` 告诉解码器：" 这还不是最后一片 ，遇到结尾那半个字先缓存住，等下一片凑齐了再一起解出来。"
    buffer += decoder.decode(value, { stream: true })

    // 帧以空行结束；split 后最后一段可能是不完整的帧，退回 buffer 等下一片
    const frames = buffer.split('\n\n')
    buffer = frames.pop()

    for (const frame of frames) {
      // 文本含换行时后端会把每行都加上 data: 前缀，所以要把本帧所有 data: 行拼回来
      const chunk = frame
        .split('\n')
        .filter((line) => line.startsWith('data:'))
        .map((line) => line.slice(5))// 从第 5 个下标开始，取到末尾
        .join('\n')

      if (chunk) onMessage?.(chunk)
    }
  }
  onDone?.()
    }catch (e) {
         // 用户主动取消不是错误，静默收场
        if (e.name === 'AbortError') return
        onError?.(e)
    }
}