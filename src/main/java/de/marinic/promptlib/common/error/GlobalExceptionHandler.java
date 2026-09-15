package de.marinic.promptlib.common.error;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(NotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    public ProblemDetail handleForbidden(ForbiddenException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    // Thrown by AuthenticationManager.authenticate() in AuthService.login(), which runs
    // outside the security filter chain's own exception handling (that only covers requests
    // going through the chain, not a manual authenticate() call from our own controller).
    // Deliberately the same message for "unknown email" and "wrong password" - telling them
    // apart would let this endpoint enumerate registered addresses. Note that this only
    // protects login: POST /auth/register still answers 409 for a taken email, a known and
    // accepted trade-off (see UserService.register and the README).
    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(AuthenticationException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid email or password");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        List<Map<String, String>> errors =
                ex.getBindingResult().getFieldErrors().stream()
                        .map(
                                (FieldError fieldError) ->
                                        Map.of(
                                                "field", fieldError.getField(),
                                                "message",
                                                        fieldError.getDefaultMessage() == null
                                                                ? ""
                                                                : fieldError.getDefaultMessage()))
                        .toList();

        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.BAD_REQUEST, "Validation failed for one or more fields");
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleMalformedRequestBody(HttpMessageNotReadableException ex) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request body is missing or could not be parsed as JSON");
    }

    // e.g. GET /api/v1/prompts/not-a-uuid. Unlike most of Spring MVC's client-error exceptions,
    // this one does NOT implement ErrorResponse, so the pass-through below doesn't cover it.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Invalid value for parameter '%s'".formatted(ex.getName()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        // Spring MVC's own client-error exceptions (unsupported media type -> 415, wrong HTTP
        // method -> 405, missing request parameter -> 400, ...) already implement ErrorResponse
        // and carry the right status, headers (e.g. "Allow") and a ProblemDetail body. This
        // advice runs before Spring's default resolver would have used them, so without passing
        // them through here every one of those client mistakes came back as a 500 and was
        // logged as an ERROR.
        if (ex instanceof ErrorResponse errorResponse) {
            return ResponseEntity.status(errorResponse.getStatusCode())
                    .headers(errorResponse.getHeaders())
                    .body(errorResponse.getBody());
        }
        log.error("Unexpected error", ex);
        return ResponseEntity.internalServerError()
                .body(ProblemDetail.forStatusAndDetail(
                        HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred"));
    }
}
