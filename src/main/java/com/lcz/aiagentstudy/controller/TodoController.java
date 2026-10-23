package com.lcz.aiagentstudy.controller;

import com.lcz.aiagentstudy.model.QuizResponse;
import com.lcz.aiagentstudy.service.QuizService;
import com.lcz.aiagentstudy.service.QuizStore;
import com.lcz.aiagentstudy.service.TodoService;
import com.lcz.aiagentstudy.web.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class TodoController {
    private final CurrentUserResolver currentUserResolver;
    private final TodoService todoService;
    private final QuizService quizService;

    public record TodoItemView(String type, UUID knowledgeItemId, UUID conversationId,
                               String title, int count) {
    }

    public record TodoResponse(List<TodoItemView> items) {
    }

    @GetMapping("/todos")
    public TodoResponse todos(HttpServletRequest request) {
        UUID userId = currentUserResolver.requireUserId(request);
        List<TodoItemView> views = new ArrayList<>();
        for (TodoService.TodoItem item : todoService.list(userId)) {
            views.add(new TodoItemView(item.type(), item.knowledgeItemId(),
                    item.conversationId(), item.title(), item.count()));
        }
        return new TodoResponse(views);
    }

    @PostMapping("/review/start")
    public QuizResponse startReview(HttpServletRequest request) {
        UUID userId = currentUserResolver.requireUserId(request);
        QuizStore.CreatedQuiz quiz = quizService.startReview(userId);
        return QuizResponse.of(quiz);
    }
}