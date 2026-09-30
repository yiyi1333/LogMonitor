package com.logmonitor;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Existing disposable MySQL V14 schema only. Inherits the complete bulk-write/rollback/concurrency contract. */
@EnabledIfEnvironmentVariable(named="MYSQL_IT_URL",matches="jdbc:mysql:.*")
class MysqlBulkStorageIntegrationTest extends BulkStorageIntegrationTest {
    @org.junit.jupiter.api.BeforeAll static void requireIsolationAcknowledgement() {
        if(!"true".equals(System.getenv("MYSQL_IT_ISOLATED")))throw new IllegalStateException("MYSQL_IT_ISOLATED=true is required for the disposable test schema");
    }
    @org.junit.jupiter.api.BeforeEach void requireFinalSchema() {
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT schema_version FROM schema_metadata WHERE component='logmonitor'",Integer.class)).isEqualTo(14);
        try(var connection=jdbc.getDataSource().getConnection()) {
            org.assertj.core.api.Assertions.assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
        }catch(java.sql.SQLException failure){throw new IllegalStateException(failure);}
    }
    @DynamicPropertySource static void mysql(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",()->System.getenv("MYSQL_IT_URL"));
        registry.add("spring.datasource.username",()->System.getenv("MYSQL_IT_USER"));
        registry.add("spring.datasource.password",()->System.getenv("MYSQL_IT_PASSWORD"));
        registry.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.enabled",()->"false");
    }
}
