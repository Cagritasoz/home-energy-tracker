package com.cagritasoz.user_service.testsupport.containers;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

// Reads infra/docker-compose.yml so a test that starts a container runs the same image tag and the same settings
// as the real stack: bumping a tag or dropping a setting in the compose file is seen by the test instead of
// drifting away from it.
public final class ComposeFile {

    private final Map<String, Map<String, Object>> services;

    private ComposeFile(Map<String, Map<String, Object>> services) {
        this.services = services;
    }

    @SuppressWarnings("unchecked")
    public static ComposeFile load() {

        try (Reader reader = Files.newBufferedReader(repoFile("infra/docker-compose.yml"))) {

            Map<String, Object> document = new Yaml().load(reader);

            return new ComposeFile((Map<String, Map<String, Object>>) document.get("services"));

        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

    }

    // Return the value of the key "image" of a given service.
    public String image(String service) {

        return String.valueOf(service(service).get("image"));

    }

    // The value exactly as written in the file (it can still contain a ${PLACEHOLDER}), or null when the
    // service does not set the variable.
    @SuppressWarnings("unchecked")
    public String environment(String service, String variable) {

        Map<String, Object> environment = (Map<String, Object>) service(service).get("environment");

        Object value = environment == null ? null : environment.get(variable);

        return value == null ? null : String.valueOf(value);

    }

    // Finds a file of this repository by walking up from the working directory, so the test works from the
    // module directory (Maven) as well as from the repository root (an IDE).
    public static Path repoFile(String relativePath) {

        Path path = Path.of("");

        Path directory = path.toAbsolutePath();

        while (directory != null) {

            Path candidate = directory.resolve(relativePath);

            if (Files.exists(candidate)) {
                return candidate;
            }

            directory = directory.getParent();

        }

        throw new IllegalStateException(relativePath + " not found above " + path.toAbsolutePath());

    }

    // Return the config of a given service.
    private Map<String, Object> service(String name) {

        Map<String, Object> service = services.get(name);

        if (service == null) {
            throw new IllegalStateException("No service " + name + " in docker-compose.yml");
        }

        return service;

    }
}
