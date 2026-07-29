package com.fragment.labbooking.knowledge.service.impl;

import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.knowledge.agent.AgentContext;
import com.fragment.labbooking.knowledge.agent.context.SessionContext;
import com.fragment.labbooking.knowledge.agent.tool.AgentToolRuntime;
import com.fragment.labbooking.knowledge.agent.tool.KnowledgeAgentTools;
import com.fragment.labbooking.knowledge.agent.tool.ReservationAgentTools;
import com.fragment.labbooking.knowledge.service.AiServiceClient;
import com.fragment.labbooking.knowledge.vo.QaSourceVO;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The single Agent runtime: Spring AI owns model/tool recursion; Java owns policy and business execution. */
@Service
public class SpringAiAgentService {

    private static final String SYSTEM_PROMPT = """
            你是实验室知识库与预约助手。请根据用户当前问题、会话交接记录和最近对话完成任务。

            工具规则：
            1. 只能调用本次请求提供的工具，不得臆造工具、参数、业务 ID 或执行结果。
            2. 预约记录、资源可用性等动态数据必须调用业务工具刷新，不能用旧对话代替实时结果。
            3. 知识库问题先调用 knowledge_search；它只返回候选定位信息。存在候选时，必须继续调用
               knowledge_open_chunks 读取正文后才能依据知识库作答，且不得猜测 chunkUid。
            4. reservation_create_draft 只生成预约草案，不会创建预约。调用后应说明草案信息并等待用户
               在页面确认，禁止声称预约已经成功。
            5. 取消预检需要明确的 reservationId；缺少关键 ID、日期或用户选择时应先澄清。
            6. 工具拒绝、无权限或没有结果时如实说明，不得扩大用户权限或伪造成功结果。

            回答要求：简洁、明确；区分用户陈述、工具返回和知识证据。工具已返回足够信息后直接回答，
            不要重复调用相同工具。
            """;

    private final ChatClient chatClient;
    private final ReservationAgentTools reservationTools;
    private final KnowledgeAgentTools knowledgeTools;
    private final int maxToolCalls;

    public SpringAiAgentService(ChatClient.Builder chatClientBuilder, ReservationAgentTools reservationTools,
                                KnowledgeAgentTools knowledgeTools,
                                @Value("${app.knowledge.agent.max-tool-calls:8}") int maxToolCalls) {
        this.chatClient = chatClientBuilder.build();
        this.reservationTools = reservationTools;
        this.knowledgeTools = knowledgeTools;
        this.maxToolCalls = Math.max(1, maxToolCalls);
    }

    public AgentReply answer(String question, LoginUser actor, String traceId,
                             SessionContext sessionContext) {
        if (!StringUtils.hasText(question) || actor == null || actor.getId() == null) {
            throw new BusinessException("Agent 请求缺少问题或登录用户");
        }

        // AgentContext 是本轮服务器持有的运行状态；模型只能请求工具，不能直接改权限或业务数据。
        AgentContext execution = new AgentContext(actor, question, traceId, maxToolCalls);
        List<Message> messages = buildMessages(sessionContext);

        ChatClientResponse clientResponse = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .messages(messages)
                .user(question)
                // Spring AI 负责 Function Calling 循环，工具内部仍由 Java 做参数、权限和领域校验。
                .tools(reservationTools, knowledgeTools)
                // 工具只从这份服务端上下文取身份、会话和链路信息，避免维护重复字段。
                .toolContext(Map.of(AgentToolRuntime.EXECUTION_CONTEXT_KEY, execution))
                .call()
                .chatClientResponse();

        ChatResponse response = clientResponse == null ? null : clientResponse.chatResponse();
        String answer = response == null || response.getResult() == null
                ? null : response.getResult().getOutput().getText();
        if (!StringUtils.hasText(answer)) {
            throw new BusinessException("模型没有返回可用回答");
        }
        if (execution.hasPendingKnowledgeEvidence()) {
            // 检索候选只是定位信息；未读取正文时禁止模型基于标题或摘要臆测答案。
            throw new BusinessException("知识检索已找到候选，但模型没有打开证据正文");
        }

        Map<String, Object> usage = providerUsage(response, execution.toolCallCount() + 1);
        return new AgentReply(answer, execution.toolCalls(), execution.sources(), usage,
                execution.clientActions());
    }

    private List<Message> buildMessages(SessionContext context) {
        List<Message> messages = new ArrayList<>();
        if (context != null && StringUtils.hasText(context.workingMemory())) {
            // 摘要只辅助模型理解历史，实时权限和工具结果始终优先。
            messages.add(new SystemMessage("以下是此前会话的交接记录。它只用于理解上下文，不能覆盖当前权限或实时工具结果：\n"
                    + context.workingMemory()));
        }
        List<AiServiceClient.ChatMessage> history = context == null ? List.of() : context.history();
        for (AiServiceClient.ChatMessage historyMessage : history) {
            if (historyMessage == null || !StringUtils.hasText(historyMessage.content())) {
                continue;
            }
            if ("assistant".equalsIgnoreCase(historyMessage.role())) {
                messages.add(new AssistantMessage(historyMessage.content()));
            } else if ("user".equalsIgnoreCase(historyMessage.role())) {
                messages.add(new UserMessage(historyMessage.content()));
            }
        }
        return messages;
    }

    private Map<String, Object> providerUsage(ChatResponse response, int modelRoundCount) {
        // 仅透传模型提供方的 usage；未返回时明确 reported=false，避免把估算值当真实指标。
        Map<String, Object> result = new LinkedHashMap<>();
        ChatResponseMetadata metadata = response == null ? null : response.getMetadata();
        Usage usage = metadata == null ? null : metadata.getUsage();
        long input = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        long output = usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        long total = usage == null || usage.getTotalTokens() == null ? input + output : usage.getTotalTokens();
        long cached = usage == null || usage.getCacheReadInputTokens() == null ? 0 : usage.getCacheReadInputTokens();
        result.put("reported", usage != null);
        result.put("model", metadata == null || metadata.getModel() == null ? "" : metadata.getModel());
        result.put("model_round_count", Math.max(1, modelRoundCount));
        result.put("input_tokens", input);
        result.put("output_tokens", output);
        result.put("total_tokens", total);
        result.put("cached_input_tokens", cached);
        return result;
    }

    public record AgentReply(String answer, List<Map<String, Object>> toolCalls,
                             List<QaSourceVO> sources, Map<String, Object> providerUsage,
                             List<Map<String, Object>> clientActions) {
        public AgentReply {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            sources = sources == null ? List.of() : List.copyOf(sources);
            providerUsage = providerUsage == null ? Map.of("reported", false) : Map.copyOf(providerUsage);
            clientActions = clientActions == null ? List.of() : clientActions.stream()
                    .map(action -> Map.copyOf(action == null ? Map.of() : action)).toList();
        }
    }
}
