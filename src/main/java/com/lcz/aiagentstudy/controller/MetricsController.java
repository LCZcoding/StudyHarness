package com.lcz.aiagentstudy.controller;

import com.lcz.aiagentstudy.exception.BadRequestException;
import com.lcz.aiagentstudy.service.MetricsService;
import com.lcz.aiagentstudy.web.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/metrics")
@RequiredArgsConstructor
public class MetricsController {
    private final CurrentUserResolver currentUserResolver;
    private final MetricsService metricsService;

    /** 只读汇总，直接回 Service 的记录，不再拷贝一层 DTO。 */
    @GetMapping("/summary")
    public MetricsService.Summary summary(@RequestParam(defaultValue = "7") int days,
                                          HttpServletRequest request) {
        UUID userId = currentUserResolver.requireUserId(request);
        if (days < 1 || days > 90) {
            throw new BadRequestException("days 必须在 1 到 90 之间");
        }
        return metricsService.summary(userId, days);
    }
}