package com.wenjie.studio.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public final class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final ProblemWriter problems;

    public ApiExceptionHandler(ProblemWriter problems) {
        this.problems = problems;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiProblem> business(ApiException exception, HttpServletRequest request) {
        return response(request, exception.status(), exception.code(), exception.getMessage(),
                exception.currentRevision());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            HandlerMethodValidationException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class, MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ApiProblem> invalid(Exception exception, HttpServletRequest request) {
        return response(request, 400, "INVALID_REQUEST", "请检查字段、修订号和请求格式。", null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiProblem> notFound(HttpServletRequest request) {
        return response(request, 404, "NOT_FOUND", "请求的内容不存在或当前不可访问。", null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiProblem> method(HttpServletRequest request) {
        return response(request, 405, "METHOD_NOT_ALLOWED", "此地址不支持当前请求方法。", null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiProblem> media(HttpServletRequest request) {
        return response(request, 415, "UNSUPPORTED_MEDIA_TYPE", "请使用接口约定的请求格式。", null);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiProblem> database(DataAccessException exception, HttpServletRequest request) {
        // 数据库异常可能包含绑定参数；日志只记录异常种类和可关联的 traceId。
        LOG.error("event=database_request_failed exceptionType={}", exception.getClass().getSimpleName());
        return response(request, 503, "STORAGE_UNAVAILABLE", "存储暂时不可用，请保留当前内容后重试。", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiProblem> unexpected(Exception exception, HttpServletRequest request) {
        LOG.error("event=request_failed exceptionType={}", exception.getClass().getSimpleName());
        return response(request, 500, "INTERNAL_ERROR", "操作未能完成，请保留当前内容并记录问题编号。", null);
    }

    private ResponseEntity<ApiProblem> response(HttpServletRequest request, int status,
                                               String code, String detail, Long revision) {
        return ResponseEntity.status(HttpStatus.valueOf(status))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problems.create(request, status, code, detail, revision));
    }
}
