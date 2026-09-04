CREATE TABLE llm_provider_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    normalized_name VARCHAR(80) NOT NULL,
    provider_type VARCHAR(32) NOT NULL,
    protocol_type VARCHAR(32) NOT NULL,
    base_url VARCHAR(1000) NOT NULL,
    api_key_ciphertext TEXT NOT NULL,
    api_key_nonce VARCHAR(64) NOT NULL,
    key_version INT NOT NULL DEFAULT 1,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_test_status VARCHAR(16),
    last_test_error VARCHAR(1000),
    last_tested_at TIMESTAMP NULL,
    created_by VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_llm_provider_name UNIQUE (normalized_name)
);

CREATE TABLE llm_model_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    provider_id BIGINT NOT NULL,
    model_id VARCHAR(160) NOT NULL,
    normalized_model_id VARCHAR(160) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_llm_provider_model UNIQUE (provider_id, normalized_model_id),
    CONSTRAINT fk_llm_model_provider FOREIGN KEY (provider_id) REFERENCES llm_provider_config(id) ON DELETE CASCADE
);

CREATE INDEX idx_llm_model_available ON llm_model_config(enabled, provider_id);

CREATE TABLE user_llm_preference (
    user_id BIGINT PRIMARY KEY,
    model_config_id BIGINT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_llm_preference_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_llm_preference_model FOREIGN KEY (model_config_id) REFERENCES llm_model_config(id) ON DELETE SET NULL
);

CREATE TABLE llm_system_setting (
    setting_id BIGINT PRIMARY KEY,
    default_model_id BIGINT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_llm_default_model FOREIGN KEY (default_model_id) REFERENCES llm_model_config(id) ON DELETE SET NULL
);

INSERT INTO llm_system_setting(setting_id, default_model_id, updated_at) VALUES(1, NULL, CURRENT_TIMESTAMP);

CREATE TABLE ai_analysis_v10 (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_id BIGINT NOT NULL,
    provider_config_id BIGINT NULL,
    model_config_id BIGINT NULL,
    provider_name VARCHAR(80) NOT NULL,
    provider_type VARCHAR(32) NOT NULL,
    model_name VARCHAR(160) NOT NULL,
    prompt_version VARCHAR(32) NOT NULL,
    requested_by VARCHAR(32),
    status VARCHAR(16) NOT NULL,
    result_json TEXT,
    result_text TEXT,
    prompt_tokens BIGINT,
    completion_tokens BIGINT,
    failure_reason VARCHAR(2000),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_ai_group_model_prompt UNIQUE (group_id, model_config_id, prompt_version),
    CONSTRAINT fk_ai_group_v10 FOREIGN KEY (group_id) REFERENCES error_group(id),
    CONSTRAINT fk_ai_provider_v10 FOREIGN KEY (provider_config_id) REFERENCES llm_provider_config(id) ON DELETE SET NULL,
    CONSTRAINT fk_ai_model_v10 FOREIGN KEY (model_config_id) REFERENCES llm_model_config(id) ON DELETE SET NULL
);

INSERT INTO ai_analysis_v10(id,group_id,provider_name,provider_type,model_name,prompt_version,status,
                            result_text,failure_reason,created_at,updated_at)
SELECT id,group_id,'Legacy','LEGACY',model_name,prompt_version,status,result_text,failure_reason,created_at,updated_at
FROM ai_analysis;

DROP TABLE ai_analysis;
ALTER TABLE ai_analysis_v10 RENAME TO ai_analysis;
CREATE INDEX idx_ai_group_updated ON ai_analysis(group_id, updated_at);
