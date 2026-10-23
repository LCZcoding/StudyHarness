package com.lcz.aiagentstudy.repository;

import com.lcz.aiagentstudy.model.KnowledgeItemRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class KnowledgeItemRepository {
    private final JdbcTemplate jdbcTemplate;

//    public UUID create(UUID userId, String title, String sourceUrl, String content, String summary) {
//        return jdbcTemplate.queryForObject("""
//                        insert into t_knowledge_item (user_id, title, source_url, content, summary)
//                        values (?, ?, ?, ?, ?) returning id
//                        """,
//                UUID.class, userId, title, sourceUrl, content, summary);
//    }

    // 创建章节
    public UUID createSection(UUID userId, String docTitle, int sectionNo, String title,
                              String sourceUrl, String content, String summary) {
        return jdbcTemplate.queryForObject("""
                            insert into t_knowledge_item
                                (user_id, doc_title, section_no, title, source_url, content, summary)
                            values (?, ?, ?, ?, ?, ?, ?) returning id
                            """,
                UUID.class, userId, docTitle, sectionNo, title, sourceUrl, content, summary);
    }

    /** 同名检查：同一用户下 doc_title 不能重复。历史行 doc_title 为空，用 title 兜底。 */
    public boolean existsDocTitle(UUID userId, String docTitle) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                        select exists(
                            select 1 from t_knowledge_item
                             where user_id = ? and coalesce(doc_title, title) = ?)
                        """,
                Boolean.class, userId, docTitle));
    }



//    // 列表不取 content：正文可能很长，列表只需要标题与摘要
//    public List<KnowledgeItemRow> findAllByUserId(UUID userId) {
//        return jdbcTemplate.query("""
//                        select id, title, source_url, summary, created_at
//                          from t_knowledge_item
//                         where user_id = ?
//                         order by created_at desc
//                        """,
//                (rs, rowNum) -> new KnowledgeItemRow(
//                        rs.getObject("id", UUID.class),
//                        rs.getString("title"),
//                        rs.getString("source_url"),
//                        null,
//                        rs.getString("summary"),
//                        rs.getObject("created_at", OffsetDateTime.class)),
//                userId);
//    }

    /** 资料页列表的一行：章节元数据 + 出题/作答计数，三态由 Service 推导。 */
    public record SectionRow(UUID id, String docTitle, int sectionNo, String title, String sourceUrl,
                             String summary, int chars, int quizCount, int answeredCount,
                             boolean allPassed, OffsetDateTime createdAt) {
    }

    /**
     * 列表不取 content。quizCount / answeredCount / allPassed 只统计 SELF_TEST 会话产生的题，
     * REVIEW 会话产生的题不参与章节三态（product-spec.md 10.5）。
     */
    public List<SectionRow> findAllByUserId(UUID userId) {
        return jdbcTemplate.query("""
                        with quiz as (
                            select q.id as question_id, q.knowledge_item_id
                              from t_question q
                              join t_message m      on m.id = q.message_id
                              join t_conversation c on c.id = m.conversation_id
                             where c.mode = 'SELF_TEST'
                        ),
                        latest as (
                            select distinct on (tr.question_id) tr.question_id, tr.verdict
                              from t_test_record tr
                             order by tr.question_id, tr.id desc
                        )
                        select ki.id,
                               coalesce(ki.doc_title, ki.title) as doc_title,
                               coalesce(ki.section_no, 1)       as section_no,
                               ki.title, ki.source_url, ki.summary, ki.created_at,
                               length(ki.content)    as chars,
                               count(qz.question_id) as quiz_count,
                               count(l.question_id)  as answered_count,
                               coalesce(bool_and(l.verdict = 'PASS'), false) as all_passed
                          from t_knowledge_item ki
                          left join quiz qz  on qz.knowledge_item_id = ki.id
                          left join latest l on l.question_id = qz.question_id
                         where ki.user_id = ?
                         group by ki.id
                         order by max(ki.created_at) over (partition by coalesce(ki.doc_title, ki.title)) desc,
                                  doc_title, section_no
                        """,
                (rs, rowNum) -> new SectionRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("doc_title"),
                        rs.getInt("section_no"),
                        rs.getString("title"),
                        rs.getString("source_url"),
                        rs.getString("summary"),
                        rs.getInt("chars"),
                        rs.getInt("quiz_count"),
                        rs.getInt("answered_count"),
                        rs.getBoolean("all_passed"),
                        rs.getObject("created_at", OffsetDateTime.class)),
                userId);
    }

    public Optional<KnowledgeItemRow> findById(UUID id) {
        List<KnowledgeItemRow> rows = jdbcTemplate.query("""
                        select id,
                               coalesce(doc_title, title) as doc_title,
                               coalesce(section_no, 1)    as section_no,
                               title, source_url, content, summary, created_at
                          from t_knowledge_item
                         where id = ?
                        """,
                (rs, rowNum) -> new KnowledgeItemRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("doc_title"),
                        rs.getInt("section_no"),
                        rs.getString("title"),
                        rs.getString("source_url"),
                        rs.getString("content"),
                        rs.getString("summary"),
                        rs.getObject("created_at", OffsetDateTime.class)),
                id);
        return rows.stream().findFirst();
    }

    // 归属校验用：只回答"是不是这个人的"，不泄露条目是否存在
    public boolean belongsTo(UUID itemId, UUID userId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "select exists(select 1 from t_knowledge_item where id = ? and user_id = ?)",
                Boolean.class, itemId, userId));
    }

//    // 交错复习用：从"另一份资料"里随机取一条；没有第二条资料时返回空
//    public Optional<UUID> findAnotherItemId(UUID userId, UUID excludeItemId) {
//        List<UUID> rows = jdbcTemplate.query("""
//                        select id from t_knowledge_item
//                         where user_id = ? and id <> ?
//                         order by random()
//                         limit 1
//                        """,
//                (rs, rowNum) -> rs.getObject("id", UUID.class),
//                userId, excludeItemId);
//        return rows.stream().findFirst();
//    }

    // 交错复习用：从"另一份文档"的章节里随机取一条；只有一份文档时返回空
    public Optional<UUID> findAnotherItemId(UUID userId, UUID excludeItemId) {
        List<UUID> rows = jdbcTemplate.query("""
                        select id from t_knowledge_item
                         where user_id = ?
                           and coalesce(doc_title, title) <> (
                               select coalesce(doc_title, title)
                                 from t_knowledge_item
                                where id = ?)
                         order by random()
                         limit 1
                        """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                userId, excludeItemId);
        return rows.stream().findFirst();
    }
}