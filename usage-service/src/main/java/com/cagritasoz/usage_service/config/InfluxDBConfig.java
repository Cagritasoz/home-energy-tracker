package com.cagritasoz.usage_service.config;

import com.influxdb.v3.client.InfluxDBClient;
import com.influxdb.v3.client.config.ClientConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class InfluxDBConfig {

    // destroyMethod = "close" is technically inferred automatically (InfluxDBClient is
    // AutoCloseable with a no-arg close()), but named explicitly here so the shutdown behavior
    // is visible at the bean declaration rather than relying on that inference silently.
    @Bean(destroyMethod = "close")
    public InfluxDBClient influxDBClient(
            @Value("${app.influxdb.host}") String host,
            @Value("${app.influxdb.token}") String token,
            @Value("${app.influxdb.database}") String database) {

        // char[] rather than String for the token - Strings are immutable and can't be
        // proactively wiped from the heap, so a leaked heap dump keeps the token readable for
        // as long as the JVM lives; char[] at least lets sensitive data be handled without that
        // permanent-string guarantee.
        ClientConfig config = new ClientConfig.Builder()
                .host(host)
                .token(token.toCharArray())
                .database(database) // Says "Use the named database", retention policy should live on the database not the client config.
                .writeTimeout(Duration.ofSeconds(10))
                .queryTimeout(Duration.ofSeconds(30))
                .build();

        return InfluxDBClient.getInstance(config);
    }
}
