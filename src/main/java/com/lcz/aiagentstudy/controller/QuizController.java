package com.lcz.aiagentstudy.controller;

import com.lcz.aiagentstudy.service.QuizService;
import com.lcz.aiagentstudy.service.QuizStore;
import com.lcz.aiagentstudy.web.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/quiz")
@RequiredArgsConstructor
public class QuizController {
    private final CurrentUserResolver currentUserResolver;
    private final QuizService quizService;

    public record GenerateRequest(@NotNull(message = "knowledgeItemId 不能为空") UUID knowledgeItemId) {
    }

    public record QuestionView(long questionId, String kind, String target, String text) {
    }

    public record GenerateResponse(UUID conversationId, List<QuestionView> questions) {
    }

    @PostMapping("/generate")
    public GenerateResponse generate(@Valid @RequestBody GenerateRequest req,
                                     HttpServletRequest request) {
        UUID userId = currentUserResolver.requireUserId(request);
        return toResponse(quizService.generate(userId, req.knowledgeItemId()));
    }

    /** 复习（`TodoController`）也用这个响应形状，抽出来避免同一个响应在两处拼。 */
    static GenerateResponse toResponse(QuizStore.CreatedQuiz quiz) {

        List<QuestionView> views = quiz.questions().stream()
            .map(q -> new QuestionView(q.id(), q.kind(), q.target(), q.text()))
            .toList();
        return new GenerateResponse(quiz.conversationId(), views);
    }
}