package com.openimage.ai.worker;

public class AiWorkerProviderException extends RuntimeException {
    private final String code;

    public AiWorkerProviderException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return AiWorkerErrorPolicy.retryable(
            org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
            code
        );
    }
}
