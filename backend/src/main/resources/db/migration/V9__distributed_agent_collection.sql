CREATE TABLE collector_agent (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_uuid VARCHAR(36) NOT NULL,
    name VARCHAR(80) NOT NULL,
    normalized_name VARCHAR(80) NOT NULL,
    host_name VARCHAR(255) NOT NULL,
    agent_version VARCHAR(32) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    config_revision BIGINT NOT NULL DEFAULT 1,
    spool_bytes BIGINT NOT NULL DEFAULT 0,
    spool_limit_bytes BIGINT NOT NULL DEFAULT 5368709120,
    last_seen_at TIMESTAMP NULL,
    last_error VARCHAR(2000),
    created_by VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,
    CONSTRAINT uk_collector_agent_uuid UNIQUE (agent_uuid),
    CONSTRAINT uk_collector_agent_name UNIQUE (normalized_name),
    CONSTRAINT uk_collector_agent_token UNIQUE (token_hash)
);

CREATE INDEX idx_collector_agent_seen ON collector_agent(enabled, last_seen_at);

CREATE TABLE agent_allowed_root (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id BIGINT NOT NULL,
    configured_path VARCHAR(1500) NOT NULL,
    real_path VARCHAR(1500) NOT NULL,
    path_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_allowed_root UNIQUE (agent_id, path_hash),
    CONSTRAINT fk_agent_root_agent FOREIGN KEY (agent_id) REFERENCES collector_agent(id)
);

CREATE TABLE log_source_v9 (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    normalized_name VARCHAR(80) NOT NULL,
    directory_path VARCHAR(1500) NOT NULL,
    real_path VARCHAR(1500) NOT NULL,
    real_path_hash VARCHAR(64) NOT NULL,
    include_pattern VARCHAR(255) NOT NULL,
    exclude_pattern VARCHAR(255) NOT NULL,
    charset_name VARCHAR(64) NOT NULL DEFAULT 'UTF-8',
    uri_normalizers TEXT,
    collector_type VARCHAR(16) NOT NULL DEFAULT 'LOCAL',
    agent_id BIGINT NULL,
    instance_key VARCHAR(80) NOT NULL DEFAULT 'local',
    validation_status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    validation_error VARCHAR(2000),
    start_mode VARCHAR(24) NOT NULL DEFAULT 'HISTORY_180D',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,
    CONSTRAINT uk_log_source_instance_name UNIQUE (instance_key, normalized_name),
    CONSTRAINT uk_log_source_instance_path UNIQUE (instance_key, real_path_hash),
    CONSTRAINT fk_log_source_agent FOREIGN KEY (agent_id) REFERENCES collector_agent(id)
);

INSERT INTO log_source_v9(id,name,normalized_name,directory_path,real_path,real_path_hash,include_pattern,
                          exclude_pattern,charset_name,uri_normalizers,collector_type,instance_key,
                          validation_status,start_mode,created_at,updated_at,deleted_at)
SELECT id,name,normalized_name,directory_path,real_path,real_path_hash,include_pattern,exclude_pattern,
       charset_name,uri_normalizers,'LOCAL','local','ACTIVE','HISTORY_180D',created_at,updated_at,deleted_at
FROM log_source;

DROP TABLE log_source;
ALTER TABLE log_source_v9 RENAME TO log_source;
CREATE INDEX idx_log_source_active ON log_source(deleted_at, collector_type, name);
CREATE INDEX idx_log_source_agent ON log_source(agent_id, deleted_at);

CREATE TABLE api_access_minute_v9 (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    service_name VARCHAR(80) NOT NULL,
    instance_key VARCHAR(80) NOT NULL DEFAULT 'local',
    uri VARCHAR(1024) NOT NULL,
    uri_hash VARCHAR(64) NOT NULL,
    method VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    minute_at TIMESTAMP NOT NULL,
    access_count BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_access_minute_instance UNIQUE (service_name, instance_key, uri_hash, minute_at)
);

INSERT INTO api_access_minute_v9(id,service_name,instance_key,uri,uri_hash,method,minute_at,access_count)
SELECT id,service_name,'local',uri,uri_hash,method,minute_at,access_count FROM api_access_minute;
DROP TABLE api_access_minute;
ALTER TABLE api_access_minute_v9 RENAME TO api_access_minute;
CREATE INDEX idx_access_minute_time ON api_access_minute(minute_at);
CREATE INDEX idx_access_service_time ON api_access_minute(service_name, minute_at);
CREATE INDEX idx_access_instance_time ON api_access_minute(instance_key, minute_at);

CREATE TABLE access_dedup_baseline_v9 (
    service_name VARCHAR(80) NOT NULL,
    instance_key VARCHAR(80) NOT NULL DEFAULT 'local',
    uri_hash VARCHAR(64) NOT NULL,
    minute_at TIMESTAMP NOT NULL,
    baseline_until TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (service_name, instance_key, uri_hash, minute_at)
);
INSERT INTO access_dedup_baseline_v9(service_name,instance_key,uri_hash,minute_at,baseline_until)
SELECT service_name,'local',uri_hash,minute_at,baseline_until FROM access_dedup_baseline;
DROP TABLE access_dedup_baseline;
ALTER TABLE access_dedup_baseline_v9 RENAME TO access_dedup_baseline;

CREATE TABLE collector_checkpoint_v9 (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_name VARCHAR(80) NOT NULL,
    instance_key VARCHAR(80) NOT NULL DEFAULT 'local',
    source_id BIGINT NULL,
    file_key VARCHAR(255) NOT NULL,
    stream_generation VARCHAR(64) NOT NULL DEFAULT 'local',
    file_path VARCHAR(1500) NOT NULL,
    byte_offset BIGINT NOT NULL DEFAULT 0,
    pending_offset BIGINT NOT NULL DEFAULT 0,
    pending_text TEXT,
    pending_bytes TEXT,
    status VARCHAR(24) NOT NULL DEFAULT 'IDLE',
    file_size BIGINT NOT NULL DEFAULT 0,
    last_modified_at TIMESTAMP,
    last_collected_at TIMESTAMP,
    last_event_at TIMESTAMP,
    last_error VARCHAR(2000),
    parse_error_count BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_checkpoint_instance_file UNIQUE (instance_key, source_name, file_key)
);

INSERT INTO collector_checkpoint_v9(id,source_name,instance_key,source_id,file_key,stream_generation,file_path,
                                    byte_offset,pending_offset,pending_text,pending_bytes,status,file_size,
                                    last_modified_at,last_collected_at,last_event_at,last_error,parse_error_count)
SELECT c.id,c.source_name,'local',s.id,c.file_key,'local',c.file_path,c.byte_offset,c.pending_offset,
       c.pending_text,c.pending_bytes,c.status,c.file_size,c.last_modified_at,c.last_collected_at,
       c.last_event_at,c.last_error,c.parse_error_count
FROM collector_checkpoint c LEFT JOIN log_source s ON s.instance_key='local' AND s.name=c.source_name;
DROP TABLE collector_checkpoint;
ALTER TABLE collector_checkpoint_v9 RENAME TO collector_checkpoint;
CREATE INDEX idx_checkpoint_source ON collector_checkpoint(instance_key, source_name);

ALTER TABLE error_occurrence ADD COLUMN instance_key VARCHAR(80) NOT NULL DEFAULT 'local';
ALTER TABLE error_occurrence ADD COLUMN source_id BIGINT NULL;
CREATE INDEX idx_occurrence_instance_time ON error_occurrence(instance_key, occurred_at);

CREATE TABLE agent_ingest_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id VARCHAR(36) NOT NULL,
    agent_id BIGINT NOT NULL,
    source_id BIGINT NOT NULL,
    file_key VARCHAR(255) NOT NULL,
    stream_generation VARCHAR(64) NOT NULL,
    start_offset BIGINT NOT NULL,
    end_offset BIGINT NOT NULL,
    checksum VARCHAR(64) NOT NULL,
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_ingest_batch UNIQUE (batch_id),
    CONSTRAINT fk_agent_batch_agent FOREIGN KEY (agent_id) REFERENCES collector_agent(id),
    CONSTRAINT fk_agent_batch_source FOREIGN KEY (source_id) REFERENCES log_source(id)
);

CREATE INDEX idx_agent_batch_received ON agent_ingest_batch(received_at);
