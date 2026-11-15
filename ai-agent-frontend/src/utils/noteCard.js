/**
 * 笔记卡片的「标题 + 前 80 字摘要」（spec FR-03）。
 *
 * 纯函数、不依赖 Vue / 浏览器 / 后端，node 脚本可以直接跑。
 * 聊天里的卡片与以后可能出现的列表页都从这里取文案，
 * 免得「卡片标题怎么取」散成两份实现。
 *
 * 口径：
 *  - 标题 = 第一个 Markdown 标题行；整篇没有标题就退化成第一行正文。
 *  - 摘要 = 跳过标题、只取正文，剥掉行内标记后截前 80 字（超了加省略号）。
 *    「跳过标题」是有意的：标题已经在卡片上单独占一行，摘要再从标题重复一遍没有信息量。
 *  - 只做文本裁剪，不拼任何 HTML 字符串。渲染侧一律 {{ }}，NFR-03 的转义口径不变。
 */

const TITLE_MAX = 60
const SUMMARY_MAX = 80

export const NOTE_SUMMARY_MAX = SUMMARY_MAX

// ``` 围栏代码块：摘要里不该出现代码，标题也不该被代码里的 # 骗到
const FENCE = /^```[\s\S]*?^```/gm

function normalize(markdown) {
  return String(markdown ?? '')
    .replace(/\r\n?/g, '\n')
    .replace(FENCE, '\n')
}

/** 行内标记去皮：图片整段丢（摘要里没有意义）、链接只留文字、强调与行内代码脱壳 */
function stripInline(line) {
  return line
    .replace(/!\[[^\]]*\]\([^)]*\)/g, '')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/\*\*([^*]+)\*\*/g, '$1')
    .replace(/__([^_]+)__/g, '$1')
    .replace(/~~([^~]+)~~/g, '$1')
    .replace(/\*([^*]+)\*/g, '$1')
}

/**
 * 把一行归类成 blank / heading / text，text 是已去皮的纯文本。
 * 分割线、表格分隔行都归 blank：它们对标题和摘要都没有贡献。
 */
function classify(raw) {
  const line = raw.replace(/\s+$/, '')
  const trimmed = line.trim()
  if (!trimmed) return { kind: 'blank', text: '' }

  // `---`、`***`、`|---|---|`：去掉 | 与 : 之后只剩减号，且至少三个
  const bare = trimmed.replace(/\|/g, '').replace(/[:\s]/g, '')
  if (bare.length >= 3 && /^-+$/.test(bare)) return { kind: 'blank', text: '' }

  const heading = trimmed.match(/^#{1,6}\s+(.*)$/)
  if (heading) return { kind: 'heading', text: stripInline(heading[1]).trim() }

  // 引用与列表只去掉行首记号，正文保留
  const body = trimmed.replace(/^(?:>\s?)+/, '').replace(/^(?:[-*+]|\d+[.)])\s+/, '')
  const text = stripInline(body).trim()
  return text ? { kind: 'text', text } : { kind: 'blank', text: '' }
}

function scan(markdown) {
  const headings = []
  const body = []
  for (const raw of normalize(markdown).split('\n')) {
    const { kind, text } = classify(raw)
    if (kind === 'heading') headings.push(text)
    else if (kind === 'text') body.push(text)
  }
  return { headings, body }
}

/** 折叠空白 + 按字符（不是字节）截断，超长补省略号 */
function clamp(text, max) {
  const chars = Array.from(String(text).replace(/\s+/g, ' ').trim())
  return chars.length > max ? `${chars.slice(0, max).join('')}…` : chars.join('')
}

export function noteTitle(markdown) {
  const { headings, body } = scan(markdown)
  const first = headings[0] || body[0] || ''
  return first ? clamp(first, TITLE_MAX) : '未命名笔记'
}

export function noteSummary(markdown, max = SUMMARY_MAX) {
  const { headings, body } = scan(markdown)
  // 正文空（比如只写了几行标题）时退回标题，别给一张没有摘要的卡
  return clamp((body.length ? body : headings).join(' '), max)
}

/** 正文可读字数：卡片上显示「N 字」，比字节数直观 */
export function noteChars(markdown) {
  const { headings, body } = scan(markdown)
  return Array.from([...headings, ...body].join('')).length
}

/** 卡片一次拿齐三样，省得调用方跑三遍扫描 */
export function noteCardOf(markdown) {
  return {
    title: noteTitle(markdown),
    summary: noteSummary(markdown),
    chars: noteChars(markdown)
  }
}
