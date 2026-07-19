package com.fragment.labbooking.knowledge.agent;

import com.fragment.labbooking.knowledge.service.AiServiceClient;

import java.util.ArrayList;
import java.util.List;

/** A complete user turn. Tool activity is represented by its final answer, never as an orphaned message. */
public record SessionTurn(Long recordId, String traceId, String question, String answer) {

    public List<AiServiceClient.ChatMessage> messages() {
        List<AiServiceClient.ChatMessage> messages = new ArrayList<>();
        if (question != null && !question.isBlank()) {
            messages.add(new AiServiceClient.ChatMessage("user", question));
        }
        if (answer != null && !answer.isBlank()) {
            messages.add(new AiServiceClient.ChatMessage("assistant", answer));
        }
        return messages;
    }

    public int tokenCount(ContextTokenCounter counter) {
        return messages().stream().mapToInt(message -> counter.estimate(message.content()) + 4).sum();
    }
}
