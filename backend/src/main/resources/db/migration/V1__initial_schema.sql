CREATE TABLE app_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE api_access_minute (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    service_name VARCHAR(80) NOT NULL,
    uri VARCHAR(1024) NOT NULL,
    uri_hash VARCHAR(64) NOT NULL,
    method VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    minute_at TIMESTAMP NOT NULL,
    access_count BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_access_minute UNIQUE (service_name, uri_hash, minute_at)
);
CREATE INDEX idx_access_minute_time ON api_access_minute(minute_at);
CREATE INDEX idx_access_service_time ON api_access_minute(service_name, minute_at);

CREATE TABLE error_group (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    fingerprint VARCHAR(64) NOT NULL UNIQUE,
    service_name VARCHAR(80) NOT NULL,
    category VARCHAR(16) NOT NULL,
    exception_class VARCHAR(255),
    summary VARCHAR(2000) NOT NULL,
    first_seen TIMESTAMP NOT NULL,
    last_seen TIMESTAMP NOT NULL,
    occurrence_count BIGINT NOT NULL DEFAULT 0,
    inferred_uri VARCHAR(1024),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_error_group_service_time ON error_group(service_name, last_seen);
CREATE INDEX idx_error_group_category_time ON error_group(category, last_seen);

CREATE TABLE error_occurrence (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_id BIGINT NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    thread_name VARCHAR(255),
    message_text TEXT NOT NULL,
    stack_trace TEXT,
    inferred_uri VARCHAR(1024),
    association_type VARCHAR(16) NOT NULL DEFAULT 'NONE',
    source_path VARCHAR(1500),
    source_offset BIGINT,
    event_key VARCHAR(64) NOT NULL UNIQUE,
    CONSTRAINT fk_occurrence_group FOREIGN KEY (group_id) REFERENCES error_group(id)
);
CREATE INDEX idx_occurrence_group_time ON error_occurrence(group_id, occurred_at);

CREATE TABLE collector_checkpoint (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_name VARCHAR(80) NOT NULL,
    file_key VARCHAR(255) NOT NULL,
    file_path VARCHAR(1500) NOT NULL,
    byte_offset BIGINT NOT NULL DEFAULT 0,
    pending_text TEXT,
    pending_bytes TEXT,
    status VARCHAR(24) NOT NULL DEFAULT 'IDLE',
    file_size BIGINT NOT NULL DEFAULT 0,
    last_modified_at TIMESTAMP,
    last_collected_at TIMESTAMP,
    last_error VARCHAR(2000),
    parse_error_count BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_checkpoint_file UNIQUE (source_name, file_key)
);
CREATE INDEX idx_checkpoint_source ON collector_checkpoint(source_name);

CREATE TABLE ai_analysis (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_id BIGINT NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    prompt_version VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    result_text TEXT,
    failure_reason VARCHAR(2000),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_ai_group_prompt UNIQUE (group_id, prompt_version),
    CONSTRAINT fk_ai_group FOREIGN KEY (group_id) REFERENCES error_group(id)
);
