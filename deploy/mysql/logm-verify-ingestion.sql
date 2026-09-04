-- Read-only checks for a completed log ingestion run.

USE `logm`;

SELECT COUNT(*) AS access_bucket_rows,
       COALESCE(SUM(`access_count`), 0) AS total_access_count,
       MIN(`minute_at`) AS first_access_at,
       MAX(`minute_at`) AS last_access_at
FROM `api_access_minute`;

SELECT COUNT(*) AS access_event_keys
FROM `access_event_dedup`;

SELECT COUNT(*) AS duplicate_access_buckets
FROM (
    SELECT 1
    FROM `api_access_minute`
    GROUP BY `service_name`, `uri_hash`, `minute_at`
    HAVING COUNT(*) > 1
) AS duplicates;

SELECT COUNT(*) AS error_groups,
       COALESCE(SUM(`occurrence_count`), 0) AS grouped_occurrences,
       MIN(`first_seen`) AS first_error_at,
       MAX(`last_seen`) AS last_error_at
FROM `error_group`;

SELECT COUNT(*) AS error_occurrences,
       COUNT(DISTINCT `event_key`) AS distinct_error_events
FROM `error_occurrence`;

SELECT COUNT(*) AS duplicate_error_fingerprints
FROM (
    SELECT 1
    FROM `error_group`
    GROUP BY `fingerprint`
    HAVING COUNT(*) > 1
) AS duplicates;

SELECT COUNT(*) AS checkpoint_rows,
       SUM(CASE WHEN `status` = 'ERROR' THEN 1 ELSE 0 END) AS error_checkpoints,
       SUM(CASE WHEN `byte_offset` = `file_size` THEN 1 ELSE 0 END) AS files_at_eof,
       SUM(CASE WHEN COALESCE(LENGTH(`pending_text`), 0) = 0 THEN 1 ELSE 0 END) AS empty_pending_tails,
       COALESCE(SUM(`parse_error_count`), 0) AS parse_errors,
       MIN(`last_event_at`) AS first_checkpoint_event_at,
       MAX(`last_event_at`) AS last_checkpoint_event_at,
       MAX(`last_collected_at`) AS latest_scan_at
FROM `collector_checkpoint`;

SELECT COUNT(*) AS duplicate_checkpoints
FROM (
    SELECT 1
    FROM `collector_checkpoint`
    GROUP BY `source_name`, `file_key`
    HAVING COUNT(*) > 1
) AS duplicates;
