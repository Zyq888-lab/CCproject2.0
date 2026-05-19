package com.jifeng.assessment.common;

public abstract class BaseController {

    protected <T> ApiResponse<T> ok(T data) {
        return ApiResponse.success(data);
    }

    protected <T> ApiResponse<T> ok(String message, T data) {
        return ApiResponse.success(message, data);
    }

    protected <T> ApiResponse<T> fail(String message) {
        return ApiResponse.error(message);
    }

    protected <T> ApiResponse<T> fail(int code, String message) {
        return ApiResponse.error(code, message);
    }
}
