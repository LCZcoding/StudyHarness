package com.lcz.aiagentstudy.model;

import java.time.OffsetDateTime;

public record MessageRow(Long messageId, String role, String content, OffsetDateTime createdAt) {
}
