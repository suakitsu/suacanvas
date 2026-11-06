package com.openimage.modules.auth.email;

public class EmailCodeSendException extends RuntimeException {
    public EmailCodeSendException(String message) {
        super(message);
    }

    public EmailCodeSendException(String message, Throwable cause) {
        super(message, cause);
    }
}
