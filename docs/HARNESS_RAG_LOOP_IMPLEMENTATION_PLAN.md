# 校园智能服务多 Agent 助手系统完善实施计划

## 1. 背景说明

本项目是一个基于 Spring Boot、Spring AI Alibaba、MCP Server、MyBatis、MySQL、Nacos、Redis、Mem0 和 Vue 3 构建的校园智能服务多 Agent 助手系统。

当前项目已经具备比较完整的多 Agent 架构：

- `supervisor-agent`：主调度 Agent，负责理解用户意图并路由到对应子 Agent。
- `consult-sub-agent`：咨询子 Agent，负责校园政策、办事流程、通知公告、校园资源和服务事项咨询。
- `order-sub-agent`：事务办理子 Agent，负责预约申请、办理记录查询、备注修改、记录取消等。
- `feedback-sub-agent`：反馈子 Agent，负责投诉、建议、评价和服务反馈。
- `order-mcp-server`：校园事务办理 MCP 工具服务，封装办理记录、服务事项、名额校验等能力。
- `feedback-mcp-server`：校园反馈投诉 MCP 工具服务，封装反馈创建、查询和解决方案更新等能力。
- `memory-mcp-server`：长期记忆 MCP 工具服务，调用 Mem0 记录用户偏好和习惯。
- `frontend`：Vue 3 + Vite 聊天前端。

项目现在已经能体现“主控 Agent + 专项子 Agent + MCP 工具服务”的基本形态。下一阶段的核心目标，不是继续堆更多 Agent，而是把它从“能演示的多 Agent Demo”升级成“可评测、可追踪、可迭代、可上线”的工程化系统。

本实施计划围绕三个方向展开：

- Harness 工程：评测、回归测试、链路追踪、质量门禁。
- RAG 工程：可信知识检索、结构化知识管理、引用溯源、防幻觉回答。
- Loop 工程：多轮任务闭环、参数收集、用户确认、异常恢复、最终校验。

## 2. 总体目标

### 2.1 产品目标

- 支持稳定可靠的校园政策咨询、事务预约、办理记录管理和投诉反馈。
- 降低政策、流程、时间、材料等咨询类回答中的幻觉。
- 对创建、取消、修改等写操作增加确认机制，避免误操作。
- 提升多轮任务完成率，让系统能把“用户模糊请求”推进到“可执行任务”。
- 支持长期记忆，但记忆行为要可审计、可解释、可关闭。

### 2.2 工程目标

- 新增一套可复用的 Agent Harness，用于路由测试、工具调用测试、RAG 测试、记忆测试和端到端对话回放。
- 为每次用户请求生成结构化 Trace，记录路由、工具调用、RAG 命中、记忆读写、最终回答和错误信息。
- 将咨询知识库从简单文档检索升级为带元数据、可引用、可评测的 RAG 系统。
- 为事务办理和反馈投诉引入状态机，控制缺参追问、用户确认、工具执行和异常恢复。
- 增加最终 Verifier，对高风险回答和写操作进行兜底校验。

## 3. 实施路线图

| 阶段 | 主题 | 预计周期 | 核心交付物 |
| --- | --- | --- | --- |
| 第 0 阶段 | 基线与观测 | 3-5 天 | 当前行为基线报告、Trace 模型、基础链路日志 |
| 第 1 阶段 | Harness 工程 MVP | 1-2 周 | `agent-harness`、测试用例、对话回放、评测报告 |
| 第 2 阶段 | RAG 工程升级 | 2-3 周 | 知识元数据、切片管线、混合检索、引用输出、RAG 评测集 |
| 第 3 阶段 | Loop 工程建设 | 2-3 周 | Slot Filling、确认机制、任务状态机、Verifier |
| 第 4 阶段 | 上线加固 | 1-2 周 | 监控面板、CI 质量门禁、安全检查、管理端能力 |

推荐优先级是：先做 Harness，再做 RAG，最后做 Loop。原因是没有评测和观测时，RAG 和 Loop 的优化很容易变成“凭感觉调 prompt”。

## 4. 第 0 阶段：基线与观测

### 4.1 阶段目标

在大规模修改 Prompt、工具和执行流程之前，先建立可观测基线。后续每次改动都能回答三个问题：

- 改动是否提升了效果？
- 是否引入了路由、工具调用或权限方面的回归？
- 失败发生在哪一层？

### 4.2 具体任务

#### 4.2.1 定义统一 Trace 模型

建议 Trace 字段如下：

```json
{
  "traceId": "uuid",
  "chatId": "string",
  "userId": "string",
  "requestTime": "datetime",
  "userQuery": "string",
  "supervisorRoute": "consult_agent | order_agent | feedback_agent | unknown",
  "subAgent": "string",
  "toolCalls": [],
  "ragChunks": [],
  "memoryReads": [],
  "memoryWrites": [],
  "finalAnswer": "string",
  "latencyMs": 0,
  "status": "success | failed | partial",
  "errorMessage": "string"
}
```

#### 4.2.2 在主入口记录 Trace

优先改造位置：

- `supervisor-agent/src/main/java/com/alibaba/cloud/ai/demo/controller/SupervisorAgentController.java`
- `supervisor-agent/src/main/java/com/alibaba/cloud/ai/demo/config/SupervisorAgent.java`

需要记录：

- 请求开始时间。
- `chat_id`。
- `user_id`。
- 用户原始输入。
- Supervisor 路由到的子 Agent。
- SSE 输出内容摘要。
- 请求成功、失败或中断状态。

#### 4.2.3 在 MCP 工具层记录工具调用

优先覆盖模块：

- `order-mcp-server`
- `feedback-mcp-server`
- `memory-mcp-server`
- `consult-sub-agent`

每次工具调用至少记录：

- 工具名。
- 入参摘要。
- 返回摘要。
- 是否成功。
- 耗时。
- 异常信息。

#### 4.2.4 选择 Trace 存储方式

MVP 阶段建议先采用 JSONL 文件：

```text
logs/traces/agent-trace-2026-07-29.jsonl
```

后续可升级为 MySQL 表：

```sql
CREATE TABLE agent_trace (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  trace_id VARCHAR(64) NOT NULL,
  chat_id VARCHAR(128),
  user_id VARCHAR(128),
  user_query TEXT,
  route_agent VARCHAR(64),
  final_answer TEXT,
  status VARCHAR(32),
  latency_ms BIGINT,
  created_at DATETIME
);
```

工具调用可拆为单独表：

```sql
CREATE TABLE agent_tool_trace (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  trace_id VARCHAR(64) NOT NULL,
  tool_name VARCHAR(128),
  input_summary TEXT,
  output_summary TEXT,
  success TINYINT,
  latency_ms BIGINT,
  error_message TEXT,
  created_at DATETIME
);
```

### 4.3 交付物

- `docs/BASELINE_REPORT.md`
- Trace 字段定义。
- JSONL Trace Writer 或 MySQL Trace 表。
- 主入口与 MCP 工具层基础日志。
- 20-30 条人工验证样例的基线结果。

### 4.4 验收标准

- 每次聊天请求都有唯一 `traceId`。
- 能从 Trace 中看出 Supervisor 路由到了哪个子 Agent。
- 能看到子 Agent 调用了哪些工具。
- 工具失败时能定位到具体工具名、入参摘要和异常信息。
- 不需要翻多个日志文件也能复盘一次失败对话。

## 5. 第 1 阶段：Harness 工程

### 5.1 阶段目标

建设一套可重复运行的 Agent 评测和回归测试框架，让项目具备持续迭代能力。

Harness 要回答这些问题：

- Supervisor 是否把请求路由到了正确的子 Agent？
- 子 Agent 是否调用了正确工具？
- 写操作是否在用户确认前被执行？
- RAG 回答是否包含必要事实、避免错误事实？
- 记忆是否提升了个性化效果，同时没有越界使用敏感信息？
- Prompt 或代码改动是否造成历史能力退化？

### 5.2 建议模块结构

新增模块：

```text
agent-harness/
  pom.xml
  src/main/java/...
  src/test/java/...
  cases/
    routing_cases.yaml
    order_cases.yaml
    feedback_cases.yaml
    rag_cases.yaml
    memory_cases.yaml
    e2e_conversation_cases.yaml
  reports/
    .gitkeep
```

如果短期内不想增加 Maven 模块，也可以先用 Python 或 Node 写轻量 runner。但长期建议沉淀为 Java 模块，这样更贴合当前后端技术栈。

### 5.3 测试用例格式

建议采用 YAML，便于阅读和维护。

路由测试示例：

```yaml
- id: routing_consult_001
  category: routing
  user_id: "1001"
  chat_id: "harness-routing-001"
  user_query: "国家奖学金申请需要准备哪些材料？"
  expected:
    route: "consult_agent"
    must_not_call:
      - "campus-create-service-record"
    must_contain:
      - "材料"
      - "申请"
```

事务办理多轮测试示例：

```yaml
- id: order_create_001
  category: order
  user_id: "1001"
  chat_id: "harness-order-001"
  turns:
    - user: "帮我预约明天下午的图书馆研讨间"
      expected:
        route: "order_agent"
        should_ask_confirmation: true
        must_contain:
          - "确认"
          - "图书馆研讨间"
    - user: "确认"
      expected:
        must_call:
          - "campus-create-service-record"
        must_contain:
          - "记录"
```

### 5.4 Harness 能力设计

#### 5.4.1 路由评测

覆盖范围：

- 咨询类请求。
- 事务办理类请求。
- 投诉反馈类请求。
- 混合意图请求。
- 模糊请求。
- 需要追问的请求。

示例：

| 用户输入 | 期望路由 |
| --- | --- |
| 奖学金申请需要哪些材料？ | `consult_agent` |
| 帮我预约明天下午图书馆研讨间 | `order_agent` |
| 宿舍维修太慢了我要投诉 | `feedback_agent` |
| 和上次一样预约 | `order_agent` + 历史记录或记忆查询 |
| 校园卡丢了怎么办，能顺便帮我补办吗？ | 先咨询或直接事务办理，需根据策略定义 |

核心指标：

- 路由准确率。
- 模糊请求追问率。
- 错误 Agent 率。

初始目标：

- 建立 50 条路由用例。
- 路由准确率不低于 90%。

#### 5.4.2 工具调用契约测试

覆盖工具：

- `campus-create-service-record`
- `campus-get-service-record`
- `campus-get-service-record-by-user`
- `campus-get-service-records-by-user`
- `campus-query-service-records`
- `campus-cancel-service-record`
- `campus-update-service-record-remark`
- `feedback-create-feedback`
- `feedback-get-feedback-by-user`
- `feedback-update-solution`
- `memory-store`
- `memory-search`

测试维度：

- 正常路径。
- 缺少必填参数。
- 参数类型错误。
- 服务事项不存在。
- 剩余名额不足。
- 用户越权访问。
- 重复请求。
- 后端服务超时。
- MCP 服务不可用。

初始目标：

- 建立 30 条工具契约用例。
- 不允许出现越权读取或越权写入。
- 不允许在确认前调用写工具。

#### 5.4.3 端到端对话回放

通过真实接口回放：

```text
GET /api/assistant/chat
```

Runner 需要支持：

- 解析 SSE 流式输出。
- 收集完整回答。
- 读取对应 Trace。
- 判断路由、工具调用和最终回答是否符合预期。

记录内容：

- 完整流式回答。
- `traceId`。
- 路由结果。
- 工具调用列表。
- 请求状态。
- 延迟。

初始目标：

- 建立 20 条多轮对话用例。
- 常见任务完成率不低于 80%。

#### 5.4.4 规则评分器与 LLM Judge

建议采用规则评分 + LLM Judge 混合模式。

规则评分适合判断硬约束：

- 路由是否等于预期。
- 必须调用的工具是否调用。
- 禁止调用的工具是否未调用。
- 必须包含的关键词是否出现。
- 禁止出现的内容是否未出现。
- 写操作是否经过确认。

LLM Judge 适合判断软质量：

- 回答是否相关。
- 回答是否完整。
- 语气是否适合校园服务场景。
- 是否基于知识库依据回答。
- 追问是否合理。

LLM Judge 输出示例：

```json
{
  "score": 4,
  "passed": true,
  "reason": "回答覆盖了申请材料，没有编造具体截止时间。"
}
```

### 5.5 报告设计

建议同时输出三种格式：

- JSON：给 CI 和自动化系统读取。
- Markdown：方便代码评审和人工查看。
- HTML：方便可视化查看失败样例。

报告字段：

- 总用例数。
- 通过数。
- 失败数。
- 通过率。
- 路由准确率。
- 工具调用准确率。
- RAG groundedness 得分。
- 平均延迟。
- 失败 Top 用例。
- 与上次运行相比的回归项。

### 5.6 CI 集成

建议增加命令：

```powershell
mvn test -pl agent-harness -Dgroups=smoke
```

质量门禁建议：

- Smoke 用例通过率必须为 100%。
- 完整 Harness 通过率不低于 90%。
- P0 安全用例不能失败。
- RAG 禁止事实不能出现。
- 写操作确认违规数必须为 0。

### 5.7 交付物

- `agent-harness` 模块。
- YAML 测试用例。
- SSE 对话回放客户端。
- 规则评分器。
- LLM Judge 接口。
- Markdown/JSON/HTML 报告生成器。
- CI 运行说明。

### 5.8 验收标准

- 开发者可以用一条命令评测核心 Agent 工作流。
- 报告能清楚说明失败原因。
- Prompt 或工具改动可以和历史基线对比。
- 不打开前端也能完成核心链路验证。

## 6. 第 2 阶段：RAG 工程

### 6.1 阶段目标

将咨询类能力从简单知识检索升级为可信、结构化、可引用、可评测的 RAG 系统。

RAG 的目标不是让回答“更像人”，而是让回答：

- 有依据。
- 可追溯。
- 可更新。
- 能识别过期信息。
- 在证据不足时不编造。

### 6.2 知识文档元数据规范

建议为校园知识定义统一格式：

```yaml
id: scholarship_national_001
title: 国家奖学金申请说明
category: 奖助学金
department: 学工处
audience:
  - 本科生
effective_date: 2026-09-01
expire_date: 2027-08-31
source_url: https://example.edu/notice/001
source_type: official_notice
last_verified_at: 2026-07-29
version: 1
tags:
  - 奖学金
  - 申请材料
  - 学工处
content: |
  ...
```

推荐分类：

- 奖助学金。
- 教务流程。
- 图书馆服务。
- 场馆预约。
- 校园卡。
- 宿舍维修。
- 心理咨询。
- 后勤服务。
- 投诉反馈。
- 常见问题。

### 6.3 知识切片策略

不建议简单按固定长度切片。校园政策和流程文档更适合按语义结构切片。

推荐切片类型：

- 概览。
- 申请条件。
- 申请材料。
- 办理流程。
- 时间节点。
- 办理地点。
- 联系方式。
- 费用说明。
- 常见问题。
- 例外情况。
- 通知公告。

切片元数据示例：

```json
{
  "chunkId": "scholarship_national_001#required_materials#001",
  "docId": "scholarship_national_001",
  "title": "国家奖学金申请说明",
  "section": "申请材料",
  "category": "奖助学金",
  "department": "学工处",
  "effectiveDate": "2026-09-01",
  "expireDate": "2027-08-31",
  "content": "..."
}
```

### 6.4 检索链路设计

建议检索流程：

```text
用户问题
-> Query 规范化
-> 意图和分类识别
-> 混合检索
-> 元数据过滤
-> 重排序
-> 上下文压缩
-> 基于证据生成回答
-> 引用来源输出
```

#### 6.4.1 Query 规范化

示例：

| 用户原始问题 | 规范化检索 Query |
| --- | --- |
| 国奖咋申请 | 国家奖学金 申请 条件 材料 流程 |
| 图书馆小房间预约 | 图书馆 研讨间 预约 规则 |
| 校园卡丢了 | 校园卡 挂失 补办 流程 |
| 宿舍坏了咋修 | 宿舍 报修 流程 后勤 |

#### 6.4.2 混合检索

建议同时使用：

- 向量检索：召回语义相关内容。
- 关键词或 BM25 检索：召回政策名、地点名、部门名、服务编号等精确表达。

如果短期基础设施有限，可以先用数据库 `LIKE` 或简单倒排索引模拟关键词检索，后续再替换为正式 BM25。

#### 6.4.3 重排序

重排序可以使用 reranker 模型，也可以先用 LLM 打分实现。

重排序信号：

- 语义相关度。
- 服务事项名称是否精确匹配。
- 文档是否在有效期内。
- 部门是否匹配。
- 用户身份是否匹配。
- 文档是否为官方来源。

#### 6.4.4 回答生成约束

咨询类回答应遵守：

- 只基于检索到的上下文回答。
- 信息不足时明确说明缺失，不编造。
- 有来源信息时给出来源标题和最后核验日期。
- 政策类回答说明适用对象和有效期。
- 流程类回答使用步骤化表达。
- 涉及截止日期时，只有来源中明确出现才输出具体日期。

### 6.5 引用格式

回答示例：

```text
国家奖学金申请通常需要准备申请表、成绩单、获奖证明和学院审核材料。

依据：
1. 国家奖学金申请说明，学工处，最后核验：2026-07-29
```

前端后续可以把引用渲染为可展开的来源卡片。

### 6.6 RAG 管理能力

建议增加管理端页面或接口，支持：

- 上传知识文档。
- 编辑文档元数据。
- 查看切片结果。
- 测试检索 Query。
- 发布或下线文档。
- 标记过期文档。
- 查看无答案问题。
- 查看高频检索问题。

可落地位置：

- 在 `consult-sub-agent` 中增加管理 API。
- 在 `frontend` 中增加管理页面，可放在设置页或新建后台路由。

### 6.7 RAG 评测集

新增：

```text
agent-harness/cases/rag_cases.yaml
```

用例示例：

```yaml
- id: rag_scholarship_001
  question: "国家奖学金申请需要哪些材料？"
  expected:
    must_contain:
      - "申请表"
      - "成绩单"
      - "获奖证明"
    forbidden:
      - "无需审核"
      - "自动发放"
    expected_sources:
      - "国家奖学金申请说明"
```

核心指标：

- 检索召回率。
- 引用准确率。
- 回答忠实度。
- 答案完整度。
- 无答案场景正确率。
- 过期来源使用率。

初始目标：

- 建立 100 条 RAG 测试用例。
- Grounded answer 通过率不低于 90%。
- 引用准确率不低于 85%。
- 正常查询中过期来源使用率为 0。

### 6.8 交付物

- 知识文档元数据规范。
- 知识切片管线。
- 混合检索服务。
- 重排序步骤。
- 引用输出格式。
- RAG 评测用例。
- 管理端检索测试接口。

### 6.9 验收标准

- 咨询回答能展示来源依据。
- 过期文档不会在普通查询中被使用。
- 无证据问题不会生成编造答案。
- RAG 效果进入 Harness 报告。

## 7. 第 3 阶段：Loop 工程

### 7.1 阶段目标

让系统从“单轮问答 + 工具调用”升级为“可控、可恢复、可审计的多轮任务执行”。

重点建设：

- 意图澄清。
- 参数收集。
- 用户确认。
- 工具执行。
- 异常恢复。
- 最终校验。
- 记忆更新。

### 7.2 Loop 类型

项目需要支持以下 Loop：

- 意图澄清 Loop。
- Slot Filling Loop。
- 用户确认 Loop。
- 工具执行 Loop。
- 异常恢复 Loop。
- 最终 Verifier Loop。
- 长期记忆更新 Loop。

### 7.3 事务办理状态机

以创建校园事务办理记录为例，建议状态机：

```text
START
-> INTENT_DETECTED
-> SLOT_COLLECTING
-> SERVICE_VALIDATING
-> CAPACITY_CHECKING
-> USER_CONFIRMING
-> RECORD_CREATING
-> RESULT_RETURNED
-> END
```

失败状态：

```text
SERVICE_NOT_FOUND
CAPACITY_INSUFFICIENT
USER_REJECTED
TOOL_FAILED
PERMISSION_DENIED
```

必填 Slot：

```json
{
  "intent": "create_service_record",
  "requiredSlots": [
    "userId",
    "serviceName",
    "timePreference",
    "quantity"
  ],
  "optionalSlots": [
    "serviceMode",
    "priority",
    "location",
    "remark"
  ]
}
```

执行规则：

- 每轮只追问最关键的缺失字段。
- 用户确认前不得调用 `campus-create-service-record`。
- 服务事项不存在时，先搜索相似事项或给出可选项。
- 名额不足时，建议更换时间、地点或服务事项。
- 任务状态按 `chat_id` 保存。

### 7.4 反馈投诉状态机

创建反馈的建议状态机：

```text
START
-> INTENT_DETECTED
-> FEEDBACK_TYPE_CLASSIFYING
-> SLOT_COLLECTING
-> SENTIMENT_CHECKING
-> USER_CONFIRMING
-> FEEDBACK_CREATING
-> RESULT_RETURNED
-> MEMORY_EVALUATING
-> END
```

必填 Slot：

```json
{
  "intent": "create_feedback",
  "requiredSlots": [
    "userId",
    "feedbackType",
    "content"
  ],
  "optionalSlots": [
    "orderId",
    "rating",
    "department",
    "expectedResolution"
  ]
}
```

执行规则：

- 投诉内容要保留用户事实表达，不能过度美化导致事实失真。
- 用户情绪明显时，先给出简短安抚，再追问缺失信息。
- 如果反馈关联办理记录，需要校验记录归属。
- 更新反馈解决方案应当要求管理员权限。

### 7.5 咨询类 Loop

咨询类通常不涉及写操作，但仍然需要检索与校验闭环：

```text
START
-> INTENT_DETECTED
-> QUERY_REWRITE
-> RETRIEVE
-> RERANK
-> ANSWER_DRAFT
-> GROUNDING_CHECK
-> ANSWER_RETURNED
-> MEMORY_EVALUATING
-> END
```

执行规则：

- 检索置信度低时，追问具体部门、校区、学生类型或时间范围。
- 多个政策都匹配时，让用户选择更具体的范围。
- 如果回答依赖当前时间，必须使用明确日期。
- 没有证据时，不编造政策细节。

### 7.6 用户确认策略

以下状态变更动作必须经过用户确认：

- 创建办理记录。
- 取消办理记录。
- 修改办理记录备注。
- 创建反馈或投诉。
- 更新反馈处理方案。
- 写入敏感或用户可见的长期记忆。

确认消息格式建议：

```text
请确认以下信息：

服务事项：图书馆研讨间预约
时间偏好：明天下午
办理方式：线上
人数/名额：1
备注：靠窗位置优先

确认后我将提交预约。
```

### 7.7 Verifier 设计

在最终回答前增加轻量 Verifier。

校验项：

- 是否回答了用户原问题。
- 路由 Agent 是否符合用户意图。
- 写操作是否已经过确认。
- 必填 Slot 是否已经收集完整。
- 工具结果是否被忠实使用。
- RAG 回答是否有来源依据。
- 是否暴露了其他用户数据。
- 记忆读写是否符合策略。

Verifier 输出：

```json
{
  "passed": true,
  "riskLevel": "low",
  "issues": [],
  "suggestedAction": "return_answer"
}
```

可选动作：

- `return_answer`：直接返回。
- `ask_clarification`：继续追问。
- `request_confirmation`：请求用户确认。
- `retry_tool`：重试工具。
- `fallback_to_manual_service`：建议人工服务。
- `block_response`：阻断回答。

### 7.8 异常恢复策略

| 异常类型 | 示例 | 恢复策略 |
| --- | --- | --- |
| 缺少参数 | 没有预约时间 | 只追问一个关键问题 |
| 服务不存在 | 服务事项名称错误 | 推荐相似服务事项 |
| 名额不足 | 研讨间无剩余名额 | 建议换时间或换服务 |
| 无权限 | 查询他人记录 | 拒绝并说明只能操作本人记录 |
| 工具超时 | MCP 服务不可用 | 提示稍后重试并保存 Trace |
| RAG 无命中 | 没有匹配政策 | 说明未找到来源，并建议联系对应部门 |
| 记忆服务不可用 | Mem0 调用失败 | 不影响主流程，继续提供非个性化回答 |

### 7.9 交付物

- Slot Schema 定义。
- 基于 `chat_id` 的任务状态存储。
- 事务办理状态机。
- 反馈投诉状态机。
- 写操作确认网关。
- Verifier 服务。
- 异常恢复规则。
- Loop 行为评测用例。

### 7.10 验收标准

- Harness 测试中，写工具不会在用户确认前被调用。
- 缺参场景能提出简洁追问。
- 工具失败时能给出可理解的恢复回答。
- 常见多轮预约任务可以稳定完成。
- Verifier 能阻断或重定向高风险回答。

## 8. 第 4 阶段：上线加固

### 8.1 安全与权限控制

必须补齐的控制：

- 在后端边界校验真实 `user_id`，不能只依赖 Prompt 中拼接的 `<userId>`。
- Prompt 不能作为权限边界。
- 用户级工具只能访问当前用户自己的记录。
- 聊天接口和工具接口增加限流。
- Trace 和日志中脱敏敏感字段。
- Trace 中不得保存密钥、Token、数据库连接串等秘密信息。

高优先级检查点：

- `SupervisorAgentController` 当前会把 `<userId>` 拼入用户输入，这只能作为上下文，不应作为可信身份来源。
- `campus-get-service-records` 可以获取所有记录，应限制为管理员工具。
- `feedback-update-solution` 应要求管理员权限。

### 8.2 可观测性

建议增加监控面板，展示：

- 请求量。
- 成功率。
- 路由分布。
- 工具平均延迟。
- 工具失败率。
- RAG 无命中率。
- 用户确认拒绝率。
- 平均对话轮次。
- 任务完成率。

落地建议：

- MVP 使用 MySQL Trace 表和简单管理页面。
- 后续接入 OpenTelemetry、Grafana、Langfuse、Phoenix 或其他 Agent 观测平台。

### 8.3 前端体验完善

建议增加：

- 多轮任务状态展示。
- 确认卡片。
- RAG 来源引用卡片。
- 回答后反馈按钮：有帮助 / 没帮助。
- 办理记录创建后的结果卡片。
- 管理端检索测试页面。

### 8.4 文档完善

建议新增或更新：

- `docs/ARCHITECTURE.md`
- `docs/HARNESS_GUIDE.md`
- `docs/RAG_GUIDE.md`
- `docs/LOOP_ENGINEERING_GUIDE.md`
- `docs/SECURITY_REVIEW.md`
- `docs/EVALUATION_REPORT_TEMPLATE.md`

## 9. 建议 Issue 拆分

### 9.1 Harness 方向

1. 增加 Trace ID 和 Trace 模型。
2. 增加 JSONL Trace Writer。
3. 增加 MCP 工具调用日志包装。
4. 创建 `agent-harness` 模块。
5. 编写路由测试用例。
6. 编写工具契约测试用例。
7. 实现 SSE 对话回放 Runner。
8. 实现规则评分器。
9. 接入 LLM Judge。
10. 生成 Markdown 和 JSON 报告。

### 9.2 RAG 方向

1. 定义知识文档元数据规范。
2. 将现有 `kownledge` 文档转换为结构化格式。
3. 实现语义切片。
4. 增加关键词检索。
5. 接入向量检索。
6. 增加重排序。
7. 增加引用输出格式。
8. 增加 RAG 回答约束 Prompt。
9. 编写 RAG 评测用例。
10. 增加管理端检索测试 API。

### 9.3 Loop 方向

1. 定义事务办理 Slot Schema。
2. 定义反馈投诉 Slot Schema。
3. 增加基于 `chat_id` 的任务状态存储。
4. 实现事务办理状态机。
5. 实现反馈投诉状态机。
6. 增加写操作确认网关。
7. 增加异常恢复策略。
8. 增加 Verifier 服务。
9. 编写 Loop 行为评测用例。
10. 增加前端确认卡片。

## 10. 核心指标

### 10.1 Harness 指标

| 指标 | 初始目标 |
| --- | --- |
| 路由准确率 | >= 90% |
| 工具调用准确率 | >= 90% |
| 确认前写操作违规数 | 0 |
| 端到端任务完成率 | >= 80% |
| P0 安全用例失败数 | 0 |

### 10.2 RAG 指标

| 指标 | 初始目标 |
| --- | --- |
| 检索召回率 | >= 85% |
| 引用准确率 | >= 85% |
| 回答忠实度通过率 | >= 90% |
| 无答案场景正确率 | >= 90% |
| 过期来源使用率 | 0 |

### 10.3 Loop 指标

| 指标 | 初始目标 |
| --- | --- |
| 缺参恢复率 | >= 85% |
| 用户确认完成率 | >= 75% |
| 工具失败恢复质量 | >= 80% |
| 简单预约平均轮次 | <= 3 |
| 越权操作阻断率 | 100% |

## 11. 推荐第一期 Sprint

第一期建议只做 Harness 工程，因为它是后续 RAG 和 Loop 优化的度量基础。

### 11.1 Sprint 1 范围

1. 增加 Trace ID 和 Trace 模型。
2. 增加 JSONL Trace Writer。
3. 为 order 和 feedback MCP 工具增加调用日志。
4. 创建 50 条路由用例。
5. 创建 20 条工具契约用例。
6. 实现简单 SSE 对话回放 Runner。
7. 生成 Markdown 评测报告。

### 11.2 Sprint 1 暂不包含

- 完整 RAG 重构。
- 完整状态机实现。
- 前端大规模改版。
- 生产级观测平台接入。

### 11.3 Sprint 1 完成定义

- 可以用一条命令运行 Harness。
- 每次运行都会生成报告。
- 报告中能看到路由准确率和工具调用通过率。
- 至少能检测出一个人为制造的 Prompt 回归。

## 12. 风险与应对

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| LLM 输出不稳定 | 测试偶发失败 | 使用宽容断言、Trace 检查和多次运行统计 |
| 只靠 Prompt 做权限控制 | 数据泄露或误操作 | 在 Service 层强制校验用户权限 |
| 知识源质量差 | 回答错误或幻觉 | 使用元数据、有效期、引用和无答案策略 |
| Loop 太重 | 用户体验变慢 | 每轮只追问一个关键问题，跳过非必填字段 |
| Trace 含敏感数据 | 隐私风险 | 日志脱敏，不记录密钥和敏感原文 |
| Harness 维护成本高 | 团队不愿使用 | YAML 用例保持可读，报告直接指向失败原因 |

## 13. 目标架构

```text
frontend
  -> supervisor-agent
    -> trace manager
    -> routing verifier
    -> consult-sub-agent
      -> RAG retrieval service
      -> memory policy
    -> order-sub-agent
      -> slot filling
      -> confirmation gate
      -> order task state machine
      -> order-mcp-server
    -> feedback-sub-agent
      -> feedback task state machine
      -> feedback-mcp-server
    -> final verifier

agent-harness
  -> routing tests
  -> tool contract tests
  -> RAG tests
  -> loop tests
  -> E2E replay
  -> reports
```

## 14. 总结

本项目已经具备多 Agent 系统的良好骨架。后续完善的关键，不是继续增加 Agent 数量，而是补齐三类工程闭环：

1. 用 Harness 工程让效果可度量、可回归。
2. 用 RAG 工程让知识回答可信、可溯源。
3. 用 Loop 工程让任务执行可控、可恢复。

完成这些改造后，系统将从演示型多 Agent 项目升级为更接近真实校园服务场景的智能服务平台，具备更好的稳定性、安全性和长期可维护性。
