package com.wenjie.studio.training;

import com.wenjie.studio.common.CurrentOwner;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Map;

@Configuration
public class TrainingAccessConfiguration implements WebMvcConfigurer {
    private final TrainingAccessGuard guard;
    private final CurrentOwner owner;

    public TrainingAccessConfiguration(TrainingAccessGuard guard, CurrentOwner owner) {
        this.guard = guard;
        this.owner = owner;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                check(request, guard, owner);
                return true;
            }
        }).addPathPatterns("/api/v1/**");
    }

    static void check(HttpServletRequest request, TrainingAccessGuard guard, CurrentOwner owner) {
        // 使用 MVC 已匹配的路由和参数，避免原始 URI 编码影响模块边界识别。
        Object matched = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (!(matched instanceof String pattern) || !guard.guarded(pattern)) return;
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String sessionId = null;
        if (variables instanceof Map<?, ?> map) {
            Object value = map.containsKey("sessionId") ? map.get("sessionId") : map.get("id");
            if (value instanceof String id) sessionId = id;
        }
        guard.checkHttp(owner.id(), request.getMethod(), pattern, sessionId);
    }
}
