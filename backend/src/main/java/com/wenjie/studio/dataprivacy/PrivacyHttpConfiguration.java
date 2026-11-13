package com.wenjie.studio.dataprivacy;

import com.wenjie.studio.common.CurrentOwner;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 入口处先核对账本，再由出站过滤器去掉回收站和无正文墓碑。 */
@Configuration
public class PrivacyHttpConfiguration implements WebMvcConfigurer {
    private final PrivacyLifecycle lifecycle;
    private final PrivacyAccess access;
    private final CurrentOwner owner;

    public PrivacyHttpConfiguration(PrivacyLifecycle lifecycle, PrivacyAccess access, CurrentOwner owner) {
        this.lifecycle = lifecycle; this.access = access; this.owner = owner;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                String path = request.getRequestURI();
                if (!path.startsWith("/api/v1/") || path.startsWith("/api/v1/data-privacy")
                        || path.startsWith("/api/v1/auth/") || path.startsWith("/api/v1/session/")
                        || request.getUserPrincipal() == null) return true;
                // 只读活动训练的访问元数据用于恢复外壳；出站统一遮去标题，不开放训练正文。
                if ("GET".equals(request.getMethod()) && "/api/v1/training/active".equals(path)) return true;
                String current = owner.id();
                lifecycle.requireRequestSynchronized(current, request.getHeader("Idempotency-Key"),
                        request.getMethod(), path);
                if (PrivacyPermissionMetadata.isSafePermissionMetadata(request.getMethod(), path)) return true;
                // 包括二进制附件／流式任务：这些出口可能绕过 JSON ResponseBodyAdvice。
                SetHolder.requirePath(access, current, path);
                return true;
            }
        }).addPathPatterns("/api/v1/**");
    }

    private static final class SetHolder {
        private static void requirePath(PrivacyAccess access, String owner, String path) {
            var blocked = access.identities(owner);
            for (String segment : path.split("/")) {
                if (blocked.contains(segment)) {
                    throw new com.wenjie.studio.common.ApiException(410, "PRIVACY_OBJECT_UNAVAILABLE",
                            "该内容已移入回收站、被清除或撤权，请在数据管理中查看状态。");
                }
            }
        }
    }
}
