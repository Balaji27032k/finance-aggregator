package com.personal.finance.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Responses never echo request content; only the exception class is logged. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ServiceUnavailableException.class)
    ResponseEntity<ProblemDetail> unavailable(ServiceUnavailableException e) {
        log.warn("Dependency unavailable: {}", e.getCause() == null ? "unknown" : e.getCause().getClass().getSimpleName());
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Temporarily unavailable, retry later");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "30").body(body);
    }

    @ExceptionHandler(InvalidPairingCodeException.class)
    ResponseEntity<ProblemDetail> invalidPairing(InvalidPairingCodeException e) {
        log.warn("Rejected device registration: invalid pairing code");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid pairing code"));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(ResourceNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        log.error("Unhandled error: {}", e.getClass().getName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error"));
    }
}
