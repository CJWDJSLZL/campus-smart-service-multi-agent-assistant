package com.alibaba.cloud.ai.common.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 增量滚动摘要 + 工具结果压缩节点的行为验证：
 * ① 首次生成摘要、增量更新、批量上限、摘要消息不重复压缩；
 * ⑤ 超长工具返回截断。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IncrementalContextCompressionNodeTest {

    private static final int MAX_MESSAGES = 20;
    private static final int KEEP_RECENT = 6;
    private static final int BATCH = 10;

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec responseSpec;
    private IncrementalContextCompressionNode node;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        responseSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("增量摘要：奖学金申请需绩点达标，已预约研讨间，记录编号 CAMPUS_0001。");

        node = new IncrementalContextCompressionNode(chatClient, MAX_MESSAGES, KEEP_RECENT, BATCH, 800, 2000);
    }

    private List<Message> buildMessages(int count) {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            messages.add(new UserMessage("第" + i + "轮：请问奖学金申请条件？"));
            messages.add(new AssistantMessage("第" + i + "轮回答：需GPA达标，记录编号 CAMPUS_" + (1000 + i)));
        }
        return messages;
    }

    private OverAllState mockState(List<Message> messages, String summary) {
        OverAllState state = mock(OverAllState.class);
        when(state.value("messages")).thenReturn(Optional.of(messages));
        when(state.value(IncrementalContextCompressionNode.SUMMARY_KEY, String.class))
                .thenReturn(Optional.ofNullable(summary));
        return state;
    }

    @Test
    void 首次触发生成摘要并折叠批量() throws Exception {
        List<Message> messages = buildMessages(13); // 26 条 > 20
        Map<String, Object> result = node.apply(mockState(messages, null));

        List<Message> compressed = (List<Message>) result.get("messages");
        // 首条为摘要消息，其余 = 原消息 26 - 折叠 10 条 = 16 条
        assertThat(compressed).hasSize(1 + (26 - BATCH));
        assertThat(compressed.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(compressed.get(0).getText()).startsWith(IncrementalContextCompressionNode.SUMMARY_PREFIX);
        assertThat(result.get(IncrementalContextCompressionNode.SUMMARY_KEY)).isNotNull();
    }

    @Test
    void 增量更新时已有摘要被传入LLM() throws Exception {
        List<Message> messages = buildMessages(13); // 26 条
        String oldSummary = "已有摘要：用户偏好晚上预约，记录编号 CAMPUS_0005。";

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        node.apply(mockState(messages, oldSummary));

        verify(requestSpec, times(1)).user(captor.capture());
        String input = captor.getValue();
        // 增量更新：输入包含已有摘要与批量内容
        assertThat(input).contains("【已有摘要】").contains("CAMPUS_0005").contains("CAMPUS_1000");
    }

    @Test
    void 突发消息时单次LLM输入受批量上限约束() throws Exception {
        List<Message> messages = buildMessages(25); // 50 条，远超阈值
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);

        node.apply(mockState(messages, null));

        verify(requestSpec, times(1)).user(captor.capture());
        String input = captor.getValue();
        // 批量上限 10：应包含第 0 条，不应包含第 10 条之后的内容
        assertThat(input).contains("第0轮：");
        assertThat(input).doesNotContain("第10轮：");
        // 输出消息 = 摘要 1 + 剩余 40
        Map<String, Object> result = node.apply(mockState(messages, null));
        assertThat(((List<?>) result.get("messages"))).hasSize(1 + (50 - BATCH));
    }

    @Test
    void 摘要消息本身不会被重复压缩() throws Exception {
        // 先压缩一次，得到 [摘要, ...]
        List<Message> first = buildMessages(13);
        Map<String, Object> firstResult = node.apply(mockState(first, null));
        List<Message> afterFirst = (List<Message>) firstResult.get("messages");

        // 再追加消息，超过阈值触发第二次压缩
        List<Message> second = new ArrayList<>(afterFirst);
        second.addAll(buildMessages(5)); // +10 条
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);

        // 清除第一次压缩的调用记录，只统计第二次压缩
        org.mockito.Mockito.clearInvocations(requestSpec);
        node.apply(mockState(second, "已有摘要：记录编号 CAMPUS_0001。"));

        verify(requestSpec, times(1)).user(captor.capture());
        String input = captor.getValue();
        // 输入中不应包含摘要 SystemMessage 自身（摘要消息不被重复压缩）
        assertThat(input).doesNotContain("【历史对话摘要】");
    }

    @Test
    void 超长工具返回被首尾截断_工具结果压缩() throws Exception {
        String longData = "A".repeat(2000) + "关键记录编号 CAMPUS_9999";
        ToolResponseMessage toolMsg = new ToolResponseMessage(List.of(
                new ToolResponseMessage.ToolResponse("resp-1", "campus-get-service-records", longData)));
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("查一下我的记录"));
        messages.add(toolMsg); // 2 条，未超阈值，只走工具结果压缩

        Map<String, Object> result = node.apply(mockState(messages, null));

        List<Message> out = (List<Message>) result.get("messages");
        ToolResponseMessage truncated = (ToolResponseMessage) out.get(1);
        String data = truncated.getResponses().get(0).responseData();
        assertThat(data).contains("…[工具返回已截断").contains("CAMPUS_9999");
        assertThat(data.length()).isLessThan(2000);
        assertThat(data.length()).isLessThanOrEqualTo(800 + 64); // 800 上限 + 截断标记
    }

    @Test
    void 未超阈值且无长工具返回时不修改消息() throws Exception {
        List<Message> messages = buildMessages(5); // 10 条
        Map<String, Object> result = node.apply(mockState(messages, null));
        assertThat(result).isEmpty();
    }
}
