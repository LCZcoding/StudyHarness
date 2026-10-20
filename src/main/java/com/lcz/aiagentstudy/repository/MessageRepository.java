package com.lcz.aiagentstudy.repository;

import com.lcz.aiagentstudy.model.MessageRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class MessageRepository {
    private final JdbcTemplate jdbcTemplate;

    // 只跟数据库说话，无 if：往 t_message 插一条
    // 形参收 String 不收枚举——枚举转值由 Service 侧显式 name() 负责（②格的坑）
    public void insert(UUID conversationId, String role, String type, String content) {
        jdbcTemplate.update("insert into t_message (conversation_id, role, type, content) values (?,?,?,?)",
                conversationId, role, type, content);
    }
    // 查某个会话的全部消息，按插入顺序（= 对话顺序）
    public List<MessageRow> findAllByConversation(UUID conversationId) {
        return jdbcTemplate.query("""
            SELECT id, role, content, created_at
              FROM t_message
             WHERE conversation_id = ?
             ORDER BY id
            """,
                (rs, rowNum) -> new MessageRow(
                        rs.getLong("id"),
                        rs.getString("role"),
                        rs.getString("content"),
                        rs.getObject("created_at", OffsetDateTime.class)),
                conversationId);
    }

}
