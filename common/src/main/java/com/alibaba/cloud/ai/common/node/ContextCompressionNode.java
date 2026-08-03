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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 滑动窗口上下文压缩节点（公共组件）。
 *
 * <p>当 messages 列表超过 {@code maxMessages} 条时，调用 LLM 对早期对话进行摘要压缩，
 * 用摘要 SystemMessage 替换早期消息，保留最近 {@code keepRecentCount} 条原始消息。
 * 防止多轮对话导致 context window 超限，同时保留关键历史信息。
 *
 * <p>压缩策略：
 * <pre>
 *   原始: [msg1, msg2, ..., msgN-5, msgN-4, ..., msgN]
 *   压缩: [SummarySystemMessage(摘要 msg1..msgN-5), msgN-4, ..., msgN]
 * </pre>
 *
 * <p>在 StateGraph 中作为 ReactAgent 的前置节点使用：
 * <pre>
 *   START → context_compress → react_agent → END
 * </pre>
 */
public class ContextCompressionNode implements NodeAction {

    private static final Logger logger = LoggerFactory.getLogger(ContextCompressionNode.class);

    private static final int DEFAULT_MAX_MESSAGES = 20;
    private static final int DEFAULT_KEEP_RECENT = 6;

    private final ChatClient chatClient;
    private final int maxMessages;
    private final int keepRecentCount;

    public ContextCompressionNode(ChatClient chatClient) {
        this(chatClient, DEFAULT_MAX_MESSAGES, DEFAULT_KEEP_RECENT);
    }

    public ContextCompressionNode(ChatClient chatClient, int maxMessages, int keepRecentCount) {
        this.chatClient = chatClient;
        this.maxMessages = maxMessages;
        this.keepRecentCount = keepRecentCount;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        List<Message> messages = castMessages(state.value("messages").orElse(List.of()));

        if (messages.size() <= maxMessages) {
            return Map.of(); // 未超限，无需压缩
        }

        int compressCount = messages.size() - keepRecentCount;
        List<Message> toCompress = messages.subList(0, compressCount);
        List<Message> toKeep = messages.subList(compressCount, messages.size());

        logger.info("ContextCompressionNode: messages={}, compressing first {} msgs, keeping last {}",
                messages.size(), compressCount, toKeep.size());

        // 构建待压缩的对话文本
        StringBuilder historyText = new StringBuilder();
        for (Message msg : toCompress) {
            String role = msg.getMessageType().getValue();
            historyText.append("[").append(role).append("]: ").append(msg.getText()).append("\n");
        }

        // 调用 LLM 生成摘要
        String summary;
        try {
            summary = chatClient.prompt()
                    .system("请用 3-5 句话提炼以下对话历史的关键信息，保留用户身份、已办理/查询的服务名称、关键数据（记录编号、时间偏好等）：")
                    .user(historyText.toString())
                    .call()
                    .content();
            logger.info("ContextCompressionNode: summary generated ({} chars)", summary != null ? summary.length() : 0);
        } catch (Exception e) {
            logger.warn("ContextCompressionNode: summary generation failed, keeping all messages: {}", e.getMessage());
            return Map.of(); // 压缩失败时静默降级，不修改 messages
        }

        // 用摘要替换早期消息
        List<Message> compressed = new ArrayList<>();
        compressed.add(new SystemMessage("【历史对话摘要】" + summary));
        compressed.addAll(toKeep);

        logger.info("ContextCompressionNode: compressed from {} to {} messages",
                messages.size(), compressed.size());
        return Map.of("messages", compressed);
    }

    @SuppressWarnings("unchecked")
    private List<Message> castMessages(Object obj) {
        if (obj instanceof List<?>) {
            return (List<Message>) obj;
        }
        return new ArrayList<>();
    }
}
