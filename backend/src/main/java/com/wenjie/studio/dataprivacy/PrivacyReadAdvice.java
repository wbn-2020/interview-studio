package com.wenjie.studio.dataprivacy;

import com.wenjie.studio.common.CurrentOwner;
import com.wenjie.studio.training.TrainingDtos;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@RestControllerAdvice(basePackages = "com.wenjie.studio")
public class PrivacyReadAdvice implements ResponseBodyAdvice<Object> {
    private final CurrentOwner owner;
    private final PrivacyLifecycle lifecycle;
    private final PrivacyAccess access;

    public PrivacyReadAdvice(CurrentOwner owner, PrivacyLifecycle lifecycle, PrivacyAccess access) {
        this.owner = owner; this.lifecycle = lifecycle; this.access = access;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return converterType.getName().contains("Jackson");
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        String path = request.getURI().getPath();
        if (response instanceof ServletServerHttpResponse servlet && servlet.getServletResponse().getStatus() >= 400) return body;
        if (body == null || !path.startsWith("/api/v1/") || path.startsWith("/api/v1/data-privacy")
                || path.startsWith("/api/v1/auth") || path.startsWith("/api/v1/session")) return body;
        if (request.getPrincipal() == null) return body;
        String current = owner.id();
        if ("GET".equals(request.getMethod().name()) && "/api/v1/training/active".equals(path)
                && body instanceof TrainingDtos.Active active
                && "RECONCILIATION_REQUIRED".equals(lifecycle.status(current).state())) {
            var session = active.session();
            if (session == null) return body;
            // 恢复管理只需模式、修订和辅助条件；标题可能来自待清除内容，不借该豁免返回。
            return new TrainingDtos.Active(new TrainingDtos.SessionSummary(session.id(), "当前活动训练",
                    session.mode(), session.status(), session.revision(), session.materialAccess(),
                    session.questionCount(), session.createdAt(), session.updatedAt()));
        }
        lifecycle.requireSynchronized(current);
        return access.filterResponse(current, body, request.getMethod().name(), path);
    }
}
