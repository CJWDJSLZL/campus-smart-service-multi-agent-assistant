# 代码评审汇总：构建修复与新增能力（2026-08-05）

> 本文件供上游评审使用，汇总自 `c7ca9aa` 以来（16 个提交）为「文档声明精确验证」项目所做的全部代码改动。
> 评审对象集中在两类：**构建修复**（使项目可编译）与**新增能力**（增量压缩节点、GoldenSetRunner）。

## 1. 提交清单（`c7ca9aa..HEAD`，16 个提交）

| 提交 | 类型 | 内容 |
|---|---|---|
| `9ffa594` | fix | 修复多模块构建阻断并适配 graph-core 1.0.0.4 API |
| `22d5a76` | test | L0 静态验证脚本 + L1 单元测试 |
| `260f36a` | docs | 验证测试方案与精确验证报告 |
| `db453e4` | test | RAG 离线评测脚本与压缩保真度用例 |
| `3e0e3a2` | docs | 报告补充 RAG 离线评测与 Rerank 开关发现 |
| `105323e` | docs | 按报告修正文档声明、注释与 rerank-top-n 配置 |
| `36804dd` | test | 静态验证脚本对齐修正后的字段契约 |
| `fbe2135` | feat | **新增增量滚动摘要与工具结果压缩节点** |
| `ccc3a4e` | test | 增量压缩行为测试与前后对比实验 |
| `3ff072a` | docs | 报告补充增量压缩实现与前后对比结果 |
| `4dab735` | test | RAG 生成质量评测 + 真实 LLM 压缩对比脚本 |
| `1e37e7f` | feat | **新增 GoldenSetRunner 结构校验执行器** |
| `cb22656` | docs | 报告补充生成质量实测、真实 LLM 压缩对比与 Golden Set 校验 |
| `ea3296b` | feat | **增量摘要改为差异追加模式（输出 token 优化）** |
| `37e4fb5` | test | Golden Set 运行时三维准确率评测 + 差异模式对比脚本 |
| `e6ec6a9` | docs | 报告补充差异优化结果与运行时三维准确率 |

## 2. 评审对象一：构建修复（`9ffa594`）

### 2.1 背景
验证前项目**无法构建**（`mvn compile` 直接失败），需先修复才可运行测试。共 6 类问题：

| 文件 | 缺陷 | 修复 |
|---|---|---|
| `pom.xml` | 无测试依赖 | 增加 `spring-boot-starter-test`（test scope） |
| `common/pom.xml` | `spring-ai-core` 在锁定的 GA 版不存在（仅发布到 1.0.0-M6） | 改用 GA 管理的 `spring-ai-model` + `spring-ai-client-chat` |
| `OrderAgent/FeedbackAgent/ConsultAgent.java` | `ReactAgent.execute(Map)` 在 graph-core 1.0.0.4 已移除（4 处） | 改用 `getCompiledGraph().invoke(map).map(OverAllState::data).orElse(Map.of())` |
| `OrderAgent.java:273` | `interruptBefore(List)` 签名不符 | 改用 `interruptBefore("executor")`（varargs） |
| `OrderMcpTools/FeedbackMcpTools/ConsultService.java` | 3 处源码字符串字面量混入中文弯引号/内嵌 ASCII 引号导致语法错误 | 定界符还原 ASCII，内嵌引号还原中文弯引号 |
| `EvaluationAgentConfiguration.java` | 条件分支 lambda 需返回 `CompletableFuture<String>` | 返回值包 `CompletableFuture.completedFuture(...)` |

### 2.2 评审要点
- ✅ 均为**最小 API 适配**，未改业务逻辑（git diff 均为小改动）
- ⚠️ `ReactAgent.execute → invoke` 返回值从 `Map` 变为 `Optional<OverAllState>`，`.map(data)` 语义等价，但**未做真实运行时回归**（需 Agent 环境）
- ⚠️ `spring-ai-core → spring-ai-model/client-chat` 属版本迁移，需确认是否与 spring-ai-alibaba-graph-core 1.0.0.4 的依赖完全对齐（当前全模块编译通过）

## 3. 评审对象二：新增能力

### 3.1 增量滚动摘要 + 工具结果压缩（`fbe2135` + `ea3296b`）
**文件**：`common/.../node/IncrementalContextCompressionNode.java`；接线于 `ConsultAgent`（debug `?mode=incremental-compress`）

- **① 增量滚动摘要**：运行摘要持久化在 state `context_summary`；`ea3296b` 起改为**差异追加模式**（增量 Prompt 只输出新事实，代码追加进摘要）
- **⑤ 工具结果压缩**：超长 `ToolResponseMessage`（>800 字符）首尾保留截断
- **实测收益**（真实 LLM，40 轮对话）：单次摘要输入 -30.9%、摘要实体保留率 5%→47.5%、输出 token 较合并版 -61.1%
- **评审要点**：① 摘要格式从"散文"变为"散文+事实行"的混合体，下游 LLM 是否接受需验证；② 600 字符上限为硬截断，可能切断关键编号；③ `context_summary` 状态键需确认与其他 Graph 的 Checkpoint 兼容

### 3.2 GoldenSetRunner（`1e37e7f`）
**文件**：`supervisor-agent/.../golden/GoldenSetRunner.java` + `GoldenSetValidationTest`（4 用例）

- 解析 30 条 GoldenCase + 结构校验（字段契约、Agent 合法性、澄清/工具契约）
- **实测**：30 条 0 结构问题；运行时三维准确率（`golden_set_runtime.py`）：路由 100% / 工具 62.1% / 关键词 75.3%
- **评审要点**：① 校验规则中"非澄清用例必须有工具"与澄清用例的判定依赖 `clarification_required` 字段的正确性；② 运行时评测为 **Prompt+LLM 代理**，非真实 Agent 执行，准确率仅供基线参考

## 4. 其他改动（脚本/测试/文档）

| 类别 | 文件 | 说明 |
|---|---|---|
| 测试 | `common` 11 用例、`order-sub` 3、`supervisor` 6、`memory` 4（共 24，全部通过） | 覆盖压缩/Retry/Schema/记忆/GoldenSet |
| 脚本 | `scripts/verify-static.sh`、`eval_rag.py`、`compare_compression_real.py`、`golden_set_runtime.py` | 可复现评测工具（无外部依赖，仅 requests） |
| 文档 | `docs/07` 方案、`docs/08` 报告、`docs/05/06` 修正 | 报告 §9 修正建议已全部应用 |

## 5. 评审清单（Checklist）

- [ ] 确认 `spring-ai-core → spring-ai-model/client-chat` 迁移与 graph-core 1.0.0.4 完全兼容
- [ ] 确认 `ReactAgent.execute → invoke` 的语义等价性（重点：state 合并行为）
- [ ] 增量摘要混合格式（散文+事实行）对下游 Agent 回答质量的影响
- [ ] `IncrementalContextCompressionNode` 在真实多轮会话下的延迟与 token 表现（staging 实测）
- [ ] `GoldenSetRunner` 的澄清用例判定规则是否覆盖全部边界
- [ ] 新增节点/脚本是否需纳入 CI（`mvn test` 已含 JUnit；Python 脚本为手工触发）

## 6. 已知局限

- 运行时三维准确率为 **Prompt+LLM 代理评测**（非真实 Agent 全链路），需 Agent 运行环境验证
- 增量压缩的差异模式在**真实 Agent 上下文**中的回答质量未实测（实验为模拟对话）
- Rerank 开关不生效属 DashScope pipeline 平台侧行为，非本项目代码问题
