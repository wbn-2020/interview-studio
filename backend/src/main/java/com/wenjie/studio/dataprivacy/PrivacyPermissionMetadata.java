package com.wenjie.studio.dataprivacy;

import com.wenjie.studio.ai.AiAuthorizationDtos;
import com.wenjie.studio.material.MaterialLifecycleDtos;

/** 许可身份属于审计元数据；白名单不包含外发清单、匹配请求、普通任务或带理由的材料历史。 */
public final class PrivacyPermissionMetadata {
    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private PrivacyPermissionMetadata() { }

    public static boolean isSafePermissionMetadata(String operation) {
        if (operation == null) return false;
        int separator = operation.indexOf(' ');
        return separator > 0 && isSafePermissionMetadata(operation.substring(0, separator), operation.substring(separator + 1));
    }

    public static boolean isSafePermissionMetadata(String method, String path) {
        if (method == null || path == null) return false;
        if ("GET".equals(method)) return "/api/v1/ai/authorizations".equals(path)
                || path.matches("/api/v1/ai/tasks/" + UUID + "/authorization-uses")
                || path.matches("/api/v1/materials/excerpts/" + UUID + "/lifecycle");
        return "POST".equals(method) && (path.matches("/api/v1/ai/authorizations/" + UUID + "/revoke")
                || path.matches("/api/v1/materials/excerpts/" + UUID + "/lifecycle/changes"));
    }

    /** 出站再核固定 DTO 类型，不能仅凭路由名称给误返回的正文对象开放豁免。 */
    public static boolean isSafeResponse(String method, String path, Object body) {
        if (!isSafePermissionMetadata(method, path)) return false;
        if ("/api/v1/ai/authorizations".equals(path)) return body instanceof AiAuthorizationDtos.Page;
        if (path.endsWith("/authorization-uses")) return body instanceof AiAuthorizationDtos.UsagePage;
        if (path.endsWith("/revoke")) return body instanceof AiAuthorizationDtos.Revocation;
        return body instanceof MaterialLifecycleDtos.State;
    }
}
