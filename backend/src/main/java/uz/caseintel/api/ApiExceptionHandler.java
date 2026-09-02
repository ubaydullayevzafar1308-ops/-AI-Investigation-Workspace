package uz.caseintel.api;

import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

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

    /**
     * Всё остальное — 500, в том же формате, что и явные ошибки, но:
     * (а) полный stack trace обязательно логируется — иначе неожиданный
     *     500 в проде не оставляет никакого следа для отладки;
     * (б) наружу клиенту уходит фиксированный текст, а не ex.getMessage()
     *     — сообщение необработанного исключения (NPE, ошибка JDBC и
     *     т.п.) может содержать детали реализации/схемы БД, которые не
     *     должны утекать через API.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unhandled exception while processing API request", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    private ResponseEntity<ErrorResponse> build(HttpStatusCode status, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(OffsetDateTime.now(), status.value(), message));
    }
}
