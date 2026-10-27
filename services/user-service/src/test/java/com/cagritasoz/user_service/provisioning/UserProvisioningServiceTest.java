package com.cagritasoz.user_service.provisioning;

import com.cagritasoz.user_service.deletion.OrphanEmailService;
import com.cagritasoz.user_service.keycloak.KeycloakUnavailableException;
import com.cagritasoz.user_service.outbox.OutboxService;
import com.cagritasoz.user_service.testsupport.data.UserFixtures;
import com.cagritasoz.user_service.testsupport.jwt.JwtFixtures;
import com.cagritasoz.user_service.user.AccountDeletedException;
import com.cagritasoz.user_service.user.User;
import com.cagritasoz.user_service.user.UserRepository;
import com.cagritasoz.user_service.user.UserStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class UserProvisioningServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private OutboxService outboxService;

    @Mock
    private OrphanEmailService orphanEmailService;

    // Stands in for the real TransactionTemplate and remembers whether a transaction is open, so a test can check that
    // the Keycloak-facing part runs after the transaction has ended.
    private final RecordingTransactions transactions = new RecordingTransactions();

    // A real registry: the mismatch counter is read back in the tests.
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private UserProvisioningService provisioningService;

    @BeforeEach
    void setUp() {

        provisioningService = new UserProvisioningService(userRepository, outboxService, orphanEmailService, transactions, meterRegistry);

    }

    // How many requests so far carried a verified token email that differs from the stored one.
    private double mismatches() {

        return meterRegistry.get("user.email.token.mismatch").counter().count();

    }

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    // A new address for the same person (Arthur), used by every test that needs to show the email
    // actually changing - distinct from UserFixtures.ARTHUR_MORGAN_EMAIL so "unchanged" and
    // "changed" cases can't be confused with each other.
    private static final String NEW_EMAIL = "a.morgan@example.com";

    // One check repeated for every status the API treats as gone. @ParameterizedTest replaces @Test on a method that
    // takes a parameter, and @EnumSource tells JUnit which values to run the body with - once per value, each reported
    // as its own pass or fail, e.g. "ensureUsable_deletingOrDeletedAccount_throwsAccountDeleted[status=DELETING]".
    // DELETION_SCHEDULED is deliberately not here: that account can still be read and cancelled (next test).
    @ParameterizedTest(name = "status={0}")
    @EnumSource(value = UserStatus.class, names = {"DELETING", "DELETED"})
    void ensureUsable_deletingOrDeletedAccount_throwsAccountDeleted(UserStatus status) {

        User user = UserFixtures.activeUser().id(USER_ID).status(status).build();
        Jwt token = JwtFixtures.validUser(USER_ID).claim("email", NEW_EMAIL).build();

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> provisioningService.ensureUsable(token))
                .isInstanceOf(AccountDeletedException.class);
        verify(userRepository, never()).insertIgnoringConflict(any(), any(), any());
        assertThat(mismatches()).as("a gone account is rejected before anything is compared").isZero();
        verifyNoInteractions(outboxService);
    }

    // The grace period: the account is let through so the user can see the banner and cancel; what a scheduled account
    // may not do is enforced where the row is changed, not here.
    @Test
    void ensureUsable_deletionScheduledAccount_isLetThroughAndWritesNothing() {

        User user = UserFixtures.activeUser().id(USER_ID).status(UserStatus.DELETION_SCHEDULED).build();
        Jwt token = JwtFixtures.validUser(USER_ID).build();

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        assertThatCode(() -> provisioningService.ensureUsable(token)).doesNotThrowAnyException();
        verifyNoInteractions(outboxService);
    }

    // The token's email is read to create the row and never written into it afterwards (a token is a copy of
    // Keycloak's state up to 5 minutes old, and writing it back would flip a freshly changed email back). A verified
    // email that differs is only counted: no update, no event, not even a second read of the row.
    @Test
    void ensureUsable_verifiedTokenEmailDiffersFromTheRow_countsItAndWritesNothing() {

        User user = UserFixtures.activeUser().id(USER_ID).build();
        Jwt token = JwtFixtures.validUser(USER_ID).claim("email", NEW_EMAIL).build();

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        provisioningService.ensureUsable(token);

        assertThat(mismatches()).isEqualTo(1.0);
        assertThat(user.getEmail()).isEqualTo(UserFixtures.ARTHUR_MORGAN_EMAIL);
        verify(userRepository, times(1)).findById(USER_ID);
        verifyNoMoreInteractions(userRepository);
        verifyNoInteractions(outboxService);
    }

    // Every request with a differing token counts, which is why a legitimate change makes noise for up to 5 minutes.
    @Test
    void ensureUsable_verifiedTokenEmailDiffersOnEveryRequest_countsEveryRequest() {

        User user = UserFixtures.activeUser().id(USER_ID).build();
        Jwt token = JwtFixtures.validUser(USER_ID).claim("email", NEW_EMAIL).build();

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        provisioningService.ensureUsable(token);
        provisioningService.ensureUsable(token);
        provisioningService.ensureUsable(token);

        assertThat(mismatches()).isEqualTo(3.0);
    }

    // A scheduled account is let through, so a divergence on it is detected like any other.
    @Test
    void ensureUsable_deletionScheduledAccountWithDifferingEmail_isCounted() {

        User user = UserFixtures.activeUser().id(USER_ID).status(UserStatus.DELETION_SCHEDULED).build();
        Jwt token = JwtFixtures.validUser(USER_ID).claim("email", NEW_EMAIL).build();

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        provisioningService.ensureUsable(token);

        assertThat(mismatches()).isEqualTo(1.0);
    }

    // Every shape of token that must NOT count. Unlike the status test above, the thing varying here isn't one enum
    // value - it's the whole shape of the token (which claims are present, and with what values) - so @EnumSource
    // can't express it. @MethodSource points JUnit at a static factory method (by name) that returns one entry per
    // run instead. Each entry is wrapped in Named.named("label", value): the test method still receives the plain
    // Jwt, but {0} in the report shows the label rather than Jwt's unreadable toString(), so a failure says e.g.
    // "[email changed, email_verified=false]".
    static Stream<Named<Jwt>> tokensThatMustNotCountAsAMismatch() {
        return Stream.of(
                // Stored email (UserFixtures default) equals the token's (JwtFixtures default) -
                // both are Arthur Morgan's address.
                Named.named("email unchanged",
                        JwtFixtures.validUser(USER_ID).build()),
                // Keycloak lower-cases every address while the row keeps what it was given, and the unique index
                // treats the two as the same address: a difference of case alone is no divergence.
                Named.named("same address in upper case",
                        JwtFixtures.validUser(USER_ID).claim("email", UserFixtures.ARTHUR_MORGAN_EMAIL.toUpperCase(java.util.Locale.ROOT)).build()),
                // No email claim at all - nothing to compare.
                Named.named("email claim missing",
                        JwtFixtures.minimalToken(USER_ID).claim("email_verified", true).build()),
                // email_verified=true is deliberately included here, unlike the other "changed" cases below:
                // without it, this would pass even if the blank check did not exist (it would be skipped by the
                // verified check instead), which wouldn't actually prove the isBlank() guard does anything.
                Named.named("email changed, email is blank",
                        JwtFixtures.minimalToken(USER_ID)
                                .claim("email", " ")
                                .claim("email_verified", true)
                                .build()),
                Named.named("email changed, email_verified missing",
                        JwtFixtures.minimalToken(USER_ID).claim("email", NEW_EMAIL).build()),
                Named.named("email changed, email_verified=false",
                        JwtFixtures.validUser(USER_ID)
                                .claim("email", NEW_EMAIL)
                                .claim("email_verified", false)
                                .build())
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tokensThatMustNotCountAsAMismatch")
    void ensureUsable_activeUser_tokenThatSaysNothingAboutADivergence_isNotCounted(Jwt token) {

        User user = UserFixtures.activeUser().id(USER_ID).build();

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        provisioningService.ensureUsable(token);

        assertThat(mismatches()).isZero();

        // An existing user is not a new registration, and nothing is written.
        verifyNoMoreInteractions(userRepository);
        verifyNoInteractions(outboxService);
    }

    // The one case none of the tests above cover: an entirely new user, with valid claims,
    // actually succeeding end to end - provision()'s happy path.
    //
    // findById is called TWICE on this path: once here in ensureUsable (there's no row yet, so it
    // must return empty to trigger provision()), and again inside provision() itself once the
    // insert has run, which now must find the row - that's what lets ensureUsable finish without
    // throwing. A single when(...).thenReturn(oneValue) can't express "different result each
    // call" - thenReturn's varargs form can: the first call gets the first argument, every call
    // after that (here, just the second one) gets the last argument given.
    @Test
    void ensureUsable_nonExistentUser_isProvisioned() {

        Jwt token = JwtFixtures.validUser(USER_ID).build();
        User provisionedUser = UserFixtures.activeUser().id(USER_ID).build();

        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.empty(), Optional.of(provisionedUser));

        // 1 = this call actually inserted the row, i.e. it won any race and must record the event.
        // An unstubbed mock returns 0, which the service reads as "the row already existed" - without
        // this stub the test would silently exercise the lost-race path instead.
        when(userRepository.insertIgnoringConflict(USER_ID, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME))
                .thenReturn(1);

        provisioningService.ensureUsable(token);

        // Proves HOW the row came to exist, not just that ensureUsable didn't throw: the right
        // id, the token's email, and Arthur Morgan's name - JwtFixtures.validUser's default
        // "name" claim, so resolveDisplayName never has to fall back to given_name/preferred_username here.
        verify(userRepository).insertIgnoringConflict(USER_ID, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME);

        // The event is recorded with the user as LOADED back from the database (the second findById),
        // not one built locally - so it carries the database's version and created_at.
        verify(outboxService).recordUserRegistered(provisionedUser);

        // provisionedUser's email matches the token's (both fixtures default to the same address), so a newly
        // created user is not a divergence either.
        assertThat(mismatches()).isZero();
    }

    // The request that LOSES the provisioning race: a concurrent request already created the row, so
    // insertIgnoringConflict returns 0. This request still gets the user and carries on (no error),
    // but must not record a second UserRegistered - exactly one event per user, from the winner.
    // The 0 is stubbed explicitly even though it is a mock's default, so the intent is visible.
    @Test
    void ensureUsable_nonExistentUser_insertLostRace_doesNotRecordEvent() {

        Jwt token = JwtFixtures.validUser(USER_ID).build();
        User winnersUser = UserFixtures.activeUser().id(USER_ID).build();

        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.empty(), Optional.of(winnersUser));
        when(userRepository.insertIgnoringConflict(USER_ID, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME))
                .thenReturn(0);

        provisioningService.ensureUsable(token);

        verify(userRepository).insertIgnoringConflict(USER_ID, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME);
        verifyNoInteractions(outboxService);
    }

    // resolveDisplayName is private - it's only reachable by actually going through provision()'s
    // success path, exactly like the happy-path test above. What varies here is BOTH the token
    // (which of name/given_name/preferred_username is present) AND what the resulting display
    // name should be, so each entry needs two values, not one - @MethodSource can return
    // Stream<Arguments> instead of Stream<Named<T>> for exactly this: Arguments.of(a, b) bundles
    // several values into one test invocation, and the test method below takes two parameters to
    // match. Wrapping only the token in Named(...) keeps the report readable ({0} shows the label,
    // {1} shows the plain expected string) without needing to wrap every argument.
    static Stream<Arguments> displayNameFallbackCases() {
        return Stream.of(
                Arguments.of(
                        Named.named("name claim present", JwtFixtures.minimalToken(USER_ID)
                                .claim("email", NEW_EMAIL).claim("email_verified", true)
                                .claim("name", UserFixtures.LEON_KENNEDY_NAME).build()),
                        UserFixtures.LEON_KENNEDY_NAME),
                // A blank "name" (whitespace-only) must be treated the same as absent - falls
                // through to given_name, same as resolveDisplayName's own name.isBlank() check.
                // given_name is typically just a first name, unlike the full "name" claim - hence
                // the plain literal rather than one of the full-name constants.
                Arguments.of(
                        Named.named("name blank, falls back to given_name", JwtFixtures.minimalToken(USER_ID)
                                .claim("email", NEW_EMAIL).claim("email_verified", true)
                                .claim("name", " ").claim("given_name", "Arthur").build()),
                        "Arthur"),
                Arguments.of(
                        Named.named("name/given_name absent, preferred_username has @", JwtFixtures.minimalToken(USER_ID)
                                .claim("email", NEW_EMAIL).claim("email_verified", true)
                                .claim("preferred_username", UserFixtures.JOHN_MARSTON_EMAIL).build()),
                        "john.marston"),
                // No "@" in preferred_username at all: indexOf returns -1, at > 0 is false, so the
                // whole string is used as-is rather than an empty/negative substring.
                Arguments.of(
                        Named.named("preferred_username has no @", JwtFixtures.minimalToken(USER_ID)
                                .claim("email", NEW_EMAIL).claim("email_verified", true)
                                .claim("preferred_username", "johnmarston").build()),
                        "johnmarston"),
                // Documents real, if unusual, current behavior rather than a realistic Keycloak
                // value: indexOf('@') is 0 here, and the guard is "at > 0", not "at >= 0" - so a
                // username starting with "@" also falls into the no-"@"-found branch and comes
                // back unchanged, "@" included, not an empty string.
                Arguments.of(
                        Named.named("preferred_username starts with @", JwtFixtures.minimalToken(USER_ID)
                                .claim("email", NEW_EMAIL).claim("email_verified", true)
                                .claim("preferred_username", "@leonkennedy").build()),
                        "@leonkennedy")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("displayNameFallbackCases")
    void ensureUsable_nonExistentUser_resolvesDisplayNameFromClaims(Jwt token, String expectedDisplayName) {

        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.empty(), Optional.of(UserFixtures.activeUser().id(USER_ID).build()));

        // These cases are about a user that really gets created (1 row inserted), not a lost race.
        when(userRepository.insertIgnoringConflict(eq(USER_ID), eq(NEW_EMAIL), any())).thenReturn(1);

        provisioningService.ensureUsable(token);

        // The email is fixed (eq(NEW_EMAIL)) so this only varies, and only asserts on, the one
        // thing each case is actually about: the resolved display name.
        ArgumentCaptor<String> displayNameCaptor = ArgumentCaptor.forClass(String.class);
        verify(userRepository).insertIgnoringConflict(eq(USER_ID), eq(NEW_EMAIL), displayNameCaptor.capture());
        assertThat(displayNameCaptor.getValue()).isEqualTo(expectedDisplayName);
    }

    // The last link in the fallback chain: none of name/given_name/preferred_username usable.
    // Stays its own @Test rather than a 6th row above - it asserts a thrown exception instead of a
    // captured value, a genuinely different shape of check, same reasoning as why the "must sync"
    // case earlier stayed separate from the "must not sync" parameterized list.
    //
    // Only a single findById stub is needed (unlike the success cases above): resolveDisplayName
    // throws while its result is still being evaluated as an argument to insertIgnoringConflict,
    // so that call - and therefore provision()'s second findById - is never reached.
    @Test
    void provision_noDisplayNameClaimsUsable_throwsMissingIdentityClaim() {

        Jwt token = JwtFixtures.minimalToken(USER_ID)
                .claim("email", NEW_EMAIL)
                .claim("email_verified", true)
                .build();

        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provisioningService.ensureUsable(token))
                .isInstanceOf(MissingIdentityClaimException.class)
                .hasMessage("display_name");

        verify(userRepository, never()).insertIgnoringConflict(any(), any(), any());
        verifyNoInteractions(outboxService);
    }

    @Test
    void provision_missingEmailClaim_throwsMissingIdentityClaim() {

        Jwt token = JwtFixtures.minimalToken(USER_ID).build();

        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provisioningService.ensureUsable(token))
                .isInstanceOf(MissingIdentityClaimException.class)
                .hasMessage("email");
        verify(userRepository, never()).insertIgnoringConflict(any(), any(), any());
        verifyNoInteractions(outboxService);
    }

    @Test
    void provision_missingEmailVerifiedClaim_throwsEmailNotVerified() {

        Jwt token = JwtFixtures.minimalToken(USER_ID).claim("email", UserFixtures.JOHN_MARSTON_EMAIL).build();

        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provisioningService.ensureUsable(token))
                .isInstanceOf(EmailNotVerifiedException.class);
        verify(userRepository, never()).insertIgnoringConflict(any(), any(), any());
        verifyNoInteractions(outboxService);
    }

    @Test
    void provision_userNotFoundAfterInserting_throwsIllegalState() {

        Jwt token = JwtFixtures.validUser(USER_ID).build();

        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(userRepository.insertIgnoringConflict(USER_ID, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME))
                .thenReturn(1); // Inserted, yet not readable: only a bug can do that. (0 would mean "the address is taken".)

        assertThatThrownBy(() -> provisioningService.ensureUsable(token))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(orphanEmailService);
    }

    // --- the address of a new registration belongs to another account (the insert returned 0 and there is no row with
    // this id): ask whether that account is an orphan, after the transaction

    private void newUserWhoseAddressIsTaken() {

        // Nothing inserted and nothing to read back: uq_users_email refused the insert.
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(userRepository.insertIgnoringConflict(USER_ID, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME))
                .thenReturn(0);

    }

    @Test
    void ensureUsable_addressHeldByAnOrphan_throwsEmailBeingReleasedAndWritesNothing() {

        Jwt token = JwtFixtures.validUser(USER_ID).build();
        newUserWhoseAddressIsTaken();
        when(orphanEmailService.releaseIfOrphaned(UserFixtures.ARTHUR_MORGAN_EMAIL)).thenReturn(OrphanEmailService.Outcome.ORPHAN_RELEASING);

        assertThatThrownBy(() -> provisioningService.ensureUsable(token))
                .isInstanceOf(EmailBeingReleasedException.class);

        verifyNoInteractions(outboxService);
    }

    // The holder vanished between the refused insert and the lookup (its address was scrubbed meanwhile): the same
    // request will work in a moment, so the answer is the same "try again".
    @Test
    void ensureUsable_addressNoLongerHeld_throwsEmailBeingReleased() {

        Jwt token = JwtFixtures.validUser(USER_ID).build();
        newUserWhoseAddressIsTaken();
        when(orphanEmailService.releaseIfOrphaned(UserFixtures.ARTHUR_MORGAN_EMAIL)).thenReturn(OrphanEmailService.Outcome.NO_HOLDER);

        assertThatThrownBy(() -> provisioningService.ensureUsable(token))
                .isInstanceOf(EmailBeingReleasedException.class);
    }

    @Test
    void ensureUsable_addressHeldByALiveAccount_throwsEmailAlreadyInUse() {

        Jwt token = JwtFixtures.validUser(USER_ID).build();
        newUserWhoseAddressIsTaken();
        when(orphanEmailService.releaseIfOrphaned(UserFixtures.ARTHUR_MORGAN_EMAIL)).thenReturn(OrphanEmailService.Outcome.HELD_BY_LIVE_ACCOUNT);

        assertThatThrownBy(() -> provisioningService.ensureUsable(token))
                .isInstanceOf(EmailAlreadyInUseException.class);

        verifyNoInteractions(outboxService);
    }

    // Keycloak down, slow or the circuit open: nothing can be decided, and the exception reaches the handler (503).
    @Test
    void ensureUsable_keycloakCannotBeAsked_propagatesTheFailure() {

        Jwt token = JwtFixtures.validUser(USER_ID).build();
        newUserWhoseAddressIsTaken();
        KeycloakUnavailableException failure = new KeycloakUnavailableException("down", true);
        when(orphanEmailService.releaseIfOrphaned(UserFixtures.ARTHUR_MORGAN_EMAIL)).thenThrow(failure);

        assertThatThrownBy(() -> provisioningService.ensureUsable(token))
                .isSameAs(failure);
    }

    // The point of splitting the transaction from the method: the lookup in Keycloak must not run inside it.
    @Test
    void ensureUsable_addressTaken_asksAboutTheHolderOnlyAfterTheTransactionEnded() {

        Jwt token = JwtFixtures.validUser(USER_ID).build();
        newUserWhoseAddressIsTaken();
        when(orphanEmailService.releaseIfOrphaned(UserFixtures.ARTHUR_MORGAN_EMAIL)).thenAnswer(invocation -> {

            assertThat(transactions.open).as("a transaction is open during the call to Keycloak").isFalse();

            return OrphanEmailService.Outcome.NO_HOLDER;

        });

        assertThatThrownBy(() -> provisioningService.ensureUsable(token))
                .isInstanceOf(EmailBeingReleasedException.class);

        assertThat(transactions.completedExceptionally)
                .as("the transaction that found the address taken was rolled back, not committed")
                .isTrue();
    }

    // The ordinary paths never ask about orphans.
    @Test
    void ensureUsable_existingActiveUser_neverAsksAboutOrphans() {

        Jwt token = JwtFixtures.validUser(USER_ID).build();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(UserFixtures.activeUser().id(USER_ID).build()));

        provisioningService.ensureUsable(token);

        verifyNoInteractions(orphanEmailService);
    }

    // A TransactionOperations that only keeps track of what happened: whether its callback is running, and whether it
    // ended by throwing (which a real transaction would roll back on).
    private static final class RecordingTransactions implements TransactionOperations {

        boolean open;

        boolean completedExceptionally;

        @Override
        public <T> T execute(TransactionCallback<T> action) {

            TransactionStatus status = new SimpleTransactionStatus();
            open = true;
            completedExceptionally = false;

            try {

                return action.doInTransaction(status);

            }
            catch (RuntimeException e) {

                completedExceptionally = true;
                throw e;

            }
            finally {

                open = false;

            }

        }
    }
}
