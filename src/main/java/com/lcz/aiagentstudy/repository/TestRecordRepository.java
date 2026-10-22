package com.lcz.aiagentstudy.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class TestRecordRepository {
    private final JdbcTemplate jdbcTemplate;

    // attemptNo 由此推导：1 是首答，2 是补讲。不额外存"作答次数"字段，避免两处不一致
    public int countByQuestion(long questionId) {
        Integer n = jdbcTemplate.queryForObject(
                "select count(*) from t_test_record where question_id = ?",
                Integer.class, questionId);
        return n == null ? 0 : n;
    }

    public void insert(UUID userId, long questionId, String answer, String feedback,
                       String verdict, int attemptNo) {
        jdbcTemplate.update("""
                        insert into t_test_record
                            (user_id, question_id, answer, feedback, verdict, attempt_no)
                        values (?, ?, ?, ?, ?, ?)
                        """,
                userId, questionId, answer, feedback, verdict, attemptNo);
    }
}