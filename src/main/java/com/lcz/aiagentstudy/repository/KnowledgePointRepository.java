package com.lcz.aiagentstudy.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class KnowledgePointRepository {
    private final JdbcTemplate jdbcTemplate;

    /**
     * 出题时把 target 写成知识点。冲突时只把 name 赋回自身，
     * 目的是"拿到已有 id"且**不把 VERIFIED 降级回 CANDIDATE**（DO NOTHING 不会返回 id）。
     */
    public UUID upsertCandidate(UUID userId, String name) {
        return jdbcTemplate.queryForObject("""
                        insert into t_knowledge_point (user_id, name, status)
                        values (?, ?, 'CANDIDATE')
                        on conflict (user_id, name) do update set name = excluded.name
                        returning id
                        """,
                UUID.class, userId, name);
    }

    // 判分 PASS / PARTIAL 时调用；已经是 VERIFIED 就不再动
    public void markVerified(UUID knowledgePointId) {
        jdbcTemplate.update(
                "update t_knowledge_point set status = 'VERIFIED' where id = ? and status = 'CANDIDATE'",
                knowledgePointId);
    }

    // 出题时注入"已掌握的概念"，让模型要求用户做类比
    public List<String> findRecentVerified(UUID userId, int limit) {
        return jdbcTemplate.query("""
                        select name from t_knowledge_point
                         where user_id = ? and status = 'VERIFIED'
                         order by created_at desc
                         limit ?
                        """,
                (rs, rowNum) -> rs.getString("name"),
                userId, limit);
    }
}