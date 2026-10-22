package com.lcz.aiagentstudy.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ReviewScheduleRepository {
    private final JdbcTemplate jdbcTemplate;

    public record ScheduleRow(UUID id, long questionId, int stage, String status) {
    }

    public record DueItem(long questionId, UUID knowledgeItemId, String title,
                          String kind, String target, UUID knowledgePointId, String text, int stage) {
    }

    public Optional<ScheduleRow> findByQuestion(long questionId) {
        return jdbcTemplate.query("""
                        select id, question_id, stage, status
                          from t_review_schedule
                         where question_id = ?
                        """,
                (rs, rowNum) -> new ScheduleRow(
                        rs.getObject("id", UUID.class),
                        rs.getLong("question_id"),
                        rs.getInt("stage"),
                        rs.getString("status")),
                questionId).stream().findFirst();
    }

    /**
     * 重问 / 复习：排期行跟着题走，**保持 PENDING、due_at 不动**。
     * 为什么不在启动复习时就标 DONE：DONE 会把 stage 一起作废，
     * 用户答对后就没法从 1 → 3 递进了。保持 PENDING 还有个好处：
     * 用户中途放弃，这题仍然算"到期未复习"，待办不会假装已经复习过。
     */
    public void moveQuestion(long fromQuestionId, long toQuestionId) {
        jdbcTemplate.update(
                "update t_review_schedule set question_id = ? where question_id = ?",
                toQuestionId, fromQuestionId);
    }

    /**
     * 一题一行排期，重复作答原地改期（ON CONFLICT (question_id)）。
     * 不用"先查再插"：并发下两个请求会同时查到"没有"，然后一条撞唯一约束。
     */
    public OffsetDateTime upsert(UUID userId, long questionId, int stage, int days) {
        return jdbcTemplate.queryForObject("""
                        insert into t_review_schedule (user_id, question_id, stage, due_at)
                        values (?, ?, ?, now() + make_interval(days => ?))
                        on conflict (question_id) do update
                           set stage = excluded.stage,
                               due_at = excluded.due_at,
                               status = 'PENDING',
                               done_at = null
                        returning due_at
                        """,
                OffsetDateTime.class, userId, questionId, stage, days);
    }

    /** 1 -> 3 -> 7 走完后再 PASS：不再排期。 */
    public void markDoneByQuestion(long questionId) {
        jdbcTemplate.update("""
                        update t_review_schedule
                           set status = 'DONE', done_at = now()
                         where question_id = ? and status = 'PENDING'
                        """,
                questionId);
    }

    public List<DueItem> findDue(UUID userId, int limit) {
        return jdbcTemplate.query("""
                        select rs.question_id, rs.stage,
                               q.knowledge_item_id, q.knowledge_point_id, q.kind, q.target,
                               ki.title, m.content as text
                          from t_review_schedule rs
                          join t_question q      on q.id = rs.question_id
                          join t_message m       on m.id = q.message_id
                          join t_knowledge_item ki on ki.id = q.knowledge_item_id
                         where rs.user_id = ? and rs.status = 'PENDING' and rs.due_at <= now()
                         order by q.help_used desc, rs.due_at
                         limit ?
                        """,
                (rs, rowNum) -> new DueItem(
                        rs.getLong("question_id"),
                        rs.getObject("knowledge_item_id", UUID.class),
                        rs.getString("title"),
                        rs.getString("kind"),
                        rs.getString("target"),
                        rs.getObject("knowledge_point_id", UUID.class),
                        rs.getString("text"),
                        rs.getInt("stage")),
                userId, limit);
    }

    public int countDue(UUID userId) {
        Integer n = jdbcTemplate.queryForObject("""
                        select count(*) from t_review_schedule
                         where user_id = ? and status = 'PENDING' and due_at <= now()
                        """,
                Integer.class, userId);
        return n == null ? 0 : n;
    }
}