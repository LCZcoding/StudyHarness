import request from './request'

/**
 * 按资料出题
 * POST /api/quiz/generate  body: { knowledgeItemId }
 * 成功：200 { conversationId, questions: [{ questionId, kind, target, text }] }
 *       有未通过的旧题时会「重问 1 道 + 新出 2 道」，其余情况新出 3 道
 * 失败：400 / 401 / 403 { message: "资料不存在或无权访问" }
 *
 * 实测耗时约 4.4 秒（两次模型调用），调用方必须给骨架/loading 态。
 */
export const generate = (knowledgeItemId) =>
  request.post('/quiz/generate', { knowledgeItemId })

/**
 * 提交作答（每题最多 2 次，第 3 次服务端返回 400）
 * POST /api/quiz/{questionId}/attempt  body: { answer }
 * 成功：200 { verdict, feedback, gaps[], retryAllowed, stage, nextDueAt, attemptNo }
 *       verdict ∈ PASS | PARTIAL | FAIL；gaps 在 PARTIAL/FAIL 时 2~4 条
 * 失败：400 { message } / 401 / 403 { message: "题目不存在或无权访问" }
 */
export const attempt = (questionId, answer) =>
  request.post(`/quiz/${questionId}/attempt`, { answer })

/**
 * 追问（不产生成绩、不限次数，只会把该题标记为「求助过」）
 * POST /api/quiz/{questionId}/help  body: { question }
 * 成功：200 { answer }
 */
export const help = (questionId, question) =>
  request.post(`/quiz/${questionId}/help`, { question })

/**
 * 回放这道题的全部求助（刷新、重开抽屉都不丢）
 * GET /api/quiz/{questionId}/help
 * 成功：200 [{ role, content }]，role ∈ USER | ASSISTANT，按提问先后排
 * 失败：401 / 403 { message: "题目不存在或无权访问" }
 */
export const helpHistory = (questionId) =>
  request.get(`/quiz/${questionId}/help`)

/**
 * 恢复测验页
 * GET /api/quiz/conversations/{conversationId}
 * 成功：200 { conversationId, questions: [{ questionId, kind, target, text, verdict }] }
 *       verdict 为 null 表示还没作答
 * 失败：401 / 403 { message: "会话不存在或无权访问" }
 */
export const conversation = (conversationId) =>
  request.get(`/quiz/conversations/${conversationId}`)
