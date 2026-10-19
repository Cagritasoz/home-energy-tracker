package com.cagritasoz.user_service.keycloak;

import com.cagritasoz.user_service.testsupport.ComposeFile;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Rules about the realm file itself, checked without Docker (KeycloakRealmIT checks what Keycloak does with it).
class RealmFileTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final JsonNode realm = MAPPER.readTree(ComposeFile.repoFile("infra/keycloak/realm-energy.json").toFile());

    // Keycloak does not parse "//" comments, and the "//setting": "note" keys that were tried instead were not treated
    // as comments either, so the file holds none of them: the notes live in infra/keycloak/README.md.
    @Test
    void realmFile_isPlainJsonWithoutCommentKeys() {

        List<String> commentKeys = new ArrayList<>();

        collectCommentKeys(realm, "", commentKeys);

        assertThat(commentKeys).isEmpty();

    }

    // Inside the realm file the user profile is one escaped line (a JSON string cannot span lines). The readable
    // source is user-profile.json, and embed-user-profile.ps1 copies it over: this fails when someone edits one
    // file and forgets the other.
    @Test
    void realmFile_embedsTheReadableUserProfileFile() {

        JsonNode embedded = MAPPER.readTree(embeddedProfileText());

        JsonNode readable = MAPPER.readTree(ComposeFile.repoFile("infra/keycloak/user-profile.json").toFile());

        assertThat(embedded).isEqualTo(readable);

    }

    private String embeddedProfileText() {

        JsonNode config = realm.path("components").path("org.keycloak.userprofile.UserProfileProvider").get(0)
                .path("config").path("kc.user.profile.config");

        // Keycloak reads the first element only, so a second one would be silently ignored.
        assertThat(config.size()).isEqualTo(1);

        return config.get(0).asString();

    }

    private static void collectCommentKeys(JsonNode node, String path, List<String> found) {

        for (Map.Entry<String, JsonNode> property : node.properties()) {

            if (property.getKey().startsWith("//")) {
                found.add(path + "/" + property.getKey());
            }

            collectCommentKeys(property.getValue(), path + "/" + property.getKey(), found);

        }

        int index = 0;
        for (JsonNode element : node) {

            if (node.isArray()) {
                collectCommentKeys(element, path + "[" + index + "]", found);
            }

            index++;

        }

    }
}
