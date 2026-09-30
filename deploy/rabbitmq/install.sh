#!/usr/bin/env bash
# Install local Ubuntu .deb packages. No RabbitMQ repository is added.
set -Eeuo pipefail

usage() {
  cat <<'EOF'
用法: sudo bash install.sh [--online-deps] [--management] 安装包目录
默认离线：目录须包含 rabbitmq-server、兼容 Erlang 和全部缺失依赖的 .deb。
--online-deps  允许从系统现有 APT 源下载缺失依赖（不会添加软件源）。
--management   启用管理插件，HTTP 端口 15672；不创建账号或修改防火墙。
如目录包含 SHA256SUMS，会在安装前校验；校验文件应来自可信渠道。
仅用于首次安装或重装同版本，不执行已有 RabbitMQ 的版本升级/降级。
EOF
}
die() { echo "错误: $*" >&2; exit 1; }
online=false
management=false
package_dir=''
while (($#)); do
  case "$1" in
    --online-deps) online=true ;;
    --management) management=true ;;
    -h|--help) usage; exit 0 ;;
    --*) die "未知参数: $1" ;;
    *) [[ -z "$package_dir" ]] || die '只能指定一个安装包目录'; package_dir=$1 ;;
  esac
  shift
done
[[ -n "$package_dir" ]] || { usage; exit 1; }
[[ $EUID -eq 0 ]] || die '请使用 sudo 或 root 执行'
[[ -r /etc/os-release ]] || die '无法识别操作系统'
source /etc/os-release
[[ ${ID:-} == ubuntu ]] || die '仅支持 Ubuntu'
for command_name in dpkg dpkg-deb dpkg-query apt-get systemctl timeout journalctl; do
  command -v "$command_name" >/dev/null || die "缺少命令: $command_name"
done
[[ -d /run/systemd/system ]] || die '需要运行中的 systemd（不支持普通容器/未启用 systemd 的 WSL）'
[[ -d "$package_dir" ]] || die "安装包目录不存在: $package_dir"
package_dir=$(cd -- "$package_dir" && pwd -P)
shopt -s nullglob
packages=("$package_dir"/*.deb)
((${#packages[@]})) || die '目录中没有 .deb 安装包'
if [[ -f "$package_dir/SHA256SUMS" ]]; then
  command -v sha256sum >/dev/null || die '缺少 sha256sum'
  (cd -- "$package_dir" && sha256sum --strict --check SHA256SUMS)
fi
host_arch=$(dpkg --print-architecture)
rabbit_count=0
rabbit_version=''
seen_packages=()
for package in "${packages[@]}"; do
  name=$(dpkg-deb -f "$package" Package)
  arch=$(dpkg-deb -f "$package" Architecture)
  version=$(dpkg-deb -f "$package" Version)
  [[ "$arch" == all || "$arch" == "$host_arch" ]] || die "$name 的架构 $arch 与主机 $host_arch 不符"
  for seen in "${seen_packages[@]:-}"; do
    [[ "$seen" != "$name" ]] || die "目录中存在重复包: ${name}；每个包仅保留一个版本"
  done
  seen_packages+=("$name")
  if [[ "$name" == rabbitmq-server ]]; then
    rabbit_count=$((rabbit_count + 1))
    rabbit_version=$version
  fi
done
[[ $rabbit_count -eq 1 ]] || die '目录必须包含一个 rabbitmq-server 安装包'
installed=$(dpkg-query -W -f='${Status}\t${Version}' rabbitmq-server 2>/dev/null || true)
if [[ "$installed" == "install ok installed"$'\t'* ]]; then
  installed_version=${installed##*$'\t'}
  [[ "$installed_version" == "$rabbit_version" ]] || die "已有 RabbitMQ $installed_version，拒绝直接升级/降级到 $rabbit_version"
fi
echo "Ubuntu ${VERSION_ID:-未知} / ${host_arch}；安装 RabbitMQ $rabbit_version"
echo '请确认包来自可信渠道，Erlang 与 RabbitMQ 版本兼容且适用于此 Ubuntu 版本。'
apt_options=(-y --no-remove)
if "$online"; then
  apt-get update
else
  apt_options+=(--no-download)
fi
# APT resolves the supplied local packages together, avoiding partial dpkg installs.
apt-get "${apt_options[@]}" --simulate install "${packages[@]}" || die '依赖预检查失败；补齐兼容的 Erlang/系统依赖包，或使用 --online-deps'
apt-get "${apt_options[@]}" install "${packages[@]}" || die '安装失败；查看上方 APT 错误，离线模式请补齐缺失安装包'
if "$management"; then
  rabbitmq-plugins enable --offline rabbitmq_management
fi
systemctl enable --now rabbitmq-server
# Ensure an already-running same-version installation also loads plugin changes.
if "$management"; then
  systemctl restart rabbitmq-server
fi
ready=false
for ((attempt=1; attempt<=30; attempt++)); do
  if timeout 5 rabbitmq-diagnostics -q ping >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 2
done
if ! "$ready"; then
  journalctl -u rabbitmq-server -n 80 --no-pager >&2 || true
  die 'RabbitMQ 未就绪；检查 Erlang 兼容性和上方服务日志'
fi
rabbitmq-diagnostics -q check_running
rabbitmq-diagnostics -q server_version
echo 'RabbitMQ 安装完成，已启动并设置开机自启。'
if "$management"; then
  echo '管理页面: http://服务器IP:15672；guest 仅允许本机登录，远程访问需另建账号。'
fi
