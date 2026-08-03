# 校园智能服务多 Agent 助手系统

基于 **Spring AI Alibaba 1.0.0.4**、**Spring Boot 3.2.0**、**MCP Server**、**MyBatis**、**MySQL**、**Nacos** 和 **Vue3** 构建的校园智能服务多 Agent 助手系统，综合演示 20 个 Agent 工程知识点。

---

## 系统架构

```
用户
 └── Frontend（Vue3 · :3000）
       └── domain-proxy（Nginx）
             └── supervisor-agent（:10008）  ← LlmRoutingAgent 总调度
                   ├── consult-sub-agent（:10005）  ← 政策咨询 · RAG · Memory
                   ├── order-sub-agent（:10006）    ← 事务办理 · Plan-Execute · HITL
                   └── feedback-sub-agent（:10007） ← 投诉反馈 · 情绪安抚

MCP Servers（工具层）：
  order-mcp-server（:10002）   ← 13 个事务办理工具（MyBatis + MySQL）
  feedback-mcp-server（:10004）← 4 个反馈工具
  memory-mcp-server（:10010）  ← Mem0 长期记忆工具（memory-store / memory-search）
```

---

## 技术栈

| 层次 | 技术 |
|------|------|
| AI 框架 | Spring AI 1.0.0 · Spring AI Alibaba 1.0.0.4 |
| Agent 模式 | LlmRoutingAgent · ReactAgent · StateGraph · A2A 协议 |
| 大模型 | DashScope（qwen-plus）· OpenAI 兼容接口 |
| 知识库 | 阿里云百炼（DashScope Bailian）· 向量检索 + Rerank + 查询改写 + 混合检索 |
| 长期记忆 | Mem0（外部 Memory 服务） |
| 数据层 | MySQL 8 · MyBatis 3.5 · Redis |
| 服务发现 | Nacos（MCP 工具注册 · A2A 子 Agent 发现） |
| 前端 | Vue3 · TypeScript · Vite · Ant Design Vue |
| 部署 | Docker Compose · Nginx 反向代理 |

---

## 覆盖的 Agent 知识点（20 个）

### 架构模式
| 知识点 | 位置 |
|--------|------|
| Supervisor 路由模式 | `SupervisorAgent.java` — LlmRoutingAgent 分发三个子 Agent |
| ReactAgent（ReAct 循环） | 三个子 Agent 默认模式 |
| Graph-based Agent | `DailyReportAgentConfiguration` · `EvaluationAgentConfiguration` |
| Plan-and-Execute | `PlannerNode → ExecutorNode → SynthesizerNode`（`?mode=plan`） |

### 工具体系
| 知识点 | 位置 |
|--------|------|
| Local Tool Calling | `ConsultTools.java` · `CronAgentTools.java` |
| MCP Server | order / feedback / memory 三个独立 MCP 服务，共 17 个工具 |
| MCP 双模式接入 | SSE 直连 + Nacos 负载均衡两种接入方式 |

### Agent 通信
| 知识点 | 位置 |
|--------|------|
| A2A 协议 | Supervisor 通过 gRPC + Nacos AgentCard 调用远程子 Agent |

### 状态与记忆
| 知识点 | 位置 |
|--------|------|
| 会话 Checkpoint | 三个子 Agent 启用 MemorySaver，`chat_id` 作为 threadId |
| 外部 Memory 集成 | memory-mcp-server 接入 Mem0，跨会话长期记忆 |
| Memory 主动注入 | `MemoryInjectNode`（公共组件）— 前置节点主动注入 SystemMessage |

### RAG 体系
| 知识点 | 位置 |
|--------|------|
| 基础 RAG 检索 | DashScope Bailian 向量知识库（16 份文档，1610 行） |
| 查询改写 | `ConsultService.rewriteQuery()` — 检索前 LLM 扩展查询 |
| Rerank 重排序 | `rerank-top-n=3` · `enable-reranking=true` |
| 混合检索 | 向量检索 + 关键词精确匹配（products 表），精确结果前置 |
| RAG 来源引用 | 回答末尾附加 `**参考来源：**` |

### 输出与控制
| 知识点 | 位置 |
|--------|------|
| 结构化输出 | `BeanOutputConverter<EvaluationResult>` · `BeanOutputConverter<ExecutionPlan>` |
| SSE 流式输出 | 所有 Controller 均基于 Reactor Flux |
| Human-in-the-Loop | `planAndExecuteHitlGraph`：`interruptBefore("executor")` + `/confirm` 端点 |
| 滑动窗口压缩 | `ContextCompressionNode`（messages >20 时 LLM 摘要早期对话） |

### 调度与自动化
| 知识点 | 位置 |
|--------|------|
| 定时调度 Agent | `LocalScheduledTrigger.java`（@Scheduled）+ XxlJob 可选 |
| 自定义 NodeAction | EvaluationClassifierNode · MemoryInjectNode · PlannerNode 等 6 种 |

---

## Prompt / Context / Harness / Loop 四大工程

| 工程 | 实现内容 |
|------|---------|
| **Prompt 工程** | 结构化 System Prompt（角色/流程/约束）· Few-shot 边界示例注入 · 工具描述四要素标准化 · Schema 注入（BeanOutputConverter） |
| **Context 工程** | 多层上下文构建（会话+用户身份+记忆）· Memory 主动注入 · 滑动窗口压缩 · 混合检索 · RAG 知识库片段注入 |
| **Harness 工程** | 校园场景 Golden Set（30 条，consult/order/feedback 各 10 条）· EvaluationClassifierNode 批量评分 · BeanOutputConverter 结构化评测结果 · 满意度趋势分析 |
| **Loop 工程** | IterationNode 批量迭代 · @Scheduled 本地定时 Loop · 条件分支 Loop（满意度<3走告警分支）· 工具调用 Retry（指数退避）· 多轮澄清 Loop · Human-in-the-Loop |

---

## 知识库（RAG 内容）

`consult-sub-agent/src/main/resources/knowledge/` 目录下 16 份文档：

| 类型 | 文件 |
|------|------|
| 服务事项（8份） | 图书馆研讨间 · 心理咨询 · 在读证明 · 校园卡补办 · 体育馆 · 宿舍报修 · 奖学金材料预审 · 社团场地申请 |
| 政策专题（4份） | 奖学金政策全览 · 困难生认定 · 转专业政策 · 课程退补选 |
| FAQ（2份） | 通用引导 · 组合场景 FAQ |
| 系统说明（2份） | overview · products |

需将上述文件上传至阿里云百炼知识库，知识库 ID 配置到 `.env` 的 `DASHSCOPE_INDEX_ID`。

---

## 环境要求

- Docker Desktop（用于中间件）
- Java 17+
- Maven 3.8+
- Node.js 20+
- 阿里云 DashScope API Key（大模型 + 知识库）
- 阿里云百炼知识库 ID
- Mem0 API Key（长期记忆，可选）

---

## 启动步骤

### 1. 配置环境变量

```bash
cp env.template .env
```

编辑 `.env`，至少填写：

```bash
DASHSCOPE_API_KEY=sk-xxxxxx          # 阿里云 DashScope API Key
DASHSCOPE_INDEX_ID=xxxxxxxx          # 阿里云百炼知识库 ID
MEM0_API_KEY=xxxxxx                  # Mem0 API Key（可选）
```

### 2. 启动中间件（MySQL · Redis · Nacos）

```bash
cd docker/middleware
cp .env.example .env
cp mysql.env.example mysql.env
cp nacos.env.example nacos.env
cp redis.env.example redis.env
# 编辑上述文件，将 change-me-* 替换为本地密码
docker compose up -d
```

### 3. 编译后端

```bash
mvn clean package -DskipTests
```

### 4. 启动后端服务

**Windows：**
```powershell
powershell -ExecutionPolicy Bypass -File .\start-backend.ps1
```

**Linux / macOS：**
```bash
chmod +x build.sh && ./build.sh
```

### 5. 启动前端

```powershell
powershell -ExecutionPolicy Bypass -File .\start-frontend.ps1
```

前端访问：**http://localhost:3000**

---

## 服务端口

| 端口 | 服务 | 说明 |
|------|------|------|
| 3000 | frontend | Vue3 聊天界面 |
| 10008 | supervisor-agent | 主入口，LlmRoutingAgent |
| 10005 | consult-sub-agent | 政策咨询 Agent（RAG + Memory） |
| 10006 | order-sub-agent | 事务办理 Agent（Plan-Execute · HITL） |
| 10007 | feedback-sub-agent | 反馈投诉 Agent |
| 10002 | order-mcp-server | 事务办理 MCP 工具服务 |
| 10004 | feedback-mcp-server | 反馈投诉 MCP 工具服务 |
| 10010 | memory-mcp-server | 长期记忆 MCP 工具服务 |
| 3306 | MySQL | 业务数据库 |
| 6379 | Redis | 缓存 |
| 8848 | Nacos | 服务注册与发现 |

---

## Debug 接口（各子 Agent 直调）

各子 Agent 提供 Debug 接口，支持多种模式直接验证功能：

### order-sub-agent（`:10006`）

```
GET /api/order-sub-agent/debug?user_query=<问题>&chat_id=<会话ID>&mode=<模式>
```

| mode | 说明 |
|------|------|
| `react`（默认） | 标准 ReactAgent + MemorySaver Checkpoint |
| `plan` | Plan-and-Execute（PlannerNode → ExecutorNode → SynthesizerNode） |
| `memory` | Memory 主动注入（MemoryInjectNode + ReactAgent） |
| `hitl` | Human-in-the-Loop（planner 暂停等待 /confirm 确认） |

```
GET /api/order-sub-agent/confirm?chat_id=<会话ID>&action=approve|reject
```

### consult-sub-agent（`:10005`）

```
GET /api/consult_sub_agent/debug?user_query=<问题>&chat_id=<会话ID>&mode=react|memory|compress
```

| mode | 说明 |
|------|------|
| `react`（默认） | 标准 ReactAgent + Checkpoint |
| `memory` | Memory 主动注入 |
| `compress` | 滑动窗口上下文压缩（ContextCompressionNode） |

### feedback-sub-agent（`:10007`）

```
GET /api/feedback-sub-agent/debug?user_query=<问题>&chat_id=<会话ID>&mode=react|memory
```

---

## 停止服务

```powershell
# Windows
powershell -ExecutionPolicy Bypass -File .\stop-backend.ps1

# 停止中间件
cd docker/middleware && docker compose down
```

---

## 项目结构

```
campus-smart-service-multi-agent-assistant/
├── common/                        # 公共组件（MemoryInjectNode · ContextCompressionNode）
├── supervisor-agent/              # 总调度 Agent（LlmRoutingAgent · A2A · 调度 Graph）
│   └── resources/data/golden_set/ # 校园场景评测 Golden Set（30条）
├── consult-sub-agent/             # 政策咨询 Agent（RAG · 混合检索 · Memory）
│   └── resources/knowledge/       # 知识库文档（16份）
├── order-sub-agent/               # 事务办理 Agent（Plan-Execute · HITL · 多轮澄清）
├── feedback-sub-agent/            # 反馈投诉 Agent
├── order-mcp-server/              # 事务办理 MCP 工具服务
├── feedback-mcp-server/           # 反馈投诉 MCP 工具服务
├── memory-mcp-server/             # 长期记忆 MCP 工具服务（Mem0）
├── frontend/                      # Vue3 聊天界面
├── docker/middleware/             # MySQL · Redis · Nacos Docker Compose
├── docs/                          # 设计文档与调研报告
└── .env                           # 环境变量（从 env.template 复制并填写）
```
