package com.cagritasoz.usage_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    // Same reasoning as device-service's own userServiceRestClient: no timeout means a hung
    // device-service blocks DeviceIdCache's scheduled poll indefinitely instead of just failing
    // that one poll and retrying next cycle.
    @Bean
    public RestClient deviceServiceRestClient(
            @Value("${device-service.base-url}") String deviceServiceBaseUrl,
            @Value("${device-service.connect-timeout-ms}") long connectTimeoutMs,
            @Value("${device-service.read-timeout-ms}") long readTimeoutMs) {

        HttpClientSettings settings = HttpClientSettings.defaults()
                .withTimeouts(
                        Duration.ofMillis(connectTimeoutMs),
                        Duration.ofMillis(readTimeoutMs));
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect().build(settings);

        return RestClient.builder()
                .baseUrl(deviceServiceBaseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
