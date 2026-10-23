package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.repository.ReviewScheduleRepository;
import com.lcz.aiagentstudy.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TodoService {
    private final TodoRepository todoRepository;
    private final ReviewScheduleRepository reviewScheduleRepository;

    public record TodoItem(String type, UUID knowledgeItemId, UUID conversationId,
                           String title, int count) {
    }

    public List<TodoItem> list(UUID userId) {
        List<TodoItem> items = new ArrayList<>();

        // REVIEW 一条汇总：到期题可能跨资料，按资料拆反而让用户要挨个点
        int dueCount = reviewScheduleRepository.countDue(userId);
        if (dueCount > 0) {
            items.add(new TodoItem("REVIEW", null, null, "到期复习", dueCount));
        }

        for (TodoRepository.RetryRow row : todoRepository.findRetry(userId)) {
            items.add(new TodoItem("RETRY", row.knowledgeItemId(), null,
                    row.title(), row.count()));
        }
        for (TodoRepository.UntestedRow row : todoRepository.findUntested(userId)) {
            items.add(new TodoItem("UNTESTED", row.knowledgeItemId(), row.conversationId(),
                    row.title(), row.count()));
        }
        return items;
    }
}