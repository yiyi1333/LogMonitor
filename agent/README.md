# LogMonitor Agent

远端日志采集程序，运行于 Linux x86_64 和 JDK 8。Agent 只读取注册时声明的允许根目录，使用 HTTPS 向中心端推送 gzip 增量批次。

```bash
export JAVA_HOME=/path/to/jdk8
mvn clean package
java -jar target/logmonitor-agent.jar configure
java -jar target/logmonitor-agent.jar check
java -jar target/logmonitor-agent.jar run
java -jar target/logmonitor-agent.jar status
```

默认配置位于 `/etc/logmonitor-agent/agent.json`，运行状态与 5GB 磁盘队列位于 `/var/lib/logmonitor-agent`。首次注册需要输入 LogMonitor 用户名和密码，注册成功后本地只保存独立 Agent 令牌。

生成独立发布包：

```bash
./deploy/release.sh
```

脚本执行测试和构建，在仓库 `release/` 生成 `logmonitor-agent-{版本}.tar.gz` 及 SHA-256 文件。目标 Linux 服务器解压后执行包内 `sudo ./install.sh`，再按照脚本输出注册，并通过 `/opt/logmonitor-agent/logmonitor-agent.sh start` 以 nohup 方式启动。启动前会运行 `check` 校验配置、目录、Token 和中心连接。完整参数和升级行为见 [发布与安装说明](../deploy/README.md#安装-agent)。

默认运行用户是执行 `sudo ./install.sh` 的原登录用户；请确保该用户能读取注册时声明的根目录。

生产中心地址应使用证书包含 IP SAN 的 HTTPS，并确保签发 CA 已导入 Agent 所用 JDK 8 信任库。`localhost` 默认允许 HTTP；临时连接远程 HTTP 中心时，注册命令必须显式添加 `--allow-http`，该授权会写入 `agent.json` 供后续预检和运行使用。远程 HTTP 会明文传输注册密码、Agent Token 和日志数据，不应用于正式生产。
