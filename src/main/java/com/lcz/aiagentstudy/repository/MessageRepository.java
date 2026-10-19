package com.lcz.aiagentstudy.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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
}
