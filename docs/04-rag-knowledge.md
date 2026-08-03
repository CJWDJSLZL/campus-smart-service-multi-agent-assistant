# RAG 相关知识详解

本文档详细介绍项目中涉及的 RAG（Retrieval-Augmented Generation，检索增强生成）技术栈，包含知识库构建、检索策略、质量优化等完整链路。

---

## 一、RAG 整体架构

```
用户查询
    │
    ▼
查询改写（rewriteQuery）← LLM 扩展查询语义
    │
    ├─→ 向量检索路（DashScope Bailian）→ Rerank 重排序
    │
    └─→ 关键词检索路（products 表精确匹配）
    │
    ▼
合并结果（精确匹配前置 + 向量检索后置）
    │
    ▼
附加来源信息（**参考来源：**）
    │
    ▼
作为工具调用结果注入 Agent 上下文
    │
    ▼
LLM 基于检索内容生成回答
```

---

## 二、知识库建设

### 2.1 知识库基本信息

- **平台**：阿里云百炼（DashScope Bailian）
- **知识库 ID**：通过 `DASHSCOPE_INDEX_ID` 环境变量配置（当前值：`m36khcyb7v`）
- **文档总数**：16 份 Markdown 文档，共 1,610 行
- **本地路径**：`consult-sub-agent/src/main/resources/knowledge/`

### 2.2 知识库文档清单

| 文件名 | 类型 | 内容摘要 |
|--------|------|---------|
| `overview.md` | 系统说明 | 多 Agent 系统定位、典型场景、设计原则 |
| `products.md` | 服务事项 | 8 类服务事项的快速导航和重点说明 |
| `service_library_room.md` | 服务事项 | 图书馆研讨间预约规则、FAQ、联系方式 |
| `service_counseling.md` | 服务事项 | 心理咨询预约说明（含隐私保护条款） |
| `service_enrollment_proof.md` | 服务事项 | 在读证明用途分类、办理步骤（电子版/盖章原件） |
| `service_campus_card.md` | 服务事项 | 校园卡补办流程、余额说明、常见 FAQ |
| `service_gym.md` | 服务事项 | 体育馆各类场地预约规则 |
| `service_dorm_repair.md` | 服务事项 | 宿舍报修类型、响应时限、联系方式 |
| `service_scholarship_review.md` | 服务事项 | 奖学金材料预审服务（仅 9-10 月开放） |
| `service_venue_apply.md` | 服务事项 | 社团活动场地申请流程（含包场规则） |
| `policy_scholarship.md` | 政策专题 | 奖学金类型全览、申请时间轴、常见 FAQ |
| `policy_financial_aid.md` | 政策专题 | 困难生认定流程、助学金类型、隐私保护 |
| `policy_major_transfer.md` | 政策专题 | 转专业条件、流程、补修课程说明 |
| `policy_course_selection.md` | 政策专题 | 选退补三阶段规则、常见操作失误 |
| `faq_general.md` | FAQ | 找错窗口引导、各部门联系方式汇总 |
| `faq_combined_scenarios.md` | FAQ | 8 类组合场景（出国留学、奖学金申请季等） |

### 2.3 文档结构规范（RAG 分块友好）

每份文档均包含标准元数据 Front Matter：

```markdown
---
title: 图书馆研讨间预约
category: service
service_items: [图书馆研讨间预约]
department: 图书馆服务部
version: v1.0
effective_date: 2026-09-01
tags: [图书馆, 研讨间, 小组讨论, 预约, 学习室...]
---
```

正文结构：**一句话摘要 → 适用场景 → 办理条件 → 所需材料/信息 → 办理步骤 → FAQ → 注意事项 → 相关服务 → 联系方式**

**分块原则**：
- 每个 `##` 二级标题下内容不超过 300 字，确保向量块语义聚焦
- FAQ 每条 Q&A 独立成段，关键词密度高
- 同义词集中在同一段（如"在读证明 = 学籍证明 = 学生身份证明"）

---

## 三、检索实现详解

### 3.1 配置参数

```yaml
# consult-sub-agent/src/main/resources/application.yml
spring:
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}
      document-retrieval:
        index-id: ${DASHSCOPE_INDEX_ID}
        enable-reranking: ${DASHSCOPE_ENABLE_RERANKING:true}   # 启用 Rerank
        rerank-top-n: ${DASHSCOPE_RERANK_TOP_N:3}             # Rerank 后保留 3 条
        rerank-min-score: ${DASHSCOPE_RERANK_MIN_SCORE:0}     # 最低分阈值
```

### 3.2 向量检索流程（第一路）

**实现位置**：`ConsultService.java`

```java
DashScopeDocumentRetrieverOptions options = DashScopeDocumentRetrieverOptions.builder()
    .withEnableReranking(enableReranking)   // true
    .withRerankTopN(rerankTopN)             // 3
    .withRerankMinScore(rerankMinScore)     // 0
    .build();

List<Document> documents = dashscopeApi.retriever(indexID, rewrittenQuery, options);
```

`DashScopeApi.retriever()` 内部执行：
1. 将 `rewrittenQuery` 向量化（Embedding）
2. 在百炼知识库中执行向量相似度检索
3. 使用 DashScope Rerank 模型对候选文档重新打分排序
4. 返回 Top-N 文档（`List<Document>`）

每个 `Document` 包含：
- `getText()`：文档块的文本内容
- `getMetadata()`：元数据（title、source、file_name 等）

---

### 3.3 查询改写（Query Rewriting）

**目的**：用户查询往往是口语化、简短的（如"奖学金"），直接向量化后与文档块的相似度较低。查询改写通过 LLM 将原始查询扩展为语义更丰富的检索友好形式。

**实现位置**：`ConsultService.rewriteQuery()`

**Prompt 设计**：
```
你是校园服务检索优化助手。将用户查询改写为更适合知识库检索的形式：
1. 展开缩写词（如"奖学金"→"奖学金申请政策和评定条件"）
2. 添加同义词和领域关键词（如"办理"→"申请办理流程步骤"）
3. 补充"申请流程"、"办理步骤"、"所需材料"等场景词
4. 只输出改写后的查询语句，不要任何解释
原始查询：{originalQuery}
```

**改写示例**：
| 原始查询 | 改写后查询 |
|---------|---------|
| "奖学金" | "奖学金申请条件政策评定流程所需材料" |
| "图书馆预约" | "图书馆研讨间预约规则申请流程注意事项" |
| "在读证明" | "在读证明办理用途申请步骤审核时间线上线下" |
| "转专业" | "转专业申请条件流程材料时间限制补修课程" |

**降级策略**：改写失败时（LLM 异常或返回空）使用原始查询，不中断流程：
```java
try {
    String rewritten = chatClient.prompt().user(prompt).call().content();
    if (rewritten != null && !rewritten.isBlank()) {
        return rewritten.trim();
    }
} catch (Exception e) {
    logger.warn("查询改写失败，使用原始查询: {}", e.getMessage());
}
return originalQuery;
```

---

### 3.4 Rerank 重排序

**目的**：向量检索返回的候选文档按向量相似度排序，但相似度高不等于语义最相关。Rerank 使用交叉编码器（Cross-encoder）对每个候选文档与查询的相关性重新打分，精排后返回 Top-N。

**参数说明**：
| 参数 | 值 | 说明 |
|------|-----|------|
| `enable-reranking` | `true` | 启用 Rerank |
| `rerank-top-n` | `3` | 返回相关度最高的 3 篇文档 |
| `rerank-min-score` | `0` | 最低分阈值（0 表示不过滤） |

**在 DashScopeDocumentRetrieverOptions 中配置**，Rerank 由百炼平台自动完成，对调用方透明。

---

### 3.5 混合检索（Hybrid Retrieval）

**目的**：纯向量检索对精确名词（服务名称、校区、时间等）召回率偏低。混合检索补充关键词精确匹配，两路结果合并后精确匹配结果前置（高优先级）。

**实现位置**：`ConsultService.buildExactMatchContext()`

**检索路径**：
```
用户查询
├─ 关键词路：productMapper.selectByNameLike(query) → 匹配 products 表的服务名称
│           → 返回 "【服务事项精确匹配】\n- 图书馆研讨间预约：为学习小组..."
│
└─ 向量路：dashscopeApi.retriever(indexID, rewrittenQuery, options)
          → 返回语义相关文档块
```

**合并策略**：
```java
StringBuilder result = new StringBuilder();
if (!exactMatchContext.isEmpty()) {
    result.append(exactMatchContext).append("\n\n");  // 精确匹配前置
}
for (Document doc : documents) {                      // 向量检索后置
    result.append(doc.getText());
}
```

**精确匹配触发场景**：
- 用户输入"图书馆研讨间"→ products 表精确命中，直接返回服务描述
- 用户输入"心理咨询"→ 精确命中服务记录，同时向量检索政策文档
- 用户输入"奖学金怎么申请"→ 精确匹配无命中，仅向量检索

---

### 3.6 RAG 来源引用

**目的**：在检索结果中附加来源信息，增强回答可信度，让用户知道答案来自哪份文档。

**实现位置**：`ConsultService.searchKnowledge()` 末尾

**来源信息提取**：
```java
StringBuilder sourceInfo = new StringBuilder("\n\n---\n**参考来源：**\n");
boolean hasSource = false;
for (Document doc : documents) {
    String source = null;
    if (doc.getMetadata() != null) {
        // 依次尝试 title、source、file_name 元数据字段
        Object titleObj = doc.getMetadata().get("title");
        if (titleObj == null) titleObj = doc.getMetadata().get("source");
        if (titleObj == null) titleObj = doc.getMetadata().get("file_name");
        if (titleObj != null) source = titleObj.toString();
    }
    if (source == null || source.isBlank()) source = "校园知识库";
    sourceInfo.append("- ").append(source).append("\n");
    hasSource = true;
}
if (hasSource) {
    finalResult = finalResult + sourceInfo;
}
```

**前端显示**：`MarkdownRenderer.vue` 自动渲染 Markdown 格式，来源信息以带分隔线的列表展示：

```
...（回答内容）...

---
**参考来源：**
- 图书馆研讨间预约
- 校园知识库
```

---

## 四、ConsultTools 工具与 RAG 的关系

`ConsultTools` 是 RAG 的对外暴露层，将检索功能包装为 LLM 可调用的工具：

```java
@Tool(name = "consult-search-knowledge",
      description = "根据用户查询语句，在校园知识库中检索相关政策、流程和说明。...")
public String searchKnowledge(
        @ToolParam(description = "查询语句，描述用户想了解的校园政策或服务信息") String query) {
    return consultService.searchKnowledge(query);
}
```

**调用链**：
```
ReactAgent（consult-sub-agent）
    → 调用工具 consult-search-knowledge
    → ConsultTools.searchKnowledge(query)
    → ConsultService.searchKnowledge(query)
        → rewriteQuery(query)           // 查询改写
        → buildExactMatchContext(query) // 关键词精确匹配
        → dashscopeApi.retriever()      // 向量检索 + Rerank
        → 合并结果 + 附加来源
    → 返回检索结果文本给 Agent
    → Agent 基于结果生成回答
```

---

## 五、评测 Golden Set（Harness 工程）

**位置**：`supervisor-agent/src/main/resources/data/golden_set/`

**consult_golden.txt**（10 条咨询场景用例）：
```
--- CASE_C001 ---
input: 奖学金怎么申请？
expected_agent: consult_agent
expected_tools: [consult-search-knowledge]
expected_output_keywords: [申请, 材料, 条件, 流程]
---
```

每条用例包含：
- `input`：用户输入
- `expected_agent`：预期路由到的子 Agent
- `expected_tools`：预期调用的工具列表
- `expected_output_keywords`：回答中应出现的关键词

**用途**：用于自动化评测 RAG 召回质量（关键词命中率）、路由准确率、工具调用正确率。

---

## 六、RAG 完整知识点清单

| 知识点 | 实现位置 | 技术细节 |
|--------|---------|---------|
| 向量检索 | `ConsultService.searchKnowledge()` | `DashScopeApi.retriever()` |
| 查询改写 | `ConsultService.rewriteQuery()` | ChatClient + 专用 Prompt |
| Rerank 重排序 | `DashScopeDocumentRetrieverOptions` | `enable-reranking=true, top-n=3` |
| 混合检索 | `ConsultService.buildExactMatchContext()` | `productMapper.selectByNameLike()` 精确匹配 |
| 来源引用 | `ConsultService.searchKnowledge()` 末尾 | 从 `Document.getMetadata()` 提取 title/source |
| 知识库文档 | `knowledge/` 目录 16 份 | 结构化 Markdown，含 Front Matter 元数据 |
| RAG 工具封装 | `ConsultTools.java` | `@Tool` 注解，LLM 可直接调用 |
| 评测 Golden Set | `data/golden_set/` | 30 条校园场景用例 |
