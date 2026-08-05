# 技术栈功能与原理详解

> 本文档对项目中每项技术的功能定位、工作原理及在本项目中的具体用法进行系统性说明。

---

## 一、Java 17

### 功能
Java 17 是 LTS（长期支持）版本，是本项目所有后端模块的编程语言。

### 用到的新特性

**Record 类型**（不可变数据载体）：
```java
// order-sub-agent/model/ExecutionPlan.java
public record ExecutionPlan(
    String goal,
    boolean needsClarification,
    String clarificationQuestion,
    List<ExecutionStep> steps
) {
    public record ExecutionStep(
        int stepNumber,
        String toolName,
        String description,
        String toolParameters
    ) {}
}
```
Record 自动生成构造器、getter、equals、hashCode、toString，比传统 POJO 减少约 70% 样板代码，且字段不可变，天然适合作为 LLM 结构化输出的目标类型。

**var 局部变量类型推断**：
```java
var saver = new MemorySaver();
var compileConfig = CompileConfig.builder()...build();
```

**文本块（Text Block）**：
```java
private static final String PLANNER_SYSTEM_TEMPLATE = """
        你是一个校园事务办理规划助手。
        ...
        {format_instructions}
        """;
```
多行 Prompt 字符串使用文本块，避免字符串拼接，可读性高。

**switch 表达式、instanceof pattern matching** 等均在框架内部使用。

---

## 二、Spring Boot 3.2.0

### 功能
应用启动框架，负责自动配置、Bean 生命周期管理、条件装配等。8 个模块各为独立的 Spring Boot 应用。

### 核心原理

**自动配置（Auto-Configuration）**：
`@SpringBootApplication` = `@EnableAutoConfiguration` + `@ComponentScan` + `@SpringBootConfiguration`。
Spring Boot 扫描 classpath 中的 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`，根据 `@ConditionalOn*` 条件决定哪些配置类生效。本项目中 `spring-ai-alibaba-starter-dashscope` 的 DashScopeAutoConfiguration 即通过此机制自动注入 `dashscopeChatModel` Bean。

**条件装配**：
```java
// LocalScheduledTrigger.java
@ConditionalOnProperty(name = "xxl.job.enabled", havingValue = "false", matchIfMissing = true)
public class LocalScheduledTrigger {
```
`xxl.job.enabled=false` 时自动激活本地调度，`true` 时关闭，切换零代码改动。

**配置绑定**：
```java
@ConfigurationProperties(prefix = "agent.prompts")
public class SupervisorAgentPromptConfig {
    private String supervisorAgentInstruction;
}
```
YAML 中的 `agent.prompts.supervisor-agent-instruction` 自动绑定到对应字段，支持热更新（配合 Nacos）。

**@PostConstruct 初始化**：
```java
@PostConstruct
public void initRetriever() {
    this.dashscopeApi = DashScopeApi.builder().apiKey(apiKey).build();
    this.chatClient = ChatClient.builder(chatModel).build();
}
```
依赖注入完成后执行，确保 `@Value` 字段已赋值。

---

## 三、Spring AI 1.0.0

### 功能
Spring 官方 AI 集成框架，提供跨 LLM 提供商的统一抽象层。

### 核心组件原理

**ChatClient**：
门面模式封装，屏蔽不同模型的 API 差异。采用 Builder 模式：
```java
ChatClient client = ChatClient.builder(chatModel)
    .defaultAdvisors(new SimpleLoggerAdvisor())  // 拦截器
    .build();

String result = client.prompt()
    .system(systemPrompt)
    .user(userMessage)
    .call()
    .content();
```
内部经过 `ChatClientAdvisor` 链（类似 Servlet Filter），每个 Advisor 可拦截请求/响应（如日志、缓存、限流）。

**BeanOutputConverter\<T\>**：
将 Java Record/POJO 的字段反射出 JSON Schema，注入 Prompt 指导 LLM 输出。收到响应后使用 Jackson ObjectMapper 反序列化：
```java
// 1. 生成 Schema 说明注入 Prompt
String formatInstructions = converter.getFormat();
// 输出类似：Respond in JSON format. Schema: {"goal":"string","steps":[...]}

// 2. 解析 LLM 输出
ExecutionPlan plan = converter.convert(rawJsonString);
```

**ToolCallback / @Tool 注解**：
`@Tool` 注解被 `MethodToolCallbackProvider` 扫描，通过反射将方法包装为 `ToolCallback`。LLM 调用时，Spring AI 将工具定义（名称、描述、参数 Schema）序列化为 OpenAI Function Calling 格式发送给模型；模型返回 tool_call 后，框架自动反序列化参数并调用对应 Java 方法。

**Document**：
RAG 检索结果的标准载体，包含 `text`（内容）和 `metadata`（元数据如 title、source）。`DashScopeDocumentRetrieverOptions` 封装检索参数（rerank、topN、minScore）。

---

## 四、Spring AI Alibaba 1.0.0.4

### 4.1 spring-ai-alibaba-graph-core（核心）

**StateGraph 原理**：
有向无环图（DAG）的执行引擎。节点（NodeAction）对输入 State 做变换后输出新 State，边（Edge）决定下一个节点。
```
compile() 阶段：
  1. 验证图结构（无死循环、所有节点可达）
  2. 绑定 CompileConfig（Checkpoint Saver、interruptBefore 节点列表）
  3. 生成 CompiledGraph

fluxStream(input, runnableConfig) 执行阶段：
  1. 从 Saver 读取 threadId 对应的历史 Checkpoint
  2. 合并 input 与历史 State 得到 OverAllState
  3. 按边拓扑顺序驱动节点执行
  4. 每个节点输出包装为 NodeOutput 推入 Flux 流
  5. 执行结束或 interruptBefore 触发时将 State 写入 Saver
```

**OverAllState**：
节点间共享的可变状态容器，`Map<String, Object>` 底层，每个 key 绑定 `KeyStrategy`（合并策略）：
- `ReplaceStrategy`：新值完全覆盖旧值（本项目全部使用）
- `AppendStrategy`：新值追加到列表尾部

**MemorySaver**：
```java
// 内部实现：ConcurrentHashMap<String(threadId), Map<String, Checkpoint>>
// Checkpoint 包含：state（OverAllState 快照）、metadata、config
```
每次 `fluxStream` 完成或 interrupt 时自动保存；下次同 threadId 请求时自动恢复。

**CompileConfig.interruptBefore**：
```java
CompileConfig.builder()
    .interruptBefore(List.of("executor"))
    .build()
```
图执行到 executor 节点前检查 interruptBefore 列表，若命中则暂停：保存当前 State 到 Saver，向 Flux 流发送 interrupt 信号，不继续执行。下次同 threadId 请求（传入空 input）时从 Saver 恢复并从 executor 继续。

**ReactAgent 原理**：
ReAct（Reasoning+Acting）循环实现，本质是一个单节点的特殊 Graph：
```
while (未结束) {
    调用 LLM（携带 system prompt + 当前 messages + 工具列表）
    if (LLM 决定调用工具) {
        执行工具调用
        将工具结果追加到 messages
    } else {
        输出最终回答
        break
    }
}
```

**LlmRoutingAgent 原理**：
Supervisor 使用的路由 Agent，LLM 根据用户意图从 subAgents 列表中选择一个，通过 A2A 协议发起调用。

**IterationNode 原理**：
将 inputArrayJsonKey 指向的 JSON 数组逐元素取出，每个元素独立运行 subGraph，结果写入 outputArrayJsonKey 指向的数组。串行执行防止 API 限速。

### 4.2 DashScope 集成

`spring-ai-alibaba-starter-dashscope` 自动注入 `dashscopeChatModel`，内部使用 WebClient 调用 `POST https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`（OpenAI 兼容接口）。

`DashScopeApi.retriever(indexId, query, options)` 调用百炼检索端点，返回 `List<Document>`。

### 4.3 A2A 协议

**原理**：
- 子 Agent（A2A Server）启动时通过 NacosA2ARegistryService 将 AgentCard（名称、描述、gRPC endpoint）注册到 Nacos
- Supervisor（A2A Client）通过 NacosA2aAgentCardProvider 从 Nacos 查询目标 Agent 的 gRPC 地址
- 使用 gRPC（Netty Shaded 1.75.0）建立 HTTP/2 连接，以流式方式传输 Agent 的响应 token
- AgentCard 包含 Agent 的能力描述，LLM 用这些描述做路由决策

### 4.4 MCP 工具注册

`spring-ai-alibaba-starter-mcp-registry` 启动时扫描所有 `@Tool` 方法，通过 Nacos MCP Registry API 将工具名称、描述、参数 Schema 注册为 Nacos 服务配置。

子 Agent 通过 `loadbalancedMcpSyncToolCallbacks`（实现 Nacos 负载均衡的 MCP Client）在运行时动态发现工具。

---

## 五、MyBatis 3.5

### 功能
ORM 框架，将 SQL 查询与 Java 方法绑定，实现对象关系映射。

### 原理

MyBatis 通过 `@Mapper` 接口 + XML（或注解）SQL，动态代理生成实现类：
```java
// ProductMapper.java
@Mapper
public interface ProductMapper {
    List<Product> selectByNameLike(@Param("name") String name);
    int existsByNameAndStatusTrue(@Param("name") String name);
}
```
MyBatis 在运行时为 `ProductMapper` 创建 JDK 动态代理，方法调用转换为对应 SQL 执行，结果集通过反射映射到 `Product` 对象。

**下划线转驼峰**（配置启用）：
```yaml
mybatis:
  configuration:
    map-underscore-to-camel-case: true
```
数据库字段 `product_name` 自动映射到 Java 字段 `productName`。

---

## 六、Nacos

### 功能
阿里巴巴开源的动态服务发现、配置管理和服务管理平台。本项目中承担两个角色：
1. **MCP 工具注册中心**：MCP Server 的工具注册和发现
2. **A2A Agent 发现**：子 Agent 的 AgentCard 注册和 Supervisor 的服务发现

### 原理

**服务注册**：
MCP Server / 子 Agent 启动时，向 Nacos Server（默认 `127.0.0.1:8848`）发送注册请求，携带服务名、IP、端口、元数据（工具定义、AgentCard）。Nacos 维护服务列表，定期向注册服务发送心跳检测。

**服务发现**：
子 Agent 订阅 `memory-mcp-server`、`order-mcp-server` 等服务，Nacos 推送服务列表变更（长轮询/推送模式）。`loadbalancedMcpSyncToolCallbacks` 使用 Nacos 提供的 IP 列表做轮询负载均衡。

**配置中心**：
`spring-ai-alibaba-agent-nacos` 支持从 Nacos 配置中心拉取 Agent Prompt（`promptKey: consult-sub-agent-instruction`），实现 Prompt 热更新无需重启。

---

## 七、Redis

### 功能
内存数据库，当前主要用于缓存。代码中也预留了 `RedisSaver` 实现，可替换 `MemorySaver` 实现分布式会话 Checkpoint。

### RedisSaver 原理（备用）
```java
// 已注释代码，可开启
var saver = new RedisSaver();
// RedisSaver 将 OverAllState 序列化后存入 Redis
// Key 格式：checkpoint:{threadId}
// 支持多实例 Supervisor 共享同一用户的会话状态
```

---

## 八、Mem0

### 功能
外部语义记忆服务，存储和检索用户长期偏好。

### 原理

**存储（v1 API）**：
```
POST https://api.mem0.ai/v1/memories/
{
  "messages": [{"role": "user", "content": "用户偏好内容"}],
  "user_id": "10001"
}
```
Mem0 对 content 做向量化后存入向量数据库，关联 user_id。

**检索（v2 API）**：
```
POST https://api.mem0.ai/v2/memories/search/
{
  "query": "用户偏好和历史习惯",
  "user_id": "10001",
  "filters": {"created_at": {"gte": "两周前"}}
}
```
对 query 向量化后做语义检索，返回与 query 最相似的历史记忆条目。

**异步写入设计**：
`MemoryService.storeMemory()` 立即返回，异步委托 `storeMemoryAsync()`（`@Async` 注解）执行真正的 HTTP 请求，不阻塞 Agent 响应流程。

---

## 九、Spring WebFlux & Reactor

### 功能
响应式 Web 框架，基于 Netty 非阻塞 IO，实现 SSE（Server-Sent Events）流式推送。

### 原理

**Flux\<NodeOutput\> 处理链**：
```java
Flux<NodeOutput> result = compiledGraph.fluxStream(input, runnableConfig);

result
    .filter(output -> "llm".equals(output.node()))    // 只取 llm 节点的输出
    .cast(StreamingOutput.class)
    .map(StreamingOutput::chunk)                       // 提取文本 chunk
    .filter(chunk -> !chunk.isEmpty())
    .map(content -> ServerSentEvent.builder(content).build())
    .doOnNext(sink::tryEmitNext)                       // 推入 Sinks
    .doOnComplete(() -> sink.tryEmitComplete())
    .subscribe();
```

**Sinks.Many 背压控制**：
`Sinks.many().unicast().onBackpressureBuffer()` 创建单播 Sink，支持背压缓冲。`tryEmitNext()` 非阻塞发送，不阻塞 LLM 推理线程。

**ServerSentEvent 格式**：
```
data: 您的预约
data: 已成功创建，
data: 记录编号 CAMPUS_20260803001
data: 
```
每个 token 对应一行 `data:`，前端通过 `ReadableStream` 逐行解析。

---

## 十、gRPC（A2A 底层传输）

### 功能
高性能 RPC 框架，基于 HTTP/2 协议，支持双向流式传输。A2A 协议使用 gRPC 在 Supervisor 和子 Agent 之间传输 Agent 响应流。

### 原理
- **HTTP/2 多路复用**：单连接并发多个流，低延迟
- **Protobuf 序列化**：二进制格式，比 JSON 体积小 3-10 倍
- **服务端流（Server Streaming）**：A2A 中子 Agent 逐 token 向 Supervisor 推送，Supervisor 再透传给前端

---

## 十一、XXL-JOB 3.2.0

### 功能
分布式任务调度框架，管理定时 Agent 任务（日报、评测）。

### 原理
- **Executor 注册**：应用启动时将自己注册为 XxlJob Admin 的 Executor（携带 appname、IP、port）
- **任务触发**：Admin 在 Cron 触发时向 Executor 发送 HTTP 请求，携带任务参数（如钉钉 Access Token）
- **Handler 执行**：`XxlJobScheduledAgentManager.registerTask()` 将 Agent 包装为 IJobHandler，Admin 触发时调用 `compiledGraph.invoke()`

```java
XxlJobExecutor.registJobHandler(task.getName(), new IJobHandler() {
    @Override
    public void execute() {
        XxlJobContext context = XxlJobContext.getXxlJobContext();
        // 从任务参数中获取 access_token 等配置
        compiledGraph.invoke(Map.of("xxl-job-context", context));
    }
});
```

---

## 十二、Vue 3 + TypeScript + Vite

### Vue 3 Composition API 原理
```typescript
const confirmVisible = ref(false)           // 响应式原始值（Proxy 包装）
const messages = computed(() => ...)        // 计算属性（依赖追踪）
const chatStore = useChatStore()            // Pinia 状态
```
Vue 3 使用 ES6 Proxy 实现响应式，getter/setter 拦截触发依赖收集和更新通知，比 Vue 2 的 Object.defineProperty 更完整（支持动态属性、数组索引）。

### SSE 接收原理
```typescript
const response = await fetch(url, { headers: { 'Accept': 'text/event-stream' } })
const reader = response.body.getReader()

while (true) {
    const { done, value } = await reader.read()
    if (done) break
    const chunk = new TextDecoder().decode(value, { stream: true })
    // 解析 "data: xxx\n\n" 格式
    buffer += chunk
    const lines = buffer.split('\n')
    buffer = lines.pop() || ''
    for (const line of lines) {
        if (line.startsWith('data:')) {
            const data = line.slice(5).trim()
            assistantContent += data
            chatStore.updateLastMessage(assistantContent, true)
        }
    }
}
```

### Pinia 状态管理原理
基于 Vue 3 响应式系统，Store 中的 state 是 `reactive()` 包装的对象。组件通过 `useXxxStore()` 获取 Store 实例，状态变更自动触发组件重渲染，无需手动 commit。

---

## 十三、Nginx（domain-proxy）

### 功能
反向代理，将前端请求按路径路由到对应后端服务：
- `/api/assistant/` → supervisor-agent:10008
- `/api/order-sub-agent/` → order-sub-agent:10006
- `/api/consult_sub_agent/` → consult-sub-agent:10005
- `/api/feedback-sub-agent/` → feedback-sub-agent:10007

### SSE 特殊配置
```nginx
proxy_buffering off;           # 禁用缓冲，SSE 数据立即转发
proxy_cache off;
proxy_read_timeout 3600s;      # 长连接超时 1 小时
proxy_set_header Connection ''; # 保持持久连接
chunked_transfer_encoding on;
```

---

## 十四、OpenTelemetry & Micrometer

### 功能
可观测性三支柱：Trace（链路追踪）+ Metric（指标）+ Log（日志）。

### 原理

**链路追踪**：
`micrometer-tracing-bridge-otel` 将 Micrometer 的 Tracer 接口代理到 OTel SDK。每个 HTTP 请求、ChatClient 调用、工具调用都自动创建 Span（携带 trace_id、span_id、parent_span_id）。

**OTLP 上报**：
Span 数据通过 OTLP（OpenTelemetry Protocol）协议以 gRPC 或 HTTP 方式上报到 Collector（`management.otlp.tracing.endpoint`），可对接 Jaeger、SkyWalking、ARMS 等后端。

**ARMS 自动埋点**：
`spring-ai-alibaba-autoconfigure-arms-observation` 为每次 LLM 调用自动创建 Observation（记录模型名称、输入/输出 token 数、耗时），通过 Micrometer 上报到 ARMS 可观测平台。
