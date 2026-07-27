package com.fragment.labbooking.knowledge.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.knowledge.agent.AgentExecutionContext;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;

/** Adapts an existing domain tool to Spring AI without moving authorization into the model layer. */
public final class SpringAiToolCallback implements ToolCallback {

    private final AgentTool tool;
    private final AgentToolExecutor executor;
    private final AgentExecutionContext context;
    private final ToolDefinition definition;

    public SpringAiToolCallback(AgentTool tool, AgentToolExecutor executor,
                                AgentExecutionContext context, ObjectMapper objectMapper) {
        this.tool = tool;
        this.executor = executor;
        this.context = context;
        this.definition = toToolDefinition(tool, objectMapper);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String input) {
        return executor.execute(tool, input, context);
    }

    @Override
    public String call(String input, ToolContext ignored) {
        return call(input);
    }

    @SuppressWarnings("unchecked")
    private static ToolDefinition toToolDefinition(AgentTool tool, ObjectMapper objectMapper) {
        try {
            Map<String, Object> wrapper = tool.definition();
            Map<String, Object> function = (Map<String, Object>) wrapper.get("function");
            String description = String.valueOf(function.getOrDefault("description", ""));
            String inputSchema = objectMapper.writeValueAsString(function.getOrDefault("parameters", Map.of(
                    "type", "object", "properties", Map.of())));
            return ToolDefinition.builder()
                    .name(tool.name())
                    .description(description)
                    .inputSchema(inputSchema)
                    .build();
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid tool definition: " + tool.name(), exception);
        }
    }
}
