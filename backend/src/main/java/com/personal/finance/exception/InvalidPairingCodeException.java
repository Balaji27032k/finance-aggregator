package com.personal.finance.exception;

public class InvalidPairingCodeException extends RuntimeException {

    public InvalidPairingCodeException() {
        super("Invalid pairing code");
    }
}
