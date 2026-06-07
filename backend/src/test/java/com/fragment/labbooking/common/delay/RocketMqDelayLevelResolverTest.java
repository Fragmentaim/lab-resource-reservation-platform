package com.fragment.labbooking.common.delay;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RocketMqDelayLevelResolverTest {

    private final RocketMqDelayLevelResolver resolver = new RocketMqDelayLevelResolver();

    @Test
    void resolveDelayLevelShouldReturnZeroWhenAlreadyDue() {
        assertThat(resolver.resolveDelayLevel(Duration.ZERO)).isZero();
        assertThat(resolver.resolveDelayLevel(Duration.ofSeconds(-1))).isZero();
    }

    @Test
    void resolveDelayLevelShouldUseMinimumLevelForSubSecondDelay() {
        assertThat(resolver.resolveDelayLevel(Duration.ofMillis(500))).isEqualTo(1);
    }

    @Test
    void resolveDelayLevelShouldMatchExactDefaultLevel() {
        assertThat(resolver.resolveDelayLevel(Duration.ofSeconds(10))).isEqualTo(3);
    }

    @Test
    void resolveDelayLevelShouldUseGreatestLevelNotExceedingRemainingDelay() {
        assertThat(resolver.resolveDelayLevel(Duration.ofMinutes(11))).isEqualTo(14);
    }

    @Test
    void resolveDelayLevelShouldUseMaxLevelForLongDelay() {
        assertThat(resolver.resolveDelayLevel(Duration.ofHours(3))).isEqualTo(18);
    }
}
