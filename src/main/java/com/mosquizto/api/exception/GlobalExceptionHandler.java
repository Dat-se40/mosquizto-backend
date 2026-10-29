package com.mosquizto.api.exception;

import com.mosquizto.api.dto.response.ErrorResponseException;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.Date;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {


    @ExceptionHandler(AppException.class)
    public ResponseEntity<ErrorResponseException> handleAppException(AppException e, WebRequest request) {
        ErrorCode errorCode = e.getErrorCode();
        HttpStatus status = errorCode.getStatus();

        if (status.is5xxServerError()) {
            log.error("Application error [{}]: {}", errorCode, e.getMessage(), e);
        } else {
            log.warn("Application error [{}]: {}", errorCode, e.getMessage());
        }

        return build(status, errorCode, e.getMessage(), request);
    }

    @ExceptionHandler(LimitExceedException.class)
    public ResponseEntity<ErrorResponseException> handleLimitExceedException(LimitExceedException e, WebRequest request) {
        if (request instanceof ServletWebRequest servletWebRequest) {
            HttpServletResponse response = servletWebRequest.getResponse();
            if (response != null && e.getRetryAfterSeconds() != null) {
                response.setHeader("Retry-After", String.valueOf(e.getRetryAfterSeconds()));
            }
        }

        String message = e.getRetryAfterSeconds() == null
                ? e.getMessage()
                : e.getMessage() + ". Retry after " + e.getRetryAfterSeconds() + " seconds.";

        return build(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMIT_EXCEEDED, message, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponseException> handleValidationException(MethodArgumentNotValidException e, WebRequest request) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));

        if (message.isBlank()) {
            message = "Validation failed";
        }

        log.warn("Validation error: {}", message);
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, message, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponseException> handleConstraintViolationException(ConstraintViolationException e, WebRequest request) {
        String message = e.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                .collect(Collectors.joining("; "));

        if (message.isBlank()) {
            message = "Validation failed";
        }

        log.warn("Constraint violation: {}", message);
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, message, request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponseException> handleMissingRequestHeaderException(MissingRequestHeaderException e, WebRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.MISSING_HEADER,
                e.getHeaderName() + " header is required", request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponseException> handleMissingRequestParameter(MissingServletRequestParameterException e, WebRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.MISSING_PARAMETER,
                e.getParameterName() + " parameter is required", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponseException> handleTypeMismatch(MethodArgumentTypeMismatchException e, WebRequest request) {
        String expectedType = e.getRequiredType() == null ? "the expected type" : e.getRequiredType().getSimpleName();
        String message = "Parameter '" + e.getName() + "' must be of type " + expectedType;
        return build(HttpStatus.BAD_REQUEST, ErrorCode.TYPE_MISMATCH, message, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponseException> handleNotReadable(HttpMessageNotReadableException e, WebRequest request) {
        log.warn("Malformed request body: {}", e.getMessage());
        return build(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST,
                "Malformed or unreadable request body", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponseException> handleMethodNotSupported(HttpRequestMethodNotSupportedException e, WebRequest request) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED,
                "HTTP method " + e.getMethod() + " is not supported for this endpoint", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponseException> handleDataIntegrityViolation(DataIntegrityViolationException e, WebRequest request) {
        log.warn("Data integrity violation: {}", e.getMostSpecificCause().getMessage());
        return build(HttpStatus.CONFLICT, ErrorCode.DATA_INTEGRITY_VIOLATION,
                "The request conflicts with the current state of the resource", request);
    }

    @ExceptionHandler(ExpiredJwtException.class)
    public ResponseEntity<ErrorResponseException> handleExpiredJwtException(ExpiredJwtException e, WebRequest request) {
        log.warn("Expired JWT token: {}", e.getMessage());
        return build(HttpStatus.UNAUTHORIZED, ErrorCode.TOKEN_EXPIRED, "Token has expired", request);
    }

    @ExceptionHandler(JwtException.class)
    public ResponseEntity<ErrorResponseException> handleJwtException(JwtException e, WebRequest request) {
        log.warn("JWT exception: {}", e.getMessage());
        return build(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_TOKEN, "Invalid token", request);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponseException> handleBadCredentialsException(BadCredentialsException e, WebRequest request) {
        log.warn("Bad credentials: {}", e.getMessage());
        return build(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS, "Invalid username or password", request);
    }

    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<ErrorResponseException> handleDisabledException(DisabledException e, WebRequest request) {
        return build(HttpStatus.FORBIDDEN, ErrorCode.ACCOUNT_DISABLED,
                "Account is not activated. Please verify your email.", request);
    }

    @ExceptionHandler(UsernameNotFoundException.class)
    public ResponseEntity<ErrorResponseException> handleUsernameNotFoundException(UsernameNotFoundException e, WebRequest request) {
        log.warn("User not found: {}", e.getMessage());
        return build(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, e.getMessage(), request);
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ErrorResponseException> handleSecurityAccessDenied(
            org.springframework.security.access.AccessDeniedException e, WebRequest request) {
        return build(HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED, "Access denied", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseException> handleUnexpectedException(Exception e, WebRequest request) {
        log.error("Unexpected error occurred", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred. Please try again later.", request);
    }

    private ResponseEntity<ErrorResponseException> build(HttpStatus status,
                                                         ErrorCode code,
                                                         String message,
                                                         WebRequest request) {
        ErrorResponseException body = new ErrorResponseException();
        body.setTimestamp(new Date());
        body.setStatus(status.value());
        body.setCode(code.name());
        body.setError(status.getReasonPhrase());
        body.setPath(request.getDescription(false).replace("uri=", ""));
        body.setMessage(message);
        return ResponseEntity.status(status).body(body);
    }

    private String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + ": " + fieldError.getDefaultMessage();
    }
}
