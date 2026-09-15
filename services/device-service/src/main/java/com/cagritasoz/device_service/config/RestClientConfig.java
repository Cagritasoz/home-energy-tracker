package com.cagritasoz.device_service.config;

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

    // Without explicit timeouts, a hung user-service blocks the calling device-service thread
    // indefinitely - under load this exhausts the servlet thread pool and takes device-service
    // down with it, not just the one request. A timeout here surfaces as ResourceAccessException,
    // already mapped to a 503 by GlobalExceptionHandler - no other code needs to change.
    @Bean
    public RestClient userServiceRestClient(
            @Value("${user-service.base-url}") String userServiceBaseUrl,
            @Value("${user-service.connect-timeout-ms}") long connectTimeoutMs,
            @Value("${user-service.read-timeout-ms}") long readTimeoutMs) {

        // Timeouts lead to ResourceAccessException.
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withTimeouts(
                        // If I cannot establish the connection within x ms, give up.
                        Duration.ofMillis(connectTimeoutMs),
                        // Once I've connected and sent my request, if I can not receive data back in x ms, give up.
                        Duration.ofMillis(readTimeoutMs));
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect().build(settings);

        return RestClient.builder()
                .baseUrl(userServiceBaseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
