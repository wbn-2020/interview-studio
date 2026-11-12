package com.wenjie.studio.search;

import com.wenjie.studio.common.CurrentOwner;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@RestControllerAdvice(assignableTypes = UnifiedSearchController.class)
public class UnifiedSearchReadAdvice implements ResponseBodyAdvice<Object> {
    private final UnifiedSearchReadChecks checks;
    private final CurrentOwner owner;

    public UnifiedSearchReadAdvice(UnifiedSearchReadChecks checks, CurrentOwner owner) {
        this.checks = checks;
        this.owner = owner;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof UnifiedSearchDtos.Page || body instanceof UnifiedSearchDtos.Source) checks.check(owner.id(), body);
        return body;
    }
}
