package com.cagritasoz.user_service.repository;

import com.cagritasoz.user_service.entity.User;
import com.cagritasoz.user_service.model.UserStatus;
import com.cagritasoz.user_service.testsupport.PostgresTestContainerConfig;
import com.cagritasoz.user_service.testsupport.UserFixtures;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Layer 2: UserRepository against a real Postgres, single-threaded. What the SQL and the entity
// mapping do to ONE row at a time. Two neighbors cover the rest and are not repeated here:
//   - UserSchemaIT: the table's own rules (constraints, defaults, trigger) in plain SQL;
//   - UserRepositoryConcurrencyIT (to be written): what happens when threads race.
//
// @DataJpaTest starts only the persistence part of the app (entities, repositories, DataSource,
// Flyway, Hibernate) - no controllers, no security, so the Keycloak JwtDecoder is never created.
// It wraps every test in a transaction that is rolled back at the end, so tests need no cleanup.
//
// replace = NONE: by default @DataJpaTest swaps the DataSource for an embedded in-memory database.
// That would silently bypass Postgres, which is the whole point here, so it is switched off.
// @Import brings in the Testcontainers configuration that supplies the real one.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainerConfig.class)
class UserRepositoryIT {

    // Old, fixed instants for rows that must not share the transaction's CURRENT_TIMESTAMP.
    private static final String YEAR_2000 = "2000-01-01T00:00:00Z";
    private static final String YEAR_2026 = "2026-01-01T00:00:00Z";

    // Fixed ids, not randomUUID(), so an expected order is known in advance. They are also written out
    // rather than sorted in Java: UUID.compareTo compares signed longs, while Postgres orders uuid
    // values bytewise, and the two can disagree for ids starting with 8 - f.
    private static final UUID NEWEST_ID = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
    private static final UUID TIED_LOW_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TIED_HIGH_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    // The ordering UserAdminService asks for: newest first (created_at DESC), and rows sharing a
    // created_at ordered by id (ASC is Sort's default direction). Both paging tests use it. It is
    // defined here rather than taken from UserAdminService (which builds it inside a method), so
    // these tests prove what this Sort does in Postgres, not that the service uses it.
    private static final Sort NEWEST_FIRST_THEN_ID = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"));

    @Autowired
    private UserRepository userRepository;

    // Needed for clear(): see the first test.
    @Autowired
    private EntityManager entityManager;

    // Reads the row behind Hibernate's back, so a test can compare "what the entity says" with
    // "what the table really holds".
    @Autowired
    private JdbcTemplate jdbc;

    // ---------------------------------------------------------------------------------------------
    // Entity mapping (save / find)
    // ---------------------------------------------------------------------------------------------

    // Hibernate seeds version at 0 on insert; created_at and updated_at are never written by
    // Hibernate at all - they are mapped insertable = false and @Generated, so Hibernate reads the
    // database's own values back. The row is built from minimalUser(), the same three columns
    // insertIgnoringConflict sets, so none of the three can come from the entity. A missing
    // created_at/updated_at would fail the insert or the comparisons below.
    //
    // entityManager.clear() is the important line. Hibernate keeps every entity it has touched in a
    // first-level cache for the length of the transaction, so without it findById would just hand
    // back the object we saved and never look at the table at all - the test would prove nothing.
    // Clearing forces a real SELECT.
    @Test
    void saveAndFlush_newUser_startsAtVersionZeroAndReadsBackDatabaseTimestamps() {

        User user = UserFixtures.minimalUser().build();

        userRepository.saveAndFlush(user); // User is managed and is cached.
        entityManager.clear(); // Clear the cache.

        User loaded = userRepository.findById(user.getId()).orElseThrow(); // findById hits the database. This is what we want.

        assertThat(loaded.getVersion()).isZero();

        // And what the entity reports is what the table holds.
        assertThat(loaded.getCreatedAt()).isEqualTo(timestampOf("created_at", loaded));
        assertThat(loaded.getUpdatedAt()).isEqualTo(timestampOf("updated_at", loaded));

    }

    @Test
    void saveAndFlush_userBuiltWithoutOptionalFields_persistsBuilderDefaults() {

        User user = UserFixtures.minimalUser().build();

        userRepository.saveAndFlush(user);
        entityManager.clear();

        User loaded = userRepository.findById(user.getId()).orElseThrow();

        assertThat(loaded.getTimezone()).isEqualTo("UTC");
        assertThat(loaded.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(loaded.isDevicesDeleted()).isFalse();

    }

    @Test
    void saveAndFlush_existingUser_incrementsVersion() {

        User user = UserFixtures.minimalUser().build();

        // After this save the entity is managed and already carries its starting version.
        userRepository.saveAndFlush(user);
        Long versionBefore = user.getVersion();

        user.setDisplayName(UserFixtures.JOHN_MARSTON_NAME);
        userRepository.saveAndFlush(user);

        // Hibernate bumps the version on the in-memory entity too, so reading it from `user` would
        // prove nothing about the table. Clearing forces a real SELECT of what was written.
        entityManager.clear();

        User reloaded = userRepository.findById(user.getId()).orElseThrow();

        assertThat(reloaded.getVersion()).isEqualTo(versionBefore + 1);

    }

    // updated_at is stamped by V6's trigger, and Hibernate is told to expect that (@Generated on
    // INSERT and UPDATE). Two separate claims, both checked here:
    //  1. after the flush, the entity Hibernate is holding already carries the database's new
    //     updated_at - no reload needed. That refresh is the part that belongs to this layer;
    //     the trigger itself is proven in UserSchemaIT.
    //  2. the table agrees with it, and created_at was not touched. created_at is deliberately
    //     tampered with in Java before the save: saving back the value it already had could never
    //     fail, so it would prove nothing. With updatable = false the tampering must never reach
    //     the table.
    // The row is inserted with explicit year-2000 timestamps because CURRENT_TIMESTAMP is the start
    // time of the transaction: an insert and an update in the same test would otherwise get the same
    // instant, and "updated_at moved" could not be observed.
    @Test
    void saveAndFlush_existingUser_bumpsUpdatedAtAndLeavesCreatedAtAlone() {

        UUID id = UUID.randomUUID();

        // JDBC needed since hibernate can not insert created_at or updated_at columns (insertable = false, for both)
        jdbc.update("INSERT INTO users (id, email, display_name, created_at, updated_at) VALUES (?, ?, ?, ?::timestamptz, ?::timestamptz)",
                id, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME, YEAR_2000, YEAR_2000);

        User user = userRepository.findById(id).orElseThrow(); // No entity managed in the cache, findById hits the database.

        user.setDisplayName(UserFixtures.LEON_KENNEDY_NAME);
        user.setCreatedAt(Instant.parse("1990-01-01T00:00:00Z"));

        userRepository.saveAndFlush(user); // Flush the changes to the database.

        // Claim 1: refreshed on the in-memory entity itself, before any clear(). @Generated in effect.
        assertThat(user.getUpdatedAt()).isAfter(Instant.parse(YEAR_2000));

        entityManager.clear();

        User reloaded = userRepository.findById(id).orElseThrow();

        // Claim 2: what the table holds.
        assertThat(reloaded.getUpdatedAt()).isEqualTo(user.getUpdatedAt());
        assertThat(reloaded.getCreatedAt()).isEqualTo(Instant.parse(YEAR_2000));

    }

    // The single-threaded form of the lost-update race that @Version exists to stop. Two copies of
    // the same row are loaded at version 0; whichever saves first wins and moves the row to
    // version 1, and the other is now working from an outdated copy and must be refused rather than
    // silently overwrite the winner's change.
    //
    // The clear() between the two loads is what makes them two independent copies: without it,
    // findById would return the same object twice. After it, copyA is detached (Hibernate has
    // forgotten it) while copyB is the managed one.
    @Test
    void saveAndFlush_staleCopy_throwsOptimisticLockingFailure() {

        User user = UserFixtures.minimalUser().build();

        userRepository.saveAndFlush(user);
        entityManager.clear();

        User copyA = userRepository.findById(user.getId()).orElseThrow();

        entityManager.clear();

        User copyB = userRepository.findById(user.getId()).orElseThrow();

        copyB.setDisplayName(UserFixtures.LEON_KENNEDY_NAME);
        userRepository.saveAndFlush(copyB); // Wins: the row moves to version 1.

        copyA.setDisplayName(UserFixtures.JOHN_MARSTON_NAME); // Still says version 0.

        // Spring translates Hibernate's stale-version failure into OptimisticLockingFailureException,
        // which is what GlobalExceptionHandler maps to 409.
        assertThatThrownBy(() -> userRepository.saveAndFlush(copyA))
                .isInstanceOf(OptimisticLockingFailureException.class);

        // The loser wrote nothing: the winner's name is still in the table.
        String storedName = jdbc.queryForObject("SELECT display_name FROM users WHERE id = ?", String.class, user.getId());
        assertThat(storedName).isEqualTo(UserFixtures.LEON_KENNEDY_NAME);

    }

    @Test
    void findById_unknownId_returnsEmpty() {

        assertThat(userRepository.findById(UUID.randomUUID())).isEmpty();

    }

    @Test
    void existsById_knownAndUnknownId_returnsTrueAndFalse() {

        User user = UserFixtures.minimalUser().build();

        userRepository.saveAndFlush(user);

        assertThat(userRepository.existsById(user.getId())).isTrue(); // existsById does not use the cache, no entityManager.clear() needed.
        assertThat(userRepository.existsById(UUID.randomUUID())).isFalse();

    }

    // Newest first, ties broken by id.
    //
    // The data is arranged so every way of getting this wrong changes the result:
    //  - the newest row has the HIGHEST id, so sorting by id alone, or created_at ascending, would
    //    push it to the end instead of the front;
    //  - the two tied rows share created_at exactly, so only the id tie-break decides their order;
    //  - the tied rows are INSERTED highest id first. Without an ORDER BY on the tie, Postgres tends
    //    to return rows in physical insertion order, so a missing tie-break would come back as
    //    (high, low) and fail. Inserting the low id first would let it pass by accident.
    @Test
    void findAllPaged_sortedByCreatedAtDescThenId_returnsNewestFirst() {

        jdbc.update("""
                        INSERT INTO users
                            (id, email, display_name, created_at)
                        VALUES
                            (?, ?, ?, ?::timestamptz),
                            (?, ?, ?, ?::timestamptz),
                            (?, ?, ?, ?::timestamptz)
                        """,
                TIED_HIGH_ID, UserFixtures.LEON_KENNEDY_EMAIL, UserFixtures.LEON_KENNEDY_NAME, YEAR_2000,
                TIED_LOW_ID, UserFixtures.JOHN_MARSTON_EMAIL, UserFixtures.JOHN_MARSTON_NAME, YEAR_2000,
                NEWEST_ID, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME, YEAR_2026
        );

        Page<User> userPage = userRepository.findAll(PageRequest.of(0, 3, NEWEST_FIRST_THEN_ID));

        assertThat(userPage.getContent())
                .extracting(User::getId)
                .containsExactly(NEWEST_ID, TIED_LOW_ID, TIED_HIGH_ID);

    }

    // 5 rows, pages of 2: 2 + 2 + 1. Sizes alone would not prove paging works - a sort that is not
    // deterministic can repeat a row on two pages and skip another while every page still has the
    // right size - so the ids of all pages together must be exactly the five inserted, each once.
    // (All five rows get the same created_at, being inserted in one transaction, so the id tie-break
    // is what keeps the order stable from one page query to the next.)
    @Test
    void findAllPaged_secondPage_returnsRemainingRowsAndTotals() {

        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        jdbc.update("""
                        INSERT INTO users
                            (id, email, display_name)
                        VALUES
                            (?, ?, ?),
                            (?, ?, ?),
                            (?, ?, ?),
                            (?, ?, ?),
                            (?, ?, ?)
                        """,
                ids.get(0), UserFixtures.LEON_KENNEDY_EMAIL, UserFixtures.LEON_KENNEDY_NAME,
                ids.get(1), UserFixtures.JOHN_MARSTON_EMAIL, UserFixtures.JOHN_MARSTON_NAME,
                ids.get(2), UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME,
                ids.get(3), UserFixtures.JACK_CARVER_EMAIL, UserFixtures.JACK_CARVER_NAME,
                ids.get(4), UserFixtures.JASON_BRODY_EMAIL, UserFixtures.JASON_BRODY_NAME
        );

        Page<User> firstPage = userRepository.findAll(PageRequest.of(0, 2, NEWEST_FIRST_THEN_ID));
        Page<User> secondPage = userRepository.findAll(PageRequest.of(1, 2, NEWEST_FIRST_THEN_ID));
        Page<User> thirdPage = userRepository.findAll(PageRequest.of(2, 2, NEWEST_FIRST_THEN_ID));

        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(secondPage.getContent()).hasSize(2);
        assertThat(thirdPage.getContent()).hasSize(1);

        assertThat(firstPage.getTotalElements()).isEqualTo(5);

        List<UUID> idsAcrossPages = Stream.of(firstPage, secondPage, thirdPage)
                .flatMap(page -> page.getContent().stream())
                .map(User::getId)
                .toList();

        assertThat(idsAcrossPages).containsExactlyInAnyOrderElementsOf(ids);

    }

    // Every column a DELETED account carries survives the trip through the table. version is
    // cleared first: Spring Data treats an entity with a non-null @Version as existing and merges it
    // instead of inserting it, and Hibernate refuses to merge a versioned row that is not there.
    @Test
    void saveAndFlush_deletedUserWithAllTimestamps_roundTripsEveryColumn() {

        User user = UserFixtures.deletedUser().version(null).build();

        userRepository.saveAndFlush(user);
        entityManager.clear();

        User loaded = userRepository.findById(user.getId()).orElseThrow();

        assertThat(loaded.getEmail()).isEqualTo(user.getEmail());
        assertThat(loaded.getDisplayName()).isEqualTo(user.getDisplayName());
        assertThat(loaded.getTimezone()).isEqualTo(user.getTimezone());
        assertThat(loaded.getStatus()).isEqualTo(UserStatus.DELETED);
        assertThat(loaded.isDevicesDeleted()).isTrue();
        assertThat(loaded.getKeycloakDisabledAt()).isEqualTo(user.getKeycloakDisabledAt());
        assertThat(loaded.getDeletionRequestedAt()).isEqualTo(user.getDeletionRequestedAt());
        assertThat(loaded.getDeletedAt()).isEqualTo(user.getDeletedAt());
        assertThat(loaded.getKeycloakDeletedAt()).isEqualTo(user.getKeycloakDeletedAt());
        assertThat(loaded.getVersion()).isZero();
        assertThat(loaded.getCreatedAt()).isEqualTo(timestampOf("created_at", loaded));
        assertThat(loaded.getUpdatedAt()).isEqualTo(timestampOf("updated_at", loaded));

        String storedStatus = jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, user.getId());
        assertThat(storedStatus).isEqualTo("DELETED");

    }

    // --- insertIgnoringConflict (ON CONFLICT (id) DO NOTHING) - the just-in-time provisioning insert

    @Test
    void insertIgnoringConflict_newId_insertsRowWithDefaults() {

        UUID id = UUID.randomUUID();

        int inserted = userRepository.insertIgnoringConflict(id, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME);

        User user = userRepository.findById(id).orElseThrow();

        assertThat(inserted).isOne();
        assertThat(user.getId()).isEqualTo(id);
        assertThat(user.getEmail()).isEqualTo(UserFixtures.ARTHUR_MORGAN_EMAIL);
        assertThat(user.getDisplayName()).isEqualTo(UserFixtures.ARTHUR_MORGAN_NAME);
        assertThat(user.getTimezone()).isEqualTo("UTC");
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getVersion()).isZero();
        assertThat(user.isDevicesDeleted()).isFalse();
        assertThat(user.getKeycloakDisabledAt()).isNull();
        assertThat(user.getDeletionRequestedAt()).isNull();
        assertThat(user.getDeletedAt()).isNull();
        assertThat(user.getKeycloakDeletedAt()).isNull();
        assertThat(user.getCreatedAt()).isNotNull();
        assertThat(user.getUpdatedAt()).isNotNull();

    }

    @Test
    void insertIgnoringConflict_existingId_leavesRowUntouched() {

        UUID id = UUID.randomUUID();

        int firstCall = userRepository.insertIgnoringConflict(id, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME);

        int secondCall = userRepository.insertIgnoringConflict(id, UserFixtures.LEON_KENNEDY_EMAIL, UserFixtures.LEON_KENNEDY_NAME);

        User user = userRepository.findById(id).orElseThrow();

        assertThat(firstCall).isOne();
        assertThat(secondCall).isZero();
        assertThat(user.getEmail()).isEqualTo(UserFixtures.ARTHUR_MORGAN_EMAIL);
        assertThat(user.getDisplayName()).isEqualTo(UserFixtures.ARTHUR_MORGAN_NAME);
        assertThat(user.getVersion()).isZero();

    }

    @Test
    void insertIgnoringConflict_newIdWithEmailOfLiveUser_throwsDataIntegrityViolation() {

        UUID existingId = UUID.randomUUID();

        jdbc.update("""
                        INSERT INTO users
                        (id, email, display_name)
                        VALUES
                        (?, ?, ?)
                        """,
                existingId, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME);

        UUID newId = UUID.randomUUID();

        assertThatThrownBy(() -> userRepository.insertIgnoringConflict(newId, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_users_email");

    }

    @Test
    void insertIgnoringConflict_newIdWithEmailOfDeletedUser_insertsRow() {

        UUID existingId = UUID.randomUUID();

        insertUser(existingId, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME, UserStatus.DELETED);

        UUID newId = UUID.randomUUID();

        int inserted = userRepository.insertIgnoringConflict(newId, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME);

        User user = userRepository.findById(newId).orElseThrow();

        assertThat(inserted).isOne();
        assertThat(user.getEmail()).isEqualTo(UserFixtures.ARTHUR_MORGAN_EMAIL);

    }

    @Test
    void insertIgnoringConflict_emailWithoutAtSign_throwsDataIntegrityViolation() {

        UUID newId = UUID.randomUUID();
        String invalidEmail = "example.com";

        assertThatThrownBy(() -> userRepository.insertIgnoringConflict(newId, invalidEmail, UserFixtures.ARTHUR_MORGAN_NAME))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_users_email");

    }

    @Test
    void markDeleting_activeUser_returnsOneAndMovesToDeleting() {

        UUID existingId = UUID.randomUUID();

        insertUser(existingId, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME, UserStatus.ACTIVE);

        int marked = userRepository.markDeleting(existingId);

        User user = userRepository.findById(existingId).orElseThrow();

        assertThat(marked).isOne();
        assertThat(user.getId()).isEqualTo(existingId);
        assertThat(user.getEmail()).isEqualTo(UserFixtures.ARTHUR_MORGAN_EMAIL);
        assertThat(user.getDisplayName()).isEqualTo(UserFixtures.ARTHUR_MORGAN_NAME);
        assertThat(user.getTimezone()).isEqualTo("UTC");
        assertThat(user.getStatus()).isEqualTo(UserStatus.DELETING);
        assertThat(user.getVersion()).isOne();
        assertThat(user.isDevicesDeleted()).isFalse();
        assertThat(user.getKeycloakDisabledAt()).isNull();
        assertThat(user.getDeletionRequestedAt()).isAfter(Instant.parse(YEAR_2000));
        assertThat(user.getDeletedAt()).isNull();
        assertThat(user.getKeycloakDeletedAt()).isNull();
        assertThat(user.getCreatedAt()).isEqualTo(Instant.parse(YEAR_2000));
        assertThat(user.getUpdatedAt()).isAfter(Instant.parse(YEAR_2000));

    }

    @Test
    void markDeleting_deletingUser_returnsZeroAndChangesNothing() {

        UUID existingId = UUID.randomUUID();

        insertUser(existingId, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME, UserStatus.DELETING);

        int marked = userRepository.markDeleting(existingId);

        User user = userRepository.findById(existingId).orElseThrow();

        assertThat(marked).isZero();
        assertThat(user.getStatus()).isEqualTo(UserStatus.DELETING);
        assertThat(user.getVersion()).isZero();
        assertThat(user.getDeletionRequestedAt()).isEqualTo(Instant.parse(YEAR_2000));
        assertThat(user.getUpdatedAt()).isEqualTo(Instant.parse(YEAR_2000));

    }

    @Test
    void markDeleting_deletedUser_returnsZero() {

        UUID existingId = UUID.randomUUID();

        insertUser(existingId, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME, UserStatus.DELETED);

        int marked = userRepository.markDeleting(existingId);

        User user = userRepository.findById(existingId).orElseThrow();

        assertThat(marked).isZero();
        assertThat(user.getStatus()).isEqualTo(UserStatus.DELETED);
        assertThat(user.getVersion()).isZero();
        assertThat(user.getDeletionRequestedAt()).isEqualTo(Instant.parse(YEAR_2000));
        assertThat(user.getDeletedAt()).isEqualTo(Instant.parse(YEAR_2000));

    }

    @Test
    void markDeleting_unknownId_returnsZero() {

        assertThat(userRepository.markDeleting(UUID.randomUUID())).isZero();

    }

    @Test
    void markDeleting_thenHibernateUpdateOfStaleEntity_throwsOptimisticLockingFailure() {

        UUID existingId = UUID.randomUUID();

        insertUser(existingId, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME, UserStatus.ACTIVE);

        User staleUser = userRepository.findById(existingId).orElseThrow();

        userRepository.markDeleting(existingId);

        staleUser.setDisplayName(UserFixtures.LEON_KENNEDY_NAME);

        assertThatThrownBy(() -> userRepository.saveAndFlush(staleUser))
                .isInstanceOf(OptimisticLockingFailureException.class);

        String storedName = jdbc.queryForObject("SELECT display_name FROM users WHERE id = ?", String.class, existingId);
        assertThat(storedName).isEqualTo(UserFixtures.ARTHUR_MORGAN_NAME);

    }

    @Test
    void markDeleting_afterEntityWasLoaded_detachesItAndFindByIdRereadsTheRow() {

        UUID existingId = UUID.randomUUID();

        insertUser(existingId, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME, UserStatus.ACTIVE);

        User loadedBefore = userRepository.findById(existingId).orElseThrow();

        userRepository.markDeleting(existingId);

        User loadedAfter = userRepository.findById(existingId).orElseThrow();

        assertThat(entityManager.contains(loadedBefore)).isFalse();
        assertThat(loadedBefore.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(loadedBefore.getVersion()).isZero();
        assertThat(loadedAfter).isNotSameAs(loadedBefore);
        assertThat(loadedAfter.getStatus()).isEqualTo(UserStatus.DELETING);
        assertThat(loadedAfter.getVersion()).isOne();

    }

    @Test
    void syncEmail_activeUserWithDifferentEmail_updatesEmailAndBumpsVersion() {

        UUID existingId = UUID.randomUUID();

        insertUser(existingId, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME, UserStatus.ACTIVE);

        int synced = userRepository.syncEmail(existingId, UserFixtures.LEON_KENNEDY_EMAIL);

        User user = userRepository.findById(existingId).orElseThrow();

        assertThat(synced).isOne();
        assertThat(user.getEmail()).isEqualTo(UserFixtures.LEON_KENNEDY_EMAIL);
        assertThat(user.getDisplayName()).isEqualTo(UserFixtures.ARTHUR_MORGAN_NAME);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getVersion()).isOne();
        assertThat(user.getCreatedAt()).isEqualTo(Instant.parse(YEAR_2000));
        assertThat(user.getUpdatedAt()).isAfter(Instant.parse(YEAR_2000));

    }

    @Test
    void syncEmail_sameEmail_changesNothing() {

        UUID existingId = UUID.randomUUID();

        insertUser(existingId, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME, UserStatus.ACTIVE);

        int synced = userRepository.syncEmail(existingId, UserFixtures.ARTHUR_MORGAN_EMAIL);

        User user = userRepository.findById(existingId).orElseThrow();

        assertThat(synced).isZero();
        assertThat(user.getEmail()).isEqualTo(UserFixtures.ARTHUR_MORGAN_EMAIL);
        assertThat(user.getVersion()).isZero();
        assertThat(user.getUpdatedAt()).isEqualTo(Instant.parse(YEAR_2000));

    }

    @Test
    void syncEmail_sameEmailDifferentCase_updatesEmail() {

        UUID existingId = UUID.randomUUID();
        String upperCaseEmail = UserFixtures.ARTHUR_MORGAN_EMAIL.toUpperCase(Locale.ROOT);

        insertUser(existingId, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME, UserStatus.ACTIVE);

        int synced = userRepository.syncEmail(existingId, upperCaseEmail);

        User user = userRepository.findById(existingId).orElseThrow();

        assertThat(synced).isOne();
        assertThat(user.getEmail()).isEqualTo(upperCaseEmail);
        assertThat(user.getVersion()).isOne();

    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"DELETING", "DELETED"})
    void syncEmail_deletingOrDeletedUser_changesNothing(UserStatus status) {

        UUID existingId = UUID.randomUUID();

        insertUser(existingId, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME, status);

        int synced = userRepository.syncEmail(existingId, UserFixtures.LEON_KENNEDY_EMAIL);

        User user = userRepository.findById(existingId).orElseThrow();

        assertThat(synced).isZero();
        assertThat(user.getEmail()).isEqualTo(UserFixtures.ARTHUR_MORGAN_EMAIL);
        assertThat(user.getStatus()).isEqualTo(status);
        assertThat(user.getVersion()).isZero();
        assertThat(user.getUpdatedAt()).isEqualTo(Instant.parse(YEAR_2000));

    }

    @Test
    void syncEmail_unknownId_changesNothingAndDoesNotThrow() {

        UUID unknownId = UUID.randomUUID();

        int synced = userRepository.syncEmail(unknownId, UserFixtures.ARTHUR_MORGAN_EMAIL);

        assertThat(synced).isZero();
        assertThat(userRepository.existsById(unknownId)).isFalse();

    }

    @Test
    void syncEmail_emailOfAnotherLiveUser_throwsDataIntegrityViolation() {

        UUID arthurId = UUID.randomUUID();
        UUID leonId = UUID.randomUUID();

        insertUser(arthurId, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME, UserStatus.ACTIVE);
        insertUser(leonId, UserFixtures.LEON_KENNEDY_EMAIL, UserFixtures.LEON_KENNEDY_NAME, UserStatus.ACTIVE);

        assertThatThrownBy(() -> userRepository.syncEmail(leonId, UserFixtures.ARTHUR_MORGAN_EMAIL))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_users_email");

    }

    @Test
    void syncEmail_emailOfDeletedUser_updatesEmail() {

        UUID arthurId = UUID.randomUUID();
        UUID leonId = UUID.randomUUID();

        insertUser(arthurId, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME, UserStatus.DELETED);
        insertUser(leonId, UserFixtures.LEON_KENNEDY_EMAIL, UserFixtures.LEON_KENNEDY_NAME, UserStatus.ACTIVE);

        int synced = userRepository.syncEmail(leonId, UserFixtures.ARTHUR_MORGAN_EMAIL);

        User leon = userRepository.findById(leonId).orElseThrow();

        assertThat(synced).isOne();
        assertThat(leon.getEmail()).isEqualTo(UserFixtures.ARTHUR_MORGAN_EMAIL);
        assertThat(leon.getVersion()).isOne();

    }

    @Test
    void syncEmail_emailWithoutAtSign_throwsDataIntegrityViolation() {

        UUID existingId = UUID.randomUUID();

        insertUser(existingId, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME, UserStatus.ACTIVE);

        assertThatThrownBy(() -> userRepository.syncEmail(existingId, "example.com"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_users_email");

    }

    private void insertUser(UUID id, String email, String displayName, UserStatus status) {

        jdbc.update("""
                        INSERT INTO users
                        (id, email, display_name, status, deletion_requested_at, deleted_at, created_at, updated_at)
                        VALUES
                        (?, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?::timestamptz, ?::timestamptz)
                        """,
                id, email, displayName, status.name(),
                status == UserStatus.ACTIVE ? null : YEAR_2000,
                status == UserStatus.DELETED ? YEAR_2000 : null,
                YEAR_2000, YEAR_2000);

    }

    // Read as an Instant so the comparison doesn't depend on the session's time zone or offset.
    private Instant timestampOf(String column, User user) {

        return Objects.requireNonNull(jdbc.queryForObject("SELECT " + column + " FROM users WHERE id = ?", Instant.class, user.getId()));

    }
}
