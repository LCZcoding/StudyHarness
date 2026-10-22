package com.lcz.aiagentstudy.controller;

import com.lcz.aiagentstudy.service.AttemptStore;
import com.lcz.aiagentstudy.service.QuizService;
import com.lcz.aiagentstudy.service.QuizStore;
import com.lcz.aiagentstudy.web.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
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


    public record AttemptRequest(
            @NotBlank(message = "answer 不能为空")
            @Size(max = 5000, message = "作答最多 5000 字") String answer) {
    }

    public record AttemptResponse(String verdict, String feedback, List<String> gaps,
                                  boolean retryAllowed, int stage,
                                  OffsetDateTime nextDueAt, int attemptNo) {
    }

    public record HelpRequest(
            @NotBlank(message = "question 不能为空")
            @Size(max = 500, message = "问题最多 500 字") String question) {
    }

    public record HelpResponse(String answer) {
    }
    // 用户回答
    @PostMapping("/{questionId}/attempt")
    public AttemptResponse attempt(@PathVariable long questionId,
                                   @Valid @RequestBody AttemptRequest req,
                                   HttpServletRequest request) {
        UUID userId = currentUserResolver.requireUserId(request);
        AttemptStore.AttemptResult r = quizService.attempt(userId, questionId, req.answer());
        return new AttemptResponse(r.verdict(), r.feedback(), r.gaps(),
                r.retryAllowed(), r.stage(), r.nextDueAt(), r.attemptNo());
    }
    // 用户求助
    @PostMapping("/{questionId}/help")
    public HelpResponse help(@PathVariable long questionId,
                             @Valid @RequestBody HelpRequest req,
                             HttpServletRequest request) {
        UUID userId = currentUserResolver.requireUserId(request);
        return new HelpResponse(quizService.help(userId, questionId, req.question()));
    }
}