package com.cagritasoz.user_service.repository;

import com.cagritasoz.user_service.entity.User;
import com.cagritasoz.user_service.model.UserStatus;
import com.cagritasoz.user_service.testsupport.PostgresTestContainerConfig;
import com.cagritasoz.user_service.testsupport.UserFixtures;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Layer 2: UserRepository against a real Postgres, single-threaded. What the SQL and the entity
// mapping do to ONE row at a time. Two neighbours cover the rest and are not repeated here:
//   - UserSchemaTest: the table's own rules (constraints, defaults, trigger) in plain SQL;
//   - UserRepositoryConcurrencyTest (to be written): what happens when threads race.
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
class UserRepositoryTest {

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

        userRepository.saveAndFlush(user);
        entityManager.clear();

        User loaded = userRepository.findById(user.getId()).orElseThrow();

        assertThat(loaded.getVersion()).isZero();

        // And what the entity reports is what the table holds.
        assertThat(loaded.getCreatedAt()).isEqualTo(timestampOf("created_at", loaded));
        assertThat(loaded.getUpdatedAt()).isEqualTo(timestampOf("updated_at", loaded));

    }

    // Rule for the native-query tests still to be written (see the TODO list at the bottom): they
    // bypass Hibernate's cache, so verify through jdbc (or entityManager.clear() first) - never
    // through a findById that may return the stale, cached entity.

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
    //     the trigger itself is proven in UserSchemaTest.
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

        jdbc.update("INSERT INTO users (id, email, display_name, created_at, updated_at) VALUES (?, ?, ?, ?::timestamptz, ?::timestamptz)",
                id, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME, YEAR_2000, YEAR_2000);

        User user = userRepository.findById(id).orElseThrow();

        user.setDisplayName(UserFixtures.LEON_KENNEDY_NAME);
        user.setCreatedAt(Instant.parse("1990-01-01T00:00:00Z"));

        userRepository.saveAndFlush(user);

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

        assertThat(userRepository.existsById(user.getId())).isTrue();
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

    // TODO saveAndFlush_deletedUserWithAllTimestamps_roundTripsEveryColumn
    //      UserFixtures.deletedUser() saved and read back: status DELETED as a string, all four
    //      nullable/audit Instants and devicesDeleted survive the trip (@Enumerated STRING mapping).

    // --- insertIgnoringConflict (ON CONFLICT (id) DO NOTHING) - the just-in-time provisioning insert
    // TODO insertIgnoringConflict_newId_insertsRowWithDefaults
    //      status ACTIVE, timezone UTC, version 0, devices_deleted false, timestamps filled.
    // TODO insertIgnoringConflict_existingId_leavesRowUntouched
    //      Second call with the same id and DIFFERENT email/displayName: no exception, and the first
    //      call's values are still there (nothing is overwritten).
    // TODO insertIgnoringConflict_newIdWithEmailOfLiveUser_throwsDataIntegrityViolation
    //      ON CONFLICT only covers the id. A different id with a live user's email still hits
    //      uq_users_email and the exception propagates - this is what the handler maps to 409.
    // TODO insertIgnoringConflict_newIdWithEmailOfDeletedUser_insertsRow
    // TODO insertIgnoringConflict_emailWithoutAtSign_throwsDataIntegrityViolation   (chk_users_email)

    // --- markDeleting (ACTIVE -> DELETING compare-and-set)
    // TODO markDeleting_activeUser_returnsOneAndMovesToDeleting
    //      Returns 1; status DELETING; deletion_requested_at set; version + 1; email, displayName
    //      and the other columns unchanged; updated_at bumped by the trigger.
    // TODO markDeleting_deletingUser_returnsZeroAndChangesNothing
    //      Returns 0; deletion_requested_at is NOT re-stamped and version is NOT bumped again.
    // TODO markDeleting_deletedUser_returnsZero
    // TODO markDeleting_unknownId_returnsZero
    // TODO markDeleting_thenHibernateUpdateOfStaleEntity_throwsOptimisticLockingFailure
    //      Load the user, markDeleting through the native query, then saveAndFlush the stale loaded
    //      copy: the native query already moved version to 1, so Hibernate's version check fails.
    //      Deterministic, single-threaded version of "PATCH /me racing DELETE /me".
    // TODO markDeleting_afterEntityWasLoaded_persistenceContextStillShowsOldStatus
    //      Characterization test of the trap the rule above warns about: findById WITHOUT clear()
    //      still says ACTIVE after markDeleting. Documents why callers must not trust an entity they
    //      loaded before a native update. Worth checking against UserProvisioningService, which loads
    //      the user and then calls syncEmail on it.

    // --- syncEmail (email compare-and-set, ACTIVE only)
    // TODO syncEmail_activeUserWithDifferentEmail_updatesEmailAndBumpsVersion
    //      Also updated_at bumped by the trigger.
    // TODO syncEmail_sameEmail_changesNothing
    //      version NOT bumped: the "AND email <> :email" guard is what keeps a no-op from writing.
    // TODO syncEmail_sameEmailDifferentCase_updatesEmail
    //      Characterization: "<>" is case-sensitive while uq_users_email compares lower(email), so a
    //      change of case alone counts as a change. Pin down whichever behavior is intended.
    // TODO syncEmail_deletingOrDeletedUser_changesNothing   (parameterized over the two statuses)
    // TODO syncEmail_unknownId_changesNothingAndDoesNotThrow
    // TODO syncEmail_emailOfAnotherLiveUser_throwsDataIntegrityViolation   (uq_users_email)
    // TODO syncEmail_emailOfDeletedUser_updatesEmail
    // TODO syncEmail_emailWithoutAtSign_throwsDataIntegrityViolation       (chk_users_email)
    //      Also worth deciding: syncEmail returns void, so a caller can't tell whether anything
    //      changed. Returning int like markDeleting would make these assertions direct. Production
    //      change - raise it before touching it.

    // Read as an Instant so the comparison doesn't depend on the session's time zone or offset.
    private Instant timestampOf(String column, User user) {

        return Objects.requireNonNull(jdbc.queryForObject("SELECT " + column + " FROM users WHERE id = ?", Instant.class, user.getId()));

    }
}
