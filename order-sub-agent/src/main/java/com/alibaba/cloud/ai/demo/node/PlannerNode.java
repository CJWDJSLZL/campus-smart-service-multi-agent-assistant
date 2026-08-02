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
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Plan-and-Execute 模式的规划节点。
 *
 * <p>接收用户请求，调用 LLM 生成结构化的 {@link ExecutionPlan}，通过
 * {@code BeanOutputConverter<ExecutionPlan>} 实现类型安全的结构化输出解析。
 *
 * <p>输出写入 state 键：
 * <ul>
 *   <li>{@code execution_plan} - 生成的执行计划对象</li>
 *   <li>{@code step_results}   - 空列表，供 ExecutorNode 填充</li>
 * </ul>
 */
public class PlannerNode implements NodeAction {

    private static final Logger logger = LoggerFactory.getLogger(PlannerNode.class);

    private static final String PLANNER_SYSTEM_TEMPLATE = """
            你是一个校园事务办理规划助手。根据用户的请求，生成一个结构化的执行计划。

            可用工具列表：
            - campus-validate-service-item: 验证服务事项是否存在且可用
            - campus-check-service-capacity: 检查服务事项剩余名额
            - campus-create-service-record: 创建校园服务办理记录
            - campus-get-service-record-by-user: 查询用户指定办理记录
            - campus-get-service-records-by-user: 查询用户所有办理记录列表
            - campus-get-latest-service-record-by-user: 查询用户最近一次办理记录
            - campus-query-service-records: 多条件查询办理记录
            - campus-update-service-record-remark: 更新办理记录备注
            - campus-cancel-service-record: 取消办理记录
            - memory-search: 查询用户历史偏好
            - memory-store: 存储用户偏好

            规划原则：
            1. 先校验（validate/check），再执行（create/update/cancel）
            2. 查询类请求直接规划对应查询工具
            3. 每个步骤的 toolParameters 须为合法 JSON 字符串，包含该工具所需的所有参数
            4. 步骤数量尽量精简，避免冗余调用

            {format_instructions}
            """;

    private final ChatClient chatClient;
    // BeanOutputConverter 生成 JSON Schema 约束 LLM 输出，并将结果反序列化为类型化对象
    private final BeanOutputConverter<ExecutionPlan> converter = new BeanOutputConverter<>(ExecutionPlan.class);

    public PlannerNode(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        List<Message> messages = castMessages(state.value("messages").orElse(List.of()));
        String userRequest = messages.isEmpty() ? "" :
                messages.get(messages.size() - 1).getText();

        logger.info("PlannerNode: planning for request: {}", userRequest);

        String systemPrompt = PLANNER_SYSTEM_TEMPLATE.replace(
                "{format_instructions}", converter.getFormat());

        String planJson = chatClient.prompt()
                .system(systemPrompt)
                .user(userRequest)
                .call()
                .content();

        logger.info("PlannerNode: raw plan output: {}", planJson);

        ExecutionPlan plan = converter.convert(planJson);
        logger.info("PlannerNode: parsed plan goal={}, steps={}", plan.goal(), plan.steps().size());

        Map<String, Object> result = new HashMap<>();
        result.put("execution_plan", plan);
        result.put("step_results", new ArrayList<String>());
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Message> castMessages(Object obj) {
        if (obj instanceof List<?>) {
            return (List<Message>) obj;
        }
        return new ArrayList<>();
    }
}
