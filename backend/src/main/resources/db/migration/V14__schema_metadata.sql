CREATE TABLE schema_metadata (
    component VARCHAR(64) NOT NULL,
    schema_version INT NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (component)
);

INSERT INTO schema_metadata(component, schema_version, updated_at)
VALUES ('logmonitor', 14, CURRENT_TIMESTAMP);
