package com.alibaba.cloud.ai.demo.config.scheduling;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * L1 集成验证 V-09 / H-2：
 * BeanOutputConverter 保证评测结果可被程序解析。
 * 验证合法 JSON 被解析为结构化结果，非法输出触发降级逻辑（流程不中断）。
 */
@ExtendWith(MockitoExtension.class)
class EvaluationClassifierNodeTest {

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec responseSpec;
    private ChatResponse chatResponse;
    private OverAllState state;

    private final String inputTextKey = "iterator_item";
    private final String outputKey = "session_analysis_result";

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        responseSpec = mock(ChatClient.CallResponseSpec.class);
        chatResponse = mock(ChatResponse.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.chatResponse()).thenReturn(chatResponse);

        state = mock(OverAllState.class);
        when(state.value(inputTextKey)).thenReturn(Optional.of("用户评价记录：等待时间太长，奶茶都凉了。"));
        when(state.value("messages")).thenReturn(Optional.empty());
    }

    private EvaluationClassifierNode buildNode() {
        return EvaluationClassifierNode.builder()
                .chatClient(chatClient)
                .inputTextKey(inputTextKey)
                .categories(List.of("yes", "no"))
                .classificationInstructions(List.of("result only JSON"))
                .outputKey(outputKey)
                .build();
    }

    private void stubRawOutput(String raw) {
        Generation generation = mock(Generation.class);
        AssistantMessage message = new AssistantMessage(raw);
        when(generation.getOutput()).thenReturn(message);
        when(chatResponse.getResult()).thenReturn(generation);
    }

    @Test
    void 合法JSON被解析为结构化结果_V09() throws Exception {
        stubRawOutput("{\"user\":\"10001\",\"time\":\"2025-06-01\",\"complaint\":\"yes\",\"satisfaction\":2,\"summary\":\"等待时间太长\"}");

        Map<String, Object> result = buildNode().apply(state);

        String out = (String) result.get(outputKey);
        JsonObject json = JsonParser.parseString(out).getAsJsonObject();
        // 结构化字段可被程序直接读取
        assertThat(json.get("complaint").getAsString()).isEqualTo("yes");
        assertThat(json.get("satisfaction").getAsInt()).isEqualTo(2);
        assertThat(json.get("user").getAsString()).isEqualTo("10001");
    }

    @Test
    void 非法输出触发降级不中断流程() throws Exception {
        stubRawOutput("抱歉，我无法按格式输出");

        Map<String, Object> result = buildNode().apply(state);

        // 降级：输出键直接保存原始文本，主流程不中断
        assertThat(result.get(outputKey)).isEqualTo("抱歉，我无法按格式输出");
    }
}
