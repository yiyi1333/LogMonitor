package com.logmonitor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class DatabaseMigrationPolicyTest {
    private static final int LAST_LEGACY_MIGRATION = 13;
    private static final Pattern MIGRATION_FILE = Pattern.compile("^V(\\d+)__.+\\.(sql|java)$");
    private static final Pattern VERSIONED_TABLE = Pattern.compile("(?i)\\b[a-z][a-z0-9_]*_v\\d+\\b");

    @Test
    void baselineDeclarationsMatchLatestMigration() throws IOException {
        Path root = repositoryRoot();
        int latestVersion = migrations(root).stream()
                .mapToInt(DatabaseMigrationPolicyTest::migrationVersion)
                .max()
                .orElseThrow();

        assertThat(read(root.resolve("deploy/mysql/logm-init.sql")))
                .contains("Schema version: Flyway V" + latestVersion)
                .contains("VALUES ('logmonitor'," + latestVersion + ")");
        assertThat(read(root.resolve(
                        "backend/src/main/java/com/logmonitor/config/ProductionDatabaseValidationConfiguration.java")))
                .contains("EXPECTED_SCHEMA_VERSION = " + latestVersion);
        assertThat(read(root.resolve("README.md")))
                .contains("Flyway V" + latestVersion);
        assertThat(read(root.resolve("docs/ARCHITECTURE.md")))
                .contains("Flyway V" + latestVersion);
        assertThat(read(root.resolve("docs/RUNTIME_ARCHITECTURE.md")))
                .contains("Flyway V" + latestVersion);

        assertThat(read(root.resolve("backend/config/application-prod.example.yml")))
                .doesNotContain("baseline-on-migrate", "baseline-version");
        assertThat(read(root.resolve(".env.example")))
                .doesNotContain("FLYWAY_BASELINE_ON_MIGRATE", "FLYWAY_BASELINE_VERSION");
        assertThat(read(root.resolve("backend/deploy/backend.env.example")))
                .doesNotContain("FLYWAY_BASELINE_ON_MIGRATE", "FLYWAY_BASELINE_VERSION");
    }

    @Test
    void migrationVersionsAreUnique() throws IOException {
        Set<Integer> versions = new HashSet<>();
        for (Path migration : migrations(repositoryRoot())) {
            assertThat(versions.add(migrationVersion(migration)))
                    .as("duplicate Flyway version in %s", migration)
                    .isTrue();
        }
    }

    @Test
    void newMigrationsUseSqlAndStableTableNames() throws IOException {
        Path root = repositoryRoot();
        for (Path migration : migrations(root)) {
            if (migrationVersion(migration) <= LAST_LEGACY_MIGRATION) {
                continue;
            }
            assertThat(migration.getFileName().toString())
                    .as("Flyway migrations after V13 must be SQL")
                    .endsWith(".sql");
            assertThat(VERSIONED_TABLE.matcher(read(migration)).find())
                    .as("migration table names must not contain a _vXX suffix: %s", migration)
                    .isFalse();
        }

        assertThat(VERSIONED_TABLE.matcher(read(root.resolve("deploy/mysql/logm-init.sql"))).find())
                .as("baseline table names must not contain a _vXX suffix")
                .isFalse();
    }

    @Test
    void latestMigrationWritesSchemaVersionAsItsFinalStatement() throws IOException {
        List<Path> migrations = migrations(repositoryRoot());
        Path latest = migrations.stream()
                .max(java.util.Comparator.comparingInt(DatabaseMigrationPolicyTest::migrationVersion))
                .orElseThrow();
        int latestVersion = migrationVersion(latest);
        Pattern finalVersionWrite = Pattern.compile(
                "(?is)(INSERT\\s+INTO|UPDATE)\\s+schema_metadata.*\\b" + latestVersion + "\\b[^;]*;\\s*$");

        assertThat(finalVersionWrite.matcher(read(latest)).find())
                .as("latest migration must finish by recording schema version %s", latestVersion)
                .isTrue();
    }

    private static List<Path> migrations(Path root) throws IOException {
        List<Path> migrations = new ArrayList<>();
        collectMigrations(root.resolve("backend/src/main/resources/db/migration"), migrations);
        collectMigrations(root.resolve("backend/src/main/resources/db/mysql-migration"), migrations);
        collectMigrations(root.resolve("backend/src/main/java/db/migration"), migrations);
        return migrations;
    }

    private static void collectMigrations(Path directory, List<Path> migrations) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> MIGRATION_FILE.matcher(path.getFileName().toString()).matches())
                    .forEach(migrations::add);
        }
    }

    private static int migrationVersion(Path migration) {
        Matcher matcher = MIGRATION_FILE.matcher(migration.getFileName().toString());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Not a Flyway migration: " + migration);
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path);
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        return Files.isDirectory(current.resolve("backend/src")) ? current : current.getParent();
    }
}
