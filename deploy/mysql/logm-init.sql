-- LogMonitor current baseline schema for MySQL 8.0.36+.
-- Schema version: Flyway V14. Expected application tables: 20.
-- Run against a new database only. This script never drops existing objects.
-- Production services only validate this schema and never run Flyway migrations.
-- Do not edit an applied Flyway migration to update this schema. Add a new SQL migration,
-- alter existing tables in place, finish the migration by updating schema_metadata,
-- and then fold the final state into this baseline script.

CREATE DATABASE IF NOT EXISTS `logm` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER DATABASE `logm` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE `logm`;
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;
SET SESSION time_zone = '+00:00';

-- Identity and collection configuration

CREATE TABLE `schema_metadata` (
  `component` VARCHAR(64) NOT NULL, `schema_version` INT NOT NULL,
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`component`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `app_user` (
  `id` BIGINT NOT NULL AUTO_INCREMENT, `username` VARCHAR(32) NOT NULL,
  `password_hash` VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `role` VARCHAR(16) NOT NULL DEFAULT 'USER', `must_change_password` BOOLEAN NOT NULL DEFAULT FALSE,
  `enabled` BOOLEAN NOT NULL DEFAULT TRUE, `session_version` BIGINT NOT NULL DEFAULT 0,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`), UNIQUE KEY `uk_app_user_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `collector_agent` (
  `id` BIGINT NOT NULL AUTO_INCREMENT, `agent_uuid` VARCHAR(36) NOT NULL,
  `name` VARCHAR(80) NOT NULL, `normalized_name` VARCHAR(80) NOT NULL,
  `host_name` VARCHAR(255) NOT NULL, `display_address` VARCHAR(255), `agent_version` VARCHAR(32) NOT NULL,
  `token_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `config_revision` BIGINT NOT NULL DEFAULT 1, `spool_bytes` BIGINT NOT NULL DEFAULT 0,
  `spool_limit_bytes` BIGINT NOT NULL DEFAULT 5368709120, `last_seen_at` DATETIME(3),
  `last_error` VARCHAR(2000), `created_by` VARCHAR(32) NOT NULL,
  `enabled` BOOLEAN NOT NULL DEFAULT TRUE, `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), `deleted_at` DATETIME(3),
  PRIMARY KEY (`id`), UNIQUE KEY `uk_collector_agent_uuid` (`agent_uuid`),
  UNIQUE KEY `uk_collector_agent_name` (`normalized_name`), UNIQUE KEY `uk_collector_agent_token` (`token_hash`),
  KEY `idx_collector_agent_seen` (`enabled`,`last_seen_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `agent_allowed_root` (
  `id` BIGINT NOT NULL AUTO_INCREMENT, `agent_id` BIGINT NOT NULL,
  `configured_path` VARCHAR(1500) NOT NULL, `real_path` VARCHAR(1500) NOT NULL,
  `path_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_allowed_root` (`agent_id`,`path_hash`),
  CONSTRAINT `fk_agent_root_agent` FOREIGN KEY (`agent_id`) REFERENCES `collector_agent` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `log_source` (
  `id` BIGINT NOT NULL AUTO_INCREMENT, `name` VARCHAR(80) NOT NULL, `normalized_name` VARCHAR(80) NOT NULL,
  `application_namespace` VARCHAR(80) NOT NULL, `normalized_namespace` VARCHAR(80) NOT NULL,
  `directory_path` VARCHAR(1500) NOT NULL, `real_path` VARCHAR(1500) NOT NULL,
  `real_path_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `include_pattern` VARCHAR(255) NOT NULL, `exclude_pattern` VARCHAR(255) NOT NULL,
  `charset_name` VARCHAR(64) NOT NULL DEFAULT 'UTF-8', `uri_normalizers` TEXT,
  `collector_type` VARCHAR(16) NOT NULL DEFAULT 'LOCAL', `agent_id` BIGINT,
  `instance_key` VARCHAR(80) NOT NULL DEFAULT 'local', `validation_status` VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  `validation_error` VARCHAR(2000), `start_mode` VARCHAR(24) NOT NULL DEFAULT 'HISTORY_180D',
  `namespace_migration_status` VARCHAR(24) NOT NULL DEFAULT 'IDLE',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), `deleted_at` DATETIME(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_log_source_instance_path` (`instance_key`,`real_path_hash`),
  KEY `idx_log_source_active` (`deleted_at`,`collector_type`,`name`), KEY `idx_log_source_agent` (`agent_id`,`deleted_at`),
  KEY `idx_log_source_namespace` (`normalized_namespace`,`deleted_at`),
  CONSTRAINT `fk_log_source_agent` FOREIGN KEY (`agent_id`) REFERENCES `collector_agent` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `app_setting` (
  `setting_key` VARCHAR(100) NOT NULL, `setting_value` VARCHAR(2000) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), PRIMARY KEY (`setting_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Aggregated access data, errors, and collection cursors

CREATE TABLE `api_access_minute` (
  `id` BIGINT NOT NULL AUTO_INCREMENT, `service_name` VARCHAR(80) NOT NULL,
  `instance_key` VARCHAR(80) NOT NULL DEFAULT 'local', `source_id` BIGINT, `uri` VARCHAR(1024) COLLATE utf8mb4_bin NOT NULL,
  `uri_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `method` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UNKNOWN',
  `minute_at` DATETIME NOT NULL, `access_count` BIGINT NOT NULL DEFAULT 0, PRIMARY KEY (`id`),
  UNIQUE KEY `uk_access_minute_source` (`source_id`,`uri_hash`,`minute_at`),
  KEY `idx_access_minute_time` (`minute_at`), KEY `idx_access_service_time` (`service_name`,`minute_at`),
  KEY `idx_access_instance_time` (`instance_key`,`minute_at`), KEY `idx_access_namespace_time` (`service_name`,`minute_at`,`source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `error_group` (
  `id` BIGINT NOT NULL AUTO_INCREMENT, `fingerprint` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `signature_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
  `service_name` VARCHAR(80) NOT NULL, `category` VARCHAR(16) NOT NULL, `exception_class` VARCHAR(255),
  `summary` VARCHAR(2000) NOT NULL, `first_seen` DATETIME(3) NOT NULL, `last_seen` DATETIME(3) NOT NULL,
  `occurrence_count` BIGINT NOT NULL DEFAULT 0, `inferred_uri` VARCHAR(1024),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), PRIMARY KEY (`id`),
  UNIQUE KEY `uk_error_group_fingerprint` (`fingerprint`), KEY `idx_error_group_service_time` (`service_name`,`last_seen`),
  KEY `idx_error_group_category_time` (`category`,`last_seen`), KEY `idx_error_group_signature` (`signature_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `error_occurrence` (
  `id` BIGINT NOT NULL AUTO_INCREMENT, `group_id` BIGINT NOT NULL, `occurred_at` DATETIME(3) NOT NULL,
  `thread_name` VARCHAR(255), `message_text` TEXT NOT NULL, `stack_trace` TEXT,
  `inferred_uri` VARCHAR(1024), `association_type` VARCHAR(16) NOT NULL DEFAULT 'NONE',
  `source_path` VARCHAR(1500), `source_offset` BIGINT,
  `event_key` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `instance_key` VARCHAR(80) NOT NULL DEFAULT 'local', `source_id` BIGINT, PRIMARY KEY (`id`),
  UNIQUE KEY `uk_error_occurrence_event` (`event_key`), KEY `idx_occurrence_group_time` (`group_id`,`occurred_at`),
  KEY `idx_occurrence_time_group` (`occurred_at`,`group_id`), KEY `idx_occurrence_instance_time` (`instance_key`,`occurred_at`),
  KEY `idx_occurrence_time_id` (`occurred_at`,`id`), KEY `idx_occurrence_instance_time_id` (`instance_key`,`occurred_at`,`id`),
  KEY `idx_occurrence_source_time` (`source_id`,`occurred_at`,`id`),
  CONSTRAINT `fk_occurrence_group` FOREIGN KEY (`group_id`) REFERENCES `error_group` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `collector_checkpoint` (
  `id` BIGINT NOT NULL AUTO_INCREMENT, `source_name` VARCHAR(80) NOT NULL,
  `instance_key` VARCHAR(80) NOT NULL DEFAULT 'local', `source_id` BIGINT,
  `file_key` VARCHAR(255) NOT NULL, `stream_generation` VARCHAR(64) NOT NULL DEFAULT 'local',
  `file_path` VARCHAR(1500) NOT NULL, `byte_offset` BIGINT NOT NULL DEFAULT 0,
  `pending_offset` BIGINT NOT NULL DEFAULT 0, `pending_text` MEDIUMTEXT, `pending_bytes` TEXT,
  `status` VARCHAR(24) NOT NULL DEFAULT 'IDLE', `file_size` BIGINT NOT NULL DEFAULT 0,
  `last_modified_at` DATETIME(3), `last_collected_at` DATETIME(3), `last_event_at` DATETIME(3),
  `last_error` VARCHAR(2000), `parse_error_count` BIGINT NOT NULL DEFAULT 0, PRIMARY KEY (`id`),
  UNIQUE KEY `uk_checkpoint_source_file` (`source_id`,`file_key`),
  KEY `idx_checkpoint_source` (`instance_key`,`source_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- LLM configuration and analysis results

CREATE TABLE `llm_provider_config` (
  `id` BIGINT NOT NULL AUTO_INCREMENT, `name` VARCHAR(80) NOT NULL, `normalized_name` VARCHAR(80) NOT NULL,
  `provider_type` VARCHAR(32) NOT NULL, `protocol_type` VARCHAR(32) NOT NULL, `base_url` VARCHAR(1000) NOT NULL,
  `api_key_ciphertext` TEXT NOT NULL, `api_key_nonce` VARCHAR(64) NOT NULL, `key_version` INT NOT NULL DEFAULT 1,
  `enabled` BOOLEAN NOT NULL DEFAULT TRUE, `last_test_status` VARCHAR(16), `last_test_error` VARCHAR(1000),
  `last_tested_at` DATETIME(3), `created_by` VARCHAR(32) NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), PRIMARY KEY (`id`),
  UNIQUE KEY `uk_llm_provider_name` (`normalized_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `llm_model_config` (
  `id` BIGINT NOT NULL AUTO_INCREMENT, `provider_id` BIGINT NOT NULL, `model_id` VARCHAR(160) NOT NULL,
  `normalized_model_id` VARCHAR(160) NOT NULL, `display_name` VARCHAR(160) NOT NULL,
  `enabled` BOOLEAN NOT NULL DEFAULT TRUE, `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), PRIMARY KEY (`id`),
  UNIQUE KEY `uk_llm_provider_model` (`provider_id`,`normalized_model_id`),
  KEY `idx_llm_model_available` (`enabled`,`provider_id`),
  CONSTRAINT `fk_llm_model_provider` FOREIGN KEY (`provider_id`) REFERENCES `llm_provider_config` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `user_llm_preference` (
  `user_id` BIGINT NOT NULL, `model_config_id` BIGINT, `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`user_id`),
  CONSTRAINT `fk_llm_preference_user` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_llm_preference_model` FOREIGN KEY (`model_config_id`) REFERENCES `llm_model_config` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `llm_system_setting` (
  `setting_id` BIGINT NOT NULL, `default_model_id` BIGINT, `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`setting_id`),
  CONSTRAINT `fk_llm_default_model` FOREIGN KEY (`default_model_id`) REFERENCES `llm_model_config` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO `llm_system_setting` (`setting_id`,`default_model_id`) VALUES (1,NULL);

CREATE TABLE `ai_analysis` (
  `id` BIGINT NOT NULL AUTO_INCREMENT, `group_id` BIGINT NOT NULL, `provider_config_id` BIGINT,
  `model_config_id` BIGINT, `provider_name` VARCHAR(80) NOT NULL, `provider_type` VARCHAR(32) NOT NULL,
  `model_name` VARCHAR(160) NOT NULL, `prompt_version` VARCHAR(32) NOT NULL, `locale` VARCHAR(16) NOT NULL DEFAULT 'zh-CN', `requested_by` VARCHAR(32),
  `status` VARCHAR(16) NOT NULL, `result_json` MEDIUMTEXT, `result_text` MEDIUMTEXT,
  `prompt_tokens` BIGINT, `completion_tokens` BIGINT, `failure_reason` VARCHAR(2000),
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_group_model_prompt_locale` (`group_id`,`model_config_id`,`prompt_version`,`locale`),
  KEY `idx_ai_group_locale` (`group_id`,`locale`,`updated_at`),
  KEY `idx_ai_group_updated` (`group_id`,`updated_at`),
  CONSTRAINT `fk_ai_group` FOREIGN KEY (`group_id`) REFERENCES `error_group` (`id`),
  CONSTRAINT `fk_ai_provider` FOREIGN KEY (`provider_config_id`) REFERENCES `llm_provider_config` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_ai_model` FOREIGN KEY (`model_config_id`) REFERENCES `llm_model_config` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `error_occurrence_ai_analysis` (
  `id` BIGINT NOT NULL AUTO_INCREMENT, `occurrence_id` BIGINT NOT NULL, `provider_config_id` BIGINT,
  `model_config_id` BIGINT, `provider_name` VARCHAR(80) NOT NULL, `provider_type` VARCHAR(32) NOT NULL,
  `model_name` VARCHAR(160) NOT NULL, `prompt_version` VARCHAR(32) NOT NULL,
  `locale` VARCHAR(16) NOT NULL DEFAULT 'zh-CN', `requested_by` VARCHAR(32), `status` VARCHAR(16) NOT NULL,
  `result_json` MEDIUMTEXT, `result_text` MEDIUMTEXT, `prompt_tokens` BIGINT, `completion_tokens` BIGINT,
  `failure_reason` VARCHAR(2000), `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), PRIMARY KEY (`id`),
  UNIQUE KEY `uk_occurrence_ai_model_prompt_locale` (`occurrence_id`,`model_config_id`,`prompt_version`,`locale`),
  KEY `idx_occurrence_ai_updated` (`occurrence_id`,`updated_at`),
  KEY `idx_occurrence_ai_locale` (`occurrence_id`,`locale`,`updated_at`),
  CONSTRAINT `fk_occurrence_ai_occurrence` FOREIGN KEY (`occurrence_id`) REFERENCES `error_occurrence` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_occurrence_ai_provider` FOREIGN KEY (`provider_config_id`) REFERENCES `llm_provider_config` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_occurrence_ai_model` FOREIGN KEY (`model_config_id`) REFERENCES `llm_model_config` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Deduplication, namespace migration, and remote ingestion metadata

CREATE TABLE `access_event_dedup` (
  `event_key` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, `occurred_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`event_key`), KEY `idx_access_dedup_time` (`occurred_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `access_dedup_baseline` (
  `service_name` VARCHAR(80) NOT NULL, `instance_key` VARCHAR(80) NOT NULL DEFAULT 'local', `source_id` BIGINT NOT NULL,
  `uri_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, `minute_at` DATETIME NOT NULL,
  `baseline_until` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`source_id`,`uri_hash`,`minute_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `source_namespace_migration` (
  `id` BIGINT NOT NULL AUTO_INCREMENT, `source_id` BIGINT NOT NULL,
  `old_namespace` VARCHAR(80) NOT NULL, `target_namespace` VARCHAR(80) NOT NULL,
  `status` VARCHAR(24) NOT NULL DEFAULT 'PENDING', `failure_reason` VARCHAR(2000),
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), `started_at` DATETIME(3), `completed_at` DATETIME(3),
  PRIMARY KEY (`id`), KEY `idx_namespace_migration_status` (`status`,`created_at`),
  KEY `idx_namespace_migration_source` (`source_id`,`status`),
  CONSTRAINT `fk_namespace_migration_source` FOREIGN KEY (`source_id`) REFERENCES `log_source` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `agent_ingest_batch` (
  `id` BIGINT NOT NULL AUTO_INCREMENT, `batch_id` VARCHAR(36) NOT NULL, `agent_id` BIGINT NOT NULL,
  `source_id` BIGINT NOT NULL, `file_key` VARCHAR(255) NOT NULL, `stream_generation` VARCHAR(64) NOT NULL,
  `start_offset` BIGINT NOT NULL, `end_offset` BIGINT NOT NULL,
  `checksum` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `received_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_ingest_batch` (`batch_id`), KEY `idx_agent_batch_received` (`received_at`),
  CONSTRAINT `fk_agent_batch_agent` FOREIGN KEY (`agent_id`) REFERENCES `collector_agent` (`id`),
  CONSTRAINT `fk_agent_batch_source` FOREIGN KEY (`source_id`) REFERENCES `log_source` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Keep this as the final write so an incomplete initialization is never marked current.
INSERT INTO `schema_metadata` (`component`,`schema_version`) VALUES ('logmonitor',14);

-- Verification: a clean V14 baseline returns 20 application tables here.
SELECT `TABLE_NAME`, `ENGINE`, `TABLE_COLLATION` FROM `information_schema`.`TABLES`
WHERE `TABLE_SCHEMA` = 'logm' ORDER BY `TABLE_NAME`;
