package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.model.MessageRole;
import com.lcz.aiagentstudy.model.MessageRow;
import com.lcz.aiagentstudy.model.MessageType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        // TC-08：历史查询必须带 type，刷新后前端才认得出哪些是 NOTE 卡片
        List<MessageRow> history = messageService.listByConversation(conversationId);
        assertEquals(2, history.size());
        assertEquals(MessageType.QUESTION.name(), history.get(0).type());
        assertEquals(MessageType.ANSWER.name(), history.get(1).type());
        assertEquals("指针的别名是引用。", history.get(1).content());
    }

    // T004：笔记模式一轮仍落两行，第二条是 NOTE 并返回其 id；touch 与两行在同一事务
    @Test
    void appendNoteRound_insertsTwoRows_returnsNoteId_andTouchesConversation() throws Exception {
        UUID conversationId = newConversation(newUser("t-note-round-" + UUID.randomUUID()));
        // 把会话时间拨回一天，用来证明 appendNoteRound 真的 touch 了它
        jdbcTemplate.update("update t_conversation set updated_at = now() - interval '1 day' where id = ?",
                conversationId);
        OffsetDateTime before = jdbcTemplate.queryForObject(
                "select updated_at from t_conversation where id = ?", OffsetDateTime.class, conversationId);

        String noteText = "# 指针\n\n指针是保存地址的变量。";
        long noteId = messageService.appendNoteRound(conversationId, "整理一下指针", noteText);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, role, type, content FROM t_message WHERE conversation_id = ? ORDER BY id",
                conversationId);
        assertEquals(2, rows.size(), "笔记模式也只落两行");
        assertEquals(MessageRole.USER.name(), rows.get(0).get("role"));
        assertEquals(MessageType.QUESTION.name(), rows.get(0).get("type"));
        assertEquals("整理一下指针", rows.get(0).get("content"));
        assertEquals(MessageRole.ASSISTANT.name(), rows.get(1).get("role"));
        assertEquals(MessageType.NOTE.name(), rows.get(1).get("type"));
        assertEquals(noteText, rows.get(1).get("content"));
        assertEquals(((Number) rows.get(1).get("id")).longValue(), noteId, "返回的 id 必须是 NOTE 那一行");

        OffsetDateTime after = jdbcTemplate.queryForObject(
                "select updated_at from t_conversation where id = ?", OffsetDateTime.class, conversationId);
        assertTrue(after.isAfter(before), "appendNoteRound 必须 touch 会话");

        // 两条 INSERT + touch 由同一个 @Transactional 包住，不能拆成三个独立事务
        assertTrue(MessageService.class
                .getMethod("appendNoteRound", UUID.class, String.class, String.class)
                .isAnnotationPresent(Transactional.class));

        // NOTE 行在历史消息里带着 type 返回
        List<MessageRow> history = messageService.listByConversation(conversationId);
        assertEquals(2, history.size());
        assertEquals(MessageType.QUESTION.name(), history.get(0).type());
        assertEquals(MessageType.NOTE.name(), history.get(1).type());
    }
}
