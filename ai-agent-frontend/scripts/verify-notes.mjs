/**
 * T019 自检：不启服务、不发真实网络请求。
 * 用 axios 的自定义 adapter 拦下两个封装实际发出的请求，
 * 断言 method / url / body 与 plan.md 5.2 #2 / #3 一致，
 * 并确认响应已被 request.js 脱壳、错误不被封装层吞掉。
 *
 * 运行：cd ai-agent-frontend && node scripts/verify-notes.mjs
 */
import assert from 'node:assert/strict'
import request from '../src/api/request.js'
import * as api from '../src/api/notes.js'
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

check('导出三个薄封装 get / list / save（list 由 T025 追加）', () => {
  assert.deepEqual(Object.keys(api).sort(), ['get', 'list', 'save'])
})

// --- #2 GET /notes/{messageId} ---
const noteView = {
  messageId: 4371,
  conversationId: 'c0a8012e-0000-0000-0000-000000000007',
  type: 'NOTE',
  content: '## 小结\n\n召回靠向量相似度…',
  createdAt: '2026-11-15T04:00:00Z',
  updatedAt: '2026-11-15T04:00:00Z'
}
nextResponse = noteView
const got = await api.get(4371)
check('#2 GET 路径正确（数字 id 直接拼进路径）', () => {
  const c = last()
  assert.equal(c.method, 'get')
  assert.equal(c.url, '/notes/4371')
})
check('#2 GET 不带 query / 请求体', () => {
  const c = last()
  assert.equal(c.params, undefined)
  assert.equal(c.data, undefined)
})
check('#2 响应已被拦截器脱壳，可直接读 content / type', () => {
  assert.equal(got.type, 'NOTE')
  assert.equal(got.messageId, 4371)
  assert.ok(got.content.includes('## 小结'))
})

// --- #3 PUT /notes/{messageId} ---
nextResponse = { messageId: 4371, updatedAt: '2026-11-15T05:00:00Z' }
const draft = '## 小结\n\n改写后的正文。'
const saved = await api.save(4371, draft)
check('#3 PUT 方法与路径正确', () => {
  const c = last()
  assert.equal(c.method, 'put')
  assert.equal(c.url, '/notes/4371')
})
check('#3 请求体恰好只有 content 一个键', () =>
  assert.deepEqual(bodyOf(last()), { content: draft }))
check('#3 返回 { messageId, updatedAt }，不额外回读正文', () => {
  assert.deepEqual(saved, { messageId: 4371, updatedAt: '2026-11-15T05:00:00Z' })
  assert.equal(calls.length, 2)
})

// --- 空串是合法正文，封装层不得拦截 ---
nextResponse = { messageId: 4371, updatedAt: '2026-11-15T05:01:00Z' }
await api.save(4371, '')
check('#3 空串原样提交（不做非空校验）', () =>
  assert.deepEqual(bodyOf(last()), { content: '' }))

// --- 错误路径：封装层不吞错，message 能被 utils/apiError.js 取到 ---
nextError = Object.assign(new Error('Request failed with status code 403'), {
  response: { status: 403, data: { message: '笔记不存在或无权访问' } }
})
const originalConsoleError = console.error
console.error = () => {} // request.js 拦截器会打一行技术日志，自检里静默
let caught = null
try {
  await api.get(1)
} catch (e) {
  caught = e
} finally {
  console.error = originalConsoleError
  nextError = null
}
check('403 原样抛出，messageOf 能拿到服务端 message', () => {
  assert.ok(caught)
  assert.equal(caught.response.status, 403)
  assert.equal(messageOf(caught, '兜底文案'), '笔记不存在或无权访问')
})

console.log(`\nT019 自检 ${passed}/${passed} 断言通过；共拦下 ${calls.length} 次请求（全部未出网）。`)
