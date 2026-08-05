# Agent 知识体系详解

> 本文档系统梳理项目中涉及的所有 Agent 相关知识点，包含概念原理、实现细节和代码对应关系。

---

## 一、Agent 的基本概念

### 1.1 什么是 Agent？

**定义**：Agent（智能体）是一个能够感知环境、做出决策、并执行行动以实现目标的自主系统。

在 LLM 时代，Agent = LLM（大脑）+ 工具（手脚）+ 循环机制（自主性）

**与普通 LLM 调用的区别**：

| 对比维度 | 普通 LLM 调用 | Agent |
|---------|-------------|-------|
| 交互模式 | 单次问答 | 多轮推理-行动循环 |
| 工具能力 | 无（只能生成文字）| 可调用外部工具/API |
| 自主性 | 被动响应 | 主动规划和执行 |
| 状态管理 | 无状态 | 维护对话和执行状态 |
| 任务复杂度 | 简单问答 | 多步骤复杂任务 |

### 1.2 Agent 的三要素

**感知（Perception）**：接收输入（用户消息、工具返回结果、历史状态）

**决策（Decision）**：LLM 基于当前状态决定下一步行动（调用哪个工具 / 直接回答 / 请求澄清）

**行动（Action）**：执行决策（工具调用 / 生成回答 / 向用户追问）

---

## 二、ReAct 模式（ReactAgent）

### 2.1 ReAct 原理

ReAct = Reasoning（推理）+ Acting（行动），每步循环包含三个子步骤：

```
Thought: 当前状态下我需要做什么？
  → 用户想预约图书馆研讨间，我需要先验证服务是否存在

Action: campus-validate-service-item("图书馆研讨间预约")

Observation: 服务事项 图书馆研讨间预约 存在且可用
  ↓
Thought: 服务存在，需要检查名额是否够
Action: campus-check-service-capacity("图书馆研讨间预约", 1)

Observation: 服务事项 图书馆研讨间预约 剩余名额充足
  ↓
Thought: 名额充足，可以创建记录
Action: campus-create-service-record(userId=10001, ...)

Observation: 办理记录创建成功！记录编号: CAMPUS_20260805001
  ↓
Thought: 任务完成，生成用户友好的回答
Final Answer: 您的图书馆研讨间预约已成功创建，记录编号 CAMPUS_20260805001...
```

### 2.2 项目中的实现

**位置**：`order-sub-agent/config/OrderAgent.java`

```java
ReactAgent.builder()
    .name("order_agent")
    .model(chatModel)                  // 使用 DashScope 模型
    .instruction(promptConfig....)     // System Prompt（含工具选择引导）
    .tools(tools)                      // 工具列表（LLM 从这里选择调用）
    .compileConfig(compileConfig)      // Checkpoint 配置
    .build();
```

ReactAgent 内部自动处理 ReAct 循环，开发者只需提供工具和 Prompt。

### 2.3 ReAct vs 直接工具调用

**直接工具调用**：用户说什么就调什么，无推理能力
**ReAct**：LLM 推理决定调用什么，能处理"按上次一样"这类需要理解意图的请求

---

## 三、Supervisor 路由模式

### 3.1 Supervisor 模式原理

Supervisor（监督者）模式：一个主 Agent 负责任务分发，多个专业子 Agent 各司其职：

```
用户请求
    ↓
Supervisor Agent（意图识别 + 路由）
    ├── 是政策咨询？ → consult_agent
    ├── 是事务办理？ → order_agent
    └── 是反馈投诉？ → feedback_agent
```

**优点**：职责分离（每个子 Agent Prompt 简洁专注）、易于扩展（增加新子 Agent 只需注册）

**Supervisor 本身不处理业务**：只做意图识别和路由，不直接调用 MCP 工具或数据库

### 3.2 项目实现：LlmRoutingAgent

```java
// supervisor-agent/config/SupervisorAgent.java
LlmRoutingAgent.builder()
    .name("supervisor_agent")
    .description(promptConfig.getSupervisorAgentInstruction())  // 路由规则 Prompt
    .subAgents(List.of(consultAgent, feedbackAgent, orderAgent)) // 三个子 Agent
    .build();
```

LLM 读取 Prompt 中的路由规则和子 Agent 的 description，决定将请求转发给哪个子 Agent。

---

## 四、Graph-based Agent（状态图）

### 4.1 为什么需要 StateGraph？

ReactAgent 适合不确定步骤数量的任务（LLM 自主决定循环几次）。
StateGraph 适合步骤固定、流程确定的任务（明确 START → 节点A → 节点B → END）。

**StateGraph 优势**：
- 每个节点职责单一，代码清晰
- 节点可复用（ContextCompressionNode 在多个 Agent 中复用）
- 支持条件分支（`addConditionalEdges`）
- 支持 Human-in-the-Loop（`interruptBefore`）

### 4.2 基本概念

**OverAllState**：节点间共享的全局状态，`Map<String, Object>` 底层，每个键有合并策略（本项目全用 `ReplaceStrategy`）

**NodeAction**：节点的执行单元，接收 `OverAllState`，返回更新后的键值对：
```java
public Map<String, Object> apply(OverAllState state) throws Exception {
    String input = (String) state.value("input").orElse("");
    // 处理逻辑
    return Map.of("output", result);  // 写入 state
}
```

**Edge**：节点间的有向边，决定执行顺序

**ConditionalEdge**：条件边，根据 State 中的值动态选择下一个节点

### 4.3 项目中的 StateGraph 实例

#### DailyReport Agent（日报图）

```
START → data_loader_node → data_analysis_node → message_sender_node → END
```

| 节点 | 读取 | 写入 | 功能 |
|------|------|------|------|
| data_loader_node | — | data_summary | 从 MySQL 拉取数据，生成文本摘要 |
| data_analysis_node | data_summary | summary_message_to_sender | LLM 分析生成报告 |
| message_sender_node | summary_message_to_sender | message_sender_result | 推送钉钉 |

#### Evaluation Agent（评测图，含条件分支）

```
START → session_loader → iteration_analysis → result_summary
                                ↓ avg_satisfaction 判断
                    ≥3.0: message_parse → message_sender → END
                    <3.0: alert_parse  → alert_sender   → END
```

### 4.4 Plan-and-Execute Graph

**位置**：`order-sub-agent/config/OrderAgent.java`（`planAndExecuteOrderGraph` Bean）

```
START → PlannerNode → ExecutorNode → SynthesizerNode → END
```

**三个节点的职责**：

**PlannerNode**：一次性生成完整执行计划（`ExecutionPlan`），使用 `BeanOutputConverter<ExecutionPlan>` 确保结构化输出

**ExecutorNode**：按计划步骤逐一调用工具（含 Retry），不让 LLM 参与每步决策

**SynthesizerNode**：汇总执行结果，用 LLM 生成用户友好的自然语言回答

**对比 ReactAgent**：
| 维度 | ReactAgent | Plan-and-Execute |
|------|-----------|-----------------|
| 规划方式 | 每步动态决策 | 一次性规划 |
| 执行方式 | LLM 决定每步 | 确定性按计划执行 |
| 适用场景 | 探索性任务 | 步骤可预知的任务 |
| 可预测性 | 低 | 高 |

---

## 五、Human-in-the-Loop（HITL）

### 5.1 原理

在图执行过程中插入暂停点，等待人工授权后继续：

```
Graph 编译时配置：
CompileConfig.interruptBefore(List.of("executor"))
                    ↓
执行到 executor 前自动暂停
保存状态到 MemorySaver
向 Flux 流发送 interrupt 信号
                    ↓
等待 /confirm 请求
(approve → resume → executor 继续执行)
(reject  → 清空   → 终止执行)
```

### 5.2 实现细节

```java
// OrderAgent.java - planAndExecuteHitlGraph Bean
var compileConfig = CompileConfig.builder()
    .saverConfig(SaverConfig.builder()
        .register(SaverEnum.MEMORY.getValue(), new MemorySaver()).build())
    .interruptBefore(List.of("executor"))  // 关键：executor 前中断
    .build();

return new StateGraph("plan_and_execute_hitl", factory)
    .addNode("planner",     node_async(new PlannerNode(chatClient)))
    .addNode("executor",    node_async(new ExecutorNode(tools)))
    .addNode("synthesizer", node_async(new SynthesizerNode(chatClient)))
    ...
    .compile(compileConfig);  // 带中断配置

// Controller 中的 /confirm 端点
@RequestMapping(path="/confirm")
public Flux<...> confirm(@RequestParam String chatId, @RequestParam String action) {
    if ("approve".equals(action)) {
        RunnableConfig runnableConfig = RunnableConfig.builder().threadId(chatId).build();
        // 空 input：从 Checkpoint 恢复 State，从 executor 继续
        return hitlGraph.fluxStream(Map.of(), runnableConfig);
    }
    return Flux.just(ServerSentEvent.builder("操作已取消").build());
}
```

### 5.3 前端触发逻辑

```typescript
// ChatInterface.vue
const WRITE_KEYWORDS = ['创建', '预约', '申请', '取消', '修改备注', ...]

// Streaming 结束后检测
const hasWriteIntent = WRITE_KEYWORDS.some(kw => assistantContent.includes(kw))
if (hasWriteIntent) {
    confirmContent.value = assistantContent  // 展示计划摘要
    confirmVisible.value = true              // 弹出 Modal
}

// 用户点击确认
const handleConfirm = async () => {
    await chatApiService.confirmAction(configStore.chatId, 'approve')
}
```

---

## 六、MCP（Model Context Protocol）

### 6.1 MCP 协议概述

MCP 是 Anthropic 制定的 Agent 工具调用标准协议，定义了：
- 工具如何注册（工具名称、描述、参数 Schema）
- 工具如何被发现（Agent 运行时获取可用工具列表）
- 工具如何调用（请求/响应格式）

**本项目的 MCP 架构**：
```
子 Agent（MCP Client）
    ↕ MCP 协议（SSE 或 Nacos 负载均衡）
MCP Server（工具实现）
    ↕ 函数调用
Service 层（业务逻辑）
    ↕ MyBatis
MySQL（数据持久化）
```

### 6.2 工具实现（@Tool 注解）

```java
// OrderMcpTools.java
@Tool(name = "campus-create-service-record",
      description = "创建校园服务事项的办理或预约记录（需提供 userId、服务事项名称...）")
public String createOrderWithUser(
        @ToolParam(description = "用户ID，必须为正整数") Long userId,
        @ToolParam(description = "校园服务事项名称") String productName,
        @ToolParam(description = "办理方式，可选值：线上、线下、自助、窗口、加急") String serviceMode,
        ...
) {
    // 调用 orderService 处理业务
    OrderResponse order = orderService.createOrder(request);
    return String.format("办理记录创建成功！记录编号: %s...", order.getOrderId());
}
```

**`@Tool` 注解的作用**：
- Spring AI 扫描 `@Tool` 方法，生成 JSON Schema（参数名、类型、描述）
- Schema 作为"工具定义"发送给 LLM，LLM 据此决定何时调用、传什么参数
- LLM 生成工具调用请求后，框架自动反序列化参数并执行 Java 方法

### 6.3 工具调用的两种接入方式

```java
// order-sub-agent/config/OrderAgent.java

// 方式 1：SSE 直连（order-mcp-server、memory-mcp-server）
@Qualifier("mcpToolCallbacks") ToolCallbackProvider toolsProvider

// 方式 2：Nacos 负载均衡（所有注册在 Nacos 的工具，支持多实例）
@Qualifier("loadbalancedMcpSyncToolCallbacks") ToolCallbackProvider nacosToolsProvider

// 合并两路工具
List<ToolCallback> tools = new ArrayList<>();
tools.addAll(Arrays.asList(toolsProvider.getToolCallbacks()));
tools.addAll(Arrays.asList(nacosToolsProvider.getToolCallbacks()));
```

---

## 七、A2A 协议（Agent-to-Agent）

### 7.1 什么是 A2A？

A2A 是让 Agent 像调用微服务一样调用另一个 Agent 的协议，保留完整的 Agent 语义（流式输出、任务状态、能力描述）。

**与普通 HTTP 调用的区别**：
| 维度 | HTTP | A2A |
|------|------|-----|
| 响应模式 | 请求-响应 | 流式（SSE/gRPC Stream）|
| 语义 | 函数调用 | Agent 任务委托 |
| 能力描述 | 无 | AgentCard（包含 Agent 描述）|
| 传输协议 | HTTP/1.1 | gRPC/HTTP2 |

### 7.2 实现机制

**子 Agent 注册**（A2A Server 端）：
```yaml
# feedback-sub-agent/application.yml
spring.ai.alibaba.a2a.server:
  card:
    name: feedback_agent
    description: 校园反馈投诉处理助手
```
启动时自动将 AgentCard 注册到 Nacos，包含 gRPC 端点地址。

**Supervisor 发现**（A2A Client 端）：
```java
// SupervisorAgent.java
A2aRemoteAgent feedbackAgent = A2aRemoteAgent.builder()
    .name("feedback_agent")
    .agentCardProvider(agentCardProvider)  // 从 Nacos 动态获取 AgentCard
    .description("处理校园服务反馈、投诉、建议和情绪安抚等请求")
    .build();
```

**调用流程**：
```
Supervisor LLM 决定调用 feedback_agent
    → A2aRemoteAgent 从 Nacos 查询 feedback_agent 的 gRPC 地址
    → 建立 gRPC 连接
    → 以 gRPC Server Streaming 方式逐 token 接收响应
    → 透传给前端 SSE 流
```

---

## 八、会话 Checkpoint

### 8.1 原理

Checkpoint 将 Agent 的执行状态（`OverAllState`）持久化，使多轮对话保持连续性：

```
Request 1 (chatId=abc):
  RunnableConfig.threadId("abc")
  [MemorySaver 无历史] → 全新执行
  执行完：MemorySaver.save("abc", {messages: [...], ...})

Request 2 (chatId=abc):
  RunnableConfig.threadId("abc")
  [MemorySaver 加载历史] → messages = [上轮对话历史...]
  合并当前 input → 继续对话
```

### 8.2 两种 Saver 对比

| Saver | 存储位置 | 跨进程 | 跨重启 | 适用场景 |
|-------|---------|--------|--------|---------|
| MemorySaver | JVM 内存 | ✗ | ✗ | 开发/演示 |
| RedisSaver | Redis | ✓ | ✓ | 生产环境 |

```java
// 切换 Saver 只需修改 OrderAgent.java 中的 CompileConfig：
// MemorySaver（当前）：
var saver = new MemorySaver();
// RedisSaver（生产）：
// var saver = new RedisSaver(redisConnectionFactory);
```

---

## 九、Memory（长期记忆）

### 9.1 Checkpoint vs Memory 的区别

| 对比 | Checkpoint | Memory（Mem0）|
|------|-----------|--------------|
| 存储内容 | 图执行状态（消息历史、中间结果）| 用户长期偏好（跨会话）|
| 生命周期 | 会话内 | 持久（跨会话、跨设备）|
| 存储位置 | JVM 内存 / Redis | Mem0 云服务 |
| 检索方式 | 按 threadId 精确检索 | 语义向量检索 |
| 更新时机 | 每次请求自动 | Agent 判断需要记录时 |

### 9.2 记忆写入时机

Agent 在以下情况调用 `memory-store` 工具：
- 用户明确告知偏好："我喜欢下午预约"
- 对话中反复出现的模式："用户连续3次选择线上办理"
- Agent Prompt 要求识别的偏好关键词

### 9.3 主动注入 vs 被动调用

```
被动调用（依赖 LLM 决策，不可靠）：
  Prompt 说"每次先调用 memory-search"
  → LLM 有时遵守，有时跳过
  → 个性化服务不稳定

主动注入（确定性触发）：
  MemoryInjectNode 作为前置节点
  → 每次请求必定执行
  → 个性化服务有保证
```

### 9.4 MemoryInjectNode StateGraph 包装

```
三个子 Agent 各有对称的包装 Graph：

order-sub-agent:   orderAgentWithMemory   (mode=memory)
consult-sub-agent: consultAgentWithMemory (mode=memory)
feedback-sub-agent:feedbackAgentWithMemory(mode=memory)

Graph 结构：
START → MemoryInjectNode → ReactAgent → END
```

---

## 十、自定义 NodeAction（6种）

### 10.1 EvaluationClassifierNode

**功能**：用 LLM 对用户反馈记录进行分类评分

**关键特性**：使用 `BeanOutputConverter<EvaluationResult>` 确保结构化输出，结果可被程序解析

### 10.2 DingMessageSenderNode

**功能**：将报告以 Markdown 格式推送到钉钉群 Webhook

```java
public class DingMessageSenderNode implements NodeAction {
    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String message = (String) state.value(messageContentKey).orElse("");
        String token = (String) state.value("access_token").orElse(this.accessToken);
        // 调用钉钉 Webhook API
        sendToDingTalk(token, title, message);
        return Map.of(resultKey, "发送成功");
    }
}
```

### 10.3 MemoryInjectNode（公共组件）

**功能**：前置节点，主动检索并注入用户历史偏好

**位置**：`common/node/MemoryInjectNode.java`（三个子 Agent 共用）

### 10.4 ContextCompressionNode（公共组件）

**功能**：滑动窗口压缩，messages 超过阈值时 LLM 摘要早期对话

**位置**：`common/node/ContextCompressionNode.java`

**触发条件**：`messages.size() > maxMessages`（默认 20）

### 10.5 PlannerNode

**功能**：Plan-and-Execute 的规划节点，使用 `BeanOutputConverter<ExecutionPlan>` 生成结构化计划，含信息完整性检查（多轮澄清 Loop）

### 10.6 ExecutorNode

**功能**：按 ExecutionPlan 逐步调用工具，含指数退避 Retry（MAX_RETRY=3，延迟 500ms×retry）

### 10.7 SynthesizerNode

**功能**：汇总 ExecutorNode 的执行结果，调用 LLM 生成用户友好的自然语言回答

---

## 十一、调度与自动化 Agent

### 11.1 LocalScheduledTrigger（@Scheduled）

```java
@Component
@ConditionalOnProperty(name = "xxl.job.enabled", havingValue = "false", matchIfMissing = true)
public class LocalScheduledTrigger {
    @Scheduled(cron = "0 0 9 * * ?")    // 每天 09:00
    public void runDailyReport() { dailyReportGraph.invoke(Map.of()); }

    @Scheduled(cron = "0 0 10 ? * MON") // 每周一 10:00
    public void runEvaluation() { evaluationGraph.invoke(Map.of()); }
}
```

### 11.2 CronAgent（动态任务注册）

用户用自然语言描述定时规则，Agent 解析为 Quartz Cron 并动态注册：

```
用户："每天早上8点30分执行日报"
    → CronTaskParseAgent（ReactAgent）
    → 工具调用：CronAgentTools.createCronTask("operationAnalysisAgent", "0 30 8 * * ?")
    → XxlJobScheduledAgentManager 动态注册
    → 按计划自动触发
```

---

## 十二、多轮澄清 Loop

### 12.1 问题背景

用户说"帮我预约一下"——缺少服务名称、时间、人数等关键信息。ReactAgent 可能猜测服务名称（产生错误），Plan-Execute 模式会在 `toolParameters` 中出现缺失值导致工具报错。

### 12.2 实现原理

**PlannerNode 的信息完整性检查**（Prompt 中的规则）：
```
如果缺少 campus-create-service-record 所需的 productName：
    needsClarification=true
    clarificationQuestion="您想预约哪项服务？（图书馆研讨间、心理咨询、体育馆等）"
    steps=[]
```

**Controller 检测并提前返回**：
```java
// processStreamWithClarification() 中
if ("planner".equals(output.node())) {
    String q = state.value("clarification_question").orElse(null);
    if (q != null) {
        sink.tryEmitNext(ServerSentEvent.builder(q).build());
        sink.tryEmitComplete();  // 提前结束，不继续执行 executor
    }
}
```

**用户补充信息后重新请求**（相同 chatId），PlannerNode 再次规划，信息完整后进入 ExecutorNode 执行。

### 12.3 澄清 Loop 与 HITL 的区别

| 维度 | 澄清 Loop | HITL |
|------|---------|------|
| 触发时机 | 信息不完整时（Planning 阶段）| 信息完整、计划生成后（执行前）|
| 目的 | 获取缺失信息 | 获取执行授权 |
| Agent 状态 | 未生成计划 | 已生成计划，暂停等待 |
| 用户操作 | 补充输入信息 | 审阅计划并确认/拒绝 |
