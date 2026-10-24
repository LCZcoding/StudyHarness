import request from './request'

/**
 * 粘贴文本保存为一份文档（服务端按标题切章）
 * POST /api/knowledge-items  body: { title, content }
 * 成功：201 { docTitle, sections: [{ knowledgeItemId, sectionNo, title, chars, summary }] }
 * 失败：400 { message: "已存在同名文档，请改名后重新上传" / "title 不能为空" 等 } / 401
 *
 * 注意：服务端会逐章调模型生成摘要，响应要等所有章节处理完才返回，
 * 章节多时可能 1~2 分钟。调用方必须给「已等待 N 秒」这类真实反馈。
 */
export const createItem = (title, content) =>
  request.post('/knowledge-items', { title, content })

/**
 * 上传 .md / .txt / .pdf，同样按标题切章
 * POST /api/knowledge-items/upload  multipart: file(+title 可选)
 * 成功：201 { docTitle, sections: [{ knowledgeItemId, sectionNo, title, chars, summary }] }
 * 失败：400 { message } —— 扩展名不支持、空文本、超长、同名文档都在服务端拒绝
 *
 * 不要手写 Content-Type：传 FormData 时 axios 会删掉默认的 application/json，
 * 交给浏览器补上带 boundary 的 multipart 头；自己写死 multipart/form-data 反而会丢 boundary。
 */
export const uploadItem = (file, title) => {
  const form = new FormData()
  form.append('file', file)
  if (title) form.append('title', title)
  return request.post('/knowledge-items/upload', form)
}

/**
 * 我的资料：按文档分组，前端不再按 docTitle 二次分组
 * GET /api/knowledge-items
 * 成功：200 [{
 *   docTitle, sectionCount, passedCount,
 *   sections: [{ knowledgeItemId, sectionNo, title, chars, summary, status }]
 * }]
 *   status ∈ NOT_STARTED | IN_PROGRESS | PASSED（服务端按 SELF_TEST 题目推导）
 *   summary 可能为 null（该章摘要生成失败）
 * 失败：401
 */
export const listDocuments = () => request.get('/knowledge-items')

/**
 * 单章正文，阅读页用
 * GET /api/knowledge-items/{knowledgeItemId}
 * 成功：200 { knowledgeItemId, docTitle, sectionNo, title, chars, content }
 * 失败：400 { message: "knowledgeItemId 不合法" } / 403 { message: "资料不存在或无权访问" }
 *
 * content 首行仍是 markdown 标题（如 "## 第 1 节 线程与锁"），渲染时去掉行首 #。
 */
export const getSection = (knowledgeItemId) =>
  request.get(`/knowledge-items/${knowledgeItemId}`)
