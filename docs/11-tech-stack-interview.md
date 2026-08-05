# 技术栈八股文精要

> 本文档整理项目中涉及的所有技术的高频面试题及标准答案，覆盖原理、对比、使用场景。

---

## 一、Java

### Q1：Java 中 Record 和普通 Class 的区别？
**Answer**：Record 是 Java 16 引入的不可变数据载体，编译器自动生成 `final` 字段、全参构造器、`equals`/`hashCode`/`toString`、getter（无 setter）。与普通类区别：字段不可变（无 setter）、不能继承（隐式 final）、适合 DTO/值对象场景。本项目用 Record 作为 LLM 结构化输出的目标类型（`ExecutionPlan`、`EvaluationResult`），天然线程安全。

### Q2：Java 中异步编程的方式有哪些？
**Answer**：① `Thread`/`Runnable`（低级）；② `ExecutorService` + `Future`；③ `CompletableFuture`（组合异步操作）；④ `@Async`（Spring 封装，基于线程池）；⑤ 响应式编程（Reactor Flux/Mono，非阻塞）。本项目 `MemoryService.storeMemoryAsync()` 用 `@Async`（简单异步），Controller 用 Reactor `Flux`（流式推送）。

### Q3：`@PostConstruct` 的执行时机和作用？
**Answer**：在 Spring Bean 实例化 + 依赖注入完成之后、Bean 对外暴露之前执行。用于需要依赖注入字段才能完成的初始化逻辑。生命周期顺序：构造函数 → 依赖注入（`@Autowired`）→ `@PostConstruct` → Bean 就绪。本项目 `ConsultService.initRetriever()` 用此注解初始化 `DashScopeApi` 和 `ChatClient`，因为需要先注入 `apiKey` 和 `chatModel`。

---

## 二、Spring Boot

### Q1：Spring Boot 自动配置原理？
**Answer**：① `@SpringBootApplication` 包含 `@EnableAutoConfiguration`；② `@EnableAutoConfiguration` 激活 `AutoConfigurationImportSelector`；③ `AutoConfigurationImportSelector` 从 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 读取配置类列表；④ 每个配置类通过 `@ConditionalOnClass`、`@ConditionalOnMissingBean`、`@ConditionalOnProperty` 等条件注解决定是否生效；⑤ 条件满足的配置类注册对应 Bean。

### Q2：`@ConfigurationProperties` vs `@Value` 的区别？
**Answer**：
| 对比维度 | @Value | @ConfigurationProperties |
|---------|--------|--------------------------|
| 绑定方式 | 单个字段 | 整个配置前缀 |
| 类型转换 | 有限 | 丰富（支持 List、Map、Duration） |
| 松绑定 | 不支持 | 支持（kebab-case = camelCase） |
| JSR-303 验证 | 不支持 | 支持（配合 @Validated）|
| 适用场景 | 单个简单值 | 一组相关配置 |

### Q3：Spring Bean 的生命周期？
**Answer**：实例化（构造函数）→ 属性注入（`@Autowired`/`@Value`）→ Aware 接口回调（如 `BeanNameAware`）→ `BeanPostProcessor.postProcessBeforeInitialization()` → `@PostConstruct` → `InitializingBean.afterPropertiesSet()` → `@Bean(initMethod)` → `BeanPostProcessor.postProcessAfterInitialization()` → Bean 就绪 → 使用 → `@PreDestroy` → `DisposableBean.destroy()`。

### Q4：`@Conditional` 系列注解如何工作？
**Answer**：条件注解在 Bean 注册阶段（ConfigurationClassPostProcessor 处理期间）生效，通过 `Condition.matches()` 方法判断。`@ConditionalOnProperty` 检查 `Environment`（含 YAML/properties）中的属性值；`@ConditionalOnClass` 检查 classpath 是否存在目标类；`@ConditionalOnMissingBean` 检查 ApplicationContext 是否已有同类型 Bean。

### Q5：Spring 事务的传播行为有哪些？
**Answer**：7 种传播行为。最常用：`REQUIRED`（默认，有则加入，无则新建）、`REQUIRES_NEW`（总是新建，挂起已有事务）、`SUPPORTS`（有则加入，无则非事务执行）、`NOT_SUPPORTED`（非事务执行，挂起已有）、`MANDATORY`（必须在事务中，否则抛异常）、`NEVER`（不能在事务中）、`NESTED`（嵌套事务，Savepoint 实现）。

---

## 三、Spring AI / Spring AI Alibaba

### Q1：Spring AI 中 ChatClient 和 ChatModel 的区别？
**Answer**：`ChatModel` 是底层接口，直接封装 LLM API 调用，无高级功能。`ChatClient` 是高级 DSL，在 ChatModel 基础上增加：Advisor 拦截链（日志/缓存/限流）、Prompt Template 渲染、工具调用自动处理、结构化输出转换。日常业务代码应使用 `ChatClient`，框架/测试场景可直接用 `ChatModel`。

### Q2：BeanOutputConverter 的工作原理？
**Answer**：① 通过 Jackson `ObjectMapper.generateJsonSchema(targetType)` 为 Java Record/POJO 生成 JSON Schema；② `getFormat()` 方法返回格式说明字符串（类似"Respond in JSON. Schema: {...}"）注入 Prompt；③ LLM 接收到 Schema 约束后按格式输出 JSON；④ `convert(rawText)` 调用 `ObjectMapper.readValue()` 反序列化为目标类型；⑤ 内置降级：解析失败时可 catch 异常回退到原始文本。

### Q3：Spring AI Alibaba 的 StateGraph 执行流程？
**Answer**：① `StateGraph` 构建阶段：添加节点（`addNode`）、边（`addEdge`）、条件边（`addConditionalEdges`），定义状态键策略（`KeyStrategyFactory`）；② `compile(compileConfig)` 阶段：拓扑排序验证、绑定 Saver 和 interruptBefore；③ `fluxStream(input, runnableConfig)` 执行阶段：从 Saver 恢复历史状态 → 合并 input → 按拓扑顺序调用 NodeAction → 每个节点输出包装为 NodeOutput 推入 Flux → 检查 interruptBefore → 执行完或中断后保存状态到 Saver。

### Q4：ReAct 模式的原理是什么？
**Answer**：ReAct（Reasoning + Acting）是一种 Prompt 框架，让 LLM 在推理和行动之间交替循环：**Thought**（当前状态分析）→ **Action**（决定调用哪个工具）→ **Observation**（工具返回结果）→ 循环直到 **Final Answer**。与纯推理的区别：Action 步骤调用外部工具获取真实信息，避免幻觉；与纯工具调用的区别：Thought 步骤提供可解释的推理过程。Spring AI Alibaba `ReactAgent` 自动处理循环逻辑，开发者只需提供工具列表和 System Prompt。

### Q5：MemorySaver 和 RedisSaver 的选择？
**Answer**：`MemorySaver` 将状态存储在 JVM 内存（`ConcurrentHashMap`），零依赖，但进程重启后丢失，多实例间不共享，适合开发/演示环境。`RedisSaver` 将状态序列化后持久化到 Redis，跨进程共享，支持水平扩展，但需要 Redis 连接，适合生产环境。切换成本低，只需替换 `CompileConfig` 中的 Saver 实现。

---

## 四、MyBatis

### Q1：MyBatis 的工作原理？
**Answer**：① SqlSessionFactory（工厂）读取配置和 SQL 映射文件，创建 SqlSession；② SqlSession 是操作数据库的门面，包含执行器（Executor）；③ `@Mapper` 接口通过 `MapperProxy`（JDK 动态代理）生成实现，方法调用委托给 `MapperMethod`；④ `MapperMethod` 根据操作类型（SELECT/INSERT/UPDATE/DELETE）调用对应的 `Executor` 方法；⑤ `Executor` 使用 `StatementHandler` 准备 SQL，`ParameterHandler` 处理参数，`ResultSetHandler` 处理结果集映射。

### Q2：MyBatis 的一级缓存和二级缓存？
**Answer**：**一级缓存**：SqlSession 级别，默认开启，同一 Session 内相同查询直接返回缓存结果；Session 关闭或 INSERT/UPDATE/DELETE 后失效。**二级缓存**：Mapper 级别，跨 Session，需要显式开启（`@CacheNamespace` 或 XML `<cache/>`），实体类须实现 Serializable；多 Session 共享缓存，存在数据一致性问题，生产环境谨慎使用。本项目未显式启用二级缓存。

### Q3：#\{\} 和 $\{\} 的区别？
**Answer**：`#{}` 是预编译参数（PreparedStatement 的 `?` 占位符），值由 JDBC 驱动安全处理，防止 SQL 注入。`${}` 是字符串替换，直接拼接到 SQL 中，有 SQL 注入风险，仅用于动态表名/列名等无法参数化的场景（需人工校验安全性）。本项目 Mapper 全部使用 `#{}`。

---

## 五、Nacos

### Q1：Nacos 服务注册与发现的原理？
**Answer**：**注册**：客户端启动时向 Nacos Server 发送注册请求（HTTP/gRPC），携带 serviceName、IP、port、weight、metadata；Nacos 维护服务注册表，并通过心跳（默认 5s）检测服务存活，超时（默认 15s 不健康，30s 删除）移除。**发现**：客户端首次查询服务时拉取完整实例列表缓存到本地；Nacos Server 主动推送变更（UDP/gRPC 长连接）；客户端同时定期轮询（30s）保证一致性。本地缓存确保 Nacos 短暂不可用时服务调用不中断。

### Q2：Nacos 配置中心的动态刷新原理？
**Answer**：客户端通过长轮询（28.5s 超时）向 Nacos Server 请求配置变更（MD5 比对）；Server 有变更时立即返回，否则 28.5s 后超时再次请求。客户端收到变更后通知所有 `@NacosConfigListener` 回调，Spring 的 `RefreshScope` 标记的 Bean 重新初始化。

### Q3：CAP 理论中 Nacos 的取舍？
**Answer**：Nacos 支持两种模式：① **CP 模式**（服务发现使用 Raft 协议，保证一致性，分区时不可用）；② **AP 模式**（默认，服务注册使用 Distro 协议，保证可用性，分区时可能短暂不一致）。临时实例默认 AP，持久实例默认 CP，通过 `ephemeral` 参数控制。

---

## 六、Redis

### Q1：Redis 常用数据结构及使用场景？
**Answer**：
- **String**：缓存、计数器、分布式锁（`SETNX`）、会话存储（Checkpoint 序列化后存储）
- **Hash**：存储对象（如用户 Profile，字段级更新）
- **List**：消息队列（LPUSH/RPOP）、最近记录
- **Set**：去重（已处理 ID 集合）、共同关注
- **ZSet**（Sorted Set）：排行榜、延迟队列（score=时间戳）
- **Stream**：消息队列（支持消费者组，持久化）

### Q2：Redis 内存淘汰策略？
**Answer**：8 种策略：`noeviction`（不淘汰，写入报错）；`allkeys-lru/lfu`（全局 LRU/LFU）；`volatile-lru/lfu`（只淘汰设置了过期时间的 key）；`allkeys-random`（随机淘汰）；`volatile-random`（随机淘汰有 TTL 的）；`volatile-ttl`（优先淘汰 TTL 最短的）。生产环境推荐 `allkeys-lru`（缓存场景）或 `volatile-lru`（有 TTL 的缓存）。

### Q3：Redis 持久化 RDB 和 AOF 的区别？
**Answer**：**RDB**：定时全量快照（`BGSAVE`），文件体积小，恢复快，但可能丢失最近一次快照后的数据；**AOF**：记录每条写命令，`always`（每命令 fsync，零丢失，性能低）/ `everysec`（每秒 fsync，最多丢 1 秒，推荐）/ `no`（OS 控制）；AOF 文件体积大但数据更安全；**混合持久化**（Redis 4.0+）：AOF 中包含 RDB 头 + 增量 AOF，兼顾速度和安全。

---

## 七、MySQL

### Q1：InnoDB 索引原理（B+ Tree）？
**Answer**：InnoDB 使用 B+ Tree 索引，所有数据存储在叶子节点，非叶子节点只存 key（减少层高）。**聚簇索引**（主键索引）：叶子节点存储完整行数据；**辅助索引**（二级索引）：叶子节点存储主键值，查询需回表。B+ Tree 高度通常 2-4 层，16KB 页存约 1000 个索引项，千万级数据高度 ≤ 3，磁盘 IO 极少。本项目 `orders` 表在 `user_id` 和 `created_at` 上建了复合索引，避免全表扫描。

### Q2：MySQL 事务隔离级别？
**Answer**：
| 隔离级别 | 脏读 | 不可重复读 | 幻读 |
|---------|------|---------|------|
| READ UNCOMMITTED | ✓ | ✓ | ✓ |
| READ COMMITTED | ✗ | ✓ | ✓ |
| REPEATABLE READ（默认）| ✗ | ✗ | ✗（MVCC+间隙锁）|
| SERIALIZABLE | ✗ | ✗ | ✗ |

InnoDB 默认 REPEATABLE READ，通过 MVCC 解决不可重复读，通过间隙锁（Gap Lock）解决幻读。

### Q3：慢查询如何优化？
**Answer**：① `EXPLAIN` 分析执行计划（关注 type、key、rows、Extra）；② 确保 WHERE/JOIN/ORDER BY 字段有合适索引；③ 避免 `SELECT *`（减少数据传输）；④ 覆盖索引（索引列包含所有查询列，避免回表）；⑤ 避免索引失效（函数操作、类型不匹配、前缀 `%LIKE%`）；⑥ 分页优化（`WHERE id > last_id LIMIT n` 代替 `LIMIT offset, n`）；⑦ 大事务拆分。

---

## 八、响应式编程（Reactor）

### Q1：Flux 和 Mono 的区别？
**Answer**：`Mono<T>` 表示 0 或 1 个元素的异步序列；`Flux<T>` 表示 0 到 N 个元素的异步序列。本项目 Controller 返回 `Flux<ServerSentEvent<String>>` 表示流式 SSE 响应（N 个 token）。两者都是 Publisher（响应式流规范），通过 `subscribe()` 触发执行，支持背压。

### Q2：背压（Backpressure）是什么？如何处理？
**Answer**：背压是生产者速度超过消费者处理能力时的流量控制机制。Reactor 处理方式：① `onBackpressureBuffer()`：缓冲超速元素（内存换时间）；② `onBackpressureDrop()`：丢弃无法处理的元素；③ `onBackpressureLatest()`：只保留最新元素；④ `onBackpressureError()`：超速时发送错误信号。本项目使用 `Sinks.many().unicast().onBackpressureBuffer()`，LLM token 推速较快，缓冲保证不丢失。

### Q3：`doOnNext` vs `map` 的区别？
**Answer**：`map` 转换元素，有返回值，是数据流操作符。`doOnNext` 执行副作用（如日志、sink 推送），不改变元素，返回原始元素。本项目中 `doOnNext(sink::tryEmitNext)` 将元素推入 Sink，`doOnComplete(() -> sink.tryEmitComplete())` 关闭流，均为副作用操作。

---

## 九、Vue 3 / TypeScript

### Q1：Vue 3 响应式原理？
**Answer**：Vue 3 使用 ES6 Proxy 实现响应式。`reactive(obj)` 返回 Proxy，拦截 get（收集依赖）和 set（触发更新）；`ref(value)` 包装为 `{value: Proxy}`，通过 `.value` 访问触发依赖收集。每个响应式数据关联一个 `Set<ReactiveEffect>`，数据变化时遍历 Set 重新执行 effect（重新渲染或重新计算 computed）。相比 Vue 2 的 `Object.defineProperty`，Proxy 可拦截对象新属性、数组索引、`delete` 等操作。

### Q2：Pinia vs Vuex？
**Answer**：Pinia 是 Vue 3 官方推荐的状态管理库。对比 Vuex 4：① 无 Mutation，直接修改 state（简化代码）；② 完整 TypeScript 支持（Vuex 需繁琐类型声明）；③ 支持多个 Store（无 Vuex 的 Modules 嵌套问题）；④ DevTools 支持；⑤ 支持组合式 API 写法；⑥ 体积更小（~1.5KB）。本项目 `chatStore` 管理消息列表和加载状态，`configStore` 管理用户配置（baseUrl、chatId、userId）。

### Q3：TypeScript 中 `interface` 和 `type` 的区别？
**Answer**：`interface` 支持声明合并（同名 interface 自动合并），只能描述对象形状，可被 `implements`；`type` 支持联合类型/交叉类型/条件类型等复杂类型操作，不支持声明合并。本项目 `ChatRequest`、`ChatResponse` 使用 `interface` 定义 API 数据结构（清晰表达对象形状），工具类型使用 `type`。

---

## 十、Docker & 微服务

### Q1：Docker Compose 的作用？
**Answer**：Docker Compose 通过单个 YAML 文件定义和运行多容器应用。`docker-compose up -d` 按依赖顺序启动所有服务（MySQL → Redis → Nacos），各服务在同一 Docker 网络中可通过服务名互相访问，`docker-compose down` 一键停止并清理。本项目 `docker/middleware/docker-compose.yml` 管理 MySQL、Redis、Nacos 三个中间件。

### Q2：微服务架构的优缺点？
**Answer**：**优点**：独立部署/扩展、技术栈自由、故障隔离、团队并行开发。**缺点**：服务间通信开销（网络延迟）、分布式事务复杂（CAP 定理）、运维复杂度高（需服务发现/负载均衡/监控）、测试难度大（需集成多服务）。本项目采用微服务架构，8 个独立 Spring Boot 应用，Nacos 提供服务发现，A2A 协议处理 Agent 间通信。

---

## 十一、设计模式

### Q1：项目中用到了哪些设计模式？
**Answer**：
- **Builder 模式**：`LlmRoutingAgent.builder()`、`ReactAgent.builder()`、`CompileConfig.builder()` — 复杂对象构建
- **工厂方法**：`KeyStrategyFactory`（函数式接口）— 创建状态键策略
- **门面模式**：`ChatClient` 封装 `ChatModel` — 简化 LLM 调用
- **策略模式**：`ReplaceStrategy`（状态合并策略）— 算法族可替换
- **模板方法**：`NodeAction.apply()` — 节点行为模板
- **代理模式**：`MapperProxy`（MyBatis）、`@Transactional`（Spring AOP 动态代理）
- **观察者模式**：Reactor `Flux`（发布-订阅）— 响应式流
- **责任链模式**：`ChatClientAdvisor` 链 — 请求拦截处理
- **单例模式**：Spring Bean 默认 Singleton 作用域

### Q2：Spring AOP 的原理？
**Answer**：AOP（面向切面编程）基于动态代理实现。目标类实现接口时使用 JDK 动态代理（`java.lang.reflect.Proxy`）；不实现接口时使用 CGLIB 字节码增强（生成目标类的子类）。Spring 在 Bean 初始化后的 `BeanPostProcessor` 阶段（`AnnotationAwareAspectJAutoProxyCreator`）判断是否需要创建代理，并将 Advice（增强逻辑）织入代理类。`@Transactional` 即通过 AOP 在方法调用前开启事务、结束后提交/回滚。
