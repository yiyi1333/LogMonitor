package com.logmonitor.config;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("prod")
public class ProductionDatabaseValidationConfiguration {

    @Bean
    FlywayMigrationStrategy productionReadOnlySchemaValidation() {
        return flyway -> ProductionDatabaseValidator.validate(flyway.getConfiguration().getDataSource());
    }

    static final class ProductionDatabaseValidator {
        static final int EXPECTED_SCHEMA_VERSION = 14;
        private static final String COMPONENT = "logmonitor";
        private static final String VERSION_SQL =
                "SELECT schema_version FROM schema_metadata WHERE component = ?";
        private static final String ENGINE_SQL =
                "SELECT table_name, engine FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' "
                        + "AND (engine IS NULL OR UPPER(engine) <> 'INNODB') ORDER BY table_name";

        private ProductionDatabaseValidator() {}

        static void validate(DataSource dataSource) {
            if (dataSource == null) {
                throw new IllegalStateException("Production database validation failed: datasource is unavailable");
            }
            try (var connection = dataSource.getConnection()) {
                connection.setReadOnly(true);
                validateProduct(connection.getMetaData().getDatabaseProductName());
                validateVersion(connection.getMetaData().getDatabaseProductVersion());
                validateSchemaVersion(connection);
                validateStorageEngines(connection);
            } catch (IllegalStateException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException(
                        "Production database validation failed without modifying the database: "
                                + exception.getMessage(),
                        exception);
            }
        }

        private static void validateProduct(String productName) {
            if (productName == null || !productName.toLowerCase(java.util.Locale.ROOT).contains("mysql")) {
                throw new IllegalStateException(
                        "Production database validation failed: MySQL 8.0.36+ is required, found " + productName);
            }
        }

        private static void validateVersion(String productVersion) {
            var matcher = java.util.regex.Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)").matcher(productVersion);
            if (!matcher.find()) {
                throw new IllegalStateException(
                        "Production database validation failed: cannot parse MySQL version " + productVersion);
            }
            int major = Integer.parseInt(matcher.group(1));
            int minor = Integer.parseInt(matcher.group(2));
            int patch = Integer.parseInt(matcher.group(3));
            if (major < 8 || (major == 8 && minor == 0 && patch < 36)) {
                throw new IllegalStateException(
                        "Production database validation failed: MySQL 8.0.36+ is required, found " + productVersion);
            }
        }

        private static void validateSchemaVersion(java.sql.Connection connection) throws java.sql.SQLException {
            try (var statement = connection.prepareStatement(VERSION_SQL)) {
                statement.setString(1, COMPONENT);
                try (var result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new IllegalStateException(
                                "Production database validation failed: schema_metadata has no logmonitor version");
                    }
                    int actualVersion = result.getInt(1);
                    if (actualVersion != EXPECTED_SCHEMA_VERSION) {
                        throw new IllegalStateException(
                                "Production database schema version mismatch: expected "
                                        + EXPECTED_SCHEMA_VERSION + ", found " + actualVersion
                                        + ". Ask the DBA to apply the required SQL migrations before starting the service");
                    }
                }
            }
        }

        private static void validateStorageEngines(java.sql.Connection connection) throws java.sql.SQLException {
            try (var statement = connection.prepareStatement(ENGINE_SQL);
                    var result = statement.executeQuery()) {
                var incompatible = new java.util.ArrayList<String>();
                while (result.next()) {
                    incompatible.add(result.getString(1) + "=" + result.getString(2));
                }
                if (!incompatible.isEmpty()) {
                    throw new IllegalStateException(
                            "Production database validation failed: all tables must use InnoDB; incompatible tables: "
                                    + String.join(", ", incompatible));
                }
            }
        }
    }
}
