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

import com.alibaba.cloud.ai.agent.nacos.NacosAgentPromptBuilderFactory;
import com.alibaba.cloud.ai.agent.nacos.NacosOptions;
import com.alibaba.cloud.ai.common.node.MemoryInjectNode;
import com.alibaba.cloud.ai.demo.tools.ConsultTools;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.constant.SaverEnum;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.*;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

@Configuration
public class ConsultAgent {
    private static final Logger logger = LoggerFactory.getLogger(ConsultAgent.class);

	@Autowired
	private AgentPromptConfig promptConfig;

	@Autowired
	private ConsultTools consultTools;

	NacosOptions nacosOptions;

    ToolCallbackProvider toolsProvider;

	public ConsultAgent(NacosOptions nacosOptions) {
		this.nacosOptions = nacosOptions;
	}

    @Bean
    public ReactAgent consultSubAgentBean(//@Qualifier("openAiChatModel") ChatModel chatModel,
										  @Qualifier("dashscopeChatModel") ChatModel chatModel,
                                          @Autowired(required = false)
										  @Qualifier("loadbalancedMcpSyncToolCallbacks")
										  ToolCallbackProvider toolsProvider) throws Exception {
		this.toolsProvider = toolsProvider;

		KeyStrategyFactory stateFactory = () -> {
			HashMap<String, KeyStrategy> keyStrategyHashMap = new HashMap<>();
			keyStrategyHashMap.put("messages", new ReplaceStrategy());
			return keyStrategyHashMap;
		};

		// add tools from mcp servers
        List<ToolCallback> tools = new ArrayList<>();
		for (ToolCallback toolCallback : toolsProvider.getToolCallbacks()) {
			String toolName = toolCallback.getToolDefinition().name();
			logger.info("consult_agent add mcp tool name: " + toolName);
			tools.add(toolCallback);
		}

		// add local tools
		MethodToolCallbackProvider localToolsProvider = MethodToolCallbackProvider.builder()
				.toolObjects(consultTools)
				.build();
		for (ToolCallback toolCallback : localToolsProvider.getToolCallbacks()) {
			logger.info("consult_agent add local tool name: " + toolCallback.getToolDefinition().name());
			tools.add(toolCallback);
		}

		logger.info("consult_agent add tools: " + tools.size());
		logger.info("nacos options info: " + nacosOptions.toString());

		// 使用 MemorySaver 实现会话状态持久化，同一 chat_id 的多轮对话状态可跨请求恢复
		var saver = new MemorySaver();
		var compileConfig = CompileConfig.builder()
				.saverConfig(SaverConfig.builder().register(SaverEnum.MEMORY.getValue(), saver).build())
				.build();

		return ReactAgent
				//.builder(new NacosAgentPromptBuilderFactory(nacosOptions))
				.builder()
				.compileConfig(compileConfig)
				.name("consult_agent")
				.model(chatModel)
				.state(stateFactory)
				.description("处理校园政策、办事流程、通知公告和服务事项咨询，支持基于用户记忆的个性化说明")
				.instruction(promptConfig.getConsultAgentInstruction())
				.inputKey("messages")
				.outputKey("messages")
				.tools(tools)
				.build();
	}

	/**
	 * Memory 主动注入 Graph（咨询智能体）。
	 *
	 * <p>与 order-sub-agent 对称实现：START → memory_inject → react_agent → END。
	 * 通过 Debug 接口 {@code ?mode=memory} 触发，展示 Memory 主动注入知识点。
	 */
	@Bean("consultAgentWithMemory")
	public CompiledGraph consultAgentWithMemory(
			@Qualifier("consultSubAgentBean") ReactAgent reactAgent,
			@Autowired(required = false)
			@Qualifier("loadbalancedMcpSyncToolCallbacks")
			ToolCallbackProvider nacosToolsProvider) throws Exception {

		ToolCallback memorySearchCb = null;
		if (nacosToolsProvider != null) {
			memorySearchCb = Arrays.stream(nacosToolsProvider.getToolCallbacks())
					.filter(t -> "memory-search".equals(t.getToolDefinition().name()))
					.findFirst()
					.orElse(null);
		}

		MemoryInjectNode memoryInjectNode = new MemoryInjectNode(memorySearchCb);
		KeyStrategyFactory factory = () -> {
			HashMap<String, KeyStrategy> m = new HashMap<>();
			m.put("messages", new ReplaceStrategy());
			m.put("user_id", new ReplaceStrategy());
			return m;
		};

		return new StateGraph("consult_agent_with_memory", factory)
				.addNode("memory_inject", node_async(memoryInjectNode))
				.addNode("react_agent", node_async(state -> {
					Map<String, Object> agentInput = new HashMap<>();
					agentInput.put("messages", state.value("messages").orElse(List.of()));
					return reactAgent.execute(agentInput);
				}))
				.addEdge(START, "memory_inject")
				.addEdge("memory_inject", "react_agent")
				.addEdge("react_agent", END)
				.compile();
	}
}
