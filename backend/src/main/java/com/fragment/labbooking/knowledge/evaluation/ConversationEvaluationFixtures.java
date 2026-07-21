package com.fragment.labbooking.knowledge.evaluation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Fixed 30-conversation suite. Every group has eight user/assistant turns. */
public final class ConversationEvaluationFixtures {

    public static final String VERSION = "conversation-suite-v1";
    public static final int TOTAL_CASES = 30;

    private ConversationEvaluationFixtures() {}

    public static List<ConversationEvaluationCase> suiteV1() {
        List<ConversationEvaluationCase> cases = new ArrayList<>(TOTAL_CASES);
        addDateAndPeriodReferences(cases); // 10
        addResourceSwitches(cases);         // 8
        addOrdinalReferences(cases);        // 6
        addReservationToCancellation(cases);// 6
        if (cases.size() != TOTAL_CASES) throw new IllegalStateException("fixture count changed: " + cases.size());
        return List.copyOf(cases);
    }

    private static void addDateAndPeriodReferences(List<ConversationEvaluationCase> cases) {
        for (int index = 0; index < 10; index++) {
            String date = "2026-07-" + String.format("%02d", 22 + index);
            cases.add(new ConversationEvaluationCase("date-period-" + twoDigits(index + 1), "先指定日期，后续仅说下午",
                    user(), turns("我想在 " + date + " 预约显微镜", "好，" + date + " 下午还有空吗？"),
                    Map.of("date", date, "period", "下午", "resource", "显微镜"), true, index >= 5,
                    "RESOURCE_AVAILABILITY"));
        }
    }

    private static void addResourceSwitches(List<ConversationEvaluationCase> cases) {
        List<String> resources = List.of("示波器", "3D打印机", "焊接台", "GPU服务器");
        for (int index = 0; index < 8; index++) {
            String first = resources.get(index % resources.size());
            String second = resources.get((index + 1) % resources.size());
            cases.add(new ConversationEvaluationCase("resource-switch-" + twoDigits(index + 1), "用户中途更换资源",
                    user(), turns("先查" + first + "的可用时段", "不要" + first + "了，改查" + second + "刚才那个时段"),
                    Map.of("resource", second, "replaced_resource", first), true, index >= 5,
                    "RESOURCE_AVAILABILITY"));
        }
    }

    private static void addOrdinalReferences(List<ConversationEvaluationCase> cases) {
        for (int index = 0; index < 6; index++) {
            String resource = index % 2 == 0 ? "示波器" : "显微镜";
            cases.add(new ConversationEvaluationCase("ordinal-" + twoDigits(index + 1), "刚才那个、第二个等指代",
                    user(), turns("请列出" + resource + "的两个可预约时段", "就选第二个，帮我看看是否还可用"),
                    Map.of("resource", resource, "slot_reference", "第二个"), true, index >= 2,
                    "RESOURCE_AVAILABILITY"));
        }
    }

    private static void addReservationToCancellation(List<ConversationEvaluationCase> cases) {
        for (int index = 0; index < 6; index++) {
            long reservationId = 5001L + index;
            cases.add(new ConversationEvaluationCase("reservation-cancel-" + twoDigits(index + 1), "先查询预约，后续要求取消",
                    user(), turns("请查我的预约，重点看看编号 " + reservationId, "把刚才那个预约取消前先帮我预检"),
                    Map.of("reservationId", reservationId, "writeExecuted", false), true, true,
                    "CANCELLATION_PREVIEW"));
        }
    }

    /** Eight complete turns: prior turns are intentionally verbose enough for compact-session scenarios. */
    private static List<AgentEvaluationCase.ConversationMessage> turns(String setup, String finalQuestion) {
        List<AgentEvaluationCase.ConversationMessage> messages = new ArrayList<>();
        addTurn(messages, setup, "我会基于当前登录用户和可访问资源继续协助你。");
        addTurn(messages, "请记住我偏好下午，并且不要替我直接执行写操作。", "已记录：优先下午；涉及取消时仅先做预检。");
        addTurn(messages, "如果信息不足，请先说明缺少什么，不要猜测预约编号。", "明白，我会要求明确的资源、时段或预约编号。");
        addTurn(messages, "我还需要遵守实验室权限和文档访问范围。", "会在检索和读取文档前执行权限过滤。");
        addTurn(messages, "之前的回答请保留关键的日期、资源和预约标识。", "会将这些关键约束保留在会话上下文中。");
        addTurn(messages, "接下来请继续按照当前对话上下文处理。", "好的，我将结合已确认的约束处理后续问题。");
        addTurn(messages, "这是较长会话，用于验证摘要后是否仍保留关键条件。", "已记录，压缩历史时会保留用户、时间、资源和预约相关事实。");
        addTurn(messages, finalQuestion, "<FINAL_AGENT_RESPONSE_CAPTURED_BY_EVALUATOR>");
        return List.copyOf(messages);
    }

    private static void addTurn(List<AgentEvaluationCase.ConversationMessage> messages, String user, String assistant) {
        messages.add(new AgentEvaluationCase.ConversationMessage("user", user));
        messages.add(new AgentEvaluationCase.ConversationMessage("assistant", assistant));
    }

    private static AgentEvaluationCase.Actor user() { return new AgentEvaluationCase.Actor(2001L, "USER", false); }
    private static String twoDigits(int value) { return String.format("%02d", value); }
}
