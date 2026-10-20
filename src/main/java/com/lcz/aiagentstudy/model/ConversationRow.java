package com.lcz.aiagentstudy.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ConversationRow(UUID conversationId, String mode, String title, OffsetDateTime updatedAt) {
}
