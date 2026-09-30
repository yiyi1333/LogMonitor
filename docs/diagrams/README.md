# LogMonitor 架构图

本图由 Archify 2.17 的 architecture 类型生成，中文节点与关系来自实际代码/部署配置；不是容量承诺，也不是运行中的基础设施探测。

- [PNG](logmonitor.png)：README 嵌入，完整细节浅色静态图。
- [SVG](logmonitor.svg)：自包含矢量导出，无交互控件。
- [HTML](logmonitor.html)：下载后在浏览器打开；双主题、聚焦、搜索及导出。
- [JSON 图源](logmonitor.architecture.json)：唯一可编辑图源。

## 代码证据

| 图中事实 | 实现 |
| --- | --- |
| Web 入口、同源 API 和容器内网 | ../../deploy/docker/nginx.conf、../../deploy/docker/compose.yaml |
| 本机增量读取和中心统一解析存储 | ../../backend/src/main/java/com/logmonitor/service/LogCollectorService.java、LogStorageService.java |
| 来源顺序、原子 spool、独立配置/心跳/目录调度 | ../../agent/src/main/java/com/logmonitor/agent/AgentRuntime.java |
| Bearer、gzip 批次及事务提交后 ACK | ../../backend/src/main/java/com/logmonitor/controller/AgentProtocolController.java、../../backend/src/main/java/com/logmonitor/service/AgentIngestService.java |
| Session、CSRF 和来源权限 | ../../backend/src/main/java/com/logmonitor/config/SecurityConfig.java、../../backend/src/main/java/com/logmonitor/controller/SourceController.java |
| MySQL、180 天保留及脱敏 LLM 调用 | ../../backend/config/application.example.yml、../../backend/src/main/java/com/logmonitor/service/RetentionService.java、LlmClientService.java |

Agent 不承担业务解析，spool 是进程内持久队列而非外部消息中间件；LLM 为用户按需调用，不在每条日志的入库路径上。数据库箭头概括查询与持久化，上传箭头为入站批次；ACK、配置、心跳与目录长轮询的反向消息在文字与交互图说明卡中说明，避免将请求/返回混为额外组件。MySQL 为生产存储；H2 仅本地演示和测试。HTTPS 表示生产外部入口，Compose 内部受控内网代理使用 HTTP。

## 验证记录

- deterministic validation：9/9 showcase，0 errors，0 warnings。
- browser_evidence：passed，使用交付 HTML 原字节执行 visual-check。
- visual_review：passed，图像检查了浅色/深色、1440×900 与 2048×1320；无节点遮挡、关系穿越或标签遮盖其他连线，静态 PNG 导出无工具栏。
- correction_rounds：1（浏览器发现纵向溢出后收紧层间距）。
- 1440×900、1600×1000、1920×1080、2048×1320 的 scrollWidth/scrollHeight 均未超出窗口；额外检查两个端点尺寸的双主题。
- HTML 导出按钮实际生成 PNG / SVG，双主题切换、节点聚焦、搜索与详情关闭已在浏览器验证；页面无脚本错误。该记录不声称所有交互模式均完成回归。

字节凭证：

```text
specification_sha256: 3da3b190b4579301263cbb12ca644f7ea4830b67f743718ff84a26faa7f0c9d3
specification_bytes: 4515
artifact_sha256: 4c1e0abb6b7c5fdea295492f987edec2ad429c587d5f362aebbec8e7440cefd7
artifact_bytes: 808336
```

## 重新生成

设置 ARCHIFY_HOME 为安装的 Skill 目录；从仓库根执行，Node 路径需可用：

```bash
node "$ARCHIFY_HOME/bin/archify.mjs" validate architecture docs/diagrams/logmonitor.architecture.json --quality showcase --json
node "$ARCHIFY_HOME/bin/archify.mjs" deliver architecture docs/diagrams/logmonitor.architecture.json docs/diagrams/logmonitor.html --quality showcase --json
node "$ARCHIFY_HOME/bin/archify.mjs" visual-check docs/diagrams/logmonitor.html --json
```

交付成功后用 HTML 的导出菜单生成浅色 PNG/SVG，并检查实际导出。SVG 导出后仅规范化行尾空白，不改变图形、样式或语义。更新图源后重新记录 hash 和浏览器/视觉结果；校验失败的旧 HTML 不可作为当前候选的截图依据。测试截图、联系表、原始本机路径凭证留在本机，不加入仓库。
