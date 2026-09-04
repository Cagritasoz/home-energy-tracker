package com.cagritasoz.device_service.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DeviceNotFoundException.class)
    public ProblemDetail handleDeviceNotFoundException(DeviceNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ProblemDetail handleUserNotFoundException(UserNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(DeviceOwnerImmutableException.class)
    public ProblemDetail handleDeviceOwnerImmutableException(DeviceOwnerImmutableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    // Thrown when user-service can't be reached at all (connection refused, timeout) - as
    // opposed to HttpClientErrorException.NotFound, which means user-service responded and
    // said the user doesn't exist. This is "can't verify", not "verified as missing", so it's
    // a 503 (retry later), not a 404.
    @ExceptionHandler(ResourceAccessException.class)
    public ProblemDetail handleResourceAccessException(ResourceAccessException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Could not reach user-service to verify the user. Please try again later!");
    }

    // Handle bean validation exceptions
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationException(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();

        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            errors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problemDetail.setProperty("errors", errors);
        return problemDetail;
    }

    // Thrown when the request body can't even be deserialized into the target DTO - malformed
    // JSON or wrong types. Runs before @Valid ever gets a chance to, so it needs its own
    // handler separate from MethodArgumentNotValidException above.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed request body.");
    }
}
