package com.wenjie.studio.ai;

import com.wenjie.studio.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.wenjie.studio.ai.AiAuthorizationDtos.*;
import static com.wenjie.studio.ai.AiAuthorizationRows.EntryRow;

@Service
public class AiAuthorizations {
    private static final long MAX_SAFE = 9_007_199_254_740_991L;
    private final AiAuthorizationMapper mapper;
    private final AiMapper events;
    private final AiProperties properties;
    private final AiSources sources;
    private final JsonMapper canonical;

    public AiAuthorizations(AiAuthorizationMapper mapper, AiMapper events, AiProperties properties,
                            AiSources sources, JsonMapper json) {
        this.mapper = mapper;
        this.events = events;
        this.properties = properties;
        this.sources = sources;
        canonical = json.rebuild().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page page(String owner, String cursor, int limit) {
        uuid(owner);
        if (cursor != null) uuid(cursor);
        if (limit < 1 || limit > 50) throw invalid();
        List<EntryRow> rows = mapper.entries(owner, cursor, limit + 1);
        if (rows == null || rows.size() > limit + 1) throw broken();
        List<Entry> items = new ArrayList<>();
        Instant now = now();
        String previous = cursor;
        for (EntryRow row : rows) {
            checked(owner, row);
            if (previous != null && row.id().compareTo(previous) <= 0) throw broken();
            previous = row.id();
            if (items.size() < limit) items.add(view(row, now));
        }
        return new Page(List.copyOf(items), rows.size() > limit ? items.getLast().id() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Matches matches(String owner, MatchRequest request) {
        uuid(owner);
        if (request == null) throw invalid();
        AiDtos.Manifest manifest = request.manifest();
        String scope = scopeHash(manifest), hash = sources.hash(manifest);
        if (!available() || !properties.configuration().equals(manifest.configuration())) {
            return new Matches(hash, scope, List.of());
        }
        Instant now = now();
        List<EntryRow> rows = mapper.matching(owner, manifest.purpose().name(), scope, properties.fingerprint(), properties.runtimeEpoch(), now);
        if (rows == null || rows.size() > 50) throw broken();
        List<Entry> items = new ArrayList<>();
        String previous = null;
        for (EntryRow row : rows) {
            checked(owner, row);
            if (!matches(row, manifest, scope) || effective(row, now) != EffectiveState.ACTIVE
                    || previous != null && row.id().compareTo(previous) <= 0) throw broken();
            previous = row.id();
            items.add(view(row, now));
        }
        return new Matches(hash, scope, List.copyOf(items));
    }

    /** 调用方已在同一 owner 工作区锁内核对原任务清单、来源和当前配置。 */
    public Entry create(String owner, AiRows.TaskRow task, AiDtos.Manifest manifest, int validDays) {
        uuid(owner);
        if (task == null || !owner.equals(task.ownerId()) || validDays < 1 || validDays > 90) throw invalid();
        uuid(task.id());
        String scope = scopeHash(manifest);
        properties.requireReady();
        if (!properties.configuration().equals(manifest.configuration())
                || !properties.fingerprint().equals(task.configurationFingerprint())) throw unavailable(EffectiveState.CONFIG_CHANGED);
        if (!manifest.purpose().name().equals(task.purpose()) || !sources.hash(manifest).equals(task.manifestHash())) {
            throw new ApiException(409, "AI_MANIFEST_CHANGED", "原任务清单已变化，请重新核对完整外发范围。");
        }
        Instant now = now();
        if (mapper.activeCount(owner, properties.runtimeEpoch(), now) >= 50) {
            throw new ApiException(422, "AI_SCOPE_AUTHORIZATION_LIMIT", "当前进程最多保留 50 条未到期的持续授权，请先撤回不再使用的范围。");
        }
        var config = manifest.configuration();
        EntryRow row = new EntryRow(owner, UUID.randomUUID().toString(), manifest.purpose().name(), config.provider(), config.model(),
                config.protocol(), config.configVersion(), config.priceVersion(), task.id(), task.manifestHash(), scope,
                task.configurationFingerprint(), properties.runtimeEpoch(), 0, State.ACTIVE.name(),
                now.plus(validDays, ChronoUnit.DAYS), now, null);
        checked(owner, row);
        if (mapper.insertEntry(row) != 1) throw broken();
        events.advanceEventCursor(owner, UUID.randomUUID().toString());
        return view(row, now);
    }

    /** 撤回与关联任务停止由调用方在同一 owner 锁事务提交，历史费用与使用绑定不改写。 */
    public Entry revoke(String owner, String id, long expectedRevision) {
        uuid(owner); uuid(id); revision(expectedRevision);
        EntryRow row = required(owner, id);
        if (row.revision() != expectedRevision) throw conflict(row.revision());
        if (!State.ACTIVE.name().equals(row.state())) throw unavailable(EffectiveState.REVOKED);
        Instant now = now();
        if (mapper.revoke(owner, id, expectedRevision, now) != 1) throw conflict(row.revision());
        events.advanceEventCursor(owner, UUID.randomUUID().toString());
        return view(required(owner, id), now);
    }

    /** 只在首次命令执行时校验；幂等回执恢复不重新授予或消费持续授权。 */
    public Use requireUse(String owner, AiDtos.Manifest manifest, Use use) {
        if (use == null) return null;
        uuid(owner); uuid(use.id());
        if (use.expectedRevision() == null) throw invalid();
        revision(use.expectedRevision());
        EntryRow row = required(owner, use.id());
        EffectiveState state = effective(row, now());
        if (state != EffectiveState.ACTIVE) throw unavailable(state);
        if (row.revision() != use.expectedRevision()) throw conflict(row.revision());
        if (!matches(row, manifest, scopeHash(manifest))) {
            throw new ApiException(409, "AI_SCOPE_AUTHORIZATION_MISMATCH", "用途、配置或确切来源范围与持续授权不一致，请重新预览并确认。");
        }
        return use;
    }

    public String refusal(String owner, AiRows.AttemptRow attempt, AiDtos.Manifest manifest) {
        if (attempt.scopeAuthorizationId() == null && attempt.scopeAuthorizationRevision() == null) return null;
        if (!owner.equals(attempt.ownerId()) || attempt.scopeAuthorizationId() == null || attempt.scopeAuthorizationRevision() == null) {
            return "AI_SCOPE_AUTHORIZATION_UNAVAILABLE";
        }
        EntryRow row = mapper.entry(owner, attempt.scopeAuthorizationId());
        if (row == null) return "AI_SCOPE_AUTHORIZATION_UNAVAILABLE";
        checked(owner, row);
        EffectiveState state = effective(row, now());
        // 正常失效返回状态，由运行时拒绝发布并提交已发生费用，不能抛异常回滚落账。
        if (state != EffectiveState.ACTIVE) return code(state);
        if (row.revision() != attempt.scopeAuthorizationRevision() || !matches(row, manifest, scopeHash(manifest))) {
            return "AI_SCOPE_AUTHORIZATION_MISMATCH";
        }
        return null;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UsagePage uses(String owner, List<AiRows.AttemptRow> attempts) {
        uuid(owner);
        if (attempts == null || attempts.size() > 3) throw invalid();
        List<Usage> items = new ArrayList<>();
        Instant now = now();
        for (AiRows.AttemptRow attempt : attempts) {
            if (attempt == null || !owner.equals(attempt.ownerId()) || attempt.createdAt() == null
                    || attempt.attemptNumber() < 1 || attempt.attemptNumber() > 3) throw broken();
            uuid(attempt.id());
            if (attempt.scopeAuthorizationId() == null && attempt.scopeAuthorizationRevision() == null) {
                items.add(new Usage(attempt.id(), attempt.attemptNumber(), Mode.ONCE, null, null, null, null, attempt.createdAt()));
                continue;
            }
            if (attempt.scopeAuthorizationId() == null || attempt.scopeAuthorizationRevision() == null) throw broken();
            uuid(attempt.scopeAuthorizationId()); revision(attempt.scopeAuthorizationRevision());
            EntryRow row = required(owner, attempt.scopeAuthorizationId());
            items.add(new Usage(attempt.id(), attempt.attemptNumber(), Mode.CONTINUING, row.id(), attempt.scopeAuthorizationRevision(),
                    row.scopeHash(), effective(row, now), attempt.createdAt()));
        }
        return new UsagePage(List.copyOf(items));
    }

    public String scopeHash(AiDtos.Manifest manifest) {
        if (manifest == null || manifest.purpose() == null || manifest.configuration() == null) throw invalid();
        ObjectNode scope = (ObjectNode) canonical.valueToTree(manifest);
        // 只排除每次主动输入与费用估算；来源正文、版本、选区及其他限定全部参与摘要。
        for (String field : List.of("instruction", "configuration", "inputTokenAllowance", "reservedMicros")) scope.remove(field);
        if (scope.get("materialQa") instanceof ObjectNode materialQa) materialQa.remove("question");
        // 只省略本次新增的空尾，既有许可范围不能因 DTO 扩展而改换摘要。
        if (manifest.knowledgeMaterialChecks() == null) scope.remove("knowledgeMaterialChecks");
        if (manifest.materialChecks() == null) scope.remove("materialChecks");
        if (manifest.sourceQa() == null) scope.remove("sourceQa");
        else if (scope.get("sourceQa") instanceof ObjectNode sourceQa) sourceQa.remove("question");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("scope-v1\n".getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest(canonical.writeValueAsBytes(scope)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行时不支持授权范围摘要", exception);
        }
    }

    private boolean matches(EntryRow row, AiDtos.Manifest manifest, String scope) {
        return manifest != null && manifest.purpose() != null && row.purpose().equals(manifest.purpose().name())
                && row.scopeHash().equals(scope) && row.configurationFingerprint().equals(properties.fingerprint())
                && properties.configuration().equals(manifest.configuration());
    }
    private boolean available() { return properties.configuration().enabled() && properties.configuration().configured(); }
    private EffectiveState effective(EntryRow row, Instant now) {
        if (State.REVOKED.name().equals(row.state())) return EffectiveState.REVOKED;
        if (!row.expiresAt().isAfter(now)) return EffectiveState.EXPIRED;
        if (!properties.runtimeEpoch().equals(row.runtimeEpoch())) return EffectiveState.RECONFIRM_REQUIRED;
        if (!available()) return EffectiveState.UNAVAILABLE;
        if (!properties.fingerprint().equals(row.configurationFingerprint())) return EffectiveState.CONFIG_CHANGED;
        return EffectiveState.ACTIVE;
    }
    private Entry view(EntryRow row, Instant now) {
        return new Entry(row.id(), AiDtos.Purpose.valueOf(row.purpose()), row.provider(), row.model(), row.protocol(),
                row.configVersion(), row.priceVersion(), row.sourceTaskId(), row.sourceManifestHash(), row.scopeHash(), row.revision(),
                State.valueOf(row.state()), effective(row, now), row.expiresAt(), row.createdAt(), row.revokedAt());
    }
    private EntryRow required(String owner, String id) {
        EntryRow row = mapper.entry(owner, id);
        if (row == null) throw new ApiException(404, "AI_SCOPE_AUTHORIZATION_NOT_FOUND", "持续授权不存在或不可访问。");
        checked(owner, row);
        return row;
    }
    private static void checked(String owner, EntryRow row) {
        try {
            if (row == null || !Objects.equals(owner, row.ownerId())) throw broken();
            uuid(row.id()); uuid(row.sourceTaskId()); uuid(row.runtimeEpoch());
            AiDtos.Purpose.valueOf(row.purpose());
            State state = State.valueOf(row.state());
            if (!publicText(row.provider(), 120) || !publicText(row.model(), 200) || !publicText(row.configVersion(), 80)
                    || !publicText(row.priceVersion(), 80) || !List.of("CHAT_COMPLETIONS", "RESPONSES").contains(row.protocol())
                    || !hash(row.scopeHash()) || !hash(row.sourceManifestHash()) || !hash(row.configurationFingerprint())
                    || row.createdAt() == null || row.expiresAt() == null || !row.expiresAt().isAfter(row.createdAt())
                    || row.expiresAt().isAfter(row.createdAt().plus(90, ChronoUnit.DAYS))
                    || state == State.ACTIVE && (row.revision() != 0 || row.revokedAt() != null)
                    || state == State.REVOKED && (row.revision() != 1 || row.revokedAt() == null || row.revokedAt().isBefore(row.createdAt()))) throw broken();
        } catch (ApiException | IllegalArgumentException | NullPointerException exception) { throw broken(); }
    }
    private static boolean publicText(String value, int max) {
        return value != null && !value.isBlank() && value.codePointCount(0, value.length()) <= max && value.indexOf('\0') < 0
                && value.codePoints().noneMatch(point -> point >= 0xD800 && point <= 0xDFFF);
    }
    private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static void uuid(String value) {
        try { if (value == null || !UUID.fromString(value).toString().equals(value)) throw invalid(); }
        catch (IllegalArgumentException exception) { throw invalid(); }
    }
    private static void revision(long value) { if (value < 0 || value > MAX_SAFE) throw invalid(); }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static String code(EffectiveState state) { return "AI_SCOPE_AUTHORIZATION_" + state.name(); }
    private static ApiException invalid() { return new ApiException(400, "INVALID_REQUEST", "持续授权的身份、修订、有效期或清单无效。"); }
    private static ApiException conflict(long current) { return new ApiException(409, "REVISION_CONFLICT", "持续授权已变化，请重新读取后操作。", current); }
    private static ApiException unavailable(EffectiveState state) {
        return new ApiException(409, code(state), "此持续授权当前不可使用，请重新核对原任务范围并明确确认。");
    }
    private static IllegalStateException broken() { return new IllegalStateException("持续授权元数据与固定身份或状态不一致"); }
}
