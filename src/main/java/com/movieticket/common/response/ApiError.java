package com.movieticket.common.response;

import java.time.LocalDateTime;
import java.util.Map;

/** The one error shape returned by every endpoint. Never contains a stack trace. */
public record ApiError(
        LocalDateTime timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        Map<String, String> fieldErrors) {

    public static ApiError of(int status, String error, String code, String message, String path) {
        return new ApiError(LocalDateTime.now(), status, error, code, message, path, null);
    }
}
