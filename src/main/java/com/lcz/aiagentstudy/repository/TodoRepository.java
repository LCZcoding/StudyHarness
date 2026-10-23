package com.lcz.aiagentstudy.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class TodoRepository {
    private final JdbcTemplate jdbcTemplate;

    public record RetryRow(UUID knowledgeItemId, String title, int count) {
    }

    public record UntestedRow(UUID knowledgeItemId, UUID conversationId, String title, int count) {
    }

    /** 上次没讲清、还没补讲、且复习未到期（到期后归 REVIEW，不重复出现）。 */
    public List<RetryRow> findRetry(UUID userId) {
        return jdbcTemplate.query("""
                        with latest as (
                            select distinct on (tr.question_id) tr.question_id, tr.verdict, tr.attempt_no
                              from t_test_record tr
                             where tr.user_id = ?
                             order by tr.question_id, tr.id desc
                        )
                        select q.knowledge_item_id, ki.title, count(*) as pending
                          from latest l
                          join t_question q          on q.id = l.question_id
                          join t_review_schedule rs  on rs.question_id = q.id
                          join t_knowledge_item ki   on ki.id = q.knowledge_item_id
                         where l.verdict <> 'PASS' and l.attempt_no = 1
                           and rs.status = 'PENDING' and rs.due_at > now()
                         group by q.knowledge_item_id, ki.title
                         order by max(q.id) desc
                        """,
                (rs, rowNum) -> new RetryRow(
                        rs.getObject("knowledge_item_id", UUID.class),
                        rs.getString("title"),
                        rs.getInt("pending")),
                userId);
    }

    /** 出了题一次都没作答的。按「会话 + 资料」聚合，点进去继续那一次测验。 */
    public List<UntestedRow> findUntested(UUID userId) {
        return jdbcTemplate.query("""
                        select q.knowledge_item_id,
                               m.conversation_id,
                               ki.title,
                               count(*) as pending
                          from t_question q
                          join t_message m        on m.id = q.message_id
                          join t_knowledge_item ki on ki.id = q.knowledge_item_id
                         where ki.user_id = ?
                           and not exists (select 1 from t_test_record tr where tr.question_id = q.id)
                         group by q.knowledge_item_id, m.conversation_id, ki.title
                         order by max(m.id) desc
                        """,
                (rs, rowNum) -> new UntestedRow(
                        rs.getObject("knowledge_item_id", UUID.class),
                        rs.getObject("conversation_id", UUID.class),
                        rs.getString("title"),
                        rs.getInt("pending")),
                userId);
    }
}