package com.lcz.yuaiagent.repository;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository 
public class ConversationRepository {
    private final JdbcTemplate jdbcTemplate;

    public ConversationRepository(JdbcTemplate jdbcTemplate){
        this.jdbcTemplate = jdbcTemplate;
    }

    // create conversation
    public UUID create(UUID userId, String mode){
        return jdbcTemplate.queryForObject(
                "insert into t_conversation (user_id, mode) values (?, ?) returning id",
                UUID.class,
                userId,
                mode);

    }

    // 归属校验(根据用户id判断对话是否归属当前用户)
    public boolean belongsTo(UUID conversationId, UUID userId){
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                //select 1 from t_conversation where id = ? and user_id = ?返回1 或‘0行’，0行对queryForObject会报错
                "select exists(select 1 from t_conversation where id = ? and user_id = ?)",
                Boolean.class,
                conversationId,
                userId));
    }


}
