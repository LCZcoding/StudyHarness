package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.model.MessageRole;
import com.lcz.aiagentstudy.model.MessageType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Transactional // 跑完自动回滚，不脏库
class MessageServiceTest {

    @Autowired MessageService messageService;
    @Autowired JdbcTemplate jdbcTemplate;

    private UUID newUser(String username) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO t_user (username, password_hash) VALUES (?, ?) RETURNING id",
                UUID.class,
                username,
                "x");
    }

    private UUID newConversation(UUID userId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO t_conversation (user_id, mode) VALUES (?, 'FREE_CHAT') RETURNING id",
                UUID.class,
                userId);
    }

    // 用例 6：一个事务里落两行，USER 在前、ASSISTANT 在后，type 分别对齐 DB CHECK 里的字符串
    @Test
    void appendRound_insertsTwoRowsInOrder() {
        UUID conversationId = newConversation(newUser("t-round-" + UUID.randomUUID()));

        messageService.appendRound(conversationId, "什么是指针的别名？", "指针的别名是引用。");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT role, type, content FROM t_message WHERE conversation_id = ? ORDER BY id",
                conversationId);

        assertEquals(2, rows.size(), "一轮问答必须恰好落两行");

        assertEquals(MessageRole.USER.name(), rows.get(0).get("role"));
        assertEquals(MessageType.QUESTION.name(), rows.get(0).get("type"));
        assertEquals("什么是指针的别名？", rows.get(0).get("content"));

        assertEquals(MessageRole.ASSISTANT.name(), rows.get(1).get("role"));
        assertEquals(MessageType.ANSWER.name(), rows.get(1).get("type"));
        assertEquals("指针的别名是引用。", rows.get(1).get("content"));
    }
}
