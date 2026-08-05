# 如何快速熟悉本项目

> 目标读者：准备在简历中介绍本项目、即将面对面试官提问的同学。
> 阅读时间：约 40 分钟，读完后能流利讲述项目并回答大部分面试问题。

---

## 第一步：用一段话说清楚这个项目是什么（2 分钟）

先把这段话背下来，这是你在面试中第一句话应该说的：

> "这是一个基于 Spring AI Alibaba 构建的**校园智能服务多 Agent 助手系统**。核心架构是 Supervisor + 多子 Agent + MCP Server 的多智能体协作模式。系统为师生提供政策咨询、事务办理、反馈投诉三类服务，后端由 7 个 Spring Boot 微服务组成，通过 A2A 协议和 MCP 协议连接，前端是 Vue3 聊天界面。在技术上，我综合运用了 RAG 知识库检索、会话 Checkpoint、Memory 主动注入、Plan-and-Execute、Human-in-the-Loop、结构化输出等 AI 工程能力，同时实践了 Prompt 工程、Context 工程、Harness 工程、Loop 工程四大 AI 工程范式。"

---

## 第二步：理解系统整体结构（5 分钟）

画一张脑图，记住这个层次关系：

```
用户 → 前端（Vue3）→ Nginx → supervisor-agent（主入口，:10008）
                                    ↓ A2A 协议（gRPC + Nacos）
                   ┌───────────────┬───────────────┐
              consult-agent    order-agent    feedback-agent
              (:10005)         (:10006)       (:10007)
              政策咨询          事务办理         投诉反馈
              RAG检索          Plan-Execute     情绪安抚
              Memory注入       HITL             Memory注入
                   └───────────────┴───────────────┘
                                    ↓ MCP 协议（Nacos 负载均衡）
                   ┌───────────────┬───────────────┐
             order-mcp-server   feedback-mcp-server  memory-mcp-server
             (:10002)           (:10004)              (:10010)
             13个工具            4个工具               2个工具
             操作MySQL           操作MySQL             调用Mem0
```

**记住三个"为什么分层"**：
- Supervisor 只做意图识别和路由，不处理业务
- 子 Agent 只做 LLM 推理和工具调用决策，不直接操作数据库
- MCP Server 只做业务逻辑和数据库操作，不做 AI 推理

---

## 第三步：掌握五个核心功能亮点（15 分钟）

这五个功能是面试官最可能追问的，每个都要能说清楚"是什么、为什么用、怎么实现"。

### 亮点 1：RAG 知识库检索（三级优化）

**是什么**：当用户问"奖学金怎么申请"时，系统先在百炼知识库中检索相关政策文档，再让 LLM 基于检索结果作答，而不是凭模型记忆猜测。

**为什么用**：LLM 训练数据中没有本校的具体政策，直接问会产生幻觉（编造错误信息）。RAG 让 LLM 基于真实文档作答，准确可追溯。

**怎么实现**（三级优化，说出这三点就很加分）：
1. **查询改写**：用户说"奖学金"→ LLM 扩展为"奖学金申请条件政策评定流程所需材料"，提升向量检索召回率
2. **Rerank 重排序**：向量粗排召回候选文档后，DashScope Rerank 模型精排取 Top-3，提升相关性
3. **混合检索**：向量检索 + products 表关键词精确匹配两路结果合并，精确服务名称也能命中

**代码位置**：`consult-sub-agent/service/ConsultService.java`，三个方法：`rewriteQuery()`、`buildExactMatchContext()`、`searchKnowledge()`

---

### 亮点 2：Plan-and-Execute + Human-in-the-Loop

**是什么**：
- **Plan-and-Execute**：Agent 先一次性生成结构化执行计划（PlannerNode），再按计划逐步调用工具（ExecutorNode），而不是 ReAct 每步动态推理。
- **Human-in-the-Loop**：计划生成后 Graph 暂停，前端弹出确认弹窗，用户确认后才继续执行写操作。

**为什么用**：
- Plan-and-Execute 让执行流程可预知、可审查，比 ReAct 更适合"创建预约"这类结果确定的操作
- HITL 确保不可逆写操作（创建/取消记录）在用户知情同意下执行，提升安全性

**怎么实现**：
```
Graph（planAndExecuteHitlGraph）：
START → PlannerNode（BeanOutputConverter<ExecutionPlan>）
     → [interruptBefore("executor")] 暂停，保存State到MemorySaver
     → 前端弹窗，用户点"确认执行"
     → GET /confirm?action=approve（相同chatId resume）
     → ExecutorNode（按计划调用工具，含 Retry 最多3次）
     → SynthesizerNode（汇总结果生成自然语言回答）
     → END
```

**代码位置**：`order-sub-agent/config/OrderAgent.java`（`planAndExecuteHitlGraph` Bean）、`order-sub-agent/node/` 目录下三个 NodeAction

---

### 亮点 3：Memory 主动注入（跨会话个性化）

**是什么**：每次对话前，通过一个前置 NodeAction（MemoryInjectNode）主动从 Mem0 服务检索用户历史偏好，以 SystemMessage 形式注入对话上下文，让 LLM 做个性化响应。

**为什么用**：
- 被动方式（Prompt 让 LLM 自己决定是否调用 memory-search 工具）不可靠，LLM 有时会跳过
- 主动注入确保每次对话都能加载用户偏好，个性化服务有保证
- Mem0 跨会话持久化，关闭浏览器再打开也能记住用户习惯

**怎么实现**：
```
StateGraph 包装模式：
START → MemoryInjectNode（主动查 Mem0，注入 SystemMessage）
      → ReactAgent（使用注入后的上下文推理）
      → END

三个子 Agent 各有对应的 Bean：
  orderAgentWithMemory（mode=memory）
  consultAgentWithMemory（mode=memory）
  feedbackAgentWithMemory（mode=memory）
```

**代码位置**：`common/node/MemoryInjectNode.java`（公共组件，三个子 Agent 共用）

---

### 亮点 4：会话 Checkpoint（多轮状态持久化）

**是什么**：使用 `MemorySaver + RunnableConfig.threadId(chatId)` 将每轮对话的 Graph 状态持久化，同一 chatId 的连续请求自动恢复上下文，实现多轮对话的状态连续性。

**为什么用**：没有 Checkpoint 时每次请求对 Agent 来说都是"失忆状态"，用户需要每次重复背景信息，体验差。

**怎么实现**：
```java
var saver = new MemorySaver();
var compileConfig = CompileConfig.builder()
    .saverConfig(SaverConfig.builder()
        .register(SaverEnum.MEMORY.getValue(), saver).build())
    .build();

ReactAgent.builder()
    .compileConfig(compileConfig)  // 挂载 Checkpoint
    ...
```
Controller 中每次请求传入 `RunnableConfig.builder().threadId(chatId).build()`，框架自动恢复历史 State。

---

### 亮点 5：上下文压缩（滑动窗口）

**是什么**：当对话轮数超过 20 条时，`ContextCompressionNode` 调用 LLM 对早期对话进行摘要压缩（3-5 句），用 1 条 SystemMessage 替换原有的多条历史消息，保留最近 6 条原始消息。

**为什么用**：多轮对话后 messages 列表无限增长，一方面超出 LLM context window，另一方面过长历史会稀释当前意图的注意力权重。

**实测效果**：14 轮对话（28 条消息）场景下，token 消耗减少约 72%，同时关键信息（记录编号、服务名称等）通过摘要保留。

**代码位置**：`common/node/ContextCompressionNode.java`，`consult-sub-agent` 暴露为 `?mode=compress` Debug 接口

---

## 第四步：理解技术选型逻辑（5 分钟）

面试官可能问"你为什么选这个框架"，提前准备答案：

**为什么用 Spring AI Alibaba 而不是 LangChain？**
> Java 生态，Spring Boot 项目天然集成，不需要引入 Python 服务。Spring AI Alibaba 的 StateGraph 提供了有状态的图执行引擎，原生支持 Checkpoint、interruptBefore、A2A 等企业级特性，与 Spring 生态（Nacos、MCP Registry、ARMS 可观测）深度集成。

**为什么用 Nacos 做 MCP 注册中心？**
> 项目已有 Nacos 做服务发现，Spring AI Alibaba 的 MCP Registry 直接复用 Nacos，工具注册和发现无缝集成，不需要额外引入新的注册中心。同时支持负载均衡（`loadbalancedMcpSyncToolCallbacks`），MCP Server 可以水平扩展。

**为什么用 Mem0 做长期记忆而不是自建？**
> Mem0 提供语义化记忆检索（不是简单的 key-value 存取），支持按语义相似度检索历史偏好，且开箱即用。自建需要向量数据库 + Embedding 模型，成本高，本项目 Mem0 免费额度够用。

**为什么用 Plan-and-Execute 而不是全部用 ReactAgent？**
> 事务办理场景的步骤相对确定（验证→检查名额→创建记录），Plan-and-Execute 能一次性生成完整计划让用户审查（配合 HITL），比 ReactAgent 的动态推理更透明、可控、安全。

---

## 第五步：准备 STAR 格式的项目介绍（5 分钟）

按 Situation-Task-Action-Result 格式准备：

**Situation（背景）**：
> 校园师生有大量重复性的信息查询、事务办理和投诉反馈需求，传统单一问答机器人无法应对政策咨询、事务预约、情绪安抚等差异显著的业务场景。

**Task（任务）**：
> 基于 Spring AI Alibaba 构建一个多 Agent 协作的校园智能服务系统，同时作为 Agent 工程能力的综合演示平台，覆盖 20 个 Agent 知识点。

**Action（行动）**：
> - 设计了 Supervisor + 3 个子 Agent + 3 个 MCP Server 的分层架构，通过 A2A 协议实现 Agent 间通信
> - 实现了三级 RAG 优化（查询改写 + Rerank + 混合检索），配合 16 份结构化知识库文档
> - 在 order-sub-agent 中实现了 Plan-and-Execute + Human-in-the-Loop，用 BeanOutputConverter 实现结构化输出
> - 抽取公共 MemoryInjectNode 和 ContextCompressionNode 到 common 模块，三个子 Agent 共用
> - 建立了 30 条 Golden Set 评测集 + EvaluationClassifierNode 自动评分 + 条件分支告警的 Harness 体系

**Result（结果）**：
> 系统覆盖 20 个 Agent 知识点，实践了 Prompt/Context/Harness/Loop 四大 AI 工程范式，代码总计 84 个 Java 文件，知识库 16 份文档，详细文档 15 份，已开源在 GitHub。

---

## 第六步：熟悉 6 个最可能被追问的技术细节（8 分钟）

### 细节 1：interruptBefore 是怎么工作的？
`CompileConfig.interruptBefore(List.of("executor"))` 在编译时标记哪些节点执行前要暂停。执行到该节点时，框架保存当前 OverAllState 到 MemorySaver，向 Flux 发送 interrupt 信号，停止执行。下次相同 threadId 的请求传入空 input，框架从 MemorySaver 恢复 State，从 executor 节点继续。

### 细节 2：BeanOutputConverter 是怎么工作的？
通过 Jackson 反射 Java Record 的字段类型生成 JSON Schema，`getFormat()` 返回该 Schema 说明字符串，注入 Prompt 约束 LLM 输出格式。收到响应后 `convert(rawText)` 调用 `ObjectMapper.readValue()` 反序列化为强类型对象，解析失败时有降级（存储原始文本）。

### 细节 3：A2A 协议具体做了什么？
子 Agent 启动时通过 `spring-ai-alibaba-starter-a2a-server` 将 AgentCard（名称、描述、gRPC endpoint）注册到 Nacos。Supervisor 通过 `NacosA2aAgentCardProvider` 从 Nacos 查询子 Agent 地址，建立 gRPC 连接（HTTP/2），以 Server Streaming 方式接收子 Agent 的逐 token 响应，透传给前端 SSE 流。

### 细节 4：MemoryInjectNode 怎么获取 userId？
优先从 `OverAllState` 的 `user_id` 键获取；若不存在，解析 UserMessage 文本末尾的 `<userId>10001</userId>` 标签（Supervisor 注入）。这种标签方式避免修改 A2A gRPC 协议格式，用约定的文本标签传递身份信息。

### 细节 5：ExecutorNode 的 Retry 策略是什么？
指数退避：最多 3 次重试，延迟分别为 500ms、1000ms、1500ms（`delay = 500L * retry`）。失败通常由瞬时故障（网络抖动）引起，间隔等待后成功率高。使用 `Thread.sleep()` 阻塞，捕获 `InterruptedException` 后恢复中断标志位。

### 细节 6：Nacos 在本项目中的两个角色？
① **MCP 工具注册中心**：MCP Server 启动时将工具列表（名称、描述、参数 Schema）注册到 Nacos，子 Agent 通过 `loadbalancedMcpSyncToolCallbacks` 发现工具，支持多 MCP Server 实例负载均衡。② **A2A Agent 发现**：子 Agent 将 AgentCard 注册到 Nacos，Supervisor 通过 `NacosA2aAgentCardProvider` 动态发现子 Agent 的 gRPC 地址，实现 A2A 服务发现。

---

## 第七步：备好两个可展示的功能（视频/截图）

**功能 1：HITL 确认流程**（最有视觉冲击力）
```bash
# 发起预约请求（HITL 模式）
curl -N "http://localhost:10006/api/order-sub-agent/debug?user_query=帮我预约图书馆研讨间明天下午4个人&chat_id=demo001&mode=hitl&user_id=10001"
# → Agent 返回执行计划（未执行）

# 用户确认后执行
curl -N "http://localhost:10006/api/order-sub-agent/confirm?chat_id=demo001&action=approve"
# → Agent 执行计划，返回预约成功结果
```

**功能 2：RAG 来源引用**（直观展示知识库能力）
在前端输入"奖学金怎么申请"，回答末尾会出现：
```
---
**参考来源：**
- 奖学金政策全览
```

---

## 快速复习卡片

打印出来随时看：

```
┌─────────────────────────────────────────────────────┐
│                   项目一句话介绍                        │
│  Spring AI Alibaba + Supervisor/Sub-Agent/MCP 架构    │
│  三类服务：政策咨询/事务办理/反馈投诉                     │
│  覆盖：20个Agent知识点 + 四大AI工程范式                  │
├──────────────────┬──────────────────────────────────┤
│    五大亮点        │         代码位置                   │
├──────────────────┼──────────────────────────────────┤
│ RAG三级优化        │ ConsultService.java              │
│ Plan-Execute+HITL │ OrderAgent.java + node/*.java    │
│ Memory主动注入     │ common/node/MemoryInjectNode.java│
│ Checkpoint       │ 三个子Agent的 config/*Agent.java  │
│ 上下文压缩         │ common/node/ContextCompress*.java│
├──────────────────┼──────────────────────────────────┤
│    核心端口        │         关键类                    │
├──────────────────┼──────────────────────────────────┤
│ 10008 Supervisor │ SupervisorAgent.java              │
│ 10005 Consult    │ ConsultAgent.java                 │
│ 10006 Order      │ OrderAgent.java                   │
│ 10007 Feedback   │ FeedbackAgent.java                │
│ 10002 OrderMCP   │ OrderMcpTools.java（11个工具）     │
└──────────────────┴──────────────────────────────────┘
```
