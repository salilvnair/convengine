package com.github.salilvnair.convengine.config;

import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Locale;
import java.util.Map;

@Configuration
public class ConvEngineHibernateDialectCustomizer {

    @Bean
    public HibernatePropertiesCustomizer convEngineHibernatePropertiesCustomizer(DataSource dataSource) {
        return (Map<String, Object> props) -> {
            String url = null;
            try (Connection connection = dataSource.getConnection()) {
                url = connection.getMetaData().getURL();
            }
            catch (Exception ignored) {
            }
            if (url != null && url.toLowerCase(Locale.ROOT).contains(":sqlite:")) {
                // Keep UUID as CHAR for SQLite only; leave Postgres/Oracle on native UUID handling.
                props.put("hibernate.type.preferred_uuid_jdbc_type", "CHAR");
            }
        };
    }
}
