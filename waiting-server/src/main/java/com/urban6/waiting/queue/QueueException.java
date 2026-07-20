package com.urban6.waiting.queue;

import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

public sealed class QueueException extends RuntimeException {

    private QueueException(String message, Throwable cause) {
        super(message, cause);
    }

    public static final class Closed extends QueueException {
        public Closed(String message) { super(message, null); }
    }

    public static final class Unavailable extends QueueException {
        public Unavailable(String message, Throwable cause) { super(message, cause); }
    }

    @RestControllerAdvice
    public static class Handler {

        @ExceptionHandler(Closed.class)
        ResponseEntity<Map<String, String>> handleClosed(Closed e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("code", "QUEUE_CLOSED", "message", e.getMessage()));
        }

        @ExceptionHandler(Unavailable.class)
        ResponseEntity<Map<String, String>> handleUnavailable(Unavailable e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, "5")
                    .body(Map.of("code", "QUEUE_UNAVAILABLE", "message", e.getMessage()));
        }
    }
}
