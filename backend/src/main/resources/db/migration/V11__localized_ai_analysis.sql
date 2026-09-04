CREATE TABLE ai_analysis_v11 (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_id BIGINT NOT NULL,
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
    CONSTRAINT uk_ai_group_model_prompt_locale UNIQUE (group_id, model_config_id, prompt_version, locale),
    CONSTRAINT fk_ai_group_v11 FOREIGN KEY (group_id) REFERENCES error_group(id),
    CONSTRAINT fk_ai_provider_v11 FOREIGN KEY (provider_config_id) REFERENCES llm_provider_config(id) ON DELETE SET NULL,
    CONSTRAINT fk_ai_model_v11 FOREIGN KEY (model_config_id) REFERENCES llm_model_config(id) ON DELETE SET NULL
);

INSERT INTO ai_analysis_v11(id,group_id,provider_config_id,model_config_id,provider_name,provider_type,
                            model_name,prompt_version,locale,requested_by,status,result_json,result_text,
                            prompt_tokens,completion_tokens,failure_reason,created_at,updated_at)
SELECT id,group_id,provider_config_id,model_config_id,provider_name,provider_type,model_name,prompt_version,
       'zh-CN',requested_by,status,result_json,result_text,prompt_tokens,completion_tokens,failure_reason,
       created_at,updated_at
FROM ai_analysis;

DROP TABLE ai_analysis;
ALTER TABLE ai_analysis_v11 RENAME TO ai_analysis;
CREATE INDEX idx_ai_group_updated ON ai_analysis(group_id, updated_at);
CREATE INDEX idx_ai_group_locale ON ai_analysis(group_id, locale, updated_at);
