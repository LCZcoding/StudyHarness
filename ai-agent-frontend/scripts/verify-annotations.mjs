/**
 * T014 自检：不启服务、不发真实网络请求。
 * 用 axios 的自定义 adapter 拦下四个封装实际发出的请求，
 * 断言 method / url / params / body 与 plan.md 5.2 #4~#7 一致，
 * 并确认响应已被 request.js 脱壳、错误不被封装层吞掉。
 *
 * 运行：cd ai-agent-frontend && node scripts/verify-annotations.mjs
 */
import assert from 'node:assert/strict'
import request from '../src/api/request.js'
import * as api from '../src/api/annotations.js'
import { messageOf } from '../src/utils/apiError.js'

const calls = []
let nextResponse = null
let nextError = null

request.defaults.adapter = (config) => {
  calls.push(config)
  if (nextError) return Promise.reject(nextError)
  return Promise.resolve({
    data: nextResponse,
    status: 200,
    statusText: 'OK',
    headers: {},
    config
  })
}

const last = () => calls[calls.length - 1]
const bodyOf = (c) =>
  typeof c.data === 'string' && c.data ? JSON.parse(c.data) : c.data

let passed = 0
function check(name, fn) {
  fn()
  passed += 1
  console.log(`  ok  ${name}`)
}

// --- 请求层基础配置没有被本任务改坏 ---
check('request.js 的 baseURL / withCredentials 保持原样', () => {
  assert.equal(request.defaults.baseURL, '/api')
  assert.equal(request.defaults.withCredentials, true)
})

check('导出恰好四个薄封装 get / ask / patch / remove', () => {
  assert.deepEqual(Object.keys(api).sort(), ['ask', 'get', 'patch', 'remove'])
})

// --- #4 GET /annotations ---
const flat = [
  { id: 12, parentId: null, blockKey: 'p-1', quote: '召回 topK 片段',
    question: 'topK 是什么？', answer: '取前 K 个片段。', edited: false, createdAt: '2026-11-14T00:00:00Z' },
  { id: 13, parentId: 12, blockKey: 'p-1', quote: '余弦相似度',
    question: '相似度怎么算？', answer: '看夹角。', edited: false, createdAt: '2026-11-14T00:00:01Z' }
]
nextResponse = flat
const list = await api.get('SECTION', 'b4f6c1d0-0000-0000-0000-000000000001')
check('#4 GET 路径与 query 形状正确（targetType / targetId）', () => {
  const c = last()
  assert.equal(c.method, 'get')
  assert.equal(c.url, '/annotations')
  assert.deepEqual(c.params, {
    targetType: 'SECTION',
    targetId: 'b4f6c1d0-0000-0000-0000-000000000001'
  })
  assert.equal(c.data, undefined)
})
check('#4 返回裸数组、字段不改名不重排', () => assert.deepEqual(list, flat))

const noteList = await api.get('NOTE', '88')
check('#4 NOTE 目标同样按字符串传 targetId', () => {
  assert.deepEqual(last().params, { targetType: 'NOTE', targetId: '88' })
  assert.equal(typeof last().params.targetId, 'string')
})

// --- #5 POST /annotations/ask ---
const askBase = {
  targetType: 'SECTION',
  targetId: 'b4f6c1d0-0000-0000-0000-000000000001',
  blockKey: 'p-1',
  quote: '召回 topK 片段',
  question: 'topK 是什么？'
}
nextResponse = flat[0]
const topRow = await api.ask({ ...askBase, parentId: null,
  // 前端派生状态：不应混进契约
  children: [], stale: false, groupKey: 'p-1', answer: '不该被透传' })
check('#5 顶层批注：路径 / 六个契约字段 / 不传 parentId', () => {
  const c = last()
  assert.equal(c.method, 'post')
  assert.equal(c.url, '/annotations/ask')
  assert.deepEqual(bodyOf(c), {
    targetType: 'SECTION',
    targetId: 'b4f6c1d0-0000-0000-0000-000000000001',
    blockKey: 'p-1',
    quote: '召回 topK 片段',
    question: 'topK 是什么？'
  })
})
check('#5 组件派生字段（children / stale / groupKey / answer）未透传', () => {
  const b = bodyOf(last())
  for (const k of ['children', 'stale', 'groupKey', 'answer', 'parentId']) {
    assert.equal(Object.hasOwn(b, k), false, `不该出现字段 ${k}`)
  }
})
check('#5 返回新建的单条批注（不是数组）', () => assert.deepEqual(topRow, flat[0]))

await api.ask({ ...askBase, parentId: 12, quote: '余弦相似度' })
check('#5 子批注带 parentId，且 blockKey 仍由调用方传、后端继承父', () => {
  const b = bodyOf(last())
  assert.equal(b.parentId, 12)
  assert.equal(b.blockKey, 'p-1')
  assert.equal(b.quote, '余弦相似度')
})

// --- #6 PATCH /annotations/{id} ---
nextResponse = { ...flat[0], answer: '我改过的解释', edited: true }
const patched = await api.patch(12, '我改过的解释')
check('#6 PATCH 路径与请求体恰好一个 answer 键', () => {
  const c = last()
  assert.equal(c.method, 'patch')
  assert.equal(c.url, '/annotations/12')
  assert.deepEqual(bodyOf(c), { answer: '我改过的解释' })
  assert.equal(Object.keys(bodyOf(c)).length, 1)
})
check('#6 返回更新后的那条（edited=true）', () => {
  assert.equal(patched.answer, '我改过的解释')
  assert.equal(patched.edited, true)
})

// --- #7 DELETE /annotations/{id} ---
nextResponse = { id: 12, deletedCount: 3 }
const deleted = await api.remove(12)
check('#7 DELETE 路径且无请求体', () => {
  const c = last()
  assert.equal(c.method, 'delete')
  assert.equal(c.url, '/annotations/12')
  assert.equal(c.data, undefined)
})
check('#7 返回 { id, deletedCount } 直接可用', () =>
  assert.deepEqual(deleted, { id: 12, deletedCount: 3 }))

// --- 错误路径：封装层不吞错，message 能被 utils/apiError.js 取到 ---
nextError = Object.assign(new Error('Request failed with status code 400'), {
  response: { status: 400, data: { message: '只允许提交 answer 字段' } }
})
const originalConsoleError = console.error
console.error = () => {} // request.js 拦截器会打一行技术日志，自检里静默
let caught = null
try {
  await api.patch(12, 'x')
} catch (e) {
  caught = e
} finally {
  console.error = originalConsoleError
  nextError = null
}
check('失败原样抛出，messageOf 能拿到服务端 message', () => {
  assert.ok(caught)
  assert.equal(messageOf(caught, '兜底文案'), '只允许提交 answer 字段')
})

console.log(`\nT014 自检 ${passed}/${passed} 断言通过；共拦下 ${calls.length} 次请求（全部未出网）。`)
