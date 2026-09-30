package com.logmonitor;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
@EnabledIfEnvironmentVariable(named="MYSQL_IT_URL",matches="jdbc:mysql:.*")
class MysqlErrorOccurrenceIntegrationTest extends ErrorOccurrenceIntegrationTest {
    @org.junit.jupiter.api.BeforeAll static void requireIsolatedMysql(){MysqlBulkStorageIntegrationTest.requireIsolationAcknowledgement();}
    @DynamicPropertySource static void mysql(DynamicPropertyRegistry registry){MysqlBulkStorageIntegrationTest.mysql(registry);}
}
