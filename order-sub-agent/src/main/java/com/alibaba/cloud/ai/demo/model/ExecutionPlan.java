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

package com.alibaba.cloud.ai.demo.model;

import java.util.List;

/**
 * Plan-and-Execute 模式的结构化执行计划。
 *
 * <p>由 PlannerNode 通过 {@code BeanOutputConverter<ExecutionPlan>} 生成，
 * 包含用户目标描述和按顺序执行的步骤列表。
 *
 * <p>ExecutorNode 遍历 steps，按 toolName 查找并调用对应的 MCP 工具，
 * 实现确定性的多步骤执行而非 ReAct 的动态推理循环。
 */
public record ExecutionPlan(
        String goal,
        List<ExecutionStep> steps
) {
    /**
     * 单个执行步骤。
     *
     * @param stepNumber     步骤序号（从1开始）
     * @param toolName       要调用的工具名称，须与 MCP 注册的工具名一致
     * @param description    步骤描述（面向用户的说明）
     * @param toolParameters 传给工具的 JSON 参数字符串，直接传入 tool.call()
     */
    public record ExecutionStep(
            int stepNumber,
            String toolName,
            String description,
            String toolParameters
    ) {}
}
