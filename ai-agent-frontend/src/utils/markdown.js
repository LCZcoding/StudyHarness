import { marked } from 'marked'
import DOMPurify from 'dompurify'

// 对话区是聊天场景：GFM（表格、删除线、任务列表）够用，breaks 让单个换行也成段落
marked.setOptions({ gfm: true, breaks: true })

// 模型输出会带链接：一律新窗口打开，且禁止把 opener 交回去
DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node.tagName === 'A') {
    node.setAttribute('target', '_blank')
    node.setAttribute('rel', 'noopener noreferrer')
  }
})

/**
 * 把模型返回的 markdown 渲染成可直接 v-html 的 HTML。
 * 先交给 marked 解析，再交给 DOMPurify 过滤：模型输出属于不可信内容，
 * 不能直接塞进 v-html，否则一段 <img onerror=...> 就能执行脚本。
 */
export function renderMarkdown(text) {
  if (!text) return ''
  const html = marked.parse(text)
  return DOMPurify.sanitize(html)
}
