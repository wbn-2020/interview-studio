package com.wenjie.studio.ai;

import com.wenjie.studio.ai.AiConfigDtos.*;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyMapper;
import com.wenjie.studio.common.IdempotencyService;
import com.wenjie.studio.security.OwnerProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

@Service
public final class AiConfigService {
    private static final long MAX_SAFE = 9_007_199_254_740_991L;
    private static final int TEST_WINDOW_SECONDS = 60;
    private static final int TEST_LIMIT = 5;
    private final AiConfigMapper mapper;
    private final AiProperties properties;
    private final AiConfigCrypto crypto;
    private final IdempotencyService commands;
    private final IdempotencyMapper workspaces;
    private final OwnerProperties ownerProperties;
    private final AiConfigTester tester;
    private final ReentrantLock mutation = new ReentrantLock();
    private final Map<String, Window> testWindows = new ConcurrentHashMap<>();

    public AiConfigService(AiConfigMapper mapper, AiProperties properties, AiConfigCrypto crypto,
                           IdempotencyService commands, IdempotencyMapper workspaces, OwnerProperties ownerProperties,
                           AiConfigTester tester) {
        this.mapper = mapper;
        this.properties = properties;
        this.crypto = crypto;
        this.commands = commands;
        this.workspaces = workspaces;
        this.ownerProperties = ownerProperties;
        this.tester = tester;
    }

    @PostConstruct
    void load() {
        if (!crypto.available()) return;
        String owner = ownerProperties.ownerId();
        AiConfigRows.RuntimeConfig row = mapper.config(owner);
        if (row == null) return;
        String apiKey = crypto.decrypt(owner, row.revision(), row.apiKeyCiphertext());
        if (row.enabled() && !row.endpoint().isBlank()) AiEndpointPolicy.resolve(AiEndpointPolicy.parse(row.endpoint()));
        properties.replaceDatabase(candidate(row, apiKey), row.revision());
    }

    public Status status(String owner) {
        uuid(owner);
        AiProperties.State state = properties.state();
        AiDtos.Configuration configuration = state.snapshot().configuration();
        return new Status(state.revision(), state.source().name(), crypto.available(), configuration.enabled(),
                configuration.configured(), configuration.protocol(), configuration.provider(), configuration.endpoint(),
                configuration.model(), configuration.configVersion(), configuration.priceVersion(), configuration.currency(),
                configuration.inputPriceMicrosPerMillion(), configuration.outputPriceMicrosPerMillion(),
                configuration.dailyBudgetMicros(), configuration.maxOutputTokens(), configuration.timeoutSeconds(),
                configuration.maxTokensField(), configuration.jsonMode(), Boolean.TRUE.equals(configuration.streaming()),
                AiProperties.validKey(state.snapshot().apiKey()));
    }

    public Status save(String owner, String key, SaveRequest request) {
        uuid(owner);
        require(request);
        revision(request.expectedRevision());
        mutation.lock();
        try {
            return commands.execute(owner, key, "PUT /api/v1/ai/config", request, Status.class,
                    () -> saveLocked(owner, request));
        } finally {
            mutation.unlock();
        }
    }

    public TestResult test(String owner, TestRequest request) {
        uuid(owner);
        long started = System.nanoTime();
        if (request == null || !Boolean.TRUE.equals(request.costAcknowledged())) {
            throw new ApiException(422, "AI_CONFIG_TEST_COST_ACKNOWLEDGEMENT_REQUIRED",
                    "连接测试会向供应商发送最小请求，可能产生费用；请明确确认后继续。");
        }
        if (!allowTest(owner)) {
            throw new ApiException(429, "AI_CONFIG_TEST_RATE_LIMITED", "连接测试过于频繁，请稍后再试；测试可能产生供应商费用。");
        }
        AiProperties.State state = properties.state();
        AiDtos.Configuration current = state.snapshot().configuration();
        String protocol = value(request.protocol(), current.protocol());
        String endpoint = value(request.endpoint(), current.endpoint());
        String model = value(request.model(), current.model());
        boolean reuseKey = request.apiKey() == null || request.apiKey().isBlank();
        if (reuseKey && request.endpoint() != null && !endpoint.equals(current.endpoint())) {
            return new TestResult(false, 0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                    "AI_CONFIG_TEST_API_KEY_REQUIRED");
        }
        String apiKey = reuseKey ? state.snapshot().apiKey() : request.apiKey();
        int timeout = request.timeoutSeconds() == null ? current.timeoutSeconds() : request.timeoutSeconds();
        boolean jsonMode = request.jsonMode() == null ? current.jsonMode() : request.jsonMode();
        String maxTokensField;
        if (request.maxTokensField() != null) maxTokensField = request.maxTokensField();
        else if ("RESPONSES".equals(protocol)) maxTokensField = "max_output_tokens";
        else maxTokensField = "max_tokens".equals(current.maxTokensField()) || "max_completion_tokens".equals(current.maxTokensField())
                ? current.maxTokensField() : "max_tokens";
        if (!List.of("CHAT_COMPLETIONS", "RESPONSES").contains(protocol) || endpoint.isBlank() || model.isBlank()
                || model.codePointCount(0, model.length()) > 200 || model.codePoints().anyMatch(Character::isISOControl)
                || !AiProperties.validKey(apiKey) || timeout < 1 || timeout > 120
                || !("RESPONSES".equals(protocol) ? "max_output_tokens".equals(maxTokensField)
                    : "max_tokens".equals(maxTokensField) || "max_completion_tokens".equals(maxTokensField))) {
            return new TestResult(false, 0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                    "AI_CONFIG_TEST_NOT_CONFIGURED");
        }
        return tester.test(new AiConfigTester.Target(protocol, endpoint, model, apiKey, timeout, maxTokensField, jsonMode));
    }

    private Status saveLocked(String owner, SaveRequest request) {
        if (!crypto.available()) throw new ApiException(409, "AI_CONFIG_LOCKED", "服务器尚未配置 AI 配置主密钥，Web 编辑暂不可用。");
        workspaces.ensureWorkspace(owner);
        if (workspaces.lockWorkspace(owner) == null) throw new IllegalStateException("未能锁定个人工作区");
        // 与 AI worker 共用 owner 行锁：检查无活动任务和写入配置之间不能插入新认领。
        if (mapper.pendingTaskCount(owner) != 0) {
            throw new ApiException(409, "AI_CONFIG_BUSY", "仍有排队、执行、校验或取消中的 AI 任务，暂不能修改运行时配置。");
        }
        AiConfigRows.RuntimeConfig previousRow = mapper.config(owner);
        long currentRevision = previousRow == null ? 0 : previousRow.revision();
        if (request.expectedRevision() != currentRevision) {
            throw new ApiException(409, "AI_CONFIG_REVISION_CONFLICT", "AI 配置已变化，请重新读取后再保存。", currentRevision);
        }
        if (currentRevision >= MAX_SAFE) throw new ApiException(422, "AI_CONFIG_LIMIT", "AI 配置修订号已达到上限。");

        Values values = validate(request);
        String suppliedKey = request.apiKey();
        if (suppliedKey != null && !suppliedKey.isBlank() && !AiProperties.validKey(suppliedKey)) {
            throw new ApiException(422, "AI_API_KEY_INVALID", "API 密钥必须为 1–8192 个可打印 ASCII 字符，且不能包含空白。");
        }
        String apiKey = suppliedKey == null || suppliedKey.isBlank() ? properties.apiKey() : suppliedKey;
        if (suppliedKey != null && !suppliedKey.isBlank() && !suppliedKey.equals(properties.apiKey())
                && values.configVersion().equals(properties.configuration().configVersion())) {
            throw new ApiException(409, "AI_CONFIG_VERSION_REQUIRED", "轮换 API 密钥时必须同时递增 configVersion，使旧任务和授权失效。");
        }
        AiProperties replacement = new AiProperties(values.enabled(), values.protocol(), values.provider(), values.endpoint(),
                values.model(), apiKey, values.configVersion(), values.priceVersion(), values.currency(),
                values.inputPriceMicrosPerMillion(), values.outputPriceMicrosPerMillion(), values.dailyBudgetMicros(),
                values.maxOutputTokens(), values.timeoutSeconds(), values.maxTokensField(), values.jsonMode(), values.streaming());
        if (values.enabled() && !replacement.configuration().configured()) {
            throw new ApiException(422, "AI_CONFIG_INCOMPLETE", "启用 AI 前必须完整配置端点、模型、版本、价格、预算和有效 API 密钥。");
        }
        long revision = currentRevision + 1;
        AiDtos.Configuration configuration = replacement.configuration();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        AiConfigRows.RuntimeConfig row = new AiConfigRows.RuntimeConfig(owner, revision, configuration.enabled(),
                configuration.protocol(), configuration.provider(), configuration.endpoint(), configuration.model(),
                crypto.encrypt(owner, revision, apiKey), configuration.configVersion(), configuration.priceVersion(),
                configuration.currency(), configuration.inputPriceMicrosPerMillion(), configuration.outputPriceMicrosPerMillion(),
                configuration.dailyBudgetMicros(), configuration.maxOutputTokens(), configuration.timeoutSeconds(),
                configuration.maxTokensField(), configuration.jsonMode(), Boolean.TRUE.equals(configuration.streaming()),
                previousRow == null ? now : previousRow.createdAt(), now);
        int changed = previousRow == null ? mapper.insertConfig(row)
                : mapper.updateConfig(row, currentRevision);
        if (changed != 1) {
            throw new ApiException(409, "AI_CONFIG_REVISION_CONFLICT", "AI 配置已变化，请重新读取后再保存。", currentRevision);
        }

        AiProperties.State previousState = properties.state();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    // 回执写入或数据库提交失败时恢复旧快照，不能只更新内存。
                    if (status != STATUS_COMMITTED) properties.restore(previousState);
                }
            });
        }
        properties.replaceDatabase(replacement, revision);
        return status(owner);
    }

    private Values validate(SaveRequest request) {
        if (request.enabled() == null || request.jsonMode() == null || request.streaming() == null
                || request.inputPriceMicrosPerMillion() == null || request.outputPriceMicrosPerMillion() == null
                || request.dailyBudgetMicros() == null || request.maxOutputTokens() == null || request.timeoutSeconds() == null
                || request.protocol() == null || request.maxTokensField() == null) throw invalid();
        String protocol = request.protocol().strip();
        if (!List.of("CHAT_COMPLETIONS", "RESPONSES").contains(protocol)) throw invalid();
        String endpoint = text(request.endpoint(), 2048);
        if (!endpoint.isEmpty()) {
            try {
                URI parsed = AiEndpointPolicy.parse(endpoint);
                if (parsed.toASCIIString().length() > 2048) throw new AiEndpointPolicy.EndpointException("AI_CONFIG_ENDPOINT_INVALID");
                AiEndpointPolicy.resolve(parsed);
            } catch (AiEndpointPolicy.EndpointException exception) {
                String code = exception.code().endsWith("FORBIDDEN") ? "AI_CONFIG_ENDPOINT_FORBIDDEN" : "AI_CONFIG_ENDPOINT_INVALID";
                throw new ApiException(422, code, "AI 端点必须是可解析到公网地址的 HTTPS URL，且不能包含账号、查询参数或片段。");
            }
        }
        String maxTokensField = request.maxTokensField().strip();
        if ("RESPONSES".equals(protocol) ? !"max_output_tokens".equals(maxTokensField)
                : !"max_tokens".equals(maxTokensField) && !"max_completion_tokens".equals(maxTokensField)) throw invalid();
        String currency = request.currency() == null ? "" : request.currency().strip();
        if (!currency.isEmpty() && !currency.matches("[A-Z]{3}")) throw invalid();
        long input = request.inputPriceMicrosPerMillion(), output = request.outputPriceMicrosPerMillion();
        long budget = request.dailyBudgetMicros();
        int tokens = request.maxOutputTokens(), timeout = request.timeoutSeconds();
        if (input < -1 || input > MAX_SAFE || output < -1 || output > MAX_SAFE || budget < 0 || budget > MAX_SAFE
                || tokens < 0 || tokens > 16_384 || timeout < 0 || timeout > 120) throw invalid();
        return new Values(request.enabled(), protocol, text(request.provider(), 120), endpoint, text(request.model(), 200),
                text(request.configVersion(), 80), text(request.priceVersion(), 80), currency, input, output, budget,
                tokens, timeout, maxTokensField, request.jsonMode(), request.streaming());
    }

    private boolean allowTest(String owner) {
        long now = System.nanoTime();
        long window = TimeUnit.SECONDS.toNanos(TEST_WINDOW_SECONDS);
        Window current = testWindows.compute(owner, (ignored, existing) -> {
            if (existing == null || now - existing.started >= window) return new Window(now, new AtomicInteger(1));
            existing.count.incrementAndGet();
            return existing;
        });
        return current.count.get() <= TEST_LIMIT;
    }

    private static AiProperties candidate(AiConfigRows.RuntimeConfig row, String apiKey) {
        return new AiProperties(row.enabled(), row.protocol(), row.provider(), row.endpoint(), row.model(), apiKey,
                row.configVersion(), row.priceVersion(), row.currency(), row.inputPriceMicrosPerMillion(),
                row.outputPriceMicrosPerMillion(), row.dailyBudgetMicros(), row.maxOutputTokens(), row.timeoutSeconds(),
                row.maxTokensField(), row.jsonMode(), row.streaming());
    }

    private static String value(String supplied, String fallback) { return supplied == null ? fallback : supplied.strip(); }

    private static String text(String value, int maximum) {
        if (value == null) throw invalid();
        String result = value.strip();
        if (result.codePointCount(0, result.length()) > maximum || result.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid();
        }
        return result;
    }

    private static void require(SaveRequest request) {
        if (request == null || request.expectedRevision() == null) throw invalid();
    }

    private static void revision(Long value) {
        if (value < 0 || value > MAX_SAFE) throw invalid();
    }

    private static void uuid(String value) {
        try {
            if (value == null || !java.util.UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw new ApiException(400, "INVALID_REQUEST", "工作区身份无效。");
        }
    }

    private static ApiException invalid() {
        return new ApiException(400, "INVALID_REQUEST", "AI 配置字段、类型或取值范围不正确。");
    }

    private record Values(boolean enabled, String protocol, String provider, String endpoint, String model,
                          String configVersion, String priceVersion, String currency, long inputPriceMicrosPerMillion,
                          long outputPriceMicrosPerMillion, long dailyBudgetMicros, int maxOutputTokens,
                          int timeoutSeconds, String maxTokensField, boolean jsonMode, boolean streaming) { }

    private record Window(long started, AtomicInteger count) { }
}
