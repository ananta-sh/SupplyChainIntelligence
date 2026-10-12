package com.example.supplychain.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> status(ResponseStatusException exception) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        return ResponseEntity.status(status).body(Map.of("error", Map.of("code", status.name().toLowerCase(), "message", exception.getReason() == null ? status.getReasonPhrase() : exception.getReason())));
    }
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", Map.of(
                "code", "not_found", "message", "No endpoint or resource exists at " + exception.getResourcePath())));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, Object>> invalid(Exception exception) {
        return ResponseEntity.badRequest().body(Map.of("error", Map.of("code", "validation_error", "message", exception.getMessage())));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> internal(Exception exception) {
        return ResponseEntity.status(500).body(Map.of("error", Map.of("code", "internal_error", "message", "An unexpected error occurred.")));
    }
}
