import request from './request'

/**
 * 粘贴文本保存为一份资料
 * POST /api/knowledge-items  body: { title, content }
 * 成功：201 { knowledgeItemId, preview, summary }
 * 失败：400 { message: "title 不能为空" / "正文最多 20000 字" 等 } / 401
 */
export const createItem = (title, content) =>
  request.post('/knowledge-items', { title, content })

/**
 * 上传 .md / .txt / .pdf
 * POST /api/knowledge-items/upload  multipart: file(+title 可选)
 * 成功：201 { knowledgeItemId, preview, summary }
 * 失败：400 { message } —— 扩展名不支持、空文本、超长都在服务端拒绝
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
 * 我的资料列表
 * GET /api/knowledge-items
 * 成功：200 [{ knowledgeItemId, title, sourceUrl, summary, createdAt }]
 *       后端直接返回数组（没有包一层 items），summary 可能为 null
 * 失败：401
 */
export const listItems = () => request.get('/knowledge-items')
