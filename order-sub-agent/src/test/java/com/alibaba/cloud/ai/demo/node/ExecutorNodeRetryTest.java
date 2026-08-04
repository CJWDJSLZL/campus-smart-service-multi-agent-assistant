package com.alibaba.cloud.ai.demo.node;

import com.alibaba.cloud.ai.demo.model.ExecutionPlan;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * L1 集成验证 V-10：工具调用 Retry 指数退避。
 * 验证「瞬时故障对用户不可见」：前 2 次失败、第 3 次成功时，
 * 用户侧拿到成功结果；退避延迟符合 500ms × retry（500ms、1000ms）。
 */
@ExtendWith(MockitoExtension.class)
class ExecutorNodeRetryTest {

    private ToolCallback buildTool(String name, AtomicInteger calls, int failUntil) {
        ToolCallback tool = mock(ToolCallback.class);
        ToolDefinition def = mock(ToolDefinition.class);
        when(def.name()).thenReturn(name);
        when(tool.getToolDefinition()).thenReturn(def);
        when(tool.call(anyString())).thenAnswer(inv -> {
            int n = calls.incrementAndGet();
            if (n <= failUntil) {
                throw new RuntimeException("模拟瞬时故障 #" + n);
            }
            return "操作成功";
        });
        return tool;
    }

    private OverAllState stateWithPlan(ExecutionPlan plan) {
        OverAllState state = mock(OverAllState.class);
        when(state.value("execution_plan")).thenReturn(Optional.of(plan));
        return state;
    }

    @Test
    void 前两次失败第三次成功对用户不可见_V10() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ToolCallback tool = buildTool("tool-a", calls, 2);
        ExecutorNode node = new ExecutorNode(List.of(tool));

        ExecutionPlan plan = new ExecutionPlan("目标", false, null,
                List.of(new ExecutionPlan.ExecutionStep(1, "tool-a", "执行步骤", "{}")));

        long start = System.nanoTime();
        Map<String, Object> result = node.apply(stateWithPlan(plan));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        @SuppressWarnings("unchecked")
        List<String> stepResults = (List<String>) result.get("step_results");
        assertThat(stepResults).hasSize(1);
        assertThat(stepResults.get(0)).contains("操作成功");
        // 共调用 3 次（1 次初始 + 2 次重试）
        assertThat(calls.get()).isEqualTo(3);
        // 退避延迟累计 ≥ 500ms + 1000ms
        assertThat(elapsedMs).isGreaterThanOrEqualTo(1400L);
        System.out.println("[V-10] 自愈耗时实测: " + elapsedMs + "ms, 调用次数: " + calls.get());
    }

    @Test
    void 三次全部失败时返回失败信息不抛异常() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ToolCallback tool = buildTool("tool-a", calls, Integer.MAX_VALUE);
        ExecutorNode node = new ExecutorNode(List.of(tool));

        ExecutionPlan plan = new ExecutionPlan("目标", false, null,
                List.of(new ExecutionPlan.ExecutionStep(1, "tool-a", "执行步骤", "{}")));

        Map<String, Object> result = node.apply(stateWithPlan(plan));

        @SuppressWarnings("unchecked")
        List<String> stepResults = (List<String>) result.get("step_results");
        assertThat(stepResults).hasSize(1);
        assertThat(stepResults.get(0)).contains("执行失败").contains("已重试 3 次");
        assertThat(calls.get()).isEqualTo(4); // 1 初始 + 3 重试
    }

    @Test
    void 工具不存在时返回明确提示() throws Exception {
        ExecutorNode node = new ExecutorNode(List.of()); // 空工具表
        ExecutionPlan plan = new ExecutionPlan("目标", false, null,
                List.of(new ExecutionPlan.ExecutionStep(1, "not-exist-tool", "执行步骤", "{}")));

        Map<String, Object> result = node.apply(stateWithPlan(plan));

        @SuppressWarnings("unchecked")
        List<String> stepResults = (List<String>) result.get("step_results");
        assertThat(stepResults.get(0)).contains("工具 [not-exist-tool] 不存在");
    }
}
