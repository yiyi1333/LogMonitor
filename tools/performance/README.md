# 第一阶段隔离环境压测

只连接独立、可丢弃的测试中心和数据库。脚本创建匿名 Agent/来源，写入日志并启动真实 Agent，不自动删除服务端数据。每次运行使用新目录；目录包含 Token，权限为 0700，配置为 0600，禁止提交或分享该目录。报告不包含凭证。

中心端使用 JDK 17，Agent 使用 JDK 8，生成器使用 Python 3 标准库。中心与 MySQL 需要在隔离环境单独启动；生产 profile 按既有流程手工初始化 V14，压测工具不修改数据库配置或结构。

```bash
mvn -o -f backend/pom.xml -DskipTests package
JAVA_HOME="$AGENT_JAVA_HOME" mvn -o -f agent/pom.xml -DskipTests package
export BENCH_AGENT_JAVA="$AGENT_JAVA_HOME/bin/java"
export BENCH_USER=benchmark
# BENCH_PASSWORD 由本地环境提供，不写入命令历史或文件。
python3 tools/performance/benchmark.py --isolated \
  --url "$BENCH_URL" --agent-jar agent/target/logmonitor-agent.jar \
  --output /tmp/logmonitor-normal --agents 100 --gb-per-day 100 --seconds 7200
```

通过 `--burst-start 3600 --burst-seconds 600 --burst-multiplier 10` 增加突发。分别使用 `--error-ratio .001/.01/.1`、`--hot-source`、`--stack-lines 100`、`--fingerprints 10000 --uris 10000` 覆盖偏斜和高基数。容量探索分别指定 `--gb-per-day 100/300/1000`。先以相同参数运行旧发布包并保存结果，再运行新发布包；不能混用两个中心的数据。

`report.json` 包含查询 P95、可见延迟 P95、生成器调度延迟、spool 采样与精确访问/错误数对照。生成器按开放到达模型每秒持续写入，不等待 ACK；生成器自身落后、Agent 进程退出或校验失败时该轮不得当作容量通过。`expected.json` 包含事件数量、匿名错误类别及首次/最近时间用于进一步核对。默认每台 Agent 两个来源，10 名查询用户每 10 秒查询一次。探针不增加额外事件，使用唯一访问 URI 测量可见性。

在中心、MySQL 和生成器主机分别记录规格及版本、网络链路，Linux 使用 `pidstat -rud 1`、`iostat -xz 1`，通过 JDK 的 `jstat -gcutil PID 1000` 采集 GC。使用 MySQL 客户端的 `--defaults-extra-file` 或受保护的凭证环境运行 `mysql-observe.sql`；不要在命令行传密码。该脚本仅会话变量及只读查询，不自动开启全局慢日志。

180 天数据预置和重启/断网/清理竞争必须在独立环境按验收手册执行；短时 H2 烟测不证明 MySQL 容量。冷启动和预热运行分别记录。长时验收标准见 `docs/performance/PHASE1.md`。
