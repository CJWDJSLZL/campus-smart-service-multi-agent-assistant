# 六大工程能力文档声明精确验证报告

> 对应测试方案：`docs/07-verification-test-plan.md`
> 验证对象：`docs/06-engineering-value.md`（六大工程能力的作用与原理说明）
> 验证时间：2026-08-04

## 1. 执行摘要

| 结论 | 说明 |
|---|---|
| **L0 静态验证** | 20 项检查，**17 项通过，2 项失败**（均为真实缺陷，非脚本问题） |
| **L1 单元/集成验证** | 4 个测试类 **12/12 全部通过**（需先修复 4 处构建阻断缺陷） |
| **L2 在线验证** | **未执行**——环境缺少 MEM0 Key、钉钉 Token，Nacos 不可达 |
| **构建状态** | 修复后 9 个模块**全部编译通过**；修复前仅 common 可编译 |
| **核心发现** | 文档大部分声明可追溯；**百分比数字为估算值**；Golden Set 无执行器；项目原有 4 类构建缺陷 |

**最重要发现**：验证前项目**无法构建**——`spring-ai-core` 依赖在锁定的 GA 版本中不存在、3 处源码中文引号损坏、4 处 graph-core API 不兼容。本报告第 4 节记录了为解锁测试所做的 6 处最小修复。

## 2. 测试环境

| 项 | 值 |
|---|---|
| JDK | OpenJDK 17.0.19（TencentKona） |
| Maven | 3.8.6（需配置 aliyun 镜像，Maven Central 被墙返回 403） |
| 关键依赖 | spring-ai-alibaba 1.0.0.4 / spring-ai 1.0.0 / spring-boot 3.2.0 |
| 外部服务 | DashScope Key：已配置；Mem0 Key：**为空**；钉钉 Token：**为空**；MySQL:3306 可达；Nacos:8848 **不可达** |

## 3. 测试执行总览

| 级别 | 计划用例 | 执行 | 结果 |
|---|---|---|---|
| L0 静态 | V-01~V-07（20 项检查） | ✅ 全部执行 | 17 PASS / 2 FAIL |
| L1 Mock 集成 | V-08~V-14（4 个测试类 12 用例） | ✅ 可执行项全部执行 | 12/12 PASS |
| L2 在线 | V-15~V-20 | ⛔ 未执行 | 环境阻断（见第 7 节） |

## 4. 测试使能变更（构建修复）

验证前项目存在 4 类构建阻断缺陷。为保证测试可运行，做了以下**最小修复**（仅适配 API 调用方式，不改业务逻辑）：

| 文件 | 缺陷 | 修复 |
|---|---|---|
| `common/pom.xml` | `spring-ai-core` 在 GA 版本不存在（仅发布到 1.0.0-M6） | 改为 GA 管理的 `spring-ai-model` + `spring-ai-client-chat` |
| `pom.xml` | 无测试依赖 | 增加 `spring-boot-starter-test`（test scope） |
| `order-sub-agent/.../OrderAgent.java:156` | `ReactAgent.execute(Map)` 在 1.0.0.4 已移除 | 改用 `getCompiledGraph().invoke(map).map(OverAllState::data).orElse(Map.of())` |
| `order-sub-agent/.../OrderAgent.java:273` | `interruptBefore(List)` 签名不符 | 改用 `interruptBefore("executor")` |
| `feedback-sub-agent/.../FeedbackAgent.java:130` | 同上 `execute(Map)` | 同上 |
| `consult-sub-agent/.../ConsultAgent.java:159,194` | 同上 `execute(Map)` ×2 | 同上 |
| `order-mcp-server/.../OrderMcpTools.java:175,198-201` | 字符串字面量中混入中文弯引号 `“”` 与内嵌 ASCII 引号，语法错误 | 定界符还原为 ASCII `"`，内嵌引号还原为中文弯引号 |
| `feedback-mcp-server/.../FeedbackMcpTools.java:77` | 同上内嵌 ASCII 引号 | 同上 |
| `consult-sub-agent/.../ConsultService.java:92-94` | 查询改写 Prompt 字符串内嵌 ASCII 引号 | 内嵌引号改为中文弯引号 |
| `supervisor-agent/.../EvaluationAgentConfiguration.java:236-258` | 条件分支 lambda 需返回 `CompletableFuture<String>` | 返回值包 `CompletableFuture.completedFuture(...)` |

> 说明：以上修复属于「使项目可构建/可测试」的必要工程，已在上游基线基础上保持最小改动。完整 diff 见 git 记录。

## 5. L0 静态验证结果（脚本：`scripts/verify-static.sh`）

**17 PASS / 2 FAIL**

| 编号 | 声明 | 结果 | 证据 |
|---|---|---|---|
| V-01 | Golden Set 共 30 条（三类各 10） | ✅ PASS | `consult/order/feedback_golden.txt` = 10/10/10 |
| V-01b | 每条用例含 5 个核心字段 | ❌ **FAIL** | `consult_golden.txt` 用例**无 `user_id` 字段**（仅 4 字段）——与 docs/05 格式说明不符 |
| V-02a/b | Retry 次数=3、退避 500ms×retry | ✅ PASS | `ExecutorNode.java:46,94` |
| V-03 | 满意度 <3.0 走告警分支 | ✅ PASS | `EvaluationAgentConfiguration.java:252` |
| V-04a/b | 两周窗口、created_at gte/lte | ✅ PASS | `MemoryService.java:59,77-82` |
| V-05a/b | 滑动窗口 >20 触发、保留 6 条 | ✅ PASS | `ContextCompressionNode.java:53-54` |
| V-06a | `<userId>` 标签解析 | ✅ PASS | `MemoryInjectNode.java:96` |
| V-06b/c | MemorySaver Checkpoint + threadId | ✅ PASS | `OrderAgent.java:93` / `OrderAgentDebugController.java:79` |
| V-06d | BeanOutputConverter<EvaluationResult> | ✅ PASS | `EvaluationClassifierNode.java:79` |
| V-06e | 定时 cron（09:00 / 周一 10:00） | ✅ PASS | `LocalScheduledTrigger.java:64,83` |
| V-06f | HITL interruptBefore(executor) | ✅ PASS | `OrderAgent.java:273` |
| V-06g | IterationNode 批量迭代 | ✅ PASS | `EvaluationAgentConfiguration.java:201` |
| V-06h | 记忆写入 @Async | ✅ PASS | `MemoryService.java:145` |
| V-06i | SystemMessage 注入 | ✅ PASS | `MemoryInjectNode.java:71` |
| V-07 | rerank-top-n 三处配置一致 | ❌ **FAIL** | `application.yml`=3、`.env`=3，但 `start-backend.ps1`/`env.template`=**2** |

## 6. L1 单元/集成验证结果

**12/12 通过**。新增 4 个测试类、共 12 个用例。

| 测试类 | 对应声明 | 用例数 | 结果 | 关键实测 |
|---|---|---|---|---|
| `ContextCompressionNodeTest`（common） | C-1 滑动窗口压缩 | 3 | ✅ 3/3 | **token(字符)节省 72.0%**（原始 1076→压缩后 301；28 条消息→7 条） |
| `ExecutorNodeRetryTest`（order-sub-agent） | L-1 Retry 指数退避 | 3 | ✅ 3/3 | **自愈耗时 1613ms，调用 3 次**（退避 500+1000ms）；失败第 4 次返回失败信息不抛异常 |
| `EvaluationClassifierNodeTest`（supervisor-agent） | P-2/H-2 Schema 注入 | 2 | ✅ 2/2 | 合法 JSON 解析为结构化字段；非法输出降级为原始文本不中断流程 |
| `MemoryServiceTest`（memory-mcp-server） | M-2 两周窗口 / M-1 异步写入 | 4 | ✅ 4/4 | 请求体 `created_at` gte=今天-14 天、lte=明天；`storeMemory` 同步路径 <1s 返回且不触发外部 HTTP |

## 7. L2 在线验证状态（未执行）

| 声明 | 计划验证 | 阻断原因 |
|---|---|---|
| R-1 查询改写召回率 | V-15 A/B | consult-sub-agent 需 Nacos（8848 不可达）与知识库索引 |
| R-2 Rerank 相关性提升 | V-16 nDCG@3 | 同上 + 需人工标注集 |
| R-3 混合检索 100% 命中 | V-17 全量查询 | 同上 + MySQL schema/数据未确认 |
| P-1 工具映射表 60%→90% | V-18 双 Prompt A/B | 需起 order-agent 全链路 |
| H-3/H-4 告警分支与响应时间 | V-19 注入低满意度数据 | 钉钉 Token 为空 |
| C-2/M-3 Memory 注入 A/B | V-20 注入对比 | **MEM0_API_KEY 为空** |

> 相关测试用例与验收标准已写入 `docs/07-verification-test-plan.md` 第 5.3 节，可在具备 staging 环境后直接执行。

## 8. 声明逐条判定

| 编号 | 声明 | 判定 | 依据 |
|---|---|---|---|
| R-1 | 查询改写提升语义召回率 | ⚠️ 代码路径存在，效果未实测 | `ConsultService.rewriteQuery()` 存在；L2 阻断 |
| R-2 | Rerank 提升相关性 20-30% | ⚠️ **估算值**，未实测 | 参数存在（rerank-top-n），幅度无基准数据 |
| R-3 | 混合检索精确名词 100% 命中 | ⚠️ 逻辑存在，未实测 | products LIKE 精确匹配 + 向量合并已实现 |
| P-1 | 工具映射表 ~60%→~90%+ | ⚠️ **估算值**，未实测 | 映射表写入 Prompt 已确认 |
| P-2 | Schema 注入输出成功率 ~95% | ⚠️ 结构化解析与降级机制已验证（2/2），**95% 幅度未实测** | 需真实 LLM 批量运行 |
| C-1 | 滑动窗口省 60-70% token | ✅ **实测 72.0%** | 略超声明区间，建议修正 |
| C-2 | Memory 注入个性化上下文 | ⚠️ 注入机制已验证（SystemMessage 前置），效果未实测 | L2 阻断 |
| C-3 | `<userId>` 标签跨 Agent 传递 | ✅ 验证 | 解析逻辑 + 注入均已确认 |
| C-4 | Checkpoint 跨请求连续性 | ✅ 验证 | MemorySaver + threadId 配置确认 |
| H-1 | 30 条 Golden Set 量化三维准确率 | ❌ **不成立（当前）** | 文件存在但**无任何执行器**，无法产出准确率 |
| H-2 | BeanOutputConverter 保证可解析 | ✅ 验证 | 合法/非法两条路径 2/2 |
| H-3 | 满意度 <3.0 自动告警 | ✅ 验证 | 阈值断言 + 分支逻辑确认 |
| H-4 | 响应时间压缩到分钟级 | ⚠️ 设计意图成立，未实测 | 需端到端计时 |
| L-1 | Retry 让瞬时故障不可见 | ✅ **实测验证** | 前 2 次失败第 3 次成功，用户侧拿成功结果 |
| L-2 | 定时 Loop 替代人工触发 | ✅ 验证 | cron 确认 |
| L-3 | HITL 授权不可逆操作 | ✅ 验证 | interruptBefore 确认 |
| L-4 | 多轮澄清 | ⚠️ 逻辑存在，未单测 | PlannerNode 代码确认 |
| L-5 | 批量迭代 N 条 | ✅ 验证 | IterationNode 配置确认 |
| L-6 | 条件分支自适应 | ✅ 验证 | 分支逻辑 + CompletableFuture 修复后编译通过 |
| M-1 | 记忆写入异步无感 | ✅ 验证 | @Async 确认 + 同步返回测试通过 |
| M-2 | 两周窗口 | ✅ 验证 | 请求体断言通过 |
| M-3 | SystemMessage 注入高权重 | ✅ 验证（机制） | SystemMessage 注入确认；权重差异未实测 |

## 9. 文档修正建议

| 位置 | 现状 | 建议 |
|---|---|---|
| docs/06 §3 滑动窗口 | "减少 ~60-70% 早期消息 token 消耗" | 改为实测 **~72%**（基于 14 轮对话样本），或标注为样本相关 |
| docs/05 §3.1 单条用例格式 | 格式示例含 `user_id` 字段 | 补充说明：consult 场景用例无 `user_id`（仅 4 字段），order/feedback 含 5 字段 |
| docs/06 §1 / start-backend.ps1 / env.template | `rerank-top-n` 默认值不一致（app.yml/.env=3，脚本=2） | 统一为同一值 |
| docs/06 多处 | "20-30%""~60%→~90%+""~95%" 等 | 标注为**估算值**，注明需基准测试验证 |
| docs/06 §5 或 HARNESS 计划 | "Golden Set 量化三维准确率" | 如实说明：当前仅有数据文件、**无执行器**，该能力尚未落地 |
| `ExecutorNode.java:41` javadoc | "延迟公式：500ms × (retryCount + 1)" | 与实际 `500ms × retry`（500/1000/1500）不符，修正注释 |

## 10. 遗留风险

1. **P-1 / P-2 / R-2 的百分比仍是估算**：需 staging 环境 + 真实 LLM 批量运行后才能给出实测值。
2. **GoldenSetRunner 缺失**（H-1）：需按测试方案 §6 实现后，Golden Set 才能实际产出路由/工具/RAG 三维准确率。
3. **L2 全部用例待 staging 环境**：缺 MEM0 Key 与钉钉 Token，Nacos 未部署。
4. **构建修复未经过上游评审**：4 处 API 适配与 3 处引号修复属工程必要改动，建议评审后合入。

## 11. 复现方法

```bash
# 1. 配置 aliyun 镜像（Maven Central 在本环境不可达）
cat > ~/.m2/settings.xml <<'EOF'
<settings><mirrors><mirror><id>aliyun-public</id><mirrorOf>*</mirrorOf>
<url>https://maven.aliyun.com/repository/public</url></mirror></mirrors></settings>
EOF

# 2. L0 静态验证（20 项，无需编译）
bash scripts/verify-static.sh

# 3. L1 测试（需先应用第 4 节的构建修复）
mvn test -pl common,order-sub-agent,supervisor-agent,memory-mcp-server -am

# 4. 全量构建
mvn -B test
```
