# 未完成功能实施调研方案

## 概述

本文档覆盖 3 个待实现功能：
1. **Human-in-the-Loop（人工介入）**
2. **Memory 主动注入补全**（consult / feedback 对称实现）
3. **前端 UX 功能**（确认弹窗 + RAG 来源展示）

---

## 功能一：Human-in-the-Loop（人工介入）

### 目标
在 order-sub-agent 的 Plan-and-Execute Graph 中，PlannerNode 生成计划后暂停，将计划展示给用户确认，用户同意后 ExecutorNode 才继续执行写操作（创建/取消/修改服务单）。

### 现状分析
- `planAndExecuteOrderGraph` 目前是线性 Graph：`planner → executor → synthesizer`，执行一气呵成
- `OrderAgentDebugController` 已有 `mode=plan` 触发入口，`RunnableConfig.threadId` 已传入（checkpoint 就绪）
- `CompileConfig` 已在 `orderSubAgentBean` 中使用 `MemorySaver`，但 `planAndExecuteOrderGraph` 当前编译时没有传 `CompileConfig`

### 实现方案

#### 核心机制：`CompileConfig.interruptBefore`
Spring AI Alibaba Graph 的 `CompileConfig` 支持 `interruptBefore(List<String> nodeNames)` —— 在指定节点**执行前**自动暂停，图状态持久化到 Checkpoint，等待下一次 `resume`。

```java
var compileConfig = CompileConfig.builder()
    .saverConfig(SaverConfig.builder()
        .register(SaverEnum.MEMORY.getValue(), new MemorySaver()).build())
    .interruptBefore(List.of("executor"))   // executor 前暂停
    .build();
```

#### 流程设计
```
第一次请求（生成计划）：
  START → planner → [INTERRUPT before executor] → 返回计划给前端

第二次请求（确认后继续）：
  resume(threadId) → executor → synthesizer → END → 返回结果
```

#### 新增端点
在 `OrderAgentDebugController` 新增 `/confirm` 端点：

```java
@RequestMapping(path="/confirm", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> confirmPlan(
        @RequestParam(name = "chat_id") String chatId,
        @RequestParam(name = "action", defaultValue = "approve") String action) {
    // action=approve → resume 继续执行
    // action=reject  → 清除 checkpoint，中止执行
    RunnableConfig runnableConfig = RunnableConfig.builder().threadId(chatId).build();
    Flux<NodeOutput> result = planAndExecuteHitlGraph.fluxStream(Map.of(), runnableConfig);
    processStream(result, sink);
    ...
}
```

#### 修改文件清单
| 文件 | 变更 |
|------|------|
| `OrderAgent.java` | 新增 `planAndExecuteHitlGraph` Bean，编译时加 `interruptBefore("executor")` 和 `MemorySaver` |
| `OrderAgentDebugController.java` | 新增 `/confirm` 端点；`mode=hitl` 触发新 Bean |

#### 新增文件
无需新增文件，在现有 `OrderAgent.java` 中追加一个 Bean 即可。

---

## 功能二：Memory 主动注入补全（consult / feedback）

### 目标
将 `order-sub-agent` 中已实现的 `MemoryInjectNode + StateGraph` 包装模式，对称地复制到 `consult-sub-agent` 和 `feedback-sub-agent`，使三个子智能体都具备 StateGraph 演示级别的 Memory 主动注入。

### 现状分析
- `MemoryInjectNode.java` 存在于 `order-sub-agent` 包下，逻辑完全可复用
- `consult-sub-agent` 工具来源：`loadbalancedMcpSyncToolCallbacks`（含 memory-search）+ 本地 `ConsultTools`
- `feedback-sub-agent` 工具来源：`loadbalancedMcpSyncToolCallbacks`（含 memory-search）
- 两个 agent 的 Debug Controller 目前只有单一 `react` 模式

### 实现方案

#### 复用 MemoryInjectNode
`MemoryInjectNode` 的逻辑（提取 userId、调用 memory-search、注入 SystemMessage）与具体 Agent 无关，直接在两个模块的对应 `node` 包下各创建一份（或抽取到公共包，但多模块项目保持各自独立更清晰）。

#### ConsultAgent.java 新增 Bean
```java
@Bean("consultAgentWithMemory")
public CompiledGraph consultAgentWithMemory(
        @Qualifier("consultSubAgentBean") ReactAgent reactAgent,
        @Qualifier("loadbalancedMcpSyncToolCallbacks") ToolCallbackProvider nacosTools) throws Exception {
    ToolCallback memorySearchCb = Arrays.stream(nacosTools.getToolCallbacks())
            .filter(t -> "memory-search".equals(t.getToolDefinition().name()))
            .findFirst().orElse(null);
    MemoryInjectNode memoryNode = new MemoryInjectNode(memorySearchCb);
    KeyStrategyFactory factory = () -> Map.of(
            "messages", new ReplaceStrategy(), "user_id", new ReplaceStrategy());
    return new StateGraph("consult_agent_with_memory", factory)
            .addNode("memory_inject", node_async(memoryNode))
            .addNode("react_agent", node_async(state -> {
                Map<String, Object> input = Map.of("messages", state.value("messages").orElse(List.of()));
                return reactAgent.execute(input);
            }))
            .addEdge(START, "memory_inject")
            .addEdge("memory_inject", "react_agent")
            .addEdge("react_agent", END)
            .compile();
}
```

`FeedbackAgent.java` 采用完全相同的模式，Bean 名为 `feedbackAgentWithMemory`。

#### Debug Controller 更新
`ConsultAgentDebugController` 和 `FeedbackAgentDebugController` 各新增 `mode=memory` 分支，复用 order 的模式。

#### 修改文件清单
| 文件 | 变更 |
|------|------|
| `consult-sub-agent/.../config/ConsultAgent.java` | 新增 `consultAgentWithMemory` Bean，新增 `StateGraph/node_async` import |
| `feedback-sub-agent/.../config/FeedbackAgent.java` | 新增 `feedbackAgentWithMemory` Bean |
| `consult-sub-agent/.../controller/ConsultAgentDebugController.java` | 新增 `mode` 参数 + `memory` 分支 |
| `feedback-sub-agent/.../controller/FeedbackAgentDebugController.java` | 同上 |

#### 新增文件
| 文件 | 说明 |
|------|------|
| `consult-sub-agent/.../node/MemoryInjectNode.java` | 从 order-sub-agent 复制，修改 package 声明 |
| `feedback-sub-agent/.../node/MemoryInjectNode.java` | 同上 |

---

## 功能三：前端 UX 功能

### 目标
1. **确认弹窗**：当 Agent 回答中包含写操作意图（创建/取消/修改）时，弹出确认 Modal，用户点"确认"后才发送第二次请求触发实际执行
2. **RAG 来源展示**：在 Agent 回答后展示检索到的知识库来源文档标题

### 现状分析

**ChatInterface.vue** 已有：
- SSE 流式接收（`ReadableStream`）
- `chatStore` 管理消息列表
- Ant Design Vue 组件库（Modal、Tag、Card 均可直接用）
- `MarkdownRenderer.vue` 渲染 Markdown

**chat.ts API** 目前只有一个 `sendMessage()` 方法，直接 GET `?chat_id&user_id&user_query`。

**后端现状**：
- Supervisor `/api/assistant/chat` 是唯一对外接口，子 Agent 通过 A2A 调用
- 目前没有独立的"确认"端点（HITL 实现后 order-sub-agent 会有 `/api/order-sub-agent/confirm`）

### 实现方案

#### 3.1 确认弹窗

**触发机制（前端检测）**：在 SSE 流结束后，检测回答文本中是否包含写操作关键词：
```ts
const WRITE_KEYWORDS = ['创建', '预约', '申请', '取消', '修改备注', '为您办理', '已为您预约']
const needsConfirm = WRITE_KEYWORDS.some(kw => assistantReply.includes(kw))
```

若命中，不立即发送执行请求，而是弹出 Modal 展示计划摘要，等待用户确认。

**方案 A（推荐）：纯前端关键词检测**
- 前端在 streaming 结束后检测关键词
- 用户确认 → 在消息末尾追加 `[CONFIRMED]` 后重新发送，后端 Prompt 识别此标志直接执行
- **优点**：改动最小，后端无需改动
- **缺点**：关键词误触发，精度有限

**方案 B：结合 HITL 端点**
- 第一次请求正常发送，后端 HITL 暂停后返回计划
- 前端展示计划 Modal，用户确认后调用 `/confirm?chat_id=xxx&action=approve`
- **优点**：准确可靠，与 HITL 知识点深度结合
- **缺点**：依赖功能一（HITL）先完成

> **建议采用方案 B**，与 HITL 联动实现更完整的知识点演示。

**前端 Modal 核心代码**：
```vue
<!-- ChatInterface.vue 新增 -->
<a-modal v-model:open="confirmVisible" title="确认执行操作" @ok="handleConfirm" @cancel="handleReject">
  <p>Agent 即将执行以下操作，请确认：</p>
  <pre>{{ pendingPlan }}</pre>
</a-modal>
```

```ts
// 新增状态
const confirmVisible = ref(false)
const pendingPlan = ref('')
const pendingChatId = ref('')

// 检测 HITL 暂停信号（后端在 SSE 中发送特殊事件标志）
// 或前端检测关键词
const handleConfirm = async () => {
  confirmVisible.value = false
  await chatApiService.confirmPlan(pendingChatId.value, 'approve')
}
```

#### 3.2 RAG 来源展示

**后端改造（轻量）**：在 `ConsultTools.searchKnowledge()` 返回时，在结果末尾追加来源标记：
```java
// ConsultTools.java 修改
return result + "\n\n---\n📚 来源：校园知识库";
```

或更精确地，修改 `ConsultService.searchKnowledge()` 在返回内容时附带文档标题：
```java
// 已有 documents 列表，可取 document.getMetadata().get("title") 或 document.getId()
StringBuilder sourceInfo = new StringBuilder("\n\n---\n**参考来源：**\n");
for (Document doc : documents) {
    String title = (String) doc.getMetadata().getOrDefault("source", "校园知识库");
    sourceInfo.append("- ").append(title).append("\n");
}
return finalResult + sourceInfo;
```

**前端展示**：`MarkdownRenderer.vue` 已支持 Markdown 渲染，来源信息以 `---` 分隔符 + Markdown 列表格式自然渲染，无需额外改动。

#### 修改文件清单
| 文件 | 变更 |
|------|------|
| `frontend/src/components/ChatInterface.vue` | 新增确认 Modal 组件、confirm 逻辑、关键词检测 |
| `frontend/src/api/chat.ts` | 新增 `confirmPlan(chatId, action)` 方法 |
| `consult-sub-agent/.../service/ConsultService.java` | `searchKnowledge()` 返回值末尾附加来源信息 |

---

## 实施顺序建议

| 优先级 | 功能 | 理由 |
|--------|------|------|
| 1️⃣ | Memory 主动注入补全 | 改动最小，纯复制模式，风险低 |
| 2️⃣ | Human-in-the-Loop | 后端逻辑，不依赖前端，可独立验证 |
| 3️⃣ | 前端 UX | 依赖 HITL 端点（方案 B），放最后 |

---

## 待确认事项

1. **HITL 确认弹窗方案**：选方案 A（前端关键词检测，简单）还是方案 B（结合 HITL 端点，准确）？
2. **RAG 来源信息格式**：是直接在回答文本末尾追加来源，还是通过单独的 SSE 事件字段传递（需要改协议）？
3. **Memory 主动注入**：consult/feedback 的 `MemoryInjectNode` 是各自独立复制，还是抽到 `supervisor-agent` 公共包？（多模块项目建议各自独立，避免跨模块依赖）

---

*调研方案结束，待确认后进入实施阶段。*
