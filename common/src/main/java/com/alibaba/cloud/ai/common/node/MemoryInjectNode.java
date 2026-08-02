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

package com.alibaba.cloud.ai.common.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Memory 主动注入节点（公共组件）。
 *
 * <p>在 ReactAgent 执行前作为前置节点运行，主动调用 memory-search 工具检索用户历史偏好，
 * 并将结果以 SystemMessage 形式注入到 messages 列表的最前面。
 *
 * <p>相比依赖 LLM 被动决定是否调用 memory-search 工具，此节点保证每次对话都能加载用户记忆，
 * 实现个性化响应的确定性触发。
 *
 * <p>各子智能体（order / consult / feedback）通过 StateGraph 共享此节点：
 * <pre>
 *   START → memory_inject_node → react_agent_node → END
 * </pre>
 */
public class MemoryInjectNode implements NodeAction {

    private final ToolCallback memorySearchTool;

    public MemoryInjectNode(ToolCallback memorySearchTool) {
        this.memorySearchTool = memorySearchTool;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        if (memorySearchTool == null) {
            return Map.of();
        }

        String userId = extractUserId(state);
        if (!StringUtils.hasText(userId)) {
            return Map.of();
        }

        try {
            String toolInput = String.format("{\"userId\":\"%s\",\"query\":\"用户偏好和历史习惯\"}", userId);
            String memoryResult = memorySearchTool.call(toolInput);

            if (StringUtils.hasText(memoryResult) && !memoryResult.contains("未找到")) {
                List<Message> messages = castMessages(state.value("messages").orElse(List.of()));
                List<Message> enriched = new ArrayList<>();
                enriched.add(new SystemMessage("【用户历史偏好记忆】以下是该用户的历史偏好，请在回答时参考：\n" + memoryResult));
                enriched.addAll(messages);
                return Map.of("messages", enriched, "user_id", userId);
            }
        } catch (Exception e) {
            // 记忆注入失败不应阻塞主流程，静默降级
        }

        return Map.of("user_id", userId);
    }

    /**
     * 从 state 中提取 user_id：优先从状态键获取，其次从消息文本的 &lt;userId&gt; 标签解析。
     * Supervisor 在转发请求时会将 userId 嵌入消息末尾，格式为 <userId>xxx</userId>。
     */
    private String extractUserId(OverAllState state) {
        String userId = (String) state.value("user_id").orElse(null);
        if (StringUtils.hasText(userId)) {
            return userId;
        }
        List<Message> messages = castMessages(state.value("messages").orElse(List.of()));
        if (!messages.isEmpty()) {
            Message last = messages.get(messages.size() - 1);
            if (last instanceof UserMessage) {
                String text = last.getText();
                int start = text.indexOf("<userId>");
                int end = text.indexOf("</userId>");
                if (start >= 0 && end > start) {
                    return text.substring(start + 8, end).trim();
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Message> castMessages(Object obj) {
        if (obj instanceof List<?>) {
            return (List<Message>) obj;
        }
        return new ArrayList<>();
    }
}
