package com.logmonitor.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class LogMonitorMapperSqlCompatibilityTest {
    @Test
    void endpointsQueryCorrelatesOnAggregatedColumns() {
        Select select = Arrays.stream(LogMonitorMapper.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("endpoints"))
                .findFirst()
                .orElseThrow()
                .getAnnotation(Select.class);
        String sql = String.join("", select.value());

        assertThat(sql).contains("FROM (SELECT MIN(a.id)");
        assertThat(sql).contains("g.service_name=aggregated.applicationNamespace");
        assertThat(sql).contains("COALESCE(os.name,'UNKNOWN')=aggregated.service");
        assertThat(sql).doesNotContain("COALESCE(os.name,'UNKNOWN')=COALESCE(s.name,'UNKNOWN')");
    }
}
