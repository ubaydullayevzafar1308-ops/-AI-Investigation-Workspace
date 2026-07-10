package uz.caseintel.api;

import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Единый формат ошибки для всех эндпоинтов: {timestamp, status, message}.
 * См. ARCHITECTURE.md §14.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    public record ErrorResponse(OffsetDateTime timestamp, int status, String message) {}

    /** 404 (Case/Alert/AuditEvent not found) и другие явные статусы из контроллеров. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException ex) {
        return build(ex.getStatusCode(), ex.getReason() != null ? ex.getReason() : ex.getMessage());
    }

    /** 400 — некорректное тело запроса или аргумент. */
    @ExceptionHandler({
            IllegalArgumentException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class
    })
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** Всё остальное — 500, но в том же формате, что и явные ошибки. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
    }

    private ResponseEntity<ErrorResponse> build(HttpStatusCode status, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(OffsetDateTime.now(), status.value(), message));
    }
}
