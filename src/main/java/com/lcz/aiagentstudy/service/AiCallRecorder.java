package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.repository.AiCallRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 模型调用埋点：只做计时和把 usage 落库。
 * 观测失败绝不能影响业务 —— 写库异常在这里吞掉，只打日志。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiCallRecorder {
    private final AiCallRepository aiCallRepository;

    public long start() {
        return System.nanoTime();
    }

    /** 评测集传 userId = null：评测不是某个用户的使用行为，不写观测表。 */
    public void recordCall(UUID userId, String scene, long startNanos, ChatResponse response) {
        if (userId == null) {
            return;
        }
        Usage usage = response == null || response.getMetadata() == null
                ? null : response.getMetadata().getUsage();
        record(userId, scene, startNanos, null,
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(),
                true, null);
    }

    public void recordFailure(UUID userId, String scene, long startNanos, RuntimeException error) {
        if (userId == null) {
            return;
        }
        record(userId, scene, startNanos, null, null, null, false,
                error.getClass().getSimpleName());
    }

    /** 流式：首 token 时间由 doOnNext 首次触发时记录；拿不到 usage 就存 NULL。 */
    public void recordStream(UUID userId, String scene, long startNanos, Long firstTokenNanos,
                             boolean success, String errorCode) {
        if (userId == null) {
            return;
        }
        Integer firstTokenMs = firstTokenNanos == null
                ? null : (int) ((firstTokenNanos - startNanos) / 1_000_000);
        record(userId, scene, startNanos, firstTokenMs, null, null, success, errorCode);
    }

    private void record(UUID userId, String scene, long startNanos, Integer firstTokenMs,
                        Integer promptTokens, Integer completionTokens,
                        boolean success, String errorCode) {
        int latencyMs = (int) ((System.nanoTime() - startNanos) / 1_000_000);
        try {
            aiCallRepository.insert(userId, scene, latencyMs, firstTokenMs,
                    promptTokens, completionTokens, success, errorCode);
        } catch (RuntimeException e) {
            log.warn("写入 AI 调用观测失败，忽略: scene={}, cause={}", scene, e.getMessage());
        }
    }
}