package com.wenjie.studio.prepare;

import com.wenjie.studio.common.IdempotencyService;
import com.wenjie.studio.lifecycle.PreparationKnowledgePropagation;
import com.wenjie.studio.lifecycle.PreparationKnowledgeDtos.ChangeKind;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.wenjie.studio.prepare.ExpressionBasisReviewDtos.Record;
import static com.wenjie.studio.prepare.ExpressionBasisReviewDtos.*;
import static com.wenjie.studio.prepare.ExpressionBasisReviewRows.*;
import static com.wenjie.studio.prepare.ExpressionBasisReviewRules.*;

@Service
public class ExpressionBasisReviewService {
    private final ExpressionBasisReviewMapper mapper;
    private final PreparationMapper preparation;
    private final PreparationSources sources;
    private final IdempotencyService commands;
    private final PreparationKnowledgePropagation knowledgePropagation;

    public ExpressionBasisReviewService(ExpressionBasisReviewMapper mapper, PreparationMapper preparation,
                                        PreparationSources sources, IdempotencyService commands,
                                        PreparationKnowledgePropagation knowledgePropagation) {
        this.mapper = mapper; this.preparation = preparation; this.sources = sources; this.commands = commands;
        this.knowledgePropagation = knowledgePropagation;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public State state(String owner, String itemId) {
        var item = expression(owner, itemId);
        long revision = revision(owner, itemId);
        var latestRow = mapper.latest(owner, itemId, item.currentVersionId());
        Record latest = latestRow == null ? null : record(owner, itemId, latestRow);
        if (latest != null && latest.revision() > revision) throw integrity();
        var basis = sources.expressionBasis(owner, itemId, item.currentVersionId());
        boolean available = basis.stream().allMatch(value -> current(value.check()));
        boolean required = latest == null || !available || !latest.basis().equals(basis)
                || preparation.hasInvalidFactBasis(owner, item.currentVersionId());
        boolean canConfirm = "ACTIVE".equals(item.status()) && available && item.revision() < MAX && revision < MAX;
        return new State(itemId, item.currentVersionId(), item.versionNumber(), item.revision(),
                PreparationDtos.Status.valueOf(item.status()), revision, latest, basis, required, canConfirm);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page history(String owner, String itemId, Long before, int limit) {
        expression(owner, itemId);
        if (limit < 1 || limit > 20 || before != null && (before < 1 || before > MAX)) throw invalid("复核历史分页参数无效。");
        var rows = mapper.history(owner, itemId, before, limit + 1);
        if (rows.size() > limit + 1) throw integrity();
        var records = new ArrayList<Record>();
        Long previous = before;
        for (var row : rows) {
            if (previous != null && row.revision() >= previous) throw integrity();
            records.add(record(owner, itemId, row));
            previous = row.revision();
        }
        boolean more = records.size() > limit;
        var items = List.copyOf(records.subList(0, Math.min(records.size(), limit)));
        return new Page(itemId, items, more ? items.getLast().revision() : null);
    }

    public Record confirm(String owner, String itemId, String key, Confirm request) {
        uuid(owner); uuid(itemId);
        if (request == null) throw invalid("复核请求不能为空。");
        ExpressionBasisReviewRules.revision(request.expectedItemRevision());
        ExpressionBasisReviewRules.revision(request.expectedRevision());
        uuid(request.expectedVersionId()); checks(request.basisChecks()); reason(request.reason());
        if (!Boolean.TRUE.equals(request.confirmed())) throw invalid("必须明确确认已逐项核对本次依据。");
        // 原请求用于幂等摘要；回放发生在 action 前，来源后来失效不重算或改写原成功记录。
        return commands.execute(owner, key, "POST /api/v1/prepare/items/" + itemId + "/expression-basis-review/confirm",
                request, Record.class, () -> {
            writing();
            var item = expression(owner, itemId);
            long revision = revision(owner, itemId);
            if (item.revision() != request.expectedItemRevision() || revision != request.expectedRevision()
                    || !item.currentVersionId().equals(request.expectedVersionId())) {
                throw conflict("表达、正文版本或复核序号已变化，请保留理由并重新核对。", item.revision());
            }
            if (item.revision() == MAX || revision == MAX) throw limit();
            if (!"ACTIVE".equals(item.status())) throw conflict("表达已归档，请恢复后重新核对依据。", item.revision());
            var basis = sources.expressionBasis(owner, itemId, item.currentVersionId());
            if (basis.stream().anyMatch(value -> !current(value.check()))
                    || !basis.stream().map(Basis::check).toList().equals(request.basisChecks())) {
                throw conflict("事实或项目状态、确切版本及代次已变化，请重新逐项核对。", item.revision());
            }
            var sourceBefore = knowledgePropagation.observe(owner, itemId);
            Instant now = now();
            time(item.updatedAt());
            if (now.isBefore(item.updatedAt())) throw conflict("服务器时间早于表达当前修订，本次复核未提交。", item.revision());
            // 只推进元数据和来源代次；正文版本保持，旧 AI 清单及许可不能由本次确认恢复。
            if (mapper.confirmItem(owner, itemId, item.currentVersionId(), item.revision(), now) != 1) {
                throw conflict("表达基线已变化，本次复核未提交。", item.revision());
            }
            var record = append(owner, itemId, item.currentVersionId(), item.versionNumber(), item.revision() + 1,
                    revision + 1, Kind.CONFIRMED_BASIS, reason(request.reason()), basis, now);
            knowledgePropagation.changed(owner, sourceBefore, ChangeKind.EXPRESSION_BASIS_CONFIRMED, now);
            return record;
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void capture(String owner, String itemId, String versionId, Instant now) {
        writing(); uuid(versionId); time(now);
        var item = expression(owner, itemId);
        if (!versionId.equals(item.currentVersionId()) || !"ACTIVE".equals(item.status())
                || mapper.latest(owner, itemId, versionId) != null) throw integrity();
        long revision = revision(owner, itemId);
        if (revision == MAX) throw limit();
        var basis = sources.expressionBasis(owner, itemId, versionId);
        if (basis.stream().anyMatch(value -> !current(value.check()))) {
            throw conflict("保存时的事实依据已变化，请重新选择当前已确认事实。", item.revision());
        }
        // 必须在正文及 saveContent 后捕获，itemRevision 才是本次新版本实际提交的修订。
        append(owner, itemId, versionId, item.versionNumber(), item.revision(), revision + 1, Kind.SAVED_BASIS, null, basis, now);
    }

    private Record append(String owner, String itemId, String versionId, int number, long itemRevision,
                          long revision, Kind kind, String reason, List<Basis> basis, Instant now) {
        if (basis.isEmpty() || basis.size() > 50) throw integrity();
        var row = new ReviewRow(UUID.randomUUID().toString(), itemId, versionId, number, revision,
                kind.name(), reason, itemRevision, now);
        inserted(mapper.insertReview(owner, row));
        for (int index = 0; index < basis.size(); index++) {
            var value = basis.get(index);
            if (!current(value.check()) || !value.itemId().equals(value.check().itemId())
                    || !value.versionId().equals(value.check().versionId())) throw integrity();
            inserted(mapper.insertBasis(owner, row, new BasisRow(index + 1, value.itemId(), value.versionId(), value.title(),
                    value.versionNumber(), value.projectId(), value.check().sourceGeneration(), value.check().projectGeneration())));
        }
        if (preparation.hasInvalidFactBasis(owner, versionId)) throw integrity();
        return record(owner, itemId, row);
    }

    private Record record(String owner, String itemId, ReviewRow row) {
        uuid(row.id()); uuid(row.itemId()); uuid(row.versionId()); time(row.createdAt());
        if (!itemId.equals(row.itemId()) || row.versionNumber() < 1 || row.revision() < 1 || row.revision() > MAX
                || row.itemRevision() < 0 || row.itemRevision() > MAX) throw integrity();
        Kind kind;
        try { kind = Kind.valueOf(row.kind()); }
        catch (IllegalArgumentException | NullPointerException exception) { throw integrity(); }
        if (kind == Kind.SAVED_BASIS ? row.reason() != null : !reason(row.reason()).equals(row.reason())) throw integrity();
        var rows = mapper.basis(owner, row.id());
        if (rows.isEmpty() || rows.size() > 50) throw integrity();
        var basis = new ArrayList<Basis>();
        var versions = new java.util.HashSet<String>();
        for (var value : rows) {
            uuid(value.itemId()); uuid(value.versionId()); uuid(value.projectId());
            if (value.ordinal() != basis.size() + 1 || value.title() == null || value.versionNumber() < 1
                    || !safe(value.sourceGeneration()) || !safe(value.projectGeneration()) || !versions.add(value.versionId())) throw integrity();
            var check = new PreparationDtos.SourceCheck(PreparationDtos.Kind.FACT, value.itemId(), value.versionId(),
                    PreparationDtos.SourceState.CURRENT, value.sourceGeneration(), value.projectGeneration());
            basis.add(new Basis(value.itemId(), value.versionId(), value.title(), value.versionNumber(), value.projectId(), check));
        }
        return new Record(row.id(), itemId, row.versionId(), row.versionNumber(), row.revision(), kind,
                row.reason(), row.itemRevision(), row.createdAt(), List.copyOf(basis));
    }

    private PreparationRows.ItemRow expression(String owner, String itemId) {
        uuid(owner); uuid(itemId);
        var item = preparation.item(owner, itemId);
        if (item == null || !"EXPRESSION".equals(item.kind())) throw missing();
        if (!owner.equals(item.ownerId()) || !itemId.equals(item.id()) || item.revision() < 0 || item.revision() > MAX
                || item.versionNumber() < 1 || !"ACTIVE".equals(item.status()) && !"ARCHIVED".equals(item.status())) throw integrity();
        uuid(item.currentVersionId());
        var version = preparation.version(owner, itemId, item.currentVersionId());
        if (version == null || !owner.equals(version.ownerId()) || !itemId.equals(version.itemId())
                || !item.currentVersionId().equals(version.id()) || version.versionNumber() != item.versionNumber()) throw integrity();
        return item;
    }

    private long revision(String owner, String itemId) {
        long value = mapper.revision(owner, itemId);
        if (value < 0 || value > MAX) throw integrity();
        return value;
    }
}
