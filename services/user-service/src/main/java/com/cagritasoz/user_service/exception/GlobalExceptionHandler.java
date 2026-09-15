package com.cagritasoz.user_service.exception;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UserNotFoundException.class)
    public ProblemDetail handleUserNotFoundException(UserNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(AlertRuleNotFoundException.class)
    public ProblemDetail handleAlertRuleNotFound(AlertRuleNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(DuplicateEmailException.class)
    public ProblemDetail handleDuplicateEmailException(DuplicateEmailException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(DuplicateAlertRuleException.class)
    public ProblemDetail handleDuplicateAlertRuleException(DuplicateAlertRuleException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    // Safety net for every TOCTOU race in this service, not just email: two concurrent requests
    // can both pass a pre-check (existsByEmail, existsByUserIdAndEvaluationWindowAndScope)
    // before either commits, so each unique constraint - not the pre-check - is what actually
    // guarantees no duplicates; a deleted-user race on alert_rules' FK lands here too. Spring
    // translates the underlying org.postgresql.util.PSQLException into this type via
    // PersistenceExceptionTranslationPostProcessor. Deliberately generic rather than
    // "Email already in use." (which this used to say) - that was wrong for the other two cases
    // it now also has to cover, and telling them apart precisely would mean inspecting the
    // triggered constraint's name (e.g. via the wrapped ConstraintViolationException), which
    // isn't done here. The specific, correctly-worded errors (DuplicateEmailException,
    // DuplicateAlertRuleException) already cover the common, non-racing path above.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolationException(DataIntegrityViolationException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Request conflicts with existing data.");
    }

    // Handle bean validation exceptions
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationException(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();

        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            errors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed.");
        problemDetail.setProperty("errors", errors);
        return problemDetail;
    }

    // Thrown when the request body can't even be deserialized into the target DTO - malformed
    // JSON or wrong types. Runs before @Valid ever gets a chance to, so it needs its own handler
    // separate from MethodArgumentNotValidException above.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed request body.");
    }
}
