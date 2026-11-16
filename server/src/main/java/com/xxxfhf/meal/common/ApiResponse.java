package com.xxxfhf.meal.common;

/**
 * 统一响应体：{ code, message, data }。
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.SUCCESS.code(), ErrorCode.SUCCESS.message(), data);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(ErrorCode.SUCCESS.code(), ErrorCode.SUCCESS.message(), null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode ec, String message) {
        return new ApiResponse<>(ec.code(), message == null ? ec.message() : message, null);
    }
}
