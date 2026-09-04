CREATE TABLE log_source (
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
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,
    CONSTRAINT uk_log_source_name UNIQUE (normalized_name),
    CONSTRAINT uk_log_source_path UNIQUE (real_path_hash)
);

CREATE INDEX idx_log_source_active ON log_source(deleted_at, name);

CREATE TABLE app_setting (
    setting_key VARCHAR(100) PRIMARY KEY,
    setting_value VARCHAR(2000) NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
