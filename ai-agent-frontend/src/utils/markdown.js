import { marked } from 'marked'
import DOMPurify from 'dompurify'

// 对话区是聊天场景：GFM（表格、删除线、任务列表）够用，breaks 让单个换行也成段落
marked.setOptions({ gfm: true, breaks: true })

// 浏览器里 dompurify 直接给出实例；Node 环境（跑 TC-01 / TC-02 脚本时）导出的只是 factory，
// 没有 DOM 就没有 sanitize 能力。这里只在有实例时注册 hook，纯分块逻辑在 Node 里照常可测。
const canPurify = typeof DOMPurify.sanitize === 'function'

if (canPurify) {
  // 模型输出会带链接：一律新窗口打开，且禁止把 opener 交回去
  DOMPurify.addHook('afterSanitizeAttributes', (node) => {
    if (node.tagName === 'A') {
      node.setAttribute('target', '_blank')
      node.setAttribute('rel', 'noopener noreferrer')
    }
  })
}

function sanitize(html) {
  return canPurify ? DOMPurify.sanitize(html) : html
}

/**
 * 把模型返回的 markdown 渲染成可直接 v-html 的 HTML。
 * 先交给 marked 解析，再交给 DOMPurify 过滤：模型输出属于不可信内容，
 * 不能直接塞进 v-html，否则一段 <img onerror=...> 就能执行脚本。
 */
export function renderMarkdown(text) {
  if (!text) return ''
  const html = marked.parse(text)
  return sanitize(html)
}

/**
 * 按 marked 的顶层 token 把正文切块（plan.md 6.2）。
 * 不能用 \n\n 硬切：代码块 / 列表 / 表格会被切坏。
 * 每块带稳定的 key（p-N）、原文 text、已消毒 html；渲染时逐块包 data-block-key。
 * 注意 R2：列表/表格整体算一块，粒度比视觉段落粗，只影响批注分组的粗细。
 */
export function splitBlocks(md) {
  if (!md) return []
  return marked.lexer(md)
    .filter(t => t.type !== 'space')
    .map((t, i) => ({
      key: `p-${i}`,
      text: t.raw,
      html: sanitize(marked.parser([t]))
    }))
}
