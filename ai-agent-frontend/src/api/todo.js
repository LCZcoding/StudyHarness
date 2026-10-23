import request from './request'

/**
 * 待办列表
 * GET /api/todos
 * 成功：200 { items: [{ type, knowledgeItemId, conversationId, title, count }] }
 *       type ∈ REVIEW | RETRY | UNTESTED，顺序由后端决定（REVIEW 一条汇总排最前）
 *       REVIEW：knowledgeItemId 与 conversationId 都是 null，count 是到期题数
 *       RETRY：knowledgeItemId 有值、conversationId 为 null（服务端尚未补该字段）
 *       UNTESTED：两者都有值，可直接打开对应测验
 */
export const todos = () => request.get('/todos')

/**
 * 开始复习
 * POST /api/review/start  无请求体
 * 成功：200 { conversationId, questions: [...] }
 *       幂等：上一轮没答完时返回同一会话、只含未答题，不新建、不复制
 * 失败：400 { message: "现在没有到期需要复习的题" } / 401
 */
export const startReview = () => request.post('/review/start')
