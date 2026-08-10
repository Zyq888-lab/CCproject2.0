package com.jifeng.assessment.common;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.EmptyFileException;
import org.apache.poi.EncryptedDocumentException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusinessException(BusinessException ex, jakarta.servlet.http.HttpServletResponse response) {
        log.warn("业务异常: code={}, message={}", ex.getCode(), ex.getMessage());
        response.setStatus(ex.getCode() >= 100 && ex.getCode() < 600 ? ex.getCode() : 500);
        return ApiResponse.error(ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(BadCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> handleBadCredentials(BadCredentialsException ex) {
        return ApiResponse.error(401, "用户名或密码错误");
    }

    @ExceptionHandler(UsernameNotFoundException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> handleUsernameNotFound(UsernameNotFoundException ex) {
        return ApiResponse.error(401, ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleAccessDenied(AccessDeniedException ex) {
        return ApiResponse.error(403, "无权访问");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return new ApiResponse<>(400, "表单校验失败", errors);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleDuplicateKey(DuplicateKeyException ex) {
        log.warn("数据重复: {}", ex.getMessage());
        return ApiResponse.error(409, "数据已存在，请检查后重试");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public ApiResponse<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return ApiResponse.error(405, "请求方法不允许: " + ex.getMethod());
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNoHandlerFound(NoHandlerFoundException ex) {
        return ApiResponse.error(404, "接口不存在: " + ex.getRequestURL());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        log.warn("请求体解析失败: {}", ex.getMessage());
        return ApiResponse.error(400, "请求数据格式错误，请检查JSON结构");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public ApiResponse<Void> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        return ApiResponse.error(415, "不支持的请求类型: " + ex.getContentType());
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleMissingPart(MissingServletRequestPartException ex) {
        return ApiResponse.error(400, "缺少上传文件参数，请选择文件后上传");
    }

    @ExceptionHandler(MultipartException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleMultipart(MultipartException ex) {
        log.warn("文件上传异常: {}", ex.getMessage());
        return ApiResponse.error(400, "文件上传失败，请检查文件大小和格式");
    }

    @ExceptionHandler(EmptyFileException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleEmptyFile(EmptyFileException ex) {
        return ApiResponse.error(400, "文件为空，请检查文件内容");
    }

    @ExceptionHandler(EncryptedDocumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleEncryptedDocument(EncryptedDocumentException ex) {
        return ApiResponse.error(400, "文件已加密或设置了密码，请解密后重新上传");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleUnknown(Exception ex) {
        log.error("未处理异常", ex);
        return ApiResponse.error(500, "服务器内部错误，请联系管理员");
    }
}
