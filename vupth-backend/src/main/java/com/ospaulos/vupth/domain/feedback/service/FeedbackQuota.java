package com.ospaulos.vupth.domain.feedback.service;

import com.ospaulos.vupth.domain.feedback.exception.FeedbackLimitException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Teto de feedbacks por usuario por dia (UTC), em memoria, zerado na virada do dia (feedback-por-email, anti-abuso). */
@Component
public class FeedbackQuota {

    private final int perUserPerDay;
    private final Clock clock;
    private final Map<UUID, Integer> perUser = new ConcurrentHashMap<>();
    private LocalDate day;

    @Autowired
    public FeedbackQuota(@Value("${vupth.feedback.daily-limit-per-user:5}") int perUserPerDay) {
        this(perUserPerDay, Clock.systemUTC());
    }

    FeedbackQuota(int perUserPerDay, Clock clock) {
        this.perUserPerDay = perUserPerDay;
        this.clock = clock;
    }

    public synchronized void take(UUID userId) {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(day)) {
            day = today;
            perUser.clear();
        }
        if (perUser.getOrDefault(userId, 0) >= perUserPerDay) {
            throw new FeedbackLimitException();
        }
        perUser.merge(userId, 1, Integer::sum);
    }
}
