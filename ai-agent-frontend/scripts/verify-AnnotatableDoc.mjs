/**
 * T016 自检：
 *  1) TC-02（plan.md 8）：resolve() 三分支 —— 原段落命中 / 段落错位后 quote 重定位 / 找不到置 stale；
 *     外加子批注、父被删兜底、带 markdown 标记也能匹配、链深计算、分组与原文高亮。
 *  2) 用 Vite SSR 加载真实 SFC + vue/server-renderer 真渲染两栏，验块锚点、高亮、
 *     分组、卡片、编辑按钮按 bodyEditable 显隐、NFR-03 文本转义。
 *  3) 产出 preview/annotatable-doc.html：真实 SSR DOM + 编译后的 scoped 样式 +
 *     演示 JS（逐层折叠、选中文字提示）。预览页不调后端，点击是演示逻辑，
 *     真实交互要到 T017 接入阅读页后才能验。
 *
 * 运行：cd ai-agent-frontend && node scripts/verify-AnnotatableDoc.mjs
 */
import assert from 'node:assert/strict'
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import { createSSRApp, h } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { parse, compileStyle } from '@vue/compiler-sfc'
import {
  resolve, groupAnnotations, highlightBlock, escapeHtml, depthOf, normalizeForMatch
} from '../src/utils/annotationResolve.js'
import { splitBlocks } from '../src/utils/markdown.js'
import { computeAskPosition } from '../src/utils/annotationAskPosition.js'
import { computeRailMargins } from '../src/utils/annotationRailLayout.js'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const docPath = path.join(root, 'src/components/AnnotatableDoc.vue')
const cardPath = path.join(root, 'src/components/AnnotationCard.vue')
const docSource = readFileSync(docPath, 'utf-8')
const docDescriptor = parse(docSource, { filename: docPath }).descriptor
const cardDescriptor = parse(readFileSync(cardPath, 'utf-8'), { filename: cardPath }).descriptor

let passed = 0
function check(name, fn) {
  fn()
  passed += 1
  console.log(`  ok  ${name}`)
}

// ---------- TC-02：锚点重定位三分支 ----------
const tcBlocks = [
  { key: 'p-0', text: '第一段讲别的。', html: '<p>第一段讲别的。</p>' },
  { key: 'p-1', text: '检索时召回 topK 片段，再交给模型重排。', html: '<p>检索时召回 topK 片段，再交给模型重排。</p>' },
  { key: 'p-2', text: '另一段。', html: '<p>另一段。</p>' }
]
const flatForTc = [
  { id: 1, parentId: null, blockKey: 'p-1', quote: '召回 topK 片段', question: 'topK 是什么？', answer: '取余弦相似度最高的 K 条。', edited: false, createdAt: '2026-11-14T00:00:00Z' },
  { id: 2, parentId: null, blockKey: 'p-0', quote: '召回 topK 片段', question: '为啥要重排？', answer: '粗排快但不准。', edited: false, createdAt: '2026-11-14T00:00:01Z' },
  { id: 3, parentId: null, blockKey: 'p-1', quote: '早就被删掉的原句', question: '这句啥意思？', answer: '当初的解释还在。', edited: false, createdAt: '2026-11-14T00:00:02Z' },
  { id: 4, parentId: 1, blockKey: 'p-1', quote: '余弦相似度', question: '相似度怎么算？', answer: '看两个向量夹角。', edited: false, createdAt: '2026-11-14T00:00:03Z' },
  { id: 5, parentId: 1, blockKey: 'p-1', quote: '父答案里根本没有的词', question: '这个呢？', answer: '解释保留。', edited: false, createdAt: '2026-11-14T00:00:04Z' },
  { id: 6, parentId: 999, blockKey: 'p-2', quote: '另一段', question: '父被删了咋办？', answer: '按顶层兜底。', edited: false, createdAt: '2026-11-14T00:00:05Z' }
]
const tree = resolve(tcBlocks, flatForTc)

console.log('TC-02 · resolve() 三分支')
check('① 原段落命中：groupKey 仍是原 blockKey，stale=false', () => {
  assert.equal(tree.get(1).groupKey, 'p-1')
  assert.equal(tree.get(1).stale, false)
})
check('② 段落错位：按 quote 在全文重定位到 p-1，stale=false', () => {
  assert.equal(tree.get(2).blockKey, 'p-0')
  assert.equal(tree.get(2).groupKey, 'p-1')
  assert.equal(tree.get(2).stale, false)
})
check('③ 找不到：stale=true、groupKey=null，但问题与答案一字不少', () => {
  const a = tree.get(3)
  assert.equal(a.stale, true)
  assert.equal(a.groupKey, null)
  assert.equal(a.question, '这句啥意思？')
  assert.equal(a.answer, '当初的解释还在。')
})
check('组树：子批注挂在父 children 下且按 createdAt 升序', () => {
  assert.deepEqual(tree.get(1).children.map(c => c.id), [4, 5])
})
check('子批注按父答案是否含引用词判 stale，groupKey 跟随父', () => {
  assert.equal(tree.get(4).stale, false)
  assert.equal(tree.get(4).groupKey, 'p-1')
  assert.equal(tree.get(5).stale, true)
  assert.equal(tree.get(5).groupKey, 'p-1')
})
check('父被删但数据残留：parentId 置空按顶层兜底，内容不丢', () => {
  const a = tree.get(6)
  assert.equal(a.parentId, null)
  assert.equal(a.stale, false)
  assert.equal(a.answer, '按顶层兜底。')
})
check('带 markdown 标记的原句也能匹配（normalize），不误判 stale', () => {
  const blks = [{ key: 'p-0', text: '**粗体** 与 `行内代码` 同时在。', html: '<p><strong>粗体</strong> 与 <code>行内代码</code> 同时在。</p>' }]
  const t = resolve(blks, [{ id: 9, parentId: null, blockKey: 'p-0', quote: '粗体 与 行内代码', question: 'q', answer: 'a' }])
  assert.equal(t.get(9).stale, false)
  assert.match(normalizeForMatch(blks[0].text), /粗体 与 行内代码/)
})
check('空输入与脏数据不抛错', () => {
  assert.equal(resolve().size, 0)
  assert.equal(resolve([], null).size, 0)
  assert.equal(resolve(tcBlocks, undefined).size, 0)
})
check('depthOf：顶层 1、子 2、不存在 0，且环状数据不死循环', () => {
  assert.equal(depthOf(flatForTc, 1), 1)
  assert.equal(depthOf(flatForTc, 4), 2)
  assert.equal(depthOf(flatForTc, 999), 0)
  assert.equal(depthOf([{ id: 1, parentId: 2 }, { id: 2, parentId: 1 }], 1), 2)
})

console.log('\n就地提问框 · computeAskPosition 几何（用户实测反馈修补）')
const vp = { width: 1440, height: 900 }
const panelSize = { width: 360, height: 220 }
const wideRail = { left: 900, width: 420 }
const midSelection = { left: 200, right: 420, top: 640, bottom: 664 }
check('宽屏：横向贴右栏、纵向与选区齐平，不再固定在页面第一行', () => {
  const p = computeAskPosition({ viewport: vp, selection: midSelection, rail: wideRail, panel: panelSize })
  assert.equal(p.left, 908)
  assert.equal(p.top, 632)
  assert.ok(p.top > 400, '选区在页面下半段时面板也必须跟着下来')
})
check('宽屏：选区贴近视口底部时面板上移，不越出视口', () => {
  const p = computeAskPosition({
    viewport: vp, selection: { left: 200, right: 420, top: 880, bottom: 896 }, rail: wideRail, panel: panelSize
  })
  assert.equal(p.top, 668)
  assert.ok(p.top + panelSize.height <= vp.height - 12)
})
check('窄屏（右栏已下移）：优先落在选区下方', () => {
  const p = computeAskPosition({
    viewport: { width: 900, height: 800 },
    selection: { left: 120, right: 300, top: 300, bottom: 322 }, rail: null, panel: panelSize
  })
  assert.deepEqual(p, { left: 120, top: 334 })
})
check('窄屏：下方放不下时翻到选区上方', () => {
  const p = computeAskPosition({
    viewport: { width: 900, height: 800 },
    selection: { left: 120, right: 300, top: 700, bottom: 722 }, rail: null, panel: panelSize
  })
  assert.equal(p.top, 468)
})
check('极窄视口：left 被 clamp，面板不会撑出横向滚动', () => {
  const p = computeAskPosition({
    viewport: { width: 320, height: 600 },
    selection: { left: 290, right: 315, top: 100, bottom: 120 }, rail: null, panel: panelSize
  })
  assert.equal(p.left, 12)
  assert.ok(p.left + Math.min(panelSize.width, 320 - 24) <= 320 - 12)
})
check('右栏太窄（<240px）按窄屏分支处理，脏输入不抛错', () => {
  const p = computeAskPosition({
    viewport: vp,
    selection: { left: 200, right: 420, top: 300, bottom: 322 },
    rail: { left: 1200, width: 200 }, panel: panelSize
  })
  assert.deepEqual(p, { left: 200, top: 334 })
  assert.doesNotThrow(() => computeAskPosition({ viewport: { width: 0, height: 0 }, selection: {}, rail: null, panel: {} }))
})

console.log('\n右栏分组对齐 · computeRailMargins（用户第二次实测反馈）')
check('单个分组精确对齐到对应正文块高度，不再停在右栏第一行', () => {
  const m = computeRailMargins({
    groups: [{ key: 'p-38', height: 180 }],
    blockTops: { 'p-38': 1200 }, startTop: 40, minGap: 18
  })
  assert.equal(m['p-38'], 1160)
  assert.equal(40 + m['p-38'], 1200, '分组最终 top 必须等于正文块 top')
})
check('相邻段落都有批注：后一组顺延，绝不盖住前一组', () => {
  const m = computeRailMargins({
    groups: [{ key: 'p-3', height: 180 }, { key: 'p-4', height: 160 }],
    blockTops: { 'p-3': 600, 'p-4': 630 }, startTop: 40, minGap: 18
  })
  assert.equal(m['p-3'], 560)
  assert.equal(m['p-4'], 0)
  const firstTop = 40 + m['p-3']
  const secondTop = firstTop + 180 + 18 + m['p-4']
  assert.ok(secondTop >= firstTop + 180, '第二组必须落在第一组下方')
})
check('目标高度已被前一组占满时 margin=0，不会把分组往上拽', () => {
  const m = computeRailMargins({
    groups: [{ key: 'a', height: 200 }, { key: 'b', height: 100 }],
    blockTops: { a: 500, b: 100 }, startTop: 40, minGap: 18
  })
  assert.equal(m.a, 460)
  assert.equal(m.b, 0)
})
check('__rest__ / __stale__ 没有对应正文块：紧接上一组只留一个 gap', () => {
  const m = computeRailMargins({
    groups: [{ key: 'p-1', height: 100 }, { key: '__stale__', height: 80 }],
    blockTops: { 'p-1': 300 }, startTop: 40, minGap: 18
  })
  assert.equal(m['p-1'], 260)
  assert.equal(m['__stale__'], 18)
})
check('脏输入不抛错：负数高度按 0、缺 key 跳过、minGap 为 0 也安全', () => {
  assert.doesNotThrow(() => computeRailMargins({}))
  const m = computeRailMargins({
    groups: [{ key: 'x', height: -50 }, null, { height: 20 }],
    blockTops: { x: 10 }, startTop: 0, minGap: 0
  })
  assert.equal(m.x, 10)
  assert.deepEqual(Object.keys(m), ['x'])
})

console.log('\nTC-02 附加 · 分组与原文高亮')
const tcGroups = groupAnnotations(tcBlocks, tree)
check('按正文顺序分组，顶层 stale 统一进最后的「原文已修改」组', () => {
  assert.ok(tcGroups.length >= 2)
  assert.equal(tcGroups.at(-1).title, '原文已修改')
  assert.equal(tcGroups.at(-1).staleGroup, true)
  assert.ok(tcGroups.at(-1).roots.some(a => a.id === 3))
  assert.ok(tcGroups.some(g => g.roots.some(a => a.id === 1)))
})
check('子批注不单独成组（挂在父卡片追问链里，只显示标签）', () => {
  const all = tcGroups.flatMap(g => g.roots.map(a => a.id))
  assert.ok(!all.includes(4))
  assert.ok(!all.includes(5))
})
check('escapeHtml 转义 & < > "', () => {
  assert.equal(escapeHtml('<img src=x onerror="1">&'), '&lt;img src=x onerror=&quot;1&quot;&gt;&amp;')
})
check('highlightBlock 命中：插入 <mark class="quote-hit">，只替换一次', () => {
  const r = highlightBlock('<p>召回 topK 片段，再召回 topK 片段。</p>', ['召回 topK 片段'])
  assert.equal(r.hits, 1)
  assert.equal((r.html.match(/<mark class="quote-hit">/g) || []).length, 1)
  assert.match(r.html, /<mark class="quote-hit">召回 topK 片段<\/mark>/)
})
check('highlightBlock 未命中：原样返回 + hits=0（组件降级成色条角标）', () => {
  const html = '<p><strong>被标签切开</strong>的句子</p>'
  const r = highlightBlock(html, ['被标签切开的句子'])
  assert.equal(r.hits, 0)
  assert.equal(r.html, html)
})
check('highlightBlock 多引用按长度降序，互不破坏、占位符不残留', () => {
  const r = highlightBlock('<p>余弦相似度 与 相似度 都要标。</p>', ['相似度', '余弦相似度'])
  assert.equal(r.hits, 2)
  assert.match(r.html, /<mark class="quote-hit">余弦相似度<\/mark>/)
  assert.match(r.html, /<mark class="quote-hit">相似度<\/mark>/)
  assert.doesNotMatch(r.html, /\u0000/)
})
check('NFR-03：quote 里的 HTML 被转义，高亮不会注入活标签或属性', () => {
  const r = highlightBlock('<p>正常正文</p>', ['<img src=x onerror="alert(1)">'])
  assert.equal(r.hits, 0)
  assert.doesNotMatch(r.html, /<img/)
  const r2 = highlightBlock('<p>&lt;script&gt;alert(1)&lt;/script&gt; 出现在这里</p>', ['<script>alert(1)</script>'])
  assert.equal(r2.hits, 1)
  assert.doesNotMatch(r2.html, /<script>/)
  assert.match(r2.html, /<mark class="quote-hit">&lt;script&gt;/)
})

// ---------- SSR：真实 SFC 渲染 ----------
const vite = await createServer({
  root,
  server: { middlewareMode: true },
  appType: 'custom',
  logLevel: 'error'
})

const markdown = [
  '# 向量检索这一章',
  '',
  '检索时先按向量相似度排序，召回 topK 片段，再把这 K 个片段交给模型做重排。K 太小会漏掉关键上下文，K 太大又会引入噪声。',
  '',
  '**余弦相似度** 衡量两个向量的夹角；嵌入空间由模型把文本映射成向量。',
  '',
  '**粗体** 与 `行内代码` 同时在一段里，引用原句会被标签切开。',
  '',
  '| 指标 | 含义 |',
  '| --- | --- |',
  '| topK | 召回条数 |',
  '',
  '```js',
  'const k = 5',
  '```'
].join('\n')

const xss = '<img src=x onerror="alert(1)">'
const demoAnnotations = [
  { id: 12, parentId: null, blockKey: 'p-1', quote: '召回 topK 片段', question: 'topK 是什么？', answer: '取余弦相似度最高的 K 个片段。', edited: false, createdAt: '2026-11-14T00:00:00Z' },
  { id: 13, parentId: 12, blockKey: 'p-1', quote: '余弦相似度', question: '相似度怎么算？', answer: '看两个向量在嵌入空间里的夹角。', edited: false, createdAt: '2026-11-14T00:00:01Z' },
  { id: 14, parentId: 13, blockKey: 'p-1', quote: '嵌入空间', question: '什么是嵌入空间？', answer: '把文本变成向量后的多维空间。', edited: false, createdAt: '2026-11-14T00:00:02Z' },
  { id: 21, parentId: null, blockKey: 'p-2', quote: '被删掉的原句', question: `这句 ${xss} 还能看见吗？`, answer: `原答案保留 ${xss}`, edited: true, createdAt: '2026-11-14T00:00:03Z' },
  // quote 在 markdown 原文里能匹配（normalize 去标记），但在渲染后的 HTML 里被
  // <strong>/<code> 切开，highlightBlock 匹配不到 → 触发色条 + 角标降级
  { id: 22, parentId: null, blockKey: 'p-3', quote: '粗体 与 行内代码', question: '这两种标记有啥区别？', answer: '一个加粗，一个标代码。', edited: false, createdAt: '2026-11-14T00:00:04Z' }
]
const allIds = demoAnnotations.map(a => a.id)

function renderDoc(extra = {}) {
  const app = createSSRApp({
    render: () => h(Doc, {
      markdown,
      targetType: 'SECTION',
      targetId: 'demo-section',
      initialAnnotations: demoAnnotations,
      initialExpandedIds: allIds,
      ...extra
    })
  })
  return renderToString(app)
}

let Doc, Card, readonlyHtml, editableHtml
try {
  const mod = await vite.ssrLoadModule('/src/components/AnnotatableDoc.vue')
  Doc = mod.default
  Card = (await vite.ssrLoadModule('/src/components/AnnotationCard.vue')).default
  check('Vite SSR 能编译加载 AnnotatableDoc（含子组件、provide、defineExpose）', () => assert.ok(Doc))
  readonlyHtml = await renderDoc()
  editableHtml = await renderDoc({ bodyEditable: true })

  console.log('\nSSR · 两栏渲染')
  check('逐块 data-block-key 渲染，块数与 splitBlocks 一致', () => {
    const blks = splitBlocks(markdown)
    assert.ok(blks.length >= 5)
    for (const b of blks) {
      assert.match(readonlyHtml, new RegExp(`data-block-key="${b.key}"`))
    }
  })
  check('正文里被引用的原句真的被 <mark class="quote-hit"> 标色', () => {
    assert.match(readonlyHtml, /<mark class="quote-hit">召回 topK 片段<\/mark>/)
  })
  check('高亮匹配不到时降级：色条 + 批注数角标（引用被 strong/code 切开的那条）', () => {
    assert.match(readonlyHtml, /block-has-annotation/)
    assert.match(readonlyHtml, /block-badge/)
  })
  check('右栏按段落分组，stale 顶层进「原文已修改」组', () => {
    assert.match(readonlyHtml, /annotation-group/)
    assert.match(readonlyHtml, /第 2 段/)
    assert.match(readonlyHtml, /原文已修改/)
  })
  check('T015 卡片被渲染：顶层 + 展开态追问链 + 层级标签', () => {
    for (const id of ['12', '13', '14', '21']) {
      assert.match(readonlyHtml, new RegExp(`data-annotation-id="${id}"`))
    }
    assert.match(readonlyHtml, /第 2 层/)
    assert.match(readonlyHtml, /data-children-of="12"/)
  })
  check('bodyEditable=false 完全没有「编辑正文」按钮与 textarea', () => {
    assert.doesNotMatch(readonlyHtml, /编辑正文/)
    assert.doesNotMatch(readonlyHtml, /body-textarea/)
  })
  check('bodyEditable=true 才出现「编辑正文」按钮', () => {
    assert.match(editableHtml, /编辑正文/)
    assert.match(editableHtml, /整篇 Markdown 原文/)
  })
  check('NFR-03：批注里的 HTML 探针被转义成文本，v-html 只用在正文块', () => {
    assert.doesNotMatch(readonlyHtml, /<img src=x/)
    assert.match(readonlyHtml, /&lt;img/)
    assert.equal((docDescriptor.template.content.match(/v-html/g) || []).length, 1)
    assert.match(docDescriptor.template.content, /class="doc-block" v-html="b\.html"/)
    assert.doesNotMatch(cardDescriptor.template.content, /v-html/)
  })
  check('SSR 输出无占位符残留、无横向滚动用的固定宽布局', () => {
    assert.doesNotMatch(readonlyHtml, /\u0000/)
    assert.match(docSource, /@media \(max-width: 1024px\)/)
    assert.match(docSource, /grid-template-columns: 1fr/)
  })
  check('组件契约：provideExpandedIds / 真接口 / 不跨阶段 import notes.js', () => {
    assert.match(docSource, /provideExpandedIds\(\)/)
    assert.match(docSource, /from '\.\.\/api\/annotations\.js'/)
    // 只查 import 语句：注释里也会出现 api/notes.js 这个文件名，别误伤
    assert.doesNotMatch(docSource, /import\s+[^;]*from\s+['"][^'"]*notes\.js['"]/)
    assert.doesNotMatch(docSource, /['"]\/notes\//)
    assert.match(docSource, /emit\('save', draft\.value\)/)
  })
  // 2026-11-15 真实浏览器白屏复盘：watch(() => [... editing.value ...]) 写在了
  // `const editing = ref(false)` 之前，浏览器端 Vue 在 setup 阶段立即跑 watch getter
  // 收集依赖 → 命中 const 的 TDZ → 组件 setup 抛错 → 整个正文栏渲染不出来。
  // SSR 下 watch 不跑 getter，所以 SSR 自检全绿也照样白屏；这条静态断言补上这个盲区。
  check('setup 防 TDZ：watch getter 引用的 ref 必须在 watch 之前声明', () => {
    const lines = docDescriptor.scriptSetup.content.split(/\r?\n/)
    const declared = new Map()
    lines.forEach((l, i) => {
      const m = l.match(/^\s*(?:const|let)\s+([A-Za-z_$][\w$]*)\s*=/)
      if (m && !declared.has(m[1])) declared.set(m[1], i)
    })
    const bad = []
    lines.forEach((l, i) => {
      const gm = l.match(/watch\(\(\)\s*=>\s*\[([^\]]*)\]/)
      if (!gm) return
      for (const ref of gm[1].matchAll(/\b([A-Za-z_$][\w$]*)\.value/g)) {
        const name = ref[1]
        if (declared.has(name) && declared.get(name) > i) {
          bad.push(`${name}.value 在第 ${i + 1} 行被 watch 引用，第 ${declared.get(name) + 1} 行才声明`)
        }
      }
    })
    assert.deepEqual(bad, [], bad.join('; '))
  })
  check('选区委托：同块校验 / 卡内 closest / 跨段落提示 / 限额与链深预检', () => {
    assert.match(docDescriptor.template.content, /@mouseup="onMouseUp"/)
    assert.match(docSource, /closest\('\[data-block-key\]'\)/)
    assert.match(docSource, /closest\('\[data-annotation-id\]'\)/)
    assert.match(docSource, /请只选一段内的文字/)
    assert.match(docSource, /flat\.value\.length >= 200/)
    assert.match(docSource, /depthOf\(flat\.value, next\.parentId\) >= 8/)
  })
  check('提问框就地浮出：Teleport + 选区定位 + 打开即聚焦 + 滚动跟随 + 关闭清理', () => {
    const tpl = docDescriptor.template.content
    assert.match(tpl, /<Teleport to="body">/)
    assert.match(tpl, /ref="askPanelRef" class="ask-panel" :style="askStyle"/)
    assert.match(tpl, /ref="askInputRef"/)
    assert.match(docSource, /from '\.\.\/utils\/annotationAskPosition\.js'/)
    assert.match(docSource, /computeAskPosition\(\{/)
    assert.match(docSource, /getRangeAt\(0\)\.cloneRange\(\)/)
    assert.match(docSource, /focus\(\{ preventScroll: true \}\)/)
    assert.match(docSource, /addEventListener\('scroll', positionAsk, true\)/)
    assert.match(docSource, /addEventListener\('resize', positionAsk\)/)
    assert.match(docSource, /removeEventListener\('scroll', positionAsk, true\)/)
    assert.match(docSource, /onUnmounted\([\s\S]*?stopFollowing\(\)/)
    assert.match(docSource, /e\.key === 'Escape'/)
    assert.doesNotMatch(docSource, /@keydown\.ctrl\.enter/)
  })
  check('面板 CSS 是 fixed 浮层，不再占右栏第一行', () => {
    const css = docDescriptor.styles[0].content
    assert.match(css, /\.ask-panel \{[\s\S]*?position: fixed/)
    assert.match(css, /width: min\(360px, calc\(100vw - 24px\)\)/)
    assert.match(css, /z-index: 60/)
  })
  check('右栏分组按正文块高度对齐：量测重算 + flex gap + 窄屏回退', () => {
    const tpl = docDescriptor.template.content
    assert.match(tpl, /ref="railGroupsRef" class="rail-groups"/)
    assert.match(tpl, /:data-group-key="g\.key"/)
    assert.match(tpl, /groupMargins\[g\.key\] \? \{ marginTop: groupMargins\[g\.key\] \} : null/)
    assert.match(docSource, /from '\.\.\/utils\/annotationRailLayout\.js'/)
    assert.match(docSource, /computeRailMargins\(\{/)
    assert.match(docSource, /getAttribute\('data-group-key'\)/)
    assert.match(docSource, /getAttribute\('data-block-key'\)/)
    assert.match(docSource, /addEventListener\('resize', alignGroups\)/)
    assert.match(docSource, /window\.innerWidth <= 1024/)
    assert.match(docSource, /@click="onLayoutClick"/)
    assert.match(docSource, /data-toggle-id/)
    const css = docDescriptor.styles[0].content
    assert.match(css, /\.rail-groups \{[\s\S]*?display: flex/)
    assert.match(css, /gap: 18px/)
    assert.match(css, /@media \(max-width: 1024px\)[\s\S]*?margin-top: 0 !important/)
  })

  // ---------- 可点击静态预览页 ----------
  const compileScoped = (descriptor, sfcPath, scopeId) => compileStyle({
    source: descriptor.styles[0].content,
    filename: sfcPath,
    id: scopeId.replace(/^data-v-/, ''),
    scoped: !!descriptor.styles[0].scoped
  }).code

  const docScope = Doc.__scopeId || 'data-v-doc'
  const cardScope = Card.__scopeId || 'data-v-card'
  const docCss = compileScoped(docDescriptor, docPath, docScope)
  const cardCss = compileScoped(cardDescriptor, cardPath, cardScope)
  const globalCss = readFileSync(path.join(root, 'src/styles/global.css'), 'utf-8')

  const dedupeScope = (s) => s.replace(/(data-v-[\w-]+)\s+\1/g, '$1')
  let previewHtml = dedupeScope(readonlyHtml)
  if (!Doc.__scopeId) {
    previewHtml = previewHtml.replace(/<([a-z][\w-]*)/g, '<$1 ' + docScope)
  }

  const page = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>T016 · 可批注正文（两栏预览）</title>
<style>${globalCss}</style>
<style>${docCss}</style>
<style>${cardCss}</style>
<style>
  body { padding: 26px 30px 64px; max-width: 1240px; margin: 0 auto; }
  h1 { font-size: 20px; margin: 0 0 6px; }
  .hint { font-size: 12.5px; color: var(--ink-dim); line-height: 1.8; margin: 0 0 16px; }
  .tools { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-bottom: 18px; }
  .tools button { font-size: 12px; padding: 5px 12px; border: 1px solid var(--line); border-radius: 6px; color: var(--ink-soft); background: var(--surface); }
  .tools button:hover { border-color: var(--accent); color: var(--accent); }
  #selHint { font-size: 12px; color: #854f0b; background: #faeeda; border-radius: 6px; padding: 5px 10px; min-height: 26px; }
  .frame { border: 1px solid var(--line); border-radius: 12px; padding: 22px; background: var(--surface); }
  [hidden] { display: none !important; }
  .annotation-children { padding-left: 0; }
  .doc-body { max-width: none; }
</style>
</head>
<body>
  <h1>T016 · 可批注正文（两栏 + 原文高亮）</h1>
  <p class="hint">
    这一页由 <b>组件真实 SSR 渲染</b> + 编译后的 scoped 样式产出：左边逐块正文（黄色 = 引用在原文中的位置高亮），右边按段落分组的批注卡片。<br>
    <b>选中正文里任意一句话</b>，提问框会<b>就地浮在选区旁并自动聚焦</b>（宽屏贴右栏、与选区齐平；窄屏落在选区下方），不用滚回页面第一行；右侧的批注卡片<b>对齐到对应正文段落的高度</b>，不再全挤在右栏第一行；<b>点蓝色小横条</b>逐层展开追问；窗口拖窄到 1024px 以下，右栏整体移到正文下方并恢复顺序堆叠。<br>
    这里的浮层是<b>预览页演示 JS</b>（注入的是组件同一个定位函数的源码快照），不调后端；真实提问 / 编辑 / 删除要启动后端 + <code>npm run dev</code> 在浏览器验。
  </p>
  <div class="tools">
    <button type="button" id="expandAll">全部展开</button>
    <button type="button" id="collapseAll">全部收起</button>
    <span id="selHint">试着用鼠标选中左边正文里的一句话 →</span>
  </div>
  <div class="frame" id="frame">${previewHtml}</div>

<script>
(function () {
  // 右栏分组对齐演示：注入组件同一个纯函数的源码快照，量的是这一页真实 DOM。
  ${computeRailMargins.toString()}

  function alignDemo() {
    var frame = document.getElementById('frame');
    var layout = frame && frame.querySelector('.doc-layout');
    var railGroups = frame && frame.querySelector('.rail-groups');
    if (!layout || !railGroups) return;
    var nodes = Array.prototype.slice.call(railGroups.querySelectorAll('[data-group-key]'));
    if (window.innerWidth <= 1024) {
      nodes.forEach(function (el) { el.style.marginTop = ''; });
      return;
    }
    var top = layout.getBoundingClientRect().top;
    var blockTops = {};
    Array.prototype.slice.call(layout.querySelectorAll('[data-block-key]')).forEach(function (el) {
      blockTops[el.getAttribute('data-block-key')] = el.getBoundingClientRect().top - top;
    });
    var margins = computeRailMargins({
      groups: nodes.map(function (el) { return { key: el.getAttribute('data-group-key'), height: el.offsetHeight }; }),
      blockTops: blockTops,
      startTop: railGroups.getBoundingClientRect().top - top,
      minGap: 18
    });
    nodes.forEach(function (el) { el.style.marginTop = (margins[el.getAttribute('data-group-key')] || 0) + 'px'; });
  }

  var toggles = Array.prototype.slice.call(document.querySelectorAll('[data-toggle-id]'));
  var groups = Array.prototype.slice.call(document.querySelectorAll('[data-children-of]'));
  function groupFor(id) { return document.querySelector('[data-children-of="' + id + '"]'); }
  function countOf(g) { return g ? g.querySelectorAll('.annotation-card').length : 0; }
  function paint(btn, isOpen, n) {
    btn.setAttribute('aria-expanded', isOpen ? 'true' : 'false');
    btn.classList.toggle('open', isOpen);
    var chev = btn.querySelector('.chev'); if (chev) chev.textContent = isOpen ? '▾' : '▸';
    var label = btn.querySelector('span:last-child');
    if (label) label.textContent = isOpen ? '收起追问' : '展开 ' + n + ' 条追问';
  }
  function collapseInside(group) {
    Array.prototype.slice.call(group.querySelectorAll('[data-children-of]')).forEach(function (g) { g.hidden = true; });
    Array.prototype.slice.call(group.querySelectorAll('[data-toggle-id]')).forEach(function (b) {
      paint(b, false, countOf(groupFor(b.getAttribute('data-toggle-id'))));
    });
  }
  function setAll(isOpen) {
    groups.forEach(function (g) { g.hidden = !isOpen; });
    toggles.forEach(function (btn) { paint(btn, isOpen, countOf(groupFor(btn.getAttribute('data-toggle-id')))); });
    alignDemo();
  }
  toggles.forEach(function (btn) {
    var group = groupFor(btn.getAttribute('data-toggle-id'));
    btn.addEventListener('click', function () {
      if (!group) return;
      var willOpen = group.hidden;
      group.hidden = !willOpen;
      if (!willOpen) collapseInside(group);
      paint(btn, willOpen, countOf(group));
      alignDemo();
    });
  });
  document.getElementById('expandAll').addEventListener('click', function () { setAll(true); });
  document.getElementById('collapseAll').addEventListener('click', function () { setAll(false); });
  setAll(false);

  // 就地提问浮层演示：定位函数用的是组件同一份源码，样式用编译后的 scoped CSS。
  // 这是预览页 JS，不是组件实例：不调 /annotations/ask，也不会真的写库。
  var scopeAttr = '${docScope}';
  var demoPanel = null, demoRect = null;
  ${computeAskPosition.toString()}

  function closeDemo() {
    if (demoPanel) demoPanel.remove();
    demoPanel = null; demoRect = null;
    window.removeEventListener('scroll', placeDemo, true);
    window.removeEventListener('resize', placeDemo);
  }
  function placeDemo() {
    if (!demoPanel || !demoRect) return;
    var rail = document.querySelector('.note-rail');
    var rr = rail ? rail.getBoundingClientRect() : null;
    var p = computeAskPosition({
      viewport: { width: window.innerWidth, height: window.innerHeight },
      selection: demoRect,
      rail: rr && rr.width >= 240 && window.innerWidth > 1024 ? { left: rr.left, width: rr.width } : null,
      panel: { width: demoPanel.offsetWidth || 360, height: demoPanel.offsetHeight || 220 }
    });
    demoPanel.style.left = p.left + 'px';
    demoPanel.style.top = p.top + 'px';
    demoPanel.style.right = 'auto';
  }
  function openDemo(text, rect, inCard) {
    closeDemo();
    demoRect = rect;
    var el = document.createElement('div');
    el.className = 'ask-panel';
    el.innerHTML = [
      '<div class="ask-head"><span>' + (inCard ? '在解释里选词追问' : '就这句话提问') + '</span><button type="button" class="ask-close" aria-label="取消提问">×</button></div>',
      '<div class="ask-quote"></div>',
      '<textarea class="ask-input" rows="2" maxlength="500" placeholder="想问什么？例如：它在这一段里指什么？"></textarea>',
      '<div class="ask-actions"><span class="ask-tip">Ctrl + Enter 提交 · Esc 取消</span><button type="button" class="soft-btn">取消</button><button type="button" class="solid-btn" disabled>提问</button></div>'
    ].join('');
    el.querySelector('.ask-quote').textContent = text.slice(0, 120);
    el.setAttribute(scopeAttr, '');
    Array.prototype.forEach.call(el.querySelectorAll('*'), function (n) { n.setAttribute(scopeAttr, ''); });
    document.body.appendChild(el);
    demoPanel = el;
    placeDemo();
    el.querySelector('.ask-close').addEventListener('click', closeDemo);
    el.querySelectorAll('.soft-btn').forEach(function (b) { b.addEventListener('click', closeDemo); });
    var input = el.querySelector('.ask-input');
    input.addEventListener('keydown', function (e) { if (e.key === 'Escape') closeDemo(); });
    input.focus({ preventScroll: true });
    window.addEventListener('scroll', placeDemo, true);
    window.addEventListener('resize', placeDemo);
  }

  var hint = document.getElementById('selHint');
  document.getElementById('frame').addEventListener('mouseup', function () {
    var sel = window.getSelection();
    var text = sel ? sel.toString().trim() : '';
    if (!text) return;
    var inCard = sel.anchorNode && sel.anchorNode.parentElement &&
      sel.anchorNode.parentElement.closest('[data-annotation-id]');
    var rect = sel.rangeCount ? sel.getRangeAt(0).getBoundingClientRect() : null;
    hint.textContent = (inCard ? '在批注解释里选中：' : '在正文里选中：') +
      '「' + text.slice(0, 40) + (text.length > 40 ? '…' : '') + '」→ 就地弹出提问框（真实页面会生成' +
      (inCard ? '子批注' : '顶层批注') + '）';
    if (rect) openDemo(text, { left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom }, !!inCard);
  });
  alignDemo();
  window.addEventListener('resize', alignDemo);
})();
</script>
</body>
</html>`

  const outDir = path.join(root, 'preview')
  mkdirSync(outDir, { recursive: true })
  const outFile = path.join(outDir, 'annotatable-doc.html')
  writeFileSync(outFile, page, 'utf-8')
  check('两栏预览页已生成：scoped 样式命中、高亮、折叠 JS、窄屏媒体查询都在', () => {
    assert.match(page, /mark\.quote-hit/)
    assert.match(page, /data-block-key="p-1"/)
    assert.match(page, /data-annotation-id="12"/)
    assert.match(page, /class="quote-hit"/)
    assert.match(page, /id="expandAll"/)
    assert.match(page, /@media \(max-width: 1024px\)/)
    assert.ok(page.length > 8000)
  })
  check('预览页能演示就地浮层：同一定位函数 + scoped 属性 + 自动聚焦 + Esc/× 关闭', () => {
    assert.match(page, /function computeAskPosition\(/)
    assert.match(page, /el\.className = 'ask-panel'/)
    assert.match(page, /setAttribute\(scopeAttr, ''\)/)
    assert.match(page, /focus\(\{ preventScroll: true \}\)/)
    assert.match(page, /addEventListener\('scroll', placeDemo, true\)/)
    assert.match(page, /key === 'Escape'/)
    assert.match(page, /就地浮在选区旁/)
  })
  check('预览页能演示分组对齐：同一个 margin 函数 + 真实块测量 + 窄屏清零', () => {
    assert.match(page, /function computeRailMargins\(/)
    assert.match(page, /data-group-key=/)
    assert.match(page, /alignDemo\(\)/)
    assert.match(page, /window\.addEventListener\('resize', alignDemo\)/)
    assert.match(page, /对齐到对应正文段落的高度/)
  })

  console.log('\n  预览页结构：')
  console.log(`    左栏 ${splitBlocks(markdown).length} 个块：标题 / 黄色高亮段落 / 普通段落 / 被标签切开的降级段落 / 表格 / 代码块`)
  console.log('    右栏 第 2 段 → 批注 12（展开 2 条追问）→ 13 → 14；降级段落组 → 22；末尾「原文已修改」组 → 21')
  console.log(`  预览页：${outFile}`)
  console.log(`\nT016 自检 ${passed}/${passed} 断言通过（含 TC-02 三分支）。`)
} finally {
  await vite.close()
}
