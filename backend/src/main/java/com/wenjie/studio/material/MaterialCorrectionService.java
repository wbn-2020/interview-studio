package com.wenjie.studio.material;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static com.wenjie.studio.material.MaterialCorrectionDtos.*;
import static com.wenjie.studio.material.MaterialGuards.*;

@Service
public class MaterialCorrectionService {
    private final MaterialCorrectionMapper mapper;
    private final MaterialLifecycleService lifecycle;
    private final MaterialSources sources;
    private final MaterialProperties properties;
    private final IdempotencyService commands;
    private final MaterialKnowledgeAdoptions adoptions;
    private final MaterialKnowledgeImpactPropagation impacts;
    private final MaterialCardDraftProof cardDrafts;
    private final MaterialDependencyImpactService dependencies;

    public MaterialCorrectionService(MaterialCorrectionMapper mapper, MaterialLifecycleService lifecycle, MaterialSources sources,
                                     MaterialProperties properties, IdempotencyService commands,
                                     MaterialKnowledgeAdoptions adoptions, MaterialKnowledgeImpactPropagation impacts,
                                     MaterialCardDraftProof cardDrafts, MaterialDependencyImpactService dependencies) {
        this.mapper = mapper; this.lifecycle = lifecycle;
        this.sources = sources; this.properties = properties; this.commands = commands;
        this.adoptions = adoptions; this.impacts = impacts; this.cardDrafts = cardDrafts;
        this.dependencies = dependencies;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 20)
    public State state(String owner, String excerptId) {
        uuid(owner); uuid(excerptId);
        var source = snapshot(owner, excerptId);
        var row = mapper.statement(owner, excerptId);
        Statement statement = row == null ? null : statement(owner, excerptId, source, row);
        return new State(excerptId, source.lifecycle().attachmentId(), source.lifecycle().revision(), statement);
    }

    public State create(String owner, String excerptId, String key, Create request) {
        // 模块关闭也拒绝历史回执；两端当前业务资格仍只在首次执行时核对。
        if (!properties.enabled()) throw new ApiException(503, "MATERIALS_DISABLED", "服务器尚未启用附件资料。");
        uuid(owner); uuid(excerptId); uuid(key);
        if (request == null || !Boolean.TRUE.equals(request.confirmed())) throw invalid("须明确确认正文更正声明。");
        revision(request.expectedRevision()); revision(request.expectedReplacementRevision());
        uuid(request.replacementExcerptId());
        if (excerptId.equals(request.replacementExcerptId())) throw invalid("替代摘录不能是原摘录自身。");
        String reason = reason(request.reason());
        return commands.execute(owner, key, "POST /api/v1/materials/excerpts/" + excerptId + "/correction",
                request, State.class, () -> {
                    // 资格检查放在首次业务执行内；同键重放返回原声明，不因替代材料后来的变更失去回执。
                    if (mapper.corrected(owner, excerptId)) throw corrected();
                    var source = snapshot(owner, excerptId);
                    var replacement = snapshot(owner, request.replacementExcerptId());
                    expected(source.lifecycle(), request.expectedRevision());
                    expected(replacement.lifecycle(), request.expectedReplacementRevision());
                    lifecycle.requireActive(owner, excerptId);
                    lifecycle.requireActive(owner, request.replacementExcerptId());
                    if (source.excerpt().text().equals(replacement.excerpt().text())) throw invalid("正文相同不能声明正文更正。");
                    var directDependencies = dependencies.collect(owner, excerptId);
                    var usageRoutes = dependencies.collectUsage(owner, excerptId);
                    // 固定采用闭包与非知识出口分别证明；正常引用不再作为一刀切的操作禁令。
                    var graph = adoptions.collect(owner, excerptId, source.lifecycle());
                    cardDrafts.verify(owner, graph);
                    Instant now = now();
                    if (now.isBefore(source.lifecycle().updatedAt()) || now.isBefore(replacement.lifecycle().updatedAt())) {
                        throw MaterialKnowledgeImpactRules.clock("服务器时间早于摘录许可基线，本次更正未提交，请校正服务器时间后重试。");
                    }
                    if (!time(now)) throw integrity();
                    impacts.checkClock(owner, now);
                    dependencies.checkClock(owner, now, usageRoutes);
                    var value = new Statement(id(), excerptId, request.replacementExcerptId(), source.lifecycle().revision(),
                            replacement.lifecycle().revision(), reason, now);
                    if (mapper.insert(owner, value) != 1) throw integrity();
                    impacts.corrected(owner, value, graph);
                    dependencies.corrected(owner, value, directDependencies, source.lifecycle().attachmentId(), usageRoutes);
                    State after = state(owner, excerptId);
                    if (!value.equals(after.correction()) || after.lifecycleRevision() != source.lifecycle().revision()
                            || !after.attachmentId().equals(source.lifecycle().attachmentId())) throw integrity();
                    return after;
                });
    }

    private Statement statement(String owner, String excerptId, Snapshot source, MaterialCorrectionMapper.Row row) {
        if (!owner.equals(row.ownerId()) || !storedUuid(row.id()) || !excerptId.equals(row.excerptId())
                || !storedUuid(row.replacementExcerptId()) || excerptId.equals(row.replacementExcerptId())
                || !safeRevision(row.sourceRevision()) || !safeRevision(row.replacementRevision()) || !time(row.createdAt())
                || row.sourceRevision() != source.lifecycle().revision()
                || row.createdAt().isBefore(source.lifecycle().updatedAt())) throw integrity();
        try { if (!reason(row.reason()).equals(row.reason())) throw integrity(); }
        catch (ApiException exception) { throw integrity(); }
        Snapshot replacement;
        try { replacement = snapshot(owner, row.replacementExcerptId()); }
        catch (ApiException exception) {
            if ("MATERIAL_NOT_FOUND".equals(exception.code())) throw integrity();
            throw exception;
        }
        // 替代端以后可归档或继续更正；历史声明只指向原固定身份，不追随替代链改写或要求仍可新选用。
        if (row.replacementRevision() > replacement.lifecycle().revision()
                || row.createdAt().isBefore(replacement.lifecycle().createdAt())
                || row.replacementRevision() == replacement.lifecycle().revision()
                    && row.createdAt().isBefore(replacement.lifecycle().updatedAt())
                || source.excerpt().text().equals(replacement.excerpt().text())) throw integrity();
        captured(owner, excerptId, row.sourceRevision(), source.lifecycle().createdAt(), row.createdAt());
        captured(owner, row.replacementExcerptId(), row.replacementRevision(), replacement.lifecycle().createdAt(), row.createdAt());
        return new Statement(row.id(), row.excerptId(), row.replacementExcerptId(), row.sourceRevision(),
                row.replacementRevision(), row.reason(), row.createdAt());
    }

    private void captured(String owner, String excerptId, long revision, Instant createdAt, Instant declaredAt) {
        if (revision == 0) {
            if (declaredAt.isBefore(createdAt)) throw integrity();
            return;
        }
        // 替代端的当前修订可更晚，仍须证明声明记录的那一版已真实发生，不能只比较安全整数大小。
        var page = lifecycle.history(owner, excerptId, revision == 1 ? null : excerptId + "~" + (revision - 1), 1);
        if (page.items().size() != 1) throw integrity();
        var event = page.items().getFirst();
        if (event.revision() != revision || event.status() != MaterialLifecycleDtos.Status.ACTIVE
                || declaredAt.isBefore(event.createdAt())) throw integrity();
    }

    private Snapshot snapshot(String owner, String excerptId) {
        var state = lifecycle.state(owner, excerptId);
        var fixed = sources.readCorrectionSource(owner, excerptId);
        if (!excerptId.equals(state.excerptId()) || !excerptId.equals(fixed.excerptId())
                || !state.attachmentId().equals(fixed.attachmentId())) throw integrity();
        return new Snapshot(state, fixed);
    }

    private static void expected(MaterialLifecycleDtos.State state, Long expected) {
        if (state.revision() != expected) {
            throw new ApiException(409, "MATERIAL_REVISION_CONFLICT", "旧摘录或替代摘录的许可修订已变化，请保留原请求并重新核对。", state.revision());
        }
    }
    private static String reason(String value) {
        if (value == null || value.indexOf('\0') >= 0) throw invalid("更正理由无效。");
        for (int index = 0; index < value.length(); index++) {
            char item = value.charAt(index);
            if (Character.isHighSurrogate(item)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) throw invalid("更正理由包含无效字符。");
            } else if (Character.isLowSurrogate(item)) throw invalid("更正理由包含无效字符。");
        }
        String result = value.strip();
        if (result.isEmpty() || result.length() > 1000) throw invalid("更正理由须为 1–1000 字符。");
        return result;
    }
    private static boolean safeRevision(Long value) { return value != null && value >= 0 && value <= MAX_REVISION; }
    private static boolean storedUuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException exception) { return false; }
    }
    private static boolean time(Instant value) {
        return value != null && value.getNano() % 1000 == 0
                && !value.isBefore(Instant.parse("1000-01-01T00:00:00Z"))
                && !value.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"));
    }
    private static ApiException corrected() {
        return new ApiException(409, "MATERIAL_CONTENT_CORRECTED", "摘录已声明正文更正，只保留历史读取，不能再次更正或新选用。");
    }
    private static IllegalStateException integrity() { return new IllegalStateException("材料正文更正声明与固定摘录或许可基线不一致"); }
    private record Snapshot(MaterialLifecycleDtos.State lifecycle, MaterialDtos.AiExcerpt excerpt) {}
}
