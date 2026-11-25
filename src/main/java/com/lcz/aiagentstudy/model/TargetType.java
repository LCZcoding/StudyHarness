package com.lcz.aiagentstudy.model;

/** 批注所挂载的正文类型。 */
public enum TargetType {
    /** 上传资料切出来的章节，targetId 为 t_knowledge_item.id（UUID）。 */
    SECTION,
    /** AI 生成的笔记，targetId 为 t_message.id（BIGINT，且 type='NOTE'）。 */
    NOTE
}
