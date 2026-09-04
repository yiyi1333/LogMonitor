package com.logmonitor.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.logmonitor.config.LogMonitorProperties;
import com.logmonitor.config.LogMonitorProperties.Source;
import com.logmonitor.mapper.LogMonitorMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogCollectorIntegrationTest {
    private LogCollectorService collector;
    private LogMonitorMapper mapper;
    private SqlSession session;
    private LogMonitorProperties properties;
    @TempDir Path directory;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:collector-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        session = sessionFactory(dataSource).openSession(true);
        mapper = session.getMapper(LogMonitorMapper.class);

        properties = new LogMonitorProperties();
        collector = newCollector();
    }

    @AfterEach
    void tearDown() {
        if (session != null) session.close();
    }

    @Test
    void scanningIsIncrementalAndIdempotentAcrossMirrorAppendRotationAndTruncate() throws Exception {
        Source source = managedSource("integration", directory);
        Path main = directory.resolve("integration_2026-08-13.0.log");
        Path mirror = directory.resolve("integration.error_2026-08-13.0.log");
        String first = request("08:00:00.000", "exec-1", "/integration/one");
        Files.writeString(main, first);
        Files.writeString(mirror, first);

        scanTwice(source);
        assertThat(mapper.totalAccessCount()).isEqualTo(1);

        scanTwice(source);
        assertThat(mapper.totalAccessCount()).isEqualTo(1);

        Files.writeString(main, request("08:00:01.000", "exec-2", "/integration/two"), StandardOpenOption.APPEND);
        scanTwice(source);
        assertThat(mapper.totalAccessCount()).isEqualTo(2);

        Path rotated = directory.resolve("integration_2026-08-13.1.log");
        Files.move(main, rotated);
        scanTwice(source);
        assertThat(mapper.totalAccessCount()).isEqualTo(2);

        String third = request("08:00:02.000", "exec-3", "/integration/three");
        Files.writeString(main, third);
        scanTwice(source);
        assertThat(mapper.totalAccessCount()).isEqualTo(3);

        Files.writeString(main, first, StandardOpenOption.TRUNCATE_EXISTING);
        scanTwice(source);
        assertThat(mapper.totalAccessCount()).isEqualTo(3);
    }

    @Test
    void accessBaselineOnlySuppressesEventsAtOrBeforeMigrationCutoff() throws Exception {
        assertThat(mapper.databasePing()).isEqualTo(1);
        Source source = managedSource("integration", directory);
        Instant minute = Instant.parse("2026-08-13T08:00:00Z");
        Instant cutoff = Instant.parse("2026-08-13T08:00:30Z");
        try (var statement = session.getConnection().prepareStatement(
                "INSERT INTO access_dedup_baseline(service_name,instance_key,source_id,uri_hash,minute_at,baseline_until) "
                        + "VALUES(?,?,?,?,?,?)")) {
            statement.setString(1, "integration");
            statement.setString(2, "local");
            statement.setLong(3, source.getId());
            statement.setString(4, "hash");
            statement.setTimestamp(5, Timestamp.from(minute));
            statement.setTimestamp(6, Timestamp.from(cutoff));
            statement.executeUpdate();
        }

        assertThat(mapper.accessBaselineExists("integration", "local", "hash", minute, cutoff.minusMillis(1))).isEqualTo(1);
        assertThat(mapper.accessBaselineExists("integration", "local", "hash", minute, cutoff.plusMillis(1))).isZero();
    }

    @Test
    void persistsLogTimeAndResumesPendingContentAfterRestart() throws Exception {
        Source source = managedSource("integration", directory);
        Path main = directory.resolve("integration.log");
        String first = request("08:00:00.123", "exec-1", "/integration/one");
        Files.writeString(main, first);

        collector.scanSource(source);
        assertThat(mapper.totalAccessCount()).isZero();
        Map<String, Object> pending = mapper.checkpoints().get(0);
        assertThat(number(pending, "byte_offset")).isEqualTo(Files.size(main));
        assertThat(number(pending, "pending_offset")).isZero();
        assertThat(value(pending, "last_event_at")).isNull();

        collector = newCollector();
        collector.scanSource(source);
        assertThat(mapper.totalAccessCount()).isEqualTo(1);
        assertThat(timestamp(mapper.checkpoints().get(0), "last_event_at"))
                .isEqualTo(Instant.parse("2026-08-13T00:00:00.123Z"));
        assertThat(accessMinute()).isEqualTo(Instant.parse("2026-08-13T00:00:00Z"));

        String second = request("08:00:01.456", "exec-2", "/integration/two");
        int splitAt = second.indexOf("/integration/two") + "/integration".length();
        long secondOffset = Files.size(main);
        Files.writeString(main, second.substring(0, splitAt), StandardOpenOption.APPEND);
        collector.scanSource(source);
        collector.scanSource(source);
        assertThat(mapper.totalAccessCount()).isEqualTo(1);
        assertThat(number(mapper.checkpoints().get(0), "pending_offset")).isEqualTo(secondOffset);

        collector = newCollector();
        Files.writeString(main, second.substring(splitAt), StandardOpenOption.APPEND);
        scanTwice(source);
        assertThat(mapper.totalAccessCount()).isEqualTo(2);
        Map<String, Object> complete = mapper.checkpoints().get(0);
        assertThat(number(complete, "byte_offset")).isEqualTo(Files.size(main));
        assertThat(value(complete, "pending_text")).isEqualTo("");
        assertThat(timestamp(complete, "last_event_at"))
                .isEqualTo(Instant.parse("2026-08-13T00:00:01.456Z"));
    }

    private SqlSessionFactory sessionFactory(DataSource dataSource) {
        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(LogMonitorMapper.class);
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private void scanTwice(Source source) throws Exception {
        collector.scanSource(source);
        collector.scanSource(source);
    }

    private LogCollectorService newCollector() {
        LogParser parser = new LogParser(properties, new RedactionService());
        return new LogCollectorService(properties, mapper, parser, new LogStorageService(mapper));
    }

    private Source managedSource(String name, Path path) throws Exception {
        Source source = new Source();
        source.setName(name);
        source.setApplicationNamespace(name);
        source.setPath(path.toString());
        source.setRealPath(path.toRealPath().toString());
        source.setRealPathHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(source.getRealPath().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        mapper.insertLogSource(source);
        return mapper.activeLogSource(source.getId());
    }

    private Instant accessMinute() throws Exception {
        try (var statement = session.getConnection().createStatement();
             var result = statement.executeQuery("SELECT minute_at FROM api_access_minute")) {
            assertThat(result.next()).isTrue();
            return result.getTimestamp(1).toInstant();
        }
    }

    private Object value(Map<String, Object> row, String name) {
        return row.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private long number(Map<String, Object> row, String name) {
        return ((Number) value(row, name)).longValue();
    }

    private Instant timestamp(Map<String, Object> row, String name) {
        Object value = value(row, name);
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.time.LocalDateTime dateTime) return dateTime.toInstant(java.time.ZoneOffset.UTC);
        return (Instant) value;
    }

    private String request(String time, String thread, String uri) {
        return "2026-08-13 " + time + "  INFO 100 --- [ " + thread + "] test.OncePerRequest : 当前请求URL:http://localhost"
                + uri + "，当前请求URI:" + uri + ",请求IP:127.0.0.1\n";
    }
}
