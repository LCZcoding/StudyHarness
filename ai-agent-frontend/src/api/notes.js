import request from './request.js'

/**
 * 笔记读写三接口的前端薄封装，对应 plan.md 5.2 #2 / #3 / #8 与 NoteController。
 *
 * 只有三个函数，且都只做「拼路径 / 拼请求体」：
 *   - 身份靠 request.js 的 withCredentials 带 JSESSIONID，接口不收 userId；
 *   - 失败是 400 / 401 / 403 / 500 + { message }，由调用方用 utils/apiError.js
 *     的 redirectOn401 / messageOf 处理，封装层不拼业务文案；
 *   - 返回的是响应体本身（request.js 拦截器已脱壳），不用再取 .data。
 *
 * 这里 import 写成 './request.js'（带后缀），与 annotations.js 同一写法：
 * 只为让 Node 脚本能直接 import 本模块做请求形状自检，Vite / 浏览器行为完全一致。
 */

/**
 * #8 我的笔记列表
 * GET /api/notes?limit=50
 * 成功：200 [{ messageId, conversationId, content, annotationCount, createdAt, updatedAt }]
 *       已按 updatedAt 倒序，空列表是 []（不是 204 / 404）
 * 失败：401
 *
 * limit 默认 50、上限也是 50（后端夹取，越界不报错）。这里固定带上，
 * 免得「默认值」在前后端各存一份；真要做「加载更多」时再把这一个参数变成入参。
 *
 * 响应里没有标题 / 摘要 / 字数：那三样由页面用 utils/noteCard.js 从 content 算，
 * 保证卡片、列表、详情页三处标题摘自同一份实现（plan.md 5.2 #8）。
 */
export const list = (limit = 50) => request.get('/notes', { params: { limit } })

/**
 * #2 读笔记全文
 * GET /api/notes/{messageId}
 * 成功：200 { messageId, conversationId, type: "NOTE", content,
 *            createdAt, updatedAt }
 * 失败：400（messageId 不是数字）/ 401
 *       403 { message: "笔记不存在或无权访问" }（别人的 / 不存在 / 非 NOTE 三合一）
 *
 * 403 的三合一文案是后端刻意统一的口径（不泄露「这条消息存在但不是你的」），
 * 前端不要再拆开猜原因，直接展示。
 */
export const get = (messageId) => request.get(`/notes/${messageId}`)

/**
 * #3 覆盖写笔记正文
 * PUT /api/notes/{messageId}  body: { content }
 * 成功：200 { messageId, updatedAt }
 * 失败：400（content 为 null / 超 20000 字）/ 401 / 403
 *
 * 只发 content：后端请求体只声明了这一个字段，多传虽被忽略但没必要。
 * 允许提交空串（用户把正文全删了再保存是合法操作），封装层不做非空校验。
 * 成功后不回读正文：调用方手里就有刚提交的 draft，服务器原样存了这份。
 */
export const save = (messageId, content) =>
  request.put(`/notes/${messageId}`, { content })
