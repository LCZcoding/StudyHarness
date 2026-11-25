/**
 * T021 自检之一（纯函数 + SSE 透传）：
 *  1) utils/noteCard.js：卡片标题 / 摘要 / 字数的取法与 spec FR-03 一致，
 *     中文按字截断、代码块与分割线不污染标题、行内标记去皮、脏输入不抛错。
 *  2) api/chatStream.js：请求体多了 asNote（默认 false，不传也是 false）；
 *     `event:note` 帧能被解析成 { noteId } 交给 onEvent，且帧序不被打乱。
 *
 * 不发真实网络请求：用假的 globalThis.fetch 返回一段 SSE 文本。
 *
 * 运行：cd ai-agent-frontend && node scripts/verify-noteCard.mjs
 */
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { noteCardOf, noteChars, noteSummary, noteTitle, NOTE_SUMMARY_MAX } from '../src/utils/noteCard.js'
import { streamChat } from '../src/api/chatStream.js'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const chatStreamSource = readFileSync(path.join(root, 'src/api/chatStream.js'), 'utf-8')

let passed = 0
function check(name, fn) {
  fn()
  passed += 1
  console.log(`  ok  ${name}`)
}

console.log('T021 · 卡片文案（utils/noteCard.js，纯函数）')

const NOTE = `# 线程池核心数怎么定

结论先说：核心数不是拍脑袋，**先看任务类型**。

## 为什么需要

- IO 密集型任务大部分时间在等
- CPU 密集型任务几乎不阻塞

| 任务类型 | 建议核心数 |
|---|---|
| IO 密集 | 2N |
| CPU 密集 | N+1 |

\`\`\`java
// # 这行注释里的井号不该被当成标题
int core = cpus * 2;
\`\`\`

> 常见误区：核心数越大越好。

参考 [Oracle 文档](https://example.com/pool)。
`

check('标题取第一个 Markdown 标题，且去掉井号', () => {
  assert.equal(noteTitle(NOTE), '线程池核心数怎么定')
})

check('整篇没有标题时退化成第一行正文', () => {
  assert.equal(noteTitle('先看任务类型再定核心数。\n第二段。'), '先看任务类型再定核心数。')
  assert.equal(noteTitle('**加粗开头**也更干净'), '加粗开头也更干净')
})

check('代码块里的 # 注释不会被当成标题', () => {
  assert.equal(noteTitle('```java\n# 不是标题\n```\n\n正文第一行'), '正文第一行')
})

check('空输入给出兜底标题，不抛错', () => {
  assert.equal(noteTitle(''), '未命名笔记')
  assert.equal(noteTitle(null), '未命名笔记')
  assert.equal(noteTitle(undefined), '未命名笔记')
})

check('摘要跳过标题，从正文第一句开始', () => {
  const s = noteSummary(NOTE)
  assert.ok(s.startsWith('结论先说：核心数不是拍脑袋，先看任务类型。'), `实际：${s}`)
  assert.ok(!s.includes('线程池核心数怎么定'), '摘要不该重复标题')
})

check('摘要剥掉强调 / 链接 / 行内代码 / 列表记号', () => {
  const s = noteSummary('## 标题\n\n- 看 `topK` 与 **相似度**，见 [文档](https://x.y)。')
  assert.equal(s, '看 topK 与 相似度，见 文档。')
})

check('摘要去掉分割线与表格分隔行，不出现一串短横', () => {
  const s = noteSummary('## 标题\n\n第一段。\n\n---\n\n|---|---|\n\n第二段。')
  assert.equal(s, '第一段。 第二段。')
})

check(`摘要按「字」而不是字节截到 ${NOTE_SUMMARY_MAX} 字，超了补省略号`, () => {
  const long = '召回'.repeat(100) // 200 个字
  const s = noteSummary(long)
  assert.equal(Array.from(s).length, NOTE_SUMMARY_MAX + 1) // +1 是省略号
  assert.ok(s.endsWith('…'))
  assert.equal(noteSummary('短句'), '短句')
})

check('摘要优先正文，正文为空时退回标题', () => {
  assert.equal(noteSummary('# 只有标题\n\n## 小节'), '只有标题 小节')
  assert.equal(noteSummary(''), '')
})

check('字数按可读文本算，不含代码块与标记符号', () => {
  assert.equal(noteChars('## 标题\n\n四个字的正文'), Array.from('标题四个字的正文').length)
  assert.equal(noteChars('```\n代码不算\n```'), 0)
})

check('一次拿齐三样，形状与卡片模板一致', () => {
  const card = noteCardOf(NOTE)
  assert.deepEqual(Object.keys(card).sort(), ['chars', 'summary', 'title'])
  assert.equal(card.title, '线程池核心数怎么定')
  assert.ok(card.chars > 50)
})

check('CRLF 与多余空行不影响结果', () => {
  assert.equal(noteTitle('# 标题\r\n\r\n\r\n正文'), '标题')
  assert.equal(noteSummary('# 标题\r\n\r\n正文一句。'), '正文一句。')
})

check('模块里没有任何 HTML 拼接（NFR-03 只裁剪纯文本）', () => {
  const source = readFileSync(path.join(root, 'src/utils/noteCard.js'), 'utf-8')
  assert.doesNotMatch(source, /innerHTML|v-html|document\.|escapeHtml/)
  assert.match(source, /^\s*\/\*\*/m)
})

console.log('\nT021 · 请求与帧解析（api/chatStream.js）')

const SSE_OK = [
  'event:delta',
  'data:{"text":"## 标题"}',
  '',
  'event:delta',
  'data:{"text":"正文"}',
  '',
  'event:note',
  'data:{"noteId":527}',
  '',
  'event:done',
  'data:{"conversationId":"c-1"}',
  '',
  ''
].join('\n')

let captured = null
const realFetch = globalThis.fetch

async function run(payload = {}) {
  const events = []
  captured = null
  globalThis.fetch = async (url, init) => {
    captured = { url, init }
    return new Response(SSE_OK, { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
  }
  await streamChat({
    conversationId: 'c-1',
    message: '线程池核心数怎么定',
    ...payload,
    onEvent: (name, data) => events.push([name, data])
  })
  globalThis.fetch = realFetch
  return events
}

const body = () => JSON.parse(captured.init.body)

const events = await run({ asNote: true })

check('请求打到 /api/ai/chat/stream，POST + JSON + 带 cookie', () => {
  assert.equal(captured.url, '/api/ai/chat/stream')
  assert.equal(captured.init.method, 'POST')
  assert.equal(captured.init.headers['Content-Type'], 'application/json')
  assert.equal(captured.init.credentials, 'include')
})

check('请求体在原四字段之后多一个 asNote=true', () => {
  assert.deepEqual(Object.keys(body()).sort(), ['asNote', 'conversationId', 'message', 'useRag', 'useTools'])
  assert.equal(body().asNote, true)
  assert.equal(body().useRag, false)
  assert.equal(body().useTools, false)
})

// 单独跑一次「不传 asNote」：body() 读的是最近一次 captured，所以必须先跑再断言
await run()

check('不传 asNote 时显式发 false（后端 Boolean 判空只看 true）', () => {
  assert.equal(body().asNote, false)
})

check('note 帧被解析成对象交给 onEvent，帧序 delta → note → done 不变', () => {
  assert.deepEqual(events.map(([name]) => name), ['delta', 'delta', 'note', 'done'])
  assert.deepEqual(events[2][1], { noteId: 527 })
  assert.equal(events[0][1].text, '## 标题')
})

check('api 层按事件名原样透传，没为 note 写特判（解读留在 ChatRoom）', () => {
  assert.doesNotMatch(chatStreamSource, /name === ['"]note['"]/)
  assert.match(chatStreamSource, /onEvent\?\.\(name, data\.json\)/)
})

console.log(`\nT021 自检 ${passed}/${passed} 断言通过。`)
