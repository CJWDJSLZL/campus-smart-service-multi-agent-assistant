# GitHub 上传前检查清单

上传前请确认：

- `.env` 没有被提交。
- `docker/middleware/.env`、`mysql.env`、`nacos.env`、`redis.env` 没有被提交。
- `logs/`、`target/`、`.m2/`、`frontend/node_modules/`、Docker 数据卷没有被提交。
- 真实 API Key、知识库 ID、Mem0 Key、域名、公网 IP、证书和私钥没有出现在仓库中。
- 如果任何 Key 曾经出现在公开位置，请立即到对应平台控制台轮换。

推荐命令：

```powershell
git status --short --ignored
git add .
git status --short
```

提交前再执行一次敏感信息扫描：

```powershell
rg -n "sk-|m0-|api[_-]?key|secret|password|token|你的公网IP|你的域名" . --glob "!**/node_modules/**" --glob "!**/target/**" --glob "!logs/**"
```

如果扫描结果只包含模板占位符或代码变量名，可以继续提交。

首次推送示例：

```powershell
git add .
git commit -m "feat: campus smart service multi-agent assistant"
git branch -M main
git remote add origin https://github.com/<your-name>/<your-repo>.git
git push -u origin main
```
