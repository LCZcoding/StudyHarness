package com.lcz.aiagentstudy.model;

import com.lcz.aiagentstudy.service.QuizStore;

import java.util.List;
import java.util.UUID;

public record QuizResponse(UUID conversationId, List<QuestionView> questions) {
    public record QuestionView(long questionId, String kind, String target, String text) {
    }
    public static QuizResponse of(QuizStore.CreatedQuiz quiz) {
        List<QuestionView> views = quiz.questions().stream()
                .map(q -> new QuestionView(q.id(), q.kind(), q.target(), q.text()))
                .toList();
        return new QuizResponse(quiz.conversationId(), views);
    }
}
