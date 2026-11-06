package com.openimage.ai.worker;

import org.springframework.http.HttpStatus;

public class AiWorkerProtocolException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public AiWorkerProtocolException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return AiWorkerErrorPolicy.retryable(status, code);
    }
}
