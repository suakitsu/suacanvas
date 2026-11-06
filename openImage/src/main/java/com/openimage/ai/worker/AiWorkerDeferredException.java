package com.openimage.ai.worker;

public class AiWorkerDeferredException extends RuntimeException {
    private final String jobId;

    public AiWorkerDeferredException(String jobId, String message) {
        super(message);
        this.jobId = jobId;
    }

    public String jobId() {
        return jobId;
    }
}
