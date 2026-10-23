package com.lcz.aiagentstudy.controller;

import com.lcz.aiagentstudy.service.ProgressService;
import com.lcz.aiagentstudy.web.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ProgressController {
    private final CurrentUserResolver currentUserResolver;
    private final ProgressService progressService;

    public record WeekView(String weekStart, int attempts) {
    }

    public record ProgressResponse(int masteredItems, int totalItems, int streakDays,
                                   int dueCount, List<WeekView> weeks) {
    }

    @GetMapping("/progress")
    public ProgressResponse progress(HttpServletRequest request) {
        UUID userId = currentUserResolver.requireUserId(request);
        ProgressService.ProgressView p = progressService.load(userId);
        return new ProgressResponse(p.masteredItems(), p.totalItems(), p.streakDays(),
                p.dueCount(),
                p.weeks().stream()
                        .map(w -> new WeekView(w.weekStart().toString(), w.attempts()))
                        .toList());
    }
}