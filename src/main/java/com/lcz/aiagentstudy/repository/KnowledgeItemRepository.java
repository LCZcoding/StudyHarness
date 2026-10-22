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

    public UUID create(UUID userId, String title, String sourceUrl, String content, String summary) {
        return jdbcTemplate.queryForObject("""
                        insert into t_knowledge_item (user_id, title, source_url, content, summary)
                        values (?, ?, ?, ?, ?) returning id
                        """,
                UUID.class, userId, title, sourceUrl, content, summary);
    }

    // 列表不取 content：正文可能很长，列表只需要标题与摘要
    public List<KnowledgeItemRow> findAllByUserId(UUID userId) {
        return jdbcTemplate.query("""
                        select id, title, source_url, summary, created_at
                          from t_knowledge_item
                         where user_id = ?
                         order by created_at desc
                        """,
                (rs, rowNum) -> new KnowledgeItemRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("title"),
                        rs.getString("source_url"),
                        null,
                        rs.getString("summary"),
                        rs.getObject("created_at", OffsetDateTime.class)),
                userId);
    }

    public Optional<KnowledgeItemRow> findById(UUID id) {
        List<KnowledgeItemRow> rows = jdbcTemplate.query("""
                        select id, title, source_url, content, summary, created_at
                          from t_knowledge_item
                         where id = ?
                        """,
                (rs, rowNum) -> new KnowledgeItemRow(
                        rs.getObject("id", UUID.class),
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

    // 交错复习用：从"另一份资料"里随机取一条；没有第二条资料时返回空
    public Optional<UUID> findAnotherItemId(UUID userId, UUID excludeItemId) {
        List<UUID> rows = jdbcTemplate.query("""
                        select id from t_knowledge_item
                         where user_id = ? and id <> ?
                         order by random()
                         limit 1
                        """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                userId, excludeItemId);
        return rows.stream().findFirst();
    }
}