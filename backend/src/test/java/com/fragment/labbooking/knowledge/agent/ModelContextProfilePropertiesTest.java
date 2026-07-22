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
        assertThat(active.effectiveMaxSingleToolResultTokens()).isEqualTo(8_000);
    }

    @Test
    void shouldAllowAConstrainedEvaluationProfileWithoutChangingTheDefault() {
        ModelContextProfileProperties properties = new ModelContextProfileProperties();
        ModelContextProfileProperties.Profile profile = new ModelContextProfileProperties.Profile();
        profile.setContextWindowTokens(48_000);
        profile.setMaxOutputTokens(4_000);
        profile.setSafetyMarginTokens(2_048);
        properties.setActiveProfile("context-eval-48k");
        properties.setProfiles(Map.of("context-eval-48k", profile));

        ModelContextProfileProperties.Profile active = properties.active();

        assertThat(active.getContextWindowTokens()).isEqualTo(48_000);
        assertThat(active.getMaxOutputTokens()).isEqualTo(4_000);
        assertThat(active.getSafetyMarginTokens()).isEqualTo(2_048);
    }
}
