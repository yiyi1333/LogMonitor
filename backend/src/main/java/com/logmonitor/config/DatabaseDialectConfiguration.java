package com.logmonitor.config;

import java.util.Properties;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.VendorDatabaseIdProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DatabaseDialectConfiguration {
    @Bean DatabaseIdProvider databaseIdProvider() {
        VendorDatabaseIdProvider provider = new VendorDatabaseIdProvider();
        Properties names = new Properties(); names.setProperty("H2", "h2"); names.setProperty("MySQL", "mysql");
        provider.setProperties(names); return provider;
    }
}
