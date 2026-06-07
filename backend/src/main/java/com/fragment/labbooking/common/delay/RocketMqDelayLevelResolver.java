package com.fragment.labbooking.common.delay;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class RocketMqDelayLevelResolver {

    private static final List<Duration> DEFAULT_LEVELS = List.of(
            Duration.ofSeconds(1),
            Duration.ofSeconds(5),
            Duration.ofSeconds(10),
            Duration.ofSeconds(30),
            Duration.ofMinutes(1),
            Duration.ofMinutes(2),
            Duration.ofMinutes(3),
            Duration.ofMinutes(4),
            Duration.ofMinutes(5),
            Duration.ofMinutes(6),
            Duration.ofMinutes(7),
            Duration.ofMinutes(8),
            Duration.ofMinutes(9),
            Duration.ofMinutes(10),
            Duration.ofMinutes(20),
            Duration.ofMinutes(30),
            Duration.ofHours(1),
            Duration.ofHours(2)
    );

    public int resolveDelayLevel(LocalDateTime deliverAt) {
        return resolveDelayLevel(Duration.between(LocalDateTime.now(), deliverAt));
    }

    public int resolveDelayLevel(Duration remaining) {
        if (remaining == null || remaining.isZero() || remaining.isNegative()) {
            return 0;
        }

        int selectedLevel = 1;
        for (int i = 0; i < DEFAULT_LEVELS.size(); i++) {
            Duration levelDuration = DEFAULT_LEVELS.get(i);
            if (levelDuration.compareTo(remaining) <= 0) {
                selectedLevel = i + 1;
            } else {
                break;
            }
        }
        return selectedLevel;
    }
}
