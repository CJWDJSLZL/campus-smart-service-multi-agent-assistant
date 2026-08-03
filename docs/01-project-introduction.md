# 项目介绍

## 项目名称

校园智能服务多 Agent 助手系统（Campus Smart Service Multi-Agent Assistant）

## 项目背景

随着高校数字化转型加速，师生在日常学习和生活中面临大量重复性的信息查询、事务办理和投诉反馈需求。传统的单一问答机器人能力有限，难以应对政策咨询、事务预约、情绪安抚等差异显著的业务场景。

本项目以「校园智能服务」为业务场景，基于 **Spring AI Alibaba** 框架，构建一个完整的多 Agent 协作系统，同时作为 Agent 工程能力的综合演示平台，覆盖架构模式、工具调用、RAG 知识库、长期记忆、定时调度、人工介入等 20 个 Agent 知识点，以及 Prompt 工程、Context 工程、Harness 工程、Loop 工程四大 AI 工程范式。

## 项目定位

本项目同时具备两个维度的价值：

1. **业务价值**：为校园师生提供政策咨询、事务办理、反馈投诉三类服务，系统可接入真实校园业务数据运行。
2. **技术价值**：作为基于 Spring AI Alibaba 的多 Agent 系统工程样板，每个功能模块均对应明确的 Agent 知识点，便于学习、演示和二次开发。

---

## 系统整体架构

```
┌──────────────────────────────────────────────────────────────┐
│                        用户（师生）                            │
└─────────────────────────┬────────────────────────────────────┘
                           │ HTTP / SSE
┌─────────────────────────▼────────────────────────────────────┐
│               Frontend（Vue3 · TypeScript · :3000）           │
└─────────────────────────┬────────────────────────────────────┘
                           │ domain-proxy（Nginx）
┌─────────────────────────▼────────────────────────────────────┐
│          supervisor-agent（LlmRoutingAgent · :10008）         │
│  ┌─── 意图识别 ──────────────────────────────────────────┐   │
│  │  consult_agent  │  order_agent  │  feedback_agent    │   │
│  └─────────────────┴───────────────┴────────────────────┘   │
│            A2A 协议（gRPC + Nacos 服务发现）                   │
└──────────┬──────────────────┬────────────────┬───────────────┘
           │                  │                │
┌──────────▼──────┐  ┌────────▼────────┐  ┌───▼──────────────┐
│ consult-sub-    │  │ order-sub-      │  │ feedback-sub-    │
│ agent(:10005)   │  │ agent(:10006)   │  │ agent(:10007)    │
│ ReactAgent      │  │ ReactAgent      │  │ ReactAgent       │
│ RAG · Memory    │  │ Plan-Execute    │  │ 情绪 · 反馈       │
│                 │  │ HITL · 澄清     │  │                  │
└────────┬────────┘  └────────┬────────┘  └────────┬─────────┘
         │ Nacos MCP                                │
         │ (loadbalanced)      │                    │
         └─────────────────────┼────────────────────┘
                               │ Nacos MCP Tool Registry
┌──────────────────────────────┼──────────────────────────────┐
│         MCP Servers 工具层   │                               │
│  order-mcp-server(:10002)    │  feedback-mcp-server(:10004) │
│  13 个事务办理工具             │  4 个反馈工具                  │
│  memory-mcp-server(:10010)   │                               │
│  2 个记忆工具（Mem0）          │                               │
└──────────────────────────────┴──────────────────────────────┘
                  ↕ MyBatis · JDBC
        ┌─────────────────────────┐
        │  MySQL · Redis · Nacos  │
        └─────────────────────────┘
```

---

## 核心业务功能

### 1. 政策咨询（consult-sub-agent）

- 奖学金申请条件和流程查询
- 转专业政策查询
- 课程退补选规则说明
- 困难生认定材料说明
- 在读证明用途和办理方式
- 校园卡补办、宿舍报修、体育馆预约、社团场地申请等服务说明

**技术实现**：RAG（向量检索 + 查询改写 + Rerank + 混合检索）+ 阿里云百炼知识库（16 份文档）

### 2. 事务办理（order-sub-agent）

- 预约图书馆研讨间、心理咨询、体育馆、宿舍报修等服务
- 查询用户的历史办理记录
- 修改办理记录备注
- 取消预约/办理记录
- 校验服务名额可用性

**技术实现**：Plan-and-Execute（PlannerNode → ExecutorNode → SynthesizerNode）+ Human-in-the-Loop + 多轮澄清 Loop + MCP 工具调用（13 个工具，MySQL 持久化）

### 3. 反馈投诉（feedback-sub-agent）

- 受理服务投诉（图书馆、宿舍、教务、后勤等）
- 记录用户建议和服务评价
- 情绪识别与安抚（愤怒/失望/满意/建议）
- 更新投诉处理方案

**技术实现**：ReactAgent + MCP 工具调用（4 个工具）+ Memory 偏好提取

### 4. 长期记忆（memory-mcp-server + Mem0）

- 跨会话记录用户偏好（预约时间偏好、办理习惯、关注政策方向）
- 每次对话前主动注入历史偏好作为上下文
- 对话结束后自动提取新偏好写入记忆

### 5. 自动化调度（supervisor-agent 调度图）

- **运营日报 Agent**：每天 09:00 自动拉取数据、LLM 分析、钉钉推送
- **用户评价分析 Agent**：每周一 10:00 批量分析反馈数据，满意度 < 3 时触发告警路径
- **定时任务管理 Agent**：支持用户自定义 Cron 表达式注册周期性任务

---

## 核心数据模型

| 表名 | 说明 | 字段要点 |
|------|------|---------|
| `users` | 校园用户 | id, username, phone, email, status |
| `products` | 服务事项 | name, stock（名额）, is_seasonal, available_regions |
| `orders` | 办理/预约记录 | order_id（CAMPUS_前缀）, user_id, product_name, sweetness（办理方式）, ice_level（时间偏好）|
| `feedback` | 反馈投诉 | user_id, feedback_type（1-4）, rating（1-5）, content, solution |

> `orders` 表的 `sweetness` 字段复用存储"办理方式"，`ice_level` 字段复用存储"时间偏好"，是对现有数据模型的语义改造。

---

## 服务端口与模块清单

| 端口 | 模块 | 职责 |
|------|------|------|
| 3000 | frontend | Vue3 聊天界面 |
| 10008 | supervisor-agent | 总调度，LlmRoutingAgent，A2A 客户端 |
| 10005 | consult-sub-agent | 政策咨询，RAG，Memory |
| 10006 | order-sub-agent | 事务办理，Plan-Execute，HITL |
| 10007 | feedback-sub-agent | 反馈投诉，情绪安抚 |
| 10002 | order-mcp-server | 13 个事务工具，MySQL |
| 10004 | feedback-mcp-server | 4 个反馈工具，MySQL |
| 10010 | memory-mcp-server | 2 个记忆工具，Mem0 外部服务 |
| 3306 | MySQL | 业务数据持久化 |
| 6379 | Redis | 缓存（配置中心等） |
| 8848 | Nacos | 服务注册与发现，MCP 工具注册中心 |
