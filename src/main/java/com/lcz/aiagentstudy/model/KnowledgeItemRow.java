package com.lcz.aiagentstudy.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 知识条目（用户自己的学习材料）。列表接口不取 content，避免一次拉太多正文。 */
public record KnowledgeItemRow(UUID id, String docTitle, int sectionNo, String title,
                               String sourceUrl, String content, String summary,
                               OffsetDateTime createdAt) {
}