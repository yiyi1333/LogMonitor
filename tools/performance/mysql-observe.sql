-- Run read-only in the isolated benchmark database before/after the same scenario.
SELECT VERSION() AS mysql_version, @@innodb_buffer_pool_size AS buffer_pool_bytes,
       @@max_connections AS max_connections, @@max_allowed_packet AS max_packet;
SHOW GLOBAL STATUS WHERE Variable_name IN
 ('Threads_running','Threads_connected','Innodb_buffer_pool_reads','Innodb_buffer_pool_read_requests',
  'Innodb_data_reads','Innodb_data_writes','Innodb_row_lock_time','Innodb_row_lock_waits','Slow_queries');
SELECT table_name, table_rows, data_length, index_length
 FROM information_schema.tables WHERE table_schema=DATABASE() ORDER BY data_length DESC;
-- Digests only; don't export SQL_TEXT containing application values.
SELECT DIGEST, COUNT_STAR, SUM_TIMER_WAIT, SUM_ROWS_EXAMINED, SUM_ROWS_SENT, SUM_ERRORS
 FROM performance_schema.events_statements_summary_by_digest
 WHERE SCHEMA_NAME=DATABASE() ORDER BY SUM_TIMER_WAIT DESC LIMIT 20;
-- Replace namespace/time values with the anonymous benchmark's values; no production queries.
SET @ns='benchmark-namespace';
SET @from_at=UTC_TIMESTAMP()-INTERVAL 1 DAY;
SET @to_at=UTC_TIMESTAMP();
EXPLAIN ANALYZE SELECT g.id, MIN(o.occurred_at), MAX(o.occurred_at), COUNT(*)
 FROM error_group g JOIN error_occurrence o ON o.group_id=g.id
 WHERE g.service_name=@ns AND o.occurred_at BETWEEN @from_at AND @to_at
 GROUP BY g.id ORDER BY MAX(o.occurred_at) DESC LIMIT 20;
EXPLAIN ANALYZE SELECT minute_at, SUM(access_count) FROM api_access_minute
 WHERE service_name=@ns AND minute_at BETWEEN @from_at AND @to_at GROUP BY minute_at;
EXPLAIN ANALYZE SELECT o.id,o.occurred_at FROM error_occurrence o JOIN error_group g ON g.id=o.group_id
 WHERE g.service_name=@ns AND o.occurred_at BETWEEN @from_at AND @to_at
 ORDER BY o.occurred_at DESC,o.id DESC LIMIT 50;
