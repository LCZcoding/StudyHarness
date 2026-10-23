package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.repository.ProgressRepository;
import com.lcz.aiagentstudy.repository.ReviewScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProgressService {
    private final ProgressRepository progressRepository;
    private final ReviewScheduleRepository reviewScheduleRepository;

    public record WeekView(LocalDate weekStart, int attempts) {
    }

    public record ProgressView(int masteredItems, int totalItems, int streakDays,
                               int dueCount, List<WeekView> weeks) {
    }

    public ProgressView load(UUID userId) {
        int totalItems = progressRepository.countItems(userId);
        int masteredItems = progressRepository.countMasteredItems(userId);
        int dueCount = reviewScheduleRepository.countDue(userId);
        int streakDays = streakDays(progressRepository.findActiveDates(userId));

        // 缺的周补 0：前端拿到固定 8 根柱子，不用自己去对齐日期
        Map<LocalDate, Integer> byWeek = new HashMap<>();
        for (ProgressRepository.WeekStat stat : progressRepository.findWeeklyAttempts(userId)) {
            byWeek.put(stat.weekStart(), stat.attempts());
        }
        LocalDate currentMonday = LocalDate.now().with(DayOfWeek.MONDAY);
        List<WeekView> weeks = new ArrayList<>(8);
        for (int i = 7; i >= 0; i--) {
            LocalDate weekStart = currentMonday.minusWeeks(i);
            weeks.add(new WeekView(weekStart, byWeek.getOrDefault(weekStart, 0)));
        }

        return new ProgressView(masteredItems, totalItems, streakDays, dueCount, weeks);
    }

    /**
     * 连续学习天数。今天还没学就从昨天开始数 —— 否则用户早上打开页面会看到"连续 0 天"，
     * 明明昨天刚学过，等于凭空惩罚一次。
     */
    private int streakDays(List<LocalDate> activeDates) {
        Set<LocalDate> days = new HashSet<>(activeDates);
        LocalDate cursor = LocalDate.now();
        if (!days.contains(cursor)) {
            cursor = cursor.minusDays(1);
        }
        int streak = 0;
        while (days.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }
}