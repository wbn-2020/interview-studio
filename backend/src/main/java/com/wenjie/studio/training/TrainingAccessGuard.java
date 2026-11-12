package com.wenjie.studio.training;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/** 只守卫面向用户的资料出口；内部来源校验不属于查看参考。 */
@Service
public class TrainingAccessGuard {
    private static final String TRAINING = "/api/v1/training";
    private static final Set<String> MATERIALS = Set.of("knowledge", "prepare", "materials", "bank", "schedule", "ai", "review", "memory", "today", "growth", "search", "recent", "lifecycle");
    private final TrainingMapper training;
    private final IdempotencyMapper workspaces;

    public TrainingAccessGuard(TrainingMapper training, IdempotencyMapper workspaces) {
        this.training = training;
        this.workspaces = workspaces;
    }

    public boolean guarded(String path) {
        if (path == null || !path.startsWith("/api/v1/")) return false;
        String rest = path.substring("/api/v1/".length());
        String module = rest.split("/", 2)[0];
        return MATERIALS.contains(module) || module.equals("training");
    }

    @Transactional(timeout = 30)
    public void checkHttp(String owner, String method, String pattern, String sessionId) {
        if (!guarded(pattern)) return;
        workspaces.ensureWorkspace(owner);
        if (workspaces.lockWorkspace(owner) == null) throw new IllegalStateException("未能锁定个人工作区");
        check(owner, method, pattern, sessionId);
    }

    // IdempotencyService 已持有同一 owner 锁；必须在读取历史回执之前检查本次条件。
    public void checkCommand(String owner, String operation) {
        int separator = operation.indexOf(' ');
        if (separator < 0) return;
        String method = operation.substring(0, separator);
        String path = operation.substring(separator + 1);
        String prefix = TRAINING + "/sessions/";
        String sessionId = path.startsWith(prefix) ? path.substring(prefix.length()).split("/", 2)[0] : null;
        check(owner, method, path, sessionId);
    }

    private void check(String owner, String method, String path, String sessionId) {
        if (!guarded(path)) return;
        // 此出口只有不透明通知游标；任何任务正文仍须经过原资料出口的动态守卫。
        if ("GET".equals(method) && "/api/v1/ai/events".equals(path)) return;
        if (authorizationMetadata(method, path)) return;
        var active = training.active(owner);
        if (active == null || "LEARNING".equals(active.mode()) || active.materialAccess()) return;
        if (path.equals(TRAINING + "/active")) return;
        if ("GET".equals(method) && path.equals(TRAINING + "/sessions")) return;
        if (path.startsWith(TRAINING + "/sessions/") && active.id().equals(sessionId)
                && !path.endsWith("/reference") && !path.endsWith("/import-board")
                && (!path.contains("/evaluations") || path.endsWith("/access") || "GET".equals(method))) return;
        throw new ApiException(409, "ASSISTANCE_CONFIRMATION_REQUIRED",
                "当前有独立训练。请先回到该训练确认查阅资料；确认后，后续提交会保留辅助条件。");
    }

    private static boolean authorizationMetadata(String method, String path) {
        // 只豁免不含原文的许可元数据；模板路径和命令实际 UUID 路径使用同一精确白名单。
        if ("/api/v1/ai/authorizations".equals(path)) return "GET".equals(method) || "POST".equals(method);
        if ("POST".equals(method) && "/api/v1/ai/authorizations/matches".equals(path)) return true;
        String id = "(?:\\{id\\}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})";
        return "POST".equals(method) && path.matches("/api/v1/ai/authorizations/" + id + "/revoke")
                || "GET".equals(method) && path.matches("/api/v1/ai/tasks/" + id + "/authorization-uses");
    }
}
