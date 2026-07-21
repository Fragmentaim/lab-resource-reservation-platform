package com.fragment.labbooking.knowledge.service;

import com.fragment.labbooking.knowledge.agent.ContextPlan;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NativeToolCallingClientTest {

    @Test
    void shouldKeepProviderUsageFieldsThatAreExplicitlyUnknown() {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("reported", true);
        usage.put("input_tokens", 320);
        usage.put("cached_input_tokens", null);

        NativeToolCallingClient.ToolRound round = new NativeToolCallingClient.ToolRound(
                List.of(), "ok", "example-model", usage);

        assertThat(round.providerUsage()).containsEntry("input_tokens", 320)
                .containsKey("cached_input_tokens");
        assertThat(ContextPlan.from(1, round).safeDetail())
                .containsEntry("provider_usage", round.providerUsage());
    }
}
