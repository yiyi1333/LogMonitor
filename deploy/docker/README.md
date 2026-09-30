# Docker 部署（源码 1.1.5）

需要 Docker Engine 与 Compose v2（支持 `bind.create_host_path`）；构建机实测 Engine 28.3.0 / Compose 2.38.2。发布包包含 backend、frontend、agent 镜像和可选 MySQL 8.4 镜像，可在无镜像仓库连接的主机导入。`PLATFORM` 文件表示架构，默认打包 Linux amd64，构建时 `PLATFORM=linux/arm64` 可生成 arm64 包。只导入与主机架构匹配的包。

## 中心端

解压并校验发布包后，在包目录执行：

```bash
docker load -i images.tar
cp .env.example .env
chmod 600 .env
mkdir -p logs
# 编辑 .env：数据库、管理员密码和 LLM_CONFIG_MASTER_KEY 必须换成真实值
# 生成 LLM 主密钥：openssl rand -base64 32
docker compose config --quiet
docker compose up -d --wait
curl http://127.0.0.1:8081/api/health
```

默认连接现有 MySQL 8.0.36+，DB_NAME 默认 `logm`。DB_HOST 设置数据库地址；`host.docker.internal` 映射到宿主机网关，但宿主数据库仍需允许网关访问。已有库由 DBA 先执行 `mysql/migrations/` 未应用的迁移；生产容器仅校验 V14 schema，不自动升级数据库。不得对已有库执行空库脚本。

需要独立空库时，使用包内可选配置，MySQL 数据保存到命名卷、不开放宿主端口：

```bash
# .env 中 MYSQL_ROOT_PASSWORD 与 DB_PASSWORD 使用不同强密码
docker compose -f compose.yaml -f compose.mysql.yaml config --quiet
docker compose -f compose.yaml -f compose.mysql.yaml up -d --wait
```

空卷首次启动使用 `mysql/logm-init.sql` 初始化 `logm`；已有卷不会重跑脚本，也不会因修改 .env 自动更改数据库密码。中心日志通过 CENTER_LOG_DIR 只读挂载到容器 `/logs`，界面路径必须使用容器路径。后端 UID/GID 10001 必须有日志读取及目录遍历权限。允许根只覆盖 `/logs`，禁止直接挂载宿主 `/`。目录选择也只浏览容器内可见目录。

仅前端默认监听宿主 `127.0.0.1:8081`，由宿主 TLS 代理转发，后端仅 Compose 内网可见。远端 Agent 应连接 TLS 公开域名。默认 Secure Cookie 保持开启；隔离环境只用 HTTP 验证时，可将 SESSION_COOKIE_SECURE=false，生产 TLS 环境保持 true。`BACKEND_JAVA_OPTIONS` 可调整堆参数，`INGEST_MAX_INFLIGHT` 可调整准入并发。没有新增统计/ACK 语义或数据库迁移。

## 每台采集主机的 Agent

在该主机解压相同版本且匹配架构的包，导入镜像并准备 .env（Agent 独立 Compose，不需要中心数据库参数）：

```bash
docker load -i images.tar
cp .env.example .env
chmod 600 .env
mkdir -p agent-config logs
sudo chown 10001:10001 agent-config
# 修改 AGENT_LOG_DIR 为本机业务日志根，只读挂载到容器 /logs
docker compose -f compose.agent.yaml run --rm agent configure --config /config/agent.json
# 交互输入中心 HTTPS 地址、用户名/密码、唯一 Agent 名称、宿主展示 IP 和允许根 /logs
docker compose -f compose.agent.yaml run --rm agent check --config /config/agent.json --data /data
docker compose -f compose.agent.yaml up -d
```

Agent UID/GID 为 10001，宿主日志目录需授予只读和目录遍历权限。`agent-config` bind 保存注册 Token；`agent-data` 命名卷保存游标/spool，首次挂载继承镜像中 10001 的目录所有权。不要在不同 Agent 间共享配置或数据卷。容器主机名不等于宿主 IP，注册时显式填写展示 IP。来源路径使用 `/logs/...`。自定义额外日志挂载时，同时维护允许根和注册配置；复用已有宿主 Agent 配置时必须保持路径一致、停止原进程，避免双采集。

HTTP 临时测试可在 configure 末尾加 `--allow-http`，生产使用 HTTPS。上传默认两个线程，可在 .env 设置 AGENT_UPLOAD_WORKERS=1–4。Agent 无入站端口；配置、心跳、目录通道保持现有独立调度。

## 升级、停止和回退

先备份 MySQL 和 Agent 配置/数据。升级前人工执行需要的数据库迁移，再导入新镜像、修改 LOGMONITOR_VERSION，使用最初启动时相同的 Compose 文件组合执行 `up -d --wait`（Agent 用 `up -d`）。MySQL 镜像更新须遵循 DBA 的数据库升级流程。回退重新导入上一版镜像并恢复版本号，保留原卷和 bind 目录；不自动回退数据库 schema。

```bash
docker compose logs --tail=100 backend frontend
docker compose stop
# 用了 MySQL 时，停止命令也带 -f compose.yaml -f compose.mysql.yaml
docker compose -f compose.agent.yaml logs --tail=100 agent
docker compose -f compose.agent.yaml stop
```

`down` 不删除命名卷；`down -v` 会删除数据库或 Agent 队列/游标，不用于升级或回退。不要启动多个同配置 Agent。容器使用 restart unless-stopped，Docker 重启后恢复；健康检查失败标记 unhealthy，不自动重启仍活着的进程。日志按 10MiB×3 轮转。运行密钥只放目标机 .env/agent.json，不烘焙进镜像或发布包。

## 从源码打包

在仓库根执行（各发布脚本均运行测试）：

```bash
export BACKEND_JAVA_HOME=/path/to/jdk-17
export AGENT_JAVA_HOME=/path/to/jdk-8
# PATH 中先放 Node 20.19+、22.12+ 或更新 LTS
./deploy/release-all.sh
```

默认得到三种 Linux 安装包及 `logmonitor-docker-1.1.5-linux-amd64.tar.gz` 和四份 SHA-256。Docker 包由已校验的三个安装包生成；不读取本机运行配置或 .env。基础镜像实际 ID/digest 与架构记录在 IMAGES.txt。各镜像可在包目录用 Dockerfile 重新构建，构建上下文通过白名单排除运行密钥、数据库和队列；部署配置仅使用应用版本号，不意味着正式发布（正式记录仍为 v1.0.0）。
