package com.lcz.aiagentstudy.controller;

import com.lcz.aiagentstudy.service.PracticeService;
import com.lcz.aiagentstudy.service.PracticeStore;
import com.lcz.aiagentstudy.web.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/practice")
@RequiredArgsConstructor
public class PracticeController {
    private final CurrentUserResolver currentUserResolver;
    private final PracticeService practiceService;

    public record GuideRequest(
            @NotNull(message = "knowledgeItemId 不能为空") UUID knowledgeItemId,
            @Size(max = 500, message = "目标最多 500 字") String goal) {
    }

    public record GuideResponse(UUID conversationId, String content) {
    }

    @PostMapping("/guide")
    public GuideResponse guide(@Valid @RequestBody GuideRequest req, HttpServletRequest request) {
        UUID userId = currentUserResolver.requireUserId(request);
        PracticeStore.Guide guide = practiceService.guide(userId, req.knowledgeItemId(), req.goal());
        return new GuideResponse(guide.conversationId(), guide.content());
    }
}