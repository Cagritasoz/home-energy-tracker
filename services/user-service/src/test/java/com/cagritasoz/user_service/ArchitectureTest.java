package com.cagritasoz.user_service;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

// The package structure as a test. No Spring and no Docker: it reads the compiled classes of the service.
//
// A feature is a package, and features build on one another in one direction. The table below is that direction:
// for every package, the other packages of the service it may use. Anything not listed is forbidden, so a new
// dependency is a decision made here, in a diff a reviewer sees, and not something that slips in with an import.
class ArchitectureTest {

    private static final String ROOT = "com.cagritasoz.user_service";

    // Main classes only: the tests reach across packages on purpose.
    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    // From the bottom up. A package name here is exactly that package: deletion.ack is not part of deletion, because
    // the sub-package may use its parent and the parent must not know the sub-package.
    //  - user, keycloak, consumer, kafka and security stand alone. (The Kafka templates are injected by bean name, so
    //    nothing needs to know the kafka package; security reads the token and nothing of ours.)
    //  - outbox records events about a user.
    //  - deletion and profile change a user and record the event.
    //  - provisioning, admin and web sit on top. provisioning uses deletion only to schedule the deletion of an
    //    orphaned account; web only knows the exceptions that the others throw.
    private static final Map<String, List<String>> MAY_USE = Map.ofEntries(
            Map.entry("user", List.of()),
            Map.entry("keycloak", List.of()),
            Map.entry("consumer", List.of()),
            Map.entry("kafka", List.of()),
            Map.entry("security", List.of()),
            Map.entry("outbox", List.of("user")),
            Map.entry("outbox.relay", List.of("outbox")),
            Map.entry("profile", List.of("outbox", "user")),
            Map.entry("deletion", List.of("keycloak", "outbox", "user")),
            Map.entry("deletion.ack", List.of("consumer", "deletion")),
            Map.entry("admin", List.of("deletion", "user")),
            Map.entry("provisioning", List.of("deletion", "keycloak", "outbox", "user")),
            Map.entry("web", List.of("deletion", "keycloak", "provisioning", "user")));

    static Stream<Arguments> packages() {
        return MAY_USE.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
    }

    @ParameterizedTest(name = "{0} may use {1}")
    @MethodSource("packages")
    void aPackage_usesOnlyThePackagesBelowIt(String pkg, List<String> allowed) {
        String[] forbidden = MAY_USE.keySet().stream()
                .filter(other -> !other.equals(pkg) && !allowed.contains(other))
                .map(other -> ROOT + "." + other)
                .toArray(String[]::new);

        noClasses().that().resideInAPackage(ROOT + "." + pkg)
                .should().dependOnClassesThat().resideInAnyPackage(forbidden)
                .check(CLASSES);
    }

    // A package missing from the table would be checked by nothing, and one that no longer exists would leave the table
    // describing a structure that is gone.
    @Test
    void theTable_listsEveryPackageOfTheService() {
        Set<String> packages = CLASSES.stream()
                .map(JavaClass::getPackageName)
                .filter(name -> !name.equals(ROOT))
                .map(name -> name.substring(ROOT.length() + 1))
                .collect(Collectors.toSet());

        assertThat(MAY_USE.keySet()).isEqualTo(packages);
    }

    // The table already rules out a loop between two packages; this also catches one through a third.
    @Test
    void features_haveNoCycles() {
        slices().matching(ROOT + ".(*)..").should().beFreeOfCycles().check(CLASSES);
    }

    @Test
    void controllers_liveInFeaturePackages() {
        classes().that().areAnnotatedWith(RestController.class)
                .should().resideInAnyPackage(ROOT + ".admin", ROOT + ".deletion", ROOT + ".profile")
                .check(CLASSES);
    }

    @Test
    void exceptionHandler_livesInWeb() {
        classes().that().areAnnotatedWith(RestControllerAdvice.class)
                .should().resideInAPackage(ROOT + ".web")
                .check(CLASSES);
    }

    // The service is organised by feature: no package named for a technical layer.
    @Test
    void noPackage_isNamedForALayer() {
        noClasses()
                .should().resideInAnyPackage("..controller..", "..service..", "..dto..", "..entity..", "..model..", "..util..", "..common..")
                .check(CLASSES);
    }
}
