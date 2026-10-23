package com.lcz.aiagentstudy.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ProgressRepository {
    private final JdbcTemplate jdbcTemplate;

    public record WeekStat(LocalDate weekStart, int attempts) {
    }

    public int countItems(UUID userId) {
        Integer n = jdbcTemplate.queryForObject(
                "select count(*) from t_knowledge_item where user_id = ?", Integer.class, userId);
        return n == null ? 0 : n;
    }

    /**
     * 已掌握章节 = 该章节由主动测验（SELF_TEST）出的题"全部答过且最近一次都是 PASS"。
     * 两道口径都不能省：
     * - 只要有一道没答，就不是已掌握，否则资料页说「已通过」、待办说「还没测过」，两边打架；
     * - REVIEW 会话复制出的题不参与，否则到期复习抽中已通过的章节，进度会周期性下降。
     */
    public int countMasteredItems(UUID userId) {
        Integer n = jdbcTemplate.queryForObject("""
                        with latest as (
                            select distinct on (tr.question_id) tr.question_id, tr.verdict
                              from t_test_record tr
                             where tr.user_id = ?
                             order by tr.question_id, tr.id desc
                        ),
                        quiz as (
                            select q.id as question_id, q.knowledge_item_id
                              from t_question q
                              join t_message m      on m.id = q.message_id
                              join t_conversation c on c.id = m.conversation_id
                             where c.mode = 'SELF_TEST'
                        ),
                        per_item as (
                            select qz.knowledge_item_id,
                                   count(*) as total,
                                   count(*) filter (where l.verdict = 'PASS') as passed
                              from quiz qz
                              join t_knowledge_item ki on ki.id = qz.knowledge_item_id
                              left join latest l on l.question_id = qz.question_id
                             where ki.user_id = ?
                             group by qz.knowledge_item_id
                        )
                        select count(*) from per_item where total > 0 and total = passed
                        """,
                Integer.class, userId, userId);
        return n == null ? 0 : n;
    }

    /** 有作答记录的日期（按东八区切日）。只取一年，连续天数不需要更久的历史。 */
    public List<LocalDate> findActiveDates(UUID userId) {
        return jdbcTemplate.query("""
                        select distinct (created_at at time zone 'Asia/Shanghai')::date as d
                          from t_test_record
                         where user_id = ? and created_at >= now() - interval '365 days'
                         order by d
                        """,
                (rs, rowNum) -> rs.getObject("d", LocalDate.class),
                userId);
    }

    /** 近 8 周的作答次数，按周一为周首（与 Java 的 DayOfWeek.MONDAY 对齐）。 */
    public List<WeekStat> findWeeklyAttempts(UUID userId) {
        return jdbcTemplate.query("""
                        select date_trunc('week', created_at at time zone 'Asia/Shanghai')::date as week_start,
                               count(*) as attempts
                          from t_test_record
                         where user_id = ? and created_at >= now() - interval '8 weeks'
                         group by week_start
                         order by week_start
                        """,
                (rs, rowNum) -> new WeekStat(
                        rs.getObject("week_start", LocalDate.class),
                        rs.getInt("attempts")),
                userId);
    }
}