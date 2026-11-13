package com.wenjie.studio.material;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import com.wenjie.studio.dataprivacy.PrivacyLifecycle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static com.wenjie.studio.material.MaterialGuards.*;
import static com.wenjie.studio.material.MaterialLifecycleDtos.*;

@Service
public class MaterialLifecycleService {
    private final MaterialLifecycleMapper mapper;
    private final MaterialProperties properties;
    private final IdempotencyService commands;
    private final MaterialKnowledgeAdoptions knowledgeAdoptions;
    private final MaterialCorrectionMapper corrections;
    private final MaterialDependencyImpactService dependencies;
    private final PrivacyLifecycle privacy;

    public MaterialLifecycleService(MaterialLifecycleMapper mapper, MaterialProperties properties, IdempotencyService commands,
                                    MaterialKnowledgeAdoptions knowledgeAdoptions, MaterialCorrectionMapper corrections,
                                    MaterialDependencyImpactService dependencies, PrivacyLifecycle privacy) {
        this.mapper = mapper; this.properties = properties; this.commands = commands;
        this.knowledgeAdoptions = knowledgeAdoptions; this.corrections = corrections;
        this.dependencies = dependencies;
        this.privacy = privacy;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 20)
    public State state(String owner, String excerptId) {
        enabled(); uuid(owner); uuid(excerptId);
        return view(required(owner, excerptId), mapper.hasReferences(owner, excerptId));
    }

    public State change(String owner, String excerptId, String key, Change request) {
        enabled(); uuid(owner); uuid(excerptId); uuid(key);
        if (request == null || request.action() == null || !Boolean.TRUE.equals(request.confirmed())) throw invalid("须明确确认生命周期动作。");
        revision(request.expectedRevision());
        String reason = reason(request.reason());
        return commands.execute(owner, key, "POST /api/v1/materials/excerpts/" + excerptId + "/lifecycle/changes",
                request, State.class, () -> {
                    uncorrected(owner, excerptId);
                    var before = required(owner, excerptId);
                    if (before.revision().longValue() != request.expectedRevision().longValue()) {
                        throw new ApiException(409, "MATERIAL_REVISION_CONFLICT", "摘录生命周期已变化，请保留原请求并读取最新状态。", before.revision());
                    }
                    var target = transition(request.action(), Status.valueOf(before.status()), AiUse.valueOf(before.aiUse()));
                    if (before.revision() == MAX_REVISION) throw conflict("摘录生命周期修订已达到上限。");
                    // 变更前在 owner 锁内完整证明实际使用路径；组织／白板仅结构关联，不伪造正文传播。
                    dependencies.collect(owner, excerptId);
                    var usageRoutes = dependencies.collectUsage(owner, excerptId);
                    knowledgeAdoptions.collect(owner, excerptId, view(before, mapper.hasReferences(owner, excerptId)));
                    Instant now = now();
                    if (now.isBefore(before.updatedAt())) throw conflict("服务器时间早于材料许可基线，本次变更未提交。");
                    dependencies.checkClock(owner, now, usageRoutes);
                    if (mapper.change(owner, excerptId, before.revision(), target.status().name(), target.aiUse().name(), now) != 1) {
                        throw new ApiException(409, "MATERIAL_REVISION_CONFLICT", "摘录生命周期已变化，请读取最新状态。");
                    }
                    var event = new Event(id(), excerptId, request.action(), Status.valueOf(before.status()),
                            AiUse.valueOf(before.aiUse()), before.revision(), target.status(), target.aiUse(),
                            before.revision() + 1, reason, now);
                    mapper.insertEvent(owner, event);
                    if (request.action() == Action.ARCHIVE || request.action() == Action.RESTORE) {
                        dependencies.localStatusChanged(owner, before.attachmentId(), event, usageRoutes);
                    }
                    privacy.recordMaterialAiUse(owner, key, excerptId, request);
                    return view(required(owner, excerptId), mapper.hasReferences(owner, excerptId));
                });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 20)
    public HistoryPage history(String owner, String excerptId, String after, int limit) {
        enabled(); uuid(owner); uuid(excerptId);
        if (limit < 1 || limit > 20) throw invalid("每页数量须为 1–20。");
        long revision = after(after, excerptId);
        var current = required(owner, excerptId);
        if (revision > current.revision()) throw invalid("生命周期历史游标超出当前修订。");
        var rows = mapper.history(owner, excerptId, revision, limit + 1);
        if (rows == null || rows.size() > limit + 1) throw integrity();
        long previous = revision;
        Event last = null;
        for (Event event : rows) {
            event(event, excerptId);
            if (event.revision() != previous + 1 || event.revision() > current.revision()
                    || event.createdAt().isBefore(current.createdAt()) || event.createdAt().isAfter(current.updatedAt())
                    || last != null && (event.previousStatus() != last.status() || event.previousAiUse() != last.aiUse()
                    || event.createdAt().isBefore(last.createdAt()))) throw integrity();
            previous = event.revision(); last = event;
        }
        if (rows.size() <= limit && revision < current.revision() && previous != current.revision()) throw integrity();
        if (last != null && last.revision() == current.revision()
                && (!last.status().name().equals(current.status()) || !last.aiUse().name().equals(current.aiUse())
                || !last.createdAt().equals(current.updatedAt()))) throw integrity();
        var items = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
        return new HistoryPage(excerptId, items, rows.size() > limit ? excerptId + "~" + items.getLast().revision() : null);
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = RuntimeException.class)
    public void requireActive(String owner, String excerptId) {
        enabled(); uuid(owner); uuid(excerptId);
        active(required(owner, excerptId));
        uncorrected(owner, excerptId);
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = RuntimeException.class)
    public boolean aiAllowed(String owner, String excerptId) {
        if (!properties.enabled() || !storedUuid(owner) || !storedUuid(excerptId)) return false;
        var row = load(owner, excerptId);
        return row != null && "ACTIVE".equals(row.status()) && "ALLOWED".equals(row.aiUse())
                && !corrections.corrected(owner, excerptId);
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = RuntimeException.class)
    public List<Check> captureAiChecks(String owner, List<String> ids) {
        enabled(); uuid(owner);
        List<String> sorted = ids(ids);
        if (sorted.isEmpty()) return null;
        var result = new ArrayList<Check>(sorted.size());
        for (String excerptId : sorted) {
            var row = required(owner, excerptId);
            active(row);
            uncorrected(owner, excerptId);
            if (!"ALLOWED".equals(row.aiUse())) throw new ApiException(409, "MATERIAL_AI_USE_REVOKED", "摘录已撤回 AI 用途。");
            result.add(new Check(excerptId, row.revision()));
        }
        return List.copyOf(result);
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = RuntimeException.class)
    public boolean currentAiChecks(String owner, List<String> ids, List<Check> checks) {
        if (!storedUuid(owner)) return false;
        List<String> sorted;
        try { sorted = ids(ids); } catch (ApiException exception) { return false; }
        if (sorted.isEmpty()) return checks == null;
        if (!properties.enabled() || checks != null && checks.size() != sorted.size()) return false;
        for (int index = 0; index < sorted.size(); index++) {
            String excerptId = sorted.get(index);
            Check check = checks == null ? null : checks.get(index);
            if (checks != null && (check == null || !excerptId.equals(check.excerptId()) || check.revision() == null
                    || check.revision() < 0 || check.revision() > MAX_REVISION)) return false;
            var row = load(owner, excerptId);
            // 历史空尾只兼容从未改变的授权代次，恢复与重新允许不会让旧清单重新有效。
            if (row == null || !"ACTIVE".equals(row.status()) || !"ALLOWED".equals(row.aiUse())
                    || row.revision() != (check == null ? 0 : check.revision())
                    || corrections.corrected(owner, excerptId)) return false;
        }
        return true;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void initialize(String owner, String excerptId, String attachmentId, Instant now) {
        uuid(owner); uuid(excerptId); uuid(attachmentId);
        if (!time(now)) throw integrity();
        mapper.initialize(owner, excerptId, attachmentId, now);
        var row = required(owner, excerptId);
        if (row.revision() != 0 || !"ACTIVE".equals(row.status()) || !"ALLOWED".equals(row.aiUse())
                || !attachmentId.equals(row.attachmentId()) || !now.equals(row.createdAt()) || !now.equals(row.updatedAt())) throw integrity();
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = RuntimeException.class)
    public void requireBaselines(String owner, String attachmentId) {
        uuid(owner); uuid(attachmentId);
        if (mapper.missingBaseline(owner, attachmentId)) throw integrity();
    }

    private MaterialLifecycleMapper.Row required(String owner, String id) {
        var row = load(owner, id);
        if (row == null) throw missing();
        return row;
    }

    private MaterialLifecycleMapper.Row load(String owner, String id) {
        var row = mapper.source(owner, id);
        if (row == null) return null;
        if (!id.equals(row.excerptId()) || !storedUuid(row.attachmentId())
                || !id.equals(row.baselineExcerptId()) || !row.attachmentId().equals(row.baselineAttachmentId())
                || !("ACTIVE".equals(row.status()) || "ARCHIVED".equals(row.status()))
                || !("ALLOWED".equals(row.aiUse()) || "REVOKED".equals(row.aiUse()))
                || row.revision() == null || row.revision() < 0 || row.revision() > MAX_REVISION
                || !time(row.createdAt()) || !row.createdAt().equals(row.excerptCreatedAt()) || !time(row.updatedAt())
                || row.updatedAt().isBefore(row.createdAt())
                || row.revision() == 0 && (!"ACTIVE".equals(row.status()) || !"ALLOWED".equals(row.aiUse())
                || !row.updatedAt().equals(row.createdAt()))) throw integrity();
        return row;
    }

    private static State view(MaterialLifecycleMapper.Row row, boolean references) {
        return new State(row.excerptId(), row.attachmentId(), Status.valueOf(row.status()), AiUse.valueOf(row.aiUse()),
                row.revision(), row.createdAt(), row.updatedAt(), references);
    }
    private static void active(MaterialLifecycleMapper.Row row) {
        if (!"ACTIVE".equals(row.status())) throw new ApiException(409, "MATERIAL_NOT_ACTIVE", "摘录已归档，不能新建本地或 AI 选用。");
    }
    private void uncorrected(String owner, String excerptId) {
        // 正文更正独立于许可代次；任何生命周期动作都不能恢复旧正文的新用途。
        if (corrections.corrected(owner, excerptId)) {
            throw new ApiException(409, "MATERIAL_CONTENT_CORRECTED", "摘录已声明正文更正，只保留历史读取，不能变更生命周期或新选用。");
        }
    }
    private static Target transition(Action action, Status status, AiUse aiUse) {
        return switch (action) {
            case ARCHIVE -> { if (status != Status.ACTIVE) throw conflict("摘录已经归档。"); yield new Target(Status.ARCHIVED, aiUse); }
            case RESTORE -> { if (status != Status.ARCHIVED) throw conflict("摘录当前未归档。"); yield new Target(Status.ACTIVE, aiUse); }
            case REVOKE_AI -> { if (aiUse != AiUse.ALLOWED) throw conflict("摘录 AI 用途已经撤回。"); yield new Target(status, AiUse.REVOKED); }
            case ALLOW_AI -> { if (aiUse != AiUse.REVOKED) throw conflict("摘录 AI 用途当前已允许。"); yield new Target(status, AiUse.ALLOWED); }
        };
    }
    private static List<String> ids(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        if (new HashSet<>(values).size() != values.size()) throw invalid("资料摘录身份不能重复。");
        values.forEach(MaterialGuards::uuid);
        return values.stream().sorted().toList();
    }
    private static long after(String value, String excerptId) {
        if (value == null) return 0;
        if (value.length() > 60) throw invalid("生命周期历史游标无效。");
        String[] parts = value.split("~", -1);
        if (parts.length != 2 || !excerptId.equals(parts[0]) || !parts[1].matches("[1-9][0-9]{0,15}")) throw invalid("生命周期历史游标无效。");
        try { long result = Long.parseLong(parts[1]); if (result > MAX_REVISION) throw invalid("生命周期历史游标无效。"); return result; }
        catch (NumberFormatException exception) { throw invalid("生命周期历史游标无效。"); }
    }
    private static void event(Event event, String excerptId) {
        if (event == null || !storedUuid(event.id()) || !excerptId.equals(event.excerptId())
                || event.action() == null || event.previousStatus() == null || event.previousAiUse() == null
                || event.status() == null || event.aiUse() == null || event.previousRevision() < 0
                || event.revision() < 1 || event.revision() > MAX_REVISION
                || event.previousRevision() != event.revision() - 1 || !time(event.createdAt())) throw integrity();
        try {
            if (!reason(event.reason()).equals(event.reason())) throw integrity();
            var target = transition(event.action(), event.previousStatus(), event.previousAiUse());
            if (target.status() != event.status() || target.aiUse() != event.aiUse()) throw integrity();
        } catch (ApiException exception) { throw integrity(); }
    }
    private static String reason(String value) {
        if (value == null || value.indexOf('\0') >= 0) throw invalid("变更理由无效。");
        for (int index = 0; index < value.length(); index++) {
            char item = value.charAt(index);
            if (Character.isHighSurrogate(item)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) throw invalid("变更理由包含无效字符。");
            } else if (Character.isLowSurrogate(item)) throw invalid("变更理由包含无效字符。");
        }
        String result = value.strip();
        if (result.isEmpty() || result.length() > 1000) throw invalid("变更理由须为 1–1000 字符。");
        return result;
    }
    private static boolean storedUuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException exception) { return false; }
    }
    private static boolean time(Instant value) {
        return value != null && value.getNano() % 1000 == 0
                && !value.isBefore(Instant.parse("1000-01-01T00:00:00Z"))
                && !value.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"));
    }
    private void enabled() { if (!properties.enabled()) throw new ApiException(503, "MATERIALS_DISABLED", "服务器尚未启用附件资料。"); }
    private static ApiException conflict(String message) { return new ApiException(409, "MATERIAL_STATE_CONFLICT", message); }
    private static IllegalStateException integrity() { return new IllegalStateException("材料生命周期基线或事件元数据不一致"); }
    private record Target(Status status, AiUse aiUse) {}
}
