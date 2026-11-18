package com.xxxfhf.meal.common;

/**
 * 业务异常。
 *
 * 约定：message 是「可以直接展示给用户」的中文文案，不含堆栈、SQL、类名等内部信息。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        this(errorCode, errorCode.message());
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /* ---------- 常用快捷构造 ---------- */

    public static BizException param(String message) {
        return new BizException(ErrorCode.PARAM_INVALID, message);
    }

    public static BizException unauthorized(String message) {
        return new BizException(ErrorCode.UNAUTHORIZED, message);
    }

    public static BizException forbidden(String message) {
        return new BizException(ErrorCode.FORBIDDEN, message);
    }

    public static BizException notFound(String message) {
        return new BizException(ErrorCode.NOT_FOUND, message);
    }

    public static BizException conflict(String message) {
        return new BizException(ErrorCode.CONFLICT, message);
    }

    public static BizException tooMany(String message) {
        return new BizException(ErrorCode.TOO_MANY_ATTEMPTS, message);
    }
}
