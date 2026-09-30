# 发布与安装

前端、后端和 Agent 是三个独立发布单元。每个组件的 `release.sh` 在构建机执行，生成带版本号的 `tar.gz` 和对应的 SHA-256 文件；后端包提供 `start.sh` / `shutdown.sh`，前端和 Agent 包提供 `install.sh`，在目标 Linux 主机解压后执行。

## 生成发布包

三个脚本默认执行测试和构建，产物写入仓库根目录的 `release/`：

```bash
cd frontend
./deploy/release.sh

cd ../backend
export JAVA_HOME=/path/to/jdk-17
export PATH="$JAVA_HOME/bin:$PATH"
./deploy/release.sh

cd ../agent
export JAVA_HOME=/path/to/jdk-8
export PATH="$JAVA_HOME/bin:$PATH"
./deploy/release.sh
```

默认版本来自各组件的 `package.json` 或 `pom.xml`，Agent 还会校验运行时上报版本与 POM 一致。`--version` 只能用于断言源码版本，不能覆盖版本；发版前应先同步修改组件版本。可以使用 `--output-dir /path/to/output` 更改输出目录；只有已经单独通过测试的 CI 阶段才应使用 `--skip-tests`。

发布目录包含：

```text
release/
  logmonitor-frontend-1.1.5.tar.gz
  logmonitor-frontend-1.1.5.tar.gz.sha256
  logmonitor-backend-1.1.5.tar.gz
  logmonitor-backend-1.1.5.tar.gz.sha256
  logmonitor-agent-1.1.5.tar.gz
  logmonitor-agent-1.1.5.tar.gz.sha256
```

传输到目标机后先校验，例如：

```bash
sha256sum -c logmonitor-backend-1.1.5.tar.gz.sha256
```

## 安装后端

后端目标机需要 Bash、完整 JDK 17、`nohup` 和 `curl`。安装脚本安装纯 API JAR、nohup 控制脚本、两个安全 application 模板和 MySQL 运维脚本，不会覆盖已有配置；默认运行用户是执行 `sudo sh start.sh` 的原登录用户：

```bash
tar -xzf logmonitor-backend-1.1.5.tar.gz
cd logmonitor-backend-1.1.5
sudo START_PROCESS=false sh start.sh
sudoedit /etc/logmonitor/backend.env
sudoedit /etc/logmonitor/application.yml
sudoedit /etc/logmonitor/application-prod.yml
# DBA 先执行 mysql/logm-init.sql 或 mysql/migrations/ 中尚未应用的增量脚本
sudo sh start.sh
/opt/logmonitor/backend/logmonitor-backend.sh status
curl http://127.0.0.1:8080/api/health
```

必须替换 `backend.env` 中的数据库密码、管理员初始密码和 LLM 主密钥占位符。两个 application 文件从安全 example 首次安装并由控制脚本作为外部配置加载，后续安装不会覆盖。生产启动不会执行 Flyway 迁移；它只读校验 MySQL 8.0.36+、InnoDB 和 `schema_metadata`，因此 DBA 必须先完成数据库初始化或升级。控制脚本使用 `nohup java -jar` 启动，并在 60 秒内轮询 `/api/health`；校验失败、进程退出或健康检查超时会输出最近日志、停止新进程并返回失败。若数据库和配置已提前准备好，可用 `sudo sh start.sh` 在安装完成后直接启动（默认 START_PROCESS=true）。两个入口支持 `sh` 调用并自动切换到 Bash；`shutdown.sh` 不删除配置或数据。自定义 APP_DIR 时启动与停止需使用相同值。升级正在运行的 systemd 或 nohup 版本时，应先停止后端并完成数据库升级；安装脚本仍会保留配置和数据，并将旧服务迁移为 nohup。

常用控制命令：

```bash
sudo sh shutdown.sh
/opt/logmonitor/backend/logmonitor-backend.sh restart
/opt/logmonitor/backend/logmonitor-backend.sh logs
```

发布包的 `mysql/logm-init.sql` 只用于空库，`mysql/migrations/V{n}__*.sql` 用于从已有版本逐版升级。安装脚本只复制这些 SQL，不执行数据库写操作。

## 安装前端

前端目标机需要 Nginx。默认安装到 `/opt/logmonitor/frontend/releases/{版本}`，并原子更新 `current` 软链接；生成的 Nginx server 监听 `127.0.0.1:8081`，把 `/api` 代理到本机后端 `127.0.0.1:8080`：

```bash
tar -xzf logmonitor-frontend-1.1.5.tar.gz
cd logmonitor-frontend-1.1.5
sudo SERVER_NAME=logmonitor.internal BACKEND_URL=http://127.0.0.1:8080 ./install.sh
curl -I http://127.0.0.1:8081/
```

生产环境必须由 TLS 反向代理把浏览器流量转发到 `127.0.0.1:8081`，并保持页面与 `/api` 同源。需要调整内部监听时可设置 `LISTEN_ADDRESS` 和 `LISTEN_PORT`；已有平台统一管理 Nginx 时可设置 `INSTALL_NGINX_CONFIG=false`，只安装静态资源。

## 安装 Agent

Agent 目标机需要完整 JDK 8 和 `nohup`。安装脚本保留已有 `agent.json`、状态和 spool；首次安装后先确保执行安装的原登录用户对日志根目录具有只读权限，再注册并启动：

```bash
tar -xzf logmonitor-agent-1.1.5.tar.gz
cd logmonitor-agent-1.1.5
sudo ./install.sh
/usr/bin/java -jar /opt/logmonitor-agent/logmonitor-agent.jar \
  configure --config /etc/logmonitor-agent/agent.json
/usr/bin/java -jar /opt/logmonitor-agent/logmonitor-agent.jar \
  check --config /etc/logmonitor-agent/agent.json --data /var/lib/logmonitor-agent
/opt/logmonitor-agent/logmonitor-agent.sh start
/opt/logmonitor-agent/logmonitor-agent.sh status
```

远程中心暂未配置 HTTPS 时，注册命令需增加 `--allow-http`。该选项会持久化到 `agent.json`，后续 `check` 和运行无需重复传入；HTTP 会明文传输注册密码、Agent Token 和日志数据，只能作为临时过渡。使用安装脚本交互注册时可设置 `ALLOW_HTTP=true`，例如 `sudo CONFIGURE_AGENT=true ALLOW_HTTP=true ./install.sh`。

`check` 会校验注册配置、允许根目录和数据目录，并使用 Agent Token 请求中心 `/api/agent/v1/config`；任一检查失败都会阻止启动。也可以用 `sudo CONFIGURE_AGENT=true START_PROCESS=true ./install.sh` 交互注册并立即启动。升级运行中的 Agent 时，脚本先通过预检，再保留配置、状态和队列迁移到 nohup。

## 安装参数与隔离验证

三个安装脚本都支持 `DESTDIR=/tmp/root`，此时只把文件写入临时根目录，不检测目标 JDK、不停止进程或重载 Nginx，用于 CI 和安装布局验证。

- 后端：`APP_USER`、`APP_GROUP`、`APP_DIR`、`CONFIG_DIR`、`DATA_DIR`、`LEGACY_SERVICE_DIR`、`JAVA_BIN`、`START_PROCESS`；控制脚本另支持 `STARTUP_TIMEOUT_SECONDS`、`HEALTH_URL` 和 `STOP_TIMEOUT_SECONDS`。
- 前端：`FRONTEND_BASE`、`NGINX_CONF_DIR`、`LISTEN_ADDRESS`、`LISTEN_PORT`、`SERVER_NAME`、`BACKEND_URL`、`INSTALL_NGINX_CONFIG`、`RELOAD_NGINX`。
- Agent：`APP_USER`、`APP_GROUP`、`APP_DIR`、`CONFIG_DIR`、`DATA_DIR`、`LEGACY_SERVICE_DIR`、`JAVA_BIN`、`CONFIGURE_AGENT`、`ALLOW_HTTP`、`START_PROCESS`；控制脚本另支持 `STARTUP_WAIT_SECONDS` 和 `STOP_TIMEOUT_SECONDS`。

安装脚本面向 Linux。`APP_USER` 默认取 `SUDO_USER`，直接以 root 安装且未显式指定非 root 用户时会拒绝执行；兼容识别旧 `ENABLE_SERVICE` 变量，但新部署应使用 `START_PROCESS`。安装程序会停用并删除旧 systemd unit，避免重复启动。nohup 不提供开机自启、崩溃自动拉起或 systemd 资源隔离，主机重启后需要手工执行 `start` 或由外部运维平台调用。`DESTDIR` 模式仅验证文件布局，不能替代目标环境中的 JDK、Nginx、TLS、权限和进程恢复验证。

### Agent 上传并发

新 Agent 默认两个上传线程，可在启动进程的环境中设置 `AGENT_UPLOAD_WORKERS=1`（支持 1–4）。例如 `AGENT_UPLOAD_WORKERS=1 /opt/logmonitor-agent/logmonitor-agent.sh start`。不修改 agent.json，回退到旧发布包可继续使用原配置与 spool。配置、心跳按既有周期独立执行。

## 实时目录选择

1.1.2 起支持中心本机/Agent 的逐层目录浏览。先升级中心和前端，再升级需要浏览的 Agent；旧 Agent 或离线节点保留手动输入。无需数据库迁移、开放 Agent 入站端口或修改 agent.json。目录通道使用既有中心地址与 Token，独立于上传/心跳。

## Docker 发布包

新增 [Docker 部署说明](docker/README.md)。`deploy/release-all.sh` 使用 BACKEND_JAVA_HOME（17）、AGENT_JAVA_HOME（8）依次执行三组件测试/构建，再调用 `deploy/docker/release.sh` 打包镜像、Compose、安全配置模板和 MySQL 运维 SQL。Docker 包默认 `logmonitor-docker-1.1.5-linux-amd64.tar.gz`，可设置 PLATFORM=linux/arm64；版本沿用三组件源码，正式发布记录保持 v1.0.0。该包包含离线 images.tar 及 SHA-256 校验文件，不包含运行凭证或本地配置。
