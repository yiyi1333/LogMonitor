package db.migration;

import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V13__application_namespace_and_multi_source extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        boolean h2 = context.getConnection().getMetaData().getDatabaseProductName().toLowerCase().contains("h2");
        try (Statement statement = context.getConnection().createStatement()) {
            execute(statement,
                    "ALTER TABLE collector_agent ADD COLUMN display_address VARCHAR(255) NULL",
                    "UPDATE collector_agent SET display_address=host_name WHERE display_address IS NULL",
                    "ALTER TABLE log_source ADD COLUMN application_namespace VARCHAR(80) NULL",
                    "ALTER TABLE log_source ADD COLUMN normalized_namespace VARCHAR(80) NULL",
                    "ALTER TABLE log_source ADD COLUMN namespace_migration_status VARCHAR(24) NOT NULL DEFAULT 'IDLE'",
                    "UPDATE log_source SET application_namespace=name,normalized_namespace=LOWER(name) WHERE application_namespace IS NULL");
            statement.execute(h2
                    ? "ALTER TABLE log_source DROP CONSTRAINT uk_log_source_instance_name"
                    : "ALTER TABLE log_source DROP INDEX uk_log_source_instance_name");
            execute(statement,
                    "CREATE INDEX idx_log_source_namespace ON log_source(normalized_namespace,deleted_at)",
                    "ALTER TABLE api_access_minute ADD COLUMN source_id BIGINT NULL",
                    "UPDATE api_access_minute a SET source_id=(SELECT MIN(s.id) FROM log_source s WHERE s.instance_key=a.instance_key AND LOWER(s.name)=LOWER(a.service_name)) WHERE source_id IS NULL");
            statement.execute(h2
                    ? "ALTER TABLE api_access_minute DROP CONSTRAINT uk_access_minute_instance"
                    : "ALTER TABLE api_access_minute DROP INDEX uk_access_minute_instance");
            execute(statement,
                    "CREATE UNIQUE INDEX uk_access_minute_source ON api_access_minute(source_id,uri_hash,minute_at)",
                    "CREATE INDEX idx_access_namespace_time ON api_access_minute(service_name,minute_at,source_id)",
                    "ALTER TABLE access_dedup_baseline ADD COLUMN source_id BIGINT NULL",
                    "UPDATE access_dedup_baseline a SET source_id=(SELECT MIN(s.id) FROM log_source s WHERE s.instance_key=a.instance_key AND LOWER(s.name)=LOWER(a.service_name)) WHERE source_id IS NULL",
                    "ALTER TABLE access_dedup_baseline DROP PRIMARY KEY");
            statement.execute(h2
                    ? "ALTER TABLE access_dedup_baseline ALTER COLUMN source_id SET NOT NULL"
                    : "ALTER TABLE access_dedup_baseline MODIFY source_id BIGINT NOT NULL");
            execute(statement,
                    "ALTER TABLE access_dedup_baseline ADD PRIMARY KEY(source_id,uri_hash,minute_at)",
                    "UPDATE collector_checkpoint c SET source_id=(SELECT MIN(s.id) FROM log_source s WHERE s.instance_key=c.instance_key AND LOWER(s.name)=LOWER(c.source_name)) WHERE source_id IS NULL");
            statement.execute(h2
                    ? "ALTER TABLE collector_checkpoint DROP CONSTRAINT uk_checkpoint_instance_file"
                    : "ALTER TABLE collector_checkpoint DROP INDEX uk_checkpoint_instance_file");
            execute(statement,
                    "CREATE UNIQUE INDEX uk_checkpoint_source_file ON collector_checkpoint(source_id,file_key)",
                    "ALTER TABLE error_group ADD COLUMN signature_hash VARCHAR(64) NULL",
                    "CREATE INDEX idx_error_group_signature ON error_group(signature_hash)",
                    "CREATE INDEX idx_occurrence_source_time ON error_occurrence(source_id,occurred_at,id)",
                    "CREATE TABLE source_namespace_migration (id BIGINT AUTO_INCREMENT PRIMARY KEY,source_id BIGINT NOT NULL,old_namespace VARCHAR(80) NOT NULL,target_namespace VARCHAR(80) NOT NULL,status VARCHAR(24) NOT NULL DEFAULT 'PENDING',failure_reason VARCHAR(2000),created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,started_at TIMESTAMP NULL,completed_at TIMESTAMP NULL,CONSTRAINT fk_namespace_migration_source FOREIGN KEY (source_id) REFERENCES log_source(id))",
                    "CREATE INDEX idx_namespace_migration_status ON source_namespace_migration(status,created_at)",
                    "CREATE INDEX idx_namespace_migration_source ON source_namespace_migration(source_id,status)");
        }
    }

    private void execute(Statement statement, String... sql) throws Exception {
        for (String item : sql) statement.execute(item);
    }
}
