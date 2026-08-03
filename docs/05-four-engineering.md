# 四大工程详解

本文档详细介绍项目中的 Prompt 工程、Context 工程、Harness 工程、Loop 工程四大 AI 工程范式，每个工程包含概念说明、实现位置和完整技术细节。

---

## 一、Prompt 工程

Prompt 工程关注**如何设计指令，使 LLM 的行为可预期、可控制**。

### 1.1 结构化 System Prompt（5 个 Agent）

每个 Agent 的 System Prompt 都遵循固定结构，存储在各模块的 `application.yml` 中：

```
角色与职责 → 核心能力 → 用户输入信息 → 工作流程 → 智能策略 → 约束 → 记忆使用原则 → Few-shot 示例
```

**supervisor-agent Prompt 核心要素**：
```yaml
supervisor-agent-instruction: |
  角色与职责:
  你是校园智能服务多 Agent 助手系统的总调度智能体...
  
  路由规则:                          # 明确每种意图对应哪个子 Agent
  1. 用户询问政策、流程时 → consult_agent
  2. 用户要求预约、申请时 → order_agent
  3. 用户表达不满、投诉时 → feedback_agent
  
  约束:
  - 只负责协调和路由，不直接处理具体业务
  - 不编造政策、办理结果
  
  Few-shot 边界示例（路由决策参考）:    # 关键：路由边界案例
  示例1: 用户说"奖学金多少钱" → 路由到 consult_agent
  示例4: 用户说"你好" → 回复问候，不路由任何子 Agent
```

**order-agent Prompt 的工具选择引导**（关键设计）：
```yaml
工作流程:
3. 根据意图选择合适的事务工具：
   - 创建办理/预约记录：使用 campus-create-service-record
   - 查询指定记录：使用 campus-get-service-record-by-user
   - 查询用户记录列表：使用 campus-get-service-records-by-user
   - 多条件查询：使用 campus-query-service-records
   - 修改备注：使用 campus-update-service-record-remark
   - 取消记录：使用 campus-cancel-service-record
```

明确告诉 LLM 每种意图对应哪个工具，大幅降低工具调用错误率。

---

### 1.2 Few-shot 示例注入

**目的**：为 LLM 提供边界案例示范，让模型在模糊场景下做出正确判断。

**四个 Agent 各自的 Few-shot 示例设计**：

**Supervisor（路由边界）**：
```
示例1: 奖学金多少钱 → consult_agent（政策咨询）
示例2: 帮我预约图书馆 → order_agent（事务办理）
示例3: 宿舍空调修了好几天还没来 → feedback_agent（投诉）
示例4: 想了解转专业流程然后申请 → 先 consult_agent 查政策，再 order_agent 办理
示例5: 你好 → 回复问候，不路由任何子 Agent
示例6: 取消我的预约 → order_agent（记录操作），非 feedback_agent
```

**Order Agent（工具选择边界）**：
```
示例1: "帮我预约" → 先澄清"您想预约哪项服务"，不直接调用 campus-create-service-record
示例2: "按上次一样再预约" → 先调用 campus-get-latest-service-record-by-user 查询，再确认
示例3: "取消我的记录" → 先查出记录列表让用户确认编号，再取消
示例4: "我没有预约成功" → 若工具返回失败，如实告知，不编造成功结果
示例5: "奖学金什么时候申请" → 回复"政策类问题由咨询助手处理"
```

**Consult Agent（回答策略边界）**：
```
示例3: "图书馆什么时候开放" → 先检索知识库，无结果时说"不确定，建议拨打图书馆电话确认"
示例4: "我要投诉" → 引导到 feedback_agent
```

**Feedback Agent（情绪处理边界）**：
```
示例1: "空调修了三天还没来，太烂了！" → 先安抚，再调用 feedback-create-feedback
示例3: "建议延长图书馆开放时间" → 肯定建议，记录（feedbackType=4），不承诺"一定会延长"
示例4: "我想投诉宿管" → 先澄清具体事件，再创建反馈
```

---

### 1.3 结构化输出 Prompt（Schema 注入）

**目的**：通过 `BeanOutputConverter.getFormat()` 自动生成 JSON Schema，注入 Prompt，强制 LLM 输出符合 Java Record 定义的 JSON。

**两处实现**：

**① EvaluationClassifierNode Prompt**：
```java
// converter.getFormat() 生成类似：
// "Respond in JSON format using the following schema: {"user": "...", "complaint": "yes|no", ...}"
systemPromptTemplate.render(Map.of(
    "inputText", inputText,
    "format_instructions", converter.getFormat()  // 替换 {format_instructions} 占位符
))
```

**② PlannerNode Prompt（包含多轮澄清规则）**：
```
{format_instructions}   ← converter.getFormat() 注入 ExecutionPlan 的 JSON Schema

【多轮澄创 Loop 规则】
- campus-create-service-record 必须有：userId、具体服务事项名称
- 信息不完整时：needsClarification=true，clarificationQuestion 写出追问内容
```

---

### 1.4 工具描述标准化（四要素）

**目的**：工具 `description` 决定 LLM 的工具选择准确率。标准化格式：**动词 + 对象 + 场景 + 限制**。

**改造前后对比**：

改造前（简单描述）：
```java
@Tool(name = "campus-cancel-service-record",
      description = "根据用户ID和记录编号取消校园事务办理/预约记录。")
```

改造后（四要素）：
```java
@Tool(name = "campus-cancel-service-record",
      description = "取消指定用户的校园事务办理或预约记录（需提供 userId 和 orderId）。"
                  + "执行前须与用户确认记录编号；仅能取消属于该 userId 的记录。"
                  + "不适用于查询记录，取消前若无记录编号请先调用查询工具。")
```

关键改进：
- **限制条件**明确（须先有 orderId，须属于该 userId）
- **适用/不适用场景**列出（不适用于查询）
- **前置条件**说明（取消前先查询）

共标准化 13 个 `@Tool` 描述（order-mcp-server 10 个 + feedback-mcp-server 4 个）。

---

## 二、Context 工程

Context 工程关注**如何构建和管理每次调用 LLM 时的上下文信息**，使模型在每次调用时拥有足够且相关的信息。

### 2.1 多层上下文构建

**位置**：`SupervisorAgentController.java`

每次用户请求会构建三层上下文：

```java
// 层1：用户身份（XML 标签方式，子 Agent 解析）
String userInput = userQuery + "<userId>" + userID + "</userId>";

// 层2：会话状态（通过 RunnableConfig 传递给 Checkpoint）
RunnableConfig runnableConfig = RunnableConfig.builder()
    .threadId(chatID)               // 会话 ID，用于恢复 Checkpoint
    .addMetadata("user_id", userID) // 元数据
    .build();

// 层3：State 键值（传入图的输入 state）
Map<String, Object> input = Map.of(
    "input",   userInput,
    "chat_id", chatID,
    "user_id", userID
);
```

---

### 2.2 Memory 主动注入（个性化上下文）

**位置**：`common/node/MemoryInjectNode.java`

在 ReactAgent 推理前，将用户历史偏好以 `SystemMessage` 形式注入上下文最前端：

```
messages 列表原始状态：
[UserMessage("帮我预约图书馆")]

注入后：
[
  SystemMessage("【用户历史偏好记忆】该用户常在下午预约，偏好研讨间4号间..."),
  UserMessage("帮我预约图书馆")
]
```

**为什么是 SystemMessage 而非 UserMessage**：SystemMessage 拥有更高的 LLM 注意力权重，且语义上表示"系统提供的背景信息"，符合 Memory 的定位。

---

### 2.3 Checkpoint 状态恢复（多轮对话上下文连续性）

**位置**：三个子 Agent 的 `config/xxxAgent.java`

同一 `chat_id` 的连续请求，通过 Checkpoint 恢复前一轮的 `OverAllState`，Agent 可以感知到：
- 上一轮用户说了什么（messages 历史）
- 上一轮调用了哪些工具（tool call history）
- 当前任务进行到哪一步（Plan-Execute 中的 execution_plan）

**状态 key 策略**：所有 key 使用 `ReplaceStrategy`（新值直接覆盖旧值），而非 Append（追加），防止状态无限增长。

---

### 2.4 RAG 检索结果注入（事实依据上下文）

**位置**：`ConsultTools.searchKnowledge()` → `ConsultService.searchKnowledge()`

将知识库检索结果作为工具调用结果注入 `messages`，构成 LLM 回答的事实依据：

```
messages 最终状态（Agent 生成回答时）：
[
  SystemMessage(agent instruction),
  SystemMessage("【用户历史偏好记忆】..."),   ← Memory 注入
  UserMessage("奖学金怎么申请"),
  AssistantMessage("[thinking: 需要检索知识库...]"),
  ToolResultMessage("consult-search-knowledge", "奖学金通常包括...【精确匹配】图书馆研讨间预约：...\n---\n**参考来源：**\n- 奖学金政策全览"),
]
```

---

### 2.5 滑动窗口上下文压缩

**位置**：`common/node/ContextCompressionNode.java`

**问题**：多轮对话后 `messages` 列表无限增长，超过 LLM 的 Context Window（约 128k tokens）。

**解决方案**：messages 超过 `maxMessages`（默认 20）条时，调用 LLM 对早期对话进行摘要压缩。

**压缩算法**：
```
原始（25条）：[msg1, msg2, ..., msg19, msg20, msg21, msg22, msg23, msg24, msg25]
              ←─── 待压缩（19条）───→   ←────── 保留最近 6 条 ──────────→

压缩后（7条）：[SystemMessage(摘要内容), msg20, msg21, msg22, msg23, msg24, msg25]
```

**压缩 Prompt**：
```
请用 3-5 句话提炼以下对话历史的关键信息，
保留用户身份、已办理/查询的服务名称、关键数据（记录编号、时间偏好等）：
{历史对话文本}
```

**降级策略**：LLM 压缩失败时静默跳过，不修改 messages，保证主流程不中断。

**触发方式**：`consultSubAgent Debug ?mode=compress`（`consultAgentWithCompression` Bean）

---

### 2.6 跨 Agent 上下文传递（`<userId>` 标签）

Supervisor 在转发请求时，将 `userId` 以 XML 标签形式嵌入用户消息末尾：

```java
String userInput = userQuery + "<userId>" + userID + "</userId>";
```

`MemoryInjectNode` 从消息文本中解析 userId：
```java
int start = text.indexOf("<userId>");
int end = text.indexOf("</userId>");
if (start >= 0 && end > start) {
    userId = text.substring(start + 8, end).trim();
}
```

这种"带外数据"传递方式确保 userId 在 A2A 调用链中不丢失，无需修改 A2A 协议。

---

## 三、Harness 工程

Harness 工程关注**建立自动化的 Agent 能力评测基础设施**，通过批量运行、收集结果、分析质量来驱动迭代。

### 3.1 校园场景 Golden Set

**位置**：`supervisor-agent/src/main/resources/data/golden_set/`

**文件结构**：
```
golden_set/
├── consult_golden.txt   (10 条，政策咨询场景)
├── order_golden.txt     (10 条，事务办理场景)
└── feedback_golden.txt  (10 条，反馈投诉场景)
```

**单条用例格式**：
```
--- CASE_O001 ---
input: 帮我预约图书馆研讨间，明天下午，4个人
user_id: 10001
expected_agent: order_agent
expected_tools: [campus-validate-service-item, campus-check-service-capacity, campus-create-service-record]
expected_output_keywords: [预约成功, CAMPUS_, 研讨间]
---
```

**字段说明**：
| 字段 | 说明 |
|------|------|
| `input` | 用户输入 |
| `user_id` | 测试用 userId |
| `expected_agent` | 预期路由子 Agent |
| `expected_tools` | 预期调用工具列表（按顺序） |
| `expected_output_keywords` | 回答中必须出现的关键词 |
| `clarification_required` | 是否需要触发澄清 |
| `expected_feedback_type` | 反馈类型（1-4） |
| `sentiment` | 情感倾向（positive/negative/neutral） |

**覆盖的评测维度**：
- 路由准确率（expected_agent）
- 工具调用顺序正确性（expected_tools）
- 回答关键词命中率（expected_output_keywords）
- 多轮澄清触发准确性（clarification_required）
- 反馈类型识别（expected_feedback_type）
- 情感识别（sentiment）

---

### 3.2 自动化评价分析流水线（EvaluationAgent）

**位置**：`supervisor-agent/config/scheduling/EvaluationAgentConfiguration.java`

**数据来源**：`feedback` 表中的真实用户反馈记录（`user_evaluations.txt` 为历史数据样例）

**流水线**：
```
数据加载 → 批量迭代评分 → 统计汇总 → [条件分支] → 推送报告
```

**SessionFileReader**（测试用例加载器）：
```java
// 读取 sessions.txt，以 ============== 为分隔符切割对话记录
List<String> sessions = SessionFileReader.readSessionsFromFile("data/sessions.txt");
```

**EvaluationClassifierNode**（评分节点，Harness 核心）：
```java
public record EvaluationResult(
    String user,        // 用户 ID
    String time,        // 评价时间
    String complaint,   // "yes" | "no"（是否投诉）
    int satisfaction,   // 0-5（满意度评分）
    String summary      // 核心吐槽点摘要
) {}

// 使用 BeanOutputConverter 保证结构化输出可被程序解析
private final BeanOutputConverter<EvaluationResult> converter =
    new BeanOutputConverter<>(EvaluationResult.class);
```

**评测指标统计**（sessionResultSummaryNode）：
```java
String message = """
    用户投诉分析监控
    总评价记录数: %d条，产品投诉: %d条, 平均满意度(0～5): %d.
    用户核心诉求：%s
    """;
```

---

### 3.3 IterationNode（批量数据迭代）

**位置**：`EvaluationAgentConfiguration.java`

`IterationNode` 是 Spring AI Alibaba 提供的批处理节点，将数组中的每个元素独立运行一个子图：

```java
StateGraph iterationNode = IterationNode.converter()
    .inputArrayJsonKey("sessions")           // 输入：会话记录数组（JSON 格式）
    .outputArrayJsonKey("analysis_results")  // 输出：分析结果数组
    .tempIndexKey("iteration_index1")        // 内部迭代索引
    .iteratorItemKey("iterator_item")        // 当前迭代元素的 key
    .iteratorResultKey("session_analysis_result")  // 当前迭代结果的 key
    .subGraph(sessionAnalysisGraph)          // 子图：单条记录分析
    .convertToStateGraph();
```

**子图**（每条记录独立运行）：
```
START → EvaluationClassifierNode（LLM 评分）→ END
```

**并发控制**：IterationNode 会串行执行每个元素（不并发），防止 API 限速。

---

### 3.4 条件分支 Loop（告警机制）

**位置**：`EvaluationAgentConfiguration.java`

评测完成后，根据平均满意度决定走哪条路径：

```java
.addConditionalEdges("session_result_summary_node",
    state -> {
        // 计算平均满意度
        double avg = calculateAvgSatisfaction(state);
        return avg < 3.0 ? "alert" : "normal";  // 阈值 3.0
    },
    Map.of("normal", "message_parse", "alert", "alert_message_parse")
)
```

**两条路径**：
| 路径 | 触发条件 | 处理节点 | 输出格式 |
|------|---------|---------|---------|
| 普通路径 | avg ≥ 3.0 | `message_parse` → `message_sender` | 标准日报格式 |
| 告警路径 | avg < 3.0 | `alert_message_parse` → `alert_message_sender` | 红色告警格式（🚨 紧急） |

**告警 Prompt 差异**：
```
告警路径 Prompt：
你是一个紧急告警信息整理助手。用户满意度已严重偏低（平均分 < 3），需要生成醒目的告警报告...
使用红色告警标识（⚠️ 🚨）突出严重性
改进方向控制在3条以内，并标注【紧急】前缀
```

---

## 四、Loop 工程

Loop 工程关注**如何让 Agent 系统持续、周期性地运行**，形成"执行 → 观察 → 调整"的闭环。

### 4.1 IterationNode（批量数据迭代循环）

**详见 Harness 工程 3.3 节**。

批量处理 N 条反馈记录 = 运行 N 轮子图 = 形成数据级别的 Loop。

---

### 4.2 @Scheduled 本地定时 Loop

**位置**：`supervisor-agent/config/scheduling/LocalScheduledTrigger.java`

**激活条件**：`xxl.job.enabled=false`（默认值），通过 `@ConditionalOnProperty` 控制：

```java
@Component
@ConditionalOnProperty(name = "xxl.job.enabled", havingValue = "false", matchIfMissing = true)
public class LocalScheduledTrigger {

    @Scheduled(cron = "0 0 9 * * ?")     // 每天 09:00 运行日报 Agent
    public void runDailyReport() {
        dailyReportGraph.invoke(Map.of());
    }

    @Scheduled(cron = "0 0 10 ? * MON")  // 每周一 10:00 运行评测分析 Agent
    public void runEvaluation() {
        evaluationGraph.invoke(Map.of());
    }
}
```

`@EnableScheduling` 在 `SupervisorAgentApplication.java` 中启用。

**与 XxlJob 对比**：
| 维度 | @Scheduled | XxlJob |
|------|-----------|--------|
| 依赖 | 零外部依赖 | 需要 XxlJob Admin 服务 |
| 分布式调度 | 不支持 | 支持 |
| 任务历史 | 不保存 | 保存 |
| 手动触发 | 不支持 | 支持 |
| 适用场景 | 演示/开发环境 | 生产环境 |

---

### 4.3 CronAgent 动态 Loop 注册

**位置**：`supervisor-agent/config/scheduling/CronAgentConfiguration.java`

允许用户通过自然语言配置定时任务，Agent 解析后动态注册：

```
用户输入："每天8点30分执行日报"
    ↓
CronTaskParseAgent（ReactAgent）
    ↓ 工具调用
CronAgentTools.createCronTask()
    {agentName: "operationAnalysisAgent", cron: "0 30 8 * * ?"}
    ↓
XxlJobScheduledAgentManager.registerTask()
    ↓
XxlJobExecutor.registJobHandler(...)
```

**CronAgentTools 工具参数**：
```java
@Tool(name = "create-cron-task",
      description = "注册一个按指定 Cron 表达式定时运行的 Agent 任务")
public String createCronTask(
    @ToolParam(description = "要定时运行的 Agent 名称") String agentName,
    @ToolParam(description = "Quartz 格式的 6 位 Cron 表达式，例如：0 0 9 * * ?") String cronExpression
)
```

---

### 4.4 工具调用 Retry（指数退避）

**位置**：`order-sub-agent/node/ExecutorNode.java`

**重试策略**：失败后等待 `500ms × retry` 毫秒后重试，最多 `MAX_RETRY = 3` 次。

```java
private static final int MAX_RETRY = 3;

private String executeWithRetry(ExecutionPlan.ExecutionStep step, ToolCallback tool) {
    Exception lastException = null;
    for (int retry = 0; retry <= MAX_RETRY; retry++) {
        try {
            if (retry > 0) {
                long delay = 500L * retry;  // 500ms, 1000ms, 1500ms
                Thread.sleep(delay);
            }
            return tool.call(step.toolParameters());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return "Step 被中断";
        } catch (Exception e) {
            lastException = e;
        }
    }
    return String.format("Step %d 执行失败（已重试 %d 次）: %s",
        step.stepNumber(), MAX_RETRY, lastException.getMessage());
}
```

**重试场景**：
- 网络超时（MCP Server 未响应）
- MySQL 短暂不可用
- Nacos 服务发现临时失败

---

### 4.5 Human-in-the-Loop（中断-确认-恢复循环）

**详见 Agent 知识文档 6.3 节**

HITL 是一种受控的两轮 Loop：
- **第一轮**：PlannerNode 生成计划 → 中断 → 展示给用户
- **第二轮**：用户确认 → resume → ExecutorNode 执行 → SynthesizerNode 汇总

**前端侧 Loop 检测**（`ChatInterface.vue`）：
```typescript
// Streaming 结束后，检测回答是否包含写操作意图
const WRITE_KEYWORDS = ['创建', '预约', '申请', '取消', '修改备注', '为您办理', ...]
const hasWriteIntent = WRITE_KEYWORDS.some(kw => assistantContent.includes(kw))
if (hasWriteIntent) {
    confirmContent.value = assistantContent  // 展示计划摘要
    confirmVisible.value = true              // 弹出确认 Modal
}
```

---

### 4.6 多轮澄清 Loop

**位置**：`PlannerNode.java` + `OrderAgentDebugController.java`

当用户提供的信息不完整时，形成"提问 → 用户补充 → 重新规划"的循环：

```
轮 1: 用户说"帮我预约一下"
      → PlannerNode 判断信息不完整 (needsClarification=true)
      → 返回澄清问题："您想预约哪项服务？"

轮 2: 用户说"图书馆研讨间"
      → PlannerNode 再次规划，仍缺时间
      → 返回："请问预约哪天什么时间？多少人？"

轮 3: 用户说"明天下午4点，4个人"
      → PlannerNode 信息完整，生成执行计划
      → 进入 ExecutorNode 执行
```

**PlannerNode 信息完整性检查规则**：
```
campus-create-service-record 必须有：
  - userId（从消息 <userId> 标签提取）
  - 具体服务事项名称（不能是"预约一下"等模糊表达）

campus-cancel-service-record 必须有：
  - userId
  - orderId（格式：CAMPUS_XXXXXX）
```

**Controller 澄清检测**：
```java
.doOnNext(output -> {
    if ("planner".equals(output.node())) {
        String clarification = state.value("clarification_question").orElse(null);
        if (clarification != null) {
            sink.tryEmitNext(ServerSentEvent.builder(clarification).build());
            sink.tryEmitComplete();  // 提前终止，等待用户补充
        }
    }
})
```

---

## 五、四大工程完整知识点清单

### Prompt 工程
| 知识点 | 实现位置 | 核心技术 |
|--------|---------|---------|
| 结构化 System Prompt | 4 个 application.yml | 角色/流程/约束/示例四段式 |
| Few-shot 示例注入 | 4 个 application.yml 末尾 | 路由/工具/情绪边界案例 |
| 工具选择引导 | order-agent-instruction | 意图→工具映射表 |
| 结构化输出 Schema 注入 | EvaluationClassifierNode · PlannerNode | `BeanOutputConverter.getFormat()` |
| 工具描述四要素标准化 | OrderMcpTools · FeedbackMcpTools | 动词+对象+场景+限制 |
| RAG 查询改写 Prompt | ConsultService.rewriteQuery() | 专用检索优化 Prompt |

### Context 工程
| 知识点 | 实现位置 | 核心技术 |
|--------|---------|---------|
| 多层上下文构建 | SupervisorAgentController | userInput标签 + RunnableConfig + State |
| Memory 主动注入 | MemoryInjectNode | SystemMessage 前置注入 |
| Checkpoint 状态恢复 | 3 个子 Agent config | MemorySaver + threadId |
| RAG 知识库注入 | ConsultTools | 工具调用结果注入 messages |
| 滑动窗口压缩 | ContextCompressionNode | LLM 摘要 + 保留最近 N 条 |
| 跨 Agent userId 传递 | SupervisorAgentController | `<userId>` XML 标签 |

### Harness 工程
| 知识点 | 实现位置 | 核心技术 |
|--------|---------|---------|
| 校园场景 Golden Set | data/golden_set/ | 30 条标准评测用例 |
| 自动化评价分析 | EvaluationAgentConfiguration | 批量 LLM 评分流水线 |
| IterationNode 迭代 | EvaluationAgentConfiguration | 子图逐条处理 |
| 结构化评测结果 | EvaluationClassifierNode | BeanOutputConverter<EvaluationResult> |
| 条件分支告警 | EvaluationAgentConfiguration | addConditionalEdges + 满意度阈值 |
| 日报自动生成 | DailyReportAgentConfiguration | 数据加载 + LLM 分析 + 钉钉推送 |

### Loop 工程
| 知识点 | 实现位置 | 核心技术 |
|--------|---------|---------|
| 批量迭代 Loop | EvaluationAgentConfiguration | IterationNode 子图循环 |
| 本地定时 Loop | LocalScheduledTrigger | @Scheduled + @ConditionalOnProperty |
| 分布式定时 Loop | XxlJobScheduledAgentManager | XxlJob Handler 注册 |
| 动态 Loop 注册 | CronAgentConfiguration | 用户自定义 Cron + 动态注册 |
| 工具调用 Retry | ExecutorNode | 指数退避（500ms×retry，最多3次）|
| Human-in-the-Loop | planAndExecuteHitlGraph | interruptBefore + /confirm 端点 |
| 多轮澄清 Loop | PlannerNode + Controller | needsClarification + processStreamWithClarification |
