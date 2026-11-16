package com.cagritasoz.user_service.keycloak;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

// Builds the Keycloak admin client from app.keycloak.admin.* (KeycloakAdminProperties) and gives the circuit breaker a gauge.
//
// The pieces are static factory methods as well as beans so that a test can assemble the same client against a fake
// server with its own timeouts and breaker settings, instead of repeating this wiring.
@Configuration
@EnableConfigurationProperties(KeycloakAdminProperties.class)
public class KeycloakAdminConfig {

    private static final String REGISTRATION_ID = "keycloak-admin";

    @Bean
    public CircuitBreaker keycloakAdminCircuitBreaker(KeycloakAdminProperties properties) {

        KeycloakAdminProperties.CircuitBreaker breaker = properties.circuitBreaker();

        return circuitBreaker(breaker.slidingWindowSize(), breaker.minimumCalls(), breaker.failureRateThreshold(),
                breaker.waitInOpenState(), breaker.halfOpenCalls());

    }

    @Bean
    public KeycloakAdmin keycloakAdmin(CircuitBreaker keycloakAdminCircuitBreaker, KeycloakAdminProperties properties) {

        return new KeycloakAdminRestClient(
                restClient(properties.baseUrl(), properties.realm(), properties.clientId(), properties.clientSecret(),
                        properties.connectTimeout(), properties.readTimeout(), properties.tokenRefreshMargin(), Clock.systemUTC()),
                keycloakAdminCircuitBreaker,
                retry(properties.retry().maxAttempts(), properties.retry().waitDuration()),
                properties.realm());

    }

    // 0 = closed (normal), 1 = half open (probing), 2 = open (refusing calls). Anything else (disabled, forced open)
    // is never configured here. Like the outbox gauges, it reads the live object on every scrape.
    @Bean
    public MeterBinder keycloakAdminMetrics(CircuitBreaker keycloakAdminCircuitBreaker) {

        return registry -> Gauge.builder("keycloak.admin.circuit.state", keycloakAdminCircuitBreaker, breaker -> switch (breaker.getState()) {

                    case CLOSED -> 0;
                    case HALF_OPEN -> 1;
                    default -> 2;

                })
                .description("State of the circuit breaker in front of Keycloak's admin API: 0 closed, 1 half open, 2 open")
                .register(registry);

    }

    public static CircuitBreaker circuitBreaker(int slidingWindowSize, int minimumCalls, float failureRateThreshold,
                                                Duration waitInOpenState, int halfOpenCalls) {

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(slidingWindowSize)
                .minimumNumberOfCalls(minimumCalls)
                .failureRateThreshold(failureRateThreshold)
                .waitDurationInOpenState(waitInOpenState)
                .permittedNumberOfCallsInHalfOpenState(halfOpenCalls)
                .build();

        return CircuitBreaker.of("keycloak-admin", config);

    }

    // Retries only what KeycloakUnavailableException says may be transient. The circuit breaker's refusal is not
    // retried: asking again at once would just be refused again.
    public static Retry retry(int maxAttempts, Duration wait) {

        RetryConfig config = RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .waitDuration(wait) // The delay between retry attempts.
                .retryOnException(e -> e instanceof KeycloakUnavailableException unavailable && unavailable.retryable()) // retry if transient
                .build();

        return Retry.of("keycloak-admin", config);

    }

    // The RestClient for Keycloak's admin API, with the service account's access token already on every request.
    //
    // Spring Security's OAuth2 client does the token work: it asks Keycloak's token endpoint with the client-credentials
    // grant, keeps the token until tokenRefreshMargin before it expires (its "clock skew"), and renews it on the next call.
    // A 401 from the admin API makes it drop the token (the failure handler), so the retry asks for a new one. There is no
    // lock around the token request, so callers that find no token at the same moment each ask for one; every token is
    // valid, and the extra requests are bounded by the number of callers.
    //
    // The JDK HttpClient is forced to HTTP/1.1 (no h2c upgrade attempt on a plain http URL). The read timeout covers the
    // wait for the response, so a Keycloak that accepts the connection and then hangs is cut off too. The token request gets
    // the same request factory: the OAuth2 client's own RestClient has no timeouts at all.
    public static RestClient restClient(String baseUrl, String realm, String clientId, String clientSecret,
                                        Duration connectTimeout, Duration readTimeout, Duration tokenRefreshMargin, Clock clock) {

        // A missing secret would otherwise show up as a rejected token on every call, long after the start.
        if (clientSecret == null || clientSecret.isBlank()) {

            throw new IllegalArgumentException("app.keycloak.admin.client-secret must not be blank");

        }

        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        String tokenUri = UriComponentsBuilder.fromUriString(baseUrl)
                .path("/realms/{realm}/protocol/openid-connect/token").buildAndExpand(realm).encode().toUriString();

        // The one client registration: the service account, credentials sent as HTTP Basic (the OAuth2 default).
        ClientRegistration registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri(tokenUri)
                .build();

        // The token request. A custom RestClient needs the converters the default one has, and an error status becomes the
        // exception the retry and the circuit breaker already understand, with the status as its only content: a Keycloak
        // error body can quote an email address, and the OAuth2 client would otherwise put it in its exception message.
        RestClientClientCredentialsTokenResponseClient tokenClient = new RestClientClientCredentialsTokenResponseClient();
        tokenClient.setRestClient(RestClient.builder()
                .requestFactory(requestFactory)
                .configureMessageConverters(converters -> {
                    converters.addCustomConverter(new FormHttpMessageConverter());
                    converters.addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {

                    int status = response.getStatusCode().value();

                    // A wrong secret is 401 and will not fix itself, so it is not retried; a 5xx may.
                    throw new KeycloakUnavailableException(
                            "Keycloak answered " + status + " to the service account's token request", status >= 500 || status == 429);

                })
                .build());

        // The manager that is not tied to an HTTP request: the deletion job calls Keycloak from a scheduler thread, where
        // there is no request to take the authorized client from.
        InMemoryClientRegistrationRepository registrations = new InMemoryClientRegistrationRepository(registration);
        InMemoryOAuth2AuthorizedClientService clients = new InMemoryOAuth2AuthorizedClientService(registrations);
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials(grant -> grant
                        .accessTokenResponseClient(tokenClient)
                        .clockSkew(tokenRefreshMargin)
                        .clock(clock))
                .build());

        // The OAuth2 client keeps one token per principal, and by default the principal is whoever is authenticated on the
        // calling thread. On a request thread that is the customer, so without this every customer would fetch and cache a
        // token of their own for the same service account. A fixed principal makes the token the service's.
        Authentication principal = new AnonymousAuthenticationToken(REGISTRATION_ID, "user-service",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

        OAuth2ClientHttpRequestInterceptor tokenInterceptor = new OAuth2ClientHttpRequestInterceptor(manager);
        tokenInterceptor.setClientRegistrationIdResolver(request -> REGISTRATION_ID);
        tokenInterceptor.setPrincipalResolver(request -> principal);
        tokenInterceptor.setAuthorizationFailureHandler(OAuth2ClientHttpRequestInterceptor.authorizationFailureHandler(clients));

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .requestInterceptor(tokenInterceptor)
                .build();

    }
}
