ALTER TABLE access_dedup_baseline
    ADD COLUMN baseline_until TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);

CREATE INDEX idx_occurrence_time_group ON error_occurrence(occurred_at, group_id);
