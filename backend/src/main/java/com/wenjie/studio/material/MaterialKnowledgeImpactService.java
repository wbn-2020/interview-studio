package com.wenjie.studio.material;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import com.wenjie.studio.lifecycle.KnowledgeRevalidationDtos;
import com.wenjie.studio.lifecycle.KnowledgeRevalidationMapper;
import com.wenjie.studio.lifecycle.KnowledgeRevalidationRows;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static com.wenjie.studio.material.MaterialKnowledgeImpactDtos.*;
import static com.wenjie.studio.material.MaterialKnowledgeImpactRules.*;

@Service
public class MaterialKnowledgeImpactService {
    private final MaterialKnowledgeImpactMapper mapper;
    private final MaterialCorrectionService corrections;
    private final MaterialProperties properties;
    private final IdempotencyService commands;
    private final KnowledgeRevalidationMapper frozen;

    public MaterialKnowledgeImpactService(MaterialKnowledgeImpactMapper mapper, MaterialCorrectionService corrections,
                                         MaterialProperties properties, IdempotencyService commands,
                                         KnowledgeRevalidationMapper frozen) {
        this.mapper = mapper; this.corrections = corrections; this.properties = properties; this.commands = commands;
        this.frozen = frozen;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 20)
    public Page list(String owner, Context context, String itemId, KnowledgeRevalidationDtos.State state, String cursor, int limit) {
        enabled(); uuid(owner); uuid(itemId);
        if (context == null || state == null || limit < 1 || limit > 20) throw invalid("须指定材料／知识上下文、处理状态及 1 至 20 条数量。");
        Before before = cursor(cursor, owner, context, itemId, state);
        var roots = new HashMap<String, MaterialCorrectionDtos.Statement>();
        String correctionId = null;
        if (context == Context.MATERIAL_EXCERPT) {
            var correction = corrections.state(owner, itemId).correction();
            if (correction == null) return new Page(context, itemId, state, java.util.List.of(), null);
            roots.put(itemId, correction); correctionId = correction.id();
        } else if (!mapper.knowledgeExists(owner, itemId)) throw missing();
        var rows = mapper.impacts(owner, context.name(), itemId, correctionId, state.name(),
                before == null ? null : before.time(), before == null ? null : before.id(), limit + 1);
        if (rows == null || rows.size() > limit + 1) throw integrity();
        var items = new ArrayList<Impact>();
        Impact previous = null;
        for (var row : rows) {
            Impact value = checked(owner, row, roots);
            if (context == Context.MATERIAL_EXCERPT && !itemId.equals(value.correction().excerptId())
                    || context == Context.KNOWLEDGE && !itemId.equals(value.targetItemId())
                    || state == KnowledgeRevalidationDtos.State.PENDING && value.revision() != 0
                    || before != null && !before(value.createdAt(), value.id(), before.time(), before.id())
                    || previous != null && !before(value.createdAt(), value.id(), previous.createdAt(), previous.id())) throw integrity();
            if (items.size() < limit) items.add(value);
            previous = value;
        }
        String next = null;
        if (rows.size() > limit) {
            Impact last = items.getLast();
            next = owner + "~" + context.name() + "~" + itemId + "~" + state.name()
                    + "~" + CURSOR_TIME.format(last.createdAt()) + "~" + last.id();
        }
        return new Page(context, itemId, state, java.util.List.copyOf(items), next);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 20)
    public Impact detail(String owner, String impactId) {
        enabled(); uuid(owner); uuid(impactId);
        var row = mapper.impact(owner, impactId);
        if (row == null) throw missing();
        if (!impactId.equals(row.id())) throw integrity();
        return checked(owner, row, new HashMap<>());
    }

    public Impact resolve(String owner, String itemId, String impactId, String key, KnowledgeRevalidationDtos.Resolve request) {
        // 模块关闭时也不返回原键回执；今日目标资格只在首次业务动作内核对。
        enabled(); uuid(owner); uuid(itemId); uuid(impactId); uuid(key);
        if (request == null || request.choice() == null || !Boolean.TRUE.equals(request.confirmed())) {
            throw invalid("须提供处理方式并明确确认。");
        }
        revision(request.expectedRevision()); revision(request.expectedTargetRevision());
        uuid(request.expectedTargetVersionId()); reason(request.reason());
        return commands.execute(owner, key, "POST /api/v1/lifecycle/knowledge/" + itemId
                + "/material-impacts/" + impactId + "/resolve", request, Impact.class, () -> {
            writing();
            var roots = new HashMap<String, MaterialCorrectionDtos.Statement>();
            var row = mapper.impact(owner, impactId);
            if (row == null || !itemId.equals(row.targetItemId())) throw missing();
            if (!impactId.equals(row.id())) throw integrity();
            Impact before = checked(owner, row, roots);
            if (before.revision() != request.expectedRevision() || before.revision() != 0) {
                throw conflict("此次材料采用影响已经处理或修订不匹配，请读取当前记录。", before.revision());
            }
            var target = mapper.target(owner, itemId, before.targetVersionId());
            if (target == null) throw missing();
            requireTarget(target, before);
            if (target.revision() != request.expectedTargetRevision()
                    || !target.currentVersionId().equals(request.expectedTargetVersionId())) {
                throw targetConflict("目标知识已变化，请保留理由并核对当前发布版。", target.revision());
            }
            if (!target.publishedTitle().equals(target.draftTitle()) || !target.publishedBody().equals(target.draftBody())) {
                throw new ApiException(409, "DRAFT_UNPUBLISHED", "目标存在未发布草稿，请先处理草稿再确认此次影响。", target.revision());
            }
            if (request.choice() == KnowledgeRevalidationDtos.Choice.ARCHIVED) {
                if (!"ARCHIVED".equals(target.status())) throw targetConflict("目标须先完成归档，才能登记归档处理。", target.revision());
            } else {
                if (!"ACTIVE".equals(target.status())) {
                    throw new ApiException(409, "KNOWLEDGE_ARCHIVED", "目标已归档，请恢复后核对内容或登记归档处理。", target.revision());
                }
                if (request.choice() == KnowledgeRevalidationDtos.Choice.PUBLISHED_UPDATE
                        && (target.currentVersionNumber() <= target.adoptedVersionNumber()
                            || target.publishedBody().equals(target.adoptedBody()))) {
                    throw targetConflict("发布更新须晚于原采用版且原始正文确已改变；标题改版不能代替正文更新。", target.revision());
                }
            }
            Instant now = now();
            Instant frozenAt = frozen.latestFrozenAt(owner);
            if (frozenAt != null && (!validTime(frozenAt) || !now.isAfter(frozenAt))) {
                throw clock("服务器时间未晚于既有冻结证据或来源处理，处理未提交，请校正服务器时间后重试。");
            }
            if (now.isBefore(before.createdAt())) throw clock("服务器时间早于本次材料影响，处理未提交，请校正服务器时间后重试。");
            if (mapper.resolve(owner, itemId, impactId, request.expectedRevision(), request.choice().name(), request.reason(),
                    target.currentVersionId(), target.revision(), now) != 1) {
                throw conflict("此次材料影响或目标基线已经变化，本次处理未提交。", before.revision());
            }
            var afterRow = mapper.impact(owner, impactId);
            if (afterRow == null || !impactId.equals(afterRow.id())) throw integrity();
            Impact after = checked(owner, afterRow, roots);
            var decision = after.resolution();
            if (after.revision() != 1 || decision == null || decision.choice() != request.choice()
                    || !decision.reason().equals(request.reason()) || !decision.targetVersionId().equals(target.currentVersionId())
                    || decision.targetRevision() != target.revision() || !decision.decidedAt().equals(now)) throw integrity();
            // 只关闭这一条待核对；材料祖先仍严格拒绝旧用途，发布更新或保留均不重建依据、不恢复评分或卡排程。
            return after;
        });
    }

    private Impact checked(String owner, MaterialKnowledgeImpactRows.ImpactRow row,
                           Map<String, MaterialCorrectionDtos.Statement> roots) {
        Impact value = view(owner, row, roots);
        if (row.parentImpactId() != null) {
            var parentRow = mapper.impact(owner, row.parentImpactId());
            if (parentRow == null) throw integrity();
            Impact parent = view(owner, parentRow, roots);
            if (!parent.id().equals(value.parentImpactId()) || !parent.correction().equals(value.correction())
                    || !parent.createdAt().equals(value.createdAt()) || !parent.targetItemId().equals(value.source().itemId())
                    || parent.targetVersionNumber() > value.source().versionNumber()
                    || (long) parent.depth() + 1 != value.depth()) throw integrity();
        }
        return value;
    }

    private Impact view(String owner, MaterialKnowledgeImpactRows.ImpactRow row,
                        Map<String, MaterialCorrectionDtos.Statement> roots) {
        if (row == null || !owner.equals(row.ownerId()) || !storedUuid(row.id()) || !safeRevision(row.revision())
                || row.revision() > 1 || !storedUuid(row.correctionId()) || !storedUuid(row.rootExcerptId())
                || !validTime(row.correctionCreatedAt()) || !row.correctionCreatedAt().equals(row.createdAt())
                || !storedUuid(row.taskId()) || !"KNOWLEDGE_DRAFT".equals(row.taskPurpose()) || !"SUCCEEDED".equals(row.taskStatus())
                || !"ADOPTED".equals(row.adoptionStatus()) || !storedUuid(row.targetItemId()) || !storedUuid(row.targetVersionId())
                || row.targetTitle() == null || row.targetTitle().isBlank() || row.targetVersionNumber() == null
                || row.targetVersionNumber() < 1 || row.depth() == null || row.depth() < 1 || row.depth() > 500) throw integrity();
        var correction = roots.get(row.rootExcerptId());
        if (correction == null) {
            correction = corrections.state(owner, row.rootExcerptId()).correction();
            if (correction == null) throw integrity();
            roots.put(row.rootExcerptId(), correction);
        }
        if (!correction.id().equals(row.correctionId()) || !correction.excerptId().equals(row.rootExcerptId())
                || !correction.createdAt().equals(row.createdAt())) throw integrity();
        Source source;
        if ("MATERIAL_EXCERPT".equals(row.sourceDomain())) {
            if (!correction.excerptId().equals(row.materialSourceExcerptId()) || row.knowledgeSourceItemId() != null
                    || row.knowledgeSourceVersionId() != null || row.sourceVersionNumber() != null || row.sourceTitle() != null
                    || !row.taskId().equals(row.materialBindingTaskId()) || row.knowledgeBindingTaskId() != null
                    || row.depth() != 1 || row.parentImpactId() != null) throw integrity();
            source = new Source(Domain.MATERIAL_EXCERPT, row.materialSourceExcerptId(), null, null, null);
        } else if ("KNOWLEDGE".equals(row.sourceDomain())) {
            if (row.materialSourceExcerptId() != null || !storedUuid(row.knowledgeSourceItemId())
                    || !storedUuid(row.knowledgeSourceVersionId()) || row.sourceVersionNumber() == null
                    || row.sourceVersionNumber() < 1 || row.sourceTitle() == null || row.sourceTitle().isBlank()
                    || !row.taskId().equals(row.knowledgeBindingTaskId()) || row.materialBindingTaskId() != null
                    || row.depth() < 2 || !storedUuid(row.parentImpactId()) || row.id().equals(row.parentImpactId())) throw integrity();
            source = new Source(Domain.KNOWLEDGE, row.knowledgeSourceItemId(), row.knowledgeSourceVersionId(),
                    row.sourceVersionNumber(), row.sourceTitle());
        } else throw integrity();
        KnowledgeRevalidationDtos.Resolution resolution = null;
        if (row.revision() == 0) {
            if (row.choice() != null || row.reason() != null || row.resolutionTargetVersionId() != null
                    || row.resolutionTargetRevision() != null || row.decidedAt() != null) throw integrity();
        } else {
            KnowledgeRevalidationDtos.Choice choice;
            try {
                choice = KnowledgeRevalidationDtos.Choice.valueOf(row.choice());
                reason(row.reason());
            } catch (IllegalArgumentException | NullPointerException | ApiException exception) { throw integrity(); }
            if (!storedUuid(row.resolutionTargetVersionId()) || !safeRevision(row.resolutionTargetRevision())
                    || !validTime(row.decidedAt()) || row.decidedAt().isBefore(row.createdAt())) throw integrity();
            resolution = new KnowledgeRevalidationDtos.Resolution(choice, row.reason(), row.resolutionTargetVersionId(),
                    row.resolutionTargetRevision(), row.decidedAt());
        }
        return new Impact(row.id(), row.revision(), correction, source, row.taskId(), row.targetItemId(), row.targetTitle(),
                row.targetVersionId(), row.targetVersionNumber(), row.depth(), row.parentImpactId(), row.createdAt(), resolution);
    }

    private static void requireTarget(KnowledgeRevalidationRows.Target target, Impact impact) {
        if (!impact.targetItemId().equals(target.id()) || !safeRevision(target.revision())
                || !storedUuid(target.currentVersionId()) || target.currentVersionNumber() == null || target.currentVersionNumber() < 1
                || target.adoptedVersionNumber() == null || target.adoptedVersionNumber() != impact.targetVersionNumber()
                || target.currentVersionNumber() < target.adoptedVersionNumber() || target.publishedTitle() == null
                || target.publishedBody() == null || target.draftTitle() == null || target.draftBody() == null
                || target.adoptedBody() == null || !"ACTIVE".equals(target.status()) && !"ARCHIVED".equals(target.status())) throw integrity();
    }

    private static Before cursor(String value, String owner, Context context, String itemId, KnowledgeRevalidationDtos.State state) {
        if (value == null) return null;
        if (value.isEmpty() || value.length() > 200) throw invalid("材料影响目录游标无效。");
        String[] parts = value.split("~", -1);
        if (parts.length != 6 || !owner.equals(parts[0]) || !context.name().equals(parts[1])
                || !itemId.equals(parts[2]) || !state.name().equals(parts[3])) throw invalid("游标与账号、上下文、对象或状态不一致。");
        uuid(parts[5]);
        try {
            Instant at = Instant.parse(parts[4]);
            if (!validTime(at) || !CURSOR_TIME.format(at).equals(parts[4])) throw invalid("目录游标时间格式无效。");
            return new Before(at, parts[5]);
        } catch (DateTimeException exception) { throw invalid("目录游标时间格式无效。"); }
    }
    private static boolean before(Instant time, String id, Instant previousTime, String previousId) {
        return time.isBefore(previousTime) || time.equals(previousTime) && id.compareTo(previousId) < 0;
    }
    private void enabled() {
        if (!properties.enabled()) throw new ApiException(503, "MATERIALS_DISABLED", "服务器尚未启用附件资料。");
    }
    private record Before(Instant time, String id) {}
}
