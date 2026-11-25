import request from './request.js'

/**
 * 批注四接口的前端薄封装，对应 plan.md 5.2 #4~#7 与 T006 的 AnnotationController。
 *
 * 四个函数只做「拼路径 / 拼请求体」，不缓存、不重试、不吞错误：
 *   - 身份靠 request.js 的 withCredentials 带 JSESSIONID，接口不收 userId；
 *   - 失败是 400 / 401 / 403 / 500 + { message }，由调用方用 utils/apiError.js
 *     的 redirectOn401 / messageOf 处理，封装层不拼业务文案；
 *   - 返回的是响应体本身（request.js 拦截器已脱壳），不用再取 .data。
 *
 * 这里 import 写成 './request.js'（带后缀）而不是项目其余 api 文件的 './request'：
 * 只为了让 Node 脚本能直接 import 本模块做请求形状自检，Vite / 浏览器行为完全一致。
 */

/**
 * #4 拉取某篇正文下的全部批注（扁平数组，按 created_at 升序）
 * GET /api/annotations?targetType=SECTION|NOTE&targetId=...
 * 成功：200 [{ id, parentId, blockKey, quote, question, answer, edited, createdAt }]
 * 失败：400 { message }（枚举非法 / 缺参 / targetId 超 64 字）/ 401 / 403（目标非本人）
 *
 * 后端返回扁平列表，按 blockKey 分组、按 parentId 组树、算 stale 都是 T016 的事，
 * 这里不重排也不改字段名。
 */
export const get = (targetType, targetId) =>
  request.get('/annotations', { params: { targetType, targetId } })

/**
 * #5 选中文字后生成一条批注
 * POST /api/annotations/ask
 * body: { targetType, targetId, blockKey, quote, question, parentId? }
 * 成功：200 新建的那一条（形状同 #4 的元素）
 * 失败：400（校验失败 / 限额 200 / 非法 parentId / 链深超 8 / 跨段落选区）
 *       401 / 403（归属失败） / 500（模型调用失败）
 *
 * parentId 非 null 表示这是「在父批注答案里选词追问」的子批注，blockKey 由后端继承父。
 * 这一路会真实调模型，调用方要给 loading 态、提交期间禁用按钮（后端不做幂等）。
 *
 * 显式挑字段而不是整个 payload 透传：组件里的 children / stale / groupKey 等派生状态
 * 不能混进契约；parentId 只在有值时发送，避免传空值。
 */
export const ask = (payload) => {
  const body = {
    targetType: payload.targetType,
    targetId: payload.targetId,
    blockKey: payload.blockKey,
    quote: payload.quote,
    question: payload.question
  }
  if (payload.parentId != null) body.parentId = payload.parentId
  return request.post('/annotations/ask', body)
}

/**
 * #6 手工编辑批注答案
 * PATCH /api/annotations/{id}  body: { answer }
 * 成功：200 更新后的那条（edited=true）
 * 失败：400（只允许提交 answer 字段 / 空值 / 超 2000 字）/ 401 / 403
 *
 * 后端要求请求体「恰好一个 answer 字符串键」，所以这里只发 answer，
 * 不把 question / quote 一起带过去（多传会被 400 拒）。
 */
export const patch = (id, answer) =>
  request.patch(`/annotations/${id}`, { answer })

/**
 * #7 删除批注（FK 级联删后代）
 * DELETE /api/annotations/{id}
 * 成功：200 { id, deletedCount }（deletedCount 含自身，仅供结果提示）
 * 失败：401 / 403
 *
 * 删除前要提示的 N 由前端用 #4 已组好的树自己算，没有预检接口。
 */
export const remove = (id) => request.delete(`/annotations/${id}`)
