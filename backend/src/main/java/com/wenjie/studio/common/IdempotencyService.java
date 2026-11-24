package com.wenjie.studio.common;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import com.wenjie.studio.training.TrainingAccessGuard;
import com.wenjie.studio.dataprivacy.PrivacyAccess;
import com.wenjie.studio.dataprivacy.PrivacyLifecycle;
import com.wenjie.studio.dataprivacy.PrivacyPermissionMetadata;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class IdempotencyService {
    private static final int MAX_RESPONSE_BYTES = 2_097_152;
    private static final int RETENTION_DAYS = 30;
    private final IdempotencyMapper receipts;
    private final JsonMapper json;
    private final JsonMapper canonical;
    private final TrainingAccessGuard trainingAccess;
    private final PrivacyLifecycle privacy;
    private final PrivacyAccess privacyAccess;

    public IdempotencyService(IdempotencyMapper receipts, JsonMapper json, TrainingAccessGuard trainingAccess,
                              PrivacyLifecycle privacy, PrivacyAccess privacyAccess) {
        this.receipts = receipts;
        this.json = json;
        this.trainingAccess = trainingAccess;
        this.privacy = privacy;
        this.privacyAccess = privacyAccess;
        this.canonical = json.rebuild()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
    }

    @Transactional(timeout = 30)
    public <T> T execute(String ownerId, String key, String operation, Object request,
                         Class<T> responseType, Supplier<T> action) {
        return execute(ownerId, key, operation, request, responseType, () -> {}, action);
    }

    @Transactional(timeout = 30)
    public <T> T execute(String ownerId, String key, String operation, Object request,
                         Class<T> responseType, Runnable accessGuard, Supplier<T> action) {
        requireUuid(ownerId);
        requireUuid(key);
        if (operation == null || operation.isBlank() || operation.length() > 255) {
            throw new IllegalArgumentException("幂等操作名称无效");
        }
        String digest = digest(operation + "\n" + canonical.writeValueAsString(request));

        // 工作区锁同时保护首次请求和重试，业务变更与回执必须在同一事务提交。
        receipts.ensureWorkspace(ownerId);
        if (receipts.lockWorkspace(ownerId) == null) {
            throw new IllegalStateException("未能锁定个人工作区");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        // 回执只保证有界重放；过期正文在 owner 锁内清理，隐私清除仍会立即脱敏相关回执。
        receipts.deleteExpired(ownerId, now);
        boolean pendingPrivacy = privacy.isMatchingPendingPrivacyCommand(ownerId, key, operation, request);
        boolean pendingPermission = privacy.isMatchingPendingPermissionCommand(ownerId, key, operation, request);
        boolean nestedPrivacy = privacy.isVerifiedPendingContinuation(ownerId);
        if (!pendingPrivacy && !pendingPermission && !nestedPrivacy) trainingAccess.checkCommand(ownerId, operation);
        boolean privacyCommand = operation.startsWith("POST /api/v1/data-privacy/");
        boolean permissionMetadata = PrivacyPermissionMetadata.isSafePermissionMetadata(operation);
        if (!privacyCommand) {
            // HTTP 预检后仍可能有另一标签清除资料；命令和旧回执必须在同一 owner 锁内重验。
            privacy.requireCommandSynchronized(ownerId, key, operation, request);
            if (!permissionMetadata) {
                var blocked = privacyAccess.identities(ownerId);
                for (String segment : operation.substring(operation.indexOf(' ') + 1).split("/")) {
                    if (blocked.contains(segment)) {
                        throw new ApiException(410, "PRIVACY_OBJECT_UNAVAILABLE", "该内容已进入隐私保护状态，请刷新后在数据管理中处理。");
                    }
                }
                privacyAccess.requireInput(ownerId, request);
            }
        }
        // 受保护回执也可能含原文，原键恢复前仍须核对该命令的当前来源访问权限。
        accessGuard.run();
        IdempotencyMapper.Receipt existing = receipts.findReceipt(ownerId, key);
        if (existing != null) {
            if (!operation.equals(existing.operation()) || !digest.equals(existing.requestHash())) {
                throw new ApiException(409, "IDEMPOTENCY_KEY_REUSED", "此操作标识已用于不同请求，请核对原请求结果。");
            }
            if (!privacyCommand && !permissionMetadata) privacyAccess.requireInput(ownerId, existing.responseJson());
            return json.readValue(existing.responseJson(), responseType);
        }

        T response = action.get();
        String responseJson = json.writeValueAsString(response);
        if (responseJson.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
            throw new ApiException(422, "RECEIPT_TOO_LARGE", "操作结果超出保存上限，本次变更未提交。");
        }
        receipts.insertReceipt(ownerId, key, operation, digest, responseJson, now, now.plusDays(RETENTION_DAYS));
        return response;
    }

    private static void requireUuid(String value) {
        try {
            if (value == null || !UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new ApiException(400, "INVALID_OPERATION_ID", "请求标识必须是标准小写 UUID。");
        }
    }

    private static String digest(String source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行时不支持请求摘要", exception);
        }
    }
}
