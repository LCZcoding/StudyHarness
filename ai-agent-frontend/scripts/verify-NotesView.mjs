/**
 * T025 自检（笔记列表页 + 导航入口 + 路由）：
 *  1) 用 axios 自定义 adapter 拦下 api/notes.js 的 list()，断言 URL / limit 参数；
 *  2) 静态解析 NotesView.vue / NavRail.vue / router，断言接线契约：
 *     文案复用 noteCardOf、五个字段都渲染、点整行进详情页、空态引导回对话、
 *     导航第 3 项是「笔记」且图标与资料库不同、静态段排在 notes/:messageId 之前；
 *  3) 用 Vite SSR 真渲染两次：两篇笔记（倒序 / 批注数 / href）与空数组（空态）。
 *
 * 运行：cd ai-agent-frontend && node scripts/verify-NotesView.mjs
 */
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import { createSSRApp, h } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { createMemoryHistory, createRouter } from 'vue-router'
import { parse } from '@vue/compiler-sfc'
import request from '../src/api/request.js'
import * as notesApi from '../src/api/notes.js'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const viewPath = path.join(root, 'src/views/NotesView.vue')
const railPath = path.join(root, 'src/components/NavRail.vue')
const routerPath = path.join(root, 'src/router/index.js')

function sfcOf(file) {
  const source = readFileSync(file, 'utf-8')
  const d = parse(source, { filename: file }).descriptor
  return { source, template: d.template.content, script: d.scriptSetup?.content || '' }
}

const view = sfcOf(viewPath)
const rail = sfcOf(railPath)
const routerSource = readFileSync(routerPath, 'utf-8')

let passed = 0
function check(name, fn) {
  fn()
  passed += 1
  console.log(`  ok  ${name}`)
}

// ---------- 1) 请求形状 ----------
const calls = []
let nextResponse = null
request.defaults.adapter = (config) => {
  calls.push(config)
  return Promise.resolve({ data: nextResponse, status: 200, statusText: 'OK', headers: {}, config })
}
const last = () => calls[calls.length - 1]

console.log('T025 · api/notes.js 的 list()')

check('导出 get / list / save 三个薄封装', () => {
  assert.deepEqual(Object.keys(notesApi).sort(), ['get', 'list', 'save'])
})

nextResponse = [
  {
    messageId: 534,
    conversationId: 'c0a8012e-0000-0000-0000-00000000000a',
    content: '# Kubernetes 入门笔记\n\nPod 是最小调度单位。',
    annotationCount: 2,
    createdAt: '2026-11-15T05:00:00Z',
    updatedAt: '2026-11-15T05:40:00Z'
  }
]
const rows = await notesApi.list()
check('GET /notes，固定带 limit=50（默认值只在后端存一份）', () => {
  const c = last()
  assert.equal(c.method, 'get')
  assert.equal(c.url, '/notes')
  assert.deepEqual(c.params, { limit: 50 })
})
check('响应已脱壳，直接是数组', () => {
  assert.ok(Array.isArray(rows))
  assert.equal(rows[0].messageId, 534)
  assert.equal(rows[0].annotationCount, 2)
})

await notesApi.list(10)
check('limit 传参覆盖默认值', () => assert.deepEqual(last().params, { limit: 10 }))

// ---------- 2) SFC 静态接线 ----------
console.log('\nT025 · NotesView 接线（SFC 静态解析）')

check('文案复用 noteCardOf，页面里没有第二套标题 / 摘要提取', () => {
  assert.match(view.script, /import \{ noteCardOf \} from '\.\.\/utils\/noteCard\.js'/)
  assert.match(view.script, /noteCardOf\(note\.content\)/)
  // 标题正则、「摘要取前 N 字」那类逻辑一旦出现在页面里，就说明又写了一套
  assert.doesNotMatch(view.script, /\^#\{1,6\}/)
  assert.doesNotMatch(view.script, /slice\(0, 80\)/)
})

check('每条渲染五个字段：标题 / 摘要 / 字数 / 批注数 / 更新时间', () => {
  assert.match(view.template, /class="row-title">\{\{ card\(n\)\.title \}\}/)
  assert.match(view.template, /class="row-summary">\{\{ card\(n\)\.summary \}\}/)
  assert.match(view.template, /\{\{ card\(n\)\.chars\.toLocaleString\('zh-CN'\) \}\} 字/)
  assert.match(view.template, /\{\{ n\.annotationCount \}\} 条批注/)
  assert.match(view.template, /更新于 \{\{ formatTime\(n\.updatedAt\) \}\}/)
})

check('点整行进详情页（note-detail，参数用 messageId）', () => {
  assert.match(
    view.template,
    /:to="\{ name: 'note-detail', params: \{ messageId: n\.messageId \} \}"/
  )
})

check('空态引导「在对话里打开生成笔记开关」，并给出去对话的入口', () => {
  assert.match(view.template, /生成笔记」开关/)
  assert.match(view.template, /去对话/)
  assert.match(view.template, /:to="\{ name: 'guide' \}"/)
})

check('顺序交给后端：页面里没有二次排序', () => {
  assert.doesNotMatch(view.script, /\.sort\(/)
})

check('401 跳登录、其它错误展示服务端文案', () => {
  assert.match(view.script, /redirectOn401\(e, router, route\)/)
  assert.match(view.script, /messageOf\(e, '笔记列表加载失败/)
})

check('文案有缓存，一次渲染不重扫三遍整篇 Markdown', () => {
  assert.match(view.script, /const cardCache = new Map\(\)/)
  assert.match(view.script, /cardCache\.set\(key, cached\)/)
})

check('NFR-03：页面不拼 HTML', () => {
  assert.doesNotMatch(view.template, /v-html/)
  assert.doesNotMatch(view.template, /innerHTML/)
})

check('样式尊重 reduced-motion', () => {
  assert.match(view.source, /@media \(prefers-reduced-motion: reduce\)/)
})

// ---------- 3) 导航 ----------
console.log('\nT025 · NavRail 第 3 项')

check('顺序是 对话 → 资料库 → 笔记 → 待办 → 进度', () => {
  const order = [...rail.script.matchAll(/to: '([^']+)'/g)].map((m) => m[1])
  assert.deepEqual(order, ['/guide', '/materials', '/notes', '/todo', '/progress'])
})

check('label 是「笔记」，图标与资料库的书本不同', () => {
  assert.match(rail.script, /to: '\/notes',\s*label: '笔记'/)
  const paths = [...rail.script.matchAll(/path: '([^']+)'/g)].map((m) => m[1])
  const materials = paths.find((p) => p.includes('M4 5.5A2.5'))
  const notes = paths.find((p) => p.includes('M14 3v5h5'))
  assert.ok(materials && notes, '两个图标都该在 items 里')
  assert.notEqual(materials, notes)
})

check('/notes/:messageId 也能点亮该入口（前缀匹配）', () => {
  assert.match(rail.script, /route\.path\.startsWith\(to \+ '\/'\)/)
})

// ---------- 4) 路由 ----------
console.log('\nT025 · 路由')

check('notes 列表路由注册在 AppShell children 内', () => {
  const shellAt = routerSource.indexOf('component: AppShell')
  const listAt = routerSource.indexOf("path: 'notes',")
  assert.ok(shellAt > 0 && listAt > shellAt, '列表路由缺失或不在 AppShell 内')
  const chunk = routerSource.slice(listAt, listAt + 220)
  assert.match(chunk, /name: 'notes'/)
  assert.match(chunk, /component: \(\) => import\('\.\.\/views\/NotesView\.vue'\)/)
  assert.match(chunk, /meta: \{ requiresAuth: true \}/)
})

check('静态段排在 notes/:messageId 之前', () => {
  const listAt = routerSource.indexOf("path: 'notes',")
  const detailAt = routerSource.indexOf("path: 'notes/:messageId'")
  assert.ok(listAt > 0 && detailAt > 0)
  assert.ok(listAt < detailAt, 'notes 必须在 notes/:messageId 之前')
})

// ---------- 5) Vite SSR ----------
const vite = await createServer({
  root,
  server: { middlewareMode: true },
  appType: 'custom',
  logLevel: 'error'
})

const NOTE_A = `# 线程池核心数怎么定

结论先说：核心数要看任务类型，**IO 密集按 2N 起**。
`
const NOTE_B = `# Kubernetes 入门笔记

Pod 是最小调度单位，一个 Pod 里可以塞多个容器。
`

try {
  const NotesView = (await vite.ssrLoadModule('/src/views/NotesView.vue')).default
  check('Vite SSR 能编译加载 NotesView（连带 noteCard / PageHeader 依赖链）', () =>
    assert.ok(NotesView))

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { render: () => null } },
      { path: '/guide', name: 'guide', component: { render: () => null } },
      { path: '/notes', name: 'notes', component: { render: () => null } },
      { path: '/notes/:messageId', name: 'note-detail', component: { render: () => null } }
    ]
  })
  await router.push('/notes')
  await router.isReady()

  // 后端已排好序：B 更新（534）在前，A（527）在后。页面必须原样呈现这个顺序。
  const rows = [
    {
      messageId: 534,
      conversationId: 'c-1',
      content: NOTE_B,
      annotationCount: 3,
      createdAt: '2026-11-15T05:00:00Z',
      updatedAt: '2026-11-15T05:40:00Z'
    },
    {
      messageId: 527,
      conversationId: 'c-1',
      content: NOTE_A,
      annotationCount: 0,
      createdAt: '2026-11-14T05:00:00Z',
      updatedAt: '2026-11-14T05:00:00Z'
    }
  ]

  const app = createSSRApp({ render: () => h(NotesView, { initialNotes: rows }) })
  app.use(router)
  const html = await renderToString(app)

  console.log('\nSSR · 两篇笔记')
  check('顺序与后端一致：更新的那篇在前', () => {
    const b = html.indexOf('Kubernetes 入门笔记')
    const a = html.indexOf('线程池核心数怎么定')
    assert.ok(b > 0 && a > 0, '两篇标题都该渲染出来')
    assert.ok(b < a, '列表顺序被前端改过')
  })

  check('标题去掉了 Markdown 井号，摘要剥掉了强调符号', () => {
    assert.doesNotMatch(html, /row-title[^>]*>#/)
    assert.doesNotMatch(html, /row-summary[^>]*>[^<]*\*\*/)
  })

  check('批注数与字数都显示（0 条也显示，便于判断有没有人批过）', () => {
    assert.match(html, /3 条批注/)
    assert.match(html, /0 条批注/)
    assert.match(html, /\d+ 字/)
  })

  check('每行链到 /notes/{messageId}', () => {
    assert.match(html, /href="\/notes\/534"/)
    assert.match(html, /href="\/notes\/527"/)
  })

  check('空态不出现（有数据时）', () => {
    assert.doesNotMatch(html, /还没有笔记/)
  })

  console.log('\nSSR · 空数组')
  const emptyApp = createSSRApp({ render: () => h(NotesView, { initialNotes: [] }) })
  emptyApp.use(router)
  const emptyHtml = await renderToString(emptyApp)
  check('空态文案与「去对话」入口都在', () => {
    assert.match(emptyHtml, /还没有笔记/)
    assert.match(emptyHtml, /生成笔记」开关/)
    assert.match(emptyHtml, /href="\/guide"/)
  })
  check('空态下不渲染列表分隔线', () => {
    assert.doesNotMatch(emptyHtml, /class="row-link"/)
  })

  console.log('\nSSR · 正文里的 HTML 必须被转义')
  const xssApp = createSSRApp({
    render: () => h(NotesView, {
      initialNotes: [{
        messageId: 9,
        conversationId: 'c-1',
        content: '# 标题\n\n<script>alert(1)</script> **粗体** 之后还有正文',
        annotationCount: 0,
        createdAt: '2026-11-15T05:00:00Z',
        updatedAt: '2026-11-15T05:00:00Z'
      }]
    })
  })
  xssApp.use(router)
  const xssHtml = await renderToString(xssApp)
  check('摘要走插值，script 标签被转义成文本', () => {
    assert.doesNotMatch(xssHtml, /<script>alert/)
    assert.match(xssHtml, /&lt;script&gt;/)
  })
} finally {
  await vite.close()
}

console.log(`\nT025 自检 ${passed}/${passed} 断言通过；共拦下 ${calls.length} 次请求（全部未出网）。`)
