# Ubuntu RabbitMQ 安装包安装

此工具独立于 LogMonitor，平台运行不依赖 RabbitMQ。脚本仅用于 Ubuntu/systemd，安装本地 `.deb`，不添加 RabbitMQ APT 源、不修改防火墙、不创建账号。已有不同版本的 RabbitMQ 会拒绝安装，升级应另行备份并遵循官方升级路径。

从 [官方发布页](https://github.com/rabbitmq/rabbitmq-server/releases) 获取目标版本的 `rabbitmq-server_<版本>_all.deb`，根据 [Erlang 兼容表](https://www.rabbitmq.com/docs/which-erlang) 准备匹配的 Erlang 包。所有依赖包必须适用于目标 Ubuntu 版本和架构，每个包仅保留一个版本；amd64 和 arm64 包不可混用。不要仅依赖 RabbitMQ 包的最低 Erlang 依赖声明来判断运行时兼容性。

将 RabbitMQ、Erlang 和系统依赖的 `.deb` 放在同一个目录。完全离线时须在相同 Ubuntu 版本/架构的环境准备完整依赖集；仅下载 RabbitMQ 一个包不能保证离线安装成功。包目录中的全部 `.deb` 都会被安装，不应混放其他软件包。

```bash
# 将脚本和安装包复制到 Ubuntu 服务器；在仓库根目录执行
sudo bash deploy/rabbitmq/install.sh --management /opt/rabbitmq-packages

# 如允许联网，只需提供 RabbitMQ 和兼容 Erlang 包，缺失依赖从现有 APT 源补齐
sudo bash deploy/rabbitmq/install.sh --online-deps --management /opt/rabbitmq-packages
```

目录可附带来自可信渠道的 `SHA256SUMS`（文件名相对包目录），脚本会校验。自行生成的校验文件只能检查传输完整性，不能证明来源可信。APT 安装本地包也不等于验证发布者签名。安装前会检查包元数据/架构/重复版本并模拟依赖解析，实际安装禁止移除现有包；这不是事务回滚，实际安装中断仍可能需要人工修复。

管理页面地址为 `http://服务器IP:15672`，AMQP 默认端口为 `5672`。脚本不开放防火墙端口。默认 `guest` 仅允许本机访问；远程管理可在服务器上交互创建账号，避免把密码写入脚本或命令历史：

```bash
sudo rabbitmqctl add_user admin
sudo rabbitmqctl set_user_tags admin administrator
sudo rabbitmqctl set_permissions -p / admin '.*' '.*' '.*'
sudo rabbitmq-diagnostics ping
sudo journalctl -u rabbitmq-server -n 100 --no-pager
```

上述权限用于管理账号，应用账号应按所用虚拟主机单独授权。启用管理插件会重启服务；重复执行同版本安装也可能由包维护脚本触发重启，请在维护窗口执行。

参考：[官方 Debian/Ubuntu 安装说明](https://www.rabbitmq.com/docs/install-debian)、[账号权限](https://www.rabbitmq.com/docs/access-control)。
