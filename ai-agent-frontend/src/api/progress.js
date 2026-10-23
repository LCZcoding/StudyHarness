import request from './request'

/**
 * 学习进度
 * GET /api/progress
 * 成功：200 { masteredItems, totalItems, streakDays, dueCount,
 *            weeks: [{ weekStart, attempts }] }  —— weeks 固定 8 项
 * 失败：401
 */
export const summary = () => request.get('/progress')
