/**
 * T015 自检：用 Vite 的 SSR 模块加载真实 SFC，再用 vue/server-renderer 真渲染。
 *
 * 2026-11-14 设计调整后验的是：追问默认折叠（子卡片根本不在 DOM 里）、点开后
 * 独立平铺（无缩进、无嵌套边框）、层级标签、折叠状态按 id 共享、状态标签、
 * 删除提示 N、NFR-03 文本转义。
 * 2026-11-15 追加：父答案里的「追问锚点」高亮（子批注选中的词），
 * 验纯函数切分、SSR 输出、只标子批注不标自己、以及分段渲染不破坏 NFR-03。
 *
 * 顺带产出 preview/annotation-card.html：把「全展开」的 SSR HTML 配上一段
 * 演示用原生 JS，做成可逐层点开 / 收起的静态预览页。预览页不可调后端，
 * 它的点击逻辑是演示 JS，不是组件代码；组件真实交互要到 T017 才能验。
 *
 * 运行：cd ai-agent-frontend && node scripts/verify-AnnotationCard.mjs
 */
import assert from 'node:assert/strict'
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import { createSSRApp, h } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { parse, compileStyle } from '@vue/compiler-sfc'
import { ANNOTATION_EXPANDED_KEY, createExpandedIds } from '../src/utils/annotationExpand.js'
import { splitAnswerByQuotes } from '../src/utils/annotationResolve.js'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const sfcPath = path.join(root, 'src/components/AnnotationCard.vue')
const source = readFileSync(sfcPath, 'utf-8')
const descriptor = parse(source, { filename: sfcPath }).descriptor

const vite = await createServer({
  root,
  server: { middlewareMode: true },
  appType: 'custom',
  logLevel: 'error'
})

let passed = 0
function check(name, fn) {
  fn()
  passed += 1
  console.log(`  ok  ${name}`)
}

// 一条五层链：顶层 12 → 13 → 14 → 15 → 16（共 4 条追问）
const chain = {
  id: 12, parentId: null, blockKey: 'p-1', quote: '召回 topK 片段',
  question: 'topK 是什么？', answer: '取余弦相似度最高的 K 个片段。',
  edited: false, createdAt: '2026-11-14T00:00:00Z', stale: false,
  children: [{
    id: 13, parentId: 12, blockKey: 'p-1', quote: '余弦相似度',
    question: '相似度怎么算？', answer: '看两个向量的夹角。',
    edited: false, createdAt: '2026-11-14T00:00:01Z', stale: false,
    children: [{
      id: 14, parentId: 13, blockKey: 'p-1', quote: '夹角',
      question: '夹角是谁的？', answer: '向量在嵌入空间里的夹角。',
      edited: false, createdAt: '2026-11-14T00:00:02Z', stale: false,
      children: [{
        id: 15, parentId: 14, blockKey: 'p-1', quote: '嵌入空间',
        question: '什么是嵌入空间？', answer: '把文本变成向量后的多维空间。',
        edited: false, createdAt: '2026-11-14T00:00:03Z', stale: false,
        children: [{
          id: 16, parentId: 15, blockKey: 'p-1', quote: '向量',
          question: '向量怎么来的？', answer: '由嵌入模型把文本映射成一串数字。',
          edited: false, createdAt: '2026-11-14T00:00:04Z', stale: false,
          children: []
        }]
      }]
    }]
  }]
}

// 状态标签 + XSS 探针
const xss = '<img src=x onerror="alert(1)">'
const states = {
  id: 21, parentId: null, blockKey: 'p-2', quote: '被删掉的原句',
  question: `这句 ${xss} 还能看见吗？`,
  answer: `已编辑过的答案 ${xss}`,
  edited: true, createdAt: '2026-11-14T00:00:05Z', stale: true, children: []
}

function collectIds(node, acc = []) {
  acc.push(node.id)
  for (const child of node.children || []) collectIds(child, acc)
  return acc
}

try {
  const mod = await vite.ssrLoadModule('/src/components/AnnotationCard.vue')
  const Card = mod.default
  check('Vite SSR 能编译加载 SFC（多根 fragment + 自引用不炸）', () => assert.ok(Card))

  if (Array.isArray(Card.emits)) {
    check('组件声明 changed / error 两个 emit', () =>
      assert.deepEqual([...Card.emits].sort(), ['changed', 'error']))
  } else {
    check('源码声明 changed / error 两个 emit', () =>
      assert.match(source, /defineEmits\(\[.*changed.*error.*\]/))
  }

  check('折叠状态模块：按 id toggle / openAll / clear', () => {
    const s = createExpandedIds()
    assert.equal(s.has(12), false)
    s.toggle(12)
    assert.equal(s.has(12), true)
    s.toggle(12)
    assert.equal(s.has(12), false)
    s.openAll([12, 13])
    assert.equal(s.has(12), true)
    assert.equal(s.has(13), true)
    s.clear()
    assert.equal(s.has(12), false)
  })

  // 折叠态：不 provide，组件各自回落到局部状态，默认全部收起
  const render = (annotation, state) => {
    const app = createSSRApp({ render: () => h(Card, { annotation, depth: 0 }) })
    if (state) app.provide(ANNOTATION_EXPANDED_KEY, state)
    return renderToString(app)
  }
  const collapsedHtml = await render(chain)
  const stateHtml = await render(states)

  // 展开态：父组件 provide 一个按 id 全展开的共享状态（T016 的接管方式）
  const allOpen = createExpandedIds(collectIds(chain))
  const expandedHtml = await render(chain, allOpen)

  check('折叠态：顶层卡片与折叠小横条都在', () => {
    assert.match(collapsedHtml, /data-annotation-id="12"/)
    assert.match(collapsedHtml, /data-toggle-id="12"/)
    assert.match(collapsedHtml, /展开 4 条追问/)
    assert.match(collapsedHtml, /aria-expanded="false"/)
  })
  check('折叠态：13~16 四层子卡片真的不在 DOM 里（v-if，不是隐藏）', () => {
    for (const id of ['13', '14', '15', '16']) {
      assert.doesNotMatch(collapsedHtml, new RegExp(`data-annotation-id="${id}"`))
    }
    assert.doesNotMatch(collapsedHtml, /data-children-of=/)
  })

  check('展开态：父组件 provide 后 13~16 全部渲染，逐层容器都在', () => {
    for (const id of ['13', '14', '15', '16']) {
      assert.match(expandedHtml, new RegExp(`data-annotation-id="${id}"`))
    }
    for (const id of ['12', '13', '14', '15']) {
      assert.match(expandedHtml, new RegExp(`data-children-of="${id}"`))
    }
  })
  check('展开态：折叠条切换成收起文案与 aria-expanded=true', () => {
    assert.match(expandedHtml, /收起追问/)
    assert.match(expandedHtml, /aria-expanded="true"/)
  })
  check('独立平铺：不再有缩进 margin-left、不再有 is-child 嵌套样式', () => {
    for (const html of [collapsedHtml, expandedHtml]) {
      assert.doesNotMatch(html, /margin-left/)
      assert.doesNotMatch(html, /is-child/)
    }
  })
  check('层级靠标签表达：第 2~5 层都有标签，顶层没有', () => {
    for (const n of [2, 3, 4, 5]) {
      assert.match(expandedHtml, new RegExp(`第 ${n} 层`))
    }
    assert.doesNotMatch(expandedHtml, /第 1 层/)
    assert.doesNotMatch(collapsedHtml, /第 2 层/)
  })
  check('引用 / 问题 / 答案都渲染出来', () => {
    assert.match(expandedHtml, /召回 topK 片段/)
    assert.match(expandedHtml, /什么是嵌入空间？/)
    assert.match(expandedHtml, /向量怎么来的？/)
  })
  check('删除按钮 title 提示后代数量（将同时删除 4 条追问）', () =>
    assert.match(collapsedHtml, /将同时删除 4 条追问/))
  check('编辑 / 删除按钮都在', () => {
    assert.match(collapsedHtml, />编辑</)
    assert.match(collapsedHtml, />删除</)
  })
  check('「已编辑」与「原文已修改」标签都出现', () => {
    assert.match(stateHtml, /已编辑/)
    assert.match(stateHtml, /原文已修改/)
    assert.match(stateHtml, /is-stale/)
  })
  check('NFR-03：answer / question 里的 HTML 被转义，不作为标签插入', () => {
    assert.doesNotMatch(stateHtml, /<img src=x/)
    assert.doesNotMatch(collapsedHtml, /<img src=x/)
    assert.doesNotMatch(expandedHtml, /<img src=x/)
    assert.match(stateHtml, /&lt;img/)
  })
  check('模板区不含 v-html，且接到 T014 封装 / apiError / 折叠状态模块', () => {
    assert.doesNotMatch(descriptor.template.content, /v-html/)
    assert.match(source, /from '\.\.\/api\/annotations\.js'/)
    assert.match(source, /from '\.\.\/utils\/apiError\.js'/)
    assert.match(source, /from '\.\.\/utils\/annotationExpand\.js'/)
  })
  check('折叠用 v-if 而非 v-show（隐藏卡片不能留在选区委托里）', () => {
    assert.match(descriptor.template.content, /v-if="open && kids\.length"/)
    assert.doesNotMatch(descriptor.template.content, /v-show/)
  })
  check('递归子组件逐层转发 changed / error', () => {
    assert.match(source, /@changed="forwardChanged"/)
    assert.match(source, /@error="forwardError"/)
  })

  // --- 2026-11-15 追加：父答案里的追问锚点高亮 ---
  check('纯函数：命中子批注引用词，片段拼接后与原文逐字相同', () => {
    const text = '取余弦相似度最高的 K 个片段。'
    const segs = splitAnswerByQuotes(text, ['余弦相似度'])
    assert.equal(segs.map(s => s.text).join(''), text)
    assert.equal(segs.filter(s => s.hit).length, 1)
    assert.equal(segs.find(s => s.hit).text, '余弦相似度')
  })
  check('纯函数：长词优先、重叠不重复标，短词顺延到下一次出现', () => {
    const text = '余弦相似度和余弦都要标'
    const segs = splitAnswerByQuotes(text, ['余弦', '余弦相似度'])
    assert.equal(segs.map(s => s.text).join(''), text)
    assert.deepEqual(segs.filter(s => s.hit).map(s => s.text), ['余弦相似度', '余弦'])
  })
  check('纯函数：正则元字符按字面匹配，不用 RegExp', () => {
    const text = '答案是 a.*b+? 而不是别的'
    const segs = splitAnswerByQuotes(text, ['a.*b+?'])
    assert.equal(segs.map(s => s.text).join(''), text)
    assert.equal(segs.filter(s => s.hit).map(s => s.text).join(''), 'a.*b+?')
  })
  check('纯函数：空引用 / 找不到 / 脏输入都不炸，原文原样保留', () => {
    const text = '一段 ordinary 解释'
    for (const qs of [[], ['不存在'], [null, '', 0, {}], null, '余弦']) {
      const segs = splitAnswerByQuotes(text, qs)
      assert.equal(segs.map(s => s.text).join(''), text)
      assert.equal(segs.filter(s => s.hit).length, 0)
    }
    assert.deepEqual(splitAnswerByQuotes(null, ['x']), [])
  })

  const markRe = (word) => new RegExp(`<mark[^>]*quote-hit[^>]*>${word}<\\/mark>`)
  check('SSR：父答案是 <mark class="quote-hit"> 分段渲染，折叠时也标出锚点', () => {
    assert.match(collapsedHtml, markRe('余弦相似度'))
    assert.match(expandedHtml, markRe('余弦相似度'))
    assert.match(descriptor.template.content, /v-for="\(seg, i\) in answerSegments"/)
    assert.doesNotMatch(descriptor.template.content, /v-html/)
  })

  const ownQuoteHtml = await render({
    id: 31, parentId: null, blockKey: 'p-3', quote: '共享词',
    question: '？', answer: '共享词出现两次，共享词还在别处。',
    edited: false, createdAt: '2026-11-14T00:00:06Z', stale: false,
    children: [{
      id: 32, parentId: 31, blockKey: 'p-3', quote: '还在别处',
      question: '？', answer: '…', edited: false,
      createdAt: '2026-11-14T00:00:07Z', stale: false, children: []
    }]
  })
  check('口径：只标子批注选中的词，本卡片自己的引用词不高亮', () => {
    assert.match(ownQuoteHtml, markRe('还在别处'))
    assert.doesNotMatch(ownQuoteHtml, markRe('共享词'))
  })
  check('NFR-03 不变：无子批注时不凭空标黄，HTML 探针仍被转义成文本', () => {
    assert.doesNotMatch(stateHtml, /quote-hit/)
    assert.doesNotMatch(stateHtml, /<img src=x/)
    assert.match(stateHtml, /&lt;img/)
  })
  check('卡片自带同配色 scoped 样式（正文那条 :deep() 管不到右栏）', () => {
    const css = descriptor.styles[0].content
    assert.match(css, /\.answer mark\.quote-hit/)
    assert.match(css, /#faeeda/)
    assert.match(css, /#ef9f27/)
  })

  // --- 生成可点击的静态预览页 ---
  const style = descriptor.styles[0]
  const scopeId = Card.__scopeId || 'data-v-preview'
  const scopedCss = compileStyle({
    source: style.content,
    filename: sfcPath,
    id: scopeId.replace(/^data-v-/, ''),
    scoped: !!style.scoped
  }).code
  let mainForPreview = expandedHtml
  let stateForPreview = stateHtml
  if (!Card.__scopeId) {
    const addScope = (s) => s.replace(/<([a-z][\w-]*)/g, '<$1 data-v-preview')
    mainForPreview = addScope(mainForPreview)
    stateForPreview = addScope(stateForPreview)
  }
  // Vue SSR 会给嵌套的同组件元素重复注入同一个 scopeId，预览页里去掉重复写法
  const dedupeScope = (s) => s.replace(/(data-v-[\w-]+)\s+\1/g, '$1')
  mainForPreview = dedupeScope(mainForPreview)
  stateForPreview = dedupeScope(stateForPreview)
  const globalCss = readFileSync(path.join(root, 'src/styles/global.css'), 'utf-8')
  const page = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>T015 · 批注卡片折叠预览</title>
<style>${globalCss}</style>
<style>${scopedCss}</style>
<style>
  body { padding: 28px 32px 60px; }
  h1 { font-size: 20px; margin: 0 0 6px; }
  h2 { font-size: 14px; margin: 26px 0 10px; color: var(--ink-soft); }
  .hint { font-size: 12.5px; color: var(--ink-dim); line-height: 1.7; margin: 0 0 20px; }
  .demo { display: flex; gap: 28px; align-items: flex-start; max-width: 900px; }
  .doc { flex: 1; min-width: 240px; background: var(--surface); border: 1px solid var(--line); border-radius: 10px; padding: 16px 18px; font-size: 13px; line-height: 1.9; color: var(--ink-soft); }
  .rail { width: 380px; flex: none; background: var(--bg); border-left: 1px solid var(--line); padding-left: 20px; }
  .quote-hit { background: #faeeda; color: #854f0b; border-bottom: 2px solid #ef9f27; border-radius: 2px; padding: 1px 2px; }
  .preview-tools { display: flex; gap: 10px; margin: 0 0 14px; }
  .preview-tools button { font-size: 12px; padding: 5px 12px; border: 1px solid var(--line); border-radius: 6px; color: var(--ink-soft); background: var(--surface); }
  .preview-tools button:hover { border-color: var(--accent); color: var(--accent); }
  [hidden] { display: none !important; }
  .annotation-children { padding-left: 0; }
  @media (max-width: 860px) { .demo { flex-direction: column; } .rail { width: 100%; border-left: none; padding-left: 0; } }
</style>
</head>
<body>
  <h1>T015 · 批注卡片（折叠 / 独立平铺）</h1>
  <p class="hint">右侧批注栏由组件真实 SSR 渲染 + 编译后的 scoped 样式产出。<b>追问默认收起，点蓝色小横条逐层展开</b>；展开后子卡片是独立平铺的卡片，不再层层套在父卡片里。<br><b>父答案里的黄色块是「追问锚点」</b>：子批注当时选中的那个词（例如「余弦相似度」），追问折叠也照样标出来，一眼能看出是针对哪儿追问的；本卡片自己的引用词不标。<br>这里的点击是预览页自己的演示 JS，编辑 / 删除 / 选词追问仍要 T017 接入页面后才能验。左侧正文的黄色高亮是<b>配色示意</b>。</p>

  <div class="preview-tools">
    <button type="button" id="expandAll">全部展开</button>
    <button type="button" id="collapseAll">全部收起</button>
  </div>

  <h2>一条五层追问链 · 顶层展开，追问默认折叠</h2>
  <div class="demo">
    <div class="doc">
      <b style="color:var(--ink)">正文（示意）</b>
      <p>检索时先按向量相似度排序，<mark class="quote-hit">召回 topK 片段</mark>，再把这 K 个片段交给模型做重排。K 太小会漏掉关键上下文，K 太大又会引入噪声。</p>
      <p style="font-size:12px;color:var(--ink-dim)">黄色块 = 引用在原文中的位置高亮配色（T016 / T017 的 .quote-hit）</p>
    </div>
    <div class="rail">${mainForPreview}</div>
  </div>

  <h2>状态标签：已编辑 / 原文已修改 / 文本转义</h2>
  <div class="demo"><div class="doc" style="font-size:12px;color:var(--ink-dim)">这条卡片的问题与答案里都含 &lt;img src=x onerror=...&gt;，页面把它当纯文本显示 —— NFR-03 的直观证据。</div>
  <div class="rail">${stateForPreview}</div></div>

<script>
(function () {
  var toggles = Array.prototype.slice.call(document.querySelectorAll('[data-toggle-id]'));
  var groups = Array.prototype.slice.call(document.querySelectorAll('[data-children-of]'));

  function groupFor(id) {
    return document.querySelector('[data-children-of="' + id + '"]');
  }
  function countOf(group) {
    return group ? group.querySelectorAll('.annotation-card').length : 0;
  }
  function paint(btn, isOpen, n) {
    btn.setAttribute('aria-expanded', isOpen ? 'true' : 'false');
    btn.classList.toggle('open', isOpen);
    var chev = btn.querySelector('.chev');
    if (chev) chev.textContent = isOpen ? '▾' : '▸';
    var label = btn.querySelector('span:last-child');
    if (label) label.textContent = isOpen ? '收起追问' : '展开 ' + n + ' 条追问';
  }
  function setAll(isOpen) {
    groups.forEach(function (g) { g.hidden = !isOpen; });
    toggles.forEach(function (btn) {
      paint(btn, isOpen, countOf(groupFor(btn.getAttribute('data-toggle-id'))));
    });
  }

  // 收起某个节点时把内部整条链一并合上，避免再次点开时整条链突然全展开
  function collapseInside(group) {
    Array.prototype.slice.call(group.querySelectorAll('[data-children-of]')).forEach(function (g) {
      g.hidden = true;
    });
    Array.prototype.slice.call(group.querySelectorAll('[data-toggle-id]')).forEach(function (b) {
      paint(b, false, countOf(groupFor(b.getAttribute('data-toggle-id'))));
    });
  }

  toggles.forEach(function (btn) {
    var group = groupFor(btn.getAttribute('data-toggle-id'));
    btn.addEventListener('click', function () {
      if (!group) return;
      var willOpen = group.hidden;
      group.hidden = !willOpen;
      if (!willOpen) collapseInside(group);
      paint(btn, willOpen, countOf(group));
    });
  });
  document.getElementById('expandAll').addEventListener('click', function () { setAll(true); });
  document.getElementById('collapseAll').addEventListener('click', function () { setAll(false); });

  setAll(false);
})();
</script>
</body>
</html>`
  const outDir = path.join(root, 'preview')
  mkdirSync(outDir, { recursive: true })
  const outFile = path.join(outDir, 'annotation-card.html')
  writeFileSync(outFile, page, 'utf-8')
  check('可点击预览页已生成：scoped 样式命中、逐层折叠 JS 与正文高亮示意都在', () => {
    assert.match(page, /\.annotation-card/)
    assert.match(page, /data-annotation-id="12"/)
    assert.match(mainForPreview, new RegExp(`data-annotation-id="12"[^>]*${scopeId}`))
    assert.match(page, /data-children-of="12"/)
    assert.match(page, /id="expandAll"/)
    assert.match(page, /setAll\(false\)/)
    assert.match(page, /collapseInside/)
    assert.match(page, /class="quote-hit"/)
    assert.match(page, /追问锚点/)
    assert.match(mainForPreview, markRe('余弦相似度'))
  })

  console.log('\n  折叠态结构预览：')
  console.log('    批注 12  引用:召回 topK 片段  [展开 4 条追问 ▸]')
  console.log('    子卡片 13~16 不在 DOM 里，点开才逐层出现')
  console.log('  展开态：12 / 13 / 14 / 15 / 16 各自独立平铺，靠「第 N 层」标签区分')
  console.log(`\n  预览页：${outFile}`)
  console.log(`\nT015 自检 ${passed}/${passed} 断言通过。`)
} finally {
  await vite.close()
}
