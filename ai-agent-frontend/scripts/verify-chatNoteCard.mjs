/**
 * T021 自检之二（组件接线 + SSR 真实渲染）：
 *  1) 静态解析 ChatRoom.vue / GuideChatView.vue 的 SFC，逐条断言接线：
 *     开关只在「发送那一刻」取值、发送后复位、note 帧写回 type/noteId、
 *     历史映射带 type、卡片模板用插值不拼 HTML。
 *  2) 用 Vite SSR 真渲染一张 NOTE 历史消息：卡片（标签 / 标题 / 摘要 / 打开按钮）
 *     出现，同一条正文没有被铺成聊天正文；普通回答消息不受影响。
 *
 * 运行：cd ai-agent-frontend && node scripts/verify-chatNoteCard.mjs
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

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const files = {
  room: path.join(root, 'src/components/ChatRoom.vue'),
  view: path.join(root, 'src/views/GuideChatView.vue')
}
const sfc = Object.fromEntries(
  Object.entries(files).map(([key, file]) => {
    const source = readFileSync(file, 'utf-8')
    const d = parse(source, { filename: file }).descriptor
    return [key, { source, template: d.template.content, script: d.scriptSetup?.content || '' }]
  })
)

const room = sfc.room
const view = sfc.view

let passed = 0
function check(name, fn) {
  fn()
  passed += 1
  console.log(`  ok  ${name}`)
}

console.log('T021 · ChatRoom 接线（SFC 静态解析）')

check('asNote 是一个显式 prop，默认关', () => {
  assert.match(room.script, /asNote: \{ type: Boolean, default: false \}/)
})

check('开关在发送那一刻先存一份，早于懒创建会话的 await', () => {
  const sendAt = room.script.indexOf('async function send')
  const captureAt = room.script.indexOf('const wantNote = props.asNote', sendAt)
  const awaitAt = room.script.indexOf('await props.resolveChatId', sendAt)
  assert.ok(captureAt > sendAt, 'send() 里没有取 props.asNote')
  assert.ok(awaitAt > captureAt, '取值必须早于 await，否则父组件复位后读到的就是 false')
})

check('新建的 AI 消息按开关决定 type=NOTE', () => {
  assert.match(room.script, /addMessage\('ai', '', wantNote \? 'NOTE' : 'ANSWER'\)/)
})

check('消息入列即 emit(sent)，父组件据此复位开关', () => {
  const sendAt = room.script.indexOf('async function send')
  const sentAt = room.script.indexOf("emit('sent')", sendAt)
  const streamAt = room.script.indexOf('streamChat({', sendAt)
  assert.ok(sentAt > sendAt && sentAt < streamAt, 'emit(sent) 应在开流之前')
  assert.match(room.script, /defineEmits\(\['done', 'sent'\]\)/)
})

check('asNote 透传给 streamChat；useRag/useTools 仍在', () => {
  const call = room.script.slice(room.script.indexOf('streamChat({'))
  assert.match(call, /useRag: props\.useRag/)
  assert.match(call, /useTools: props\.useTools/)
  assert.match(call, /asNote: wantNote/)
})

check('note 帧写回 type/noteId（帧序保证在 done 之前）', () => {
  assert.match(room.script, /if \(name === 'note'\)/)
  assert.match(room.script, /aiMsg\.type = 'NOTE'/)
  assert.match(room.script, /aiMsg\.noteId = payload\?\.noteId \?\? null/)
})

check('历史映射带 type，NOTE 行把 messageId 当 noteId（TC-08 的直接用处）', () => {
  assert.match(room.script, /type: m\.type === 'NOTE' \? 'NOTE' : 'ANSWER'/)
  assert.match(room.script, /noteId: m\.type === 'NOTE' \? m\.messageId : null/)
})

check('卡片四种形态齐全：成品 / 生成中 / 失败降级 / 普通消息', () => {
  assert.match(room.template, /v-if="m\.role === 'ai' && m\.type === 'NOTE'" class="note-slot"/)
  assert.match(room.template, /<div v-if="m\.noteId" class="note-card">/)
  assert.match(room.template, /<div v-else-if="streaming" class="note-pending">/)
  assert.match(room.template, /<p v-else class="note-fallback">/)
  assert.match(room.template, /<div v-else :class="\['bubble', m\.role\]">/)
})

check('卡片是「标签 + 标题 + 前 80 字摘要 + 打开笔记 →」', () => {
  assert.match(room.template, /<span class="note-tag">NOTE<\/span>/)
  assert.match(room.template, /class="note-card-title">\{\{ noteCard\(m\)\.title \}\}/)
  assert.match(room.template, /class="note-card-summary">\{\{ noteCard\(m\)\.summary \}\}/)
  assert.match(room.template, /打开笔记 →/)
  assert.match(room.template, /:to="\{ name: 'note-detail', params: \{ messageId: m\.noteId \} \}"/)
})

check('卡片文案全部走插值，没有 v-html（NFR-03）', () => {
  const card = room.template.slice(room.template.indexOf('class="note-slot"'))
  const block = card.slice(0, card.indexOf(":class=\"['bubble'"))
  assert.ok(block.length > 100, 'note-slot 区块没截出来')
  assert.doesNotMatch(block, /v-html/)
  assert.doesNotMatch(block, /innerHTML/)
})

check('卡片文案有缓存，不在每次重渲染时重扫整篇 Markdown', () => {
  assert.match(room.script, /import \{ noteCardOf \} from '\.\.\/utils\/noteCard\.js'/)
  assert.match(room.script, /const cardCache = new Map\(\)/)
  assert.match(room.script, /cardCache\.set\(key, card\)/)
})

check('卡片样式与生成中动画都在，且尊重 reduced-motion', () => {
  assert.match(room.source, /\.note-card \{/)
  assert.match(room.source, /\.note-card-title \{/)
  assert.match(room.source, /-webkit-line-clamp: 2/)
  assert.match(room.source, /\.note-pending-dot \{ animation: none; \}/)
})

console.log('\nT021 · GuideChatView 接线（SFC 静态解析）')

check('第 4 个开关的 ref 与透传都在', () => {
  assert.match(view.script, /const asNote = ref\(false\)/)
  assert.match(view.template, /:as-note="asNote"/)
})

check('开关按钮有 aria 状态与两种悬停说明', () => {
  const at = view.template.indexOf('aria-label="生成笔记"')
  assert.ok(at > 0, '没有「生成笔记」按钮')
  const block = view.template.slice(at - 260, at + 260)
  assert.match(block, /:class="\{ on: asNote \}"/)
  assert.match(block, /:aria-pressed="asNote"/)
  assert.match(block, /生成笔记 · 已开启/)
  assert.match(block, /生成笔记 · 已关闭/)
})

check('开关排在「联网」之后、「做成题」之前（第 4 个）', () => {
  const labels = ['资料检索', '联网', '生成笔记', '把这轮做成题']
  const positions = labels.map((l) => view.template.indexOf(`aria-label="${l}"`))
  assert.ok(positions.every((p) => p > 0), `按钮缺失：${labels[positions.findIndex((p) => p < 0)]}`)
  assert.deepEqual([...positions].sort((a, b) => a - b), positions)
})

check('发送后复位：@sent 绑定在 ChatRoom 上，onSent 只关开关', () => {
  assert.match(view.template, /@sent="onSent"/)
  const fn = view.script.slice(view.script.indexOf('function onSent'))
  assert.match(fn.slice(0, 80), /asNote\.value = false/)
  // 复位只动这一个开关，别顺手把检索 / 联网也关掉
  assert.doesNotMatch(fn.slice(0, 80), /useRag|useTools/)
})

// ---------- Vite SSR：真的渲染一张 NOTE 卡片 ----------
const NOTE_MD = `# 笔记：线程池核心数怎么定

结论先说：核心数要看任务类型，**IO 密集按 2N 起**。

## 为什么需要

线程大部分时间在等 IO，多开线程能把等待重叠起来。
`

const vite = await createServer({
  root,
  server: { middlewareMode: true },
  appType: 'custom',
  logLevel: 'error'
})

try {
  const Room = (await vite.ssrLoadModule('/src/components/ChatRoom.vue')).default
  check('Vite SSR 能编译加载 ChatRoom（连带 noteCard / BrandMark 依赖链）', () => assert.ok(Room))

  // 用真的 vue-router（内存 history）而不是手写 stub：RouterLink 在 SSR 里要的
  // options / resolve / matched 一样不能少，stub 只会把注意力引到假问题上。
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { render: () => null } },
      { path: '/guide/:conversationId', name: 'guide-conversation', component: { render: () => null } },
      { path: '/notes/:messageId', name: 'note-detail', component: { render: () => null } }
    ]
  })
  await router.push('/guide/c-1')
  await router.isReady()

  const history = [
    { messageId: 526, role: 'USER', type: 'QUESTION', content: '线程池核心数怎么定', createdAt: '2026-11-15T05:00:00Z' },
    { messageId: 527, role: 'ASSISTANT', type: 'NOTE', content: NOTE_MD, createdAt: '2026-11-15T05:00:30Z' },
    { messageId: 528, role: 'ASSISTANT', type: 'ANSWER', content: '你先说说任务类型，我们再定数量。', createdAt: '2026-11-15T05:01:00Z' }
  ]

  const app = createSSRApp({ render: () => h(Room, { chatId: 'c-1', historyMessages: history }) })
  app.use(router)
  const html = await renderToString(app)

  console.log('\nSSR · 历史里的一条 NOTE 消息')

  check('NOTE 渲染成卡片：标签 + 标题 + 摘要 + 打开按钮', () => {
    assert.match(html, /class="note-tag"[^>]*>NOTE</)
    assert.match(html, /class="note-card-title"[^>]*>笔记：线程池核心数怎么定</)
    assert.match(html, /class="note-card-summary"[^>]*>结论先说：核心数要看任务类型，IO 密集按 2N 起。/)
    assert.match(html, /打开笔记 →/)
  })

  check('标题去掉了 Markdown 井号，摘要剥掉了强调符号', () => {
    assert.doesNotMatch(html, /<h3 class="note-card-title"[^>]*>#/)
    assert.doesNotMatch(html, /note-card-summary"[^>]*>[^<]*\*\*/)
  })

  check('卡片链到详情页，href 指向 /notes/527', () => {
    assert.match(html, /href="\/notes\/527"/)
  })

  check('同一条 NOTE 的正文没有铺进聊天（FR-03 不刷屏）', () => {
    assert.doesNotMatch(html, /data-block-key/)
    // 顶栏那个空的 <h1> 不算：这里查的是「笔记标题被当成正文标题渲染出来了」
    assert.doesNotMatch(html, /<h1[^>]*>[^<]*笔记/)
    // 正文第二段的标题只可能来自整篇笔记，卡片上不该出现
    assert.doesNotMatch(html, /为什么需要/)
  })

  check('普通回答与用户消息照旧，不被卡片逻辑带跑', () => {
    assert.match(html, /你先说说任务类型，我们再定数量。/)
    assert.match(html, /class="bubble user"/)
    assert.match(html, /线程池核心数怎么定/)
  })

  check('卡片里显示字数，便于判断是不是空笔记', () => {
    assert.match(html, /note-meta"[^>]*>\d+ 字 · 可批注/)
  })
} finally {
  await vite.close()
}

console.log(`\nT021 自检 ${passed}/${passed} 断言通过。`)
