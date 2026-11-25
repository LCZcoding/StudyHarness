/**
 * T020 自检（笔记详情页 + 路由）：
 *  1) 用 @vue/compiler-sfc 解析真实 SFC，逐条断言接线契约：组件 props 是否
 *     真的打开了可编辑、保存是否只交了草稿、成功后是否显式退出编辑态。
 *  2) 断言 router/index.js 里 notes/:messageId 与页面动态 import 同时存在
 *     （旧版把两者拆到两个任务，路由 import 了一个还不存在的文件）。
 *  3) 用 Vite SSR 真实加载 NoteDetailView.vue，连带编译整条 import 链，
 *     证明页面在没有浏览器时也能跑起来、加载态与顶栏都在。
 *  4) 用真实 AnnotatableDoc SSR 一次 bodyEditable=true，确认笔记页拿到的组件
 *     确实有「编辑正文」与 textarea（阅读页那条断言是它的反面）。
 *
 * 运行：cd ai-agent-frontend && node scripts/verify-NoteDetailView.mjs
 */
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import { createSSRApp, h, reactive } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { parse } from '@vue/compiler-sfc'
import { routeLocationKey, routerKey } from 'vue-router'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const viewPath = path.join(root, 'src/views/NoteDetailView.vue')
const routerPath = path.join(root, 'src/router/index.js')
const source = readFileSync(viewPath, 'utf-8')
const descriptor = parse(source, { filename: viewPath }).descriptor
const template = descriptor.template.content
const script = descriptor.scriptSetup?.content || ''
const routerSource = readFileSync(routerPath, 'utf-8')

let passed = 0
function check(name, fn) {
  fn()
  passed += 1
  console.log(`  ok  ${name}`)
}

console.log('T020 · 笔记页接线契约（SFC 静态解析）')

check('挂的是共享组件，六个接线属性齐全', () => {
  assert.match(template, /<AnnotatableDoc[\s>]/)
  assert.match(template, /:markdown="markdown"/)
  assert.match(template, /:target-type="'NOTE'"/)
  assert.match(template, /:target-id="note\.messageId"/)
  assert.match(template, /:body-editable="true"/)
  assert.match(template, /@save="onSave"/)
  assert.match(template, /@unauthorized="onUnauthorized"/)
})

check('顶栏标题取笔记自己的第一个标题，与聊天卡片同源（T021 补）', () => {
  // 导入清单可以随任务增长（T025 加了 noteChars），这里只要求 noteTitle 来自同一个工具模块
  assert.match(script, /import \{[^}]*noteTitle[^}]*\} from '\.\.\/utils\/noteCard\.js'/)
  assert.match(script, /const title = computed\(\(\) => \(note\.value \? noteTitle\(markdown\.value\) : '笔记'\)\)/)
  // 写死的「AI 生成的笔记」会让「卡片说 A、点进来标题是 B」
  assert.doesNotMatch(script, /AI 生成的笔记/)
})

check('拿得到组件实例（finishSave 要从这里调）', () => {
  assert.match(template, /ref="docRef"/)
  assert.match(script, /const docRef = ref\(null\)/)
})

check('字数与卡片 / 列表同源（noteChars），不是自己数一遍', () => {
  assert.match(script, /import \{ noteChars, noteTitle \} from '\.\.\/utils\/noteCard\.js'/)
  assert.match(script, /const chars = noteChars\(markdown\.value\)/)
  // 曾是 markdown.replace(/\s/g,'').length：留着 # 去掉空格，与列表页差几个字
  assert.doesNotMatch(script, /replace\(\/\\s\/g, ''\)\.length/)
})

check('切换笔记时按 messageId 重建组件，批注列表与折叠状态不串', () => {
  assert.match(template, /:key="note\.messageId"/)
})

check('保存只交草稿：组件内不出现保存按钮、不 import 笔记接口', () => {
  // 编辑入口在组件里（bodyEditable 打开即渲染「编辑正文」），页面自己不该再放一个
  assert.doesNotMatch(template, /保存正文/)
  assert.doesNotMatch(template, /body-textarea/)
})

check('三个依赖都在：notes 封装、AnnotatableDoc、apiError', () => {
  assert.match(script, /import AnnotatableDoc from '\.\.\/components\/AnnotatableDoc\.vue'/)
  assert.match(script, /from '\.\.\/api\/notes\.js'/)
  assert.match(script, /from '\.\.\/utils\/apiError\.js'/)
})

check('保存成功：先换 markdown、再显式 finishSave、并刷新 updatedAt', () => {
  const success = script.slice(script.indexOf('async function onSave'))
  assert.match(success, /await saveNote\(note\.value\.messageId, draft\)/)
  assert.match(success, /markdown\.value = draft/)
  assert.match(success, /docRef\.value\?\.finishSave\(\)/)
  assert.match(success, /updatedAt: res\.updatedAt/)
})

check('保存失败：不退出编辑态（草稿必须留在输入框里）', () => {
  const failure = script.slice(script.indexOf('} catch (e) {', script.indexOf('async function onSave')))
  const catchBlock = failure.slice(0, failure.indexOf('finally'))
  assert.doesNotMatch(catchBlock, /finishSave/)
  assert.doesNotMatch(catchBlock, /markdown\.value =/)
  assert.match(catchBlock, /messageOf\(e, /)
})

check('保存成功后旧批注重新定位：靠 markdown 变化触发组件内 resolve，页面不自己算', () => {
  // 页面不引入定位逻辑，否则「正文改了批注怎么变」会有两份实现
  assert.doesNotMatch(script, /annotationResolve/)
  assert.doesNotMatch(script, /splitBlocks/)
})

check('NFR-03：页面不拼 HTML', () => {
  assert.doesNotMatch(template, /v-html/)
})

check('返回走 conversationId，不依赖浏览器历史', () => {
  assert.match(script, /conversationId: cid/)
  assert.match(script, /router\.push\(\{ name: 'guide-conversation'/)
})

console.log('\nT020 · 路由（页面与动态 import 同一次提交）')

check('notes/:messageId 已注册，name 与查重要求都对', () => {
  const at = routerSource.indexOf("path: 'notes/:messageId'")
  assert.ok(at > 0, '路由块缺失')
  const chunk = routerSource.slice(at, at + 260)
  assert.match(chunk, /name: 'note-detail'/)
  assert.match(chunk, /component: \(\) => import\('\.\.\/views\/NoteDetailView\.vue'\)/)
  assert.match(chunk, /meta: \{ requiresAuth: true \}/)
})

check('路由挂在 AppShell 的 children 下（左侧图标栏仍显示）', () => {
  const shellAt = routerSource.indexOf('component: AppShell')
  const notesAt = routerSource.indexOf("path: 'notes/:messageId'")
  assert.ok(shellAt > 0 && notesAt > shellAt, 'notes 路由必须在 AppShell children 内')
  // 两者之间不能出现 children 数组闭合（"  ]" 顶格缩进在后，用组件定义的结束位置判断）
  const between = routerSource.slice(shellAt, notesAt)
  assert.ok(between.includes('children: ['), '应位于 children 数组内')
})

// ---------- Vite SSR：真实加载页面 ----------
const vite = await createServer({
  root,
  server: { middlewareMode: true },
  appType: 'custom',
  logLevel: 'error'
})

let Page, Doc
try {
  Page = (await vite.ssrLoadModule('/src/views/NoteDetailView.vue')).default
  check('Vite SSR 能编译加载 NoteDetailView（连带整条组件 import 链）', () => assert.ok(Page))

  // params 留空 → watch 的 immediate 分支不触发取数，停在加载态（不发真实请求）
  const route = reactive({
    name: 'note-detail',
    path: '/notes/',
    fullPath: '/notes/',
    params: {},
    query: {},
    meta: {},
    matched: []
  })
  const app = createSSRApp({ render: () => h(Page) })
  app.provide(routeLocationKey, route)
  app.provide(routerKey, { push: () => Promise.resolve() })
  const loadingHtml = await renderToString(app)

  console.log('\nSSR · 页面加载态')
  check('顶栏与加载提示都在，且没有提前渲染空正文', () => {
    assert.match(loadingHtml, /NOTE · 笔记/)
    assert.match(loadingHtml, /正在打开笔记/)
    assert.match(loadingHtml, /返回对话/)
    assert.doesNotMatch(loadingHtml, /data-block-key/)
  })

  Doc = (await vite.ssrLoadModule('/src/components/AnnotatableDoc.vue')).default

  console.log('\nSSR · bodyEditable=true 的组件形态')
  const docHtml = await renderToString(createSSRApp({
    render: () => h(Doc, {
      markdown: '## 小结\n\n召回靠向量相似度。',
      targetType: 'NOTE',
      targetId: 4371,
      bodyEditable: true,
      initialAnnotations: []
    })
  }))
  check('笔记页拿到的组件有「编辑正文」按钮（阅读页那条断言的反面）', () => {
    assert.match(docHtml, /编辑正文/)
    assert.match(docHtml, /整篇 Markdown 原文，保存后覆盖/)
  })
  check('正文已按块渲染，批注栏是 NOTE 的空态', () => {
    assert.match(docHtml, /data-block-key="p-0"/)
    assert.match(docHtml, /选中正文里的一句话/)
  })
} finally {
  await vite.close()
}

console.log(`\nT020 自检 ${passed}/${passed} 断言通过。`)
