# 技术栈详解

## 一、后端核心框架

### Spring Boot 3.2.0

整个后端由 7 个 Spring Boot 应用组成（Maven 多模块），统一父 pom 管理依赖版本。

```xml
<!-- root pom.xml -->
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>3.2.0</version>
</parent>
```

每个子模块是独立可运行的 Spring Boot 应用，支持独立部署和水平扩展。

### Spring AI 1.0.0

Spring AI 是 Spring 官方的 AI 集成框架，提供：

- **ChatClient**：统一的 LLM 调用接口，屏蔽不同模型提供商的差异
- **ChatModel / ChatResponse**：模型调用的抽象层
- **ToolCallback / ToolCallbackProvider**：工具调用的标准接口，对应 OpenAI Function Calling
- **BeanOutputConverter**：LLM 结构化输出解析，将 JSON 文本转为 Java 类型
- **SystemPromptTemplate**：Prompt 模板渲染工具
- **Document**：RAG 检索结果的标准数据结构

```java
// ChatClient 使用示例
String rewritten = chatClient.prompt()
    .system(systemPrompt)
    .user(userQuery)
    .call()
    .content();
```

### Spring AI Alibaba 1.0.0.4

Spring AI Alibaba 是阿里云对 Spring AI 的扩展实现，新增：

- **DashScope 模型集成**：`dashscopeChatModel`，支持 `qwen-plus` 等通义系列模型
- **DashScopeApi**：DashScope 文档检索 API 的封装，用于 RAG 向量检索
- **spring-ai-alibaba-graph-core**：多 Agent 编排核心库，包含：
  - `StateGraph`：有状态图，支持节点 + 边构建复杂工作流
  - `ReactAgent`：ReAct 模式的 Agent 实现
  - `LlmRoutingAgent`：基于 LLM 的路由 Agent
  - `NodeAction / AsyncNodeAction`：图节点动作接口
  - `IterationNode`：批量迭代节点
  - `LlmNode`：封装 LLM 调用的标准节点
  - `CompileConfig`：图编译配置，支持 Checkpoint 和 interruptBefore
  - `MemorySaver / RedisSaver`：会话状态持久化
  - `OverAllState`：图的全局状态对象，节点间通过其传递数据
  - `KeyStrategyFactory`：状态 key 的合并策略配置
- **spring-ai-alibaba-starter-a2a-server / a2a-client**：A2A 协议服务端和客户端
- **spring-ai-alibaba-starter-mcp-registry**：MCP 工具注册到 Nacos

---

## 二、AI 大模型与服务

### DashScope（阿里云通义）

- **模型**：`qwen-plus`（默认），可通过 `DASHSCOPE_MODEL` 环境变量切换
- **配置方式**：`spring.ai.dashscope.api-key`，自动注入 `dashscopeChatModel` Bean
- **文档检索**：`DashScopeDocumentRetrieverOptions`，支持 reranking、top-n、min-score 参数

```yaml
spring:
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}
      chat:
        options:
          model: ${DASHSCOPE_MODEL:qwen-plus}
      document-retrieval:
        index-id: ${DASHSCOPE_INDEX_ID}
        enable-reranking: true
        rerank-top-n: 3
        rerank-min-score: 0
```

### 阿里云百炼（Bailian）知识库

- 提供向量化文档存储和语义检索服务
- 通过 `DashScopeApi.retriever(indexId, query, options)` 调用
- 知识库 ID（`index-id`）由百炼控制台创建后获得
- 支持 Rerank 重排序，提升检索精准度

### Mem0（外部记忆服务）

- 提供语义化长期记忆的存储和检索
- REST API 接口：`POST /v1/memories/`（存储）、`POST /v2/memories/search/`（检索）
- 每条记忆关联 `user_id`，支持按用户隔离
- 在 `MemoryService.java` 中封装，通过 `RestTemplate` 调用

```java
// 存储记忆
POST https://api.mem0.ai/v1/memories/
Authorization: Token {MEM0_API_KEY}
{"messages": [{"role": "user", "content": "用户偏好内容"}], "user_id": "10001"}

// 检索记忆
POST https://api.mem0.ai/v2/memories/search/
{"query": "用户偏好和历史习惯", "user_id": "10001", "filters": {...}}
```

---

## 三、微服务基础设施

### Nacos 2.x

Nacos 在本项目中承担两个核心角色：

**1. MCP 工具注册中心**

各 MCP Server 启动时通过 `spring-ai-alibaba-starter-mcp-registry` 自动将工具注册到 Nacos。
子 Agent 通过 `loadbalancedMcpSyncToolCallbacks`（Nacos 负载均衡 MCP 客户端）发现和调用工具。

```yaml
# order-mcp-server 注册配置
spring.ai.alibaba.mcp.nacos:
  server-addr: ${NACOS_SERVER_ADDR:127.0.0.1:8848}
  namespace: ${NACOS_NAMESPACE:public}
  client:
    enabled: true
```

**2. A2A Agent 发现**

各子 Agent 通过 `spring-ai-alibaba-starter-a2a-server` 启动时将自己的 AgentCard 注册到 Nacos。
Supervisor 通过 `NacosA2aAgentCardProvider` 动态发现子 Agent 的 gRPC 端点。

```java
// Supervisor 通过 Nacos 发现子 Agent
AgentCard consultAgentCard = agentCardProvider.getAgentCard("consult_agent").getAgentCard();
A2aRemoteAgent consultAgent = A2aRemoteAgent.builder()
    .name("consult_agent")
    .agentCardProvider(agentCardProvider)
    .description("处理校园政策、办事流程、通知公告和服务事项咨询")
    .build();
```

### MySQL 8

通过 MyBatis 3.5.19 操作，4 张核心表：`users`、`products`（服务事项）、`orders`（办理记录）、`feedback`（反馈投诉）。

主键策略：办理记录编号格式为 `CAMPUS_` + 时间戳（如 `CAMPUS_20260601001`）。

### Redis

当前用于 Spring Boot 配置缓存，后续可用于 `RedisSaver` 实现分布式会话 Checkpoint（代码中已有注释版本）。

---

## 四、MCP（Model Context Protocol）

### 协议说明

MCP 是 Anthropic 制定的 Agent 工具调用标准协议。本项目使用 Spring AI 的 MCP 实现，支持两种传输方式：

| 传输方式 | Bean 名称 | 说明 |
|---------|----------|------|
| SSE（Server-Sent Events） | `mcpToolCallbacks` | order-sub-agent 直连 order-mcp-server 和 memory-mcp-server |
| Sync（Nacos 负载均衡） | `loadbalancedMcpSyncToolCallbacks` | 所有子 Agent 通过 Nacos 发现工具 |

### MCP 工具实现规范

所有工具使用 `@Tool` + `@ToolParam` 注解，工具描述遵循**动词+对象+场景+限制**四要素：

```java
@Tool(name = "campus-create-service-record",
      description = "创建校园服务事项的办理或预约记录（需提供 userId、服务事项名称、办理方式、优先级）。"
                  + "适用于图书馆研讨间预约、心理咨询预约、证明材料办理等场景。"
                  + "执行前须确认用户已知晓服务名称和时间偏好；系统会自动校验名额是否充足。"
                  + "不适用于查询、修改或取消已有记录。")
public String createOrderWithUser(
        @ToolParam(description = "用户ID，必须为正整数") Long userId,
        @ToolParam(description = "校园服务事项名称，例如：图书馆研讨间预约") String productName,
        ...
```

---

## 五、前端技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Vue 3 | 3.3.8 | Composition API 组件框架 |
| TypeScript | 5.x | 类型安全 |
| Vite | 6.3.5 | 构建工具 |
| Ant Design Vue | 4.0.8 | UI 组件库（Button、Modal、Card 等） |
| Pinia | 2.3.1 | 状态管理（chatStore · configStore） |
| Vue Router | 4.2.5 | 路由管理（Chat · Home · Settings） |
| Axios | latest | HTTP 客户端 |
| vue-i18n | latest | 国际化（中英文） |

**SSE 流式接收**（`ReadableStream` 原生 API）：

```typescript
const response = await fetch(url, {
  method: 'GET',
  headers: { 'Accept': 'text/event-stream' }
})
const reader = response.body.getReader()
while (true) {
  const { done, value } = await reader.read()
  if (done) break
  // 解析 data: 前缀的 SSE 数据并更新聊天界面
}
```

---

## 六、定时调度

| 方案 | 条件 | 说明 |
|------|------|------|
| `@Scheduled`（本地） | `xxl.job.enabled=false`（默认） | `LocalScheduledTrigger.java`，零依赖，适合演示 |
| XXL-JOB | `xxl.job.enabled=true` | 分布式任务调度，生产环境推荐 |

`@EnableScheduling` 在 `SupervisorAgentApplication.java` 启用。

---

## 七、可观测性

- **OpenTelemetry**：consult-sub-agent 配置 OTLP Tracing 上报（`management.otlp.tracing.endpoint`）
- **Micrometer**：接入 `micrometer-tracing-bridge-otel`，链路追踪与 Spring Boot Actuator 集成
- **ARMS**：`spring-ai-alibaba-autoconfigure-arms-observation` 自动埋点 AI 调用指标
- **SimpleLoggerAdvisor**：ChatClient 请求/响应日志输出，方便本地调试

---

## 八、项目构建

```
Maven 多模块项目（8 个模块）：
├── common               ← 公共组件（无 Spring Boot 启动类）
├── order-mcp-server     ← Spring Boot 应用
├── feedback-mcp-server  ← Spring Boot 应用
├── memory-mcp-server    ← Spring Boot 应用
├── consult-sub-agent    ← Spring Boot 应用
├── order-sub-agent      ← Spring Boot 应用
├── feedback-sub-agent   ← Spring Boot 应用
└── supervisor-agent     ← Spring Boot 应用（主入口）
```

构建命令：
```bash
mvn clean package -DskipTests
```

各模块独立打 Jar 包，通过 `spring-boot-maven-plugin` 生成可执行 Fat Jar。
