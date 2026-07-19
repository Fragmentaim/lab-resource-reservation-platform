package com.fragment.labbooking.knowledge.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResultContextPackerTest {

    private final ToolResultContextPacker packer = new ToolResultContextPacker(new ContextTokenCounter());

    @Test
    void shouldPassSmallResultsThroughWithoutAnExtraModelCall() {
        Map<String, Object> output = Map.of("resultCount", 1, "slots", List.of(Map.of("resourceName", "GPU-A")));

        ToolResultContextPacker.PackedToolResult packed = packer.pack("resource_availability", output);

        assertThat(packed.modelOutput()).isEqualTo(output);
        assertThat(packed.safeDetail()).containsEntry("strategy", "DIRECT");
    }

    @Test
    void shouldTrimLargeToolResultsAndExposeTheDecisionToTheRuntime() {
        Map<String, Object> output = Map.of("chunks", List.of(
                Map.of("chunk_uid", "c1", "content", "资料正文".repeat(1200)),
                Map.of("chunk_uid", "c2", "content", "资料正文".repeat(1200))
        ));

        ToolResultContextPacker.PackedToolResult packed = packer.pack("knowledge_open_chunks", output);

        assertThat(packed.safeDetail()).containsEntry("strategy", "DETERMINISTIC_FIELD_AND_TOKEN_TRIM");
        assertThat(packed.modelOutput()).containsKey("_context_pack");
        assertThat(String.valueOf(packed.modelOutput())).contains("已按上下文预算截断");
    }
}
