package com.xxxfhf.meal.common;

import org.springframework.http.HttpStatus;

/**
 * 错误码枚举。code=0 表示成功，其余为失败。
 *
 * 分段约定：4xxxx 客户端/业务错误，5xxxx 服务端错误。
 *
 * HTTP 状态码同样由这里决定，小程序端据此区分「业务异常」与「系统异常」：
 * <ul>
 *   <li><b>4xx</b>：业务异常。message 是给用户看的原话，前端直接展示；</li>
 *   <li><b>5xx</b>：系统异常（未预期异常）。前端只提示「服务暂时不可用」，不暴露内部细节；</li>
 *   <li><b>200</b>：业务失败但文案仍可展示，如微信接口失败、图片保存失败这类
 *       「用户看得懂、也知道该怎么办」的失败，走 200 保证前端不会把它当系统故障吞掉。</li>
 * </ul>
 *
 * 所以新增错误码时要把状态码想清楚：能提示给用户的选择 4xx 或 200，
 * 只有确实不该让用户看到细节的才用 5xx。不要为了省事一律写成 200 或 500。
 */
public enum ErrorCode {

    SUCCESS(0, "ok", HttpStatus.OK),

    /* ---------- 业务异常：4xx，message 可直接展示 ---------- */

    PARAM_INVALID(40001, "参数不合法", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(40101, "未登录或登录已过期", HttpStatus.UNAUTHORIZED),
    FORBIDDEN(40301, "没有操作权限", HttpStatus.FORBIDDEN),
    NOT_FOUND(40401, "数据不存在", HttpStatus.NOT_FOUND),
    CONFLICT(40901, "数据状态已变化，请刷新后重试", HttpStatus.CONFLICT),
    TOO_MANY_ATTEMPTS(42901, "尝试过于频繁，请稍后再试", HttpStatus.TOO_MANY_REQUESTS),

    /*
     * ---------- 服务端异常 ----------
     * 这三个码目前都由 BizException 抛出，文案已经过人工措辞、可以直接展示，
     * 所以走 200；真正未预期的异常（空指针等）由 GlobalExceptionHandler 直接给 500。
     */

    SERVER_ERROR(50000, "服务器内部错误", HttpStatus.OK),
    WX_API_ERROR(50001, "微信接口调用失败", HttpStatus.OK),
    STORAGE_ERROR(50002, "文件存储失败", HttpStatus.OK);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(int code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }

    /** 该错误码对外响应的 HTTP 状态码（业务异常为 4xx） */
    public HttpStatus httpStatus() {
        return httpStatus;
    }

    /** 是否业务异常：4xx 即告诉前端「这是可展示的业务提示」 */
    public boolean isBiz() {
        return httpStatus.is4xxClientError();
    }
}
