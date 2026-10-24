package com.lcz.aiagentstudy.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class HelpTurnRepository {
    private final JdbcTemplate jdbcTemplate;

    /** role 只会是 USER 或 ASSISTANT，与 t_help_turn.role 的 CHECK 约束逐字对应。 */
    public record HelpTurn(String role, String content) {
    }

    /** 一轮问答两行，必须同时成功：只写进一半，回放时会出现「有问没答」的断档。 */
    @Transactional
    public void saveTurn(long questionId, String question, String answer) {
        jdbcTemplate.update(
                "insert into t_help_turn (question_id, role, content) values (?, 'USER', ?)",
                questionId, question);
        jdbcTemplate.update(
                "insert into t_help_turn (question_id, role, content) values (?, 'ASSISTANT', ?)",
                questionId, answer);
    }

    /** 按题目回放全部求助：刷新页面、重开抽屉都要靠它，不能只存内存。 */
    public List<HelpTurn> findByQuestion(long questionId) {
        return jdbcTemplate.query(
                "select role, content from t_help_turn where question_id = ? order by id",
                (rs, rowNum) -> new HelpTurn(rs.getString("role"), rs.getString("content")),
                questionId);
    }
}
