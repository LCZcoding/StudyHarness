/**
 * T017 自检（阅读页接入）：
 *  1) 用 @vue/compiler-sfc 解析真实 SFC，逐条断言接线契约：组件 props、只读、
 *     401 由父页面处理、action-bar 与翻页不动、容器放宽、不重复加高亮样式。
 *  2) 用 Vite SSR 真实加载 SectionReadView.vue —— 这一步会连带编译 import 链
 *     （AnnotatableDoc / AnnotationCard / api/annotations / markdown / resolve），
 *     比静态解析更能证明页面真的能跑；提供一个假 router 渲染加载态。
 *  3) 用真实的 AnnotatableDoc SSR DOM + 页面/组件编译后的 scoped 样式，拼出
 *     preview/section-read.html：能看到阅读页全貌（顶栏 + 两栏 + 底部操作栏）。
 *
 * 预览页的折叠与选区提示是演示 JS，不调后端；
 * 真正的提问 / 编辑 / 删除要启动后端 + npm run dev 在浏览器里验。
 *
 * 运行：cd ai-agent-frontend && node scripts/verify-SectionReadView.mjs
 */
import assert from 'node:assert/strict'
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import { createSSRApp, h, reactive } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { parse, compileStyle } from '@vue/compiler-sfc'
import { routeLocationKey, routerKey } from 'vue-router'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const viewPath = path.join(root, 'src/views/SectionReadView.vue')
const docPath = path.join(root, 'src/components/AnnotatableDoc.vue')
const cardPath = path.join(root, 'src/components/AnnotationCard.vue')
const headerPath = path.join(root, 'src/components/PageHeader.vue')
const source = readFileSync(viewPath, 'utf-8')
const descriptor = parse(source, { filename: viewPath }).descriptor
const template = descriptor.template.content
const script = descriptor.scriptSetup?.content || ''
const styles = descriptor.styles.map(s => s.content).join('\n')

let passed = 0
function check(name, fn) {
  fn()
  passed += 1
  console.log(`  ok  ${name}`)
}

console.log('T017 · 阅读页接线契约（SFC 静态解析）')

check('正文换成 AnnotatableDoc，六个接线属性齐全', () => {
  assert.match(template, /<AnnotatableDoc[\s>]/)
  assert.match(template, /:markdown="contentMarkdown"/)
  assert.match(template, /:target-type="'SECTION'"/)
  assert.match(template, /:target-id="detail\.knowledgeItemId"/)
  assert.match(template, /:body-editable="false"/)
  assert.match(template, /@unauthorized="onUnauthorized"/)
})

check('翻页时按 knowledgeItemId 加 :key 重建组件，清空上一章批注与折叠状态', () => {
  assert.match(template, /:key="detail\.knowledgeItemId"/)
})

check('FR-11：阅读页不再自己渲染整篇 HTML，没有页面级 v-html', () => {
  assert.doesNotMatch(template, /class="prose"/)
  assert.doesNotMatch(template, /contentHtml/)
  assert.doesNotMatch(template, /v-html/)
})

check('FR-11：底部操作栏、翻页与「开始检测」全部原样保留', () => {
  assert.match(template, /class="action-bar"/)
  assert.match(template, /class="pager"/)
  assert.match(template, /goSection\(prevSection\)/)
  assert.match(template, /goSection\(nextSection\)/)
  assert.match(template, /我读完了，开始检测/)
  assert.match(template, /直接检测/)
})

check('script import 换成组件，原有取正文 / 目录 / 出题 / 401 依赖都在', () => {
  assert.match(script, /import AnnotatableDoc from '\.\.\/components\/AnnotatableDoc\.vue'/)
  assert.match(script, /from '\.\.\/api\/knowledge\.js'/)
  assert.match(script, /from '\.\.\/api\/quiz\.js'/)
  assert.match(script, /from '\.\.\/utils\/apiError\.js'/)
  assert.doesNotMatch(script, /renderMarkdown/)
  // 只查 import 语句：注释里也可能出现模块名
  assert.doesNotMatch(script, /import\s+[^;]*from\s+['"][^'"]*notes\.js['"]/)
})

check('contentMarkdown 交的是原始 markdown，且仍跳过与页面标题重复的首行', () => {
  assert.match(script, /const contentMarkdown = computed\(/)
  assert.match(script, /detail\.value\?\.content/)
  assert.match(script, /\/\^#\{1,6\}\\s\+\(\.\*\)\$\//)
  assert.match(script, /lines\.splice\(first, 1\)/)
  assert.match(script, /return lines\.join\('\\n'\)/)
})

check('组件 401 只 emit，跳登录由父页面用项目统一逻辑完成', () => {
  assert.match(script, /function onUnauthorized\(\)/)
  assert.match(script, /redirectOn401\(\{ response: \{ status: 401 \} \}, router, route\)/)
  assert.match(script, /redirectOn401\(e, router, route\)/)
})

check('翻页仍走 watch(immediate) + load(id)，章节切换会重新取正文', () => {
  assert.match(script, /route\.params\.knowledgeItemId/)
  assert.match(script, /if \(id\) load\(id\)/)
  assert.match(script, /\{ immediate: true \}/)
  assert.match(script, /async function load\(id\)/)
})

check('C9：容器从 720px 放宽到 1200px，两栏才放得下', () => {
  const m = styles.match(/\.reader-inner\s*\{[^}]*max-width:\s*(\d+)px/)
  assert.ok(m, '.reader-inner 没找到 max-width')
  assert.ok(Number(m[1]) >= 1100, `容器仍是 ${m[1]}px，放不下两栏`)
  assert.match(styles, /@media \(max-width: 820px\)/)
  assert.match(styles, /padding-bottom: 132px/)
  assert.match(styles, /\.action-bar\s*\{[\s\S]*?position: sticky/)
})

check('C10：页面不重复定义 markdown 排版与高亮样式', () => {
  assert.doesNotMatch(styles, /(^|\n)\s*\.prose[^{]*\{/)
  assert.doesNotMatch(styles, /quote-hit/)
  assert.doesNotMatch(styles, /:deep\(/)
})

// ---------- Vite SSR：真实加载页面 + 真实渲染两栏组件 ----------
const vite = await createServer({
  root,
  server: { middlewareMode: true },
  appType: 'custom',
  logLevel: 'error'
})

const markdown = [
  '## 第 1 节 线程与锁',
  '',
  '检索时召回 topK 片段，再交给模型重排。余弦相似度衡量两个向量的夹角，嵌入空间由模型把文本映射成向量。',
  '',
  '**粗体** 与 `行内代码` 会把引用词切开，此时只显示段落左侧色条与批注数角标。',
  '',
  '| 指标 | 含义 |',
  '|---|---|',
  '| topK | 召回条数 |',
  '',
  '```js',
  'const k = 20',
  '```'
].join('\n')

const xss = '<img src=x onerror="alert(1)">'
const demoAnnotations = [
  { id: 12, parentId: null, blockKey: 'p-1', quote: '召回 topK 片段', question: 'topK 是什么？', answer: '取余弦相似度最高的 K 个片段。', edited: false, createdAt: '2026-11-14T00:00:00Z' },
  { id: 13, parentId: 12, blockKey: 'p-1', quote: '余弦相似度', question: '相似度怎么算？', answer: '看两个向量在嵌入空间里的夹角。', edited: false, createdAt: '2026-11-14T00:00:01Z' },
  { id: 14, parentId: 13, blockKey: 'p-1', quote: '嵌入空间', question: '什么是嵌入空间？', answer: '把文本变成向量后的多维空间。', edited: false, createdAt: '2026-11-14T00:00:02Z' },
  { id: 21, parentId: null, blockKey: 'p-2', quote: '粗体 与 行内代码', question: '这两种标记有啥区别？', answer: '一个加粗，一个标代码。', edited: false, createdAt: '2026-11-14T00:00:03Z' },
  { id: 22, parentId: null, blockKey: 'p-2', quote: '被删掉的原句', question: `这句 ${xss} 还能看见吗？`, answer: `原答案保留 ${xss}`, edited: true, createdAt: '2026-11-14T00:00:04Z' }
]
const allIds = demoAnnotations.map(a => a.id)

let loadingHtml, docHtml, headerHtml, Page, Doc, Card, Header, pageScope

try {
  Page = (await vite.ssrLoadModule('/src/views/SectionReadView.vue')).default
  check('Vite SSR 能编译加载 SectionReadView（连带整条组件 import 链）', () => assert.ok(Page))

  const route = reactive({
    name: 'section-read',
    path: '/materials/',
    fullPath: '/materials/',
    params: {},
    query: {},
    meta: {},
    matched: []
  })
  const app = createSSRApp({ render: () => h(Page) })
  app.provide(routeLocationKey, route)
  app.provide(routerKey, { push: () => Promise.resolve() })
  loadingHtml = await renderToString(app)

  console.log('\nSSR · 页面加载态')
  check('页面能在无浏览器的 SSR 下渲染，顶栏与加载提示都在', () => {
    assert.match(loadingHtml, /章节阅读/)
    assert.match(loadingHtml, /加载正文中/)
    assert.doesNotMatch(loadingHtml, /data-block-key/)
  })

  Doc = (await vite.ssrLoadModule('/src/components/AnnotatableDoc.vue')).default
  Card = (await vite.ssrLoadModule('/src/components/AnnotationCard.vue')).default
  Header = (await vite.ssrLoadModule('/src/components/PageHeader.vue')).default

  docHtml = await renderToString(createSSRApp({
    render: () => h(Doc, {
      markdown,
      targetType: 'SECTION',
      targetId: 'demo-section',
      bodyEditable: false,
      initialAnnotations: demoAnnotations,
      initialExpandedIds: allIds
    })
  }))

  headerHtml = await renderToString(createSSRApp({
    render: () => h(Header,
      { eyebrow: 'READING · 章节阅读', title: '第 1 节 线程与锁', subtitle: '第 1 / 9 章' },
      { actions: () => h('button', { type: 'button', class: 'btn', 'data-v-page': '' }, '返回资料库') }
    )
  }))

  console.log('\nSSR · 阅读页里的两栏组件')
  check('逐块正文 + 引用原句黄色高亮 + 降级色条都在', () => {
    assert.match(docHtml, /data-block-key="p-1"/)
    assert.match(docHtml, /<mark class="quote-hit">召回 topK 片段<\/mark>/)
    assert.match(docHtml, /block-has-annotation/)
  })
  check('右栏按段落分组，追问链与「原文已修改」组都在', () => {
    assert.match(docHtml, /annotation-group/)
    assert.match(docHtml, /data-annotation-id="12"/)
    assert.match(docHtml, /data-annotation-id="14"/)
    assert.match(docHtml, /原文已修改/)
  })
  check('阅读页只读：bodyEditable=false 时没有编辑正文按钮与 textarea', () => {
    assert.doesNotMatch(docHtml, /编辑正文/)
    assert.doesNotMatch(docHtml, /body-textarea/)
  })
  check('NFR-03：批注里的 HTML 探针仍被转义成文本', () => {
    assert.doesNotMatch(docHtml, /<img src=x/)
    assert.match(docHtml, /&lt;img/)
  })

  // ---------- 阅读页全貌预览页 ----------
  const compileScoped = (desc, sfcPath, scopeId) => compileStyle({
    source: desc.styles[0].content,
    filename: sfcPath,
    id: scopeId.replace(/^data-v-/, ''),
    scoped: !!desc.styles[0].scoped
  }).code

  pageScope = 'data-v-page'
  const docScope = Doc.__scopeId || 'data-v-doc'
  const cardScope = Card.__scopeId || 'data-v-card'
  const headerScope = Header.__scopeId || 'data-v-header'
  const globalCss = readFileSync(path.join(root, 'src/styles/global.css'), 'utf-8')
  const pageCss = compileScoped(descriptor, viewPath, pageScope)
  const docCss = compileScoped(parse(readFileSync(docPath, 'utf-8'), { filename: docPath }).descriptor, docPath, docScope)
  const cardCss = compileScoped(parse(readFileSync(cardPath, 'utf-8'), { filename: cardPath }).descriptor, cardPath, cardScope)
  const headerCss = compileScoped(parse(readFileSync(headerPath, 'utf-8'), { filename: headerPath }).descriptor, headerPath, headerScope)

  const dedupeScope = (s) => s.replace(/(data-v-[\w-]+)\s+\1/g, '$1')
  let previewDocHtml = dedupeScope(docHtml)
  if (!Doc.__scopeId) previewDocHtml = previewDocHtml.replace(/<([a-z][\w-]*)/g, `<$1 ${docScope}`)
  let previewHeaderHtml = dedupeScope(headerHtml)
  if (!Header.__scopeId) previewHeaderHtml = previewHeaderHtml.replace(/<([a-z][\w-]*)/g, `<$1 ${headerScope}`)

  const S = `${pageScope}` // 页面骨架元素都带同一个 scope 属性，才能命中页面 scoped CSS
  const page = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>T017 · 章节阅读页接入（全貌预览）</title>
<style>${globalCss}</style>
<style>${pageCss}</style>
<style>${headerCss}</style>
<style>${docCss}</style>
<style>${cardCss}</style>
<style>
  body { padding: 24px 26px 60px; max-width: 1280px; margin: 0 auto; }
  h1 { font-size: 19px; margin: 0 0 6px; }
  .hint { font-size: 12.5px; color: var(--ink-dim); line-height: 1.8; margin: 0 0 14px; }
  .tools { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-bottom: 16px; }
  .tools button { font-size: 12px; padding: 5px 12px; border: 1px solid var(--line); border-radius: 6px; color: var(--ink-soft); background: var(--surface); }
  .tools button:hover { border-color: var(--accent); color: var(--accent); }
  #selHint { font-size: 12px; color: #854f0b; background: #faeeda; border-radius: 6px; padding: 5px 10px; min-height: 26px; }
  .shell { border: 1px solid var(--line); border-radius: 12px; overflow: hidden; background: var(--surface); }
  [hidden] { display: none !important; }
  .doc-body { max-width: none; }
  .action-bar { position: static; margin-top: 8px; }
</style>
</head>
<body>
  <h1>T017 · 章节阅读页接入（全貌预览）</h1>
  <p class="hint">
    真实 <b>SectionReadView</b> 的单栏正文已换成 <b>AnnotatableDoc</b> 两栏：左边逐块正文（黄色 = 引用在原文中的位置高亮），右边按段落分组的批注卡片；底部「我读完了，开始检测」与翻页原样保留。<br>
    <b>这里仍是静态预览</b>：折叠与选区提示是演示 JS，不调后端。要真正选文字提问，启动后端后 <code>cd ai-agent-frontend &amp;&amp; npm run dev</code>，登录 → 资料库 → 打开任一章节。
  </p>
  <div class="tools">
    <button type="button" id="expandAll">全部展开</button>
    <button type="button" id="collapseAll">全部收起</button>
    <span id="selHint">试着选中左边正文里的一句话 →</span>
  </div>

  <div class="shell" id="frame">
    <div class="page" ${S}>
      ${previewHeaderHtml}
      <main class="content" ${S}>
        <article class="reader" ${S}>
          <div class="reader-inner" ${S}>
            <p class="chapter-line" ${S}>Java 并发编程笔记 <span class="sep" ${S}>·</span> 1,280 字</p>
            ${previewDocHtml}
          </div>
        </article>
        <div class="action-bar" ${S}>
          <div class="action-inner" ${S}>
            <nav class="pager" ${S}>
              <button type="button" class="pager-btn" disabled ${S}><span class="pager-label" ${S}>上一章</span><span class="pager-title" ${S}>已是第一章</span></button>
              <button type="button" class="pager-btn right" ${S}><span class="pager-label" ${S}>下一章</span><span class="pager-title" ${S}>第 2 节 线程池</span></button>
            </nav>
            <div class="cta" ${S}>
              <button type="button" class="btn ghost" ${S}>直接检测</button>
              <button type="button" class="btn primary" ${S}>我读完了，开始检测</button>
            </div>
          </div>
        </div>
      </main>
    </div>
  </div>

<script>
(function () {
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

  var hint = document.getElementById('selHint');
  document.getElementById('frame').addEventListener('mouseup', function () {
    var sel = window.getSelection();
    var text = sel ? sel.toString().trim() : '';
    if (!text) return;
    var inCard = sel.anchorNode && sel.anchorNode.parentElement &&
      sel.anchorNode.parentElement.closest('[data-annotation-id]');
    hint.textContent = (inCard ? '在批注解释里选中：' : '在正文里选中：') +
      '「' + text.slice(0, 40) + (text.length > 40 ? '…' : '') + '」→ 真实页面会打开提问框生成' +
      (inCard ? '子批注' : '顶层批注');
  });
})();
</script>
</body>
</html>`

  const outDir = path.join(root, 'preview')
  mkdirSync(outDir, { recursive: true })
  const outFile = path.join(outDir, 'section-read.html')
  writeFileSync(outFile, page, 'utf-8')

  console.log('\n静态预览页')
  check('阅读页全貌预览已生成：页面骨架 + 两栏 + 底栏 + 编译后 scoped 样式', () => {
    assert.match(page, /class="reader-inner" data-v-page/)
    assert.match(page, /class="action-bar" data-v-page/)
    assert.match(page, /我读完了，开始检测/)
    assert.match(page, /data-block-key="p-1"/)
    assert.match(page, /class="quote-hit"/)
    assert.match(page, /\.reader-inner\[data-v-page\]/)
    assert.match(page, /mark\.quote-hit/)
    assert.match(page, /@media \(max-width: 1024px\)/)
    assert.match(page, /id="expandAll"/)
    assert.ok(page.length > 12000)
  })
} finally {
  await vite.close()
}

console.log(`\nT017 自检 ${passed}/${passed} 全过`)
console.log('  预览页：preview/section-read.html')
console.log('  真实可交互界面：启动后端 + npm run dev → 登录 → 资料库 → 打开任一章节')
