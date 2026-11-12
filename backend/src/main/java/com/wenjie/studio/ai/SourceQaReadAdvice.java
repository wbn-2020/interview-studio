package com.wenjie.studio.ai;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.CurrentOwner;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@RestControllerAdvice(assignableTypes = { SourceQaController.class, AiController.class })
public class SourceQaReadAdvice implements ResponseBodyAdvice<Object> {
    private final SourceQaReadChecks checks;
    private final CurrentOwner owner;

    public SourceQaReadAdvice(SourceQaReadChecks checks, CurrentOwner owner) {
        this.checks = checks;
        this.owner = owner;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) { return true; }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (!(body instanceof AiDtos.Preview) && !(body instanceof AiDtos.Task)) return body;
        try { checks.check(owner.id(), body); }
        catch (ApiException exception) {
            // 写事务可能已提交；返回前失权不能让客户端把原键当成明确失败而另建任务。
            if (body instanceof AiDtos.Task && !"GET".equals(request.getMethod().name())) {
                throw new ApiException(503, "AI_SOURCE_QA_RECEIPT_UNAVAILABLE",
                        "任务操作可能已保存，但来源读取条件尚无法核实。请恢复权限后使用原请求重试以取回回执。");
            }
            throw exception;
        }
        return body;
    }
}
