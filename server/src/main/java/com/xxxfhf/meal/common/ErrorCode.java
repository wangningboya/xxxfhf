package com.xxxfhf.meal.common;

/**
 * 错误码。code=0 表示成功，其余为失败。
 *
 * 分段约定：
 * 4xxxx 客户端错误，5xxxx 服务端错误。
 */
public enum ErrorCode {

    SUCCESS(0, "ok"),

    PARAM_INVALID(40001, "参数不合法"),
    UNAUTHORIZED(40101, "未登录或登录已过期"),
    FORBIDDEN(40301, "没有操作权限"),
    NOT_FOUND(40401, "数据不存在"),
    CONFLICT(40901, "数据状态已变化，请刷新后重试"),
    TOO_MANY_ATTEMPTS(42901, "尝试过于频繁，请稍后再试"),

    SERVER_ERROR(50000, "服务器内部错误"),
    WX_API_ERROR(50001, "微信接口调用失败"),
    STORAGE_ERROR(50002, "文件存储失败");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}
