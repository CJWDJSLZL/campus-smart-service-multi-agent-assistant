# 文档声明精确验证测试方案

> 目标：对 `docs/06-engineering-value.md`（六大工程能力文档）中的全部量化声明进行可复现的精确验证，区分「结构性事实」（代码可断言）与「效果性声明」（需实测），输出实测数据并据实修正文档。

## 1. 现状盘点（方案设计的出发点）

| 项 | 现状 | 影响 |
|---|---|---|
| 测试依赖 | 全部模块 pom.xml 无 `junit`/`mockito`/`spring-boot-starter-test` | 需先补依赖 |
| 测试代码 | 全仓库无 `*Test.java` | 从零搭建 |
| Golden Set | 文件存在（30 条，3 类各 10 条），**但无任何 Java 代码读取/运行它** | 「Golden Set 量化三维准确率」声明当前不可执行，需先实现 Runner |
| 外部依赖 | DashScope（LLM/RAG/Rerank）、Mem0、MySQL、MCP Server、钉钉 webhook | L1/L2 验证需依赖或 Mock 这些服务 |
| 可调用入口 | `GET /api/assistant/chat`（SSE）、`/api/consult_sub_agent/debug` | L2 端到端入口可用 |

## 2. 待验证声明清单（追溯矩阵）

| 编号 | 声明（出处） | 类型 | 验证方式 |
|---|---|---|---|
| R-1 | 查询改写提升语义召回率（06:34-37） | 效果 | L2 A/B 召回对比 |
| R-2 | Rerank 精排相关性提升 20-30%（06:39-42） | 效果 | L2 nDCG@3 A/B |
| R-3 | 混合检索精确名词 100% 命中（06:44-47） | 效果 | L2 全量名称命中率 |
| P-1 | 工具映射表准确率 ~60%→~90%+（06:89-93） | 效果 | L2 映射表 A/B |
| P-2 | Schema 注入结构化输出成功率 ~95%（06:95-108） | 效果 | L1 批量解析成功率 |
| C-1 | 滑动窗口压缩减少 60-70% token（06:142-146） | 效果 | L1 token 统计 |
| C-2 | Memory 注入个性化上下文（06:133-140） | 效果 | L2 注入 A/B |
| C-3 | `<userId>` 标签跨 Agent 传递（06:152-156） | 静态+集成 | L0 代码断言 + L1 单测 |
| C-4 | Checkpoint 跨请求连续性（06:127-131） | 静态+集成 | L0 配置断言 + L1 图测试 |
| H-1 | 30 条 Golden Set 量化路由/工具/RAG 三维准确率（06:177-181） | 静态+效果 | L0 格式断言 + **先实现 Runner** |
| H-2 | BeanOutputConverter 保证结果可程序解析（06:190） | 静态 | L0 断言 + L1 覆盖 P-2 |
| H-3 | 满意度 < 3.0 自动告警（06:194-196） | 静态+集成 | L0 阈值断言 + L1 分支测试 |
| H-4 | 问题响应时间压缩到分钟级（06:196） | 效果 | L2 端到端计时 |
| L-1 | Retry 指数退避让瞬时故障不可见（06:235-239） | 静态+集成 | L0 参数断言 + L1 故障注入 |
| L-2 | 定时 Loop 替代人工触发（06:217-221） | 静态 | L0 cron 断言 |
| L-3 | HITL 授权不可逆操作（06:241-245） | 静态 | L0 配置断言 |
| L-4 | 多轮澄清（06:247-251） | 集成 | L1 图状态机测试 |
| L-5 | 批量迭代 N 条记录（06:223-227） | 静态+集成 | L0 配置断言 + L1 图测试 |
| L-6 | 条件分支自适应（06:229-233） | 静态+集成 | L0 断言 + L1 分支测试 |
| M-1 | 记忆写入异步无感（06:274-284） | 静态+集成 | L0 `@Async` 断言 + L1 返回时序 |
| M-2 | 检索限制两周窗口（06:288-298） | 静态+集成 | L0 请求体断言 + L1 时序用例 |
| M-3 | SystemMessage 注入高权重（06:300-308） | 静态+集成 | L0 断言 + L2 A/B |

> **验证原则**：对「估算值」声明（R-2 的 20-30%、P-1 的 60%/90%、P-2 的 95%、C-1 的 60-70%），验证目标是**产出实测值并修正文档**，而非证明数字恰好成立。

## 3. 验证级别定义

- **L0 静态代码验证**：读代码/配置断言常量与结构，无外部依赖，一条命令可跑。
- **L1 单元/集成验证**：JUnit + Mockito，Mock 外部服务（DashScope/Mem0/MySQL/钉钉），验证行为逻辑。
- **L2 在线端到端验证**：真实外部服务 + 真实数据，A/B 实验，产出指标。

## 4. 前置准备

### 4.1 补充测试基础设施
在根 `pom.xml`（或各模块）添加：
```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-test</artifactId>
  <scope>test</scope>
</dependency>
```
（含 JUnit5 / Mockito / AssertJ / Spring Test）

### 4.2 测试数据准备清单
| 数据集 | 内容 | 用途 |
|---|---|---|
| `queries_rewrite.txt` | 20 条查询：缩写（"图书馆"）、口语（"帮我约个地方自习"）、精确服务名（"图书馆研讨间预约"）、模糊（"预约一下"） | R-1 |
| `relevance_labels.json` | 30 条 query→候选 doc 相关性人工标注（相关/部分/不相关） | R-2 |
| `golden_set/*.txt` | 现有 30 条，直接复用 | P-1、H-1 |
| `feedback_samples.txt` | 30 条真实反馈记录（投诉/满意/中性混合） | P-2 |
| `history_25msgs.txt` | 25 条模拟多轮对话历史（含记录编号、时间偏好等关键信息） | C-1 |
| `memory_out_of_window.txt` | 3 条 20 天前写入的记忆 | M-2 |

## 5. 详细测试用例

### 5.1 L0 静态验证（V-01 ~ V-07）

**V-01 Golden Set 数量与格式**（H-1 前置）
- 断言：3 个文件各恰好 10 条 `--- CASE_xxx ---`；每条含 `input/user_id/expected_agent/expected_tools/expected_output_keywords` 字段。
- 失败即文档「30 条」声明不成立。

**V-02 Retry 参数**（L-1）
- 断言 `ExecutorNode.MAX_RETRY == 3`，退避公式 `delay = 500L * retry`（重试延迟 500/1000/1500ms）。
- 实现方式：反射读取私有常量，或将该常量提取为包内可见后断言。

**V-03 满意度阈值**（H-3）
- 断言 `EvaluationAgentConfiguration` 条件分支表达式为 `avg < 3.0 ? "alert" : "normal"`。
- 建议：将阈值提为 `@Value("${agent.satisfaction.alert-threshold:3.0}")` 常量以支持测试注入。

**V-04 两周窗口**（M-2）
- 断言 `MemoryService.searchMemory` 构造的请求体中 `filters.created_at.gte` = 当前日期 - 14 天，`lte` = 明天。
- 实现方式：Mock `RestTemplate.postForEntity`，捕获请求体断言。

**V-05 滑动窗口参数**（C-1）
- 断言 `ContextCompressionNode` 默认 `maxMessages=20`、`keepRecent=6`，压缩为 1 条 SystemMessage + 最近 6 条。

**V-06 结构性声明集合**（C-3/C-4/H-2/L-2/L-3/L-5/M-1/M-3）
- C-3：`MemoryInjectNode.extractUserId` 解析 `<userId>` 标签逻辑。
- C-4：三个子 Agent config 使用 `MemorySaver` + `threadId`。
- H-2：`EvaluationClassifierNode` 持有 `BeanOutputConverter<EvaluationResult>`。
- L-2：`LocalScheduledTrigger` 存在 cron `0 0 9 * * ?` 与 `0 0 10 ? * MON`。
- L-3：`planAndExecuteHitlGraph` 使用 `interruptBefore("executor")`。
- L-5：`IterationNode.converter()` 的 input/output array key 配置。
- M-1：`MemoryService.storeMemoryAsync` 标注 `@Async("memoryTaskExecutor")`。
- M-3：`MemoryInjectNode` 以 `SystemMessage` 注入 messages 首部。

**V-07 配置一致性**（R-2 附带）
- 断言 `application.yml` 的 `rerank-top-n` 默认值与 `start-backend.ps1`/`env.template` 的 `DASHSCOPE_RERANK_TOP_N` 一致。
- **预期发现不一致**：app.yml 默认 3，脚本默认 2。文档引用 3，需统一或修正。

### 5.2 L1 单元/集成验证（V-08 ~ V-14）

**V-08 滑动窗口 token 节省量**（C-1）
- Given：25 条消息（含关键信息）。
- When：mock `ChatClient` 返回 3 句摘要，执行 `ContextCompressionNode.apply`。
- Then：断言压缩后 messages 数 = 7；统计压缩前后 token 数（用 `tiktoken` 或 `Character` 计数），记录实际减少比例。
- 判定：输出实测值，接近 60-70% 则声明成立，否则以实测修正。

**V-09 Schema 注入结构化输出成功率**（P-2）
- Given：30 条 `feedback_samples.txt`。
- When：逐条执行 `EvaluationClassifierNode`（mock ChatModel 返回合规/不合规 JSON 的混合注入，或真实调用）。
- Then：统计 `BeanOutputConverter` 解析成功比例与降级（原始文本）次数。
- 判定：成功率 ≥ 95%（含降级兜底不计为失败，需在报告中区分「原始解析成功率」与「降级后成功率」）。

**V-10 Retry 故障注入**（L-1）
- Given：mock `ToolCallback.call()` 前 2 次抛 `RuntimeException`，第 3 次成功。
- When：`ExecutorNode.executeWithRetry`。
- Then：断言总调用次数 = 3、返回成功、两次失败间隔 ≥ 500ms 与 1000ms（用 `System.nanoTime` 计时）。
- 边界用例：3 次全失败 → 返回失败信息，不抛异常。

**V-11 两周窗口时序**（M-2）
- Given：mock `RestTemplate` 按 `created_at` 过滤逻辑。
- When：构造「今天写入」「10 天前写入」「20 天前写入」三条记忆。
- Then：断言仅前两条被返回。
- 说明：此用例验证的是 `MemoryService` 请求参数的正确性；Mem0 服务端是否严格遵守过滤由 L2 补充。

**V-12 记忆异步写入不阻塞**（M-1）
- Given：mock `RestTemplate` 的 `postForEntity` 延迟 2 秒。
- When：调用 `storeMemory`。
- Then：断言方法在 <500ms 内返回「成功存储用户喜好」，且 2 秒后异步日志记录完成。

**V-13 满意度分支选择**（H-3/L-6）
- Given：`analysis_results` 构造平均满意度 2.8 / 3.0 / 3.2 三组。
- When：执行条件分支函数（或直接调用 `evaluationAnalysisAgent`，mock `DingMessageSenderNode` 捕获输出）。
- Then：断言 2.8 → alert 分支、3.0/3.2 → normal 分支。

**V-14 多轮澄清状态机**（L-4）
- Given：input「帮我预约」（缺服务名）。
- When：执行 `planAndExecuteHitlGraph`（mock 工具层）。
- Then：断言 PlannerNode 输出 `needsClarification=true` 与澄清问题，Controller 提前终止且不调用任何工具。

### 5.3 L2 在线端到端验证（V-15 ~ V-20）

> 需配置真实 DashScope Key、Mem0 Token、MySQL。每次实验记录模型版本与参数，保证可复现。

**V-15 查询改写召回率 A/B**（R-1）
- Given：`queries_rewrite.txt` 20 条。
- When：对每条分别走「改写+检索」与「跳过改写直接检索」两路（在 `ConsultService.searchKnowledge` 增加开关参数或临时 debug 端点），记录召回文档及人工判定相关性。
- Then：统计 Top-3 含相关文档的比例。
- 判定：改写路召回率 > 原始路，即声明「提升语义召回率」成立；输出提升幅度实测值。

**V-16 Rerank 相关性提升**（R-2）
- Given：`relevance_labels.json` 30 条标注。
- When：`enable-reranking=true` vs `false` 两轮检索（`rerank-top-n` 固定同一值），计算 nDCG@3、MRR。
- Then：对比两轮指标。
- 判定：声明目标为「提升 20-30%」，验证目标是确认「有提升」并记录实测幅度；若无提升，文档需修正。

**V-17 混合检索精确名词命中率**（R-3）
- Given：products 表全部服务名称 + 常见口语变体各一条查询。
- When：`searchKnowledge` 精确匹配路径。
- Then：统计命中率。
- 判定：100% 命中才成立。注意范围限定：查询词需与名称完全一致或为 LIKE 可命中形式；特殊字符（如空格、括号）需单独记录。

**V-18 工具映射表 A/B**（P-1）
- Given：order Golden Set 10 条。
- When：分别用「含意图→工具映射表」与「去掉映射表仅留工具描述」两版 Prompt 跑 order-agent，对比实际工具调用序列与 `expected_tools`。
- Then：统计准确率。
- 判定：映射表版 ≥ 90% 且高于无映射表版（约 60%），声明成立；否则记录实测修正。

**V-19 满意度告警端到端**（H-3/H-4）
- Given：向 feedback 表注入 10 条低满意度记录（平均 < 3.0）。
- When：触发 `evaluationAnalysisAgent`（手动 invoke 或等待定时任务）。
- Then：断言走告警分支、钉钉收到「🚨 紧急告警」格式消息；计时「触发→送达」。
- 判定：分支正确；响应时间记录实测值，与「分钟级」声明对比（注意：声明指人工 review→自动监控的对比，非纯链路耗时，报告需说明口径）。

**V-20 Memory 注入 A/B**（C-2/M-3）
- Given：用户 10001 在 Mem0 中存在「偏好晚上预约图书馆」记忆。
- When：同一问题分别带/不带 `MemoryInjectNode` 运行。
- Then：断言带注入的回答包含偏好体现（如主动建议晚上时段），不带注入则不含。
- 判定：差异存在即声明成立；同时记录 SystemMessage 与 UserMessage 两种注入形式的回答差异作为参考。

## 6. 前置依赖实现：GoldenSetRunner（H-1 的必要条件）

现状：Golden Set 文件存在但无执行器，「量化三维准确率」无法运行。需新增：

```
supervisor-agent/src/test/java/.../GoldenSetRunner.java（或 main 下 debug 工具）
逻辑：
  1. 解析 golden_set/*.txt（复用字段：expected_agent / expected_tools / expected_output_keywords）
  2. 逐条调用 SupervisorAgentController 的 chat 流程（或 compiledGraph.invoke）
  3. 断言：实际路由 Agent == expected_agent        → 路由准确率
          实际工具调用序列 == expected_tools       → 工具准确率
          回答包含 expected_output_keywords        → RAG 召回/回答质量
  4. 输出三个维度的准确率报告（JSON/CSV）
```

产出指标即文档「30 条 Golden Set 量化路由/工具/RAG 三维准确率」的直接数据源。

## 7. 执行顺序与里程碑

| 阶段 | 内容 | 依赖 | 预计耗时排序 |
|---|---|---|---|
| P0 | 补充测试依赖 + L0 静态用例（V-01~V-07） | 无 | 最快，立即执行 |
| P1 | 实现 GoldenSetRunner | P0 | 高优先（填补缺口） |
| P2 | L1 集成用例（V-08~V-14） | P0 | mock 依赖 |
| P3 | L2 在线用例（V-15~V-20） | P1+P2，需外部 Key | 最后 |

## 8. 判定标准汇总

| 声明 | 通过标准 | 不通过时的动作 |
|---|---|---|
| R-1 | 改写后召回率 > 原始 | 报告实测，修正文档表述 |
| R-2 | 开启 Rerank 后 nDCG@3 有提升 | 记录实测幅度，将「20-30%」改为实测值 |
| R-3 | 精确名称查询命中率 = 100% | 列出未命中名称，修正为「≥ xx%」 |
| P-1 | 映射表版 ≥90% 且高于对照版 | 修正百分比 |
| P-2 | 结构化解析成功率 ≈95% | 修正百分比，注明降级兜底影响 |
| C-1 | token 节省量在 60-70% 区间 | 修正区间 |
| C-2/C-3/C-4 | 行为差异/标签解析/状态恢复成立 | 记录差异 |
| H-1 | Runner 产出三维准确率 | 若数据不达标，文档降级为「待优化」 |
| H-3/H-4 | 分支正确；响应时间如实记录 | 修正「分钟级」口径 |
| L-1 | 3 次内自愈且不抛异常 | 修复 Retry 逻辑 |
| M-1/M-2 | 异步不阻塞；窗口过滤正确 | 修复实现 |

## 9. 风险与注意事项

1. **估算值声明不应以「恰好成立」为验证目标**：文档中的 20-30%、60-70% 等是设计时估算，验证目的是产生实测数据并回写文档。
2. **外部服务不确定性**：L2 结果受模型版本、知识库内容、Mem0 行为影响，报告必须记录版本与时间。
3. **rerank-top-n 默认值不一致**（app.yml=3 vs 脚本=2）需先统一，否则 L2 结果不可比。
4. **LLM 随机性**：P-1/P-2/R-1 等涉及 LLM 的用例建议每组 ≥3 次运行取均值，或固定 `temperature=0`。
5. **Golden Set 依赖真实数据**：order/consult 场景依赖 MySQL 中存在对应服务记录与知识库文档，需先初始化数据。
