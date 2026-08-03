# Agent 相关知识详解

本文档详细介绍项目中涉及的 20 个 Agent 知识点，每个知识点包含概念说明、实现细节和关键代码位置。

---

## 一、多智能体架构模式

### 1.1 Supervisor 路由模式

**概念**：由一个"主 Agent"负责意图识别和任务分发，多个"子 Agent"各司其职处理特定类型任务。主 Agent 只做路由决策，不直接调用业务工具。

**实现位置**：`supervisor-agent/config/SupervisorAgent.java`

**框架类**：`LlmRoutingAgent`（Spring AI Alibaba 提供）

**构建方式**：
```java
return LlmRoutingAgent.builder()
    .name("supervisor_agent")
    .model(chatModel)
    .state(stateFactory)                          // 状态键策略
    .description(promptConfig.getSupervisorAgentInstruction())  // System Prompt
    .inputKey("input")
    .outputKey("messages")
    .subAgents(List.of(consultAgent, feedbackAgent, orderAgent))  // 三个子 Agent
    .build();
```

**状态键**：`input`（用户输入）、`chat_id`（会话ID）、`user_id`（用户ID）、`messages`（对话历史），均使用 `ReplaceStrategy`。

**路由过程**：
1. 用户消息附带 `<userId>xxx</userId>` 标签后进入 Supervisor
2. LLM 分析消息内容，根据 System Prompt 中的路由规则选择子 Agent
3. 通过 A2A 协议调用对应子 Agent，等待响应
4. 将子 Agent 的 SSE 流输出过滤（`filter a2aNode`）后透传给前端

**Few-shot 路由示例**（System Prompt 中注入）：
```
示例1: 用户说"奖学金多少钱" → 路由到 consult_agent（政策咨询）
示例2: 用户说"帮我预约图书馆" → 路由到 order_agent（事务办理）
示例3: 用户说"宿舍空调修了好几天还没来" → 路由到 feedback_agent（投诉）
示例4: 用户说"你好" → 回复问候，不路由任何子 Agent
```

---

### 1.2 ReactAgent 模式（ReAct 循环）

**概念**：基于 ReAct（Reasoning + Acting）框架，LLM 在每一步循环中：
1. **Reasoning**：基于当前状态思考下一步行动
2. **Acting**：决定调用哪个工具（或结束）
3. **Observing**：获取工具结果，更新状态，进入下一轮

**实现位置**：三个子 Agent 的 `config/xxxAgent.java`

**构建方式**：
```java
return ReactAgent.builder()
    .compileConfig(compileConfig)            // Checkpoint 配置
    .name("order_agent")
    .model(chatModel)
    .state(stateFactory)
    .description("处理校园事务办理...")
    .instruction(promptConfig.getOrderAgentInstruction())  // System Prompt
    .inputKey("messages")
    .outputKey("messages")
    .tools(tools)                            // MCP 工具列表
    .build();
```

**工具注册来源**（以 order-sub-agent 为例）：
- SSE 直连：`@Qualifier("mcpToolCallbacks")` ← order-mcp-server、memory-mcp-server
- Nacos 负载均衡：`@Qualifier("loadbalancedMcpSyncToolCallbacks")` ← Nacos 注册的所有工具

---

### 1.3 Graph-based Agent（状态图工作流）

**概念**：使用有向图（DAG）构建多步骤工作流，每个节点是一个 `NodeAction`，节点间通过边传递 `OverAllState`。比 ReactAgent 更具确定性，适合步骤固定的复杂任务。

#### DailyReport Agent（运营日报图）

**实现位置**：`supervisor-agent/config/scheduling/DailyReportAgentConfiguration.java`

```
START → data_loader_node → data_analysis_node → message_sender_node → END
```

| 节点 | 类型 | 说明 |
|------|------|------|
| data_loader_node | AsyncNodeAction | 从 MySQL 拉取最新订单和反馈数据，序列化为文本摘要 |
| data_analysis_node | LlmNode | ChatClient 分析数据趋势，生成运营报告文本 |
| message_sender_node | DingMessageSenderNode | 将报告以 Markdown 格式推送到钉钉群 Webhook |

**关键状态键**：`data_summary`（数据摘要文本）、`summary_message_to_sender`（待发送的报告）、`message_sender_result`（发送结果）

#### Evaluation Agent（用户评价分析图）

**实现位置**：`supervisor-agent/config/scheduling/EvaluationAgentConfiguration.java`

```
START → session_loader → iteration_session_analysis → session_result_summary
                                    │                          │
                                    │           ┌──────────────┴────────────┐
                                    │        avg≥3               avg<3（告警）
                                    │      message_parse      alert_message_parse
                                    │      message_sender     alert_message_sender
                                    └──────────────────────────────────────→ END
```

**子图设计**（IterationNode 内部）：
```
对每条反馈记录独立运行：
START → EvaluationClassifierNode → END
```

`EvaluationClassifierNode` 使用 `BeanOutputConverter<EvaluationResult>` 将 LLM 输出解析为：
```java
public record EvaluationResult(
    String user,       // 用户 ID
    String time,       // 评价时间
    String complaint,  // "yes" | "no"（是否投诉）
    int satisfaction,  // 0-5（满意度）
    String summary     // 核心问题摘要
) {}
```

---

### 1.4 Plan-and-Execute 模式

**概念**：将任务执行分为两个阶段：
1. **Plan 阶段**：LLM 一次性生成完整的结构化执行计划（而非逐步推理）
2. **Execute 阶段**：按计划步骤确定性执行，不再让 LLM 临时决策

**区别**：ReactAgent 每步都让 LLM 决策（动态），Plan-and-Execute 先规划再执行（静态）。

**实现位置**：`order-sub-agent/config/OrderAgent.java`（`planAndExecuteOrderGraph` Bean）

**图结构**：
```
START → PlannerNode → ExecutorNode → SynthesizerNode → END
```

**PlannerNode**（`order-sub-agent/node/PlannerNode.java`）：
```java
// 使用 BeanOutputConverter 约束 LLM 输出为 ExecutionPlan 类型
private final BeanOutputConverter<ExecutionPlan> converter = new BeanOutputConverter<>(ExecutionPlan.class);

// apply() 中：
String planJson = chatClient.prompt()
    .system(PLANNER_SYSTEM_TEMPLATE.replace("{format_instructions}", converter.getFormat()))
    .user(userRequest)
    .call().content();
ExecutionPlan plan = converter.convert(planJson);
```

**ExecutionPlan 数据结构**（`order-sub-agent/model/ExecutionPlan.java`）：
```java
public record ExecutionPlan(
    String goal,                        // 用户目标描述
    boolean needsClarification,         // 信息是否完整（多轮澄清用）
    String clarificationQuestion,       // 需要追问的内容
    List<ExecutionStep> steps           // 执行步骤列表
) {
    public record ExecutionStep(
        int stepNumber,                 // 步骤序号（从1开始）
        String toolName,                // MCP 工具名称
        String description,             // 步骤描述
        String toolParameters           // 工具调用的 JSON 参数字符串
    ) {}
}
```

**ExecutorNode**（`order-sub-agent/node/ExecutorNode.java`）：
- 遍历 `plan.steps()`，按 `toolName` 查找 `ToolCallback` 并调用 `tool.call(toolParameters)`
- 带指数退避 Retry（MAX_RETRY=3，延迟 500ms × retry）
- 每步结果追加到 `step_results` 列表

**SynthesizerNode**（`order-sub-agent/node/SynthesizerNode.java`）：
- 读取 `execution_plan`（目标）和 `step_results`（执行结果）
- 调用 ChatClient 生成自然语言总结
- 将总结追加为 `AssistantMessage` 到 `messages`

---

## 二、工具调用体系

### 2.1 Local Tool Calling（本地工具调用）

**概念**：使用 `@Tool` + `@ToolParam` 注解将普通 Java 方法注册为 Agent 可调用的工具，Spring AI 框架自动生成工具描述 Schema 并处理调用。

**实现位置**：
- `consult-sub-agent/tools/ConsultTools.java`（4 个知识库检索工具）
- `supervisor-agent/tools/CronAgentTools.java`（1 个定时任务注册工具）

**ConsultTools 工具列表**：
| 工具名 | 说明 |
|--------|------|
| `consult-search-knowledge` | RAG 检索校园知识库 |
| `consult-get-products` | 查询所有可用服务事项列表 |
| `consult-get-product-info` | 查询指定服务事项详情 |
| `consult-search-products` | 模糊搜索服务事项 |

**注册方式**：
```java
// ConsultAgent.java 中
MethodToolCallbackProvider localToolsProvider = MethodToolCallbackProvider.builder()
    .toolObjects(consultTools)
    .build();
for (ToolCallback toolCallback : localToolsProvider.getToolCallbacks()) {
    tools.add(toolCallback);
}
```

---

### 2.2 MCP Server（工具服务封装）

**概念**：Model Context Protocol 服务，将业务工具封装为标准化服务，Agent 通过协议调用，不直接访问数据库。

**order-mcp-server 工具清单**（13 个）：
| 工具名 | 说明 | 必填参数 |
|--------|------|---------|
| `campus-create-service-record` | 创建办理/预约记录 | userId, productName, serviceMode, priority |
| `campus-get-service-record` | 按记录编号查询 | orderId |
| `campus-get-service-record-by-user` | 按用户+编号查询（防越权） | userId, orderId |
| `campus-get-service-records-by-user` | 查用户全部记录 | userId |
| `campus-get-latest-service-record-by-user` | 查用户最近一次记录 | userId |
| `campus-query-service-records` | 多条件筛选查询 | userId（必填），其余可选 |
| `campus-cancel-service-record` | 取消记录 | userId, orderId |
| `campus-update-service-record-remark` | 更新备注 | userId, orderId, remark |
| `campus-check-service-capacity` | 检查名额是否充足 | productName, quantity |
| `campus-validate-service-item` | 验证服务是否可用 | productName |
| `campus-get-service-records` | 获取所有记录（管理端） | 无 |

**feedback-mcp-server 工具清单**（4 个）：
| 工具名 | 说明 |
|--------|------|
| `feedback-create-feedback` | 创建反馈/投诉/建议 |
| `feedback-get-feedback-by-user` | 查用户历史反馈 |
| `feedback-get-feedback-by-order` | 查记录关联反馈 |
| `feedback-update-solution` | 更新处理方案 |

**memory-mcp-server 工具清单**（2 个）：
| 工具名 | 说明 |
|--------|------|
| `memory-store` | 存储用户长期偏好（异步） |
| `memory-search` | 语义检索用户历史偏好 |

---

### 2.3 MCP 双模式接入

| 模式 | Bean 名称 | 配置 | 适用场景 |
|------|----------|------|---------|
| SSE 直连 | `mcpToolCallbacks` | `spring.ai.mcp.client.sse.connections` | order-sub-agent 直连 order-mcp-server |
| Nacos 负载均衡 | `loadbalancedMcpSyncToolCallbacks` | `spring.ai.alibaba.mcp.nacos` | 通过 Nacos 发现工具，支持多实例 |

**order-sub-agent 中同时接入两种方式**：
```java
List<ToolCallback> tools = new ArrayList<>();
// SSE 直连工具
for (ToolCallback toolCallback : toolsProvider.getToolCallbacks()) {
    logger.info("order_agent add tool from sse: {}", toolCallback.getToolDefinition().name());
    tools.add(toolCallback);
}
// Nacos 负载均衡工具（包含 memory-search 等）
for (ToolCallback toolCallback : nacosToolsProvider.getToolCallbacks()) {
    logger.info("order_agent add tool from nacos: {}", toolCallback.getToolDefinition().name());
    tools.add(toolCallback);
}
```

---

## 三、Agent 通信协议（A2A）

**概念**：Agent-to-Agent 协议，允许 Agent 像调用微服务一样调用另一个 Agent，保留完整的 Agent 语义（流式输出、任务状态、AgentCard）。

**实现位置**：
- 服务端：三个子 Agent 依赖 `spring-ai-alibaba-starter-a2a-server`
- 客户端：supervisor-agent 依赖 `spring-ai-alibaba-starter-a2a-client`

**注册流程**：
1. 子 Agent 启动时，A2A Server 自动将 AgentCard 注册到 Nacos（包含名称、描述、gRPC 端点）
2. Supervisor 通过 `NacosA2aAgentCardProvider` 从 Nacos 动态发现 AgentCard
3. `A2aRemoteAgent` 使用 AgentCard 中的 gRPC 地址发起调用

**AgentCard 配置**（各子 Agent 的 `application.yml`）：
```yaml
spring.ai.alibaba.a2a.server:
  card:
    name: order_agent
    description: 校园事务办理、预约申请与办理记录管理助手
    provider:
      name: 校园智能服务中心
```

**Supervisor 流输出过滤**：
```java
// 只透传 a2aNode 的流式输出，过滤掉 "Agent State: submitted" 等内部状态
.filter(output -> "a2aNode".equals(output.node()) && output instanceof StreamingOutput)
.filter(content -> !content.equals("Agent State: submitted"))
```

---

## 四、状态与记忆

### 4.1 会话 Checkpoint（状态持久化）

**概念**：将每轮 Graph 执行后的状态（`OverAllState`）持久化到存储介质，下次调用时通过相同的 `threadId` 恢复，实现多轮对话的状态连续性。

**实现位置**：三个子 Agent 的 `config/xxxAgent.java`

**实现方式**：
```java
var saver = new MemorySaver();  // JVM 内存存储（演示用）
// var saver = new RedisSaver(); // Redis 存储（生产用，代码中已注释备用）

var compileConfig = CompileConfig.builder()
    .saverConfig(SaverConfig.builder()
        .register(SaverEnum.MEMORY.getValue(), saver)
        .build())
    .build();

return ReactAgent.builder()
    .compileConfig(compileConfig)
    // ...
    .build();
```

**threadId 传递**：
```java
// Controller 中
RunnableConfig runnableConfig = RunnableConfig.builder()
    .threadId(chatId)  // chat_id 作为 threadId
    .build();
Flux<NodeOutput> result = compiledGraph.fluxStream(input, runnableConfig);
```

**效果**：同一个 `chat_id` 的连续请求，Agent 能感知到上一轮对话的内容，无需用户重复提供背景信息。

---

### 4.2 外部 Memory 集成（Mem0）

**概念**：通过外部 Memory 服务存储用户的长期偏好，跨会话持久化（不同于 Checkpoint 仅在会话内有效）。

**实现位置**：`memory-mcp-server/service/MemoryService.java`

**存储调用**（异步执行）：
```java
@Async
public void storeMemoryAsync(String userId, String content) {
    Mem0ServerRequest.MemoryCreate request = Mem0ServerRequest.MemoryCreate.builder()
        .messages(List.of(new Message("user", content)))
        .userId(userId)
        .build();
    restTemplate.postForObject(url + "/v1/memories/", request, String.class);
}
```

**检索调用**：
```java
public String searchMemory(String userId, String query) {
    // 查询近两周的记忆
    Map<String, Object> requestBody = Map.of(
        "query", query,
        "user_id", userId,
        "filters", Map.of("AND", List.of(
            Map.of("user_id", userId),
            Map.of("created_at", Map.of("gte", twoWeeksAgo, "lte", tomorrow))
        ))
    );
    // POST /v2/memories/search/
}
```

---

### 4.3 Memory 主动注入

**概念**：在 ReactAgent 推理前，通过自定义前置 `NodeAction` 主动加载用户历史偏好并以 `SystemMessage` 形式注入上下文，而不是依赖 LLM 自己决定是否调用 `memory-search` 工具。

**实现位置**：`common/node/MemoryInjectNode.java`（公共模块，三个子 Agent 共用）

**StateGraph 包装**：
```
START → MemoryInjectNode（主动注入历史偏好） → ReactAgent（使用注入的上下文） → END
```

**userId 提取逻辑**：
```java
// 1. 优先从 state 的 user_id 键获取
String userId = (String) state.value("user_id").orElse(null);

// 2. 从用户消息的 <userId> 标签解析（Supervisor 注入）
if (!StringUtils.hasText(userId)) {
    String text = lastMessage.getText();
    int start = text.indexOf("<userId>");
    int end = text.indexOf("</userId>");
    if (start >= 0 && end > start) {
        userId = text.substring(start + 8, end).trim();
    }
}
```

**注入方式**：
```java
List<Message> enriched = new ArrayList<>();
enriched.add(new SystemMessage(
    "【用户历史偏好记忆】以下是该用户的历史偏好，请在回答时参考：\n" + memoryResult
));
enriched.addAll(messages);  // 历史偏好 SystemMessage 排在最前
return Map.of("messages", enriched, "user_id", userId);
```

**三个子 Agent 对应的 Bean**：
- `orderAgentWithMemory`（order-sub-agent）— `?mode=memory`
- `consultAgentWithMemory`（consult-sub-agent）— `?mode=memory`
- `feedbackAgentWithMemory`（feedback-sub-agent）— `?mode=memory`

---

## 五、RAG 相关知识（详见 RAG 专题文档）

本节仅列举位置，详细内容参见《04-rag-knowledge.md》。

- 基础 RAG：`ConsultService.searchKnowledge()`
- 查询改写：`ConsultService.rewriteQuery()`
- Rerank：`DashScopeDocumentRetrieverOptions`
- 混合检索：`ConsultService.buildExactMatchContext()`
- RAG 来源引用：`sourceInfo` 追加

---

## 六、输出与控制

### 6.1 结构化输出（BeanOutputConverter）

**概念**：通过 `BeanOutputConverter<T>` 将 Java Record 的字段信息转换为 JSON Schema，注入 Prompt，约束 LLM 必须按 Schema 格式输出，然后自动解析为强类型对象。

**两处实现**：

**① EvaluationClassifierNode**（`supervisor-agent/config/scheduling/`）：
```java
private final BeanOutputConverter<EvaluationResult> converter =
    new BeanOutputConverter<>(EvaluationResult.class);

// Prompt 中注入 Schema 说明
systemPromptTemplate.render(Map.of(
    "inputText", inputText,
    "categories", categories,
    "format_instructions", converter.getFormat()  // 自动生成 JSON Schema
))

// 解析输出
EvaluationResult result = converter.convert(rawText);
// 降级：解析失败时存储原始文本
```

**② PlannerNode**（`order-sub-agent/node/`）：
```java
private final BeanOutputConverter<ExecutionPlan> converter =
    new BeanOutputConverter<>(ExecutionPlan.class);

ExecutionPlan plan = converter.convert(planJson);
// plan.goal() / plan.needsClarification() / plan.steps() 均为强类型
```

---

### 6.2 SSE 流式输出

**概念**：Server-Sent Events，服务端单向推送，Agent 每生成一个 token 立即推送，用户无需等待完整响应。

**实现模式**：
```java
Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().unicast().onBackpressureBuffer();

Flux<NodeOutput> result = compiledGraph.fluxStream(input, runnableConfig);

result
    .filter(output -> "llm".equals(output.node()) && output instanceof StreamingOutput)
    .cast(StreamingOutput.class)
    .map(StreamingOutput::chunk)
    .filter(chunk -> chunk != null && !chunk.trim().isEmpty())
    .map(content -> ServerSentEvent.builder(content).build())
    .doOnNext(sink::tryEmitNext)
    .doOnComplete(() -> sink.tryEmitComplete())
    .subscribe(null, e -> sink.tryEmitError(e));

return sink.asFlux();
```

**节点过滤规则**：
- sub-agent：过滤 `"llm"` 节点（ReactAgent 的 LLM 推理输出）
- supervisor-agent：过滤 `"a2aNode"` 节点（A2A 透传的子 Agent 输出）

---

### 6.3 Human-in-the-Loop（HITL）

**概念**：在 Graph 执行过程中插入"暂停点"，等待人工确认后才继续执行后续节点，防止 Agent 擅自执行不可逆的写操作。

**实现位置**：`order-sub-agent/config/OrderAgent.java`（`planAndExecuteHitlGraph` Bean）

**关键 API**：`CompileConfig.interruptBefore(List<String> nodeNames)`

```java
var compileConfig = CompileConfig.builder()
    .saverConfig(SaverConfig.builder()
        .register(SaverEnum.MEMORY.getValue(), new MemorySaver()).build())
    .interruptBefore(List.of("executor"))   // 在 executor 节点执行前暂停
    .build();

return new StateGraph("plan_and_execute_hitl", factory)
    .addNode("planner",     node_async(new PlannerNode(chatClient)))
    .addNode("executor",    node_async(new ExecutorNode(tools)))
    .addNode("synthesizer", node_async(new SynthesizerNode(chatClient)))
    .addEdge(START, "planner")
    .addEdge("planner", "executor")
    .addEdge("executor", "synthesizer")
    .addEdge("synthesizer", END)
    .compile(compileConfig);  // 带中断配置编译
```

**两步交互流程**：
```
第一次请求（?mode=hitl）：
  用户请求 → PlannerNode 生成计划 → [INTERRUPT] → 返回计划摘要给前端
  
用户确认（/confirm?action=approve）：
  frontend 检测到写操作关键词，弹出确认 Modal
  用户点击「确认执行」→ 相同 threadId resume → ExecutorNode 执行 → SynthesizerNode 汇总
  
用户拒绝（/confirm?action=reject）：
  返回"操作已取消"提示，Checkpoint 状态清空
```

**`/confirm` 端点实现**：
```java
@RequestMapping(path="/confirm", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> confirm(
        @RequestParam(name = "chat_id") String chatId,
        @RequestParam(name = "action", defaultValue = "approve") String action) {
    if ("reject".equals(action)) {
        return Flux.just(ServerSentEvent.builder("操作已取消。").build());
    }
    // approve：传入空 input，从 Checkpoint 恢复状态后继续执行 executor
    RunnableConfig runnableConfig = RunnableConfig.builder().threadId(chatId).build();
    Flux<NodeOutput> result = hitlGraph.fluxStream(Map.of(), runnableConfig);
    // ...
}
```

---

## 七、调度与自动化

### 7.1 定时调度 Agent（Loop 工程）

**本地 @Scheduled 方案**（`LocalScheduledTrigger.java`）：

```java
@Component
@ConditionalOnProperty(name = "xxl.job.enabled", havingValue = "false", matchIfMissing = true)
public class LocalScheduledTrigger {

    @Scheduled(cron = "0 0 9 * * ?")      // 每天 09:00
    public void runDailyReport() {
        dailyReportGraph.invoke(Map.of());
    }

    @Scheduled(cron = "0 0 10 ? * MON")   // 每周一 10:00
    public void runEvaluation() {
        evaluationGraph.invoke(Map.of());
    }
}
```

**XxlJob 方案**（`XxlJobScheduledAgentManager.java`，生产环境）：
- 将 Agent 注册为 XxlJob Handler，通过 XxlJob Admin 界面配置 Cron 表达式
- 支持分布式调度、任务历史记录、手动触发

---

### 7.2 自定义 NodeAction（6 种）

| NodeAction 类 | 模块 | 功能 |
|--------------|------|------|
| `EvaluationClassifierNode` | supervisor-agent | LLM 分类评分（BeanOutputConverter） |
| `DingMessageSenderNode` | supervisor-agent | 钉钉 Webhook 消息推送 |
| `MemoryInjectNode` | common | 主动注入用户历史偏好 |
| `ContextCompressionNode` | common | 滑动窗口上下文压缩 |
| `PlannerNode` | order-sub-agent | LLM 生成结构化执行计划 |
| `ExecutorNode` | order-sub-agent | 按计划逐步调用 MCP 工具（含 Retry） |
| `SynthesizerNode` | order-sub-agent | 汇总执行结果为自然语言 |

---

## 八、多轮澄清 Loop

**概念**：当用户请求信息不完整时（如"帮我预约一下"缺少服务名称），Agent 不擅自执行，而是返回澄清问题，等用户补充信息后重新规划。

**实现流程**：

1. `PlannerNode` 在生成计划前检查信息完整性
2. 若不完整，`ExecutionPlan.needsClarification = true`，`clarificationQuestion` 填入追问内容
3. `processStreamWithClarification()` 检测 planner 节点输出的 `clarification_question` 状态
4. 若存在澄清问题，直接将其作为 SSE 输出返回，**不进入 executor**
5. 用户补充信息后重新请求，形成多轮追问循环

**PlannerNode Prompt 中的澄清规则**：
```
【多轮澄清 Loop 规则】
- campus-create-service-record 必须有：userId、具体服务事项名称（不能是模糊表达）
- campus-cancel-service-record 必须有：userId、orderId
- 信息不完整时：needsClarification=true，clarificationQuestion 写出追问内容，steps 为空
```

**Controller 检测逻辑**：
```java
.doOnNext(output -> {
    if ("planner".equals(output.node()) && !(output instanceof StreamingOutput)) {
        String clarification = overAllState.value("clarification_question").orElse(null);
        if (clarification != null && !clarification.isBlank()) {
            sink.tryEmitNext(ServerSentEvent.builder(clarification).build());
            sink.tryEmitComplete();  // 提前结束，不等待后续节点
        }
    }
})
```
