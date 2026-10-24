package com.lcz.aiagentstudy.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class QuestionRepository {
    private final JdbcTemplate jdbcTemplate;

    /** 待重问的旧题 + 它自带的归属信息。 */
    public record RetryQuestion(long questionId, UUID knowledgeItemId, UUID knowledgePointId,
                                String kind, String target, String text) {
    }

    /** 判分时反查：题目 + 会话 + 会话所属用户 + 资料/知识点 + 材料正文。一条查询拿到判分所需的一切。 */
    public record QuestionContext(long questionId, String text, String kind, String target,
                                  UUID conversationId, UUID userId, UUID knowledgeItemId,
                                  UUID knowledgePointId, String material) {
    }

    public long insert(long messageId, UUID knowledgeItemId, UUID knowledgePointId,
                       String kind, String target) {
        return jdbcTemplate.queryForObject("""
                        insert into t_question (message_id, knowledge_item_id, knowledge_point_id, kind, target)
                        values (?, ?, ?, ?, ?) returning id
                        """,
                Long.class, messageId, knowledgeItemId, knowledgePointId, kind, target);
    }

    /** 用户点了「我卡住了」。之后复习时这题排前面。 */
    public void markHelpUsed(long questionId) {
        jdbcTemplate.update("update t_question set help_used = true where id = ?", questionId);
    }

    /** 用户跳过这道题：不算作答、不产生成绩，只是这一轮不再等它。 */
    public void markSkipped(long questionId) {
        jdbcTemplate.update("update t_question set skipped = true where id = ?", questionId);
    }

    public boolean isSkipped(long questionId) {
        Boolean skipped = jdbcTemplate.queryForObject(
                "select skipped from t_question where id = ?", Boolean.class, questionId);
        return Boolean.TRUE.equals(skipped);
    }

    public boolean hasRecord(long questionId) {
        Boolean exists = jdbcTemplate.queryForObject(
                "select exists (select 1 from t_test_record where question_id = ?)",
                Boolean.class, questionId);
        return Boolean.TRUE.equals(exists);
    }

    public List<QuestionContext> findWithOwner(long questionId) {
        return jdbcTemplate.query("""
                        select q.id            as question_id,
                               m.content       as question_text,
                               q.kind, q.target,
                               m.conversation_id,
                               c.user_id,
                               q.knowledge_item_id,
                               q.knowledge_point_id,
                               ki.content      as material
                          from t_question q
                          join t_message m       on m.id = q.message_id
                          join t_conversation c  on c.id = m.conversation_id
                          join t_knowledge_item ki on ki.id = q.knowledge_item_id
                         where q.id = ?
                        """,
                (rs, rowNum) -> new QuestionContext(
                        rs.getLong("question_id"),
                        rs.getString("question_text"),
                        rs.getString("kind"),
                        rs.getString("target"),
                        rs.getObject("conversation_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("knowledge_item_id", UUID.class),
                        rs.getObject("knowledge_point_id", UUID.class),
                        rs.getString("material")),
                questionId);
    }

    /**
     * 「上次答得不完整、还没补讲、复习也还没到期」的那道题。
     * 条件与待办里的 RETRY 完全一致，避免两处口径漂移。
     */
    public Optional<RetryQuestion> findRetryQuestion(UUID userId, UUID knowledgeItemId) {
        List<RetryQuestion> rows = jdbcTemplate.query("""
                        with latest as (
                            select distinct on (tr.question_id)
                                   tr.question_id, tr.verdict, tr.attempt_no
                              from t_test_record tr
                              join t_question q on q.id = tr.question_id
                             where tr.user_id = ? and q.knowledge_item_id = ?
                             order by tr.question_id, tr.id desc
                        )
                        select q.id, q.knowledge_item_id, q.knowledge_point_id,
                               q.kind, q.target, m.content as text
                          from latest l
                          join t_question q       on q.id = l.question_id
                          join t_message m        on m.id = q.message_id
                          join t_review_schedule rs on rs.question_id = q.id
                         where l.verdict <> 'PASS' and l.attempt_no = 1
                           and rs.status = 'PENDING' and rs.due_at > now()
                         order by q.id desc
                         limit 1
                        """,
                (rs, rowNum) -> new RetryQuestion(
                        rs.getLong("id"),
                        rs.getObject("knowledge_item_id", UUID.class),
                        rs.getObject("knowledge_point_id", UUID.class),
                        rs.getString("kind"),
                        rs.getString("target"),
                        rs.getString("text")),
                userId, knowledgeItemId);
        return rows.stream().findFirst();
    }

    public record QuestionRow(long id, String kind, String target, String text, String verdict) {
    }

    /** 测验页刷新恢复：每题带最近一次结论（null = 还没作答）。(按会话查题) */
    public List<QuestionRow> findByConversation(UUID conversationId) {
        return jdbcTemplate.query("""
                        select q.id, q.kind, q.target, m.content as text,
                               case when q.skipped then 'SKIPPED'
                                    else (select tr.verdict from t_test_record tr
                                           where tr.question_id = q.id
                                           order by tr.id desc limit 1) end as verdict
                          from t_question q
                          join t_message m on m.id = q.message_id
                         where m.conversation_id = ?
                         order by q.id
                        """,
                (rs, rowNum) -> new QuestionRow(
                        rs.getLong("id"),
                        rs.getString("kind"),
                        rs.getString("target"),
                        rs.getString("text"),
                        rs.getString("verdict")),
                conversationId);
    }

    public List<QuestionRow> findUnanswered(UUID conversationId) {
        return jdbcTemplate.query("""
                    select q.id, q.kind, q.target, m.content as text, null::text as verdict
                      from t_question q
                      join t_message m on m.id = q.message_id
                     where m.conversation_id = ?
                       and q.skipped = false
                       and not exists (select 1 from t_test_record tr where tr.question_id = q.id)
                     order by q.id
                    """,
                (rs, rowNum) -> new QuestionRow(
                        rs.getLong("id"),
                        rs.getString("kind"),
                        rs.getString("target"),
                        rs.getString("text"),
                        rs.getString("verdict")),
                conversationId);
    }
}