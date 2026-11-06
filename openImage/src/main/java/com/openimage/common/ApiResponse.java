package com.openimage.common;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ApiResponse<T>(
    boolean success,
    String code,
    String message,
    T data,
    @JsonProperty("request_id") String requestId
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "OK", "success", data, RequestIds.current());
    }

    public static <T> ApiResponse<T> fail(String code, String message) {
        return new ApiResponse<>(false, code, message, null, RequestIds.current());
    }
}
