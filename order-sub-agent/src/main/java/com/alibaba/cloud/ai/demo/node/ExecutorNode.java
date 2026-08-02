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
 */
public class ExecutorNode implements NodeAction {

    private static final Logger logger = LoggerFactory.getLogger(ExecutorNode.class);

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

            try {
                String result = tool.call(step.toolParameters());
                String stepResult = String.format("Step %d [%s]: %s", step.stepNumber(), step.toolName(), result);
                logger.info("ExecutorNode: {}", stepResult);
                stepResults.add(stepResult);
            } catch (Exception e) {
                String errorMsg = String.format("Step %d [%s] 执行失败: %s",
                        step.stepNumber(), step.toolName(), e.getMessage());
                logger.error(errorMsg);
                stepResults.add(errorMsg);
            }
        }

        return Map.of("step_results", stepResults);
    }
}
