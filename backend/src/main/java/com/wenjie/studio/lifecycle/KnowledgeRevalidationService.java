package com.wenjie.studio.lifecycle;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.List;

import static com.wenjie.studio.lifecycle.KnowledgeRevalidationDtos.*;
import static com.wenjie.studio.lifecycle.KnowledgeRevalidationRules.*;

@Service
public class KnowledgeRevalidationService {
    private final KnowledgeRevalidationMapper mapper;
    private final IdempotencyService commands;

    public KnowledgeRevalidationService(KnowledgeRevalidationMapper mapper, IdempotencyService commands) {
        this.mapper = mapper; this.commands = commands;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page list(String owner, String itemId, Direction direction, State state, String cursor, int limit) {
        uuid(owner); uuid(itemId);
        if (direction == null || state == null) throw invalid("必须指定来源方向和处理状态。");
        if (limit < 1 || limit > 20) throw invalid("每页数量须为 1 至 20。");
        Before before = cursor(cursor, owner, itemId, direction, state);
        // 目录仅核对对象身份并读取窄元数据，不进入知识详情、AI 清单或候选正文流程。
        if (!mapper.itemExists(owner, itemId)) throw missing();
        var rows = mapper.impacts(owner, itemId, direction.name(), state.name(),
                before == null ? null : before.time(), before == null ? null : before.id(), limit + 1);
        if (rows.size() > limit + 1) throw integrity();
        List<Impact> items = rows.stream().limit(limit).map(KnowledgeRevalidationService::impact).toList();
        String next = null;
        if (rows.size() > limit) {
            Impact last = items.getLast();
            next = owner + "~" + itemId + "~" + direction.name() + "~" + state.name()
                    + "~" + CURSOR_TIME.format(last.createdAt()) + "~" + last.id();
        }
        return new Page(itemId, direction, state, items, next);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Lineage lineage(String owner, String itemId, String impactId) {
        uuid(owner); uuid(itemId); uuid(impactId);
        var row = mapper.lineage(owner, itemId, impactId);
        if (row == null) throw missing();
        Impact current = impact(row);
        if (row.parentImpactId() != null) {
            // 只读真实上一跳；同事件与版本下限逐次核对，不把一条父路径当作完整依赖清单。
            var parent = mapper.lineage(owner, row.sourceItemId(), row.parentImpactId());
            if (parent == null) throw integrity();
            impact(parent); parentIdentity(row, parent);
        }
        return new Lineage(current, row.sourceItemId(), row.sourceTitle(), row.sourceVersionNumber(),
                row.depth(), row.parentImpactId());
    }

    public Impact resolve(String owner, String itemId, String impactId, String key, Resolve request) {
        uuid(owner); uuid(itemId); uuid(impactId);
        if (request == null) throw invalid("处理请求不能为空。");
        revision(request.expectedRevision()); revision(request.expectedTargetRevision());
        uuid(request.expectedTargetVersionId()); reason(request.reason());
        if (request.choice() == null || !Boolean.TRUE.equals(request.confirmed())) throw invalid("须选择处理方式并明确确认。");
        return commands.execute(owner, key, "POST /api/v1/lifecycle/knowledge/" + itemId
                + "/revalidation-impacts/" + impactId + "/resolve", request, Impact.class, () -> {
            writing();
            var row = mapper.impact(owner, itemId, impactId);
            if (row == null) throw missing();
            Impact before = impact(row);
            if (before.revision() != request.expectedRevision() || before.revision() != 0) {
                throw conflict("此次采用影响已经处理或修订不匹配，请读取当前记录。", before.revision());
            }
            var target = mapper.target(owner, itemId, before.targetVersionId());
            if (target == null) throw missing();
            requireTarget(target, before);
            if (target.revision() != request.expectedTargetRevision()
                    || !target.currentVersionId().equals(request.expectedTargetVersionId())) {
                throw targetConflict("目标知识已变化，请保留理由并核对当前发布版。", target.revision());
            }
            // 结论绑定确切发布版；未发布改写不能借用发布版的确认结果。
            if (!target.publishedTitle().equals(target.draftTitle()) || !target.publishedBody().equals(target.draftBody())) {
                throw new ApiException(409, "DRAFT_UNPUBLISHED", "目标存在未发布草稿，请先处理草稿再确认此次影响。", target.revision());
            }
            if (request.choice() == Choice.ARCHIVED) {
                if (!"ARCHIVED".equals(target.status())) throw targetConflict("目标须先完成归档，才能登记归档处理。", target.revision());
            } else {
                if (!"ACTIVE".equals(target.status())) {
                    throw new ApiException(409, "KNOWLEDGE_ARCHIVED", "目标已归档，请恢复后核对内容或登记归档处理。", target.revision());
                }
                if (request.choice() == Choice.PUBLISHED_UPDATE && (target.currentVersionNumber() <= target.adoptedVersionNumber()
                        || target.publishedBody().equals(target.adoptedBody()))) {
                    throw targetConflict("发布更新须晚于原采用版，且原始正文确已改变；标题改版不能代替正文更新。", target.revision());
                }
            }
            Instant now = now();
            Instant frozenAt = mapper.latestFrozenAt(owner);
            if (frozenAt != null) {
                time(frozenAt);
                // 处理时间须晚于训练冻结点，否则时钟回拨会把冻结时仍待处理的影响伪装成更早已解决。
                if (!now.isAfter(frozenAt)) throw new ApiException(409, "KNOWLEDGE_REVALIDATION_CLOCK_CONFLICT",
                        "服务器时间未晚于既有冻结证据或来源处理，处理未提交，请校正服务器时间后重试。");
            }
            if (now.isBefore(before.createdAt())) {
                throw new ApiException(409, "KNOWLEDGE_REVALIDATION_CLOCK_CONFLICT",
                        "服务器时间早于本次来源影响，处理未提交，请校正服务器时间后重试。");
            }
            if (mapper.resolve(owner, itemId, impactId, request.expectedRevision(), request.choice().name(),
                    request.reason(), target.currentVersionId(), target.revision(), now) != 1) {
                throw conflict("此次采用影响或目标基线已经变化，本次处理未提交。", before.revision());
            }
            Impact after = impact(mapper.impact(owner, itemId, impactId));
            Resolution decision = after.resolution();
            if (after.revision() != 1 || decision == null || decision.choice() != request.choice()
                    || !decision.reason().equals(request.reason()) || !decision.targetVersionId().equals(target.currentVersionId())
                    || decision.targetRevision() != target.revision() || !decision.decidedAt().equals(now)) throw integrity();
            // 这里只结束该采用影响；卡片仍须显式复核来源并恢复排程，旧授权与旧证据不被恢复。
            return after;
        });
    }

    private static void requireTarget(KnowledgeRevalidationRows.Target target, Impact impact) {
        if (!target.id().equals(impact.targetItemId()) || target.revision() < 0 || target.revision() > MAX
                || target.currentVersionId() == null || target.currentVersionNumber() == null || target.currentVersionNumber() < 1
                || target.adoptedVersionNumber() == null || target.adoptedVersionNumber() != impact.targetVersionNumber()
                || target.currentVersionNumber() < target.adoptedVersionNumber()
                || target.publishedTitle() == null || target.publishedBody() == null || target.draftTitle() == null
                || target.draftBody() == null || target.adoptedBody() == null
                || !"ACTIVE".equals(target.status()) && !"ARCHIVED".equals(target.status())) throw integrity();
        uuid(target.currentVersionId());
    }

    private static Impact impact(KnowledgeRevalidationRows.ImpactRow row) {
        if (row == null || row.rootTitle() == null || row.targetTitle() == null || row.targetVersionNumber() < 1
                || row.revision() < 0 || row.revision() > 1) throw integrity();
        lineageIdentity(row);
        uuid(row.id()); uuid(row.eventId()); uuid(row.rootItemId()); uuid(row.rootPreviousVersionId());
        uuid(row.rootCurrentVersionId()); uuid(row.sourceVersionId()); uuid(row.taskId());
        uuid(row.targetItemId()); uuid(row.targetVersionId()); time(row.createdAt());
        ChangeKind kind;
        try { kind = ChangeKind.valueOf(row.changeKind()); }
        catch (IllegalArgumentException | NullPointerException exception) { throw integrity(); }
        if ((kind == ChangeKind.ARCHIVED) != row.rootPreviousVersionId().equals(row.rootCurrentVersionId())) throw integrity();
        Resolution resolution = null;
        if (row.revision() == 0) {
            if (row.choice() != null || row.reason() != null || row.resolutionTargetVersionId() != null
                    || row.resolutionTargetRevision() != null || row.decidedAt() != null) throw integrity();
        } else {
            Choice choice;
            try { choice = Choice.valueOf(row.choice()); }
            catch (IllegalArgumentException | NullPointerException exception) { throw integrity(); }
            reason(row.reason()); uuid(row.resolutionTargetVersionId()); revision(row.resolutionTargetRevision()); time(row.decidedAt());
            if (row.decidedAt().isBefore(row.createdAt())) throw integrity();
            resolution = new Resolution(choice, row.reason(), row.resolutionTargetVersionId(), row.resolutionTargetRevision(), row.decidedAt());
        }
        return new Impact(row.id(), row.revision(), row.eventId(), row.rootItemId(), row.rootTitle(),
                row.rootPreviousVersionId(), row.rootCurrentVersionId(), kind, row.sourceVersionId(), row.taskId(),
                row.targetItemId(), row.targetTitle(), row.targetVersionId(), row.targetVersionNumber(), row.createdAt(), resolution);
    }

    private static Before cursor(String value, String owner, String itemId, Direction direction, State state) {
        if (value == null) return null;
        if (value.isEmpty() || value.length() > 200) throw invalid("来源复核目录游标无效。");
        String[] parts = value.split("~", -1);
        if (parts.length != 6 || !owner.equals(parts[0]) || !itemId.equals(parts[1])
                || !direction.name().equals(parts[2]) || !state.name().equals(parts[3])) {
            throw invalid("游标与当前账号、知识、方向或状态不一致。");
        }
        uuid(parts[5]);
        try {
            Instant at = Instant.parse(parts[4]); time(at);
            if (!CURSOR_TIME.format(at).equals(parts[4])) throw invalid("目录游标时间格式无效。");
            return new Before(at, parts[5]);
        } catch (DateTimeException exception) { throw invalid("目录游标时间格式无效。"); }
    }

    private static ApiException conflict(String message, long revision) {
        return new ApiException(409, "KNOWLEDGE_REVALIDATION_CONFLICT", message, revision);
    }
    private static ApiException targetConflict(String message, long revision) {
        return new ApiException(409, "KNOWLEDGE_REVALIDATION_TARGET_CONFLICT", message, revision);
    }
    private record Before(Instant time, String id) {}
}
