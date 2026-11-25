/**
 * 批注锚点重定位、分组与原文高亮（plan.md 6.2 / spec FR-06、FR-10）。
 *
 * 这里全是纯函数：不依赖 Vue、DOM、接口，Node 脚本可直接 import 跑 TC-02；
 * AnnotatableDoc.vue 只负责把结果渲染出来。拆出来的原因是这三条逻辑是
 * 整个批注功能最容易出错、又最不该靠「浏览器里点一下」来验的部分。
 *
 * 数据形状：
 *   blocks = splitBlocks(markdown) → [{ key:'p-0', text, html }]
 *   flat   = GET /annotations 的扁平数组（按 created_at 升序）
 *   byId   = resolve() 组树后的 Map，节点多出 children / stale / groupKey
 */

/** 去掉常见 markdown 标记，只用于「引用快照还能不能在原文里找到」的判断。
 *  正文里 **粗体** / `行内代码` 在 raw 文本里带标记，用户选中的却是渲染后的纯文本，
 *  直接 b.text.includes(quote) 会把带格式的段落误判成「原文已修改」。 */
export function normalizeForMatch(s) {
  return String(s ?? '')
    .replace(/`{1,3}/g, '')
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/<[^>]*>/g, '')
    .replace(/[*_~#>|]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
}

function blockContains(b, quote) {
  const q = String(quote ?? '').trim()
  if (!q) return false
  const raw = String(b?.text ?? '')
  return raw.includes(q) || normalizeForMatch(raw).includes(normalizeForMatch(q))
}

/**
 * 组树 + 锚点重定位。plan.md 6.2 原样落地，两点细化在注释里：
 *  - 顶层先按「原 blockKey 命中」找，找不到再按 quote 全文重定位，都找不到才 stale；
 *  - 子批注按「父答案是否还含被追问的词」判 stale，groupKey 跟随父（不单独成组）。
 * flat 按 created_at 升序，父一定先于子，所以一次遍历就能拿到父的 groupKey；
 * 父被删但数据残留时把 parentId 置空按顶层兜底，宁可贴不准也不丢内容。
 */
export function resolve(blocks = [], flat = []) {
  const list = Array.isArray(flat) ? flat : []
  const blks = Array.isArray(blocks) ? blocks : []
  const byId = new Map(
    list.map(a => [a.id, { ...a, children: [], stale: false, groupKey: null }])
  )

  for (const a of byId.values()) {
    if (a.parentId != null && byId.has(a.parentId)) {
      byId.get(a.parentId).children.push(a)
    } else if (a.parentId != null) {
      a.parentId = null
    }
  }
  for (const a of byId.values()) {
    a.children.sort((x, y) =>
      String(x.createdAt ?? '').localeCompare(String(y.createdAt ?? '')) ||
      Number(x.id) - Number(y.id))
  }

  for (const a of byId.values()) {
    if (a.parentId == null) {
      const hit = blks.find(b => b.key === a.blockKey && blockContains(b, a.quote))
        || blks.find(b => blockContains(b, a.quote))
      a.stale = !hit
      a.groupKey = hit ? hit.key : null
    } else {
      const parent = byId.get(a.parentId)
      a.stale = !parent || !String(parent.answer ?? '').includes(String(a.quote ?? ''))
      a.groupKey = parent ? parent.groupKey : null
    }
  }
  return byId
}

/** 某条批注所在的层数（顶层 = 1），不存在返回 0。用于提交前拦链深 > 8。 */
export function depthOf(flat, id) {
  const byId = new Map((Array.isArray(flat) ? flat : []).map(a => [a.id, a]))
  let cur = byId.get(id)
  let n = 0
  const seen = new Set()
  while (cur && !seen.has(cur.id)) {
    seen.add(cur.id)
    n += 1
    if (cur.parentId == null) break
    cur = byId.get(cur.parentId)
  }
  return n
}

/**
 * 右栏分组：按正文块顺序排「第 N 段」，顶层 stale 统一进最后的「原文已修改」组。
 * 子批注不单独成组 —— 它挂在父卡片的追问链里，卡片的「原文已修改」标签负责提示
 * （spec AT-08）；把子批注拎出来会断链。这是对 plan 6.2「stale 统一进分组」措辞
 * 的一处精确化，见 tasks.md T016 栏。
 */
export function groupAnnotations(blocks = [], byId = new Map()) {
  const roots = [...byId.values()].filter(a => a.parentId == null)
  const normal = roots.filter(a => !a.stale)
  const stale = roots.filter(a => a.stale)
  const groups = []
  const used = new Set()

  ;(Array.isArray(blocks) ? blocks : []).forEach((b, i) => {
    const rows = normal.filter(a => a.groupKey === b.key)
    if (!rows.length) return
    rows.forEach(a => used.add(a.id))
    const snippet = normalizeForMatch(b.text).slice(0, 22)
    groups.push({
      key: b.key,
      title: `第 ${i + 1} 段${snippet ? ` · ${snippet}…` : ''}`,
      count: rows.length,
      roots: rows,
      staleGroup: false
    })
  })

  const rest = normal.filter(a => !used.has(a.id))
  if (rest.length) {
    groups.push({ key: '__rest__', title: '其他段落', count: rest.length, roots: rest, staleGroup: false })
  }
  if (stale.length) {
    groups.push({ key: '__stale__', title: '原文已修改', count: stale.length, roots: stale, staleGroup: true })
  }
  return groups
}

export function escapeHtml(s) {
  return String(s ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/**
 * 在「已经过 DOMPurify 消毒的块 HTML」里把引用原句包一层 <mark class="quote-hit">。
 * best-effort：被 markdown 标记切开（例如选中跨 <strong>）时匹配不到，返回原 HTML，
 * 由组件给该块加 .block-has-annotation 色条 + 批注数角标降级提示，批注本身不丢。
 *
 * 安全：插入的只有固定的 <mark> 标签与 escapeHtml 后的 quote，绝不会把批注文本
 * 当 HTML 执行（NFR-03）。多个引用按长度降序、用占位符隔离，避免短引用命中
 * 已插入的标记文本。
 */
export function highlightBlock(html, quotes = []) {
  let out = String(html ?? '')
  const marks = []
  const uniq = [...new Set(
    (Array.isArray(quotes) ? quotes : [])
      .map(q => String(q ?? '').trim())
      .filter(Boolean)
  )].sort((a, b) => b.length - a.length)

  for (const quote of uniq) {
    const needle = escapeHtml(quote)
    if (!needle) continue
    const at = out.indexOf(needle)
    if (at < 0) continue
    const token = `\u0000Q${marks.length}\u0000`
    out = out.slice(0, at) + token + out.slice(at + needle.length)
    marks.push(needle)
  }

  const finalHtml = out.replace(/\u0000Q(\d+)\u0000/g, (_, n) => {
    const i = Number(n)
    return i < marks.length ? `<mark class="quote-hit">${marks[i]}</mark>` : ''
  })
  return { html: finalHtml, hits: marks.length }
}

/**
 * 把批注答案按「子批注选中的词」切成 [{ text, hit }] 片段，让卡片能在解释文字里
 * 标出追问锚点（用户 2026-11-15 截图反馈：在解释里选词追问后，看不出选的是哪儿）。
 *
 * 与 highlightBlock 的区别：那边处理的是已消毒的块 HTML、要拼 <mark> 标签；
 * 这边只切纯文本，片段交给 Vue 的 {{ }} 渲染，**不产生任何 HTML 字符串**，
 * 所以 NFR-03 的转义口径完全不变（见 AnnotationCard.vue）。
 *
 * 匹配规则刻意与 resolve() 的 stale 判定保持一致：原样 String.includes / indexOf，
 * 不 normalize、不 trim、不用正则（引用词里带 . * + ? 也按字面匹配），
 * 避免出现「标了高亮却判成原文已修改」或反之。
 *
 * 多个引用按长度降序占区间：长词优先，短词若被长词覆盖就找下一次不重叠的出现，
 * 因此不会把同一个字标两遍、也不会切碎长词。命中不到的引用直接跳过。
 */
export function splitAnswerByQuotes(answer, quotes = []) {
  const text = String(answer ?? '')
  if (!text) return []

  const needles = [...new Set(
    (Array.isArray(quotes) ? quotes : [])
      .map(q => String(q ?? ''))
      .filter(Boolean)
  )].sort((a, b) => b.length - a.length)

  const ranges = []
  for (const needle of needles) {
    let from = 0
    let at
    while ((at = text.indexOf(needle, from)) >= 0) {
      const end = at + needle.length
      const overlaps = ranges.some(r => at < r.end && end > r.start)
      if (!overlaps) {
        ranges.push({ start: at, end })
        break
      }
      from = at + 1
    }
  }
  if (!ranges.length) return [{ text, hit: false }]

  ranges.sort((a, b) => a.start - b.start)
  const segments = []
  let cursor = 0
  for (const r of ranges) {
    if (r.start > cursor) segments.push({ text: text.slice(cursor, r.start), hit: false })
    segments.push({ text: text.slice(r.start, r.end), hit: true })
    cursor = r.end
  }
  if (cursor < text.length) segments.push({ text: text.slice(cursor), hit: false })
  return segments
}
