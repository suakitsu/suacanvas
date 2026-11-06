package com.openimage.ai.worker;

import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(assignableTypes = AiWorkerController.class)
public class AiWorkerExceptionHandler {
    @ExceptionHandler(AiWorkerProtocolException.class)
    public ResponseEntity<Map<String, Object>> protocolError(AiWorkerProtocolException exception) {
        return ResponseEntity.status(exception.status()).body(Map.of(
            "error", Map.of(
                "code", exception.code(),
                "message", exception.getMessage(),
                "retryable", exception.retryable()
            )
        ));
    }

    @ExceptionHandler(AiWorkerProviderException.class)
    public ResponseEntity<Map<String, Object>> providerError(AiWorkerProviderException exception) {
        return ResponseEntity.unprocessableEntity().body(
            error(exception.code(), exception.getMessage(), exception.retryable())
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> internalError(Exception exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            error("WORKER_INTERNAL_ERROR", "Worker request could not be completed", true)
        );
    }

    private Map<String, Object> error(String code, String message, boolean retryable) {
        return Map.of("error", Map.of(
            "code", code,
            "message", message,
            "retryable", retryable
        ));
    }
}
