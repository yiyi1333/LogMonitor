-- Reset only data derived from log files.
-- Use this when a failed initial import must be replayed from the source logs.
-- Application users and Flyway history are deliberately preserved.

USE `logm`;

START TRANSACTION;

DELETE FROM `ai_analysis`;
DELETE FROM `error_occurrence`;
DELETE FROM `error_group`;
DELETE FROM `api_access_minute`;
DELETE FROM `access_event_dedup`;
DELETE FROM `access_dedup_baseline`;
DELETE FROM `collector_checkpoint`;

COMMIT;
