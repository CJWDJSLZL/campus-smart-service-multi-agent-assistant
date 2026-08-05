# 小白入门指南：从零开始理解本项目

> 本文档面向初次接触 Agent / Spring AI / 多智能体系统的同学，用最通俗的语言解释项目的每一个核心概念，读完后能独立理解代码逻辑并动手调试。

---

## 第一章：你需要先理解的三个核心概念

### 1.1 什么是 LLM（大语言模型）？

LLM（Large Language Model，如 ChatGPT、通义千问）就是一个"超级文字接龙选手"：给它一段文字（Prompt），它预测接下来应该出现什么文字。

但这个预测能力非常强大，强到它能够：
- 理解意图（"帮我预约"= 用户要做一件事）
- 生成结构化内容（"用 JSON 格式输出"）
- 推理和规划（"先验证再创建"）
- 决定调用哪个工具

在本项目中，LLM 是整个系统的"大脑"，每个 Agent 的核心逻辑都靠 LLM 驱动。

### 1.2 什么是 Agent（智能体）？

Agent = LLM + 工具调用能力 + 感知-规划-行动循环

普通 LLM 只能"说"，Agent 能"做"：
```
用户说："帮我预约图书馆"

普通 LLM：回答"好的，您可以去图书馆官网预约..."

Agent：
  1. 理解：用户要预约图书馆研讨间
  2. 规划：先验证服务存在 → 检查名额 → 创建记录
  3. 调用工具：campus-validate-service-item("图书馆研讨间预约")
  4. 调用工具：campus-check-service-capacity("图书馆研讨间预约", 1)
  5. 调用工具：campus-create-service-record(userId=10001, ...)
  6. 回答："预约成功！记录编号 CAMPUS_20260805001"
```

### 1.3 什么是多智能体系统？

一个复杂任务让多个专业 Agent 协作完成，比单个 Agent 更高效、更准确：

```
校园服务助手（你）
    ↓
Supervisor Agent（总调度）
    知道：有三个专业助手
    会做：分析你的问题，转发给对应的助手
    ├── Consult Agent（政策专家）
    │   会做：查知识库，回答政策/流程问题
    ├── Order Agent（办事专家）
    │   会做：帮你预约/申请/查记录
    └── Feedback Agent（反馈专家）
        会做：记录你的投诉/建议/评价
```

就像公司前台：不是所有事都自己处理，而是把你引导到对应的部门。

---

## 第二章：项目架构全景

### 2.1 "收到请求到给出回答"的完整流程

```
你输入："帮我预约图书馆研讨间，明天下午4个人"
  ↓
前端（Vue3）发送 HTTP 请求（GET + SSE 连接）
  ↓
Nginx 反向代理 → supervisor-agent:10008
  ↓
SupervisorAgentController.chat()
  ├── 把你的消息改为 "帮我预约图书馆...<userId>10001</userId>"
  └── 传给 LlmRoutingAgent
        ↓
        LLM 判断：这是预约需求 → 转发给 order_agent
        ↓
        A2A 协议（gRPC）→ order-sub-agent:10006
        ↓
        MemoryInjectNode：先查你的历史偏好
        ↓
        ReactAgent（order_agent）开始循环：
          LLM 思考 → 调用 campus-validate-service-item
          LLM 思考 → 调用 campus-check-service-capacity
          LLM 思考 → 调用 campus-create-service-record
          ↓ (order-mcp-server:10002 操作 MySQL)
          LLM 生成最终回答
        ↓
        SSE 流式返回（每个字分开推送）
  ↓
前端逐字显示回答
```

### 2.2 为什么要用这么多微服务？

**单体架构的问题**：把所有功能塞在一个程序里，任何一个功能改变都要重新部署整个系统，而且代码越来越复杂难以维护。

**微服务的好处**：每个 Agent 是独立程序，可以独立升级。今天改 order-agent 的 Prompt，只需要重启 order-sub-agent，其他服务不受影响。

本项目中的关系：
```
supervisor-agent     ← 只关心路由逻辑
consult-sub-agent    ← 只关心知识库检索
order-sub-agent      ← 只关心事务办理
feedback-sub-agent   ← 只关心反馈处理
order-mcp-server     ← 只关心数据库操作
```

---

## 第三章：逐行读懂关键代码

### 3.1 SupervisorAgent.java — 路由 Agent 是如何工作的

```java
@Bean
public LlmRoutingAgent supervisorAgentBean(
        ChatModel chatModel,              // 注入 DashScope 模型
        AgentCardProvider agentCardProvider) throws Exception {

    // 定义状态键：这些键可以在图节点间传递
    KeyStrategyFactory stateFactory = () -> {
        HashMap<String, KeyStrategy> map = new HashMap<>();
        map.put("input", new ReplaceStrategy());      // 用户输入
        map.put("chat_id", new ReplaceStrategy());    // 会话ID
        map.put("user_id", new ReplaceStrategy());    // 用户ID
        map.put("messages", new ReplaceStrategy());   // 对话历史
        return map;
    };

    // 创建三个"远程 Agent 代理"
    // agentCardProvider 从 Nacos 获取子 Agent 的地址
    A2aRemoteAgent consultAgent = A2aRemoteAgent.builder()
            .name("consult_agent")
            .agentCardProvider(agentCardProvider)
            .description("处理校园政策、办事流程...咨询")  // LLM 用这段描述决定何时调用
            .build();
    // ... (类似创建 feedbackAgent、orderAgent)

    // 构建路由 Agent
    return LlmRoutingAgent.builder()
            .name("supervisor_agent")
            .model(chatModel)                    // 使用的 LLM
            .state(stateFactory)                 // 状态管理
            .description(promptConfig.getSupervisorAgentInstruction())  // System Prompt
            .inputKey("input")                   // 从 state 的 "input" 键读取用户输入
            .outputKey("messages")               // 把结果写入 state 的 "messages" 键
            .subAgents(List.of(consultAgent, feedbackAgent, orderAgent))  // 子 Agent 列表
            .build();
}
```

**理解要点**：
- `LlmRoutingAgent` 是 Spring AI Alibaba 提供的现成组件，不需要自己写循环逻辑
- `description` 里的文字就是 System Prompt，LLM 读了这段文字知道自己该干什么
- `A2aRemoteAgent.description` 是子 Agent 的"简历"，LLM 根据这段文字决定转发给哪个

### 3.2 OrderAgent.java — 子 Agent 是如何配置的

```java
@Bean
public ReactAgent orderSubAgentBean(
        @Qualifier("dashscopeChatModel") ChatModel chatModel,
        // SSE 直连工具（order-mcp-server、memory-mcp-server）
        @Qualifier("mcpToolCallbacks") ToolCallbackProvider toolsProvider,
        // Nacos 负载均衡工具（所有注册在 Nacos 的 MCP 工具）
        @Qualifier("loadbalancedMcpSyncToolCallbacks") ToolCallbackProvider nacosToolsProvider
) throws Exception {
    // 收集所有工具
    List<ToolCallback> tools = new ArrayList<>();
    for (ToolCallback tool : toolsProvider.getToolCallbacks()) {
        tools.add(tool);  // SSE 工具
    }
    for (ToolCallback tool : nacosToolsProvider.getToolCallbacks()) {
        tools.add(tool);  // Nacos 工具
    }

    // Checkpoint 配置：用 MemorySaver 持久化会话状态
    var saver = new MemorySaver();
    var compileConfig = CompileConfig.builder()
            .saverConfig(SaverConfig.builder()
                .register(SaverEnum.MEMORY.getValue(), saver).build())
            .build();

    return ReactAgent.builder()
            .compileConfig(compileConfig)           // 挂载 Checkpoint
            .name("order_agent")
            .model(chatModel)
            .instruction(promptConfig.getOrderAgentInstruction())  // System Prompt
            .tools(tools)                           // 注入所有工具
            .build();
}
```

**理解要点**：
- `tools` 列表就是 LLM 的"工具箱"，LLM 看到工具列表中有哪些工具，才能决定调用哪个
- `MemorySaver` + `compileConfig` 就是"记忆功能"，同一个 `chat_id` 的连续对话 LLM 都能记住

### 3.3 ConsultService.java — RAG 检索是如何工作的

```java
public String searchKnowledge(String query) {
    // 第一步：查询改写（让 LLM 扩展查询语义）
    String rewrittenQuery = rewriteQuery(query);
    // 例：query="奖学金" → rewrittenQuery="奖学金申请条件政策评定流程所需材料"

    // 第二步：关键词精确匹配（混合检索路 1）
    String exactMatchContext = buildExactMatchContext(query);
    // 查 products 表，如 query="图书馆" → "【服务事项精确匹配】\n- 图书馆研讨间预约：..."

    // 第三步：向量检索（混合检索路 2）
    DashScopeDocumentRetrieverOptions options = ...;
    List<Document> documents = dashscopeApi.retriever(indexID, rewrittenQuery, options);
    // 在百炼知识库中找最相似的文档块，并 Rerank 取 Top-3

    // 第四步：合并结果（精确匹配前置）
    StringBuilder result = new StringBuilder();
    if (!exactMatchContext.isEmpty()) result.append(exactMatchContext).append("\n\n");
    for (Document doc : documents) result.append(doc.getText());

    // 第五步：附加来源引用
    result.append("\n\n---\n**参考来源：**\n- ").append(source).append("\n");

    return result.toString();
    // 这段文字将作为工具调用结果注入 Agent 的消息历史
    // LLM 基于这段内容作答，不再凭记忆猜测
}
```

### 3.4 MemoryInjectNode.java — 记忆注入是如何工作的

```java
@Override
public Map<String, Object> apply(OverAllState state) throws Exception {
    // 从消息中提取用户 ID（Supervisor 注入的 <userId> 标签）
    String userId = extractUserId(state);
    if (!StringUtils.hasText(userId)) return Map.of();  // 没有 userId 直接跳过

    // 调用 memory-search 工具查询历史偏好
    String toolInput = String.format("{\"userId\":\"%s\",\"query\":\"用户偏好和历史习惯\"}", userId);
    String memoryResult = memorySearchTool.call(toolInput);
    // 返回类似："用户偏好线上办理，常在晚上预约，关注奖学金政策"

    if (StringUtils.hasText(memoryResult) && !memoryResult.contains("未找到")) {
        // 把历史偏好插入到消息列表最前面
        List<Message> enriched = new ArrayList<>();
        enriched.add(new SystemMessage("【用户历史偏好记忆】以下是该用户的历史偏好，请在回答时参考：\n" + memoryResult));
        enriched.addAll(messages);  // 原有消息跟在后面
        return Map.of("messages", enriched);
    }
    return Map.of();
}
```

---

## 第四章：如何在本地运行项目

### 4.1 前置准备

| 工具 | 版本要求 | 用途 |
|------|---------|------|
| Java | 17+ | 运行后端 |
| Maven | 3.8+ | 编译后端 |
| Node.js | 20+ | 运行前端 |
| Docker Desktop | 最新 | 运行中间件 |
| DashScope API Key | — | 大模型调用 |
| 百炼知识库 ID | — | RAG 检索 |

### 4.2 第一步：配置环境变量

```bash
cp env.template .env
```

打开 `.env` 文件，**至少**填写：
```
DASHSCOPE_API_KEY=sk-xxxxxx      # 在 DashScope 控制台获取
DASHSCOPE_INDEX_ID=xxxxxxxx      # 创建百炼知识库后获得
```

**Mem0 可选**：不配置 `MEM0_API_KEY` 时，Memory 功能会静默降级（不报错，只是没有长期记忆）。

### 4.3 第二步：启动中间件

```bash
cd docker/middleware
cp .env.example .env
cp mysql.env.example mysql.env
cp nacos.env.example nacos.env
cp redis.env.example redis.env
# 编辑这些文件，把 change-me-xxx 改成你自己的密码
docker compose up -d
```

等待约 30 秒，验证中间件正常：
```bash
docker compose ps
# 应该看到 mysql、nacos、redis 都是 running 状态
```

### 4.4 第三步：编译后端

```bash
mvn clean package -DskipTests
```

首次编译会下载依赖，耗时 5-15 分钟（取决于网络）。

### 4.5 第四步：启动后端

**Windows**：
```powershell
powershell -ExecutionPolicy Bypass -File .\start-backend.ps1
```

**Linux/Mac**：
```bash
# 依次启动（建议每个在独立终端）
java -jar order-mcp-server/target/order-mcp-server-1.0.0.jar &
java -jar feedback-mcp-server/target/feedback-mcp-server-1.0.0.jar &
java -jar memory-mcp-server/target/memory-mcp-server-1.0.0.jar &
java -jar consult-sub-agent/target/consult-sub-agent-1.0.0.jar &
java -jar order-sub-agent/target/order-sub-agent-1.0.0.jar &
java -jar feedback-sub-agent/target/feedback-sub-agent-1.0.0.jar &
java -jar supervisor-agent/target/supervisor-agent-1.0.0.jar &
```

**验证启动**：
```bash
curl http://localhost:10008/actuator/health  # supervisor-agent
# 应返回 {"status":"UP"}
```

### 4.6 第五步：启动前端

```bash
cd frontend
npm install
npm run dev
```

打开浏览器访问 `http://localhost:3000`，在右上角设置 userId（如 `10001`），即可开始对话。

---

## 第五章：常见问题排查

### Q1：启动报错 "Could not connect to Nacos"

**原因**：Nacos 中间件未启动或连接配置错误

**排查**：
```bash
docker compose ps  # 检查 nacos 容器状态
curl http://localhost:8848/nacos/v1/ns/instance/list?serviceName=test  # 测试连接
```

### Q2：调用时报 "No tool found: memory-search"

**原因**：memory-mcp-server 未成功注册到 Nacos

**排查**：访问 Nacos 控制台（http://localhost:8848/nacos），检查"服务管理"中是否有 `memory-mcp-server` 服务实例。

### Q3：LLM 回答不调用工具，直接凭记忆回答

**原因**：Prompt 指引不够明确，或工具描述质量低

**排查**：
1. 检查 application.yml 中的 `agent.prompts.order-agent-instruction` 是否包含工具映射说明
2. 检查 `@Tool` 注解的 description 是否清晰描述了使用场景

### Q4：RAG 检索结果不相关

**排查**：
1. 确认 `DASHSCOPE_INDEX_ID` 配置正确
2. 确认知识库文档已上传到百炼（控制台查看文档状态是否"就绪"）
3. 在日志中查看 "查询改写" 的结果，判断改写方向是否正确

### Q5：前端收不到流式数据

**排查**：
1. 检查 Nginx 是否启动（`docker ps | grep nginx`）
2. 检查 Nginx 配置是否包含 `proxy_buffering off`
3. 直接调用后端接口验证：`curl -N http://localhost:10008/api/assistant/chat?...`

---

## 第六章：调试技巧

### 6.1 使用 Debug 接口直接测试子 Agent

不经过 Supervisor，直接调用单个子 Agent：

```bash
# 测试 order-sub-agent（默认 ReactAgent 模式）
curl -N "http://localhost:10006/api/order-sub-agent/debug?user_query=查询我的记录&chat_id=test001"

# 测试 Plan-and-Execute 模式
curl -N "http://localhost:10006/api/order-sub-agent/debug?user_query=帮我预约图书馆&mode=plan&chat_id=test002"

# 测试 Memory 主动注入模式
curl -N "http://localhost:10006/api/order-sub-agent/debug?user_query=帮我预约&mode=memory&chat_id=test003"

# 测试 HITL 模式
curl -N "http://localhost:10006/api/order-sub-agent/debug?user_query=帮我预约图书馆&mode=hitl&chat_id=test004"
# 收到计划后确认：
curl -N "http://localhost:10006/api/order-sub-agent/confirm?chat_id=test004&action=approve"
```

### 6.2 开启详细日志

在 `application.yml` 中临时开启 DEBUG：
```yaml
logging:
  level:
    com.alibaba.cloud.ai: DEBUG
    org.springframework.ai: DEBUG
```

### 6.3 监控 Nacos 服务注册

访问 `http://localhost:8848/nacos`（默认用户名/密码：nacos/nacos），查看"服务管理 → 服务列表"，确认所有 MCP Server 和子 Agent 已注册。

---

## 第七章：扩展和定制

### 7.1 如何修改 Agent 的行为

修改 `application.yml` 中的 Prompt 字段即可（无需重新编译）：
```yaml
# order-sub-agent/src/main/resources/application.yml
agent:
  prompts:
    order-agent-instruction: |
      # 在这里修改 Prompt
      你是校园事务办理 Agent...
      # 添加新的工作规则
```

如果配置了 Nacos 配置中心，修改后 Prompt 热更新，无需重启。

### 7.2 如何新增一个 MCP 工具

在 `order-mcp-server` 中：
```java
@Tool(name = "campus-new-tool",
      description = "工具描述（动词+对象+场景+限制）")
public String newTool(@ToolParam(description = "参数说明") String param) {
    // 实现逻辑
    return "结果文本";
}
```

重启 `order-mcp-server`，工具会自动注册到 Nacos，所有引用它的子 Agent 都能感知到新工具。

### 7.3 如何增加新的知识库文档

1. 在 `consult-sub-agent/src/main/resources/knowledge/` 创建新的 `.md` 文件
2. 按文档结构规范写内容（含 Front Matter 元数据）
3. 将文件上传到百炼知识库（控制台 → 上传文档 → 等待索引完成）

上传后 Agent 会在下次调用 `consult-search-knowledge` 时检索到新内容，无需重启。
