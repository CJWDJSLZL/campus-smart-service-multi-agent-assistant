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

package com.alibaba.cloud.ai.demo.controller;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;

@RequestMapping("/api/order-sub-agent/")
@RestController
public class OrderAgentDebugController {

    private static final Logger logger = LoggerFactory.getLogger(OrderAgentDebugController.class);
    private final ReactAgent orderSubAgent;

    @Autowired(required = false)
    @Qualifier("planAndExecuteOrderGraph")
    private CompiledGraph planAndExecuteGraph;

    @Autowired(required = false)
    @Qualifier("orderAgentWithMemory")
    private CompiledGraph orderAgentWithMemory;

    @Autowired(required = false)
    @Qualifier("planAndExecuteHitlGraph")
    private CompiledGraph hitlGraph;

    public OrderAgentDebugController(@Qualifier("orderSubAgentBean") ReactAgent orderSubAgent) {
        this.orderSubAgent = orderSubAgent;
    }

    /**
     * Debug 接口，支持四种运行模式：
     * <ul>
     *   <li>{@code mode=react}（默认）：标准 ReactAgent，启用 MemorySaver Checkpoint</li>
     *   <li>{@code mode=plan}：Plan-and-Execute 三节点 Graph（PlannerNode + ExecutorNode + SynthesizerNode）</li>
     *   <li>{@code mode=memory}：带 Memory 主动注入的 StateGraph（MemoryInjectNode + ReactAgent）</li>
     *   <li>{@code mode=hitl}：Human-in-the-Loop，planner 生成计划后暂停，等待 /confirm 确认后再执行</li>
     * </ul>
     */
    @RequestMapping(path="/debug", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(
            @RequestParam(name = "user_query") String userQuery,
            @RequestParam(name = "chat_id", defaultValue = "debug-session") String chatId,
            @RequestParam(name = "mode", defaultValue = "react") String mode) throws Exception {

        RunnableConfig runnableConfig = RunnableConfig.builder().threadId(chatId).build();
        Map<String, Object> input = Map.of("messages", List.of(new UserMessage(userQuery)));
        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().unicast().onBackpressureBuffer();

        CompiledGraph graph;
        if ("plan".equals(mode) && planAndExecuteGraph != null) {
            logger.info("Debug mode: plan-and-execute");
            graph = planAndExecuteGraph;
        } else if ("memory".equals(mode) && orderAgentWithMemory != null) {
            logger.info("Debug mode: memory injection");
            graph = orderAgentWithMemory;
        } else if ("hitl".equals(mode) && hitlGraph != null) {
            logger.info("Debug mode: human-in-the-loop (step 1: plan, chat_id={})", chatId);
            graph = hitlGraph;
        } else {
            // 默认：ReactAgent + MemorySaver Checkpoint
            logger.info("Debug mode: react (checkpoint enabled, chat_id={})", chatId);
            graph = orderSubAgent.getAndCompileGraph();
        }

        Flux<NodeOutput> result = graph.fluxStream(input, runnableConfig);

        // 多轮澄清 Loop：检测 PlannerNode 输出的 clarification_question
        // 若存在则直接返回澄清问题作为 SSE 流，不进入 executor
        processStreamWithClarification(result, sink);

        return sink.asFlux()
                .doOnCancel(() -> logger.info("Client disconnected from stream"))
                .doOnError(e -> logger.error("Error occurred during streaming", e));
    }

    /**
     * Human-in-the-Loop 确认端点。
     *
     * <p>在 {@code mode=hitl} 的 /debug 调用后，planner 生成计划并暂停于 executor 前。
     * 前端展示计划内容，用户点击确认后调用此端点 resume 执行：
     * <ul>
     *   <li>{@code action=approve}：继续执行（resume），executor → synthesizer → END</li>
     *   <li>{@code action=reject}：拒绝执行，返回取消提示</li>
     * </ul>
     */
    @RequestMapping(path="/confirm", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> confirm(
            @RequestParam(name = "chat_id") String chatId,
            @RequestParam(name = "action", defaultValue = "approve") String action) {

        if (hitlGraph == null) {
            return Flux.just(ServerSentEvent.builder("HITL Graph 未初始化").build());
        }

        if ("reject".equals(action)) {
            logger.info("HITL: user rejected plan (chat_id={})", chatId);
            return Flux.just(ServerSentEvent.builder("操作已取消，如需重新办理请重新发起请求。").build());
        }

        logger.info("HITL: user approved plan, resuming executor (chat_id={})", chatId);
        RunnableConfig runnableConfig = RunnableConfig.builder().threadId(chatId).build();
        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().unicast().onBackpressureBuffer();

        // 传入空 input，graph 从 checkpoint 恢复状态后从 executor 继续执行
        Flux<NodeOutput> result = hitlGraph.fluxStream(Map.of(), runnableConfig);
        processStream(result, sink);

        return sink.asFlux()
                .doOnCancel(() -> logger.info("Client disconnected from confirm stream"))
                .doOnError(e -> logger.error("Error in confirm stream", e));
    }

    public void processStream(Flux<NodeOutput> generator, Sinks.Many<ServerSentEvent<String>> sink) {
        generator
            .doOnNext(output -> logger.info("output = {}", output))
            .filter(output -> "llm".equals(output.node()) && output instanceof StreamingOutput)
            .cast(StreamingOutput.class)
            .filter(streamingOutput -> {
                String chunk = streamingOutput.chunk();
                return chunk != null && !chunk.trim().isEmpty();
            })
            .map(StreamingOutput::chunk)
            .map(content -> ServerSentEvent.builder(content).build())
            .doOnNext(sink::tryEmitNext)
            .doOnError(e -> {
                logger.error("Unexpected error in stream processing: {}", e.getMessage(), e);
                sink.tryEmitNext(ServerSentEvent.builder("系统处理出现错误，请稍后重试。").build());
            })
            .doOnComplete(() -> {
                logger.info("Stream processing completed successfully");
                sink.tryEmitComplete();
            })
            .subscribe(
                // onNext - 已经在doOnNext中处理
                null,
                // onError
                e -> {
                    logger.error("Stream processing failed: {}", e.getMessage(), e);
                    sink.tryEmitError(e);
                }
            );
    }

    /**
     * 多轮澄清 Loop 感知的流处理。
     *
     * <p>在标准 LLM 流输出基础上，额外监听 planner 节点的 OverAllState，
     * 若检测到 {@code clarification_question} 非空，说明 PlannerNode 判断信息不足，
     * 将澄清问题作为 SSE 输出直接返回，前端接收后展示给用户。
     * 用户补充信息后重新发起请求，形成多轮澄清循环。
     */
    public void processStreamWithClarification(Flux<NodeOutput> generator, Sinks.Many<ServerSentEvent<String>> sink) {
        generator
            .doOnNext(output -> {
                logger.info("output = {}", output);
                // 检测 planner 节点是否输出了澄清问题
                if ("planner".equals(output.node()) && !(output instanceof StreamingOutput)) {
                    try {
                        Object stateObj = output.state();
                        if (stateObj instanceof com.alibaba.cloud.ai.graph.OverAllState overAllState) {
                            String clarification = (String) overAllState.value("clarification_question").orElse(null);
                            if (clarification != null && !clarification.isBlank()) {
                                logger.info("Clarification needed: {}", clarification);
                                sink.tryEmitNext(ServerSentEvent.builder(clarification).build());
                                sink.tryEmitComplete();
                            }
                        }
                    } catch (Exception ignored) { /* 反射读取失败时静默忽略 */ }
                }
            })
            .filter(output -> "llm".equals(output.node()) && output instanceof StreamingOutput)
            .cast(StreamingOutput.class)
            .filter(streamingOutput -> {
                String chunk = streamingOutput.chunk();
                return chunk != null && !chunk.trim().isEmpty();
            })
            .map(StreamingOutput::chunk)
            .map(content -> ServerSentEvent.builder(content).build())
            .doOnNext(sink::tryEmitNext)
            .doOnError(e -> {
                logger.error("Unexpected error in stream processing: {}", e.getMessage(), e);
                sink.tryEmitNext(ServerSentEvent.builder("系统处理出现错误，请稍后重试。").build());
            })
            .doOnComplete(() -> {
                logger.info("Stream processing completed successfully");
                sink.tryEmitComplete();
            })
            .subscribe(null, e -> {
                logger.error("Stream processing failed: {}", e.getMessage(), e);
                sink.tryEmitError(e);
            });
    }
}
