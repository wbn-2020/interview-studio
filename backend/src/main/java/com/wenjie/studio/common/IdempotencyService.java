package com.wenjie.studio.common;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import com.wenjie.studio.training.TrainingAccessGuard;

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
    private final IdempotencyMapper receipts;
    private final JsonMapper json;
    private final JsonMapper canonical;
    private final TrainingAccessGuard trainingAccess;

    public IdempotencyService(IdempotencyMapper receipts, JsonMapper json, TrainingAccessGuard trainingAccess) {
        this.receipts = receipts;
        this.json = json;
        this.trainingAccess = trainingAccess;
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
        trainingAccess.checkCommand(ownerId, operation);
        // 受保护回执也可能含原文，原键恢复前仍须核对该命令的当前来源访问权限。
        accessGuard.run();
        IdempotencyMapper.Receipt existing = receipts.findReceipt(ownerId, key);
        if (existing != null) {
            if (!operation.equals(existing.operation()) || !digest.equals(existing.requestHash())) {
                throw new ApiException(409, "IDEMPOTENCY_KEY_REUSED", "此操作标识已用于不同请求，请核对原请求结果。");
            }
            return json.readValue(existing.responseJson(), responseType);
        }

        T response = action.get();
        String responseJson = json.writeValueAsString(response);
        if (responseJson.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
            throw new ApiException(422, "RECEIPT_TOO_LARGE", "操作结果超出保存上限，本次变更未提交。");
        }
        receipts.insertReceipt(ownerId, key, operation, digest, responseJson, LocalDateTime.now(ZoneOffset.UTC));
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
