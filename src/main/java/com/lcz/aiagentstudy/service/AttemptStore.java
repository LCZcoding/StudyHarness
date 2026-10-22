package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.exception.BadRequestException;
import com.lcz.aiagentstudy.repository.ConversationRepository;
import com.lcz.aiagentstudy.repository.KnowledgePointRepository;
import com.lcz.aiagentstudy.repository.QuestionRepository;
import com.lcz.aiagentstudy.repository.ReviewScheduleRepository;
import com.lcz.aiagentstudy.repository.TestRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 一次作答的写入口：成绩 + 排期 + 知识点 + 会话时间，四件事必须同生共死。
 * 单独一个 bean，理由同 QuizStore：判分要调模型（慢），不能让事务包住模型调用。
 */
@Service
@RequiredArgsConstructor
public class AttemptStore {
    private final TestRecordRepository testRecordRepository;
    private final ReviewScheduleRepository reviewScheduleRepository;
    private final KnowledgePointRepository knowledgePointRepository;
    private final ConversationRepository conversationRepository;

    public record AttemptResult(String verdict, String feedback, List<String> gaps,
                                boolean retryAllowed, int stage, OffsetDateTime nextDueAt,
                                int attemptNo) {
    }

    private record Plan(int stage, int days, boolean done) {
    }

    @Transactional
    public AttemptResult record(UUID userId, QuestionRepository.QuestionContext question,
                                String answer, GradingService.Grading grading) {
        int attemptNo = testRecordRepository.countByQuestion(question.questionId()) + 1;
        // 补讲只有一次：第 3 次提交直接拒绝，避免同一道题被无限重答
        if (attemptNo > 2) {
            throw new BadRequestException("这道题已经补讲过了，等下次复习再讲");
        }
        testRecordRepository.insert(userId, question.questionId(), answer, grading.feedback(),
                grading.verdict(), attemptNo);

        // 讲过一遍并且不是完全跑偏，知识点就算"用过"。knowledge_point_id 允许为空（历史数据）
        if (!"FAIL".equals(grading.verdict()) && question.knowledgePointId() != null) {
            knowledgePointRepository.markVerified(question.knowledgePointId());
        }

        Optional<ReviewScheduleRepository.ScheduleRow> current =
                reviewScheduleRepository.findByQuestion(question.questionId());
        Plan plan = planFor(grading.verdict(), current);

        OffsetDateTime nextDueAt = null;
        if (plan.done()) {
            reviewScheduleRepository.markDoneByQuestion(question.questionId());
        } else {
            nextDueAt = reviewScheduleRepository.upsert(
                    userId, question.questionId(), plan.stage(), plan.days());
        }

        conversationRepository.touch(question.conversationId());

        boolean retryAllowed = !"PASS".equals(grading.verdict()) && attemptNo == 1;
        return new AttemptResult(grading.verdict(), grading.feedback(), grading.gaps(),
                retryAllowed, plan.stage(), nextDueAt, attemptNo);
    }

    /**
     * 排期状态机。只看"当前这道题自己的排期"，不跨题推断。
     * FAIL / PARTIAL 一律重置到 stage 1：答崩了还继续拉长间隔，等于假装学会了。
     */
    private Plan planFor(String verdict, Optional<ReviewScheduleRepository.ScheduleRow> current) {
        if (!"PASS".equals(verdict)) {
            return new Plan(1, "PARTIAL".equals(verdict) ? 3 : 1, false);
        }
        if (current.isEmpty() || "DONE".equals(current.get().status())) {
            return new Plan(1, 1, false);
        }
        return switch (current.get().stage()) {
            case 1 -> new Plan(3, 3, false);
            case 3 -> new Plan(7, 7, false);
            default -> new Plan(7, 0, true);
        };
    }
}