CREATE TABLE access_event_dedup (
    event_key VARCHAR(64) PRIMARY KEY,
    occurred_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_access_dedup_time ON access_event_dedup(occurred_at);

CREATE TABLE access_dedup_baseline (
    service_name VARCHAR(80) NOT NULL,
    uri_hash VARCHAR(64) NOT NULL,
    minute_at TIMESTAMP NOT NULL,
    PRIMARY KEY (service_name, uri_hash, minute_at)
);
INSERT INTO access_dedup_baseline(service_name, uri_hash, minute_at)
SELECT service_name, uri_hash, minute_at FROM api_access_minute;
