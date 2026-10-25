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

import java.sql.SQLException;
import java.time.Duration;
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
    private static final String FAR_FUTURE = "2999-01-01T00:00:00Z";

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
        assertThat(loaded.getDeletionScheduledFor()).isNull();
        assertThat(loaded.getDeletionStartedAt()).isNull();

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
        assertThat(loaded.getDeletionRequestedAt()).isEqualTo(user.getDeletionRequestedAt());
        assertThat(loaded.getDeletionScheduledFor()).isEqualTo(user.getDeletionScheduledFor());
        assertThat(loaded.getDeletionStartedAt()).isEqualTo(user.getDeletionStartedAt());
        assertThat(loaded.getKeycloakDeletedAt()).isEqualTo(user.getKeycloakDeletedAt());
        assertThat(loaded.getDeletedAt()).isEqualTo(user.getDeletedAt());
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
        assertThat(user.getDeletionRequestedAt()).isNull();
        assertThat(user.getDeletionScheduledFor()).isNull();
        assertThat(user.getDeletionStartedAt()).isNull();
        assertThat(user.getKeycloakDeletedAt()).isNull();
        assertThat(user.getDeletedAt()).isNull();
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
                .hasMessageContaining("uq_users_email")
                .rootCause().isInstanceOfSatisfying(SQLException.class,
                        cause -> assertThat(cause.getSQLState()).isEqualTo("23505"));

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
                .hasMessageContaining("chk_users_email")
                .rootCause().isInstanceOfSatisfying(SQLException.class,
                        cause -> assertThat(cause.getSQLState()).isEqualTo("23514"));

    }

    // --- scheduleDeletion: ACTIVE -> DELETION_SCHEDULED, a compare-and-set whose deadline the database computes

    @Test
    void scheduleDeletion_activeUser_returnsOneAndSchedulesWithTheDeadlineComputedByTheDatabase() {

        UUID existingId = UUID.randomUUID();

        insertUser(existingId, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME, UserStatus.ACTIVE);

        int scheduled = userRepository.scheduleDeletion(existingId, "PT5M");

        User user = userRepository.findById(existingId).orElseThrow();

        assertThat(scheduled).isOne();
        assertThat(user.getId()).isEqualTo(existingId);
        assertThat(user.getEmail()).isEqualTo(UserFixtures.ARTHUR_MORGAN_EMAIL);
        assertThat(user.getDisplayName()).isEqualTo(UserFixtures.ARTHUR_MORGAN_NAME);
        assertThat(user.getTimezone()).isEqualTo("UTC");
        assertThat(user.getStatus()).isEqualTo(UserStatus.DELETION_SCHEDULED);
        assertThat(user.getVersion()).isOne();
        assertThat(user.getDeletionRequestedAt()).isAfter(Instant.parse(YEAR_2000));
        // Both come from CURRENT_TIMESTAMP of the same statement, so the grace period is exact.
        assertThat(user.getDeletionScheduledFor()).isEqualTo(user.getDeletionRequestedAt().plusSeconds(300));
        assertThat(user.getDeletionStartedAt()).isNull();
        assertThat(user.getKeycloakDeletedAt()).isNull();
        assertThat(user.getDeletedAt()).isNull();
        assertThat(user.getCreatedAt()).isEqualTo(Instant.parse(YEAR_2000));
        assertThat(user.getUpdatedAt()).isAfter(Instant.parse(YEAR_2000));

    }

    // Production grace periods are days: the ISO duration of 14 days is "PT336H", which Postgres must read as an interval.
    @Test
    void scheduleDeletion_longGracePeriod_addsExactlyThatManyDays() {

        UUID existingId = UUID.randomUUID();

        insertUser(existingId, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME, UserStatus.ACTIVE);

        userRepository.scheduleDeletion(existingId, Duration.ofDays(14).toString());

        User user = userRepository.findById(existingId).orElseThrow();

        assertThat(Duration.between(user.getDeletionRequestedAt(), user.getDeletionScheduledFor())).isEqualTo(Duration.ofDays(14));

    }

    @Test
    void scheduleDeletion_alreadyScheduledUser_returnsZeroAndKeepsTheOriginalDeadline() {

        UUID existingId = UUID.randomUUID();

        insertScheduledUser(existingId, FAR_FUTURE);

        int scheduled = userRepository.scheduleDeletion(existingId, "PT5M");

        User user = userRepository.findById(existingId).orElseThrow();

        assertThat(scheduled).isZero();
        assertThat(user.getStatus()).isEqualTo(UserStatus.DELETION_SCHEDULED);
        assertThat(user.getVersion()).isZero();
        assertThat(user.getDeletionScheduledFor()).isEqualTo(Instant.parse(FAR_FUTURE));
        assertThat(user.getUpdatedAt()).isEqualTo(Instant.parse(YEAR_2000));

    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"DELETING", "DELETED"})
    void scheduleDeletion_deletingOrDeletedUser_returnsZeroAndChangesNothing(UserStatus status) {

        UUID existingId = UUID.randomUUID();

        insertUser(existingId, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME, status);

        int scheduled = userRepository.scheduleDeletion(existingId, "PT5M");

        User user = userRepository.findById(existingId).orElseThrow();

        assertThat(scheduled).isZero();
        assertThat(user.getStatus()).isEqualTo(status);
        assertThat(user.getVersion()).isZero();
        assertThat(user.getDeletionRequestedAt()).isEqualTo(Instant.parse(YEAR_2000));
        assertThat(user.getUpdatedAt()).isEqualTo(Instant.parse(YEAR_2000));

    }

    @Test
    void scheduleDeletion_unknownId_returnsZero() {

        assertThat(userRepository.scheduleDeletion(UUID.randomUUID(), "PT5M")).isZero();

    }

    @Test
    void scheduleDeletion_thenHibernateUpdateOfStaleEntity_throwsOptimisticLockingFailure() {

        UUID existingId = UUID.randomUUID();

        insertUser(existingId, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME, UserStatus.ACTIVE);

        User staleUser = userRepository.findById(existingId).orElseThrow();

        userRepository.scheduleDeletion(existingId, "PT5M");

        staleUser.setDisplayName(UserFixtures.LEON_KENNEDY_NAME);

        // A PATCH that read the row before the schedule committed cannot overwrite it afterwards.
        assertThatThrownBy(() -> userRepository.saveAndFlush(staleUser))
                .isInstanceOf(OptimisticLockingFailureException.class);

        String storedName = jdbc.queryForObject("SELECT display_name FROM users WHERE id = ?", String.class, existingId);
        assertThat(storedName).isEqualTo(UserFixtures.ARTHUR_MORGAN_NAME);

    }

    @Test
    void scheduleDeletion_afterEntityWasLoaded_detachesItAndFindByIdRereadsTheRow() {

        UUID existingId = UUID.randomUUID();

        insertUser(existingId, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME, UserStatus.ACTIVE);

        User loadedBefore = userRepository.findById(existingId).orElseThrow();

        userRepository.scheduleDeletion(existingId, "PT5M");

        User loadedAfter = userRepository.findById(existingId).orElseThrow();

        assertThat(entityManager.contains(loadedBefore)).isFalse();
        assertThat(loadedBefore.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(loadedBefore.getVersion()).isZero();
        assertThat(loadedAfter).isNotSameAs(loadedBefore);
        assertThat(loadedAfter.getStatus()).isEqualTo(UserStatus.DELETION_SCHEDULED);
        assertThat(loadedAfter.getVersion()).isOne();

    }

    // --- cancelDeletion: DELETION_SCHEDULED -> ACTIVE, only while the deadline lies ahead

    @Test
    void cancelDeletion_scheduledUserBeforeTheDeadline_returnsOneAndClearsTheDeletionColumns() {

        UUID existingId = UUID.randomUUID();

        insertScheduledUser(existingId, FAR_FUTURE);

        int cancelled = userRepository.cancelDeletion(existingId);

        User user = userRepository.findById(existingId).orElseThrow();

        assertThat(cancelled).isOne();
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getVersion()).isOne();
        assertThat(user.getDeletionRequestedAt()).isNull();
        assertThat(user.getDeletionScheduledFor()).isNull();
        assertThat(user.getDeletionStartedAt()).isNull();
        assertThat(user.getUpdatedAt()).isAfter(Instant.parse(YEAR_2000));

    }

    // The deadline is the user's last moment: after it the promoter owns the row, even before it has run.
    @Test
    void cancelDeletion_scheduledUserAfterTheDeadline_returnsZeroAndChangesNothing() {

        UUID existingId = UUID.randomUUID();

        insertScheduledUser(existingId, YEAR_2000);

        int cancelled = userRepository.cancelDeletion(existingId);

        User user = userRepository.findById(existingId).orElseThrow();

        assertThat(cancelled).isZero();
        assertThat(user.getStatus()).isEqualTo(UserStatus.DELETION_SCHEDULED);
        assertThat(user.getVersion()).isZero();
        assertThat(user.getDeletionScheduledFor()).isEqualTo(Instant.parse(YEAR_2000));

    }

    // The comparison is strict: a deadline equal to the database's "now" is already too late. (A zero grace period is
    // refused by the service; the query itself is exercised here with PT0S, whose deadline is exactly the request time
    // and, in this one transaction, exactly CURRENT_TIMESTAMP.)
    @Test
    void cancelDeletion_deadlineEqualToNow_returnsZero() {

        UUID existingId = UUID.randomUUID();

        insertUser(existingId, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME, UserStatus.ACTIVE);
        userRepository.scheduleDeletion(existingId, "PT0S");

        assertThat(userRepository.cancelDeletion(existingId)).isZero();

    }

    @Test
    void cancelDeletion_activeUser_returnsZeroAndChangesNothing() {

        UUID existingId = UUID.randomUUID();

        insertUser(existingId, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME, UserStatus.ACTIVE);

        int cancelled = userRepository.cancelDeletion(existingId);

        User user = userRepository.findById(existingId).orElseThrow();

        assertThat(cancelled).isZero();
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getVersion()).isZero();
        assertThat(user.getUpdatedAt()).isEqualTo(Instant.parse(YEAR_2000));

    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"DELETING", "DELETED"})
    void cancelDeletion_deletingOrDeletedUser_returnsZeroAndChangesNothing(UserStatus status) {

        UUID existingId = UUID.randomUUID();

        insertUser(existingId, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME, status);

        int cancelled = userRepository.cancelDeletion(existingId);

        User user = userRepository.findById(existingId).orElseThrow();

        assertThat(cancelled).isZero();
        assertThat(user.getStatus()).isEqualTo(status);
        assertThat(user.getVersion()).isZero();
        assertThat(user.getDeletionRequestedAt()).isEqualTo(Instant.parse(YEAR_2000));

    }

    @Test
    void cancelDeletion_unknownId_returnsZero() {

        assertThat(userRepository.cancelDeletion(UUID.randomUUID())).isZero();

    }

    // Cancelling must leave a row the table accepts as ACTIVE (no deletion timestamps), so the user can schedule again.
    @Test
    void scheduleCancelSchedule_cycle_worksAndEndsAtVersionThree() {

        UUID existingId = UUID.randomUUID();

        insertUser(existingId, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME, UserStatus.ACTIVE);

        assertThat(userRepository.scheduleDeletion(existingId, "PT5M")).isOne();
        assertThat(userRepository.cancelDeletion(existingId)).isOne();
        assertThat(userRepository.scheduleDeletion(existingId, "PT5M")).isOne();

        User user = userRepository.findById(existingId).orElseThrow();

        assertThat(user.getStatus()).isEqualTo(UserStatus.DELETION_SCHEDULED);
        assertThat(user.getVersion()).isEqualTo(3L);

    }

    // --- findIdsDueForPromotion: the promoter's work list

    @Test
    void findIdsDueForPromotion_returnsOnlyScheduledUsersWhoseDeadlineHasPassed() {

        UUID overdue = UUID.randomUUID();
        UUID stillInGrace = UUID.randomUUID();
        UUID active = UUID.randomUUID();
        UUID deleting = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();

        insertScheduledUser(overdue, YEAR_2000);
        insertScheduledUser(stillInGrace, FAR_FUTURE);
        insertUser(active, emailOf(active), UserFixtures.ARTHUR_MORGAN_NAME, UserStatus.ACTIVE);
        insertUser(deleting, emailOf(deleting), UserFixtures.ARTHUR_MORGAN_NAME, UserStatus.DELETING);
        insertUser(deleted, emailOf(deleted), UserFixtures.ARTHUR_MORGAN_NAME, UserStatus.DELETED);

        assertThat(userRepository.findIdsDueForPromotion(10)).containsExactly(overdue);

    }

    // Oldest deadline first, ties by id (the fixed ids are written out, see their declaration), and the limit
    // cuts the list after that order, so a backlog is worked off from its oldest end.
    @Test
    void findIdsDueForPromotion_ordersByDeadlineThenIdAndHonoursTheLimit() {

        insertScheduledUser(TIED_HIGH_ID, "2000-01-02T00:00:00Z");
        insertScheduledUser(TIED_LOW_ID, "2000-01-02T00:00:00Z");
        insertScheduledUser(NEWEST_ID, "2000-01-01T12:00:00Z"); // The earliest deadline, though the highest id.

        assertThat(userRepository.findIdsDueForPromotion(10)).containsExactly(NEWEST_ID, TIED_LOW_ID, TIED_HIGH_ID);
        assertThat(userRepository.findIdsDueForPromotion(2)).containsExactly(NEWEST_ID, TIED_LOW_ID);

    }

    // "Due" includes the moment itself (<=), while cancelling needs the deadline strictly ahead (>): at exactly the
    // deadline the account can no longer be cancelled and is already due, so no instant belongs to neither.
    @Test
    void findIdsDueForPromotion_deadlineEqualToNow_isDue() {

        UUID existingId = UUID.randomUUID();

        insertUser(existingId, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME, UserStatus.ACTIVE);
        userRepository.scheduleDeletion(existingId, "PT0S");

        assertThat(userRepository.findIdsDueForPromotion(10)).containsExactly(existingId);

    }

    @Test
    void findIdsDueForPromotion_nobodyDue_returnsAnEmptyList() {

        assertThat(userRepository.findIdsDueForPromotion(10)).isEmpty();

    }

    // --- promoteDeletion: DELETION_SCHEDULED -> DELETING once the deadline has passed

    @Test
    void promoteDeletion_overdueScheduledUser_returnsOneAndScrubsThePersonalData() {

        UUID existingId = UUID.randomUUID();

        insertScheduledUser(existingId, YEAR_2000);

        int promoted = userRepository.promoteDeletion(existingId);

        User user = userRepository.findById(existingId).orElseThrow();

        assertThat(promoted).isOne();
        assertThat(user.getStatus()).isEqualTo(UserStatus.DELETING);
        assertThat(user.getEmail()).isEqualTo("deleted+" + existingId + "@invalid");
        assertThat(user.getDisplayName()).isEqualTo("Deleted user");
        assertThat(user.getVersion()).isOne();
        assertThat(user.getDeletionStartedAt()).isAfter(Instant.parse(YEAR_2000)); // Stamped by the database.
        assertThat(user.getUpdatedAt()).isAfter(Instant.parse(YEAR_2000)); // The V6 trigger ran.

        // What the user asked for and when it was due stays on the row as history; nothing else is touched.
        assertThat(user.getDeletionRequestedAt()).isEqualTo(Instant.parse(YEAR_2000));
        assertThat(user.getDeletionScheduledFor()).isEqualTo(Instant.parse(YEAR_2000));
        assertThat(user.getKeycloakDeletedAt()).isNull();
        assertThat(user.getDeletedAt()).isNull();
        assertThat(user.getTimezone()).isEqualTo("UTC");

    }

    // The grace period is the user's: a row that is not overdue is never promoted, whatever the work list said.
    @Test
    void promoteDeletion_beforeTheDeadline_returnsZeroAndChangesNothing() {

        UUID existingId = UUID.randomUUID();

        insertScheduledUser(existingId, FAR_FUTURE);

        int promoted = userRepository.promoteDeletion(existingId);

        User user = userRepository.findById(existingId).orElseThrow();

        assertThat(promoted).isZero();
        assertThat(user.getStatus()).isEqualTo(UserStatus.DELETION_SCHEDULED);
        assertThat(user.getEmail()).isEqualTo(emailOf(existingId));
        assertThat(user.getDisplayName()).isEqualTo(UserFixtures.ARTHUR_MORGAN_NAME);
        assertThat(user.getVersion()).isZero();
        assertThat(user.getDeletionStartedAt()).isNull();

    }

    @Test
    void promoteDeletion_deadlineEqualToNow_returnsOne() {

        UUID existingId = UUID.randomUUID();

        insertUser(existingId, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME, UserStatus.ACTIVE);
        userRepository.scheduleDeletion(existingId, "PT0S");

        assertThat(userRepository.promoteDeletion(existingId)).isOne();

    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"ACTIVE", "DELETING", "DELETED"})
    void promoteDeletion_userNotScheduled_returnsZeroAndKeepsTheAddress(UserStatus status) {

        UUID existingId = UUID.randomUUID();

        insertUser(existingId, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME, status);

        int promoted = userRepository.promoteDeletion(existingId);

        User user = userRepository.findById(existingId).orElseThrow();

        assertThat(promoted).isZero();
        assertThat(user.getStatus()).isEqualTo(status);
        assertThat(user.getEmail()).isEqualTo(UserFixtures.ARTHUR_MORGAN_EMAIL);
        assertThat(user.getDisplayName()).isEqualTo(UserFixtures.ARTHUR_MORGAN_NAME);
        assertThat(user.getVersion()).isZero();

    }

    @Test
    void promoteDeletion_unknownId_returnsZero() {

        assertThat(userRepository.promoteDeletion(UUID.randomUUID())).isZero();

    }

    // Running the same promotion again (a second promoter, or a retry) finds the row DELETING and does nothing.
    @Test
    void promoteDeletion_calledTwice_promotesOnce() {

        UUID existingId = UUID.randomUUID();

        insertScheduledUser(existingId, YEAR_2000);

        assertThat(userRepository.promoteDeletion(existingId)).isOne();
        assertThat(userRepository.promoteDeletion(existingId)).isZero();
        assertThat(userRepository.findById(existingId).orElseThrow().getVersion()).isOne();

    }

    // The scrubbed address is unique per id, so any number of accounts can be DELETING at once, and the real address
    // is free for a new account as soon as the old one is promoted (uq_users_email would have refused it before).
    @Test
    void promoteDeletion_releasesTheAddressAndNeverCollidesWithAnotherPromotedUser() {

        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        insertScheduledUser(first, YEAR_2000);
        insertScheduledUser(second, YEAR_2000);

        assertThat(userRepository.promoteDeletion(first)).isOne();
        assertThat(userRepository.promoteDeletion(second)).isOne();

        int reregistered = userRepository.insertIgnoringConflict(UUID.randomUUID(), emailOf(first), UserFixtures.LEON_KENNEDY_NAME);

        assertThat(reregistered).isOne();

    }

    // A DELETION_SCHEDULED row asked for in the year 2000 whose deadline is the given time: a far-future one can
    // still be cancelled, YEAR_2000 itself is long overdue.
    private void insertScheduledUser(UUID id, String scheduledFor) {

        jdbc.update("""
                        INSERT INTO users
                        (id, email, display_name, status, deletion_requested_at, deletion_scheduled_for, created_at, updated_at)
                        VALUES
                        (?, ?, ?, 'DELETION_SCHEDULED', ?::timestamptz, ?::timestamptz, ?::timestamptz, ?::timestamptz)
                        """,
                id, emailOf(id), UserFixtures.ARTHUR_MORGAN_NAME, YEAR_2000, scheduledFor,
                YEAR_2000, YEAR_2000);

    }

    // One address per id: uq_users_email allows no two live accounts with the same one.
    private static String emailOf(UUID id) {

        return "user." + id + "@example.com";

    }

    private void insertUser(UUID id, String email, String displayName, UserStatus status) {

        jdbc.update("""
                        INSERT INTO users
                        (id, email, display_name, status, deletion_requested_at, deletion_scheduled_for,
                         deletion_started_at, deleted_at, created_at, updated_at)
                        VALUES
                        (?, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?::timestamptz, ?::timestamptz,
                         ?::timestamptz, ?::timestamptz)
                        """,
                id, email, displayName, status.name(),
                status == UserStatus.ACTIVE ? null : YEAR_2000,
                status == UserStatus.ACTIVE ? null : YEAR_2000,
                status == UserStatus.DELETING || status == UserStatus.DELETED ? YEAR_2000 : null,
                status == UserStatus.DELETED ? YEAR_2000 : null,
                YEAR_2000, YEAR_2000);

    }

    // Read as an Instant so the comparison doesn't depend on the session's time zone or offset.
    private Instant timestampOf(String column, User user) {

        return Objects.requireNonNull(jdbc.queryForObject("SELECT " + column + " FROM users WHERE id = ?", Instant.class, user.getId()));

    }
}
