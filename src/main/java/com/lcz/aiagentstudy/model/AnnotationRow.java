package com.lcz.aiagentstudy.model;

import java.time.OffsetDateTime;

/**
 * 批注的对外形状：GET /annotations 的列表元素、POST /annotations/ask 的单条响应
 * （plan.md 5.2 #4）。userId / targetType / targetId 只用于服务端归属校验，不下发前端；
 * 前端按 parentId 组树、按 blockKey 分组并自行计算 stale。
 */
public record AnnotationRow(
        Long id,
        Long parentId,          // NULL = 顶层批注
        String blockKey,        // 子批注继承父的 blockKey
        String quote,           // 不可改
        String question,        // 不可改
        String answer,          // 唯一可编辑字段
        boolean edited,         // PATCH 后置 true
        OffsetDateTime createdAt
) {
}
