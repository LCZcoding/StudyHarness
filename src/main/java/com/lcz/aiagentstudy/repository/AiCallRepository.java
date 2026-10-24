package com.lcz.aiagentstudy.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class AiCallRepository {
    private final JdbcTemplate jdbcTemplate;

    public record SceneMetrics(String scene,
                               long calls,
                               int avgMs,
                               int p95Ms,
                               Integer p95FirstTokenMs,
                               long promptTokens,
                               long completionTokens,
                               long failedCalls,
                               long callsWithoutUsage) {
    }

    public void insert(UUID userId, String scene, int latencyMs, Integer firstTokenMs,
                       Integer promptTokens, Integer completionTokens,
                       boolean success, String errorCode) {
        jdbcTemplate.update("""
                        insert into t_ai_call (user_id, scene, latency_ms, first_token_ms,
                                               prompt_tokens, completion_tokens, success, error_code)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                userId, scene, latencyMs, firstTokenMs,
                promptTokens, completionTokens, success, errorCode);
    }

    /**
     * 近 days 天、按场景聚合。percentile_cont 自动忽略 NULL：
     * 流式行没有 usage 也能参与延迟统计，不会被 token 缺失拖累。
     */
    public List<SceneMetrics> summary(UUID userId, int days) {
        return jdbcTemplate.query("""
                        select scene,
                               count(*)                                                              as calls,
                               coalesce(avg(latency_ms), 0)::int                                     as avg_ms,
                               coalesce(percentile_cont(0.95) within group (order by latency_ms), 0)::int as p95_ms,
                               percentile_cont(0.95) within group (order by first_token_ms)::int     as p95_first_token_ms,
                               coalesce(sum(prompt_tokens), 0)                                       as prompt_tokens,
                               coalesce(sum(completion_tokens), 0)                                   as completion_tokens,
                               count(*) filter (where not success)                                   as failed_calls,
                               count(*) filter (where prompt_tokens is null)                         as calls_without_usage
                          from t_ai_call
                         where user_id = ?
                           and created_at >= now() - make_interval(days => ?)
                         group by scene
                         order by calls desc
                        """,
                (rs, rowNum) -> {
                    Number p95First = (Number) rs.getObject("p95_first_token_ms");
                    return new SceneMetrics(
                            rs.getString("scene"),
                            rs.getLong("calls"),
                            rs.getInt("avg_ms"),
                            rs.getInt("p95_ms"),
                            p95First == null ? null : p95First.intValue(),
                            rs.getLong("prompt_tokens"),
                            rs.getLong("completion_tokens"),
                            rs.getLong("failed_calls"),
                            rs.getLong("calls_without_usage"));
                },
                userId, days);
    }
}