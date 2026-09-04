package com.logmonitor.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.logmonitor.LogMonitorApplication;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

class ProductionDatabaseValidationConfigurationTest {
    private DataSource dataSource;
    private Connection connection;
    private PreparedStatement versionStatement;
    private PreparedStatement engineStatement;
    private ResultSet versionResult;
    private ResultSet engineResult;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        versionStatement = mock(PreparedStatement.class);
        engineStatement = mock(PreparedStatement.class);
        versionResult = mock(ResultSet.class);
        engineResult = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        when(metadata.getDatabaseProductVersion()).thenReturn("8.0.36");
        when(connection.prepareStatement(anyString())).thenReturn(versionStatement, engineStatement);
        when(versionStatement.executeQuery()).thenReturn(versionResult);
        when(engineStatement.executeQuery()).thenReturn(engineResult);
        when(versionResult.next()).thenReturn(true, false);
        when(versionResult.getInt(1)).thenReturn(14);
        when(engineResult.next()).thenReturn(false);
    }

    @Test
    void acceptsExpectedSchemaWithoutWriting() throws Exception {
        ProductionDatabaseValidationConfiguration.ProductionDatabaseValidator.validate(dataSource);

        verify(connection).setReadOnly(true);
        verify(versionStatement, never()).executeUpdate();
        verify(engineStatement, never()).executeUpdate();
        verify(connection, never()).createStatement();
    }

    @Test
    void rejectsOlderSchema() throws Exception {
        when(versionResult.getInt(1)).thenReturn(13);

        assertThatThrownBy(() -> ProductionDatabaseValidationConfiguration.ProductionDatabaseValidator.validate(dataSource))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected 14, found 13");
    }

    @Test
    void rejectsNewerSchema() throws Exception {
        when(versionResult.getInt(1)).thenReturn(15);

        assertThatThrownBy(() -> ProductionDatabaseValidationConfiguration.ProductionDatabaseValidator.validate(dataSource))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected 14, found 15");
    }

    @Test
    void rejectsMissingSchemaMetadata() throws Exception {
        when(versionResult.next()).thenReturn(false);

        assertThatThrownBy(() -> ProductionDatabaseValidationConfiguration.ProductionDatabaseValidator.validate(dataSource))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schema_metadata has no logmonitor version");
    }

    @Test
    void rejectsDuckDbTables() throws Exception {
        when(engineResult.next()).thenReturn(true, false);
        when(engineResult.getString(1)).thenReturn("app_user");
        when(engineResult.getString(2)).thenReturn("DUCKDB");

        assertThatThrownBy(() -> ProductionDatabaseValidationConfiguration.ProductionDatabaseValidator.validate(dataSource))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app_user=DUCKDB");
    }

    @Test
    void rejectsUnsupportedMysqlVersion() throws Exception {
        when(connection.getMetaData().getDatabaseProductVersion()).thenReturn("8.0.35");

        assertThatThrownBy(() -> ProductionDatabaseValidationConfiguration.ProductionDatabaseValidator.validate(dataSource))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MySQL 8.0.36+");
    }

    @Test
    void reportsConnectionFailures() throws Exception {
        when(dataSource.getConnection()).thenThrow(new java.sql.SQLException("connection refused"));

        assertThatThrownBy(() -> ProductionDatabaseValidationConfiguration.ProductionDatabaseValidator.validate(dataSource))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without modifying the database")
                .hasMessageContaining("connection refused");
    }

    @Test
    void productionProfileRejectsBeforeFlywayCreatesAnyTable() throws Exception {
        String url = "jdbc:h2:mem:prod-readonly-" + java.util.UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";

        assertThatThrownBy(() -> new SpringApplicationBuilder(LogMonitorApplication.class)
                        .profiles("prod")
                        .web(WebApplicationType.NONE)
                        .run(
                                "--spring.datasource.url=" + url,
                                "--spring.datasource.username=sa",
                                "--spring.datasource.password=",
                                "--spring.datasource.driver-class-name=org.h2.Driver",
                                "--spring.main.banner-mode=off",
                                "--logging.level.root=OFF",
                                "--log-monitor.admin.password=test-admin-password",
                                "--log-monitor.llm.master-key=" + com.logmonitor.IntegrationTestSupport.masterKey()))
                .hasRootCauseMessage("Production database validation failed: MySQL 8.0.36+ is required, found H2");

        try (var connection = DriverManager.getConnection(url, "sa", "");
                var statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public'");
                var result = statement.executeQuery()) {
            result.next();
            assertThat(result.getInt(1)).isZero();
        }
    }
}
