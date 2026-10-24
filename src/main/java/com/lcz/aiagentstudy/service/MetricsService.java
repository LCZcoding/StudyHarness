package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.repository.AiCallRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MetricsService {
    private final AiCallRepository aiCallRepository;

    public record Summary(int days,
                          List<AiCallRepository.SceneMetrics> scenes,
                          long totalCalls,
                          long totalPromptTokens,
                          long totalCompletionTokens,
                          long failedCalls,
                          long callsWithoutUsage) {
    }

    public Summary summary(UUID userId, int days) {
        List<AiCallRepository.SceneMetrics> scenes = aiCallRepository.summary(userId, days);
        return new Summary(
                days,
                scenes,
                scenes.stream().mapToLong(AiCallRepository.SceneMetrics::calls).sum(),
                scenes.stream().mapToLong(AiCallRepository.SceneMetrics::promptTokens).sum(),
                scenes.stream().mapToLong(AiCallRepository.SceneMetrics::completionTokens).sum(),
                scenes.stream().mapToLong(AiCallRepository.SceneMetrics::failedCalls).sum(),
                scenes.stream().mapToLong(AiCallRepository.SceneMetrics::callsWithoutUsage).sum());
    }
}