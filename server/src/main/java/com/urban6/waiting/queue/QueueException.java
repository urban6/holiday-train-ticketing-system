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

    /** 대기열에 없는 토큰. 만료됐거나, 애초에 발급된 적 없거나, 창이 다르다. */
    public static final class Expired extends QueueException {
        public Expired(String message) { super(message, null); }
    }

    public static final class InvalidRequest extends QueueException {
        public InvalidRequest(String message) { super(message, null); }
    }

    @RestControllerAdvice
    public static class Handler {

        @ExceptionHandler(Closed.class)
        ResponseEntity<Map<String, String>> handleClosed(Closed e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("code", "QUEUE_CLOSED", "message", e.getMessage()));
        }

        @ExceptionHandler(Expired.class)
        ResponseEntity<Map<String, String>> handleExpired(Expired e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("code", "QUEUE_TOKEN_EXPIRED", "message", e.getMessage()));
        }

        @ExceptionHandler(InvalidRequest.class)
        ResponseEntity<Map<String, String>> handleInvalidRequest(InvalidRequest e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("code", "QUEUE_BAD_REQUEST", "message", e.getMessage()));
        }

        @ExceptionHandler(Unavailable.class)
        ResponseEntity<Map<String, String>> handleUnavailable(Unavailable e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, "5")
                    .body(Map.of("code", "QUEUE_UNAVAILABLE", "message", e.getMessage()));
        }
    }
}
