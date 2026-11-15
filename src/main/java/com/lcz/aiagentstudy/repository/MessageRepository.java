package com.lcz.aiagentstudy.repository;

import com.lcz.aiagentstudy.model.MessageRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
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
    // V2：SELECT 补 m.type（plan.md C1 / TC-08）——刷新历史后前端要靠它认出 NOTE 卡片
    public List<MessageRow> findAllByConversation(UUID conversationId) {
        return jdbcTemplate.query("""
            SELECT m.id, m.role, m.content, m.created_at, m.type
              FROM t_message m
             WHERE m.conversation_id = ?
             ORDER BY m.id
            """,
                (rs, rowNum) -> new MessageRow(
                        rs.getLong("id"),
                        rs.getString("role"),
                        rs.getString("content"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getString("type")),
                conversationId);
    }

    // 插一条并返回自增 id：出题要把"消息 id"回填到 t_question.message_id；
    // V2 的 appendNoteRound 也复用它拿 NOTE 行 id 去发 SSE note 帧（本方法因此无需新增）
    public long insertReturningId(UUID conversationId, String role, String type, String content) {
        return jdbcTemplate.queryForObject(
                "insert into t_message (conversation_id, role, type, content) values (?, ?, ?, ?) returning id",
                Long.class, conversationId, role, type, content);
    }

    // ---------- V2：NOTE 消息的读 / 归属 / 改（T004 共享底座，供 T005 / T012 复用） ----------

    /** 一条 NOTE 消息的完整视图，字段对齐 plan.md 5.2 #2 的 GET /notes/{id} 响应。 */
    public record NoteMessage(
            long messageId, UUID conversationId, String type, String content,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    /** 按 id 读 NOTE 全文。非 NOTE / 不存在 → empty（纵深防御，别把普通聊天消息当笔记正文）。 */
    public Optional<NoteMessage> findNoteById(long messageId) {
        return jdbcTemplate.query("""
                        select m.id, m.conversation_id, m.type, m.content, m.created_at, m.updated_at
                          from t_message m
                         where m.id = ? and m.type = 'NOTE'
                        """,
                (rs, rowNum) -> new NoteMessage(
                        rs.getLong("id"),
                        rs.getObject("conversation_id", UUID.class),
                        rs.getString("type"),
                        rs.getString("content"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)),
                messageId).stream().findFirst();
    }

    /** plan.md 4.4 的跨两层归属校验：t_message → t_conversation → t_user，且必须是 NOTE。 */
    public boolean noteBelongsToUser(long messageId, UUID userId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                        select exists(
                            select 1 from t_message m
                              join t_conversation c on c.id = m.conversation_id
                             where m.id = ? and m.type = 'NOTE' and c.user_id = ?)
                        """,
                Boolean.class, messageId, userId));
    }

    /** 保存笔记正文，同时写 updated_at（plan.md 5.2 #3）。返回新的 updatedAt，0 行 → empty。 */
    public Optional<OffsetDateTime> updateContent(long messageId, String content) {
        int rows = jdbcTemplate.update(
                "update t_message set content = ?, updated_at = now() where id = ? and type = 'NOTE'",
                content, messageId);
        return rows == 1 ? findNoteById(messageId).map(NoteMessage::updatedAt) : Optional.empty();
    }

    // ---------- T024：笔记列表（plan.md 5.2 #8） ----------

    /**
     * 列表行。**不含标题 / 摘要 / 字数** —— 那三样由前端 {@code utils/noteCard.js} 从 {@code content}
     * 算，卡片、列表、详情页共用同一份实现（plan.md 5.2 #8）。
     */
    public record NoteListRow(
            long messageId, UUID conversationId, String content,
            int annotationCount, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    /**
     * 某个用户的全部笔记，按 {@code updated_at DESC}。归属只认 {@code t_conversation.user_id}，
     * 与 {@link #noteBelongsToUser} 同一套 JOIN，不另立第二条判断。
     *
     * <p>两个易错点：
     * <ul>
     *   <li>{@code a.target_id} 是 varchar，而 {@code m.id} 是 bigint —— 必须显式 {@code m.id::text}。
     *       漏了 cast 在 PG 里会直接报「operator does not exist」，而不是「只是慢」；
     *       反过来把 {@code a.target_id} cast 成 bigint 则会因为脏数据整条查询崩掉。</li>
     *   <li>批注数不额外过滤 {@code a.user_id}：笔记归属单一用户，且只有正文所有者能给它打批注
     *       （FR-12），所以这条 JOIN 天然只汇到本人的批注。将来若支持共享笔记，须补 {@code a.user_id = c.user_id}。</li>
     * </ul>
     *
     * <p>排序补 {@code m.id DESC} 作次级键：同一次 {@code now()} 写入的多篇（批量造数据、测试）
     * {@code updated_at} 完全相同，只按时间排会得到不确定顺序。
     */
    public List<NoteListRow> findNotesByUser(UUID userId, int limit) {
        return jdbcTemplate.query("""
                        select m.id, m.conversation_id, m.content, m.created_at, m.updated_at,
                               count(a.id) as annotation_count
                          from t_message m
                          join t_conversation c on c.id = m.conversation_id
                          left join t_annotation a
                                 on a.target_type = 'NOTE' and a.target_id = m.id::text
                         where m.type = 'NOTE' and c.user_id = ?
                         group by m.id, m.conversation_id, m.content, m.created_at, m.updated_at
                         order by m.updated_at desc, m.id desc
                         limit ?
                        """,
                (rs, rowNum) -> new NoteListRow(
                        rs.getLong("id"),
                        rs.getObject("conversation_id", UUID.class),
                        rs.getString("content"),
                        rs.getInt("annotation_count"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)),
                userId, limit);
    }
}
