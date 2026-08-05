# 全部功能函数路线图

> 本文档按功能维度梳理所有关键函数的调用链路，可作为代码阅读和调试的索引。

---

## 一、用户对话主链路

### 1.1 正常对话（Supervisor → 子 Agent → MCP）

```
用户浏览器
  └─ GET /api/assistant/chat?chat_id=xxx&user_query=xxx&user_id=xxx
       └─ [Nginx] 转发 → supervisor-agent:10008
            └─ SupervisorAgentController.chat()
                 ├─ 构建 userInput = userQuery + "<userId>" + userID + "</userId>"
                 ├─ RunnableConfig.builder().threadId(chatID).addMetadata("user_id", userID)
                 ├─ supervisorAgentBean.getAndCompileGraph()
                 │    └─ [CompileConfig：MemorySaver]
                 ├─ compiledGraph.fluxStream(input, runnableConfig)
                 │    └─ [LlmRoutingAgent 执行]
                 │         ├─ DashScope ChatModel 调用（意图识别）
                 │         ├─ 路由决策：consult / order / feedback
                 │         └─ A2aRemoteAgent.call(subAgentName)
                 │              └─ [gRPC 连接子 Agent]
                 └─ processStream(result, sink)
                      └─ filter "a2aNode" + StreamingOutput
                           └─ ServerSentEvent 推送给浏览器
```

---

## 二、consult-sub-agent 调用链路

### 2.1 Debug 接口（mode=react）

```
GET /api/consult_sub_agent/debug?user_query=xxx&chat_id=xxx&mode=react
  └─ ConsultAgentDebugController.chat()
       ├─ RunnableConfig.builder().threadId(chatId)
       ├─ graph = consultSubAgent.getAndCompileGraph()
       └─ compiledGraph.fluxStream(input, runnableConfig)
            └─ [ReactAgent 执行（ReAct 循环）]
                 ├─ LLM 调用（System Prompt + messages + 工具列表）
                 ├─ 可能调用工具：
                 │   ├─ consult-search-knowledge
                 │   │    └─ ConsultTools.searchKnowledge(query)
                 │   │         ├─ ConsultService.searchKnowledge(query)
                 │   │         │    ├─ ConsultService.rewriteQuery(query)
                 │   │         │    │    └─ ChatClient.prompt().user(rewritePrompt).call()
                 │   │         │    ├─ ConsultService.buildExactMatchContext(query)
                 │   │         │    │    └─ ProductMapper.selectByNameLike(query)
                 │   │         │    ├─ DashScopeApi.retriever(indexID, rewrittenQuery, options)
                 │   │         │    └─ 合并结果 + 附加来源信息
                 │   ├─ consult-get-products
                 │   │    └─ ConsultTools.getProducts()
                 │   │         └─ ConsultService.getAllProducts()
                 │   │              └─ ProductMapper.selectAllAvailable()
                 │   ├─ consult-get-product-info
                 │   │    └─ ConsultTools.getProductInfo(productName)
                 │   │         └─ ConsultService.getProductByName(productName)
                 │   │              └─ ProductMapper.selectByNameAndStatus(name, 1)
                 │   └─ consult-search-products
                 │        └─ ConsultTools.searchProducts(keyword)
                 │             └─ ConsultService.searchProductsByName(keyword)
                 │                  └─ ProductMapper.selectByNameLike(keyword)
                 └─ 流式输出（filter "llm" node）→ SSE
```

### 2.2 Debug 接口（mode=memory）

```
GET /api/consult_sub_agent/debug?mode=memory
  └─ ConsultAgentDebugController.chat()
       └─ graph = consultAgentWithMemory (CompiledGraph)
            └─ StateGraph: START → memory_inject → react_agent → END
                 ├─ memory_inject: MemoryInjectNode.apply(state)
                 │    ├─ extractUserId(state) → 从 <userId> 标签解析
                 │    ├─ memorySearchTool.call("{userId:xxx, query:用户偏好...}")
                 │    │    └─ [Nacos MCP → memory-mcp-server:10010]
                 │    │         └─ MemoryMcpTools.searchMemory(userId, query)
                 │    │              └─ MemoryService.searchMemory(userId, query)
                 │    │                   └─ POST https://api.mem0.ai/v2/memories/search/
                 │    └─ 注入 SystemMessage("【用户历史偏好记忆】...")
                 └─ react_agent: reactAgent.execute(agentInput)
                      └─ [同 mode=react 调用链]
```

### 2.3 Debug 接口（mode=compress）

```
GET /api/consult_sub_agent/debug?mode=compress
  └─ graph = consultAgentWithCompression
       └─ StateGraph: START → context_compress → react_agent → END
            ├─ context_compress: ContextCompressionNode.apply(state)
            │    ├─ if messages.size() <= 20: return Map.of()（跳过）
            │    ├─ toCompress = messages[0 .. size-keepRecentCount]
            │    ├─ toKeep = messages[最近 keepRecentCount 条]
            │    ├─ ChatClient.prompt().user(historyText).call().content() → summary
            │    └─ return {messages: [SystemMessage(摘要)] + toKeep}
            └─ react_agent: [同 mode=react]
```

---

## 三、order-sub-agent 调用链路

### 3.1 Debug 接口（mode=react，默认）

```
GET /api/order-sub-agent/debug?mode=react
  └─ OrderAgentDebugController.chat()
       └─ graph = orderSubAgent.getAndCompileGraph()
            └─ [ReactAgent 执行]
                 └─ 工具来源：
                      ├─ SSE 直连 (mcpToolCallbacks)：
                      │   order-mcp-server:10002 的所有工具（11个）
                      │   memory-mcp-server:10010 的 memory-store/search
                      └─ Nacos 负载均衡 (loadbalancedMcpSyncToolCallbacks)：
                          全部注册在 Nacos 的工具
```

### 3.2 Debug 接口（mode=plan）

```
GET /api/order-sub-agent/debug?mode=plan
  └─ graph = planAndExecuteOrderGraph
       └─ StateGraph: START → planner → executor → synthesizer → END

       planner: PlannerNode.apply(state)
         ├─ messages 最后一条 = 用户请求
         ├─ ChatClient 调用：PLANNER_SYSTEM_TEMPLATE（含 Schema 注入）
         ├─ converter.convert(planJson) → ExecutionPlan
         │    ├─ goal: "预约图书馆研讨间"
         │    ├─ needsClarification: false
         │    └─ steps: [
         │         {stepNumber:1, toolName:"campus-validate-service-item", toolParameters:"{...}"},
         │         {stepNumber:2, toolName:"campus-check-service-capacity", ...},
         │         {stepNumber:3, toolName:"campus-create-service-record", ...}
         │       ]
         └─ state 写入：execution_plan, step_results=[]

       executor: ExecutorNode.apply(state)
         └─ for each step in plan.steps():
              └─ executeWithRetry(step, tool)
                   ├─ retry 0: tool.call(step.toolParameters())
                   │    └─ [MCP → order-mcp-server → OrderMcpTools → OrderService → MySQL]
                   ├─ 失败 retry 1: Thread.sleep(500ms); tool.call(...)
                   ├─ 失败 retry 2: Thread.sleep(1000ms); tool.call(...)
                   └─ 失败 retry 3: Thread.sleep(1500ms); tool.call(...)
         └─ state 写入：step_results=[...]

       synthesizer: SynthesizerNode.apply(state)
         ├─ execution_plan.goal + step_results
         ├─ ChatClient 调用：生成自然语言总结
         └─ state 写入：messages 追加 AssistantMessage(synthesis)
```

### 3.3 Debug 接口（mode=hitl）— 两步流程

```
第一步：GET /api/order-sub-agent/debug?mode=hitl&chat_id=hitl-001
  └─ graph = planAndExecuteHitlGraph
       └─ [同 mode=plan，但 CompileConfig.interruptBefore("executor")]
            ├─ planner 节点执行完 → state 含 execution_plan
            ├─ 检查 interruptBefore 列表：命中 "executor"
            ├─ MemorySaver.save("hitl-001", state)
            └─ 返回 planner 节点输出（计划摘要）给前端

前端弹出确认 Modal，用户点击"确认执行"

第二步：GET /api/order-sub-agent/confirm?chat_id=hitl-001&action=approve
  └─ OrderAgentDebugController.confirm()
       ├─ RunnableConfig.builder().threadId("hitl-001")
       ├─ hitlGraph.fluxStream(Map.of(), runnableConfig)
       │    ├─ MemorySaver.load("hitl-001") → 恢复 state（含 execution_plan）
       │    └─ 从 executor 节点继续执行
       │         executor → synthesizer → END
       └─ processStream → SSE 流式返回执行结果
```

### 3.4 Debug 接口（mode=memory）

```
GET /api/order-sub-agent/debug?mode=memory
  └─ graph = orderAgentWithMemory
       └─ [同 consult mode=memory 调用链结构]
            START → MemoryInjectNode → ReactAgent(orderSubAgent) → END
```

---

## 四、order-mcp-server 工具调用链路

### 4.1 工具 → 服务 → 数据库

```
MCP 工具调用 → OrderMcpTools → OrderService → MySQL (via MyBatis)

campus-create-service-record(userId, productName, ...)
  └─ OrderMcpTools.createOrderWithUser(userId, productName, serviceMode, priority, quantity, remark)
       ├─ convertServiceModeToNumber(serviceMode) → Integer（线上=1, 线下=2...）
       ├─ convertPriorityToNumber(priority) → Integer（普通=1, 上午=4...）
       ├─ new OrderCreateRequest(userId, null, productName, serviceModeLevel, priorityLevel, quantity, remark)
       └─ OrderService.createOrder(request)
            ├─ validateUser(userId) → UserMapper.selectById(userId)
            ├─ validateProduct(productName) → ProductMapper.existsByNameAndStatusTrue(name)
            ├─ checkStock(productName, quantity) → ProductMapper.checkStock(name, qty)
            ├─ orderId = "CAMPUS_" + System.currentTimeMillis()
            ├─ new Order(orderId, userId, productId, productName, sweetness, iceLevel, quantity, ...)
            └─ OrderMapper.insert(order) → INSERT INTO orders(...)

campus-get-service-records-by-user(userId)
  └─ OrderMcpTools.getOrdersByUser(userId)
       └─ OrderService.getOrdersByUserId(userId)
            └─ OrderMapper.selectByUserId(userId) → SELECT * FROM orders WHERE user_id=?

campus-cancel-service-record(userId, orderId)
  └─ OrderMcpTools.deleteOrder(userId, orderId)
       └─ OrderService.deleteOrder(userId, orderId)
            ├─ 验证记录属于该用户
            └─ OrderMapper.deleteByUserIdAndOrderId(userId, orderId) → DELETE FROM orders WHERE...

campus-validate-service-item(productName)
  └─ OrderMcpTools.validateProduct(productName)
       └─ OrderService.validateProduct(productName)
            └─ ProductMapper.existsByNameAndStatusTrue(name) → SELECT COUNT(*) FROM products WHERE...

campus-check-service-capacity(productName, quantity)
  └─ OrderMcpTools.checkStock(productName, quantity)
       └─ OrderService.checkStock(productName, quantity)
            └─ ProductMapper.checkStock(name, qty) → SELECT stock FROM products WHERE...
```

---

## 五、feedback-sub-agent 调用链路

### 5.1 Debug 接口

```
GET /api/feedback-sub-agent/debug?user_query=xxx&mode=react|memory
  └─ FeedbackAgentDebugController.chat()
       ├─ mode=react: feedbackSubAgent.getAndCompileGraph()
       └─ mode=memory: feedbackAgentWithMemory
            └─ StateGraph: START → MemoryInjectNode → ReactAgent → END
```

### 5.2 MCP 工具调用（feedback-mcp-server）

```
feedback-create-feedback(userId, feedbackType, content, orderId, rating)
  └─ FeedbackMcpTools.createFeedback(...)
       └─ FeedbackService.createFeedback(feedback)
            └─ FeedbackMapper.insert(feedback) → INSERT INTO feedback(...)

feedback-get-feedback-by-user(userId)
  └─ FeedbackMcpTools.getFeedbacksByUserId(userId)
       └─ FeedbackService.getFeedbacksByUserId(userId)
            └─ FeedbackMapper.selectByUserId(userId) → SELECT * FROM feedback WHERE user_id=?

feedback-update-solution(feedbackId, solution)
  └─ FeedbackMcpTools.updateFeedbackSolution(feedbackId, solution)
       └─ FeedbackService.updateFeedbackSolution(feedbackId, solution)
            └─ FeedbackMapper.updateSolution(feedbackId, solution) → UPDATE feedback SET solution=?
```

---

## 六、memory-mcp-server 调用链路

```
memory-store(userId, content)
  └─ MemoryMcpTools.storeMemory(userId, content)
       └─ MemoryService.storeMemory(userId, content)
            ├─ 立即返回 "成功存储用户喜好"
            └─ [异步 @Async] MemoryService.storeMemoryAsync(userId, content)
                  ├─ 构建 Mem0ServerRequest.MemoryCreate
                  └─ RestTemplate.postForObject("https://api.mem0.ai/v1/memories/", request, String.class)

memory-search(userId, query)
  └─ MemoryMcpTools.searchMemory(userId, query)
       └─ MemoryService.searchMemory(userId, query)
            ├─ 构建查询体（含时间范围过滤：近两周）
            └─ RestTemplate.postForObject("https://api.mem0.ai/v2/memories/search/", body, String.class)
```

---

## 七、定时调度 Agent 链路

### 7.1 LocalScheduledTrigger 触发链

```
每天 09:00（@Scheduled cron = "0 0 9 * * ?"）
  └─ LocalScheduledTrigger.runDailyReport()
       └─ operationAnalysisAgent.invoke(Map.of())
            └─ [StateGraph: data_loader → data_analysis → message_sender]
                 ├─ data_loader_node (AsyncNodeAction)
                 │    ├─ FeedbackMapper.selectMaxCreatedMonth() → 获取最新数据月份
                 │    ├─ OrderMapper.selectStats(...) → 订单统计
                 │    └─ state.put("data_summary", summaryText)
                 ├─ data_analysis_node (LlmNode)
                 │    ├─ 读取 state["data_summary"]
                 │    └─ DashScope LLM 分析 → state.put("summary_message_to_sender", report)
                 └─ message_sender_node (DingMessageSenderNode)
                      ├─ 读取 state["summary_message_to_sender"]
                      └─ HTTP POST → 钉钉 Webhook URL

每周一 10:00（@Scheduled cron = "0 0 10 ? * MON"）
  └─ LocalScheduledTrigger.runEvaluation()
       └─ evaluationAnalysisAgent.invoke(Map.of())
            └─ [StateGraph: session_loader → iteration_analysis → result_summary → 条件分支]
                 ├─ session_loader_node
                 │    ├─ FeedbackMapper.selectByTimeRange(startTime, endTime)
                 │    └─ state.put("sessions", sessionJsonArray)
                 ├─ iteration_session_analysis_node (IterationNode)
                 │    └─ 对每条记录运行子图：
                 │         EvaluationClassifierNode.apply(state)
                 │           ├─ DashScope LLM 评分
                 │           ├─ converter.convert(rawText) → EvaluationResult
                 │           └─ state.put("session_analysis_result", gson.toJson(result))
                 ├─ session_result_summary_node
                 │    ├─ 统计：总数、投诉数、平均满意度、核心诉求
                 │    └─ state.put("summary_message", summaryMap)
                 ├─ [addConditionalEdges] 判断 avg_satisfaction
                 │    ├─ avg ≥ 3.0 → message_parse → message_sender（普通格式）
                 │    └─ avg < 3.0 → alert_message_parse → alert_message_sender（告警格式）
                 └─ DingMessageSenderNode → 钉钉推送
```

### 7.2 Admin Agent 链路

```
GET /api/admin/chat?chat_id=xxx&user_id=xxx&user_query=xxx
  └─ AdminAgentController.chat()
       └─ adminAgentBean（LlmRoutingAgent）
            └─ 子 Agent：cronTaskParseAgent（ReactAgent）
                 └─ 工具：CronAgentTools
                      └─ CronAgentTools.createCronTask(agentName, cronExpression)
                           ├─ 从 Spring Context 查找目标 Agent Bean（CompiledGraph）
                           └─ XxlJobScheduledAgentManager.registerTask(agentName, cronExpression, graphBean)
                                └─ XxlJobExecutor.registJobHandler(name, IJobHandler{invoke compiledGraph})
```

---

## 八、前端完整调用链路

### 8.1 发送消息

```
用户输入文字 → Enter 键 / 发送按钮
  └─ handleKeyPress(e) / sendMessage()
       ├─ chatStore.addMessage({type: 'user', content: userMessage})
       ├─ chatStore.addMessage({type: 'assistant', content: '', isStreaming: true})
       ├─ chatApiService.sendMessage(userMessage)
       │    └─ fetch(`${apiUrl}?chat_id=xxx&user_id=xxx&user_query=xxx`)
       │         └─ [HTTP GET + Accept: text/event-stream]
       ├─ reader = response.body.getReader()
       └─ while (true):
            └─ { done, value } = await reader.read()
                 └─ 解析 SSE "data: " 行
                      └─ chatStore.updateLastMessage(content, isStreaming=true)
       → 完成后 chatStore.updateLastMessage(content, isStreaming=false)
       → 检测写操作关键词 → 触发 confirmVisible=true（弹窗）
```

### 8.2 HITL 确认流程

```
用户点击"确认执行"
  └─ handleConfirm()
       ├─ chatStore.addMessage({type: 'assistant', content: '', isStreaming: true})
       ├─ chatApiService.confirmAction(configStore.chatId, 'approve')
       │    └─ fetch(`${baseUrl}/api/order-sub-agent/confirm?chat_id=xxx&action=approve`)
       └─ [同普通消息的流式接收逻辑]

用户点击"取消操作"
  └─ handleReject()
       ├─ chatApiService.confirmAction(configStore.chatId, 'reject')
       └─ chatStore.addMessage({type: 'assistant', content: '操作已取消...'})
```

---

## 九、Bean 依赖关系图

```
supervisor-agent:
  SupervisorAgentController
    └─ supervisorAgentBean (LlmRoutingAgent)
         ├─ dashscopeChatModel
         ├─ agentCardProvider (NacosA2aAgentCardProvider)
         │    └─ Nacos → [consult_agent AgentCard, order_agent AgentCard, feedback_agent AgentCard]
         └─ SupervisorAgentPromptConfig (YAML 绑定)

  AdminAgentController
    └─ adminAgentBean (LlmRoutingAgent)
         └─ cronTaskParseAgent (ReactAgent)
              └─ CronAgentTools

  LocalScheduledTrigger
    ├─ operationAnalysisAgent (CompiledGraph)
    └─ evaluationAnalysisAgent (CompiledGraph)

consult-sub-agent:
  ConsultAgentDebugController
    ├─ consultSubAgentBean (ReactAgent)
    │    ├─ dashscopeChatModel
    │    ├─ loadbalancedMcpSyncToolCallbacks (Nacos MCP)
    │    │    └─ memory-search, memory-store
    │    ├─ ConsultTools (本地工具)
    │    │    └─ ConsultService
    │    │         ├─ DashScopeApi (RAG)
    │    │         ├─ ChatClient (查询改写)
    │    │         └─ ProductMapper (混合检索)
    │    └─ MemorySaver + CompileConfig
    ├─ consultAgentWithMemory (CompiledGraph)
    │    └─ MemoryInjectNode + consultSubAgentBean
    └─ consultAgentWithCompression (CompiledGraph)
         └─ ContextCompressionNode + consultSubAgentBean

order-sub-agent:
  OrderAgentDebugController
    ├─ orderSubAgentBean (ReactAgent)
    │    ├─ dashscopeChatModel
    │    ├─ mcpToolCallbacks (SSE MCP)
    │    │    └─ order-mcp-server:10002 的全部工具
    │    │    └─ memory-mcp-server:10010 的 memory-store/search
    │    ├─ loadbalancedMcpSyncToolCallbacks (Nacos MCP)
    │    └─ MemorySaver + CompileConfig
    ├─ orderAgentWithMemory (CompiledGraph)
    │    └─ MemoryInjectNode + orderSubAgentBean
    ├─ planAndExecuteOrderGraph (CompiledGraph)
    │    └─ PlannerNode + ExecutorNode + SynthesizerNode
    └─ planAndExecuteHitlGraph (CompiledGraph)
         └─ PlannerNode + ExecutorNode + SynthesizerNode
              + CompileConfig.interruptBefore("executor")

feedback-sub-agent:
  FeedbackAgentDebugController
    ├─ feedbackSubAgentBean (ReactAgent)
    │    ├─ dashscopeChatModel
    │    ├─ loadbalancedMcpSyncToolCallbacks (Nacos MCP)
    │    │    └─ feedback-mcp-server:10004 的全部工具 + memory 工具
    │    └─ MemorySaver + CompileConfig
    └─ feedbackAgentWithMemory (CompiledGraph)
         └─ MemoryInjectNode + feedbackSubAgentBean
```

---

## 十、数据流转图

```
用户输入 "帮我预约图书馆"
          ↓
Supervisor LLM：识别为 order_agent 任务
          ↓
A2A gRPC → order-sub-agent
          ↓
MemoryInjectNode：memory-search → Mem0
          → "该用户常在下午预约，偏好线上"
          ↓ 注入 SystemMessage
ReactAgent LLM（含历史偏好上下文）：
  思考 → 调用 campus-validate-service-item
  工具返回 → "服务存在且可用"
  思考 → 调用 campus-check-service-capacity
  工具返回 → "名额充足"
  思考 → 调用 campus-create-service-record（serviceMode=线上, priority=下午/晚上）
  ↓ MCP → order-mcp-server → OrderService → MySQL
  工具返回 → "记录创建成功，CAMPUS_20260805001"
  LLM 生成回答："图书馆研讨间已成功预约，记录编号 CAMPUS_20260805001，
                办理方式：线上，时间偏好：下午/晚上。"
          ↓
A2A gRPC 流式返回每个 token
          ↓
Supervisor 透传 SSE
          ↓
前端逐字显示回答
          ↓
MemoryInjectNode 后续：
  order_agent 调用 memory-store
  → "用户偏好线上办理，预约时间偏好下午/晚上" → Mem0 异步存储
```

---

## 十一、端点完整清单

| HTTP 方法 | URL | 模块 | 功能 |
|----------|-----|------|------|
| GET | `/api/assistant/chat` | supervisor-agent:10008 | 主聊天入口（SSE）|
| GET | `/api/admin/chat` | supervisor-agent:10008 | 管理员聊天（CronAgent）|
| GET | `/api/order-sub-agent/debug` | order-sub-agent:10006 | Order Agent Debug（SSE）|
| GET | `/api/order-sub-agent/confirm` | order-sub-agent:10006 | HITL 确认/拒绝 |
| GET | `/api/consult_sub_agent/debug` | consult-sub-agent:10005 | Consult Agent Debug（SSE）|
| GET | `/api/feedback-sub-agent/debug` | feedback-sub-agent:10007 | Feedback Agent Debug（SSE）|
| GET/POST | `/api/orders/*` | order-mcp-server:10002 | 订单 REST API（管理用）|
| GET/POST | `/api/feedback/*` | feedback-mcp-server:10004 | 反馈 REST API（管理用）|
| GET/POST | `/api/memory/*` | memory-mcp-server:10010 | 记忆 REST API（管理用）|

---

## 十二、Debug 接口 mode 参数汇总

| 模块 | mode | 功能描述 |
|------|------|---------|
| order-sub-agent | `react`（默认）| ReactAgent + MemorySaver Checkpoint |
| order-sub-agent | `plan` | Plan-and-Execute（三节点 Graph）|
| order-sub-agent | `memory` | Memory 主动注入（MemoryInjectNode + ReactAgent）|
| order-sub-agent | `hitl` | Human-in-the-Loop（planner 暂停等待 /confirm）|
| consult-sub-agent | `react`（默认）| ReactAgent + MemorySaver Checkpoint |
| consult-sub-agent | `memory` | Memory 主动注入 |
| consult-sub-agent | `compress` | 滑动窗口上下文压缩 |
| feedback-sub-agent | `react`（默认）| ReactAgent + MemorySaver Checkpoint |
| feedback-sub-agent | `memory` | Memory 主动注入 |
