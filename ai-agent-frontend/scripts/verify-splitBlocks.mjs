/**
 * TC-01：splitBlocks 对含代码块 / 列表 / 表格的 markdown 不切坏。
 * 本项目前端没有单测框架，纯函数用 Node 直接跑；不引入任何新依赖。
 *   node scripts/verify-splitBlocks.mjs
 */
import assert from 'node:assert/strict'
import { splitBlocks, renderMarkdown } from '../src/utils/markdown.js'

let passed = 0
function check(name, fn) {
  fn()
  passed++
  console.log('  PASS', name)
}

const MD = [
  '# 指针与引用',
  '',
  '第一段讲线程池核心参数，这里有一句可以被选中的话。',
  '',
  '```js',
  'const a = 1',
  'const b = 2',
  'console.log(a + b)',
  '```',
  '',
  '- 列表项一',
  '- 列表项二',
  '  - 嵌套项',
  '',
  '| 列A | 列B |',
  '| --- | --- |',
  '| 1 | 2 |',
  '',
  '最后一段讲余弦相似度与归一化。'
].join('\n')

const blocks = splitBlocks(MD)

check('空输入返回空数组', () => {
  assert.deepEqual(splitBlocks(null), [])
  assert.deepEqual(splitBlocks(''), [])
  assert.deepEqual(splitBlocks(undefined), [])
})

check('space token 被过滤，key 连续且从 p-0 开始', () => {
  assert.equal(blocks.length, 6)
  assert.deepEqual(blocks.map(b => b.key), ['p-0', 'p-1', 'p-2', 'p-3', 'p-4', 'p-5'])
  for (const b of blocks) {
    assert.ok(b.text.trim().length > 0, `${b.key} 不应是空白块`)
    assert.ok(b.html.trim().length > 0, `${b.key} 应有 html`)
  }
})

check('代码块整体一块，没被内部换行切坏', () => {
  const code = blocks.find(b => b.text.startsWith('```'))
  assert.ok(code, '应存在代码块')
  assert.ok(code.text.includes('const a = 1'))
  assert.ok(code.text.includes('console.log(a + b)'))
  assert.ok(code.html.includes('<pre>') && code.html.includes('<code'))
  assert.ok(!code.html.includes('最后一段'), '代码块里不能混入后面的段落')
})

check('列表整体一块（含嵌套项），html 有 ul/li', () => {
  const list = blocks.find(b => b.text.startsWith('- 列表项一'))
  assert.ok(list, '应存在列表块')
  assert.ok(list.text.includes('嵌套项'))
  assert.ok(list.html.includes('<ul>') && list.html.includes('<li>'))
  assert.ok(!list.html.includes('<table>'), '列表不能与表格粘成一块')
})

check('GFM 表格整体一块，html 有 table', () => {
  const table = blocks.find(b => b.text.startsWith('| 列A'))
  assert.ok(table, '应存在表格块')
  assert.ok(table.text.includes('| 1 | 2 |'))
  assert.ok(table.html.includes('<table>') && table.html.includes('<td>'))
})

check('标题与两个段落各自成块，互不串内容', () => {
  const heading = blocks.find(b => b.text.startsWith('# '))
  assert.ok(heading.html.includes('<h1>'))
  const paragraphs = blocks.filter(b => b.text.startsWith('第一段') || b.text.startsWith('最后一段'))
  assert.equal(paragraphs.length, 2)
  assert.notEqual(paragraphs[0].key, paragraphs[1].key)
  assert.ok(!paragraphs[0].html.includes('余弦相似度'))
  assert.ok(!paragraphs[1].html.includes('线程池'))
})

check('只有换行与空格的正文不产生空块', () => {
  const sparse = splitBlocks('\n\n   \n\n第一段。\n\n\n\n第二段。\n\n')
  assert.equal(sparse.length, 2)
  assert.deepEqual(sparse.map(b => b.key), ['p-0', 'p-1'])
})

check('Node 无 DOM 时 renderMarkdown 也不抛错（浏览器仍走 DOMPurify）', () => {
  assert.ok(renderMarkdown('# hi').includes('<h1>'))
})

console.log('\n分块结果预览（TC-01 的输入）：')
for (const b of blocks) {
  const preview = b.text.replace(/\s+/g, ' ').trim().slice(0, 52)
  console.log(`  ${b.key.padEnd(5)} ${preview}${b.text.length > 52 ? '…' : ''}`)
}
console.log(`\nTC-01 PASS · ${passed} 项断言全过`)
