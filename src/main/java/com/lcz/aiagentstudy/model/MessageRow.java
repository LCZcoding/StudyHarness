package com.lcz.aiagentstudy.model;

import java.time.OffsetDateTime;

/**
 * 会话历史里的一行消息。V2 追加 {@code type}：前端据此把 NOTE 渲染成笔记卡片（plan.md C1 / spec FR-03）。
 * 字段只增不改，旧消息读出来是 QUESTION / ANSWER / CHAT 等原有值。
 */
public record MessageRow(Long messageId, String role, String content, OffsetDateTime createdAt, String type) {
}
