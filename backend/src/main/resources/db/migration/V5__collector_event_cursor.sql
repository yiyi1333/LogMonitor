ALTER TABLE collector_checkpoint
    ADD COLUMN pending_offset BIGINT NOT NULL DEFAULT 0;

UPDATE collector_checkpoint
SET pending_offset = byte_offset;

ALTER TABLE collector_checkpoint
    ADD COLUMN last_event_at DATETIME(3);
