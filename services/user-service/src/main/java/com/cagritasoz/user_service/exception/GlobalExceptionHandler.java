package com.cagritasoz.user_service.exception;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

// Extending ResponseEntityExceptionHandler makes Spring's own MVC errors (405, 415, unknown
// route, bad path variable, ...) come back as ProblemDetail too. Every response carries the same
// fields as the 401/403 ones from the security handlers: type, title, status, detail, instance
// (set by Spring) and timestamp.
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String PROBLEM_TYPE_BASE = "https://home-energy-tracker/problems/";

    @ExceptionHandler(UserNotFoundException.class)
    public ProblemDetail handleUserNotFoundException(UserNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "user-not-found", "User not found", e.getMessage());
    }

    // A @Version check failed: another request changed the row first. Nothing was written, so the
    // client can re-read and retry.
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLockingFailureException(OptimisticLockingFailureException e) {
        return problem(HttpStatus.CONFLICT, "concurrent-modification", "Concurrent modification",
                "The resource was modified by another request. Please retry.");
    }

    // A database constraint was violated, e.g. uq_users_email when provisioning a new account.
    // Kept generic on purpose: the message must not reveal which value or constraint clashed.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolationException(DataIntegrityViolationException e) {
        return problem(HttpStatus.CONFLICT, "data-conflict", "Data conflict",
                "Request conflicts with existing data.");
    }

    // The three below are thrown by the provisioning interceptor. The token itself is valid
    // (otherwise Spring Security would have answered 401), it just can't be used here.
    // TODO: Evaluate whether it would be better to make these custom exceptions extend AccessDeniedException. See comment below for Exception.class
    @ExceptionHandler(AccountNotActiveException.class)
    public ProblemDetail handleAccountNotActiveException(AccountNotActiveException e) {
        return problem(HttpStatus.FORBIDDEN, "account-not-active", "Account not active", e.getMessage());
    }

    @ExceptionHandler(EmailNotVerifiedException.class)
    public ProblemDetail handleEmailNotVerifiedException(EmailNotVerifiedException e) {
        return problem(HttpStatus.FORBIDDEN, "email-not-verified", "Email not verified", e.getMessage());
    }

    // The exception message is the missing claim's name, not a sentence.
    @ExceptionHandler(MissingIdentityClaimException.class)
    public ProblemDetail handleMissingIdentityClaimException(MissingIdentityClaimException e) {
        return problem(HttpStatus.FORBIDDEN, "missing-identity-claim", "Missing identity claim",
                "The access token is missing the required claim '" + e.getMessage() + "'.");
    }

    // Last resort. The real cause is only logged, never sent to the client.
    // Spring Security exceptions (e.g. from @PreAuthorize) are rethrown untouched, so they still
    // reach ExceptionTranslationFilter and the 401/403 handlers in SecurityConfig.
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpectedException(Exception e) throws Exception {

        if (e instanceof AccessDeniedException || e instanceof AuthenticationException) {

            throw e;

        }

        log.error("Unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal server error",
                "An unexpected error occurred.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
                                                                  @NonNull HttpHeaders headers,
                                                                  @NonNull HttpStatusCode status,
                                                                  @NonNull WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>(); // Keep insertion order.

        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            errors.merge(fieldError.getField(),
                    Objects.requireNonNullElse(fieldError.getDefaultMessage(), "invalid"),
                    (first, second) -> first + "; " + second);
        }

        ProblemDetail problemDetail = problem(HttpStatus.BAD_REQUEST, "validation-failed",
                "Validation failed", "Validation failed.");
        problemDetail.setProperty("errors", errors);

        return handleExceptionInternal(e, problemDetail, headers, status, request);
    }

    // The body could not be deserialized at all (malformed JSON, wrong types), so @Valid never ran.
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(@NonNull HttpMessageNotReadableException e,
                                                                  @NonNull HttpHeaders headers,
                                                                  @NonNull HttpStatusCode status,
                                                                  @NonNull WebRequest request) {
        ProblemDetail problemDetail = problem(HttpStatus.BAD_REQUEST, "malformed-request",
                "Malformed request", "Malformed request body.");

        return handleExceptionInternal(e, problemDetail, headers, status, request);
    }

    // Every response passes through here, including the ones Spring builds itself. Fills in the
    // fields those are missing so all error bodies look the same.
    @Override
    @NullMarked
    protected ResponseEntity<Object> createResponseEntity(@Nullable Object body,
                                                                HttpHeaders headers,
                                                                HttpStatusCode statusCode,
                                                                WebRequest request) {
        if (body instanceof ProblemDetail problemDetail) {

            // Spring 7 leaves a ProblemDetail's type null when nobody set one (Spring 6 defaulted it
            // to "about:blank" explicitly) - RFC 9457 says an absent type means "about:blank", so
            // both are treated as "no real type yet". The old requireNonNull turned that into an
            // NPE for every ProblemDetail Spring built itself, which made Spring fall back to an
            // empty-bodied sendError instead of reaching this method's result.
            URI type = problemDetail.getType();

            if (type == null || type.equals(URI.create("about:blank"))) {

                HttpStatus status = HttpStatus.resolve(statusCode.value());

                // The status phrase as a key, e.g. 405 -> "method-not-allowed".
                String problemKey = status == null ? "error" : status.getReasonPhrase().toLowerCase().replace(' ', '-');

                problemDetail.setType(URI.create(PROBLEM_TYPE_BASE + problemKey));

            }

            if (problemDetail.getProperties() == null || !problemDetail.getProperties().containsKey("timestamp")) {

                problemDetail.setProperty("timestamp", Instant.now().toString());

            }
        }

        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private ProblemDetail problem(HttpStatus status, String problemKey, String title, String detail) {

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);

        problemDetail.setType(URI.create(PROBLEM_TYPE_BASE + problemKey));
        problemDetail.setTitle(title);
        problemDetail.setProperty("timestamp", Instant.now().toString());

        return problemDetail;
    }
}
