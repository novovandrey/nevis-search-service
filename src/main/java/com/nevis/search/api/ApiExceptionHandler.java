package com.nevis.search.api;

import com.nevis.search.api.dto.ApiError;
import com.nevis.search.api.dto.FieldViolation;
import com.nevis.search.application.exception.ClientNotFoundException;
import com.nevis.search.application.exception.InvalidRequestException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ClientNotFoundException.class)
    ResponseEntity<ApiError> handleNotFound(ClientNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<ApiError> handleInvalidRequest(InvalidRequestException exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<FieldViolation> violations = exception.getBindingResult().getFieldErrors().stream()
                .map(this::toViolation)
                .toList();
        return error(HttpStatus.BAD_REQUEST, "Request validation failed", request, violations);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<ApiError> handleMalformedRequest(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "Malformed request", request, List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unexpected failure handling {} {}", request.getMethod(), request.getRequestURI(), exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", request, List.of());
    }

    private FieldViolation toViolation(FieldError error) {
        return new FieldViolation(error.getField(), error.getDefaultMessage());
    }

    private ResponseEntity<ApiError> error(
            HttpStatus status,
            String message,
            HttpServletRequest request,
            List<FieldViolation> violations
    ) {
        ApiError body = new ApiError(
                Instant.now(), status.value(), status.getReasonPhrase(), message, request.getRequestURI(), violations
        );
        return ResponseEntity.status(status).body(body);
    }
}

