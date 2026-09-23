package com.cagritasoz.user_service.service;

import com.cagritasoz.user_service.entity.User;
import com.cagritasoz.user_service.exception.AccountNotActiveException;
import com.cagritasoz.user_service.exception.EmailNotVerifiedException;
import com.cagritasoz.user_service.exception.MissingIdentityClaimException;
import com.cagritasoz.user_service.model.UserStatus;
import com.cagritasoz.user_service.repository.UserRepository;
import com.cagritasoz.user_service.testsupport.JwtFixtures;
import com.cagritasoz.user_service.testsupport.UserFixtures;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class UserProvisioningServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserProvisioningService provisioningService;

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    // A new address for the same person (Arthur), used by every test that needs to show the email
    // actually changing - distinct from UserFixtures.ARTHUR_MORGAN_EMAIL so "unchanged" and
    // "changed" cases can't be confused with each other.
    private static final String NEW_EMAIL = "a.morgan@example.com";

    // A single @Test method can only check ONE fixed scenario. This behavior - "any status other
    // than ACTIVE gets rejected" - is really the SAME check repeated for every non-ACTIVE value of
    // UserStatus (currently DELETING and DELETED). @ParameterizedTest replaces @Test on a method
    // that takes a parameter, and a "source" annotation (@EnumSource here) tells JUnit what values
    // to run that one method body with - once per value, each run reported as its own separate
    // pass/fail, not lumped together. @EnumSource(mode = EXCLUDE, names = "ACTIVE") means "every
    // UserStatus constant except ACTIVE" - so this runs twice, once with status = DELETING and
    // once with status = DELETED, without two near-identical @Test methods. The (name = "...")
    // customizes what each individual run is labeled as in the test report/output, using {0} for
    // the first (and here, only) parameter - so a failure clearly says which status failed, e.g.
    // "ensureUsable_nonActiveStatus_throwsAccountNotActive[status=DELETING]".
    @ParameterizedTest(name = "status={0}")
    @EnumSource(value = UserStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "ACTIVE")
    void ensureUsable_nonActiveStatus_throwsAccountNotActive(UserStatus status) {

        User user = UserFixtures.activeUser().id(USER_ID).status(status).build();
        Jwt token = JwtFixtures.validUser(USER_ID).build();

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> provisioningService.ensureUsable(token))
                .isInstanceOf(AccountNotActiveException.class);
        verify(userRepository, never()).insertIgnoringConflict(any(), any(), any());
        verify(userRepository, never()).syncEmail(any(), any());
    }

    // The single "do write" case stays its own @Test rather than a row in the source above: it
    // asserts the opposite outcome (verify called, with exact arguments), and mixing both
    // outcomes into one parameterized method would need an if/else inside the test body -
    // conditional logic in a test is exactly where a wrong branch can silently pass.
    @Test
    void ensureUsable_activeUser_verifiedChangedEmail_syncsEmail() {

        User user = UserFixtures.activeUser().id(USER_ID).build();
        Jwt token = JwtFixtures.validUser(USER_ID).claim("email", NEW_EMAIL).build();

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        provisioningService.ensureUsable(token);

        verify(userRepository).syncEmail(USER_ID, NEW_EMAIL);
    }

    // Every way syncEmailIfChanged can decide "don't write". Unlike the status test above, the
    // thing varying here isn't one enum value - it's the whole shape of the token (which claims
    // are present, and with what values) - so @EnumSource can't express it. @MethodSource points
    // JUnit at a static factory method (by name) that returns one entry per run instead.
    // Each entry is wrapped in Named.named("label", value): the test method still receives the
    // plain Jwt, but {0} in the report shows the label rather than Jwt's unreadable toString(),
    // so a failure says e.g. "[email changed, email_verified=false]".
    static Stream<Named<Jwt>> tokensThatMustNotSyncEmail() {
        return Stream.of(
                // Stored email (UserFixtures default) equals the token's (JwtFixtures default) -
                // both are Arthur Morgan's address.
                Named.named("email unchanged",
                        JwtFixtures.validUser(USER_ID).build()),
                // No email claim at all - nothing to sync to; the stored value is kept as-is.
                Named.named("email claim missing",
                        JwtFixtures.minimalToken(USER_ID).claim("email_verified", true).build()),
                // email_verified=true is deliberately included here, unlike the other "changed"
                // cases below: without it, this would pass even if syncEmailIfChanged had no
                // blank check at all (it would be rejected by the verified check instead), which
                // wouldn't actually prove the isBlank() guard does anything.
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
    @MethodSource("tokensThatMustNotSyncEmail")
    void ensureUsable_activeUser_doesNotSyncEmail(Jwt token) {

        User user = UserFixtures.activeUser().id(USER_ID).build();

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        provisioningService.ensureUsable(token);

        // never() + any(): "syncEmail was not called with ANY arguments" - stronger than checking
        // it wasn't called with one specific email.
        verify(userRepository, never()).syncEmail(any(), any());
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

        provisioningService.ensureUsable(token);

        // Proves HOW the row came to exist, not just that ensureUsable didn't throw: the right
        // id, the token's email, and Arthur Morgan's name - JwtFixtures.validUser's default
        // "name" claim, so resolveDisplayName never has to fall back to given_name/preferred_username here.
        verify(userRepository).insertIgnoringConflict(USER_ID, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME);

        // provisionedUser's email already matches the token's (both fixtures default to the same
        // address) - syncEmailIfChanged has nothing to change, so it must not write either.
        verify(userRepository, never()).syncEmail(any(), any());
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
    }

    @Test
    void provision_missingEmailClaim_throwsMissingIdentityClaim() {

        Jwt token = JwtFixtures.minimalToken(USER_ID).build();

        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provisioningService.ensureUsable(token))
                .isInstanceOf(MissingIdentityClaimException.class)
                .hasMessage("email");
        verify(userRepository, never()).insertIgnoringConflict(any(), any(), any());
        verify(userRepository, never()).syncEmail(any(), any());
    }

    @Test
    void provision_missingEmailVerifiedClaim_throwsEmailNotVerified() {

        Jwt token = JwtFixtures.minimalToken(USER_ID).claim("email", UserFixtures.JOHN_MARSTON_EMAIL).build();

        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provisioningService.ensureUsable(token))
                .isInstanceOf(EmailNotVerifiedException.class);
        verify(userRepository, never()).insertIgnoringConflict(any(), any(), any());
        verify(userRepository, never()).syncEmail(any(), any());
    }

    @Test
    void provision_userNotFoundAfterInserting_throwsIllegalState() {

        Jwt token = JwtFixtures.validUser(USER_ID).build();

        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provisioningService.ensureUsable(token))
                .isInstanceOf(IllegalStateException.class);
        verify(userRepository, never()).syncEmail(any(), any());
    }
}
