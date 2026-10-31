package com.cagritasoz.user_service.security;

import com.cagritasoz.user_service.testsupport.config.ConfigAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

// app.security.* as the application binds it, from the real application.yaml with one setting changed.
class SecurityPropertiesTest {

    @EnableConfigurationProperties(SecurityProperties.class)
    static class Enable {
    }

    private final ApplicationContextRunner runner = ConfigAssertions.runnerFor(Enable.class);

    @Test
    void applicationYaml_bindsEverySetting() {

        runner.run(context -> {

            SecurityProperties properties = context.getBean(SecurityProperties.class);

            assertThat(properties.requiredAudience()).isEqualTo("user-service");
            assertThat(properties.cors().allowedOrigins()).containsExactly("http://localhost:3000");

        });

    }

    @Test
    void unknownKey_stopsTheStartUp() {

        ConfigAssertions.assertUnknownKeyRefused(runner, "app.security.required-audiance=user-service");

    }

    // An audience that is blank would make the validator accept no token, or every one, depending on how it compared.
    @Test
    void requiredAudience_blank_stopsTheStartUp() {

        ConfigAssertions.assertRefused(runner, "app.security.required-audience= ", "NotBlank");

    }

    @Test
    void allowedOrigins_aCommaSeparatedList_isBoundAsSeveralOrigins() {

        runner.withPropertyValues("app.security.cors.allowed-origins=https://app.example.com,http://localhost:3000")
                .run(context -> assertThat(context.getBean(SecurityProperties.class).cors().allowedOrigins())
                        .containsExactly("https://app.example.com", "http://localhost:3000"));

    }

    // With no origin listed no browser could call the API; that is a mistake to stop on, not a policy to apply quietly.
    @Test
    void allowedOrigins_empty_stopsTheStartUp() {

        ConfigAssertions.assertRefused(runner, "app.security.cors.allowed-origins=", "NotEmpty");

    }
}
