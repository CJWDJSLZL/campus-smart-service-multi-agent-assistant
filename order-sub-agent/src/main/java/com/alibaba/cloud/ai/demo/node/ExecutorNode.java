/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.cloud.ai.demo.node;

import com.alibaba.cloud.ai.demo.model.ExecutionPlan;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Plan-and-Execute 模式的执行节点。
 *
 * <p>从 state 读取 {@link ExecutionPlan}，按照计划步骤顺序调用对应的 MCP 工具，
 * 将每步的执行结果追加到 {@code step_results} 列表。
 *
 * <p>相比 ReactAgent 的动态推理循环，ExecutorNode 以确定性方式逐步执行，
 * 每步结果都被记录下来供 SynthesizerNode 汇总。
 *
 * <p>每个步骤失败时进行指数退避重试（最多 {@value MAX_RETRY} 次），
 * 延迟公式：500ms × retry（第 1 次重试 500ms、第 2 次 1000ms、第 3 次 1500ms）。
 */
public class ExecutorNode implements NodeAction {

    private static final Logger logger = LoggerFactory.getLogger(ExecutorNode.class);
    private static final int MAX_RETRY = 3;

    private final Map<String, ToolCallback> toolMap;

    public ExecutorNode(List<ToolCallback> tools) {
        this.toolMap = new HashMap<>();
        for (ToolCallback tool : tools) {
            toolMap.put(tool.getToolDefinition().name(), tool);
        }
        logger.info("ExecutorNode initialized with {} tools: {}", toolMap.size(), toolMap.keySet());
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        ExecutionPlan plan = (ExecutionPlan) state.value("execution_plan")
                .orElseThrow(() -> new IllegalStateException("execution_plan not found in state"));

        List<String> stepResults = new ArrayList<>();

        for (ExecutionPlan.ExecutionStep step : plan.steps()) {
            logger.info("ExecutorNode: executing step {} - tool={}, desc={}",
                    step.stepNumber(), step.toolName(), step.description());

            ToolCallback tool = toolMap.get(step.toolName());
            if (tool == null) {
                String msg = String.format("Step %d: 工具 [%s] 不存在", step.stepNumber(), step.toolName());
                logger.warn(msg);
                stepResults.add(msg);
                continue;
            }

            // 指数退避重试：单步工具调用失败时最多重试 MAX_RETRY 次
            String stepResult = executeWithRetry(step, tool);
            stepResults.add(stepResult);
        }

        return Map.of("step_results", stepResults);
    }

    /**
     * 带指数退避的工具调用。
     * 延迟策略：第 1 次重试等待 500ms，第 2 次等待 1000ms，第 3 次等待 1500ms。
     */
    private String executeWithRetry(ExecutionPlan.ExecutionStep step, ToolCallback tool) {
        Exception lastException = null;
        for (int retry = 0; retry <= MAX_RETRY; retry++) {
            try {
                if (retry > 0) {
                    long delay = 500L * retry;
                    logger.warn("ExecutorNode: step {} retry {}/{}, waiting {}ms",
                            step.stepNumber(), retry, MAX_RETRY, delay);
                    Thread.sleep(delay);
                }
                String result = tool.call(step.toolParameters());
                if (retry > 0) {
                    logger.info("ExecutorNode: step {} succeeded on retry {}", step.stepNumber(), retry);
                }
                return String.format("Step %d [%s]: %s", step.stepNumber(), step.toolName(), result);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return String.format("Step %d [%s] 被中断", step.stepNumber(), step.toolName());
            } catch (Exception e) {
                lastException = e;
                logger.error("ExecutorNode: step {} attempt {} failed: {}",
                        step.stepNumber(), retry + 1, e.getMessage());
            }
        }
        return String.format("Step %d [%s] 执行失败（已重试 %d 次）: %s",
                step.stepNumber(), step.toolName(), MAX_RETRY,
                lastException != null ? lastException.getMessage() : "未知错误");
    }
}
