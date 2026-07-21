package com.fragment.labbooking.knowledge.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** CLI used by external evaluators so Python/CI consumers cannot silently fork the Java fixture definitions. */
public final class AgentEvaluationFixtureExporter {

    private AgentEvaluationFixtureExporter() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || args[0].isBlank()) {
            throw new IllegalArgumentException("Usage: AgentEvaluationFixtureExporter <output-json>");
        }
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("task_suite_version", AgentEvaluationFixtures.VERSION);
        document.put("task_cases", AgentEvaluationFixtures.taskSuiteV1());
        document.put("conversation_suite_version", ConversationEvaluationFixtures.VERSION);
        document.put("conversation_cases", ConversationEvaluationFixtures.suiteV1());
        document.put("acl_cases", AclEvaluationFixtures.suiteV1());
        document.put("fault_cases", FaultEvaluationFixtures.suiteV1());
        new ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), document);
        System.out.println(output);
    }
}
