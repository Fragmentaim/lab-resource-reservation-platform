package com.fragment.labbooking.knowledge.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelContextProfilePropertiesTest {

    @Test
    void shouldSelectOperatorConfiguredLongContextProfile() {
        ModelContextProfileProperties properties = new ModelContextProfileProperties();
        ModelContextProfileProperties.Profile profile = new ModelContextProfileProperties.Profile();
        profile.setContextWindowTokens(256_000);
        profile.setMaxOutputTokens(8_000);
        profile.setSafetyMarginTokens(4_096);
        properties.setActiveProfile("long-context");
        properties.setProfiles(Map.of("long-context", profile));

        ModelContextProfileProperties.Profile active = properties.active();

        assertThat(active.getContextWindowTokens()).isEqualTo(256_000);
        assertThat(active.getMaxOutputTokens()).isEqualTo(8_000);
        assertThat(active.getSafetyMarginTokens()).isEqualTo(4_096);
        assertThat(active.effectiveSummaryMaxTokens()).isEqualTo(8_000);
        assertThat(active.effectiveMaxSingleToolResultTokens()).isEqualTo(8_000);
    }
}
