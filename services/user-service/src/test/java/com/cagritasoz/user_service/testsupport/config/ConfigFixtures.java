package com.cagritasoz.user_service.testsupport.config;

import com.cagritasoz.user_service.consumer.ProcessedEventsProperties;
import com.cagritasoz.user_service.deletion.DeletionProperties;
import com.cagritasoz.user_service.kafka.KafkaClientIdProperties;
import com.cagritasoz.user_service.keycloak.KeycloakAdminProperties;
import com.cagritasoz.user_service.outbox.relay.OutboxProperties;
import com.cagritasoz.user_service.security.SecurityProperties;
import jakarta.validation.Validation;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// The settings records as the application binds them, filled from the real application.yaml, so a test changes only the
// setting it is about and cannot drift from the defaults the service ships with. The binding and the validation are
// Spring Boot's own (the same as at start-up): a fixture with a value the application would refuse fails right here.
//
// An override is `key=value`, the key relative to the record's prefix, spelled as in application.yaml:
//
//     ConfigFixtures.deletion("grace=5s", "promoter.batch-size=3")
//
// Only the main application.yaml is read, not the test resources' application.properties, which switch the background
// jobs off for full-context tests and mean nothing to a test that builds a class by hand.
public final class ConfigFixtures {

    private static final List<PropertySource<?>> APPLICATION_YAML = loadApplicationYaml();

    private ConfigFixtures() {
    }

    public static OutboxProperties outbox(String... overrides) {
        return bind(OutboxProperties.class, overrides);
    }

    public static ProcessedEventsProperties processedEvents(String... overrides) {
        return bind(ProcessedEventsProperties.class, overrides);
    }

    public static DeletionProperties deletion(String... overrides) {
        return bind(DeletionProperties.class, overrides);
    }

    public static KeycloakAdminProperties keycloakAdmin(String... overrides) {
        return bind(KeycloakAdminProperties.class, overrides);
    }

    public static SecurityProperties security(String... overrides) {
        return bind(SecurityProperties.class, overrides);
    }

    public static KafkaClientIdProperties kafkaClientIds(String... overrides) {
        return bind(KafkaClientIdProperties.class, overrides);
    }

    private static <T> T bind(Class<T> type, String[] overrides) {

        String prefix = type.getAnnotation(ConfigurationProperties.class).prefix();

        Map<String, Object> overridden = new LinkedHashMap<>();

        for (String override : overrides) {

            int equals = override.indexOf('=');
            overridden.put(prefix + "." + override.substring(0, equals), override.substring(equals + 1));

        }

        // The overrides come first, so they win over the file.
        List<ConfigurationPropertySource> sources = new ArrayList<>();
        sources.add(new MapConfigurationPropertySource(overridden));
        ConfigurationPropertySources.from(APPLICATION_YAML).forEach(sources::add);

        var validator = new SpringValidatorAdapter(Validation.buildDefaultValidatorFactory().getValidator());

        return new Binder(sources)
                .bind(prefix, Bindable.of(type), new ValidationBindHandler(validator))
                .orElseThrow(() -> new IllegalStateException("application.yaml has no " + prefix));

    }

    private static List<PropertySource<?>> loadApplicationYaml() {

        try {

            return new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));

        }
        catch (IOException e) {

            throw new IllegalStateException("application.yaml cannot be read", e);

        }
    }
}
