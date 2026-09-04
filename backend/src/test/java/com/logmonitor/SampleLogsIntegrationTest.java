package com.logmonitor;

import static org.assertj.core.api.Assertions.assertThat;

import com.logmonitor.config.LogMonitorProperties;
import com.logmonitor.config.LogMonitorProperties.Source;
import com.logmonitor.mapper.LogMonitorMapper;
import com.logmonitor.service.LogCollectorService;
import com.logmonitor.service.LogParser;
import com.logmonitor.service.LogStorageService;
import com.logmonitor.service.RedactionService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.HexFormat;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SampleLogsIntegrationTest {
    private static final List<String> SERVICES = List.of("service-a", "service-b", "service-c", "service-d");

    @TempDir Path tempDir;

    @Test
    void generatedSampleDirectoryContainsMainAndMirrorLogs() throws Exception {
        Path root = createSampleRoot();
        assertThat(root).isDirectory();
        try (var files = Files.walk(root)) {
            var logs = files.filter(p -> p.toString().endsWith(".log")).toList();
            assertThat(logs).isNotEmpty();
            assertThat(logs).anySatisfy(path -> assertThat(path.getFileName().toString()).contains(".error_"));
            assertThat(logs).anySatisfy(path -> assertThat(path.getFileName().toString()).doesNotContain(".error_"));
        }
    }

    @Test
    void collectorStoresGeneratedMainLogsAndRestartAddsNoDuplicates() throws Exception {
        Path root = createSampleRoot();
        long expectedMainLogs;
        try (var files = Files.walk(root)) {
            expectedMainLogs = files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".log"))
                    .filter(path -> !path.getFileName().toString().contains(".error_"))
                    .count();
        }
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:samples-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        try (SqlSession session = sessionFactory(dataSource).openSession(true)) {
            LogMonitorMapper mapper = session.getMapper(LogMonitorMapper.class);
            LogMonitorProperties properties = new LogMonitorProperties();
            for (String name : SERVICES) {
                Source source = new Source();
                source.setName(name);
                source.setApplicationNamespace(name);
                source.setPath(root.resolve(name).toString());
                source.setRealPath(root.resolve(name).toRealPath().toString());
                source.setRealPathHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(source.getRealPath().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
                mapper.insertLogSource(source);
                properties.getSources().add(source);
            }

            LogCollectorService collector = collector(properties, mapper);
            collector.scan();
            collector.scan();

            assertThat(mapper.checkpoints()).hasSize((int) expectedMainLogs);
            assertThat(mapper.checkpoints())
                    .allSatisfy(row -> {
                        assertThat(value(row, "file_path").toString()).doesNotContain(".error_");
                        assertThat(value(row, "byte_offset")).isEqualTo(value(row, "file_size"));
                        assertThat(value(row, "last_event_at")).isNotNull();
                    });
            assertThat(mapper.totalAccessCount()).isPositive();
            assertThat(mapper.accessBucketCount()).isPositive();
            assertThat(mapper.totalOccurrenceCount()).isPositive();
            assertThat(queryCount(session, "SELECT COUNT(*) FROM error_group")).isPositive();
            assertThat(queryCount(session, "SELECT COUNT(*) FROM error_group WHERE category='SYSTEM'")).isPositive();
            assertThat(queryCount(session, "SELECT COUNT(*) FROM error_group WHERE category='BUSINESS'")).isPositive();
            assertThat(queryCount(session, "SELECT COUNT(*) FROM error_group WHERE first_seen > last_seen")).isZero();
            assertThat(queryCount(session, "SELECT COUNT(*) FROM error_group g WHERE occurrence_count <> "
                    + "(SELECT COUNT(*) FROM error_occurrence o WHERE o.group_id=g.id)")).isZero();

            long accessCount = mapper.totalAccessCount();
            long occurrenceCount = mapper.totalOccurrenceCount();
            collector = collector(properties, mapper);
            collector.scan();
            collector.scan();

            assertThat(mapper.totalAccessCount()).isEqualTo(accessCount);
            assertThat(mapper.totalOccurrenceCount()).isEqualTo(occurrenceCount);
        }
    }

    private Path createSampleRoot() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("logs"));
        for (String service : SERVICES) {
            Path directory = Files.createDirectory(root.resolve(service));
            Files.writeString(directory.resolve("application.log"), sampleLog(service), StandardCharsets.UTF_8);
            Files.writeString(directory.resolve("application.error_2026-08-12.log"),
                    "mirror file must be excluded\n", StandardCharsets.UTF_8);
        }
        return root;
    }

    private String sampleLog(String service) {
        String access = "2026-08-12 10:49:03.367 INFO 100 --- [worker-1] test.OncePerRequest : "
                + "当前请求URL:http://example/" + service + "/items,当前请求URI:/"
                + service + "/items\n";
        if ("service-a".equals(service)) {
            return access + "2026-08-12 10:49:04.367 ERROR 100 --- [worker-1] test.GlobalExceptionHandler : "
                    + "database unavailable\njava.sql.SQLException: connection failed\n"
                    + "\tat example.service.Repository.find(Repository.java:12)\n";
        }
        if ("service-b".equals(service)) {
            return access + "2026-08-12 10:49:04.367 ERROR 100 --- [worker-1] test.GlobalExceptionHandler : "
                    + "invalid request\nexample.BusinessException: invalid request\n"
                    + "\tat example.service.Handler.handle(Handler.java:18)\n";
        }
        return access;
    }

    private LogCollectorService collector(LogMonitorProperties properties, LogMonitorMapper mapper) {
        return new LogCollectorService(properties, mapper, new LogParser(properties, new RedactionService()),
                new LogStorageService(mapper));
    }

    private SqlSessionFactory sessionFactory(DataSource dataSource) {
        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(LogMonitorMapper.class);
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private Object value(java.util.Map<String, Object> row, String name) {
        return row.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(java.util.Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private long queryCount(SqlSession session, String sql) throws Exception {
        try (var statement = session.getConnection().createStatement(); var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }
}
