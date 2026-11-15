package com.lcz.aiagentstudy.repository;

import com.lcz.aiagentstudy.model.AnnotationRow;
import com.lcz.aiagentstudy.model.TargetType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 批注持久化。归属校验与限额判断的 SQL 见 plan.md 4.4；不在本层标 @Transactional。 */
@Repository
@RequiredArgsConstructor
public class AnnotationRepository {
    private final JdbcTemplate jdbcTemplate;

    private static final String COLUMNS = """
            select id, user_id, target_type, target_id, parent_id, block_key,
                   quote, question, answer, edited, created_at
              from t_annotation
            """;

    /** 库表全字段行。userId / targetType / targetId 只供服务端校验，不下发前端。 */
    public record AnnotationEntity(
            long id, UUID userId, TargetType targetType, String targetId,
            Long parentId, String blockKey, String quote, String question,
            String answer, boolean edited, OffsetDateTime createdAt) {

        public AnnotationRow toRow() {
            return new AnnotationRow(id, parentId, blockKey, quote, question, answer, edited, createdAt);
        }
    }

    /** 新增批注入参。userId 来自会话，不放进请求体。 */
    public record NewAnnotation(
            TargetType targetType, String targetId, Long parentId,
            String blockKey, String quote, String question, String answer) {
    }

    private static final RowMapper<AnnotationEntity> ENTITY_MAPPER = (rs, n) -> map(rs);

    private static AnnotationEntity map(ResultSet rs) throws SQLException {
        long parentRaw = rs.getLong("parent_id");
        Long parentId = rs.wasNull() ? null : parentRaw;
        return new AnnotationEntity(
                rs.getLong("id"),
                rs.getObject("user_id", UUID.class),
                TargetType.valueOf(rs.getString("target_type")),
                rs.getString("target_id"),
                parentId,
                rs.getString("block_key"),
                rs.getString("quote"),
                rs.getString("question"),
                rs.getString("answer"),
                rs.getBoolean("edited"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    /** 新增一条批注，返回自增 id。parentId 为 null 即顶层批注。 */
    public long insert(UUID userId, NewAnnotation cmd) {
        return jdbcTemplate.queryForObject("""
                        insert into t_annotation
                            (user_id, target_type, target_id, parent_id, block_key, quote, question, answer)
                        values (?, ?, ?, ?, ?, ?, ?, ?) returning id
                        """,
                Long.class,
                userId, cmd.targetType().name(), cmd.targetId(), cmd.parentId(),
                cmd.blockKey(), cmd.quote(), cmd.question(), cmd.answer());
    }

    /**
     * GET /annotations 的扁平列表（plan.md 5.2 #4）。
     * 带 user_id 是纵深防御：即便上层漏了目标归属校验，也不会读到他人批注。
     * 次级排序 id 不可省 —— 同一事务内多条 created_at 可能完全相同，
     * 只按 created_at 排会让子批注排到父前面，前端组树时会丢节点。
     */
    public List<AnnotationRow> findByTarget(UUID userId, TargetType targetType, String targetId) {
        return jdbcTemplate.query(COLUMNS + """
                         where user_id = ? and target_type = ? and target_id = ?
                         order by created_at, id
                        """,
                        ENTITY_MAPPER, userId, targetType.name(), targetId)
                .stream().map(AnnotationEntity::toRow).toList();
    }

    /** 单条全字段，供 I1 归属校验、父批注上下文与祖先链上溯使用。 */
    public Optional<AnnotationEntity> findById(long id) {
        return jdbcTemplate.query(COLUMNS + " where id = ?", ENTITY_MAPPER, id)
                .stream().findFirst();
    }

    public boolean belongsTo(long id, UUID userId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "select exists(select 1 from t_annotation where id = ? and user_id = ?)",
                Boolean.class, id, userId));
    }

    /** I3：单个目标下批注总数（含子批注）≤ 200 的判断依据。 */
    public int countByTarget(UUID userId, TargetType targetType, String targetId) {
        Integer n = jdbcTemplate.queryForObject("""
                        select count(*) from t_annotation
                         where user_id = ? and target_type = ? and target_id = ?
                        """,
                Integer.class, userId, targetType.name(), targetId);
        return n == null ? 0 : n;
    }

    /** I2：沿 parent_id 上溯到根的深度（含自身）。新建子批注前查父，>=8 即拒绝。 */
    public int depthOf(long id) {
        Integer d = jdbcTemplate.queryForObject("""
                        WITH RECURSIVE chain AS (
                            SELECT id, parent_id, 1 AS depth FROM t_annotation WHERE id = ?
                            UNION ALL
                            SELECT a.id, a.parent_id, c.depth + 1
                              FROM t_annotation a JOIN chain c ON a.id = c.parent_id
                        )
                        SELECT COALESCE(MAX(depth), 0) FROM chain
                        """,
                Integer.class, id);
        return d == null ? 0 : d;
    }

    /** DELETE 响应的 deletedCount（含自身）。实际删除靠 FK 的 ON DELETE CASCADE。 */
    public int countDescendants(long id) {
        Integer n = jdbcTemplate.queryForObject("""
                        WITH RECURSIVE tree AS (
                            SELECT id, parent_id FROM t_annotation WHERE id = ?
                            UNION ALL
                            SELECT a.id, a.parent_id FROM t_annotation a JOIN tree t ON a.parent_id = t.id
                        )
                        SELECT COUNT(*) FROM tree
                        """,
                Integer.class, id);
        return n == null ? 0 : n;
    }

    /** 唯一可编辑字段。归属校验由 Service 先做。 */
    public int updateAnswer(long id, String answer) {
        return jdbcTemplate.update(
                "update t_annotation set answer = ?, edited = true where id = ?", answer, id);
    }

    public int deleteById(long id) {
        return jdbcTemplate.update("delete from t_annotation where id = ?", id);
    }
}
