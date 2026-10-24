import request from './request'

/**
 * 按章节生成练习方案（PRACTICE 会话首轮）
 * POST /api/practice/guide  body: { knowledgeItemId, goal? }
 * 成功：200 { conversationId, content }
 *       content 是可动手做的小项目 / 练习步骤 / 验收标准
 * 失败：400 { message } / 401 / 403 { message: "资料不存在或无权访问" }
 *
 * 会调模型，耗时与出题同量级；调用方必须给进行中态。
 * conversationId 用来接后续追问：/guide/:conversationId。
 */
export const guide = (knowledgeItemId, goal) =>
  request.post('/practice/guide', { knowledgeItemId, goal })
