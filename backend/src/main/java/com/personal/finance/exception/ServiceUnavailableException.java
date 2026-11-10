package com.personal.finance.exception;

/** A dependency (normally the database) is unavailable; nothing was acknowledged and the caller should retry. */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(Throwable cause) {
        super("Service temporarily unavailable", cause);
    }
}
