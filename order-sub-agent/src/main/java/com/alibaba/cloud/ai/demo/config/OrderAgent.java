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

package com.alibaba.cloud.ai.demo.config;

import com.alibaba.cloud.ai.demo.node.ExecutorNode;
import com.alibaba.cloud.ai.common.node.MemoryInjectNode;
import com.alibaba.cloud.ai.demo.node.PlannerNode;
import com.alibaba.cloud.ai.demo.node.SynthesizerNode;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.constant.SaverEnum;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.RedisSaver;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.*;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

@Configuration
public class OrderAgent {
    private static final Logger logger = LoggerFactory.getLogger(OrderAgent.class);

	@Autowired
	private OrderAgentPromptConfig promptConfig;

    ToolCallbackProvider toolsProvider;

    @Bean
    public ReactAgent orderSubAgentBean(//@Qualifier("openAiChatModel") ChatModel chatModel,
										@Qualifier("dashscopeChatModel") ChatModel chatModel,
                                        @Autowired(required = false) @Qualifier("mcpToolCallbacks")
								        ToolCallbackProvider toolsProvider,
										@Autowired(required = false) @Qualifier("loadbalancedMcpSyncToolCallbacks")
										ToolCallbackProvider nacosToolsProvider) throws Exception {
		this.toolsProvider = toolsProvider;

		KeyStrategyFactory stateFactory = () -> {
			HashMap<String, KeyStrategy> keyStrategyHashMap = new HashMap<>();
			keyStrategyHashMap.put("messages", new ReplaceStrategy());
			return keyStrategyHashMap;
		};

		List<ToolCallback> tools = new ArrayList<>();
		for (ToolCallback toolCallback : toolsProvider.getToolCallbacks()) {
			logger.info("order_agent add tool from sse: " + toolCallback.getToolDefinition().name());
			tools.add(toolCallback);
		}

		for (ToolCallback toolCallback : nacosToolsProvider.getToolCallbacks()) {
			logger.info("order_agent add tool from nacos: " + toolCallback.getToolDefinition().name());
			tools.add(toolCallback);
		}

		//var saver = new RedisSaver();
		//var compileConfig = CompileConfig.builder()
		//		.saverConfig(SaverConfig.builder().register(SaverEnum.REDIS.getValue(), saver).build())
		//		.build();

		// 使用 MemorySaver 实现 JVM 内会话状态持久化：相同 chat_id（threadId）的多轮对话
		// 状态会被保存并在下一轮自动恢复，实现断点续跑能力
		var saver = new MemorySaver();
		var compileConfig = CompileConfig.builder()
				.saverConfig(SaverConfig.builder().register(SaverEnum.MEMORY.getValue(), saver).build())
				.build();

		logger.info("order_agent add tools: " + tools.size());
		return ReactAgent.builder()
				.compileConfig(compileConfig)
				.name("order_agent")
				.model(chatModel)
				.state(stateFactory)
				.description("处理校园事务办理、预约申请、办理记录查询、备注修改和记录取消")
				.instruction(promptConfig.getOrderAgentInstruction())
				.inputKey("messages")
				.outputKey("messages")
				.tools(tools)
				.build();
	}

	/**
	 * Memory 主动注入 Graph：演示在 ReactAgent 前置一个 MemoryInjectNode 的 StateGraph 包装模式。
	 *
	 * <p>流程：START → memory_inject（主动加载用户历史偏好注入 SystemMessage）
	 *              → react_agent（原 ReactAgent 使用注入后的上下文处理请求）→ END
	 *
	 * <p>此 Bean 通过 Debug 接口（?mode=memory）暴露，展示 Memory 主动注入知识点；
	 * A2A 服务注册仍使用原 orderSubAgentBean。
	 */
	@Bean("orderAgentWithMemory")
	public CompiledGraph orderAgentWithMemory(
			@Qualifier("orderSubAgentBean") ReactAgent reactAgent,
			@Autowired(required = false) @Qualifier("loadbalancedMcpSyncToolCallbacks")
			ToolCallbackProvider nacosToolsProvider) throws Exception {

		ToolCallback memorySearchCb = null;
		if (nacosToolsProvider != null) {
			memorySearchCb = Arrays.stream(nacosToolsProvider.getToolCallbacks())
					.filter(t -> "memory-search".equals(t.getToolDefinition().name()))
					.findFirst()
					.orElse(null);
		}
		if (memorySearchCb != null) {
			logger.info("orderAgentWithMemory: memory-search tool found, injection enabled");
		} else {
			logger.warn("orderAgentWithMemory: memory-search tool not found, injection disabled");
		}

		final ToolCallback finalMemorySearchCb = memorySearchCb;
		MemoryInjectNode memoryInjectNode = new MemoryInjectNode(finalMemorySearchCb);

		KeyStrategyFactory factory = () -> {
			HashMap<String, KeyStrategy> m = new HashMap<>();
			m.put("messages", new ReplaceStrategy());
			m.put("user_id", new ReplaceStrategy());
			return m;
		};

		return new StateGraph("order_agent_with_memory", factory)
				.addNode("memory_inject", node_async(memoryInjectNode))
				.addNode("react_agent", node_async(state -> {
					// 将注入后的 messages 传入原 ReactAgent 处理
					Map<String, Object> agentInput = new HashMap<>();
					agentInput.put("messages", state.value("messages").orElse(List.of()));
					// graph-core 1.0.0.4: ReactAgent.execute(Map) 已移除，改用 CompiledGraph.invoke
					return reactAgent.getCompiledGraph().invoke(agentInput)
							.map(com.alibaba.cloud.ai.graph.OverAllState::data)
							.orElse(Map.of());
				}))
				.addEdge(START, "memory_inject")
				.addEdge("memory_inject", "react_agent")
				.addEdge("react_agent", END)
				.compile();
	}

	/**
	 * Plan-and-Execute Graph：先规划后执行的三节点 StateGraph。
	 *
	 * <p>流程：START → planner（LLM 生成结构化 ExecutionPlan）
	 *              → executor（按计划步骤逐一调用 MCP 工具）
	 *              → synthesizer（汇总结果生成自然语言回答）→ END
	 *
	 * <p>与 ReactAgent 的 ReAct 循环相比：
	 * - PlannerNode 使用 BeanOutputConverter 生成类型化计划（结构化输出知识点）
	 * - ExecutorNode 确定性执行每个步骤（无动态推理循环）
	 * - 通过 Debug 接口 {@code ?mode=plan} 触发，A2A 服务注册仍使用原 orderSubAgentBean
	 */
	@Bean("planAndExecuteOrderGraph")
	public CompiledGraph planAndExecuteOrderGraph(
			@Qualifier("dashscopeChatModel") ChatModel chatModel,
			@Autowired(required = false) @Qualifier("mcpToolCallbacks")
			ToolCallbackProvider toolsProvider,
			@Autowired(required = false) @Qualifier("loadbalancedMcpSyncToolCallbacks")
			ToolCallbackProvider nacosToolsProvider) throws Exception {

		List<ToolCallback> tools = new ArrayList<>();
		if (toolsProvider != null) {
			for (ToolCallback toolCallback : toolsProvider.getToolCallbacks()) {
				tools.add(toolCallback);
			}
		}
		if (nacosToolsProvider != null) {
			for (ToolCallback toolCallback : nacosToolsProvider.getToolCallbacks()) {
				tools.add(toolCallback);
			}
		}
		logger.info("planAndExecuteOrderGraph: loaded {} tools", tools.size());

		ChatClient chatClient = ChatClient.builder(chatModel).build();

		KeyStrategyFactory factory = () -> {
			HashMap<String, KeyStrategy> m = new HashMap<>();
			m.put("messages", new ReplaceStrategy());
			m.put("execution_plan", new ReplaceStrategy());
			m.put("step_results", new ReplaceStrategy());
			m.put("clarification_question", new ReplaceStrategy());
			return m;
		};

		return new StateGraph("plan_and_execute_order", factory)
				.addNode("planner",     node_async(new PlannerNode(chatClient)))
				.addNode("executor",    node_async(new ExecutorNode(tools)))
				.addNode("synthesizer", node_async(new SynthesizerNode(chatClient)))
				.addEdge(START, "planner")
				.addEdge("planner", "executor")
				.addEdge("executor", "synthesizer")
				.addEdge("synthesizer", END)
				.compile();
	}

	/**
	 * Human-in-the-Loop Graph：在 executor 节点前插入中断点，等待用户二次确认。
	 *
	 * <p>流程：
	 * <pre>
	 *   第一次请求（生成计划）：
	 *     START → planner → [INTERRUPT before executor] → 返回计划摘要给前端
	 *
	 *   用户确认后（第二次请求，相同 chat_id）：
	 *     resume → executor → synthesizer → END → 返回执行结果
	 * </pre>
	 *
	 * <p>通过 {@code CompileConfig.interruptBefore("executor")} 实现中断，
	 * 结合 {@code MemorySaver} 持久化中断状态，通过相同 {@code threadId} 恢复执行。
	 * 通过 Debug 接口 {@code ?mode=hitl} 触发第一步，
	 * 通过 {@code /confirm} 端点触发第二步（resume）。
	 */
	@Bean("planAndExecuteHitlGraph")
	public CompiledGraph planAndExecuteHitlGraph(
			@Qualifier("dashscopeChatModel") ChatModel chatModel,
			@Autowired(required = false) @Qualifier("mcpToolCallbacks")
			ToolCallbackProvider toolsProvider,
			@Autowired(required = false) @Qualifier("loadbalancedMcpSyncToolCallbacks")
			ToolCallbackProvider nacosToolsProvider) throws Exception {

		List<ToolCallback> tools = new ArrayList<>();
		if (toolsProvider != null) {
			for (ToolCallback toolCallback : toolsProvider.getToolCallbacks()) {
				tools.add(toolCallback);
			}
		}
		if (nacosToolsProvider != null) {
			for (ToolCallback toolCallback : nacosToolsProvider.getToolCallbacks()) {
				tools.add(toolCallback);
			}
		}
		logger.info("planAndExecuteHitlGraph: loaded {} tools", tools.size());

		ChatClient chatClient = ChatClient.builder(chatModel).build();

		KeyStrategyFactory factory = () -> {
			HashMap<String, KeyStrategy> m = new HashMap<>();
			m.put("messages", new ReplaceStrategy());
			m.put("execution_plan", new ReplaceStrategy());
			m.put("step_results", new ReplaceStrategy());
			m.put("clarification_question", new ReplaceStrategy());
			return m;
		};

		// MemorySaver 持久化中断状态，使 resume 时能从 executor 继续执行
		var saver = new MemorySaver();
		var compileConfig = CompileConfig.builder()
				.saverConfig(SaverConfig.builder()
						.register(SaverEnum.MEMORY.getValue(), saver).build())
				.interruptBefore("executor")   // executor 前暂停，等待用户确认
				.build();

		return new StateGraph("plan_and_execute_hitl", factory)
				.addNode("planner",     node_async(new PlannerNode(chatClient)))
				.addNode("executor",    node_async(new ExecutorNode(tools)))
				.addNode("synthesizer", node_async(new SynthesizerNode(chatClient)))
				.addEdge(START, "planner")
				.addEdge("planner", "executor")
				.addEdge("executor", "synthesizer")
				.addEdge("synthesizer", END)
				.compile(compileConfig);
	}
}