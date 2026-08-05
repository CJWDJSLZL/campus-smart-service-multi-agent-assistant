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
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 增量滚动摘要 + 工具结果压缩节点（滑动窗口方案的替代实现）。
 *
 * <p>相对 {@link ContextCompressionNode}（一次性重摘要全部早期消息）的两个改进：
 *
 * <p><b>① 增量滚动摘要</b>：运行摘要持久化在 state 的 {@code context_summary} 键中。
 * 每次触发时不再"从零重摘要全部历史"，而是把"已有摘要 + 最多 {@code compressBatch} 条新消息"
 * 合并为更新后的摘要（增量更新 Prompt）。相比一次性重摘要：
 * <ul>
 *   <li>单次 LLM 调用输入规模<b>有上界</b>（摘要 + 至多 compressBatch 条消息），突发消息时不会出现一次超长调用；</li>
 *   <li>已有摘要作为"事实"传入而非被重新加工，关键实体保留更稳定；</li>
 *   <li>摘要消息位于 messages[0]，后续触发时跳过不再重复压缩。</li>
 * </ul>
 *
 * <p><b>⑤ 工具结果压缩</b>：对 {@link ToolResponseMessage} 中超长的 responseData
 * 做首尾保留截断，直击 tool result 常为 token 大头的场景。
 *
 * <p>用法：作为 ReactAgent 的前置节点，注册进 StateGraph：
 * <pre>
 *   START → incremental_context_compress → react_agent → END
 * </pre>
 * 注意需在 KeyStrategyFactory 中注册 {@code context_summary} 键。
 */
public class IncrementalContextCompressionNode implements NodeAction {

    private static final Logger logger = LoggerFactory.getLogger(IncrementalContextCompressionNode.class);

    /** state 中保存运行摘要的键名 */
    public static final String SUMMARY_KEY = "context_summary";
    /** 摘要 SystemMessage 的前缀，用于识别 messages[0] 处已存在的摘要消息 */
    public static final String SUMMARY_PREFIX = "【历史对话摘要】";

    private static final int DEFAULT_MAX_MESSAGES = 20;
    private static final int DEFAULT_KEEP_RECENT = 6;
    private static final int DEFAULT_COMPRESS_BATCH = 10;
    private static final int DEFAULT_MAX_TOOL_RESULT_CHARS = 800;
    private static final int DEFAULT_MAX_SUMMARY_CHARS = 600;

    private final ChatClient chatClient;
    private final int maxMessages;
    private final int keepRecentCount;
    private final int compressBatch;
    private final int maxToolResultChars;
    private final int maxSummaryChars;

    public IncrementalContextCompressionNode(ChatClient chatClient) {
        this(chatClient, DEFAULT_MAX_MESSAGES, DEFAULT_KEEP_RECENT, DEFAULT_COMPRESS_BATCH,
                DEFAULT_MAX_TOOL_RESULT_CHARS, DEFAULT_MAX_SUMMARY_CHARS);
    }

    public IncrementalContextCompressionNode(ChatClient chatClient, int maxMessages, int keepRecentCount,
                                             int compressBatch, int maxToolResultChars, int maxSummaryChars) {
        this.chatClient = chatClient;
        this.maxMessages = maxMessages;
        this.keepRecentCount = keepRecentCount;
        this.compressBatch = compressBatch;
        this.maxToolResultChars = maxToolResultChars;
        this.maxSummaryChars = maxSummaryChars;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        List<Message> messages = castMessages(state.value("messages").orElse(List.of()));
        if (messages.isEmpty()) {
            return Map.of();
        }

        List<Message> processed = new ArrayList<>(messages);
        boolean changed = false;

        // ⑤ 工具结果压缩：截断超长 ToolResponseMessage 的 responseData
        changed |= compressToolResults(processed);

        // ① 增量滚动摘要：messages 超阈值时，把"已有摘要 + 至多 compressBatch 条早期消息"合并为更新摘要
        if (processed.size() > maxMessages) {
            String oldSummary = state.value(SUMMARY_KEY, String.class).orElse("");
            int compressEnd = processed.size() - keepRecentCount;
            if (compressEnd > 0) {
                // messages[0] 若已是摘要消息则跳过（摘要本身不再被压缩）
                int startIdx = isSummaryMessage(processed.get(0)) ? 1 : 0;
                int batchEnd = Math.min(compressEnd, startIdx + compressBatch);
                if (batchEnd > startIdx) {
                    List<Message> batch = processed.subList(startIdx, batchEnd);
                    String newSummary = summarize(oldSummary, batch);

                    List<Message> compressed = new ArrayList<>();
                    compressed.add(new SystemMessage(SUMMARY_PREFIX + newSummary));
                    compressed.addAll(processed.subList(batchEnd, processed.size()));
                    logger.info("IncrementalContextCompressionNode: incremental compress, batch={}, "
                                    + "messages {} -> {}, summary {} chars",
                            batch.size(), processed.size(), compressed.size(), newSummary.length());
                    return Map.of("messages", compressed, SUMMARY_KEY, newSummary);
                }
            }
        }

        return changed ? Map.of("messages", processed) : Map.of();
    }

    /**
     * ⑤ 工具结果压缩：对每个超长 ToolResponse 的 responseData 做首尾保留截断。
     *
     * @return 是否有消息被截断
     */
    private boolean compressToolResults(List<Message> messages) {
        boolean changed = false;
        for (int i = 0; i < messages.size(); i++) {
            Message m = messages.get(i);
            if (!(m instanceof ToolResponseMessage toolMsg)) {
                continue;
            }
            List<ToolResponseMessage.ToolResponse> responses = toolMsg.getResponses();
            boolean anyTruncated = false;
            List<ToolResponseMessage.ToolResponse> newResponses = new ArrayList<>();
            for (ToolResponseMessage.ToolResponse r : responses) {
                String data = r.responseData();
                if (data != null && data.length() > maxToolResultChars) {
                    newResponses.add(new ToolResponseMessage.ToolResponse(r.id(), r.name(), truncateMiddle(data)));
                    anyTruncated = true;
                } else {
                    newResponses.add(r);
                }
            }
            if (anyTruncated) {
                messages.set(i, new ToolResponseMessage(newResponses));
                changed = true;
            }
        }
        return changed;
    }

    /**
     * 增量摘要：把新片段合并进已有摘要。
     * 首次（无已有摘要）使用"从零提炼"Prompt；
     * 之后使用"仅输出新增事实"的差异 Prompt，并在代码中将新增事实追加进已有摘要——
     * 相比"每次重写完整摘要"，显著降低输出 token 膨胀（实测 -61%，见 docs/08 §6.6.4）。
     */
    private String summarize(String oldSummary, List<Message> batch) {
        StringBuilder historyText = new StringBuilder();
        if (!oldSummary.isEmpty()) {
            historyText.append("【已有摘要】\n").append(oldSummary).append("\n\n");
        }
        for (Message msg : batch) {
            historyText.append("[").append(msg.getMessageType().getValue()).append("]: ")
                    .append(msg.getText()).append("\n");
        }

        String systemPrompt;
        if (oldSummary.isEmpty()) {
            systemPrompt = "请用 3-5 句话提炼以下对话历史的关键信息，"
                    + "保留用户身份、已办理/查询的服务名称、关键数据（记录编号、时间偏好等）：";
        } else {
            systemPrompt = "你已有一段历史对话摘要。请从新对话片段中提取【新增】的关键事实"
                    + "（记录编号、服务名称、时间偏好、办理状态等），每行一条。"
                    + "不要重复已有摘要中已出现的内容，不要输出完整摘要，只输出新增事实。";
        }

        String content = chatClient.prompt()
                .system(systemPrompt)
                .user(historyText.toString())
                .call()
                .content();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("summary generation returned blank content");
        }
        // 增量差异：把新增事实追加进已有摘要；首次则直接作为摘要
        String summary = oldSummary.isEmpty() ? content : (oldSummary + "\n" + content).trim();
        // 限制摘要长度，防止运行摘要无限膨胀
        if (summary.length() > maxSummaryChars) {
            summary = summary.substring(0, maxSummaryChars) + "…";
        }
        return summary;
    }

    private boolean isSummaryMessage(Message m) {
        if (!(m instanceof SystemMessage)) {
            return false;
        }
        String text = m.getText();
        return text != null && text.startsWith(SUMMARY_PREFIX);
    }

    /** 首尾保留截断：保留前一半与后一半，中间以省略说明替换 */
    private String truncateMiddle(String text) {
        int keep = maxToolResultChars / 2;
        return text.substring(0, keep)
                + "\n…[工具返回已截断，原长 " + text.length() + " 字符]…\n"
                + text.substring(text.length() - keep);
    }

    @SuppressWarnings("unchecked")
    private List<Message> castMessages(Object obj) {
        if (obj instanceof List<?>) {
            return (List<Message>) obj;
        }
        return new ArrayList<>();
    }
}
