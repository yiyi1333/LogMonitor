CREATE INDEX idx_occurrence_time_id ON error_occurrence(occurred_at, id);
CREATE INDEX idx_occurrence_instance_time_id ON error_occurrence(instance_key, occurred_at, id);

CREATE TABLE error_occurrence_ai_analysis (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    occurrence_id BIGINT NOT NULL,
    provider_config_id BIGINT NULL,
    model_config_id BIGINT NULL,
    provider_name VARCHAR(80) NOT NULL,
    provider_type VARCHAR(32) NOT NULL,
    model_name VARCHAR(160) NOT NULL,
    prompt_version VARCHAR(32) NOT NULL,
    locale VARCHAR(16) NOT NULL DEFAULT 'zh-CN',
    requested_by VARCHAR(32),
    status VARCHAR(16) NOT NULL,
    result_json TEXT,
    result_text TEXT,
    prompt_tokens BIGINT,
    completion_tokens BIGINT,
    failure_reason VARCHAR(2000),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_occurrence_ai_model_prompt_locale UNIQUE (occurrence_id, model_config_id, prompt_version, locale),
    CONSTRAINT fk_occurrence_ai_occurrence FOREIGN KEY (occurrence_id) REFERENCES error_occurrence(id) ON DELETE CASCADE,
    CONSTRAINT fk_occurrence_ai_provider FOREIGN KEY (provider_config_id) REFERENCES llm_provider_config(id) ON DELETE SET NULL,
    CONSTRAINT fk_occurrence_ai_model FOREIGN KEY (model_config_id) REFERENCES llm_model_config(id) ON DELETE SET NULL
);

CREATE INDEX idx_occurrence_ai_updated ON error_occurrence_ai_analysis(occurrence_id, updated_at);
CREATE INDEX idx_occurrence_ai_locale ON error_occurrence_ai_analysis(occurrence_id, locale, updated_at);
