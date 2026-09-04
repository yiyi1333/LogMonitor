package com.logmonitor.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.logmonitor.config.LogMonitorProperties;
import com.logmonitor.config.LogMonitorProperties.Source;
import com.logmonitor.model.LogModels.ParsedBatch;
import java.nio.charset.Charset;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LogParserTest {
    private LogParser parser;

    @BeforeEach
    void setUp() {
        LogMonitorProperties properties = new LogMonitorProperties();
        properties.setSourceTimezone(ZoneId.of("Asia/Shanghai"));
        parser = new LogParser(properties, new RedactionService());
    }

    @Test
    void parsesBothRequestFormatsAndStripsQuery() {
        String log = """
                2026-08-12 10:49:03.367  INFO 16609 --- [ nio-9096-exec-5] example.servicea.config.OncePerRequest : 当前请求URL:http://example/service-a/login?a=1,当前请求URI:/service-a/login?a=1,请求IP:172.16.0.162
                2026-08-12 10:49:04.367  INFO 16609 --- [ nio-9096-exec-6] example.servicea.config.OncePerRequest : 当前sessoinID：A，当前请求URL:http://example/service-a/users，当前请求URI:/service-a/users,请求IP:172.16.0.162
                """;
        ParsedBatch batch = parser.parse("service-a", log, "sample.log", 0);
        assertThat(batch.accesses()).extracting(a -> a.uri()).containsExactly("/service-a/login", "/service-a/users");
        assertThat(batch.errors()).isEmpty();
    }

    @Test
    void filtersUriNoiseAndMergesWrapperErrors() {
        String log = """
                2026-08-12 14:10:35.454 ERROR 18182 --- [ nio-9099-exec-9] example.serviceb.config.LoginInterceptor : *********uri:**********/service-b/find
                2026-08-12 14:10:35.483 ERROR 18182 --- [ nio-9099-exec-9] org.hibernate.engine.jdbc.spi.SqlExceptionHelper : Unknown column 'payment_time' in 'field list'
                2026-08-12 14:10:35.491 ERROR 18182 --- [ nio-9099-exec-9] c.n.common.GlobalExceptionHandler : org.hibernate.exception.SQLGrammarException: could not extract ResultSet
                javax.persistence.PersistenceException: broken
                \tat example.serviceb.ServiceImpl.find(ServiceImpl.java:12)
                """;
        ParsedBatch batch = parser.parse("service-b", log, "sample.log", 0);
        assertThat(batch.errors()).hasSize(1);
        assertThat(batch.errors().get(0).category()).isEqualTo("SYSTEM");
        assertThat(batch.errors().get(0).exceptionClass()).contains("PersistenceException");
    }

    @Test
    void classifiesBusinessErrorAndRedactsSecrets() {
        String log = """
                2026-08-13 10:59:21.810 ERROR 13019 --- [ nio-9097-exec-2] c.n.common.GlobalExceptionHandler : 用户未登录 token:eyJhbGciOiJIUzI1NiJ9.eyJ1c2VyIjoxfQ.signature IP 172.16.0.162 phone 13800138000
                cn.example.BusinessException: 用户凭证不存在
                \tat src.main.biz.accounts.LoginService.login(LoginService.java:24)
                """;
        var error = parser.parse("service-c", log, "sample.log", 0).errors().get(0);
        assertThat(error.category()).isEqualTo("BUSINESS");
        assertThat(error.stackTrace()).contains("[REDACTED]", "[REDACTED_IP]", "[REDACTED_PHONE]");
        assertThat(error.stackTrace()).doesNotContain("eyJhbGciOiJIUzI1NiJ9", "13800138000", "172.16.0.162");
    }

    @Test
    void associatesOpenRequestAsInference() {
        String log = """
                2026-08-12 11:07:38.800  INFO 13019 --- [ nio-9097-exec-5] src.main.biz.accounts.config.OncePerRequest : 当前请求URL:http://example/account/add，当前请求URI:/account/add,请求IP:172.16.0.162
                2026-08-12 11:07:38.822 ERROR 13019 --- [ nio-9097-exec-5] c.n.common.GlobalExceptionHandler : 数据不能为空
                cn.example.BusinessException: 数据不能为空
                """;
        var error = parser.parse("service-c", log, "sample.log", 0).errors().get(0);
        assertThat(error.inferredUri()).isEqualTo("/account/add");
        assertThat(error.associationType()).isEqualTo("INFERRED");
    }

    @Test
    void keepsIdenticalEventsAtDifferentSourceOffsetsDistinct() {
        String line = "2026-08-12 10:49:03.367  INFO 16609 --- [ nio-9096-exec-5] "
                + "example.servicea.config.OncePerRequest : 当前请求URL:http://example/repeated，当前请求URI:/repeated\n";
        ParsedBatch batch = parser.parse("service-a", line + line, "sample.log", 128);

        assertThat(batch.accesses()).hasSize(2);
        assertThat(batch.accesses()).extracting(event -> event.eventKey()).doesNotHaveDuplicates();
        assertThat(batch.lastEventAt()).isEqualTo(java.time.Instant.parse("2026-08-12T02:49:03.367Z"));
    }

    @Test
    void calculatesSourceOffsetsWithConfiguredCharset() {
        LogMonitorProperties properties = new LogMonitorProperties();
        properties.setSourceTimezone(ZoneId.of("Asia/Shanghai"));
        Source source = new Source();
        source.setName("legacy");
        source.setCharset(Charset.forName("GBK"));
        properties.getSources().add(source);
        LogParser legacyParser = new LogParser(properties, new RedactionService());
        String first = "2026-08-12 10:49:03.367  INFO 16609 --- [ worker-1] test.Logger : 中文内容\n";
        String second = "2026-08-12 10:49:04.367 ERROR 16609 --- [ worker-1] test.Logger : 数据异常\n";
        long baseOffset = 37;

        var error = legacyParser.parse("legacy", first + second, "legacy.log", baseOffset).errors().get(0);

        assertThat(error.sourceOffset()).isEqualTo(baseOffset + first.getBytes(source.getCharset()).length);
    }
}
