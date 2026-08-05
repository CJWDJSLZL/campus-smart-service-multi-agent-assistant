package com.alibaba.cloud.ai.common.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 前后对比实验：滑动窗口（ContextCompressionNode） vs 增量滚动摘要 + 工具结果压缩
 * （IncrementalContextCompressionNode）。
 *
 * <p>在相同模拟对话轨迹（40 轮，含突发工具返回）下分别运行两种节点，对比：
 * LLM 摘要调用次数、单次调用输入规模（上界）、累计输入规模、最终消息缓冲规模、
 * 摘要关键实体（CAMPUS 记录编号）保留率。
 *
 * <p>Mock 摘要器：从输入中提取 CAMPUS_ 编号作为摘要实体，模拟"保留关键实体的摘要"。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContextCompressionComparisonTest {

    private static final int MAX_MESSAGES = 20;
    private static final int KEEP_RECENT = 6;
    private static final int BATCH = 10;
    private static final int TOOL_MAX = 800;

    private static final Pattern CAMPUS_ID = Pattern.compile("CAMPUS_\\d+");

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec responseSpec;
    private final List<String> llmInputs = new ArrayList<>();

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        responseSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenAnswer(inv -> {
            llmInputs.add(inv.getArgument(0));
            return requestSpec;
        });
        when(requestSpec.call()).thenReturn(responseSpec);
        // 模拟摘要器：保留输入中出现的所有 CAMPUS 编号
        when(responseSpec.content()).thenAnswer(inv -> {
            String last = llmInputs.isEmpty() ? "" : llmInputs.get(llmInputs.size() - 1);
            Set<String> ids = new LinkedHashSet<>();
            Matcher m = CAMPUS_ID.matcher(last);
            while (m.find()) {
                ids.add(m.group());
            }
            return "摘要：" + String.join(",", ids);
        });
    }

    /** 运行一个节点走完整模拟对话，返回统计指标 */
    private Map<String, Object> runSimulation(CompressionNodeFactory factory) {
        llmInputs.clear();
        List<Message> messages = new ArrayList<>();
        String summary = "";

        int turns = 40;
        Set<String> everSeen = new LinkedHashSet<>();

        for (int t = 0; t < turns; t++) {
            // 每轮 2 条消息（user + assistant），assistant 携带唯一记录编号
            String id = "CAMPUS_" + (1000 + t);
            messages.add(new UserMessage("第" + t + "轮：帮我办理业务"));
            messages.add(new AssistantMessage("第" + t + "轮回答：已办理，" + id));
            everSeen.add(id);

            // 每 5 轮插入一条超长工具返回（约 4000 字符），触发工具结果压缩
            if (t % 5 == 4) {
                String longData = "记录详情".repeat(500) + id;
                messages.add(new ToolResponseMessage(List.of(
                        new ToolResponseMessage.ToolResponse("r" + t, "campus-get-service-record", longData))));
            }

            // 调用节点
            OverAllState state = mock(OverAllState.class);
            when(state.value("messages")).thenReturn(Optional.of(new ArrayList<>(messages)));
            when(state.value(IncrementalContextCompressionNode.SUMMARY_KEY, String.class))
                    .thenReturn(Optional.ofNullable(summary.isEmpty() ? null : summary));

            try {
                Map<String, Object> result = factory.createNode().apply(state);
                if (result.containsKey("messages")) {
                    messages = new ArrayList<>((List<Message>) result.get("messages"));
                }
                if (result.containsKey(IncrementalContextCompressionNode.SUMMARY_KEY)) {
                    summary = (String) result.get(IncrementalContextCompressionNode.SUMMARY_KEY);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        // 统计
        int calls = llmInputs.size();
        int maxInput = llmInputs.stream().mapToInt(String::length).max().orElse(0);
        int totalInput = llmInputs.stream().mapToInt(String::length).sum();
        // 缓冲字数 = 普通消息文本 + 工具返回 responseData（反映工具结果压缩的真实收益）
        int bufferChars = messages.stream().mapToInt(m -> {
            if (m instanceof ToolResponseMessage trm) {
                return trm.getResponses().stream()
                        .mapToInt(r -> r.responseData() == null ? 0 : r.responseData().length()).sum();
            }
            return m.getText() == null ? 0 : m.getText().length();
        }).sum();

        // 实体保留率：从最终完整缓冲（摘要 + raw 消息 + 工具结果）提取 CAMPUS 编号，
        // 对比全程去重编号 —— 反映对话上下文实际可用的关键实体比例
        Set<String> bufferIds = new LinkedHashSet<>();
        for (Message msg : messages) {
            String text = msg.getText();
            if (text != null) {
                Matcher m = CAMPUS_ID.matcher(text);
                while (m.find()) {
                    bufferIds.add(m.group());
                }
            }
            if (msg instanceof ToolResponseMessage trm) {
                for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                    if (r.responseData() != null) {
                        Matcher m = CAMPUS_ID.matcher(r.responseData());
                        while (m.find()) {
                            bufferIds.add(m.group());
                        }
                    }
                }
            }
        }
        double recall = everSeen.isEmpty() ? 1.0 : (double) bufferIds.size() / everSeen.size();

        return Map.of(
                "calls", calls,
                "maxInputChars", maxInput,
                "totalInputChars", totalInput,
                "finalMessages", messages.size(),
                "bufferChars", bufferChars,
                "entityRecall", Math.round(recall * 1000) / 10.0);
    }

    @FunctionalInterface
    private interface CompressionNodeFactory {
        NodeAction createNode();
    }

    @Test
    void 前后对比实验_滑动窗口_vs_增量滚动摘要() {
        Map<String, Object> before = runSimulation(() ->
                new ContextCompressionNode(chatClient, MAX_MESSAGES, KEEP_RECENT));
        Map<String, Object> after = runSimulation(() ->
                new IncrementalContextCompressionNode(chatClient, MAX_MESSAGES, KEEP_RECENT, BATCH, TOOL_MAX, 2000));

        int beforeCalls = (int) before.get("calls");
        int afterCalls = (int) after.get("calls");
        int beforeMax = (int) before.get("maxInputChars");
        int afterMax = (int) after.get("maxInputChars");
        int beforeTotal = (int) before.get("totalInputChars");
        int afterTotal = (int) after.get("totalInputChars");
        int beforeBuffer = (int) before.get("bufferChars");
        int afterBuffer = (int) after.get("bufferChars");
        int beforeFinal = (int) before.get("finalMessages");
        int afterFinal = (int) after.get("finalMessages");
        double beforeRecall = (double) before.get("entityRecall");
        double afterRecall = (double) after.get("entityRecall");

        System.out.println("========== 压缩策略前后对比（40 轮模拟对话） ==========");
        System.out.printf("| 指标                        | 滑动窗口(before) | 增量滚动摘要(after) | 变化 |%n");
        System.out.printf("| LLM 摘要调用次数            | %16d | %18d | %s |%n",
                beforeCalls, afterCalls, diffPct(beforeCalls, afterCalls));
        System.out.printf("| 单次调用最大输入(字符)       | %16d | %18d | %s |%n",
                beforeMax, afterMax, diffPct(beforeMax, afterMax));
        System.out.printf("| 累计输入(字符)              | %16d | %18d | %s |%n",
                beforeTotal, afterTotal, diffPct(beforeTotal, afterTotal));
        System.out.printf("| 最终消息条数                | %16d | %18d | %s |%n",
                beforeFinal, afterFinal, diffPct(beforeFinal, afterFinal));
        System.out.printf("| 最终缓冲总字符数             | %16d | %18d | %s |%n",
                beforeBuffer, afterBuffer, diffPct(beforeBuffer, afterBuffer));
        System.out.printf("| 缓冲实体保留率(%%)           | %15.1f | %17.1f | %s |%n",
                beforeRecall, afterRecall, diffPct(beforeRecall, afterRecall));

        // 断言核心改进点：增量方案单次调用输入有上界（摘要上限 + 批量），不随会话无限增长；
        // 真实 token 成本对比以 scripts/compare_compression_real.py 实验为准
        assertThatMax(afterMax);
        assertThatBuffer(afterBuffer, beforeBuffer);
    }

    private static String diffPct(double before, double after) {
        if (before == 0) {
            return "-";
        }
        double pct = (after - before) / before * 100;
        return String.format("%+.1f%%", pct);
    }

    private static void assertThatMax(int afterMax) {
        // 有上界：单次输入 ≤ 摘要上限(2000) + 批量(10条 × 60字符宽松上限)
        int bound = 2000 + BATCH * 60;
        org.assertj.core.api.Assertions.assertThat((double) afterMax)
                .as("增量方案单次调用输入应有上界（摘要上限 + 批量）")
                .isLessThanOrEqualTo(bound);
    }

    private static void assertThatBuffer(int afterBuffer, int beforeBuffer) {
        if (beforeBuffer == 0) {
            return;
        }
        org.assertj.core.api.Assertions.assertThat((double) afterBuffer)
                .as("工具结果压缩应降低最终缓冲规模")
                .isLessThan(beforeBuffer);
    }
}
