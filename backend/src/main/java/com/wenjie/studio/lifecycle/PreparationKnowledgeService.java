package com.wenjie.studio.lifecycle;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import com.wenjie.studio.prepare.PreparationDtos;
import com.wenjie.studio.prepare.PreparationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.List;
import static com.wenjie.studio.lifecycle.KnowledgeRevalidationDtos.*;
import static com.wenjie.studio.lifecycle.KnowledgeRevalidationRules.*;
import static com.wenjie.studio.lifecycle.PreparationKnowledgeDtos.*;
import static com.wenjie.studio.lifecycle.PreparationKnowledgeRows.*;
import static com.wenjie.studio.lifecycle.PreparationKnowledgeRules.*;

@Service
public class PreparationKnowledgeService {
    private final PreparationKnowledgeMapper mapper;
    private final KnowledgeRevalidationMapper knowledge;
    private final PreparationMapper preparation;
    private final IdempotencyService commands;

    public PreparationKnowledgeService(PreparationKnowledgeMapper mapper, KnowledgeRevalidationMapper knowledge,
                                       PreparationMapper preparation, IdempotencyService commands) {
        this.mapper = mapper; this.knowledge = knowledge; this.preparation = preparation; this.commands = commands;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PreparationKnowledgeDtos.Page list(String owner, String itemId, Context context, State state, String cursor, int limit) {
        uuid(owner); uuid(itemId);
        if (context == null || state == null || limit < 1 || limit > 20) throw invalidRequest("目录状态或每页数量无效。");
        Before before = cursor(cursor, owner, itemId, context, state);
        if (!mapper.itemExists(owner, itemId, context.name())) throw notFound();
        var rows = mapper.impacts(owner, itemId, context.name(), state.name(),
                before == null ? null : before.time(), before == null ? null : before.id(), limit + 1);
        if (rows.size() > limit + 1) throw integrity();
        List<PreparationKnowledgeDtos.Impact> items = rows.stream().limit(limit).map(row -> impact(owner, row)).toList();
        String next = null;
        if (rows.size() > limit) {
            var last = items.getLast();
            next = owner + "~" + context.name() + "~" + itemId + "~" + state.name()
                    + "~" + CURSOR_TIME.format(last.createdAt()) + "~" + last.id();
        }
        return new PreparationKnowledgeDtos.Page(context, itemId, state, items, next);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PreparationKnowledgeDtos.Impact detail(String owner, String impactId) {
        uuid(owner); uuid(impactId);
        var row = mapper.impact(owner, impactId);
        if (row == null) throw notFound();
        return impact(owner, row);
    }

    public PreparationKnowledgeDtos.Impact resolve(String owner, String itemId, String impactId, String key, Resolve request) {
        uuid(owner); uuid(itemId); uuid(impactId);
        if (request == null) throw invalidRequest("处理请求不能为空。");
        revision(request.expectedRevision()); revision(request.expectedTargetRevision()); uuid(request.expectedTargetVersionId());
        reason(request.reason());
        if (request.choice() == null || !Boolean.TRUE.equals(request.confirmed())) throw invalidRequest("须选择处理方式并明确确认。");
        return commands.execute(owner, key, "POST /api/v1/lifecycle/knowledge/" + itemId
                + "/preparation-impacts/" + impactId + "/resolve", request, PreparationKnowledgeDtos.Impact.class, () -> {
            writing();
            var row = mapper.impact(owner, impactId);
            if (row == null || !row.targetItemId().equals(itemId)) throw notFound();
            var before = impact(owner, row);
            if (before.revision() != request.expectedRevision() || before.revision() != 0) {
                throw conflict("此次采用影响已经处理或修订不匹配，请读取当前记录。", before.revision());
            }
            var target = knowledge.target(owner, itemId, before.targetVersionId());
            requireTarget(target, before);
            if (target.revision() != request.expectedTargetRevision()
                    || !target.currentVersionId().equals(request.expectedTargetVersionId())) {
                throw conflict("目标知识已变化，请保留理由并重新核对当前发布版。", target.revision());
            }
            if (!target.publishedTitle().equals(target.draftTitle()) || !target.publishedBody().equals(target.draftBody())) {
                throw new ApiException(409, "DRAFT_UNPUBLISHED", "目标存在未发布草稿，请先处理草稿再确认此次影响。", target.revision());
            }
            if (request.choice() == Choice.ARCHIVED) {
                if (!"ARCHIVED".equals(target.status())) throw conflict("目标须先完成归档，才能登记归档处理。", target.revision());
            } else {
                if (!"ACTIVE".equals(target.status())) {
                    throw new ApiException(409, "KNOWLEDGE_ARCHIVED", "目标已归档，请恢复后核对内容或登记归档处理。", target.revision());
                }
                if (request.choice() == Choice.PUBLISHED_UPDATE && (target.currentVersionNumber() <= target.adoptedVersionNumber()
                        || target.publishedBody().equals(target.adoptedBody()))) {
                    throw conflict("发布更新须晚于原采用版，且原始正文确已改变；标题改版不能代替正文更新。", target.revision());
                }
            }
            Instant now = now();
            if (now.isBefore(before.createdAt())) throw new ApiException(409, "PREPARATION_KNOWLEDGE_CLOCK_CONFLICT",
                    "服务器时间早于本次来源影响，处理未提交，请校正服务器时间后重试。");
            inserted(mapper.resolve(owner, itemId, impactId, request.expectedRevision(), request.choice().name(),
                    request.reason(), target.currentVersionId(), target.revision(), now));
            var after = impact(owner, mapper.impact(owner, impactId));
            var decision = after.resolution();
            if (after.revision() != 1 || decision == null || decision.choice() != request.choice()
                    || !decision.reason().equals(request.reason()) || !decision.targetVersionId().equals(target.currentVersionId())
                    || decision.targetRevision() != target.revision() || !decision.decidedAt().equals(now)) throw integrity();
            return after;
        });
    }

    private PreparationKnowledgeDtos.Impact impact(String owner, ImpactRow row) {
        if (row == null || row.revision() < 0 || row.revision() > 1 || row.targetTitle() == null
                || row.targetVersionNumber() < 1) throw integrity();
        var event = mapper.event(owner, row.eventId());
        Root root = root(event);
        if (!row.createdAt().equals(event.createdAt())) throw integrity();
        Source source = source(row, root);
        if (source.domain() == Domain.PREPARATION) verifyBridge(owner, root, source);
        if (row.parentImpactId() != null) {
            var parent = mapper.impact(owner, row.parentImpactId());
            if (parent == null) throw integrity();
            source(parent, root); parent(row, parent);
        }
        Resolution resolution = null;
        if (row.revision() == 0) {
            if (row.choice() != null || row.reason() != null || row.resolutionTargetVersionId() != null
                    || row.resolutionTargetRevision() != null || row.decidedAt() != null) throw integrity();
        } else {
            var choice = enumeration(Choice.class, row.choice());
            reason(row.reason()); uuid(row.resolutionTargetVersionId()); revision(row.resolutionTargetRevision()); time(row.decidedAt());
            if (row.decidedAt().isBefore(row.createdAt())) throw integrity();
            resolution = new Resolution(choice, row.reason(), row.resolutionTargetVersionId(), row.resolutionTargetRevision(), row.decidedAt());
        }
        return new PreparationKnowledgeDtos.Impact(row.id(), row.revision(), row.eventId(), root, source, row.taskId(),
                row.targetItemId(), row.targetTitle(), row.targetVersionId(), row.targetVersionNumber(),
                row.depth(), row.parentImpactId(), row.createdAt(), resolution);
    }

    private void verifyBridge(String owner, Root root, Source source) {
        if (source.dependencyKind() == DependencyKind.PROJECT_FACT) {
            var fact = preparation.item(owner, source.itemId());
            if (fact == null || !"FACT".equals(fact.kind()) || !root.itemId().equals(fact.projectId())) throw integrity();
        }
        if (source.viaFact() != null) {
            var fact = preparation.item(owner, source.viaFact().itemId());
            if (fact == null || !"FACT".equals(fact.kind())
                    || source.dependencyKind() == DependencyKind.PROJECT_EXPRESSION && !root.itemId().equals(fact.projectId())) throw integrity();
            var fixed = preparation.expressionBasis(owner, source.itemId(), source.versionId());
            if (fixed.stream().noneMatch(value -> "FACT".equals(value.sourceKind()) && "FACT_BASIS".equals(value.useType())
                    && source.viaFact().itemId().equals(value.sourceItemId())
                    && source.viaFact().versionId().equals(value.sourceVersionId()))) throw integrity();
        }
    }
    private static void requireTarget(KnowledgeRevalidationRows.Target target, PreparationKnowledgeDtos.Impact impact) {
        if (target == null) throw notFound();
        if (!target.id().equals(impact.targetItemId()) || target.revision() < 0 || target.revision() > MAX
                || target.currentVersionId() == null || target.currentVersionNumber() == null || target.currentVersionNumber() < 1
                || target.adoptedVersionNumber() == null || target.adoptedVersionNumber() != impact.targetVersionNumber()
                || target.currentVersionNumber() < target.adoptedVersionNumber()
                || target.publishedTitle() == null || target.publishedBody() == null || target.draftTitle() == null
                || target.draftBody() == null || target.adoptedBody() == null
                || !"ACTIVE".equals(target.status()) && !"ARCHIVED".equals(target.status())) throw integrity();
        uuid(target.currentVersionId());
    }
    private static Before cursor(String value, String owner, String itemId, Context context, State state) {
        if (value == null) return null;
        if (value.isEmpty() || value.length() > 200) throw invalidRequest("来源影响目录游标无效。");
        String[] parts = value.split("~", -1);
        if (parts.length != 6 || !owner.equals(parts[0]) || !context.name().equals(parts[1]) || !itemId.equals(parts[2])
                || !state.name().equals(parts[3])) throw invalidRequest("游标与当前账号、对象、目录或状态不一致。");
        uuid(parts[5]);
        try {
            Instant time = Instant.parse(parts[4]); time(time);
            if (!CURSOR_TIME.format(time).equals(parts[4])) throw invalidRequest("目录游标时间格式无效。");
            return new Before(time, parts[5]);
        } catch (DateTimeException exception) { throw invalidRequest("目录游标时间格式无效。"); }
    }
    private record Before(Instant time, String id) {}
}
