# Docker 部署验证（1.1.4）

验证日期：2026-09-30。仅本机隔离环境，不部署生产，也不更新正式发布记录 v1.0.0。

## 构建和自动化

- 后端：Corretto JDK 17，72 项测试中 65 通过、7 跳过（6 项可选 MySQL 集成测试、1 项外部模型烟测），测试及 clean package 成功。
- Agent：Corretto JDK 8，23 项测试全部通过，运行时版本与 POM 1.1.4 一致。
- 前端：Node 24.19.0，48 项测试全部通过，类型检查及 Vite 构建成功。保留既有 bundle 大小提示，不影响构建。
- 后端入口回归 3 项通过；新增发布脚本 Bash 语法检查通过。
- 三组件传统安装包和 Docker 离线包全部校验 SHA-256。

## 容器端到端

宿主为 macOS arm64，Docker Engine 28.3.0 / Compose 2.38.2。打包及烟测目标为 Linux amd64，通过 Docker Desktop 架构模拟运行，不作为原生 Linux 性能或容量结论。arm64 打包参数已提供，但本次不宣称完成 arm64 端到端验收。

Dockerfile 固定 Corretto 17、Corretto 8、Nginx 基础镜像 manifest digest，离线包包含实际应用镜像 ID/架构和 MySQL 8.4 镜像 ID/digest。包内导入后检查三应用镜像标签、版本和架构一致。

`tools/deploy/docker_smoke.py` 使用临时凭证、临时日志根、唯一 Compose 项目与独立 MySQL/Agent 数据卷，验证：

- 从离线 images.tar 导入镜像，MySQL 空库初始化至 V14，后端/前端健康；SPA 深层路由与 /api 代理正常。
- 实际容器 Agent 交互 configure 和 check 成功，Token 写入挂载配置，版本上报正确。
- 中心与 Agent 目录浏览正常，后端及 Agent UID 为 10001，日志挂载无法写入。
- 实际扫描匿名日志、gzip 上传、MySQL 入库与查询，20 条访问正确统计；目录查询期间心跳推进。
- 重启中心与 Agent 后注册、来源、统计仍在；追加一条访问后总量精确为 21，没有重读造成重复统计。
- 最后仅删除本轮唯一隔离项目及其临时卷，临时运行凭证不进入仓库或发布包。

复现（先在支持 Docker 的构建机生成发布包）：

```bash
python3 tools/deploy/docker_smoke.py --isolated \
  --package release/logmonitor-docker-1.1.4-linux-amd64.tar.gz
```

命令会创建并清理独立测试项目；不会连接已有生产部署。真实生产 TLS、宿主日志权限、既有数据库升级、资源限制和容量仍需在目标环境按操作说明验证。
