package com.cagritasoz.user_service.exception;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Layer 1, no mocks: every plain @ExceptionHandler method here just takes an exception and
// returns a ProblemDetail directly - no Spring context, no HTTP round trip, no dependencies to
// fake, so a real GlobalExceptionHandler instance is called exactly like any other Java object.
//
// The two ResponseEntityExceptionHandler overrides (handleMethodArgumentNotValid,
// handleHttpMessageNotReadable) are deliberately NOT covered here: Spring only ever hands them a
// MethodArgumentNotValidException/HttpMessageNotReadableException it built itself mid-request -
// convincingly faking one by hand needs a real BindingResult/HttpInputMessage, which is a lot of
// machinery just to avoid the thing that actually produces them for free: a controller test
// (MockMvc against UserController.updateMe with a bad request body). Cover those there instead.
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleUserNotFoundException_returns404WithMessage() {
        ProblemDetail problemDetail = handler.handleUserNotFoundException(new UserNotFoundException());

        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problemDetail.getDetail()).isEqualTo("User not found.");
        assertThat(problemDetail.getType()).hasToString("https://home-energy-tracker/problems/user-not-found");
    }

    @Test
    void handleOptimisticLockingFailureException_returns409() {
        ProblemDetail problemDetail = handler.handleOptimisticLockingFailureException(
                new OptimisticLockingFailureException("stale version"));

        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    }

    // Kept deliberately generic - the message must never reveal which constraint or value
    // clashed (see the handler's own comment) - so asserting the exact detail text here IS the
    // point, not an incidental check. The SQLState sits on the cause, as it does for real Postgres errors.
    @Test
    void handleDataIntegrityViolationException_uniqueViolation_returns409WithGenericMessage() {
        ProblemDetail problemDetail = handler.handleDataIntegrityViolationException(
                new DataIntegrityViolationException("could not execute statement",
                        new SQLException("duplicate key value violates unique constraint \"uq_users_email\"", "23505")));

        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problemDetail.getDetail()).isEqualTo("Request conflicts with existing data.");
    }

    // 23502 = NOT NULL, 23503 = foreign key, 23514 = CHECK: our own code wrote something the schema refuses,
    // not a client conflict.
    @ParameterizedTest
    @ValueSource(strings = {"23502", "23503", "23514"})
    void handleDataIntegrityViolationException_otherViolation_returns500WithoutLeakingCause(String sqlState) {
        ProblemDetail problemDetail = handler.handleDataIntegrityViolationException(
                new DataIntegrityViolationException("could not execute statement",
                        new SQLException("violates constraint \"chk_users_email\"", sqlState)));

        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problemDetail.getType()).hasToString("https://home-energy-tracker/problems/internal-error");
        assertThat(problemDetail.getDetail()).isEqualTo("An unexpected error occurred.");
    }

    @Test
    void handleDataIntegrityViolationException_noSqlExceptionInCauseChain_returns500() {
        ProblemDetail problemDetail = handler.handleDataIntegrityViolationException(
                new DataIntegrityViolationException("unique constraint, but no SQLState to prove it"));

        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
    }

    // 410 Gone, not 403: the token is fine, the account behind it is DELETING or DELETED (one exception for both).
    @Test
    void handleAccountDeletedException_returns410WithMessage() {
        ProblemDetail problemDetail = handler.handleAccountDeletedException(new AccountDeletedException());

        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.GONE.value());
        assertThat(problemDetail.getType()).hasToString("https://home-energy-tracker/problems/account-deleted");
        assertThat(problemDetail.getTitle()).isEqualTo("Account deleted");
        assertThat(problemDetail.getDetail()).isEqualTo("Account has been deleted or is being deleted.");
        assertThat(problemDetail.getProperties()).containsKey("timestamp");

    }

    // 409, not 403: the caller may write in general, the account's state blocks it, and cancelling fixes it.
    @Test
    void handleDeletionScheduledException_returns409WithMessage() {
        ProblemDetail problemDetail = handler.handleDeletionScheduledException(new DeletionScheduledException());

        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problemDetail.getType()).hasToString("https://home-energy-tracker/problems/deletion-scheduled");
        assertThat(problemDetail.getTitle()).isEqualTo("Deletion scheduled");
        assertThat(problemDetail.getDetail())
                .isEqualTo("The account is scheduled for deletion and cannot be changed. Cancel the deletion first.");
        assertThat(problemDetail.getProperties()).containsKey("timestamp");
    }

    @Test
    void handleDeletionDeadlinePassedException_returns409WithMessage() {
        ProblemDetail problemDetail = handler.handleDeletionDeadlinePassedException(new DeletionDeadlinePassedException());

        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problemDetail.getType()).hasToString("https://home-energy-tracker/problems/deletion-deadline-passed");
        assertThat(problemDetail.getTitle()).isEqualTo("Deletion deadline passed");
        assertThat(problemDetail.getDetail())
                .isEqualTo("The grace period has ended, so the deletion can no longer be cancelled.");
        assertThat(problemDetail.getProperties()).containsKey("timestamp");
    }

    @Test
    void handleEmailNotVerifiedException_returns403WithMessage() {
        ProblemDetail problemDetail = handler.handleEmailNotVerifiedException(new EmailNotVerifiedException());

        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(problemDetail.getType()).hasToString("https://home-energy-tracker/problems/email-not-verified");
        assertThat(problemDetail.getTitle()).isEqualTo("Email not verified");
        assertThat(problemDetail.getDetail()).isEqualTo("Email is not verified.");
    }

    @Test
    void handleMissingIdentityClaimException_wrapsClaimNameIntoSentence() {
        ProblemDetail problemDetail = handler.handleMissingIdentityClaimException(
                new MissingIdentityClaimException("email"));

        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(problemDetail.getDetail())
                .isEqualTo("The access token is missing the required claim 'email'.");
    }

    // handleUnexpectedException has real branching, unlike every handler above - worth more than
    // one test. throws Exception on the last test method below: the handler's own signature
    // declares it (needed for the rethrow branch), and a JUnit 5 @Test method is free to declare
    // it too rather than needing a try/catch just to satisfy the compiler.

    @Test
    void handleUnexpectedException_accessDeniedException_isRethrownUntouched() {
        AccessDeniedException original = new AccessDeniedException("denied");

        // The SAME exception instance must come back out, not a new one and not something the
        // handler wraps - that's what lets it still reach ExceptionTranslationFilter and
        // SecurityConfig's own 401/403 handlers instead of becoming this handler's generic 500.
        assertThatThrownBy(() -> handler.handleUnexpectedException(original)).isSameAs(original);
    }

    @Test
    void handleUnexpectedException_authenticationException_isRethrownUntouched() {
        // AuthenticationException itself is abstract - BadCredentialsException is just a concrete
        // stand-in, any subclass would do here.
        BadCredentialsException original = new BadCredentialsException("bad credentials");

        assertThatThrownBy(() -> handler.handleUnexpectedException(original)).isSameAs(original);
    }

    @Test
    void handleUnexpectedException_anyOtherException_returns500WithoutLeakingCause() throws Exception {
        ProblemDetail problemDetail = handler.handleUnexpectedException(new RuntimeException("db password: hunter2"));

        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problemDetail.getDetail()).isEqualTo("An unexpected error occurred.");
    }
}
