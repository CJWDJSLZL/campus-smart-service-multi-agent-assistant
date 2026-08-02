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

    public OrderAgentDebugController(@Qualifier("orderSubAgentBean") ReactAgent orderSubAgent) {
        this.orderSubAgent = orderSubAgent;
    }

    /**
     * Debug 接口，支持三种运行模式：
     * <ul>
     *   <li>{@code mode=react}（默认）：标准 ReactAgent，启用 MemorySaver Checkpoint</li>
     *   <li>{@code mode=plan}：Plan-and-Execute 三节点 Graph（PlannerNode + ExecutorNode + SynthesizerNode）</li>
     *   <li>{@code mode=memory}：带 Memory 主动注入的 StateGraph（MemoryInjectNode + ReactAgent）</li>
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
        } else {
            // 默认：ReactAgent + MemorySaver Checkpoint
            logger.info("Debug mode: react (checkpoint enabled, chat_id={})", chatId);
            graph = orderSubAgent.getAndCompileGraph();
        }

        Flux<NodeOutput> result = graph.fluxStream(input, runnableConfig);
        processStream(result, sink);

        return sink.asFlux()
                .doOnCancel(() -> logger.info("Client disconnected from stream"))
                .doOnError(e -> logger.error("Error occurred during streaming", e));
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
}
