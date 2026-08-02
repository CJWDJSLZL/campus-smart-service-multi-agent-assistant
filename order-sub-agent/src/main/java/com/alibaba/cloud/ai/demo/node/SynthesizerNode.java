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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Plan-and-Execute 模式的综合输出节点。
 *
 * <p>读取 ExecutorNode 产生的 {@code step_results} 和原始 {@link ExecutionPlan}，
 * 调用 LLM 将技术性的执行结果汇总为用户友好的自然语言回答，追加到 {@code messages}。
 */
public class SynthesizerNode implements NodeAction {

    private static final Logger logger = LoggerFactory.getLogger(SynthesizerNode.class);

    private final ChatClient chatClient;

    public SynthesizerNode(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        ExecutionPlan plan = (ExecutionPlan) state.value("execution_plan").orElse(null);
        List<String> stepResults = castStepResults(state.value("step_results").orElse(List.of()));

        String goalDesc = plan != null ? plan.goal() : "完成用户请求";
        String resultsText = String.join("\n", stepResults);

        logger.info("SynthesizerNode: synthesizing {} step results for goal: {}", stepResults.size(), goalDesc);

        String synthesis = chatClient.prompt()
                .system("你是一个友好的校园服务助手。根据下方工具执行结果，用简洁自然的语言向用户汇报办理情况。"
                        + "不要暴露内部工具名称或技术细节，直接说明办理结果和注意事项。"
                        + "用户的目标是：" + goalDesc)
                .user("工具执行结果：\n" + resultsText + "\n\n请总结办理结果。")
                .call()
                .content();

        List<Message> messages = castMessages(state.value("messages").orElse(List.of()));
        List<Message> updatedMessages = new ArrayList<>(messages);
        updatedMessages.add(new AssistantMessage(synthesis));

        return Map.of("messages", updatedMessages);
    }

    @SuppressWarnings("unchecked")
    private List<Message> castMessages(Object obj) {
        if (obj instanceof List<?>) {
            return (List<Message>) obj;
        }
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private List<String> castStepResults(Object obj) {
        if (obj instanceof List<?>) {
            return (List<String>) obj;
        }
        return new ArrayList<>();
    }
}
