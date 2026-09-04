# MySQL 数据库脚本

## 当前基线

`logm-init.sql` 是 MySQL 8.0.36+ / InnoDB 全新数据库的当前最终态初始化脚本，对应 Flyway V14，包含 20 张表。它只用于空数据库，不是升级脚本，也不创建或写入 `flyway_schema_history`。

当前表按职责分为：

- 身份与采集配置：`app_user`、`collector_agent`、`agent_allowed_root`、`log_source`、`app_setting`。
- 访问、错误与游标：`api_access_minute`、`error_group`、`error_occurrence`、`collector_checkpoint`。
- LLM 配置与分析：`llm_provider_config`、`llm_model_config`、`user_llm_preference`、`llm_system_setting`、`ai_analysis`、`error_occurrence_ai_analysis`。
- 去重与采集元数据：`access_event_dedup`、`access_dedup_baseline`、`source_namespace_migration`、`agent_ingest_batch`。
- 结构版本：`schema_metadata`，固定以 `component=logmonitor` 记录当前版本。

生产数据库必须由 DBA 在服务启动前执行：

```bash
mysql -h DB_HOST -u ADMIN_USER -p < deploy/mysql/logm-init.sql
```

脚本最后才写入 V14 版本标记。生产服务启动时只读校验版本、MySQL 版本和全部表的 InnoDB 引擎，不运行 `migrate`、`repair`、`baseline` 或 `clean`。开发和测试环境仍由 Flyway 自动执行迁移。

## 增量迁移规则

V1 至 V13 是已发布历史，禁止修改、重命名或删除。历史脚本中曾使用带版本后缀的重建中间表，并且 V13 是 Java 迁移；保留它们是为了维持现有数据库的 Flyway 校验结果，不代表后续规范。

从 V14 开始，每次数据库变更必须：

1. 在 `backend/src/main/resources/db/migration/` 新增一个跨 H2/MySQL 的 `V{版本}__{说明}.sql`，或在 `backend/src/main/resources/db/mysql-migration/` 新增一个仅 MySQL 使用的同格式 SQL。
2. 通过 `ALTER TABLE 原表名 ...` 直接调整已有表，不创建 `表名_v14`、`表名_v15` 等带版本号的表；新增业务表也使用稳定的领域名称。
3. 把更新 `schema_metadata` 的语句放在迁移最后；MySQL DDL 可能隐式提交，版本标记只能表示前面的语句已全部成功。
4. 在同一提交中把最终结构合并进 `logm-init.sql`，并同步基线版本、发布说明和架构文档。
5. 运行后端测试。`DatabaseMigrationPolicyTest` 会检查 SQL 类型、稳定表名、最终版本写入和所有版本声明。

生产升级时先停止后端并备份数据库，查询当前版本，再从发布包按顺序执行所有缺失脚本：

```sql
SELECT schema_version FROM schema_metadata WHERE component = 'logmonitor';
```

```bash
mysql -h DB_HOST -u ADMIN_USER -p DB_NAME < mysql/migrations/V14__schema_metadata.sql
```

每个脚本只执行一次。脚本失败时保持服务停止，检查已完成的 DDL 后由 DBA 恢复；不要直接修改版本标记绕过失败步骤。V13 标准 MySQL 数据库首次采用新流程时执行 V14，之后再启动服务。

## 从 RDS DuckDB 迁移

RDS DuckDB 不符合生产校验要求，不能通过修复 `flyway_schema_history` 继续使用。迁移到标准 MySQL 时采用停机切换：

1. 创建 MySQL 8.0.36+ / InnoDB 目标实例，配置字符集、账号和网络白名单。
2. 停止后端，从源库仅导出账号、Agent、日志源、非采集类 `app_setting` 和 LLM 配置；导出文件必须限制权限并在验收后安全删除。
3. 在空目标库执行最新 `logm-init.sql`，按外键顺序导入配置。不要导入 `log_sources_seeded`，并保持原 `LLM_CONFIG_MASTER_KEY`。
4. 不迁移访问统计、错误、AI 结果、游标、批次、命名空间迁移和去重数据；目标库保持这些表为空，由原始日志重新采集。
5. 修改 `backend.env` 指向目标库，启动并验证健康接口、登录、配置和重新采集结果。源 DuckDB 保持只读备份，不再作为回退运行库。

## 运维脚本

- `logm-reset-ingestion-data.sql`：停止中心端后删除日志派生数据，保留账号和结构版本记录。
- `logm-verify-ingestion.sql`：只读核对统计、重复数据、游标和日志时间范围。
- `logm-v5-incremental-cursor.sql`：仅供旧 V4 数据库一次性升级，不能用于全新 V14 数据库。
