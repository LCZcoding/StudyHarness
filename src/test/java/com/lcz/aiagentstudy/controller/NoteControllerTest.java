package com.lcz.aiagentstudy.controller;

import com.lcz.aiagentstudy.constant.SessionKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T012 / T024（plan.md 5.2 #2 / #3 / #8）的端到端用例：HTTP → Controller → Service → Repository → 真实库。
 *
 * <p>为什么不用 {@code @Transactional} 自动回滚：PostgreSQL 的 {@code now()} 取的是**事务开始时间**，
 * 在测试事务里插入与更新拿到的是同一个时间戳，{@code updated_at} 与 {@code created_at} 会相等，
 * 「保存后 updated_at 往前走」这条根本断言不出来。改成显式建数据 + {@code @AfterEach} 手动清理，
 * 两条语句各自自动提交，时间戳才是真的两个时刻。
 *
 * <p>本类不涉及模型调用，不需要 {@code @MockitoBean}。
 */
@SpringBootTest
@AutoConfigureMockMvc
class NoteControllerTest {

    /** 归属失败统一这一句：不存在 / 别人的 / 不是笔记，三者不许在文案上被区分开 */
    private static final String FORBIDDEN_MESSAGE = "笔记不存在或无权访问";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;

    private UUID ownerId;
    private UUID conversationId;
    private long noteId;

    /** 列表用例会一次性造多个用户 / 多个会话，全部登记在这里，由 {@link #cleanUp()} 统一删。 */
    private final List<UUID> extraUsers = new ArrayList<>();
    private final List<UUID> extraConversations = new ArrayList<>();

    private UUID newUser(String username) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO t_user (username, password_hash) VALUES (?, ?) RETURNING id",
                UUID.class, username, "x");
    }

    /** 造一个用户并登记到清理清单，返回 id */
    private UUID newUserTracked(String prefix) {
        UUID id = newUser(prefix + UUID.randomUUID());
        extraUsers.add(id);
        return id;
    }

    private UUID newConversation(UUID userId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO t_conversation (user_id, mode) VALUES (?, 'FREE_CHAT') RETURNING id",
                UUID.class, userId);
    }

    /** 造一个会话并登记到清理清单 */
    private UUID newConversationTracked(UUID userId) {
        UUID id = newConversation(userId);
        extraConversations.add(id);
        return id;
    }

    private long newMessage(String role, String type, String content) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO t_message (conversation_id, role, type, content) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, conversationId, role, type, content);
    }

    /** 在指定会话里插一条 NOTE，返回 id */
    private long newNoteIn(UUID convId, String content) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO t_message (conversation_id, role, type, content) VALUES (?, 'ASSISTANT', 'NOTE', ?) RETURNING id",
                Long.class, convId, content);
    }

    /** 给某篇笔记打一条顶层批注（目标 id 存成字符串，与生产口径一致） */
    private long newAnnotation(UUID userId, long targetNoteId, String quote) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO t_annotation (user_id, target_type, target_id, block_key, quote, question, answer)
                        VALUES (?, 'NOTE', ?, 'b-1', ?, '问', '答') RETURNING id
                        """,
                Long.class, userId, String.valueOf(targetNoteId), quote);
    }

    /** 给某个顶层批注挂一条子批注，target 仍指向同一篇笔记 */
    private void newChildAnnotation(UUID userId, long targetNoteId, long parentId, String quote) {
        jdbcTemplate.update("""
                        INSERT INTO t_annotation
                            (user_id, target_type, target_id, parent_id, block_key, quote, question, answer)
                        VALUES (?, 'NOTE', ?, ?, 'b-1', ?, '追问', '追答')
                        """,
                userId, String.valueOf(targetNoteId), parentId, quote);
    }

    /** 建「一个用户 + 一个会话 + 一条 NOTE」，顺手返回 note id */
    private void seedNote() {
        ownerId = newUser("t-note-owner-" + UUID.randomUUID());
        conversationId = newConversation(ownerId);
        noteId = newMessage("ASSISTANT", "NOTE", "# 原标题\n\nG1 是新生代回收器。");
    }

    @AfterEach
    void cleanUp() {
        List<UUID> users = new ArrayList<>();
        if (ownerId != null) {
            users.add(ownerId);
        }
        users.addAll(extraUsers);

        List<UUID> conversations = new ArrayList<>();
        if (conversationId != null) {
            conversations.add(conversationId);
        }
        conversations.addAll(extraConversations);

        for (UUID convId : conversations) {
            jdbcTemplate.update("DELETE FROM t_message WHERE conversation_id = ?", convId);
        }
        // 批注必须先删：t_annotation.user_id 有外键指向 t_user，倒过来删用户会失败
        for (UUID userId : users) {
            jdbcTemplate.update("DELETE FROM t_annotation WHERE user_id = ?", userId);
        }
        for (UUID convId : conversations) {
            jdbcTemplate.update("DELETE FROM t_conversation WHERE id = ?", convId);
        }
        for (UUID userId : users) {
            jdbcTemplate.update("DELETE FROM t_user WHERE id = ?", userId);
        }
    }

    // ---------- #2 GET /notes/{id} ----------

    @Test
    void get_unauthorized() throws Exception {
        mockMvc.perform(get("/notes/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void get_ownNote_returnsFullView() throws Exception {
        seedNote();

        mockMvc.perform(get("/notes/" + noteId).sessionAttr(SessionKeys.USER_ID, ownerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageId").value(noteId))
                .andExpect(jsonPath("$.conversationId").value(conversationId.toString()))
                .andExpect(jsonPath("$.type").value("NOTE"))
                .andExpect(jsonPath("$.content").value("# 原标题\n\nG1 是新生代回收器。"))
                .andExpect(jsonPath("$.createdAt").exists())
                // 从未保存过时 updated_at 落库即为插入时刻，不能是 null —— 前端直接用它可以省一次判空
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void get_noteOfOthers_forbidden() throws Exception {
        seedNote();
        UUID intruderId = newUser("t-intr-" + UUID.randomUUID());
        try {
            mockMvc.perform(get("/notes/" + noteId).sessionAttr(SessionKeys.USER_ID, intruderId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(FORBIDDEN_MESSAGE));
        } finally {
            jdbcTemplate.update("DELETE FROM t_user WHERE id = ?", intruderId);
        }
    }

    /** 「不存在」与「不归你」必须返回同一句：否则拿一串 id 试一遍就能探出哪些笔记真实存在 */
    @Test
    void get_notExists_sameMessageAsForbidden() throws Exception {
        seedNote();

        mockMvc.perform(get("/notes/" + 99999999L).sessionAttr(SessionKeys.USER_ID, ownerId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(FORBIDDEN_MESSAGE));
    }

    /**
     * 把自己会话里的普通聊天消息当笔记 id：归属 SQL 里带 {@code type='NOTE'}，
     * 所以在归属这关就被挡下（403），不会漏成 400。
     */
    @Test
    void get_ordinaryMessage_forbidden() throws Exception {
        seedNote();
        long chatMessageId = newMessage("ASSISTANT", "ANSWER", "这只是一条聊天回答");

        mockMvc.perform(get("/notes/" + chatMessageId).sessionAttr(SessionKeys.USER_ID, ownerId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(FORBIDDEN_MESSAGE));
    }

    // ---------- #3 PUT /notes/{id} ----------

    @Test
    void put_updatesContentAndUpdatedAt() throws Exception {
        seedNote();
        OffsetDateTime createdAt = jdbcTemplate.queryForObject(
                "SELECT created_at FROM t_message WHERE id = ?", OffsetDateTime.class, noteId);
        OffsetDateTime updatedAtBefore = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM t_message WHERE id = ?", OffsetDateTime.class, noteId);

        mockMvc.perform(put("/notes/" + noteId)
                        .sessionAttr(SessionKeys.USER_ID, ownerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"# 改过的标题\\n\\n我自己重写的正文。\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageId").value(noteId))
                .andExpect(jsonPath("$.updatedAt").exists());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT content FROM t_message WHERE id = ?", String.class, noteId))
                .isEqualTo("# 改过的标题\n\n我自己重写的正文。");
        OffsetDateTime updatedAtAfter = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM t_message WHERE id = ?", OffsetDateTime.class, noteId);
        assertThat(updatedAtAfter).isAfter(updatedAtBefore);
        // 覆盖写不碰 created_at
        assertThat(jdbcTemplate.queryForObject(
                "SELECT created_at FROM t_message WHERE id = ?", OffsetDateTime.class, noteId))
                .isEqualTo(createdAt);
    }

    @Test
    void put_noteOfOthers_forbiddenAndContentUntouched() throws Exception {
        seedNote();
        UUID intruderId = newUser("t-intr-" + UUID.randomUUID());
        try {
            mockMvc.perform(put("/notes/" + noteId)
                            .sessionAttr(SessionKeys.USER_ID, intruderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"越权覆盖\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(FORBIDDEN_MESSAGE));
        } finally {
            jdbcTemplate.update("DELETE FROM t_user WHERE id = ?", intruderId);
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT content FROM t_message WHERE id = ?", String.class, noteId))
                .isEqualTo("# 原标题\n\nG1 是新生代回收器。");
    }

    @Test
    void put_contentTooLong_badRequest() throws Exception {
        seedNote();

        mockMvc.perform(put("/notes/" + noteId)
                        .sessionAttr(SessionKeys.USER_ID, ownerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + "a".repeat(20001) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("笔记正文最多 20000 字"));
    }

    @Test
    void put_missingContent_badRequest() throws Exception {
        seedNote();

        mockMvc.perform(put("/notes/" + noteId)
                        .sessionAttr(SessionKeys.USER_ID, ownerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("content 不能为空"));
    }

    /** 覆盖写允许把正文清空：不替用户判断「空是不是误操作」 */
    @Test
    void put_emptyContent_allowed() throws Exception {
        seedNote();

        mockMvc.perform(put("/notes/" + noteId)
                        .sessionAttr(SessionKeys.USER_ID, ownerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"\"}"))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT content FROM t_message WHERE id = ?", String.class, noteId)).isEmpty();
    }

    @Test
    void put_unauthorized() throws Exception {
        mockMvc.perform(put("/notes/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- #8 GET /notes?limit= （T024，FR-14） ----------

    @Test
    void list_unauthorized() throws Exception {
        mockMvc.perform(get("/notes"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists());
    }

    /** 没有笔记是正常状态：`[]` 而不是 204 / 404 */
    @Test
    void list_userWithoutNotes_returnsEmptyArray() throws Exception {
        UUID me = newUserTracked("t-nl-");

        mockMvc.perform(get("/notes").sessionAttr(SessionKeys.USER_ID, me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    /**
     * 排序看的是 {@code updated_at} 而不是 id：先建 first、再建 second（second 更新），
     * 然后把 first 保存一次 → first 必须跳到队首。同时验证批注数包含子批注、
     * 没有批注的那篇是 0（{@code LEFT JOIN} 不能把行丢掉），普通聊天消息不进列表。
     */
    @Test
    void list_ordersByUpdatedAtDesc_andCountsAnnotations() throws Exception {
        UUID me = newUserTracked("t-nl-");
        UUID conv = newConversationTracked(me);
        long first = newNoteIn(conv, "# 第一篇\n\n正文一");
        long second = newNoteIn(conv, "# 第二篇\n\n正文二");
        // 同一会话里的普通聊天消息：不在列表里
        jdbcTemplate.update(
                "INSERT INTO t_message (conversation_id, role, type, content) VALUES (?, 'ASSISTANT', 'CHAT', '普通回答')",
                conv);

        long topOne = newAnnotation(me, second, "正文二");
        newAnnotation(me, second, "第二篇");
        newChildAnnotation(me, second, topOne, "正文");

        mockMvc.perform(get("/notes").sessionAttr(SessionKeys.USER_ID, me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // 刚插入的顺序：second 的 updated_at 更晚
                .andExpect(jsonPath("$[0].messageId").value(second))
                .andExpect(jsonPath("$[0].conversationId").value(conv.toString()))
                // 正文原样返回，后端不算标题 / 摘要 / 字数
                .andExpect(jsonPath("$[0].content").value("# 第二篇\n\n正文二"))
                .andExpect(jsonPath("$[0].title").doesNotExist())
                .andExpect(jsonPath("$[0].summary").doesNotExist())
                .andExpect(jsonPath("$[0].type").doesNotExist())
                // 2 条顶层 + 1 条子批注，都指向同一篇笔记
                .andExpect(jsonPath("$[0].annotationCount").value(3))
                .andExpect(jsonPath("$[1].messageId").value(first))
                .andExpect(jsonPath("$[1].annotationCount").value(0))
                .andExpect(jsonPath("$[1].createdAt").exists())
                .andExpect(jsonPath("$[1].updatedAt").exists());

        // 保存第一篇 → updated_at 往前走 → 队首换人
        mockMvc.perform(put("/notes/" + first)
                        .sessionAttr(SessionKeys.USER_ID, me)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"# 第一篇改过\\n\\n正文一\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/notes").sessionAttr(SessionKeys.USER_ID, me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].messageId").value(first))
                .andExpect(jsonPath("$[1].messageId").value(second));
    }

    /** 归属只认 {@code t_conversation.user_id}：别人的笔记一条都不出现，自己有几篇就只看到几篇 */
    @Test
    void list_neverShowsNotesOfOthers() throws Exception {
        UUID owner = newUserTracked("t-nl-o-");
        UUID ownerConv = newConversationTracked(owner);
        newNoteIn(ownerConv, "# 别人的笔记\n\n不该被你看到");

        UUID stranger = newUserTracked("t-nl-s-");
        mockMvc.perform(get("/notes").sessionAttr(SessionKeys.USER_ID, stranger))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        // 自己有一篇时，也只看到自己那一篇
        UUID strangerConv = newConversationTracked(stranger);
        long mine = newNoteIn(strangerConv, "# 我自己的笔记\n\n正文");
        mockMvc.perform(get("/notes").sessionAttr(SessionKeys.USER_ID, stranger))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].messageId").value(mine));
    }

    @Test
    void list_limitTakesEffect() throws Exception {
        UUID me = newUserTracked("t-nl-");
        UUID conv = newConversationTracked(me);
        newNoteIn(conv, "# 一\n\n正文一");
        newNoteIn(conv, "# 二\n\n正文二");
        long newest = newNoteIn(conv, "# 三\n\n正文三");

        mockMvc.perform(get("/notes").param("limit", "2").sessionAttr(SessionKeys.USER_ID, me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].messageId").value(newest));

        // 越界不报错：超过上限按 50 处理、0 / 负数夹到 1（都有笔记可返回，长度仍是 1）
        mockMvc.perform(get("/notes").param("limit", "999").sessionAttr(SessionKeys.USER_ID, me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
        mockMvc.perform(get("/notes").param("limit", "0").sessionAttr(SessionKeys.USER_ID, me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }
}
