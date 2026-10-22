package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.model.ConversationMode;
import com.lcz.aiagentstudy.model.KnowledgeItemRow;
import com.lcz.aiagentstudy.model.MessageRole;
import com.lcz.aiagentstudy.model.MessageType;
import com.lcz.aiagentstudy.repository.ConversationRepository;
import com.lcz.aiagentstudy.repository.KnowledgePointRepository;
import com.lcz.aiagentstudy.repository.MessageRepository;
import com.lcz.aiagentstudy.repository.QuestionRepository;
import com.lcz.aiagentstudy.repository.ReviewScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 出题 / 复习的写入口。单独一个 bean：出题要先调模型（慢、会失败），再写库；
 * 把 @Transactional 和调用方放同一个类里，自调用绕过代理，注解会静默失效。
 */
@Service
@RequiredArgsConstructor
public class QuizStore {
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final QuestionRepository questionRepository;
    private final KnowledgePointRepository knowledgePointRepository;
    private final ReviewScheduleRepository reviewScheduleRepository;

    public record CreatedQuestion(long id, String kind, String target, String text) {
    }

    public record CreatedQuiz(UUID conversationId, List<CreatedQuestion> questions) {
    }

    @Transactional
    public CreatedQuiz createSelfTest(UUID userId, KnowledgeItemRow item,
                                      QuestionRepository.RetryQuestion retry,
                                      List<QuizService.TaskDraft> newTasks) {
        UUID conversationId = conversationRepository.create(userId, ConversationMode.SELF_TEST.name());
        // 一条 USER 消息：让左侧会话列表有标题可派生
        messageRepository.insert(conversationId, MessageRole.USER.name(), MessageType.CHAT.name(),
                "请考考我：" + item.title());

        List<CreatedQuestion> created = new ArrayList<>();
        if (retry != null) {
            created.add(appendExistingQuestion(conversationId, retry));
        }
        for (QuizService.TaskDraft task : newTasks) {
            created.add(appendNewQuestion(conversationId, userId, item.id(), task));
        }
        conversationRepository.touch(conversationId);
        return new CreatedQuiz(conversationId, created);
    }

    @Transactional
    public CreatedQuiz createReview(UUID userId, List<ReviewScheduleRepository.DueItem> due,
                                    UUID interleavedItemId, QuizService.TaskDraft interleaved) {
        UUID conversationId = conversationRepository.create(userId, ConversationMode.REVIEW.name());
        messageRepository.insert(conversationId, MessageRole.USER.name(), MessageType.CHAT.name(), "开始复习");

        List<CreatedQuestion> created = new ArrayList<>();
        for (ReviewScheduleRepository.DueItem item : due) {
            // 原题重问：复制成新消息 + 新题，排期行迁到新题（stage 保留，答完才重排）
            long messageId = messageRepository.insertReturningId(conversationId,
                    MessageRole.ASSISTANT.name(), MessageType.QUESTION.name(), item.text());
            long questionId = questionRepository.insert(messageId, item.knowledgeItemId(),
                    item.knowledgePointId(), item.kind(), item.target());
            reviewScheduleRepository.moveQuestion(item.questionId(), questionId);
            created.add(new CreatedQuestion(questionId, item.kind(), item.target(), item.text()));
        }
        if (interleaved != null) {
            created.add(appendNewQuestion(conversationId, userId, interleavedItemId, interleaved));
        }
        conversationRepository.touch(conversationId);
        return new CreatedQuiz(conversationId, created);
    }

    private CreatedQuestion appendExistingQuestion(UUID conversationId,
                                                   QuestionRepository.RetryQuestion retry) {
        long messageId = messageRepository.insertReturningId(conversationId,
                MessageRole.ASSISTANT.name(), MessageType.QUESTION.name(), retry.text());
        long questionId = questionRepository.insert(messageId, retry.knowledgeItemId(),
                retry.knowledgePointId(), retry.kind(), retry.target());
        // 排期跟着题走：旧题的 PENDING 排期迁到新题，到期时间不变
        reviewScheduleRepository.moveQuestion(retry.questionId(), questionId);
        return new CreatedQuestion(questionId, retry.kind(), retry.target(), retry.text());
    }

    private CreatedQuestion appendNewQuestion(UUID conversationId, UUID userId,
                                              UUID knowledgeItemId, QuizService.TaskDraft task) {
        UUID knowledgePointId = knowledgePointRepository.upsertCandidate(userId, task.target());
        long messageId = messageRepository.insertReturningId(conversationId,
                MessageRole.ASSISTANT.name(), MessageType.QUESTION.name(), task.text());
        long questionId = questionRepository.insert(messageId, knowledgeItemId,
                knowledgePointId, task.kind(), task.target());
        return new CreatedQuestion(questionId, task.kind(), task.target(), task.text());
    }
}