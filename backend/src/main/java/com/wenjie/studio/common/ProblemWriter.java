package com.wenjie.studio.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.UUID;

@Component
public final class ProblemWriter {
    private final JsonMapper mapper;

    public ProblemWriter(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public ApiProblem create(HttpServletRequest request, int status, String code,
                             String detail, Long currentRevision) {
        Object trace = request.getAttribute(TraceFilter.ATTRIBUTE);
        String traceId = trace instanceof String value ? value : UUID.randomUUID().toString();
        String title = switch (status) {
            case 400 -> "请求参数不正确";
            case 401 -> "需要登录";
            case 403 -> "请求未获允许";
            case 404 -> "内容不存在";
            case 405 -> "请求方法不支持";
            case 409 -> "内容发生冲突";
            case 413 -> "请求内容过大";
            case 415 -> "请求格式不支持";
            case 422 -> "当前操作超出限制";
            case 503 -> "服务暂时不可用";
            default -> "请求未完成";
        };
        return new ApiProblem("about:blank", title, status, detail,
                request.getRequestURI(), code, traceId, status >= 500, currentRevision);
    }

    public void write(HttpServletRequest request, HttpServletResponse response,
                      int status, String code, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(response.getOutputStream(), create(request, status, code, detail, null));
    }
}
