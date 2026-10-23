package com.lcz.aiagentstudy.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lcz.aiagentstudy.model.ConversationRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ConversationRepository {
    private final JdbcTemplate jdbcTemplate;

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

    // 消息落库后，把会话的 updated_at 推到当前时间
    public void touch(UUID conversationId) {
        jdbcTemplate.update("update t_conversation set updated_at = now() where id = ?", conversationId);
    }

    // 查某个用户的全部会话列表，按「最近活动」倒序；标题由首条 USER 消息派生
    public List<ConversationRow> findAllByUserId(UUID userId){
        return jdbcTemplate.query("""
                        select c.id, c.mode, c.updated_at,
                                        (SELECT left(m.content, 50)
                                          FROM t_message m
                                          WHERE m.conversation_id = c.id AND m.role = 'USER'
                                          ORDER BY m.id LIMIT 1) AS title
                            from t_conversation c
                            where c.user_id = ?
                            order by c.updated_at DESC
                        """,
                (rs, rowNum) -> new ConversationRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("mode"),
                        rs.getString("title"),
                        rs.getObject("updated_at", OffsetDateTime.class)),
                userId);

    }

    public Optional<UUID> findLatestIdByMode(UUID userId, String mode) {
        return jdbcTemplate.query(
                        "select id from t_conversation where user_id = ? and mode = ? " +
                                "order by created_at desc limit 1",
                        (rs, i) -> rs.getObject("id", UUID.class), userId, mode)
                .stream().findFirst();
    }


}
