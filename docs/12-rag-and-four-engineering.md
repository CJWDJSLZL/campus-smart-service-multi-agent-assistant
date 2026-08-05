# RAG 与四大工程知识详解

> 本文档系统梳理 RAG 技术体系和 Prompt/Context/Harness/Loop 四大工程的完整知识点，结合项目实现细节说明。

---

## 第一部分：RAG（检索增强生成）

### 1.1 RAG 的核心问题与解决思路

**LLM 的两大根本缺陷**：
1. **知识截止**：参数中的知识在训练后固化，无法获取新信息
2. **幻觉（Hallucination）**：对不确定的问题会编造听起来合理的错误答案

**RAG 的解决思路**：
将信息检索（IR）和文本生成（LG）结合：
```
Query → [Retrieve] → 相关文档 → [Augment] → 增强 Prompt → [Generate] → 答案
```
LLM 不再从参数中"回忆"答案，而是基于检索到的当前文档"阅读"后作答。

---

### 1.2 知识库构建（Indexing 阶段）

**文档加载**：将原始文档（本项目：16 份 Markdown 文件）读入系统。

**文本分块（Chunking）**：
将长文档切分为适合向量化的块。分块策略影响检索质量：
- 块太小：单块语义不完整，检索结果碎片化
- 块太大：向量"稀释"，相似度计算不精准

本项目知识库文档结构设计遵循 RAG 分块友好原则：
- 每个 `##` 二级标题下内容不超过 300 字
- FAQ 每条 Q&A 独立成段
- 同义词在同一段落集中（如"在读证明 = 学籍证明 = 学生身份证明"）

**向量化（Embedding）**：
使用 Embedding 模型将文本块转换为高维向量（如 768 维），语义相近的文本向量距离近（余弦相似度高）。本项目通过阿里云百炼平台自动完成向量化和索引建立。

**向量存储**：
向量及原始文本存储在向量数据库（本项目：阿里云百炼向量库，知识库 ID `m36khcyb7v`）。

---

### 1.3 检索阶段（Retrieval）

**基础向量检索**：

```java
// ConsultService.java
DashScopeDocumentRetrieverOptions options = DashScopeDocumentRetrieverOptions.builder()
    .withEnableReranking(enableReranking)   // 是否开启 Rerank
    .withRerankTopN(rerankTopN)             // Rerank 后取 Top-N（当前：3）
    .withRerankMinScore(rerankMinScore)     // 最低分过滤（当前：0）
    .build();
List<Document> documents = dashscopeApi.retriever(indexID, rewrittenQuery, options);
```

内部流程：
1. 对 `rewrittenQuery` 调用 Embedding 模型获取查询向量
2. 在向量库中执行 ANN（近似最近邻）搜索，召回候选文档
3. 对候选文档应用 Rerank 精排
4. 返回 Top-N 结果

**查询改写（Query Rewriting）**：

问题：用户的口语化短查询（"奖学金"）与知识库中的长文本块向量距离较大。

解决：检索前用 LLM 扩展查询语义：

```java
// ConsultService.rewriteQuery()
String prompt = "你是校园服务检索优化助手。将用户查询改写为更适合知识库检索的形式：\n"
    + "1. 展开缩写词（如"奖学金"→"奖学金申请政策和评定条件"）\n"
    + "2. 添加同义词和领域关键词\n"
    + "3. 补充场景词（申请流程/办理步骤/所需材料）\n"
    + "4. 只输出改写后的查询语句，不要任何解释\n"
    + "原始查询：" + originalQuery;
```

改写示例：
| 原始 | 改写后 |
|------|--------|
| "奖学金" | "奖学金申请条件政策评定流程所需材料" |
| "图书馆预约" | "图书馆研讨间预约规则申请流程人数注意事项" |
| "转专业" | "转专业申请条件流程时间补修课程学分" |

**Rerank 重排序**：

粗排（向量相似度）是 ANN 的结果，Top-10 中排名靠前的不一定语义最相关。
精排（Rerank）使用交叉编码器（Cross-Encoder）对每个（query, document）对单独计算相关分数。

对比：
| 方法 | 模型类型 | 精度 | 速度 |
|------|---------|------|------|
| 向量相似度（Bi-Encoder）| 双塔模型 | 中 | 快（可预计算文档向量）|
| Rerank（Cross-Encoder）| 单塔交叉编码 | 高 | 慢（每对单独计算）|

实践策略：粗排召回 Top-20，精排取 Top-3（`rerank-top-n=3`）。精排的慢速问题通过减少候选集（20而非全量）缓解。

**混合检索（Hybrid Retrieval）**：

纯向量检索对精确名词（服务名称、人名、编号）召回不稳定，关键词检索弥补这一缺陷：

```java
// ConsultService.buildExactMatchContext()
private String buildExactMatchContext(String query) {
    List<Product> matched = productMapper.selectByNameLike(query);
    if (matched.isEmpty()) return "";
    StringBuilder sb = new StringBuilder("【服务事项精确匹配】\n");
    for (Product p : matched) {
        sb.append("- ").append(p.getName()).append("：").append(p.getDescription()).append("\n");
    }
    return sb.toString();
}
```

合并策略：精确匹配结果前置（高优先级） + 向量检索结果后置：
```java
if (!exactMatchContext.isEmpty()) result.append(exactMatchContext).append("\n\n");
for (Document doc : documents) result.append(doc.getText());
```

---

### 1.4 生成阶段（Generation）

**上下文注入**：检索结果作为 `ToolResultMessage` 注入 messages，LLM 基于这段上下文作答：

```
System: 你是校园咨询 Agent...
User: 奖学金怎么申请？
Tool(consult-search-knowledge): 【奖学金申请政策】...【参考来源：policy_scholarship.md】
Assistant: 根据校园知识库，奖学金申请需要以下材料...
```

**来源引用**：
```java
// 从 Document.getMetadata() 提取文档来源
String source = doc.getMetadata().get("title");  // → "奖学金政策全览"
sourceInfo.append("- ").append(source).append("\n");
```
前端 MarkdownRenderer 渲染 `**参考来源：**` 段落，用户可追溯答案出处。

---

### 1.5 RAG 质量评测

**评测维度**：
- **召回率（Recall@K）**：正确文档是否在 Top-K 中
- **精确率（Precision@K）**：Top-K 中正确文档的比例
- **MRR（Mean Reciprocal Rank）**：正确文档排名的倒数均值
- **关键词命中率**：回答中预期关键词的出现比例

**本项目评测数据集**：`data/golden_set/consult_golden.txt`（10 条咨询场景用例），每条含 `expected_output_keywords`。

---

## 第二部分：四大工程

### 2.1 Prompt 工程

#### 什么是 Prompt 工程？

Prompt 工程是通过设计、优化、管理 LLM 的输入指令（Prompt），使模型在特定任务上达到期望行为的工程实践。

#### Prompt 的组成要素

```
System Prompt（系统指令）：
  ├── 角色定义：你是谁
  ├── 能力边界：你能做什么/不能做什么
  ├── 工作流程：如何一步步执行
  ├── 工具映射：什么意图用什么工具
  ├── 约束规则：边界和限制
  └── Few-shot 示例：边界案例示范

User Message（用户输入）

Tool Result（工具调用结果）

Assistant Message（历史回答）
```

#### 结构化 System Prompt 设计

本项目 4 个 Agent 的 System Prompt 均遵循固定七段结构：

```
1. 角色与职责   → 明确身份和使命
2. 核心能力    → 列举可完成的任务
3. 用户输入信息 → 告知模型可能收到什么
4. 工作流程    → step-by-step 执行指引
5. 智能策略    → 特殊场景处理方式
6. 约束        → 不能做的事、越权限制
7. Few-shot   → 边界案例示范
```

**关键设计原则**：
- **正向约束**："只有工具返回成功才告知用户成功"
- **负向约束**："绝对不允许查询他人记录"
- **意图→工具映射**：明确告知模型每种场景用哪个工具，减少推理歧义

#### Few-shot 示例的作用机制

LLM 是 in-context learner，在 Prompt 中提供示例比纯文字描述的泛化效果好：

```yaml
# order-agent Prompt 中的 Few-shot
示例1: 用户说"帮我预约" → 先澄清"您想预约哪项服务"，不要直接调用工具
示例2: 用户说"按上次一样" → 先查询最近记录，向用户确认后才创建
示例5: 用户说"奖学金什么时候申请" → 告知"政策类问题由咨询助手处理"
```

没有示例时，模型遇到"帮我预约"可能直接猜测服务名称调用工具。有示例后模型学会了"模糊请求需先澄清"的行为模式。

#### BeanOutputConverter（Schema 注入）

```java
private final BeanOutputConverter<ExecutionPlan> converter =
    new BeanOutputConverter<>(ExecutionPlan.class);

// 1. 获取 Schema 说明注入 Prompt
String formatInstructions = converter.getFormat();
// 输出示例：
// "Respond in JSON format. Schema: {"goal":"string","needsClarification":"boolean",...}"

// 2. 将 formatInstructions 替换 Prompt 中的占位符
systemPrompt = TEMPLATE.replace("{format_instructions}", formatInstructions);

// 3. 解析 LLM 输出
ExecutionPlan plan = converter.convert(llmOutputJson);
```

**原理**：通过 Jackson 反射 Java Record 的字段类型生成 JSON Schema 字符串。LLM 接受 Schema 约束后按格式输出，相比自由文本提高结构化输出成功率（由 ~70% 提升至 ~95%）。

#### 工具描述标准化（四要素）

工具 description 是 LLM 选择工具的唯一依据，质量直接影响工具调用准确率：

```java
// 改造前
@Tool(name = "campus-cancel-service-record",
      description = "取消记录")  // 模糊，模型不知边界

// 改造后（动词+对象+场景+限制）
@Tool(name = "campus-cancel-service-record",
      description = "取消指定用户的校园事务办理或预约记录（需提供 userId 和 orderId）。"
                  + "执行前须与用户确认记录编号；仅能取消属于该 userId 的记录。"  // 限制
                  + "不适用于查询记录，取消前若无记录编号请先调用查询工具。")   // 排除误用
```

---

### 2.2 Context 工程

#### 什么是 Context 工程？

Context 工程是管理 LLM 每次调用时输入上下文（Context Window）的工程实践：哪些信息放入，如何排列，如何压缩，如何在多轮/多 Agent 间传递。

#### Context Window 的约束

LLM 的 Context Window 有限（如 qwen-plus 支持 128k tokens），超出部分会被截断或导致质量下降。过长的上下文也会稀释当前意图的权重（LLM 对距离远的信息注意力衰减）。

#### 多层上下文构建

本项目每次请求的上下文由三层构成：

```
Layer 1 - 用户身份（嵌入用户消息）：
  "帮我预约图书馆<userId>10001</userId>"

Layer 2 - 会话状态（通过 RunnableConfig）：
  threadId(chatId) → MemorySaver 恢复历史 State

Layer 3 - State 键值（传入 Graph）：
  {input: "...", chat_id: "xxx", user_id: "10001"}
```

`<userId>` 标签设计：避免修改 A2A gRPC 消息格式（协议成本高），通过约定消息末尾标签传递用户身份，子 Agent 从文本中解析提取。

#### Checkpoint 状态恢复机制

```
请求 1 (chatId=abc123)：
  RunnableConfig.threadId("abc123")
  Saver.load("abc123") → null（首次）
  执行完后：Saver.save("abc123", {messages: [user: "你好", assistant: "我是校园助手..."]})

请求 2 (chatId=abc123)：
  RunnableConfig.threadId("abc123")
  Saver.load("abc123") → {messages: [...历史...]}
  合并当前 input 与历史 state
  Agent 感知到上一轮对话，维持上下文连续性
```

#### Memory 主动注入 vs 被动调用

| 方式 | 机制 | 问题 |
|------|------|------|
| 被动调用 | Prompt 要求 LLM 自己决定是否调用 memory-search | LLM 可能跳过调用，偶发失忆 |
| 主动注入 | MemoryInjectNode 在每次请求前强制检索并注入 | 确定性触发，但每次都有额外 Mem0 API 调用 |

**主动注入的代码位置**：
```java
// common/node/MemoryInjectNode.java
String toolInput = String.format("{\"userId\":\"%s\",\"query\":\"用户偏好和历史习惯\"}", userId);
String memoryResult = memorySearchTool.call(toolInput);

List<Message> enriched = new ArrayList<>();
enriched.add(new SystemMessage("【用户历史偏好记忆】\n" + memoryResult));  // 前置注入
enriched.addAll(messages);
return Map.of("messages", enriched);
```

注入为 `SystemMessage`（权重高于 UserMessage）而非普通消息，确保偏好信息优先被 LLM 参考。

#### 滑动窗口压缩

**触发条件**：`messages.size() > 20`（默认值，可配置）

**压缩算法**：
```
原始 messages（25条）：
[m1, m2, ..., m19, m20, m21, m22, m23, m24, m25]
                  ↑保留最近 keepRecentCount=6 条

压缩步骤：
1. toCompress = messages[0..18]（19条）
2. 构建历史文本：[user]: m1\n[assistant]: m2\n...
3. 调用 LLM：请用 3-5 句话提炼关键信息（userId、服务名称、记录编号）
4. 生成 summary SystemMessage

压缩后（7条）：
[SystemMessage("【历史对话摘要】用户 10001 已预约..."), m20, m21, m22, m23, m24, m25]
```

**Token 节省**：实测约 72%（14 轮对话 28 条消息场景）

**降级策略**：LLM 压缩失败时保留原始 messages，不中断主流程。

---

### 2.3 Harness 工程

#### 什么是 Harness 工程？

Harness 工程（测试框架工程）是为 AI 系统建立自动化评测基础设施的工程实践，使系统质量从"依赖人工判断"变为"数据驱动量化"。

类比软件工程中的自动化测试，Harness 工程是 AI Agent 的"测试框架"。

#### Golden Set（黄金评测集）

**定义**：预先标注好"标准答案"的评测数据集，用于量化 Agent 能力。

**设计原则**：
- 覆盖多样场景（正常/边界/错误）
- 标注粒度明确（预期 Agent、工具、关键词）
- 定期维护更新（业务变更后同步更新）

**本项目 Golden Set 格式**：
```
--- CASE_O001 ---
input: 帮我预约图书馆研讨间，明天下午，4个人
user_id: 10001
expected_agent: order_agent                    # 路由准确率
expected_tools: [campus-validate-service-item, # 工具调用正确性
                 campus-check-service-capacity,
                 campus-create-service-record]
expected_output_keywords: [预约成功, CAMPUS_]  # RAG/回答质量
---
```

**评测维度**：
| 维度 | 计算方式 | 衡量的能力 |
|------|---------|---------|
| 路由准确率 | 实际 Agent == 预期 Agent 的比例 | Supervisor 意图识别 |
| 工具调用正确率 | 实际工具列表包含预期工具的比例 | ReactAgent 工具选择 |
| 关键词命中率 | 回答中包含预期关键词的比例 | RAG 召回质量 |
| 澄清触发准确率 | 应澄清时是否触发澄清的比例 | 信息完整性判断 |

#### EvaluationClassifierNode（自动化评分节点）

```java
// 使用 BeanOutputConverter 确保评测结果可被程序解析
public record EvaluationResult(
    String user,        // 用户 ID
    String time,        // 评价时间
    String complaint,   // "yes" | "no"
    int satisfaction,   // 0-5
    String summary      // 核心诉求摘要
) {}

private final BeanOutputConverter<EvaluationResult> converter =
    new BeanOutputConverter<>(EvaluationResult.class);
```

**为什么必须结构化输出**：
- 评测结果是文本 → 无法用代码判断满意度是否 < 3
- 评测结果是 `EvaluationResult` → `result.satisfaction() < 3` 直接触发告警

#### IterationNode 批量评测

```java
StateGraph iterationNode = IterationNode.converter()
    .inputArrayJsonKey("sessions")           // 输入：N条反馈记录
    .outputArrayJsonKey("analysis_results")  // 输出：N条评分结果
    .subGraph(sessionAnalysisGraph)          // 对每条记录运行分析子图
    .convertToStateGraph();
```

**批量处理原理**：
串行遍历数组，每个元素独立运行 subGraph，结果追加到输出数组。串行而非并发，防止 DashScope API 限速（QPS 限制）。

#### 条件分支告警

```java
// 满意度 < 3 时走告警路径
.addConditionalEdges("session_result_summary_node",
    state -> {
        double avg = calculateAvgSatisfaction(state);
        return avg < 3.0 ? "alert" : "normal";
    },
    Map.of("normal", "message_parse", "alert", "alert_message_parse")
)
```

**决策树**：
```
平均满意度
├── ≥ 3.0 → 普通路径 → 标准日报格式 → DingTalk 正常推送
└── < 3.0 → 告警路径 → 红色🚨格式 → DingTalk 紧急推送（标注【紧急】）
```

---

### 2.4 Loop 工程

#### 什么是 Loop 工程？

Loop 工程是让 Agent 系统从"单次响应"升级为"持续自主运行"的工程实践，涵盖定时循环、数据迭代循环、人机交互循环、容错重试循环等。

#### IterationNode 数据循环

**原理**：将 N 条数据的批量处理封装为图内的 for 循环，每次迭代独立运行子图。

对比手写循环：
```java
// 手写（命令式，混入业务逻辑）
for (String session : sessions) {
    EvaluationResult result = analyze(session);
    results.add(result);
}

// IterationNode（声明式，子图可复用）
IterationNode.converter()
    .inputArrayJsonKey("sessions")
    .subGraph(sessionAnalysisGraph)  // 复用已有图
    .convertToStateGraph();
```

#### 定时 Loop（@Scheduled）

```java
@Scheduled(cron = "0 0 9 * * ?")   // 每天 09:00
public void runDailyReport() {
    dailyReportGraph.invoke(Map.of());
}
```

Cron 表达式格式（6 位 Quartz）：`秒 分 时 日 月 周`
- `0 0 9 * * ?` = 每天 09:00:00
- `0 0 10 ? * MON` = 每周一 10:00:00
- `0 */5 * * * ?` = 每 5 分钟

**@ConditionalOnProperty 控制激活**：
```java
@ConditionalOnProperty(name = "xxl.job.enabled", havingValue = "false", matchIfMissing = true)
```
`xxl.job.enabled` 不配置（默认 false）或显式设 false 时激活本地调度。生产切换 XxlJob 只需改一个配置项，零代码改动。

#### Human-in-the-Loop（HITL）

**核心机制**：`CompileConfig.interruptBefore()`

```
interruptBefore(["executor"]) 的执行逻辑：

Graph 执行到 executor 节点前：
  → 检查 interruptBefore 列表，命中 "executor"
  → 保存当前 State 到 MemorySaver（包含 execution_plan）
  → 向 Flux 发送 interrupt 标识
  → 停止执行

/confirm?action=approve (相同 threadId)：
  → MemorySaver 加载 State（含 execution_plan）
  → 传入空 input（使用已保存的 State）
  → 从 executor 节点继续执行
  → executor → synthesizer → END
```

**HITL 的必要性**：写操作（创建/取消/修改记录）是不可逆的，一旦执行需要付出代价才能撤销。HITL 将执行授权权转移给用户，在 AI 可信度不足 100% 的阶段保证系统安全性。

#### 工具调用 Retry（指数退避）

```java
private static final int MAX_RETRY = 3;

for (int retry = 0; retry <= MAX_RETRY; retry++) {
    try {
        if (retry > 0) Thread.sleep(500L * retry);  // 500ms, 1000ms, 1500ms
        return tool.call(step.toolParameters());
    } catch (Exception e) {
        lastException = e;
    }
}
```

**指数退避原理**：失败通常由瞬时故障（网络抖动、服务重启）引起，等待一段时间后重试成功率高。等待时间随重试次数增加（`delay = base * retry`），避免高频重试加剧服务压力。

**对比固定间隔重试**：指数退避能适应不同恢复时间的故障，固定间隔在长时故障下浪费次数，短时故障下等待过久。

#### 多轮澄清 Loop（Clarification Loop）

```
轮 N: PlannerNode 检查信息完整性
  if (缺少 productName 或 orderId):
      ExecutionPlan.needsClarification = true
      ExecutionPlan.clarificationQuestion = "您想预约哪项服务？"
      → Controller 检测到 clarification_question
      → 直接返回追问，不进 executor
  else:
      → 正常生成 steps，进 executor 执行

用户补充信息后重新请求 → 进入下一轮
```

**状态保持**：每轮澄清使用相同 `chat_id`（threadId），Checkpoint 保存对话历史，下一轮 PlannerNode 可看到用户之前的补充信息。

**前端检测逻辑**：
```javascript
// OrderAgentDebugController.processStreamWithClarification()
.doOnNext(output -> {
    if ("planner".equals(output.node())) {
        String q = state.value("clarification_question").orElse(null);
        if (q != null) {
            sink.tryEmitNext(ServerSentEvent.builder(q).build());
            sink.tryEmitComplete();  // 提前结束，等待用户输入
        }
    }
})
```

---

### 2.5 四大工程的相互关系

```
Prompt 工程：定义 Agent "怎么想"
  ↓ 系统指令约束行为 + Few-shot 示范边界
Context 工程：管理 Agent "看到什么"
  ↓ Checkpoint 保持连续性 + Memory 注入个性化 + 滑动窗口压缩长度
Loop 工程：让 Agent "持续做"
  ↓ 定时触发 + 批量迭代 + 错误重试 + 人机交互 + 澄清追问
Harness 工程：量化 Agent "做得好不好"
  ↓ Golden Set 基准 + 自动评分 + 指标告警
```

**实践顺序**：先做好 Prompt（定义行为）→ 优化 Context（给足信息）→ 建立 Loop（持续运行）→ 建立 Harness（量化评测）→ 根据 Harness 结果回头优化 Prompt 和 Context，形成闭环迭代。
