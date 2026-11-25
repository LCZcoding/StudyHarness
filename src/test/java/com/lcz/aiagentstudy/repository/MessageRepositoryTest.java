package com.lcz.aiagentstudy.repository;

import com.lcz.aiagentstudy.model.MessageRow;
import com.lcz.aiagentstudy.model.MessageType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T004 集成测试：打真实库（t_message.type / updated_at 已由 T001 迁移放宽 / 补齐），类上 @Transactional 自动回滚。
 * 覆盖 MessageRepository 的 V2 三件事：历史消息带 type（TC-08）、NOTE 跨两层归属、笔记读与改。
 */
@SpringBootTest
@Transactional
class MessageRepositoryTest {

    @Autowired
    private MessageRepository repo;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID newUser() {
        return jdbc.queryForObject(
                "insert into t_user(username, password_hash) values (?, ?) returning id",
                UUID.class, UUID.randomUUID().toString(), "test-hash");
    }

    private UUID newConversation(UUID userId) {
        return jdbc.queryForObject(
                "insert into t_conversation(user_id, mode) values (?, 'FREE_CHAT') returning id",
                UUID.class, userId);
    }

    private long newMessage(UUID conversationId, String role, String type, String content) {
        return repo.insertReturningId(conversationId, role, type, content);
    }

    @Test
    void findAllByConversation_returnsType_forAllKinds_includingNote() {
        UUID conversationId = newConversation(newUser());
        newMessage(conversationId, "USER", MessageType.QUESTION.name(), "问什么？");
        newMessage(conversationId, "ASSISTANT", MessageType.ANSWER.name(), "答什么。");
        long noteId = newMessage(conversationId, "ASSISTANT", MessageType.NOTE.name(), "# 笔记");

        List<MessageRow> rows = repo.findAllByConversation(conversationId);
        assertEquals(3, rows.size());
        assertEquals(MessageType.QUESTION.name(), rows.get(0).type());
        assertEquals(MessageType.ANSWER.name(), rows.get(1).type());
        assertEquals(MessageType.NOTE.name(), rows.get(2).type());
        assertEquals(noteId, rows.get(2).messageId());
        assertEquals("# 笔记", rows.get(2).content());
        assertEquals("ASSISTANT", rows.get(2).role());
        assertNotNull(rows.get(2).createdAt());
    }

    @Test
    void noteBelongsToUser_跨两层归属且只认NOTE() {
        UUID owner = newUser();
        UUID other = newUser();
        UUID ownerConversation = newConversation(owner);
        long noteId = newMessage(ownerConversation, "ASSISTANT", "NOTE", "本人的笔记");
        long plainId = newMessage(ownerConversation, "ASSISTANT", "ANSWER", "普通回答");
        UUID otherConversation = newConversation(other);
        long otherNoteId = newMessage(otherConversation, "ASSISTANT", "NOTE", "别人的笔记");

        assertTrue(repo.noteBelongsToUser(noteId, owner));
        assertTrue(repo.noteBelongsToUser(otherNoteId, other));
        assertFalse(repo.noteBelongsToUser(noteId, other), "别人的笔记对本人必须 false");
        assertFalse(repo.noteBelongsToUser(otherNoteId, owner));
        assertFalse(repo.noteBelongsToUser(plainId, owner), "普通 ANSWER 消息不能被当成笔记");
        assertFalse(repo.noteBelongsToUser(999999L, owner));
    }

    @Test
    void findNoteById_返回完整字段_非NOTE与不存在都为空() {
        UUID conversationId = newConversation(newUser());
        long noteId = newMessage(conversationId, "ASSISTANT", "NOTE", "# 指针\n\n正文。");
        long plainId = newMessage(conversationId, "ASSISTANT", "ANSWER", "普通回答");

        var note = repo.findNoteById(noteId).orElseThrow();
        assertEquals(noteId, note.messageId());
        assertEquals(conversationId, note.conversationId());
        assertEquals(MessageType.NOTE.name(), note.type());
        assertEquals("# 指针\n\n正文。", note.content());
        assertNotNull(note.createdAt());
        assertNotNull(note.updatedAt(), "T001 迁移补的 updated_at 必须能读出来");

        assertTrue(repo.findNoteById(plainId).isEmpty());
        assertTrue(repo.findNoteById(999999L).isEmpty());
    }

    @Test
    void updateContent_改正文并刷新updatedAt_非NOTE不写() {
        UUID conversationId = newConversation(newUser());
        long noteId = newMessage(conversationId, "ASSISTANT", "NOTE", "旧笔记");
        long plainId = newMessage(conversationId, "ASSISTANT", "ANSWER", "普通回答");
        jdbc.update("update t_message set updated_at = now() - interval '1 day' where id = ?", noteId);
        OffsetDateTime before = jdbc.queryForObject(
                "select updated_at from t_message where id = ?", OffsetDateTime.class, noteId);

        OffsetDateTime updatedAt = repo.updateContent(noteId, "改过的笔记").orElseThrow();
        assertEquals("改过的笔记", repo.findNoteById(noteId).orElseThrow().content());
        assertTrue(updatedAt.isAfter(before), "保存正文必须同时刷新 updated_at");
        assertEquals(updatedAt, repo.findNoteById(noteId).orElseThrow().updatedAt());

        assertTrue(repo.updateContent(plainId, "不许改").isEmpty());
        assertEquals("普通回答", jdbc.queryForObject(
                "select content from t_message where id = ?", String.class, plainId));
        assertTrue(repo.updateContent(999999L, "x").isEmpty());
    }
}
