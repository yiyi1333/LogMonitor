-- Upgrade an existing Log Monitor schema to incremental cursor version 5.
-- MySQL 8.0.36+. Run once; this preserves all existing parsed data and checkpoints.

USE `logm`;

ALTER TABLE `collector_checkpoint`
    ADD COLUMN `pending_offset` BIGINT NOT NULL DEFAULT 0;

UPDATE `collector_checkpoint`
SET `pending_offset` = `byte_offset`
WHERE `pending_offset` = 0 AND `byte_offset` > 0;

ALTER TABLE `collector_checkpoint`
    ADD COLUMN `last_event_at` DATETIME(3) NULL;

SELECT `COLUMN_NAME`, `COLUMN_TYPE`, `IS_NULLABLE`
FROM `information_schema`.`COLUMNS`
WHERE `TABLE_SCHEMA` = 'logm'
  AND `TABLE_NAME` = 'collector_checkpoint'
  AND `COLUMN_NAME` IN ('byte_offset', 'pending_offset', 'last_event_at')
ORDER BY `ORDINAL_POSITION`;
