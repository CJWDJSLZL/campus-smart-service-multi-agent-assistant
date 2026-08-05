# 面试官问题与回答（完整版）

> 覆盖：项目介绍问题、技术深度问题、场景设计问题、反问陷阱问题。每个答案均基于项目真实代码，可直接作为面试答案。

---

## 第一类：项目整体介绍

### Q1：请介绍一下你这个项目

**答**：

这个项目是一个基于 Spring AI Alibaba 构建的**校园智能服务多 Agent 助手系统**。核心架构是 Supervisor + 多子 Agent + MCP Server 的多智能体协作模式。

从业务角度说：系统为校园师生提供三类服务——政策咨询（查奖学金、转专业政策等）、事务办理（预约图书馆、宿舍报修等）、反馈投诉（投诉、建议、评价）。

从技术架构说：后端由 8 个 Spring Boot 微服务组成。Supervisor Agent 负责意图识别和路由，将请求通过 A2A gRPC 协议分发给对应的子 Agent；子 Agent 通过 Nacos 注册的 MCP 工具调用底层的 MCP Server；MCP Server 封装业务逻辑并操作 MySQL 数据库；前端是 Vue3 聊天界面，通过 SSE 接收流式响应。

从技术深度说：我综合实现了 RAG 三级优化（查询改写+Rerank+混合检索）、Plan-and-Execute + Human-in-the-Loop、Memory 主动注入、会话 Checkpoint、结构化输出等 20 个 Agent 知识点，同时实践了 Prompt、Context、Harness、Loop 四大 AI 工程范式。

---

### Q2：项目里为什么要用多 Agent 架构，用一个 Agent 不行吗？

**答**：

从技术角度分析，单 Agent 方案有几个问题：

第一，**Prompt 复杂度指数增长**。如果一个 Agent 同时处理政策咨询、事务办理、反馈投诉，它的 System Prompt 要描述三类场景的所有规则，工具列表也要包含 19 个工具。Prompt 越长，LLM 的注意力越稀释，工具选择错误率越高。

第二，**工具混淆风险**。"查询用户记录"和"查询知识库"语义相近，19 个工具放在一起 LLM 容易混淆。分拆后每个 Agent 只有 4-5 个工具，选择更准确。

第三，**扩展性差**。新增一种服务（比如"学校通知公告订阅"）时，单 Agent 方案要改整个 Prompt 和工具列表，影响所有场景。多 Agent 只需新增一个子 Agent 注册到 Nacos 即可。

在本项目中，Supervisor 的路由准确率因为 Prompt 简洁（只有路由规则 + Few-shot 示例）而保持较高水平；每个子 Agent 的 Prompt 专注单一职责，Tool 选择正确率也更高。

---

### Q3：Supervisor Agent 是怎么决定把请求发给哪个子 Agent 的？

**答**：

Supervisor 使用 `LlmRoutingAgent`，路由决策完全由 LLM 完成，分两步：

**第一步，读取 System Prompt 中的路由规则**。Supervisor 的 Prompt 明确写了：
- 用户询问政策/流程/条件时 → consult_agent
- 用户要求预约/申请/查询记录时 → order_agent
- 用户表达不满/投诉/建议时 → feedback_agent

**第二步，结合子 Agent 的 AgentCard description**。每个子 Agent 注册到 Nacos 时都有一段描述文字（AgentCard）：
```
consult_agent: "处理校园政策、办事流程、通知公告和服务事项咨询"
order_agent:   "处理校园事务办理、预约申请、办理记录查询..."
feedback_agent:"处理校园服务反馈、投诉、建议和评价..."
```

LLM 对比用户消息和这些描述，做出路由决策，然后通过 A2A gRPC 协议调用对应的子 Agent。

遇到边界情况（如"你好"这类非业务消息），Prompt 中的 Few-shot 示例会指引 LLM 正确处理（直接问候，不路由）。

---

## 第二类：RAG 技术深问

### Q4：你的 RAG 是怎么做的？讲一下检索流程。

**答**：

我实现了三级优化的 RAG：

**第一级：查询改写（Query Rewriting）**

用户输入往往是口语化的短语，比如"奖学金"或"图书馆预约"，直接向量化的话与知识库中长文本块的相似度较低。我在检索前先用 LLM 对查询进行改写扩展：

```
原始：奖学金
改写后：奖学金申请条件政策评定流程所需材料证明
```

改写的 Prompt 包含四条规则：展开缩写、添加同义词、补充场景词、只输出结果。这一步显著提升了向量召回率。

**第二级：Rerank 重排序**

向量检索的粗排结果中，排名靠前的不一定语义最相关。我使用 DashScope 的 Rerank 模型对候选文档重新精排，取 Top-3（`rerank-top-n=3`）。Rerank 使用 Cross-Encoder（查询和文档交叉编码），比 Bi-Encoder 向量相似度更精准。

**第三级：混合检索**

纯向量检索对精确服务名称（如"图书馆研讨间预约"）命中不稳定。我增加了一路关键词精确匹配（`products` 表的 LIKE 查询），两路结果合并时精确匹配结果前置（高优先级）。

最后，检索结果末尾附加参考来源（从 Document 的 metadata 提取 title 字段），前端 MarkdownRenderer 渲染为可见的引用。

---

### Q5：向量检索和关键词检索各自的优缺点是什么？你为什么要混合？

**答**：

**向量检索（语义检索）**：
- 优点：能理解语义相似性（"图书馆学习室"和"图书馆研讨间"会被认为相关）、对长文本和模糊查询效果好
- 缺点：对精确词汇（专有名词、编号）不稳定，Embedding 可能把不相关的内容评分偏高

**关键词检索（精确匹配）**：
- 优点：精确词汇 100% 命中，速度快（SQL 索引）
- 缺点：无语义理解，"申请"不会匹配"预约"

**为什么混合**：

用户查询"校园卡补办"时，向量检索可能返回语义相似但不完全准确的文档；关键词检索能直接精确命中 products 表中的"校园卡补办"条目，立即返回服务名称和基本描述。

混合策略取两者之长：精确词汇靠关键词检索兜底，语义理解靠向量检索补充。合并时精确匹配结果前置，LLM 优先参考权重更高的精确信息。

---

### Q6：Rerank 和向量检索的召回有什么区别？

**答**：

**向量检索**使用 Bi-Encoder：查询和文档分别独立编码为向量，然后计算余弦相似度。速度快，可以预计算文档向量，适合从大量候选中粗排。

**Rerank** 使用 Cross-Encoder：将查询和每个候选文档拼接后一起输入模型，模型直接输出相关性分数。精度高，但计算量大（每对文档单独计算），适合对少量候选精排。

**实际流程**：向量检索粗排召回 Top-20 候选 → Rerank 精排取 Top-3。粗排快速缩小候选集，精排保证最终结果质量，两者分工不同。

需要注意：在本项目使用的 DashScope pipeline 配置下，`enable-reranking` 参数配置后始终执行重排序，是平台的默认能力。

---

## 第三类：Agent 技术深问

### Q7：Plan-and-Execute 和 ReactAgent 有什么区别？你为什么在事务办理场景用 Plan-and-Execute？

**答**：

**ReactAgent（ReAct 模式）**：每步执行前都让 LLM 动态决策下一步做什么。LLM 看到当前状态后选择工具调用，看到工具结果后再决定下一步，循环直到任务完成。优点：灵活，能处理探索性任务；缺点：不确定性高，可能中途改变计划。

**Plan-and-Execute**：先让 LLM 一次性生成完整计划（PlannerNode），再按计划确定性执行（ExecutorNode）。优点：计划可预知可审查，执行过程透明；缺点：计划生成后难以根据中间结果调整。

**为什么在事务办理场景用 Plan-and-Execute**：

事务办理的步骤相对固定（验证→检查名额→创建记录），适合预先规划。更重要的是，Plan-and-Execute 能配合 Human-in-the-Loop：PlannerNode 生成计划后暂停，用户看到完整计划（要调用哪些工具、传什么参数）后才确认执行，提升透明度和安全性。如果用 ReactAgent，用户没有机会在执行前看到完整计划。

---

### Q8：Human-in-the-Loop 具体是怎么实现的？`interruptBefore` 是什么原理？

**答**：

HITL 通过 `CompileConfig.interruptBefore(List.of("executor"))` 实现。

**原理**：

在 StateGraph 编译时，`CompileConfig` 中注册了 `interruptBefore` 节点列表。Graph 执行时，每次准备进入新节点前，框架检查该节点是否在 `interruptBefore` 列表中。如果命中，执行流程：

1. 将当前 `OverAllState`（含 PlannerNode 生成的 `execution_plan`）持久化到 MemorySaver，key 为 threadId（chatId）
2. 向 Flux 流发送 interrupt 信号，停止执行
3. Controller 检测到 interrupt 后将 planner 的输出（计划摘要）作为 SSE 推送给前端

**前端弹窗确认**：
用户点击"确认执行"后，前端调用 `GET /confirm?chat_id=xxx&action=approve`。

**resume 执行**：
`/confirm` 端点传入空 input + 相同 threadId 的 RunnableConfig，框架从 MemorySaver 加载之前保存的 State（含 execution_plan），从 executor 节点继续执行，走完 executor → synthesizer → END。

**reject 流程**：
用户点击"取消操作"，`action=reject`，直接返回"操作已取消"，不 resume。

---

### Q9：MemorySaver 是怎么实现多轮对话状态保持的？

**答**：

`MemorySaver` 内部是一个 `ConcurrentHashMap<String, Map<String, Checkpoint>>`，key 是 threadId（chatId），value 是该会话的检查点数据（OverAllState 快照 + metadata + config）。

**流程**：

每次 `compiledGraph.fluxStream(input, runnableConfig)` 时：
1. 框架调用 `saver.get(threadId)` 加载历史 State（首次为 null）
2. 将历史 State 与本次 input 合并，形成完整的 `OverAllState`
3. 图执行结束（或 interruptBefore 触发）后，调用 `saver.save(threadId, state)` 保存当前 State

**效果**：
同一 chatId 的第二轮对话，Agent 看到的 messages 列表里包含第一轮的用户消息和 AI 回复，自然感知到上下文，无需用户重复背景信息。

**局限**：MemorySaver 是 JVM 内存存储，进程重启后丢失，多实例间不共享。生产环境应切换为 `RedisSaver`（代码中已预留，注释掉的版本）。

---

### Q10：你是怎么实现多轮澄清 Loop 的？

**答**：

多轮澄清 Loop 的核心在 `PlannerNode` 的 Prompt 设计和 Controller 的状态检测。

**Prompt 中的信息完整性检查规则**：
```
如果缺少必要信息（如创建记录时没有服务名称，取消记录时没有记录编号）：
  needsClarification = true
  clarificationQuestion = "您想预约哪项服务？（图书馆研讨间、心理咨询等）"
  steps = []（不生成执行步骤）
```

`ExecutionPlan` 这个 Java Record 新增了 `boolean needsClarification` 和 `String clarificationQuestion` 字段，`BeanOutputConverter` 直接解析为强类型对象。

**Controller 检测逻辑**：

`processStreamWithClarification()` 方法在监听 Flux 流时，检测 planner 节点的输出 State 中是否有 `clarification_question` 键：
```java
if ("planner".equals(output.node())) {
    String q = state.value("clarification_question").orElse(null);
    if (q != null) {
        sink.tryEmitNext(ServerSentEvent.builder(q).build());
        sink.tryEmitComplete();  // 提前终止，不进入 executor
    }
}
```

**循环过程**：
轮 1：用户说"帮我预约" → PlannerNode 判断缺少服务名称 → 返回"您想预约哪项服务？" → 不执行任何操作
轮 2：用户说"图书馆研讨间，明天下午" → PlannerNode 再次规划，仍缺时间段精确信息 → 继续追问
轮 3：用户补全信息 → PlannerNode 判断信息完整 → 生成 steps → ExecutorNode 执行

---

### Q11：MemoryInjectNode 是怎么设计的？为什么注入的是 SystemMessage 而不是 UserMessage？

**答**：

`MemoryInjectNode` 实现 `NodeAction` 接口，作为 ReactAgent 的前置节点。

**执行逻辑**：
1. 从 `OverAllState` 中提取 `user_id`（优先从状态键，其次从消息文本的 `<userId>` 标签解析）
2. 调用 `memory-search` MCP 工具：`{"userId": "10001", "query": "用户偏好和历史习惯"}`
3. 将检索结果（如"该用户偏好线上办理，常在下午预约，关注奖学金政策"）封装为 `SystemMessage`
4. 将 SystemMessage 插入到 messages 列表最前面，原有消息跟在后面
5. 返回更新后的 messages

**为什么是 SystemMessage 而不是 UserMessage**：

LLM 对不同角色的消息有不同权重：SystemMessage（系统指令）> AssistantMessage（AI 回复）> UserMessage（用户输入）。SystemMessage 被 LLM 视为"系统级约束"，权重最高，不容易被后续大量用户消息"淹没"。如果注入为 UserMessage，在多轮对话中它的影响会逐渐被稀释。

---

## 第四类：系统设计与架构问题

### Q12：如果让你在生产环境部署这个系统，你会做哪些改进？

**答**：

从几个维度考虑：

**① Checkpoint 持久化**：当前用 MemorySaver（JVM 内存），进程重启或多实例时状态丢失。生产环境应切换为 `RedisSaver`，代码中已预留，只需修改 CompileConfig。

**② 认证鉴权**：目前 userId 通过消息文本的 `<userId>` 标签传递，没有真正的身份验证。生产环境需要加 JWT 认证或 Spring Security，在 Controller 层解析 Token 获取 userId，而不是信任请求参数。

**③ 分布式限流**：DashScope API 有 QPS 限制，如果并发高需要在 ChatClient 层加限流（Resilience4j 或 Sentinel），防止 429 错误。

**④ 可观测性完善**：项目中已集成 OpenTelemetry，但需要配置真实的 ARMS/Jaeger 后端，建立 Dashboard 监控 LLM 调用延迟、工具调用成功率、Token 消耗量等指标。

**⑤ 知识库版本管理**：目前知识库文档是手动上传的静态文件，生产环境需要建立文档版本管理（政策变更时及时更新），并有机制检测哪些文档已过时。

---

### Q13：MCP 协议和普通 HTTP 接口有什么区别？为什么要用 MCP？

**答**：

**普通 HTTP 接口**：格式固定，需要手动写调用代码，LLM 无法自动发现和使用。

**MCP（Model Context Protocol）**：标准化的 AI 工具协议，具备：
1. **自描述**：工具的名称、描述、参数 Schema 自动注册（通过 `@Tool` + `@ToolParam` 注解），LLM 能理解"这个工具能做什么、参数是什么"
2. **动态发现**：Spring AI Alibaba 的 MCP Client 从 Nacos 动态获取当前可用工具列表，无需硬编码
3. **标准调用格式**：LLM 生成工具调用请求 → 框架自动解析参数并执行 Java 方法 → 结果返回给 LLM

**用 MCP 的好处**：
- 新增工具只需在 MCP Server 加 `@Tool` 方法，重启后子 Agent 自动发现，无需修改 Agent 代码
- 工具可以跨 Agent 复用：`memory-search` 工具被三个子 Agent 同时使用
- 支持负载均衡：同一工具有多个 MCP Server 实例时，`loadbalancedMcpSyncToolCallbacks` 自动负载均衡

---

### Q14：你的项目用了 Nacos，说说 Nacos 在里面起了什么作用。

**答**：

Nacos 在项目中承担**两个完全不同的角色**，这是项目中比较有特色的设计：

**角色一：MCP 工具注册中心**

三个 MCP Server 启动时，通过 `spring-ai-alibaba-starter-mcp-registry` 将各自的工具（名称、描述、参数 Schema）注册为 Nacos 服务实例的元数据。子 Agent 通过 `loadbalancedMcpSyncToolCallbacks` 在运行时从 Nacos 拉取工具列表，支持负载均衡（如果有多个 order-mcp-server 实例）。

**角色二：A2A Agent 发现**

子 Agent（consult/order/feedback）启动时通过 `spring-ai-alibaba-starter-a2a-server` 将自己的 AgentCard（含名称、描述、gRPC endpoint 地址）注册到 Nacos。Supervisor 的 `NacosA2aAgentCardProvider` 从 Nacos 查询目标 Agent 的 gRPC 地址，建立连接并调用。

**带来的好处**：
- 子 Agent 或 MCP Server 地址变更（如换端口或 IP）时，只需重启即可自动更新 Nacos 注册信息，Supervisor 下次请求时自动感知新地址
- 支持水平扩展：同名子 Agent 可以启动多个实例，Supervisor 通过负载均衡选择

---

### Q15：项目中 SSE 是如何实现的？为什么用 SSE 而不是 WebSocket？

**答**：

**SSE 实现**：

Controller 返回 `Flux<ServerSentEvent<String>>`，Spring WebFlux 框架自动处理 `Content-Type: text/event-stream` 响应头。内部使用 `Sinks.Many.unicast().onBackpressureBuffer()` 作为发布者，Graph 节点每输出一个 token 时调用 `sink.tryEmitNext()`，流处理完成时调用 `sink.tryEmitComplete()`。

Nginx 需要配置 `proxy_buffering off` 禁用缓冲，否则 Nginx 会等到响应完整才转发给前端，失去流式效果。

**为什么用 SSE 而不是 WebSocket**：

SSE 是单向流（服务端 → 客户端），WebSocket 是双向通信。

对话场景中用户输入是一次性的（HTTP 请求），服务端响应是流式的（逐 token 推送），正好符合 SSE 的语义（单向推送）。

SSE 的优势：① 基于 HTTP，防火墙兼容性好；② Spring WebFlux 原生支持（`Flux<ServerSentEvent>`），无需额外依赖；③ 浏览器原生 `EventSource` API 支持，或 `ReadableStream` fetch API 处理。

WebSocket 在这里是过设计（overkill），增加了复杂性但没有额外收益。

---

## 第五类：代码设计与工程实践

### Q16：你为什么把 MemoryInjectNode 和 ContextCompressionNode 抽取到 common 模块？

**答**：

最初 MemoryInjectNode 写在 order-sub-agent 模块里，当我要在 consult-sub-agent 和 feedback-sub-agent 中实现相同功能时，有两个选择：

**方案 A：各自复制一份代码**
- 问题：三份代码逻辑相同，未来如果修改 userId 提取逻辑（比如增加新的标签格式），需要改三个地方，容易遗漏，违反 DRY（Don't Repeat Yourself）原则

**方案 B：抽取到 common 公共模块（我的选择）**
- 创建 `common` Maven 模块，包含 `MemoryInjectNode`（包路径 `com.alibaba.cloud.ai.common.node`）
- 三个子 Agent 的 pom.xml 添加 `<dependency>common</dependency>`
- 任何修改只需在 common 模块改一次，三个子 Agent 自动生效

后来 `ContextCompressionNode` 也直接放在 common 模块，因为这种"前置处理节点"的模式在多个 Agent 中都可以复用。

---

### Q17：BeanOutputConverter 解析失败怎么处理？你的降级策略是什么？

**答**：

两处 BeanOutputConverter 都有降级处理：

**EvaluationClassifierNode**：
```java
try {
    EvaluationResult result = converter.convert(rawText);
    updatedState.put(outputKey, gson.toJson(result));  // 序列化回 JSON 给下游
} catch (Exception e) {
    // 降级：直接存储原始文本
    System.err.println("BeanOutputConverter 解析失败，降级使用原始文本: " + e.getMessage());
    updatedState.put(outputKey, rawText);
}
```

**PlannerNode**：
```java
ExecutionPlan plan = converter.convert(planJson);
```
如果解析失败，会抛出异常，由 StateGraph 的错误处理机制捕获，向用户返回"系统处理出现错误，请稍后重试"。

**整体设计原则**：
- EvaluationClassifierNode 是批量评测场景，单条解析失败不应影响其他条目，所以降级存原始文本让流程继续
- PlannerNode 是关键执行路径，计划解析失败代表 LLM 输出不符合预期，应直接报错让用户重试，而不是用错误的计划继续执行（可能产生错误操作）

---

### Q18：你的项目中用了哪些设计模式？

**答**：

**① Builder 模式**：大量使用，`LlmRoutingAgent.builder()`、`ReactAgent.builder()`、`CompileConfig.builder()`、`DashScopeDocumentRetrieverOptions.builder()` 等。复杂对象的构建参数多，Builder 模式使代码可读性高，可选参数灵活。

**② 模板方法模式**：`NodeAction` 接口定义 `apply(OverAllState state)` 作为模板方法，`MemoryInjectNode`、`ContextCompressionNode`、`PlannerNode`、`ExecutorNode`、`SynthesizerNode`、`EvaluationClassifierNode`、`DingMessageSenderNode` 各自实现具体逻辑。

**③ 策略模式**：`KeyStrategy`（`ReplaceStrategy`）是状态合并策略，可替换为 `AppendStrategy` 或自定义策略，图节点对策略无感知。

**④ 门面模式**：`ChatClient` 封装 `ChatModel`，提供统一的高级 API（含 Advisor 链、工具处理、Prompt 模板），屏蔽不同 LLM 的 API 差异。

**⑤ 代理模式**：MyBatis `@Mapper` 接口通过 JDK 动态代理生成实现，`@Transactional` 通过 Spring AOP CGLIB 代理实现事务。

**⑥ 观察者模式**：Reactor `Flux` 的发布-订阅模式，`sink.tryEmitNext()` 发布事件，`doOnNext()` 订阅处理。

---

### Q19：ExecutorNode 的 Retry 是指数退避，为什么不用固定间隔？

**答**：

**固定间隔重试的问题**：
- 如果服务过载导致请求失败，所有客户端都在相同间隔后同时重试，会造成"重试风暴"，加重服务压力，可能导致服务更难恢复
- 固定间隔无法适应不同恢复时间的故障

**指数退避的优势**：
- 每次重试等待时间增加（500ms → 1000ms → 1500ms），给服务更长的恢复时间
- 多个客户端的重试时间因首次失败时刻不同而错开，避免重试风暴
- 对瞬时故障（网络抖动，几十毫秒恢复）：第一次 500ms 后重试通常就成功
- 对较长故障（服务重启，几百毫秒-1秒）：3 次重试共等待 3000ms，覆盖大多数短暂故障场景

项目中 `MAX_RETRY = 3`，最长等待约 3 秒，对用户体验影响可接受，同时覆盖常见瞬时故障场景。

---

## 第六类：反问与深挖

### Q20：如果让你优化这个项目，你觉得最重要的改进点是什么？

**答**：（展示你的思考深度，不要说"加 JWT 认证"这种套话）

我觉得最重要的改进点是 **Golden Set 执行器的实现**。

目前项目有 30 条 Golden Set 评测数据文件，但还没有实现自动化运行器——还需要手工触发 Agent 并对比结果。真正的 Harness 工程应该是：修改 Prompt 或 RAG 配置后，一键运行 Golden Set，自动统计路由准确率、工具调用正确率、关键词命中率，对比改动前后的指标变化，这才能驱动量化迭代。

其次是 **RAG 内容质量提升**。16 份知识库文档是通用高校惯例写的，如果要真正落地到某所学校，需要对接真实的教务处/学工处数据，文档内容需要定期维护（政策变更同步更新），最好有文档版本管理机制（标记哪些文档已过时）。

第三个是 **Memory 查询策略优化**。目前 MemoryInjectNode 对所有 Agent 都用同一个查询语句（"用户偏好和历史习惯"），其实可以按 Agent 类型差异化查询——order_agent 应该查"用户办理习惯和时间偏好"，consult_agent 应该查"用户历史关注的政策方向"，这样检索结果更精准。

---

### Q21：你在这个项目里遇到的最难的问题是什么，怎么解决的？

**答**：（选择一个真实的技术难点，下面是一个可用的答案）

最难的问题是 **ContextCompressionNode 在 StateGraph 包装模式中遇到的 NPE 问题**。

当时把 ContextCompressionNode 作为前置节点，包装 ReactAgent 构建 StateGraph 时，第二次调用（Checkpoint 恢复后）偶发 NullPointerException。

排查过程发现：问题出在 StateGraph 包装模式下调用 `reactAgent.execute(agentInput)` 时，内部逻辑期望 `CompiledGraph` 实例已通过 `getAndCompileGraph()` 初始化，但在某些时序下这个方法未被调用，导致 NPE。

解决方案：在 StateGraph 的 `react_agent` 节点中改为显式调用 `reactAgent.getAndCompileGraph().invoke(agentInput)`，确保每次执行前 CompiledGraph 都已初始化。这个 Bug 也让我更深入理解了 Spring AI Alibaba ReactAgent 的内部生命周期。

---

### Q22：A2A 协议和 HTTP 调用有什么区别？为什么不直接用 OpenFeign 或 RestTemplate 调子 Agent？

**答**：

**直接用 HTTP/OpenFeign 的问题**：

HTTP 是请求-响应模型，必须等响应完整才能返回。Agent 的响应是流式的（逐 token），如果用 HTTP，要么等 LLM 生成完整响应再返回（延迟高，用户体验差），要么自己实现 Chunked Transfer Encoding（复杂，不标准）。

**A2A 的优势**：

A2A 基于 gRPC Server Streaming（HTTP/2），天然支持流式传输：子 Agent 每生成一个 token 立即通过 gRPC 流发给 Supervisor，Supervisor 立即转发给前端 SSE，用户几乎没有感知延迟。

此外 A2A 有 AgentCard 机制：Supervisor LLM 读取 AgentCard 的 description 做路由决策，子 Agent 的能力描述即路由依据，不需要在 Supervisor 代码里硬编码"哪些场景转发给哪个 Agent"。

第三点，A2A 通过 Nacos 实现服务发现，子 Agent 的地址可以动态变更，Supervisor 无需修改配置。而 OpenFeign 通常需要在 `@FeignClient(url="...")` 中写死地址或配置服务名。

---

## 附录：高频追问速查表

| 话题 | 关键词 | 一句话答案 |
|------|-------|---------|
| ReactAgent 原理 | ReAct / Thought-Action-Observation | LLM 推理-调工具-观察结果的循环，直到输出最终答案 |
| StateGraph | NodeAction / OverAllState / Edge | 有向图执行引擎，节点间共享 State，框架驱动执行顺序 |
| interruptBefore | Checkpoint / resume | 指定节点前暂停+保存State，下次相同threadId从该节点继续 |
| BeanOutputConverter | JSON Schema / 类型安全 | 反射Record生成Schema注入Prompt，LLM输出直接反序列化 |
| MemorySaver | threadId / 多轮对话 | JVM内存存储State快照，相同chatId的请求自动恢复历史 |
| A2A协议 | gRPC Streaming / AgentCard | 子Agent通过Nacos注册AgentCard，Supervisor建gRPC流式调用 |
| MCP工具 | @Tool / @ToolParam / 动态发现 | 注解声明工具，Nacos注册，子Agent运行时动态发现使用 |
| RAG查询改写 | LLM扩展语义 | 检索前LLM把短查询扩展为语义丰富的长查询，提升召回 |
| 混合检索 | 向量+关键词 | 两路并行检索，精确匹配结果前置合并 |
| MemoryInjectNode | 主动注入/SystemMessage | 前置节点主动查Mem0并注入SystemMessage，确定性触发 |
| ContextCompressionNode | 滑动窗口/token压缩 | messages>20条时LLM摘要早期对话，实测减少72% token |
| HITL | interruptBefore/approve/reject | 计划生成后暂停，用户确认后resume执行 |
| Plan-Execute vs ReAct | 确定性vs动态 | Plan-Execute预先规划可审查，ReAct每步动态决策灵活 |
| 多轮澄清 | needsClarification / clarificationQuestion | 信息不完整时返回追问，不进executor，等用户补充后重新规划 |
| Harness | Golden Set / EvaluationClassifier | 标准评测集+自动评分+条件告警，质量量化可追踪 |
| IterationNode | 批量处理 | 子图对数组中每个元素独立运行，串行防止API限速 |
| @Scheduled vs XxlJob | 定时Loop | @Scheduled零依赖本地演示，XxlJob分布式生产调度 |
| Retry指数退避 | MAX_RETRY=3 / 500ms×retry | 瞬时故障恢复后重试，指数间隔防重试风暴 |
