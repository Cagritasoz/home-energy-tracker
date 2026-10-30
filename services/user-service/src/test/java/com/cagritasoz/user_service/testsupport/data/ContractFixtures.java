package com.cagritasoz.user_service.testsupport.data;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

public final class ContractFixtures {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private ContractFixtures() {
    }

    public static JsonNode load(String eventType, int schemaVersion) {

        String path = "events/" + eventType + ".v" + schemaVersion + ".json";

        // Everything inside the contracts jar is on the classpath of user-service's
        // tests, resource files included, not only the classes, user-service depends on contracts (see pom)
        try (InputStream in = ContractFixtures.class.getClassLoader().getResourceAsStream(path)) {

            if (in == null) {
                throw new IllegalStateException("No contract fixture " + path + " (expected in contracts/src/main/resources)");
            }

            return MAPPER.readTree(in);

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

    }
}
