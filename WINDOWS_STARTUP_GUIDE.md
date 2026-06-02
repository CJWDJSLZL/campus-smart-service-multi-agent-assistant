# Windows 启动与部署指南

本文档用于在 Windows 环境下启动校园智能服务多 Agent 助手系统。公开仓库中不包含任何个人 API Key、真实域名或公网 IP。

## 1. 准备环境

请先安装：

- Docker Desktop
- Java 17+
- Maven
- Node.js 20+
- PowerShell

## 2. 配置环境变量

复制根目录配置模板：

```powershell
Copy-Item .\env.template .\.env
```

然后编辑 `.env`，填写自己的配置：

```env
DASHSCOPE_API_KEY=你的DashScopeKey
DASHSCOPE_INDEX_ID=你的百炼知识库ID
MEM0_API_KEY=你的Mem0Key
AI_OPENAI_API_KEY=你的OpenAI兼容接口Key
AI_OPENAI_BASE_URL=你的OpenAI兼容接口地址
```

注意：

- `.env` 已加入 `.gitignore`，不要上传到 GitHub。
- 如果 API Key 曾经出现在公开仓库、截图或日志中，请立即到平台控制台轮换。

## 3. 配置 Docker 中间件环境

进入中间件目录：

```powershell
cd docker\middleware
Copy-Item .\.env.example .\.env
Copy-Item .\mysql.env.example .\mysql.env
Copy-Item .\nacos.env.example .\nacos.env
Copy-Item .\redis.env.example .\redis.env
```

编辑这些文件，把 `change-me-*` 替换成自己的本地密码。

## 4. 启动中间件

```powershell
cd docker\middleware
docker compose up -d
docker compose ps
```

如果 `3306` 端口被本机 MySQL 占用，可以：

- 停止本机 MySQL 服务；或
- 修改 `docker/middleware/docker-compose.yaml` 中 MySQL 端口映射。

## 5. 编译后端

回到项目根目录：

```powershell
mvn clean package -DskipTests "-Dmaven.repo.local=.m2\repository"
```

首次编译需要下载依赖，可能耗时较长。

## 6. 启动后端

```powershell
powershell -ExecutionPolicy Bypass -File .\start-backend.ps1
```

主要服务端口：

- `10002`：事务办理 MCP Server
- `10004`：反馈 MCP Server
- `10005`：咨询 Agent
- `10006`：事务办理 Agent
- `10007`：反馈 Agent
- `10008`：Supervisor 主入口
- `10010`：记忆 MCP Server

## 7. 启动前端

```powershell
powershell -ExecutionPolicy Bypass -File .\start-frontend.ps1
```

访问：

```text
http://localhost:3000
```

## 8. 域名访问配置

如果需要通过自己的域名访问：

1. 在域名控制台添加 A 记录，指向你的公网 IP。
2. 在路由器中把公网 `80` 端口转发到当前电脑。
3. 启动本项目前端、后端和域名代理。
4. 根据需要修改 `domain-proxy/nginx.conf` 的 `server_name`。

公开仓库中不要写入真实域名、公网 IP 或路由器内网地址。

## 9. 常见问题

### 前端发送失败

检查：

- `supervisor-agent` 是否监听 `10008`
- API Key 是否有效
- DashScope 账号是否欠费
- Nacos 中各 Agent 和 MCP Server 是否注册成功

### 子 Agent 未启动

查看日志：

```powershell
Get-Content .\logs\consult-sub-agent.err.log -Tail 80
Get-Content .\logs\order-sub-agent.err.log -Tail 80
Get-Content .\logs\feedback-sub-agent.err.log -Tail 80
Get-Content .\logs\supervisor-agent.err.log -Tail 80
```

### 中间件无法启动

检查 Docker Desktop 是否运行，以及端口 `3306`、`6379`、`8848` 是否被占用。

## 10. 停止服务

```powershell
powershell -ExecutionPolicy Bypass -File .\stop-backend.ps1
```

停止中间件：

```powershell
cd docker\middleware
docker compose down
```
