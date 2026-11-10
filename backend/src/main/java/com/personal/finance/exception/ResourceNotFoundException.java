package com.personal.finance.exception;

public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String what) {
        super(what + " not found");
    }
}
