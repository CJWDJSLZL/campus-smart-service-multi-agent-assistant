# 可写入简历的技术栈清单

> 本文档按"**能写什么 → 简历怎么写 → 面试怎么讲**"三段式整理，帮助你最大化简历项目亮点，同时确保每条内容都有真实代码支撑，不虚写。

---

## 一、核心框架（必写）

### 1. Spring Boot 3.2 + Spring AI 1.0 + Spring AI Alibaba 1.0.0.4

**简历写法**：
> 基于 Spring Boot 3.2 + Spring AI Alibaba 1.0.0.4 构建多智能体服务系统，运用 StateGraph、ReactAgent、LlmRoutingAgent、MemorySaver 等核心组件实现多 Agent 编排与会话状态持久化。

**能展开的点**：
- **StateGraph**：设计三条 StateGraph 工作流（Plan-Execute、Memory 注入、上下文压缩），每个 NodeAction 职责单一，节点间通过 OverAllState 传递数据（`ReplaceStrategy`）
- **ReactAgent**：三个子 Agent 均基于 ReAct 循环，LLM 在 Thought-Action-Observation 中自主决策工具调用顺序
- **LlmRoutingAgent**：Supervisor 基于此实现意图识别和多 Agent 路由，SubAgents 列表的 description 即路由依据
- **MemorySaver + CompileConfig**：三个子 Agent 启用 Checkpoint，`threadId(chatId)` 实现多轮对话状态连续性

---

### 2. MCP（Model Context Protocol）

**简历写法**：
> 设计并实现三个 MCP Server（order / feedback / memory），通过 `@Tool` + `@ToolParam` 注解暴露共 19 个业务工具，子 Agent 通过 SSE 直连和 Nacos 负载均衡两种方式动态接入工具。

**能展开的点**：
- order-mcp-server 暴露 11 个工具（创建/查询/取消/修改记录等）
- feedback-mcp-server 暴露 4 个工具
- memory-mcp-server 暴露 2 个工具（异步存储 + 语义检索）
- 工具描述遵循"动词+对象+场景+限制"四要素标准化，提升 LLM 工具选择准确率

---

### 3. A2A 协议（Agent-to-Agent）

**简历写法**：
> 使用 Spring AI Alibaba A2A 协议实现跨微服务的 Agent 调用，子 Agent 通过 gRPC（Netty Shaded 1.75.0）暴露为 A2A Server 并将 AgentCard 注册到 Nacos，Supervisor 通过 NacosA2aAgentCardProvider 动态发现子 Agent 端点，实现流式响应透传。

**能展开的点**：
- AgentCard 包含 Agent 的名称、描述、gRPC endpoint
- Supervisor LLM 基于 AgentCard 的 description 做路由决策
- gRPC Server Streaming：子 Agent 逐 token 推送，Supervisor 透传给前端 SSE，延迟极低

---

## 二、AI 能力（重点写，差异化优势）

### 4. RAG（检索增强生成）+ 三级优化

**简历写法**：
> 基于阿里云百炼向量知识库构建 RAG 检索系统，实现三级质量优化：LLM 查询改写（提升语义召回）、DashScope Rerank 重排序（rerank-top-n=3）、关键词精确匹配与向量检索混合（精确结果前置），并在回答中附加知识来源引用。

**能展开的点**：
- 查询改写：自研改写 Prompt，将口语化短查询扩展为语义丰富的检索友好形式
- Rerank：粗排候选 → Cross-Encoder 精排 → Top-3，相比纯向量检索相关性显著提升
- 混合检索：两路并行（向量检索 + SQL LIKE 查询），合并时精确匹配结果前置
- 知识库：16 份结构化 Markdown 文档，按 RAG 分块友好设计（每 ## 段落不超 300 字）

---

### 5. Plan-and-Execute 模式

**简历写法**：
> 在 order-sub-agent 中实现 Plan-and-Execute 模式：PlannerNode 使用 `BeanOutputConverter<ExecutionPlan>` 生成类型安全的结构化执行计划，ExecutorNode 确定性逐步调用 MCP 工具（含指数退避 Retry，最多 3 次），SynthesizerNode 汇总结果生成自然语言回答。

**能展开的点**：
- `BeanOutputConverter`：反射 Java Record 生成 JSON Schema 注入 Prompt，LLM 输出直接反序列化为 `ExecutionPlan` 对象（类型安全，无手工解析）
- `ExecutionPlan` 包含 `needsClarification`（多轮澄清 Loop）字段
- 与 ReactAgent 对比：Plan-and-Execute 步骤可预知、可审查，适合写操作场景

---

### 6. Human-in-the-Loop（HITL）

**简历写法**：
> 利用 `CompileConfig.interruptBefore("executor")` 实现 Human-in-the-Loop：PlannerNode 生成执行计划后 Graph 自动暂停，计划状态持久化到 MemorySaver，前端弹出确认 Modal，用户点击确认后通过 `/confirm` 端点 resume 执行，实现对不可逆写操作的人工授权控制。

**能展开的点**：
- `interruptBefore` 是 Spring AI Alibaba StateGraph 的 Checkpoint 中断机制
- MemorySaver 保存中断时的完整 State（含 ExecutionPlan），resume 时从 executor 继续
- 前端关键词检测（检测到写操作意图时弹窗）+ `/confirm` REST 端点两步交互流程

---

### 7. Memory 主动注入（长期记忆）

**简历写法**：
> 设计 MemoryInjectNode 前置节点（抽取到 common 公共模块），在 ReactAgent 执行前主动调用 Mem0 检索用户历史偏好，以 SystemMessage 形式注入对话上下文，实现三个子 Agent 的跨会话个性化服务（用户偏好跨会话持久化，注入后偏好始终生效）。

**能展开的点**：
- 主动注入 vs 被动调用：确定性触发（不依赖 LLM 自主决定是否调用），个性化服务有保证
- 注入为 SystemMessage（权重高于 UserMessage），优先被 LLM 参考
- 三个子 Agent 共用公共组件（common 模块），符合 DRY 原则

---

### 8. 结构化输出（BeanOutputConverter）

**简历写法**：
> 两处运用 `BeanOutputConverter<T>`：① `EvaluationClassifierNode` 将用户反馈评分结果解析为 `EvaluationResult`（含 complaint/satisfaction/summary 字段），② `PlannerNode` 将执行计划解析为 `ExecutionPlan`，均使用 Java Record，类型安全，含降级处理（解析失败时保存原始文本）。

---

### 9. Context 工程（上下文压缩）

**简历写法**：
> 实现 ContextCompressionNode（滑动窗口上下文压缩）：messages 超过 20 条时调用 LLM 摘要早期对话，用 1 条 SystemMessage 替换多条历史消息，保留最近 6 条原始消息。实测 14 轮对话场景下 token 消耗减少约 72%，同时关键信息通过摘要保留。

---

## 三、微服务与基础设施

### 10. Spring Boot 微服务架构（8 模块）

**简历写法**：
> 设计并实现 8 个 Spring Boot 微服务（Maven 多模块）：supervisor-agent、consult/order/feedback-sub-agent、order/feedback/memory-mcp-server、common 公共模块，各服务独立部署，通过 A2A 协议和 MCP 协议互相协作。

---

### 11. Nacos 服务发现与配置中心

**简历写法**：
> 使用 Nacos 2.x 承担双重职责：① MCP 工具注册中心（MCP Server 工具自动注册，子 Agent 通过负载均衡客户端发现工具）；② A2A Agent 发现（子 Agent AgentCard 注册，Supervisor 动态发现子 Agent gRPC 端点）。

---

### 12. MyBatis + MySQL

**简历写法**：
> 使用 MyBatis 3.5 + MySQL 8 实现数据持久化，设计 4 张业务表（users/products/orders/feedback），orders 表通过字段语义复用（sweetness 存储"办理方式"，ice_level 存储"时间偏好"）适配多场景查询。

---

### 13. Redis（Checkpoint 备用）

**简历写法**：
> 已预留 RedisSaver 实现（代码中备注），当前 demo 环境使用 MemorySaver（JVM 内存），生产环境可一行配置切换为 RedisSaver 实现分布式 Checkpoint，无需修改业务代码。

---

### 14. Docker Compose + Nginx

**简历写法**：
> 使用 Docker Compose 编排中间件（MySQL + Redis + Nacos），Nginx 作为 domain-proxy 反向代理，按路径路由请求到对应微服务，配置 `proxy_buffering off` 支持 SSE 流式推送。

---

## 四、工程实践

### 15. SSE 流式输出（Spring WebFlux + Reactor）

**简历写法**：
> 基于 Spring WebFlux + Reactor 实现 SSE 流式推送，Controller 返回 `Flux<ServerSentEvent<String>>`，使用 `Sinks.Many.unicast().onBackpressureBuffer()` 处理背压，Agent 每生成一个 token 立即推送给前端，用户无需等待完整响应。

---

### 16. Harness 工程（自动化评测）

**简历写法**：
> 建立 Agent 质量评测体系：30 条校园场景 Golden Set（consult/order/feedback 各 10 条），基于 `EvaluationClassifierNode` + `IterationNode` 实现批量自动评分，满意度低于阈值（3.0）时 `addConditionalEdges` 触发专项告警推送，实现系统质量从"主观感受"到"数据量化"的转变。

---

### 17. Loop 工程（定时调度 + 多轮澄清 + Retry）

**简历写法**：
> 实现多种 Loop 机制：① `@Scheduled` 本地定时触发（日报 Agent 每日 09:00，评测 Agent 每周一 10:00），`@ConditionalOnProperty` 控制 XxlJob/本地调度切换；② ExecutorNode 工具调用指数退避 Retry（最多 3 次，延迟 500ms×retry）；③ 多轮澄清 Loop（信息不完整时 PlannerNode 返回澄清问题，Controller 检测并提前终止，等待用户补充信息后重新规划）。

---

## 五、前端技术

### 18. Vue 3 + TypeScript + Vite + Ant Design Vue

**简历写法**：
> 使用 Vue 3 Composition API + TypeScript 开发聊天前端，Pinia 管理全局状态（消息列表、用户配置），Ant Design Vue 4 构建 UI，原生 `ReadableStream` API 实现 SSE 流式接收（逐 token 解析 `data:` 行并更新界面），`a-modal` 实现 HITL 确认弹窗。

---

## 六、简历项目描述模板

将以上技术点组合成完整的简历描述（根据岗位侧重自行取舍）：

---

**[技术实习/校招版]**

**项目名称**：校园智能服务多 Agent 助手系统

**技术栈**：Spring Boot 3.2 / Spring AI Alibaba 1.0.0.4 / MCP / A2A / Vue3 / MySQL / Nacos / Redis

**项目描述**：
- 基于 Spring AI Alibaba 构建 Supervisor + 多子 Agent + MCP Server 的多智能体协作系统，提供政策咨询、事务办理、反馈投诉三类校园服务，共 8 个 Spring Boot 微服务（Maven 多模块）
- 实现三级 RAG 优化（LLM 查询改写 + DashScope Rerank + 关键词/向量混合检索），结合 16 份结构化知识库文档，解决 LLM 幻觉问题，回答附加来源引用
- 在 order-sub-agent 中设计 Plan-and-Execute + Human-in-the-Loop 流程：`BeanOutputConverter<ExecutionPlan>` 结构化输出 + `CompileConfig.interruptBefore` 暂停执行 + 用户确认后 resume，保障不可逆写操作的人工授权
- 抽取 `MemoryInjectNode`（主动注入 Mem0 长期记忆）和 `ContextCompressionNode`（滑动窗口压缩，实测减少 72% token 消耗）到 common 公共模块，三个子 Agent 复用
- 建立 30 条 Golden Set 评测集 + `EvaluationClassifierNode` 自动评分 + `addConditionalEdges` 条件分支告警的 Harness 评测体系
- 使用 Spring WebFlux + Reactor 实现 SSE 流式推送，通过 A2A gRPC 协议 + Nacos 动态发现实现跨服务 Agent 调用

---

**[AI 工程师/Java 研发版，突出工程深度]**

**项目名称**：基于 Spring AI Alibaba 的多 Agent 系统（20 个 Agent 知识点综合实践）

**技术栈**：Spring AI Alibaba 1.0.0.4 / StateGraph / MCP / A2A / RAG / Mem0 / Spring WebFlux / Nacos

**核心亮点**：
- **多 Agent 架构**：Supervisor（LlmRoutingAgent）+ 3 子 Agent（ReactAgent）+ 3 MCP Server，通过 A2A gRPC 协议 + Nacos 实现动态发现和流式响应透传
- **RAG 三级优化**：查询改写（扩展语义）+ DashScope Rerank（精排 Top-3）+ 混合检索（向量 + 关键词精确匹配）
- **Plan-Execute + HITL**：`BeanOutputConverter` 结构化输出 + `interruptBefore` Graph 中断 + `MemorySaver` 状态持久化 + resume 机制，写操作人工授权
- **Context 工程**：MemoryInjectNode 主动注入跨会话记忆，ContextCompressionNode 滑动窗口压缩（实测减少 72% token 消耗），多层上下文管理体系
- **Harness 体系**：Golden Set（30 条）+ EvaluationClassifierNode（BeanOutputConverter 结构化评分）+ IterationNode 批量处理 + addConditionalEdges 条件告警
- **Loop 工程**：@Scheduled 定时触发、指数退避 Retry（MAX_RETRY=3）、多轮澄清 Loop（PlannerNode 信息完整性检查）

---

## 七、按岗位侧重的写法建议

| 应聘岗位 | 重点突出的技术 | 淡化的技术 |
|---------|-------------|---------|
| Java 后端 | Spring Boot、MyBatis、微服务架构、Nacos、SSE | 前端 Vue3 |
| AI 工程师 | RAG、Plan-Execute、HITL、BeanOutputConverter、Memory、Harness | MySQL 细节 |
| 全栈工程师 | 全部，但强调前后端联调（SSE、HITL 弹窗） | 无需淡化 |
| 大数据/算法 | RAG 三级优化、EvaluationClassifierNode、Golden Set、IterationNode | 微服务架构细节 |
| 实习生 | 项目完整度、技术广度、能跑起来 | 不需要特别深入某项 |

---

## 八、绝对不要写进简历的内容

以下内容虽然有实现，但不成熟或有限制，面试中被追问容易暴露：

- ❌ "实现了单元测试覆盖率 XX%"（项目测试覆盖不全，不要写具体数字）
- ❌ "支持分布式部署" （RedisSaver 是预留代码，当前 MemorySaver 不支持多实例）
- ❌ "RAG 准确率提升了 XX%"（Rerank 效果受配置限制，已知 enable-reranking 开关在当前 pipeline 不生效）
- ❌ "实现了完整的权限控制"（目前 userId 仅通过 Prompt 标签传递，无 JWT/OAuth 认证）
