# 四大工程强化调研报告

> **文档日期**：2026-08-03
> **适用项目**：campus-smart-service-multi-agent-assistant
> **现状基准**：Agent 知识点已全覆盖，Prompt/Context/Harness/Loop 四大工程均有初步实现

---

## 一、现状基线评估

| 工程 | 当前完成度 | 核心短板 |
|------|-----------|---------|
| Prompt 工程 | ★★★☆☆ | Prompt 硬编码在 YAML，无版本管理；少 Few-shot 示例；无 A/B 对比测试 |
| Context 工程 | ★★★☆☆ | Memory query 固定单一；RAG 无混合检索；跨 Agent 上下文无显式传递 |
| Harness 工程 | ★★☆☆☆ | 评测数据集业务不匹配（沿用云服务会话）；无 Golden Set；无指标仪表盘 |
| Loop 工程 | ★★☆☆☆ | XxlJob 默认关闭；无条件分支 Loop；无 Retry/Backoff；无自适应循环 |

---

## 二、Prompt 工程强化方案

### 2.1 现状问题

- **Prompt 全部硬编码**在 `application.yml`，修改需重新部署，`NacosAgentPromptBuilderFactory` 已 import 但被注释掉
- **无 Few-shot 示例**：每个 Agent Prompt 只有 zero-shot 指令，缺乏边界案例示范
- **无版本追踪**：Prompt 变更无法和模型输出质量变化建立关联
- **工具描述质量参差不齐**：`@Tool` 注解的 description 决定 LLM 的工具调用准确率，但目前写法不统一

### 2.2 强化方向

#### 方向 A：启用 Nacos 动态 Prompt（改动最小）

`ConsultAgent.java` 中 `NacosAgentPromptBuilderFactory` 已引入但被注释，启用后 Prompt 从 Nacos 配置中心动态拉取，实现**不停机热更新**：

```java
// 当前（注释状态）
// .builder(new NacosAgentPromptBuilderFactory(nacosOptions))
// 目标
.builder(new NacosAgentPromptBuilderFactory(nacosOptions))
```

**涉及文件**：
- `ConsultAgent.java`：去掉注释即可
- Nacos 配置中心：新建 `consult-sub-agent-instruction` 配置项

#### 方向 B：Few-shot 示例注入

在每个 Agent 的 System Prompt 末尾增加"边界案例"示范段落，帮助 LLM 理解模糊场景如何处理：

```yaml
# order-agent-instruction 末尾增加
示例（边界情况）:
- 用户说"帮我预约一下"但没说预约什么 → 先澄清："您想预约哪项校园服务？"
- 用户说"取消我的记录" → 先查询最近记录确认，再取消
- 用户问"奖学金什么时候发" → 转交 consult_agent 处理
```

**涉及文件**：4 个 `application.yml` 的 prompt 字段

#### 方向 C：工具描述标准化

当前 `@Tool` 注解 description 写法不统一，建议统一为"动词+对象+场景+限制"四要素格式，提升 LLM 工具选择准确率：

```java
// 当前（不规范）
@Tool(name = "campus-cancel-service-record", description = "根据用户ID和记录编号取消校园事务办理/预约记录。")

// 改进（四要素）
@Tool(name = "campus-cancel-service-record", 
      description = "取消指定用户的校园事务办理或预约记录（需提供 userId 和 orderId）。"
                  + "仅适用于取消操作，不适用于查询或修改；"
                  + "执行前须与用户确认记录编号。")
```

**涉及文件**：`OrderMcpTools.java`、`FeedbackMcpTools.java`（共 17 个工具描述）

#### 方向 D：Prompt 评分反向循环（与 Harness 联动）

在 `EvaluationClassifierNode` 已有满意度评分的基础上，将低分（< 3）的对话自动提取为"待改进案例"，定期人工 review 后更新 Prompt。

---

## 三、Context 工程强化方案

### 3.1 现状问题

- **Memory query 固定为单一字符串**："用户偏好和历史习惯"，无法按当前意图针对性检索
- **RAG 只有向量检索**，对精确名词（如"CAMPUS_20260601001"记录编号）召回率低
- **跨 Agent 上下文无显式传递**：Supervisor 通过 `<userId>` 标签传递 userId，但无法传递跨轮的业务上下文（如"用户上一步刚查询了奖学金"）
- **消息列表无长度管理**：多轮对话后 messages 无限增长，超出模型 context window

### 3.2 强化方向

#### 方向 A：意图感知的 Memory 查询

根据当前 Agent 类型构造针对性的 memory query，而非一律查询"用户偏好和历史习惯"：

```java
// MemoryInjectNode 中按 Agent 类型差异化 query
Map<String, String> AGENT_QUERIES = Map.of(
    "order_agent",    "用户办理习惯 预约时间偏好 常用服务",
    "consult_agent",  "用户关注的政策方向 历史咨询话题",
    "feedback_agent", "用户历史投诉类型 关注的服务质量问题"
);
String query = AGENT_QUERIES.getOrDefault(agentName, "用户偏好和历史习惯");
```

**涉及文件**：`common/.../node/MemoryInjectNode.java`（新增 agentName 构造参数）

#### 方向 B：混合检索（向量 + 关键词）

当前 RAG 纯向量检索，对精确词汇（服务名称、记录编号、时间）召回差。增加关键词匹配层：

```java
// ConsultService.searchKnowledge() 中增加前置关键词过滤
// 若 query 包含精确词（校园卡、图书馆、宿舍报修），
// 先从本地 products 表精确匹配，作为向量检索的补充
List<Product> exactMatches = productMapper.selectByNameLike(extractKeyword(query));
if (!exactMatches.isEmpty()) {
    // 将精确匹配结果以高优先级拼入上下文
    result.insert(0, buildProductContext(exactMatches) + "\n\n");
}
```

**涉及文件**：`ConsultService.java`、`ConsultTools.java`

#### 方向 C：跨轮摘要压缩（滑动窗口）

当 messages 超过 N 条时，调用 LLM 对早期对话做摘要压缩，避免 context 过长：

```java
// 在 ReactAgent 前加一个 ContextCompressionNode
// 当 messages.size() > 20 时触发摘要
if (messages.size() > MAX_MESSAGES) {
    String summary = chatClient.prompt()
        .system("请用 3-5 句话总结以下对话历史的关键信息：")
        .user(buildHistoryText(messages.subList(0, messages.size() - 5)))
        .call().content();
    // 用摘要替换早期对话，保留最近 5 条
    messages = buildCompressedMessages(summary, messages.subList(messages.size() - 5, messages.size()));
}
```

**涉及文件**：`common` 模块新增 `ContextCompressionNode.java`

#### 方向 D：跨 Agent 业务上下文传递

Supervisor 传给子 Agent 的 `<userId>` 标签模式可以扩展，携带更多跨 Agent 上下文：

```java
// SupervisorAgentController.java 中扩展标签
String userInput = userQuery 
    + "<userId>" + userID + "</userId>"
    + "<chatId>" + chatID + "</chatId>";
    // 未来可以加：+ "<lastAgent>consult_agent</lastAgent>"
    //            + "<lastIntent>奖学金咨询</lastIntent>"
```

---

## 四、Harness 工程强化方案

### 4.1 现状问题

- **评测数据集业务不匹配**：`sessions.txt` 是云服务客服对话（MSE/SLB/OSS），与校园场景无关，评测结论无参考价值
- **`user_evaluations.txt` 覆盖率低**：只有 5 条评价，统计结果不可信
- **无 Golden Set**：没有"标准答案"数据集，无法量化回答质量
- **无评测指标仪表盘**：评测结果发到钉钉就结束，无历史趋势对比
- **EvaluationClassifierNode 仅分析 complaint/satisfaction**，未覆盖工具调用准确率、路由准确率

### 4.2 强化方向

#### 方向 A：构建校园场景 Golden Set（最高优先级）

替换现有 `sessions.txt` 为校园场景对话，建立标准评测集：

**目标**：覆盖 3 类 Agent × 5 种意图 × 2 种复杂度 = 30 条最小 Golden Set

```
data/golden_set/
├── consult_golden.txt    # 政策咨询 10 条（奖学金/转专业/选课/在读证明/困难生）
├── order_golden.txt      # 事务办理 10 条（预约/查询/取消/修改/边界拒绝）
└── feedback_golden.txt   # 投诉反馈 10 条（投诉/建议/表扬/情绪安抚/多轮澄清）
```

每条格式：
```
--- CASE_001 ---
input: 我想预约图书馆研讨间，明天下午4个人
expected_agent: order_agent
expected_tools: [campus-validate-service-item, campus-create-service-record]
expected_output_keywords: [预约成功, CAMPUS_, 研讨间]
---
```

**涉及文件**：新建 `supervisor-agent/src/main/resources/data/golden_set/` 目录及文件

#### 方向 B：自动化路由准确率评测

在 `EvaluationAgentConfiguration.java` 基础上新增路由评测维度：

```java
// 新增 RoutingEvaluationNode
public class RoutingEvaluationNode implements NodeAction {
    // 对比 expected_agent 与 actual_agent（从对话日志中提取）
    // 输出: routing_accuracy = correct / total
}
```

**涉及文件**：`supervisor-agent` 新增 `RoutingEvaluationNode.java`

#### 方向 C：RAG 召回质量评测

在 Harness 中增加 RAG 质量检查节点：

```java
// RagEvaluationNode
// 对于 consult_agent 的回答，检查是否包含来源引用
// 计算 with_source / total 比率（来源引用率）
// 检查 rewrittenQuery vs originalQuery 的差异度
```

**涉及文件**：`consult-sub-agent` 新增 `RagEvaluationNode.java`

#### 方向 D：评测指标持久化（数据库 or 文件）

当前评测结果只推钉钉，历史趋势无法追踪。增加指标持久化：

```java
// EvaluationAgentConfiguration 中 message_sender 节点后增加
.addNode("metrics_persist", node_async(state -> {
    // 将 analysis_results 写入 feedback 表（新增 evaluation_date 字段）
    // 或写入本地 JSON 文件：data/evaluation_history.json
    return Map.of();
}))
```

可用 `supervisor-agent` 已有的 `FeedbackMapper` 直接持久化，无需新增表。

**涉及文件**：`EvaluationAgentConfiguration.java`、`supervisor-agent` 数据库 schema

---

## 五、Loop 工程强化方案

### 5.1 现状问题

- **XxlJob 默认关闭**（`XXL_JOB_ENABLED=false`），定时 Loop 无法演示
- **IterationNode 无错误跳过机制**：单条失败会影响整批评测
- **无 Retry/Backoff**：工具调用失败直接返回错误，无重试策略
- **无条件分支 Loop**：评测发现问题后无法自动触发优化建议流程
- **HITL Loop 是两步**，实际场景可能需要多轮澄清（用户提供部分信息 → Agent 追问 → 用户补充）

### 5.2 强化方向

#### 方向 A：本地 @Scheduled 替代 XxlJob（零依赖可演示）

为日报和评测 Agent 增加 Spring `@Scheduled` 触发器，不依赖 XXL-JOB 即可演示定时 Loop：

```java
// 新增 LocalScheduledTrigger.java
@Component
@ConditionalOnProperty(name = "xxl.job.enabled", havingValue = "false")
public class LocalScheduledTrigger {

    @Autowired
    private CompiledGraph dailyReportGraph;

    @Autowired
    private CompiledGraph evaluationGraph;

    // 每天 9:00 运行日报
    @Scheduled(cron = "0 0 9 * * ?")
    public void runDailyReport() {
        dailyReportGraph.invoke(Map.of());
    }

    // 每周一 10:00 运行评测分析
    @Scheduled(cron = "0 0 10 ? * MON")
    public void runEvaluation() {
        evaluationGraph.invoke(Map.of());
    }
}
```

**涉及文件**：`supervisor-agent` 新增 `LocalScheduledTrigger.java`，`SupervisorAgentApplication.java` 增加 `@EnableScheduling`

#### 方向 B：带条件分支的 Loop（Adaptive Loop）

在 `EvaluationAgentConfiguration` 中增加条件边：若平均满意度 < 3 则自动触发"告警分支"，输出专项报告；否则走普通汇总路径：

```java
// 增加 ConditionalEdge
.addConditionalEdges("summarizer",
    state -> {
        double avgScore = calculateAvgSatisfaction(state);
        return avgScore < 3.0 ? "alert_sender" : "normal_sender";
    },
    Map.of("alert_sender", "alert_sender", "normal_sender", "message_sender")
)
.addNode("alert_sender", node_async(generateAlertSender()))  // 发送专项告警
```

**涉及文件**：`EvaluationAgentConfiguration.java`

#### 方向 C：工具调用 Retry 节点

在 `ExecutorNode` 中对失败的工具调用增加退避重试逻辑：

```java
// ExecutorNode.java 中对每个步骤增加 retry
for (int retry = 0; retry < MAX_RETRY; retry++) {
    try {
        String result = tool.call(step.toolParameters());
        stepResults.add("Step " + step.stepNumber() + ": " + result);
        break;
    } catch (Exception e) {
        if (retry == MAX_RETRY - 1) {
            stepResults.add("Step " + step.stepNumber() + " 最终失败: " + e.getMessage());
        } else {
            Thread.sleep(500L * (retry + 1));  // 指数退避
        }
    }
}
```

**涉及文件**：`ExecutorNode.java`

#### 方向 D：多轮澄清 Loop（Multi-turn Clarification）

当前 HITL 是两步（plan → confirm）。扩展为支持多轮澄清的 Loop 模式，允许 Agent 主动追问缺失信息，用户补充后继续：

```
用户: "帮我预约一下"
Agent: [需要澄清] → "您想预约哪项服务？图书馆研讨间、心理咨询还是体育馆？"
用户: "图书馆"
Agent: [继续澄清] → "预约日期和人数是？"
用户: "明天下午3点，4人"
Agent: [信息完整] → 执行预约
```

实现方式：在 PlannerNode 增加"信息完整性检查"，若不完整输出 `NEED_CLARIFICATION` 信号，Controller 检测到后返回澄清问题而非进入 executor：

```java
// PlannerNode 输出扩展
record PlannerOutput(boolean needsClarification, String clarificationQuestion, ExecutionPlan plan) {}
```

**涉及文件**：`PlannerNode.java`、`OrderAgentDebugController.java`（增加澄清状态检测）

---

## 六、实施优先级矩阵

| 强化项 | 工程 | 改动量 | 演示价值 | 优先级 |
|--------|------|--------|---------|--------|
| 构建校园 Golden Set | Harness | 小（只写数据） | ★★★★★ | P0 |
| `@Scheduled` 替代 XxlJob | Loop | 小（新增一个类） | ★★★★★ | P0 |
| 启用 Nacos 动态 Prompt | Prompt | 极小（去注释） | ★★★★☆ | P1 |
| 意图感知 Memory query | Context | 小 | ★★★★☆ | P1 |
| 条件分支 Loop | Loop | 中 | ★★★★☆ | P1 |
| Few-shot 示例注入 | Prompt | 小（改 YAML） | ★★★☆☆ | P2 |
| 混合检索（向量+关键词） | Context | 中 | ★★★☆☆ | P2 |
| 工具描述标准化 | Prompt | 中（改 17 个描述） | ★★★☆☆ | P2 |
| 路由准确率自动评测 | Harness | 中 | ★★★☆☆ | P2 |
| Retry/Backoff | Loop | 小 | ★★★☆☆ | P2 |
| 消息滑动窗口压缩 | Context | 中 | ★★☆☆☆ | P3 |
| 多轮澄清 Loop | Loop | 大 | ★★★★☆ | P3 |
| 评测指标持久化 | Harness | 中 | ★★☆☆☆ | P3 |

---

## 七、各工程最终目标状态

```
Prompt 工程目标：
  Prompt 在 Nacos 热更新 → Few-shot 覆盖边界案例 → 工具描述四要素标准化
  → 低分对话自动提取为 Prompt 优化输入

Context 工程目标：
  Memory 按意图差异化查询 → 向量+关键词混合检索
  → 长对话滑动窗口压缩 → 跨 Agent 业务上下文传递

Harness 工程目标：
  校园场景 Golden Set（30+条）→ 路由/工具/RAG 三维自动评测
  → 满意度趋势持久化 → 低分自动触发告警

Loop 工程目标：
  @Scheduled 本地可演示 → 条件分支 Loop（满意度 < 3 触发告警）
  → 工具调用 Retry → 多轮澄清 Loop → HITL 与 Clarification 融合
```

---

*调研报告结束。建议优先实施 P0 项（Golden Set + @Scheduled），可在最短时间内显著提升四个工程的完整度和演示效果。*
