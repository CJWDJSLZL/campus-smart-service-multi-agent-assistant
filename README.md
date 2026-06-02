# 校园智能服务多 Agent 助手系统

基于 Spring Boot、Spring AI Alibaba、MCP Server、MyBatis、MySQL、Nacos、Redis 和 Vue3 构建的校园智能服务多 Agent 助手系统。

## 项目简介

本项目面向全校师生，提供政策咨询、事务办理、预约申请、办理记录查询、反馈投诉和服务建议等能力。

系统采用 `Supervisor Agent + 多个子 Agent + MCP Server` 的架构：

- `supervisor-agent`：总调度 Agent，负责理解用户意图并分发任务。
- `consult-sub-agent`：咨询 Agent，负责奖学金、办事流程、通知公告、校园资源等咨询。
- `order-sub-agent`：事务办理 Agent，负责预约申请、办理记录查询、备注修改和记录取消。
- `feedback-sub-agent`：反馈 Agent，负责投诉、差评、建议、评价和情绪安抚。
- `order-mcp-server`：封装校园事务办理工具，底层通过 Service 和 MyBatis 操作 MySQL。
- `feedback-mcp-server`：封装校园反馈投诉工具。
- `memory-mcp-server`：封装用户长期偏好记忆工具，调用 Mem0 服务。
- `frontend`：Vue3 + Vite 聊天前端。

## 核心场景

1. 政策咨询：例如“奖学金申请需要准备什么材料？”
2. 事务办理：例如“帮我预约明天下午的图书馆研讨间。”
3. 记录查询：例如“查询我的校园事务办理记录。”
4. 反馈投诉：例如“我要反馈宿舍维修处理太慢。”
5. 长期记忆：例如记住用户喜欢流程图式回答、常在下午预约服务等偏好。

## 项目结构

- `frontend/`：前端聊天界面。
- `supervisor-agent/`：Supervisor 路由 Agent。
- `consult-sub-agent/`：校园咨询子 Agent。
- `order-sub-agent/`：校园事务办理子 Agent。
- `feedback-sub-agent/`：校园反馈投诉子 Agent。
- `order-mcp-server/`：校园事务办理 MCP Server。
- `feedback-mcp-server/`：反馈投诉 MCP Server。
- `memory-mcp-server/`：长期记忆 MCP Server。
- `docker/middleware/`：MySQL、Nacos、Redis 等中间件。
- `consult-sub-agent/src/main/resources/kownledge/`：校园知识库示例文档。

## 环境要求

- Docker Desktop
- Java 17+
- Maven
- Node.js 20+
- 可用的 DashScope API Key
- 可用的百炼知识库 ID
- 可用的 Mem0 API Key

## 启动步骤

### 1. 配置环境变量

复制 `env.template` 为 `.env`，并配置：

- `DASHSCOPE_API_KEY`
- `DASHSCOPE_INDEX_ID`
- `MEM0_API_KEY`
- `AI_OPENAI_BASE_URL`
- `AI_OPENAI_API_KEY`


### 2. 上传校园知识库

将以下文件上传到阿里云百炼知识库，并把知识库 ID 配置到 `.env`：

- `consult-sub-agent/src/main/resources/kownledge/overview.md`
- `consult-sub-agent/src/main/resources/kownledge/products.md`

### 3. 启动中间件

首次启动前，先准备 Docker 中间件环境文件：

```powershell
cd docker\middleware
Copy-Item .\.env.example .\.env
Copy-Item .\mysql.env.example .\mysql.env
Copy-Item .\nacos.env.example .\nacos.env
Copy-Item .\redis.env.example .\redis.env
```

然后把 `change-me-*` 替换成本地密码。

```powershell
cd docker\middleware
docker compose up -d
docker compose ps
```

如果本机已有 MySQL 占用 `3306`，需要先停止本机 MySQL 服务，或修改 Docker Compose 的端口映射。

### 4. 编译后端

```powershell
mvn clean package -DskipTests "-Dmaven.repo.local=.m2\repository"
```

首次构建会下载依赖，耗时可能较长。

### 5. 启动后端服务

```powershell
powershell -ExecutionPolicy Bypass -File .\start-backend.ps1
```

主 API 地址：

- `http://localhost:10008`

### 6. 启动前端

```powershell
powershell -ExecutionPolicy Bypass -File .\start-frontend.ps1
```

前端访问地址：

- `http://localhost:3000`

## 服务端口

- `10002`：order-mcp-server，校园事务办理工具服务。
- `10004`：feedback-mcp-server，反馈投诉工具服务。
- `10005`：consult-sub-agent，咨询 Agent。
- `10006`：order-sub-agent，事务办理 Agent。
- `10007`：feedback-sub-agent，反馈 Agent。
- `10008`：supervisor-agent，主入口。
- `10010`：memory-mcp-server，长期记忆工具服务。
- `3000`：前端页面。
- `3306`：MySQL。
- `6379`：Redis。
- `8848`：Nacos。


## 停止服务

```powershell
powershell -ExecutionPolicy Bypass -File .\stop-backend.ps1
```

如需停止中间件：

```powershell
cd docker\middleware
docker compose down
```
