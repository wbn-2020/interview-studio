package com.wenjie.studio.review;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.wenjie.studio.review.StandardReviewDtos.*;
import static com.wenjie.studio.review.StandardReviewRules.*;
import static com.wenjie.studio.review.StandardReviewStoreRows.*;

@Service
public class StandardReviewService {
    private static final String PATH = "/api/v1/bank/standard-reviews";
    private final StandardReviewStoreMapper store;
    private final StandardReviewSources sources;
    private final StandardReviewProjectionSeal projectionSeal;
    private final IdempotencyService commands;
    private final JsonMapper json;

    public StandardReviewService(StandardReviewStoreMapper store, StandardReviewSources sources, StandardReviewProjectionSeal projectionSeal,
                                 IdempotencyService commands, JsonMapper json) {
        this.store = store;
        this.sources = sources;
        this.projectionSeal = projectionSeal;
        this.commands = commands;
        this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StandardReviewPage list(String owner, QuestionVersionRef question, RecordStatus status, String cursor, int limit) {
        uuid(owner); question(question); page(limit);
        if (cursor != null) uuid(cursor);
        sources.requireQuestion(owner, question);
        String state = status == null ? null : status.name();
        List<ReviewRow> rows = store.reviews(owner, question.questionId(), question.versionId(), state, cursor, limit);
        var items = new ArrayList<StandardReviewDetail>();
        String previous = cursor;
        for (ReviewRow row : rows) {
            validateRow(owner, row.id(), row);
            if (!question.equals(rowQuestion(row)) || status != null && !status.name().equals(row.status())
                    || previous != null && row.id().compareTo(previous) <= 0) throw integrity();
            StandardReviewSnapshot snapshot = current(owner, row).snapshot();
            items.add(detail(owner, row, snapshot));
            previous = row.id();
        }
        // SQL 每次最多读取 50 行；满页只探测下一行，不扫描或补齐所谓有效记录页。
        boolean more = rows.size() == limit && !store.reviews(owner, question.questionId(), question.versionId(),
                state, rows.getLast().id(), 1).isEmpty();
        return readResponse(json, new StandardReviewPage(List.copyOf(items), more ? rows.getLast().id() : null));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StandardReviewDetail get(String owner, String id, Long revision) {
        if (revision != null) revision(revision);
        ReviewRow row = requireRow(owner, id);
        StandardReviewHistory current = current(owner, row);
        StandardReviewSnapshot snapshot = revision == null || revision == row.revision()
                ? current.snapshot() : readRevision(owner, row, revision).snapshot();
        return readResponse(json, detail(owner, row, snapshot));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StandardReviewPreview preview(String owner, StandardReviewDraft draft) {
        uuid(owner); bounded(json, required(draft), REQUEST_BYTES);
        Instant now = now();
        draft(draft, now);
        Existing existing = existing(owner, draft, now);
        Prepared prepared = prepare(owner, draft, existing, now);
        if (existing != null) requireRereview(owner, existing, draft.content());
        return readResponse(json, prepared.preview());
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, timeout = 30)
    public StandardReviewSnapshot create(String owner, String key, SaveStandardReview request) {
        return commands.execute(owner, key, "POST " + PATH, request, StandardReviewSnapshot.class, () -> {
            Instant now = now();
            validateSave(request, now);
            if (request.draft().id() != null) throw invalid("新建审阅不能提供对象标识或预期修订。");
            Prepared prepared = prepare(owner, request.draft(), null, now);
            matchPreview(request.expectedPreviewHash(), prepared.preview().previewHash());
            sealQuestion(owner, request.draft().question(), prepared.inspection().basis().directSources(), now);
            String id = UUID.randomUUID().toString();
            StandardReviewSnapshot snapshot = new StandardReviewSnapshot(id, request.draft().question(), 0,
                    RecordStatus.ACTIVE, request.draft().content(), prepared.inspection().basis(), now, now);
            StandardReviewHistory history = checkedHistory(RecordChange.CREATED, snapshot, null);
            requireInserted(store.insertReview(row(owner, snapshot)));
            append(owner, history);
            return snapshot;
        });
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, timeout = 30)
    public StandardReviewSnapshot save(String owner, String id, String key, SaveStandardReview request) {
        uuid(id);
        return commands.execute(owner, key, "PUT " + PATH + "/" + id, request, StandardReviewSnapshot.class, () -> {
            Instant now = now();
            validateSave(request, now);
            if (!id.equals(request.draft().id())) throw invalid("路径与审阅草稿标识不一致。");
            Existing existing = existing(owner, request.draft(), now);
            Prepared prepared = prepare(owner, request.draft(), existing, now);
            matchPreview(request.expectedPreviewHash(), prepared.preview().previewHash());
            requireRereview(owner, existing, request.draft().content());
            sealQuestion(owner, request.draft().question(), prepared.inspection().basis().directSources(), now);
            StandardReviewSnapshot before = existing.history().snapshot();
            // 标题发布只改变知识展示版本；相同内容与语义基线不改写旧快照，也不消耗修订。
            if (before.content().equals(request.draft().content()) && sameBasis(before.basis(), prepared.inspection().basis())) return before;
            StandardReviewSnapshot snapshot = new StandardReviewSnapshot(id, before.question(), next(before.revision()),
                    RecordStatus.ACTIVE, request.draft().content(), prepared.inspection().basis(), before.createdAt(), now);
            StandardReviewHistory history = checkedHistory(RecordChange.REVISED, snapshot, request.draft().changeReason());
            append(owner, history);
            changed(store.updateReview(row(owner, snapshot), before.revision()), before.revision());
            return snapshot;
        });
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, timeout = 30)
    public StandardRevisionSummary withdraw(String owner, String id, String key, WithdrawRecord request) {
        uuid(id);
        return commands.execute(owner, key, "POST " + PATH + "/" + id + "/withdraw", request, StandardRevisionSummary.class, () -> {
            bounded(json, StandardReviewRules.withdraw(request), REQUEST_BYTES);
            ReviewRow row = requireRow(owner, id);
            StandardReviewHistory before = current(owner, row);
            expect(row.revision(), request.expectedRevision());
            if (before.snapshot().status() == RecordStatus.WITHDRAWN) {
                if (Objects.equals(before.changeReason(), request.reason())) return summary(before);
                throw withdrawn();
            }
            Instant now = now();
            clock(now, row.updatedAt());
            StandardReviewSnapshot snapshot = before.snapshot();
            StandardReviewSnapshot after = new StandardReviewSnapshot(id, snapshot.question(), next(snapshot.revision()),
                    RecordStatus.WITHDRAWN, snapshot.content(), snapshot.basis(), snapshot.createdAt(), now);
            StandardReviewHistory history = checkedHistory(RecordChange.WITHDRAWN, after, request.reason());
            // 撤回只复制已核历史；来源当前不可用不能阻挡终态记录，也不借响应泄露引文。
            append(owner, history);
            changed(store.updateReview(row(owner, after), row.revision()), row.revision());
            return summary(history);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StandardHistoryPage history(String owner, String id, Long before, int limit) {
        page(limit); if (before != null) revision(before);
        ReviewRow row = requireRow(owner, id);
        current(owner, row);
        List<RevisionRow> rows = store.history(owner, id, before, limit);
        var items = new ArrayList<StandardRevisionSummary>();
        Long previous = before;
        for (RevisionRow revision : rows) {
            if (previous != null && revision.revision() >= previous) throw integrity();
            items.add(summary(read(owner, row, revision)));
            previous = revision.revision();
        }
        boolean more = rows.size() == limit && !store.history(owner, id, rows.getLast().revision(), 1).isEmpty();
        return readResponse(json, new StandardHistoryPage(List.copyOf(items), more ? rows.getLast().revision() : null));
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, timeout = 30)
    public OpenedStandardReview open(String owner, String id, String key, OpenStandardReview request) {
        uuid(id);
        return commands.execute(owner, key, "POST " + PATH + "/" + id + "/open", request, OpenedStandardReview.class, () -> {
            bounded(json, StandardReviewRules.open(request), REQUEST_BYTES);
            ReviewRow row = requireRow(owner, id);
            StandardReviewHistory current = current(owner, row);
            StandardReviewHistory history = request.revision() == row.revision()
                    ? current : readRevision(owner, row, request.revision());
            Instant now = now();
            clock(now, row.updatedAt());
            StandardEligibility eligibility = sources.eligibility(owner, history.snapshot(), row.revision(), status(row.status()));
            var actualQuestion = eligibility.basisChecks().stream()
                    .filter(check -> check.source().kind() == DirectKind.QUESTION).map(BasisCheck::current)
                    .filter(Objects::nonNull).findFirst();
            if (actualQuestion.isPresent()) projectionSeal.sealQuestion(owner, history.snapshot().question(), actualQuestion.get(), now);
            String exposureId = UUID.randomUUID().toString();
            // 审阅正文可能包含参考信息，必须与确切题目版本的 REFERENCE_VIEW 和回执一起提交。
            sources.recordReferenceExposure(owner, history.snapshot().question(), exposureId, now);
            return new OpenedStandardReview(history, eligibility, exposureId, now);
        });
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true, noRollbackFor = RuntimeException.class)
    public ObservationBasis observationBasis(String owner, RevisionRef ref) {
        ref(ref);
        ReviewRow row = requireRow(owner, ref.id());
        StandardReviewHistory current = current(owner, row);
        StandardReviewSnapshot snapshot = ref.revision() == row.revision()
                ? current.snapshot() : readRevision(owner, row, ref.revision()).snapshot();
        // 此内部入口只校验观察依据；HTTP 标准正文仍必须走带曝光的 open 命令。
        return new ObservationBasis(snapshot, sources.eligibility(owner, snapshot, row.revision(), status(row.status())));
    }

    public record ObservationBasis(StandardReviewSnapshot snapshot, StandardEligibility eligibility) {}

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true, noRollbackFor = RuntimeException.class)
    public ProjectionBasis projectionBasis(String owner, RevisionRef ref) {
        ref(ref);
        ReviewRow row = requireRow(owner, ref.id());
        StandardReviewHistory current = current(owner, row);
        StandardReviewSnapshot snapshot = ref.revision() == row.revision()
                ? current.snapshot() : readRevision(owner, row, ref.revision()).snapshot();
        // 成长只取已核身份的确切历史；直接来源另走窄投影，不能回调加载正文的资格入口。
        return new ProjectionBasis(snapshot, row.revision(), status(row.status()));
    }

    public record ProjectionBasis(StandardReviewSnapshot snapshot, long currentRevision, RecordStatus currentStatus) {}

    private void sealQuestion(String owner, QuestionVersionRef question, List<DirectStamp> verifiedSources, Instant now) {
        // 指纹必须来自本事务已经读取并核验的实际题目，不能拿历史基线代替当前完整性证明。
        DirectStamp actual = verifiedSources.stream().filter(source -> source.source().kind() == DirectKind.QUESTION)
                .findFirst().orElseThrow(StandardReviewRules::integrity);
        projectionSeal.sealQuestion(owner, question, actual, now);
    }

    private void validateSave(SaveStandardReview request, Instant now) {
        bounded(json, required(request), REQUEST_BYTES);
        hashText(request.expectedPreviewHash());
        draft(request.draft(), now);
    }

    private Existing existing(String owner, StandardReviewDraft draft, Instant now) {
        if (draft.id() == null) return null;
        ReviewRow row = requireRow(owner, draft.id());
        StandardReviewHistory history = current(owner, row);
        expect(row.revision(), draft.expectedRevision());
        if (history.snapshot().status() == RecordStatus.WITHDRAWN) throw withdrawn();
        if (!history.snapshot().question().equals(draft.question())) throw invalid("审阅创建后不能改变确切题目或版本。");
        clock(now, row.updatedAt());
        return new Existing(row, history);
    }

    private Prepared prepare(String owner, StandardReviewDraft draft, Existing existing, Instant now) {
        StandardReviewSources.Inspection inspected = sources.inspect(owner, draft, now);
        PreviewFacts facts = new PreviewFacts("standard-review", draft, existing == null ? null : existing.history().snapshot(),
                inspected.questionFacts(), inspected.evidenceFacts(), inspected.basis(), inspected.checkpoints(),
                inspected.validationDates(), inspected.limitations());
        String hash = hash("standard-review-preview-v1\n", facts);
        StandardReviewPreview preview = new StandardReviewPreview(draft, inspected.basis(), inspected.evidenceChecks(),
                inspected.checkpoints(), hash, inspected.limitations());
        return new Prepared(inspected, preview);
    }

    private void requireRereview(String owner, Existing existing, StandardReviewContent content) {
        StandardReviewSnapshot before = existing.history().snapshot();
        var snapshots = new HashMap<Long, StandardReviewSnapshot>();
        var eligibilities = new HashMap<Long, StandardEligibility>();
        snapshots.put(before.revision(), before);
        for (StandardCheckpointReview checkpoint : content.checkpoints()) {
            if (checkpoint.decision() != CheckpointDecision.SUPPORTED_WITHIN_SCOPE) continue;
            StandardReviewSnapshot basis = before;
            StandardCheckpointReview prior = checkpoint(basis.content(), checkpoint.checkpointId());
            if (prior == null) {
                // 移除再加回同一检查项不能清除来源更正史，须追溯该项最近出现的不可变修订。
                Long last = store.lastCheckpointRevision(owner, before.id(), checkpoint.checkpointId(), before.revision());
                if (last == null) continue;
                revision(last);
                if (last >= before.revision()) throw integrity();
                basis = snapshots.computeIfAbsent(last, value -> readRevision(owner, existing.row(), value).snapshot());
                prior = checkpoint(basis.content(), checkpoint.checkpointId());
                if (prior == null) throw integrity();
            }
            StandardReviewSnapshot selected = basis;
            StandardEligibility eligibility = eligibilities.computeIfAbsent(basis.revision(), value ->
                    sources.eligibility(owner, selected, existing.row().revision(), status(existing.row().status())));
            CheckpointEligibility check = eligibility.checkpoints().stream()
                    .filter(item -> item.checkpointId().equals(checkpoint.checkpointId())).findFirst().orElseThrow(StandardReviewRules::integrity);
            boolean changed = check.reasons().contains(EligibilityReason.DIRECT_BASIS_CHANGED)
                    || check.reasons().contains(EligibilityReason.BASIS_UNAVAILABLE)
                    || check.reasons().contains(EligibilityReason.QUESTION_ARCHIVED);
            if (!changed && prior.decision() == CheckpointDecision.SUPPORTED_WITHIN_SCOPE) continue;
            Set<String> oldRecords = locations(reviewRecords(basis.content(), prior));
            Set<String> newRecords = locations(reviewRecords(content, checkpoint));
            Set<String> oldEvidence = locations(checkpointEvidence(basis.content(), prior));
            boolean newExplanation = !basis.content().reviewContext().equals(content.reviewContext())
                    || !reviewNotes(basis.content(), prior).containsAll(reviewNotes(content, checkpoint));
            // 新审阅须有该项此前任何角色都没有的实际定位，换角色或只删除旧记录不能冒充重核。
            if (oldRecords.equals(newRecords) || oldEvidence.containsAll(newRecords)
                    || !newExplanation || content.reviewedOn().isBefore(basis.content().reviewedOn())) {
                throw conflict("REVIEW_REREVIEW_EVIDENCE_REQUIRED", "该检查项需要新的实际审阅记录和说明，不能仅确认、改日期或恢复原支持状态。", null);
            }
        }
    }

    private static Set<String> locations(List<ReviewEvidence> evidenceList) {
        var result = new HashSet<String>();
        for (ReviewEvidence evidence : evidenceList) {
            Object location = switch (evidence.kind()) {
                case KNOWLEDGE -> List.of(evidence.kind(), evidence.knowledge().itemId(), evidence.knowledge().versionId(),
                        evidence.knowledge().start(), evidence.knowledge().end(), evidence.knowledge().quote());
                case MATERIAL_EXCERPT -> List.of(evidence.kind(), evidence.material().excerptId(),
                        evidence.material().start(), evidence.material().end(), evidence.material().quote());
                case EXTERNAL_QUOTE -> List.of(evidence.kind(), evidence.external().url(), evidence.external().locator(), evidence.external().quote());
            };
            result.add(canonicalJson(location));
        }
        return Set.copyOf(result);
    }

    private static Set<String> reviewNotes(StandardReviewContent content, StandardCheckpointReview checkpoint) {
        var result = new HashSet<String>();
        for (ReviewEvidence evidence : reviewRecords(content, checkpoint)) result.add(evidence.provenanceNote());
        return Set.copyOf(result);
    }

    private static List<ReviewEvidence> reviewRecords(StandardReviewContent content, StandardCheckpointReview checkpoint) {
        return checkpointEvidence(content, checkpoint).stream()
                .filter(evidence -> evidence.roles().contains(EvidenceRole.REVIEW_RECORD)).toList();
    }

    private static List<ReviewEvidence> checkpointEvidence(StandardReviewContent content, StandardCheckpointReview checkpoint) {
        return content.evidence().stream().filter(evidence -> checkpoint.evidenceIds().contains(evidence.id())).toList();
    }

    private static StandardCheckpointReview checkpoint(StandardReviewContent content, String id) {
        return content.checkpoints().stream().filter(item -> item.checkpointId().equals(id)).findFirst().orElse(null);
    }

    private static boolean sameBasis(BasisSeal before, BasisSeal after) {
        if (!before.question().equals(after.question()) || before.directSources().size() != after.directSources().size()) return false;
        for (int i = 0; i < before.directSources().size(); i++) {
            if (!StandardReviewSources.sameBasis(before.directSources().get(i), after.directSources().get(i))) return false;
        }
        return true;
    }

    private StandardReviewDetail detail(String owner, ReviewRow row, StandardReviewSnapshot snapshot) {
        var summary = new StandardReviewSummary(snapshot.id(), snapshot.question(), snapshot.revision(), snapshot.status(),
                snapshot.content().reviewer().label(), snapshot.content().reviewedOn(), snapshot.content().reviewZoneId(),
                snapshot.content().checkpoints().stream().map(StandardCheckpointReview::checkpointId).toList(),
                snapshot.createdAt(), snapshot.revisedAt());
        return new StandardReviewDetail(summary, sources.eligibility(owner, snapshot, row.revision(), status(row.status())));
    }

    private ReviewRow requireRow(String owner, String id) {
        ReviewRow row = store.review(uuid(owner), uuid(id));
        if (row == null) throw missing();
        validateRow(owner, id, row);
        return row;
    }

    private static void validateRow(String owner, String id, ReviewRow row) {
        try {
            uuid(row.id()); uuid(row.ownerId()); question(rowQuestion(row)); revision(row.revision()); status(row.status());
            time(row.createdAt()); time(row.updatedAt());
            if (!owner.equals(row.ownerId()) || !id.equals(row.id()) || row.updatedAt().isBefore(row.createdAt())) throw integrity();
        } catch (ApiException exception) { throw integrity(); }
    }

    private StandardReviewHistory current(String owner, ReviewRow row) {
        RevisionRow revision = store.revision(owner, row.id(), row.revision());
        if (revision == null || revision.revision() != row.revision()) throw integrity();
        return read(owner, row, revision);
    }

    private StandardReviewHistory readRevision(String owner, ReviewRow row, long number) {
        RevisionRow revision = store.revision(owner, row.id(), number);
        if (revision == null) throw missing();
        if (revision.revision() != number) throw integrity();
        return read(owner, row, revision);
    }

    private StandardReviewHistory read(String owner, ReviewRow current, RevisionRow row) {
        StandardReviewSnapshot snapshot = readStored(row.snapshotJson(), StandardReviewSnapshot.class);
        if (!owner.equals(row.ownerId()) || !current.id().equals(row.reviewId()) || row.revision() != snapshot.revision()
                || !row.questionId().equals(snapshot.question().questionId()) || !row.versionId().equals(snapshot.question().versionId())
                || !current.id().equals(snapshot.id()) || !rowQuestion(current).equals(snapshot.question())
                || !current.createdAt().equals(snapshot.createdAt()) || !row.createdAt().equals(snapshot.revisedAt())
                || row.revision() > current.revision() || snapshot.revisedAt().isAfter(current.updatedAt())
                || row.revision() < current.revision() && snapshot.status() == RecordStatus.WITHDRAWN) throw integrity();
        if (row.revision() == current.revision() && (!current.status().equals(snapshot.status().name())
                || !current.updatedAt().equals(snapshot.revisedAt()))) throw integrity();
        StandardReviewHistory history;
        try { history = StandardReviewRules.history(json, StandardReviewRules.history(
                new StandardReviewHistory(RecordChange.valueOf(row.changeKind()), snapshot, row.changeReason()))); }
        catch (RuntimeException exception) { throw new IllegalStateException("审阅历史事件与快照不一致", exception); }
        validateRelations(owner, snapshot);
        return history;
    }

    private void validateRelations(String owner, StandardReviewSnapshot snapshot) {
        List<CheckpointRow> checkpoints = store.checkpoints(owner, snapshot.id(), snapshot.revision());
        List<EvidenceRow> evidence = store.evidence(owner, snapshot.id(), snapshot.revision());
        if (checkpoints.size() != snapshot.content().checkpoints().size() || evidence.size() != snapshot.content().evidence().size()) throw integrity();
        for (int i = 0; i < checkpoints.size(); i++) {
            if (!checkpointRow(owner, snapshot, i).equals(checkpoints.get(i))) throw integrity();
        }
        for (int i = 0; i < evidence.size(); i++) {
            if (!evidenceRow(owner, snapshot, i).equals(evidence.get(i))) throw integrity();
        }
    }

    private StandardReviewHistory checkedHistory(RecordChange change, StandardReviewSnapshot snapshot, String reason) {
        return StandardReviewRules.history(json, StandardReviewRules.history(new StandardReviewHistory(change, snapshot, reason)));
    }

    private void append(String owner, StandardReviewHistory history) {
        StandardReviewSnapshot snapshot = history.snapshot();
        requireInserted(store.insertRevision(new RevisionRow(owner, snapshot.id(), snapshot.revision(), snapshot.question().questionId(),
                snapshot.question().versionId(), history.change().name(), json.writeValueAsString(snapshot), history.changeReason(), snapshot.revisedAt())));
        for (int i = 0; i < snapshot.content().checkpoints().size(); i++) requireInserted(store.insertCheckpoint(checkpointRow(owner, snapshot, i)));
        for (int i = 0; i < snapshot.content().evidence().size(); i++) requireInserted(store.insertEvidence(evidenceRow(owner, snapshot, i)));
    }

    private static CheckpointRow checkpointRow(String owner, StandardReviewSnapshot snapshot, int position) {
        StandardCheckpointReview checkpoint = snapshot.content().checkpoints().get(position);
        return new CheckpointRow(owner, snapshot.id(), snapshot.revision(), checkpoint.checkpointId(), position, checkpoint.decision().name());
    }

    private static EvidenceRow evidenceRow(String owner, StandardReviewSnapshot snapshot, int position) {
        ReviewEvidence evidence = snapshot.content().evidence().get(position);
        int mask = evidence.roles().stream().mapToInt(role -> 1 << role.ordinal()).sum();
        return new EvidenceRow(owner, snapshot.id(), snapshot.revision(), evidence.id(), position, mask, evidence.kind().name(),
                evidence.knowledge() == null ? null : evidence.knowledge().itemId(),
                evidence.knowledge() == null ? null : evidence.knowledge().versionId(),
                evidence.material() == null ? null : evidence.material().excerptId());
    }

    private static ReviewRow row(String owner, StandardReviewSnapshot snapshot) {
        return new ReviewRow(snapshot.id(), owner, snapshot.question().questionId(), snapshot.question().versionId(), snapshot.revision(),
                snapshot.status().name(), snapshot.createdAt(), snapshot.revisedAt());
    }

    private static QuestionVersionRef rowQuestion(ReviewRow row) { return new QuestionVersionRef(row.questionId(), row.versionId()); }
    private static StandardRevisionSummary summary(StandardReviewHistory history) {
        return new StandardRevisionSummary(history.snapshot().revision(), history.change(), history.snapshot().status(), history.snapshot().revisedAt());
    }
    private static void page(int limit) { if (limit < 1 || limit > 50) throw invalid("每页数量须为 1 至 50。"); }
    private static void expect(long current, long expected) {
        if (current != expected) throw conflict("REVIEW_REVISION_CONFLICT", "审阅已发生变化，请保留输入并重新核对。", current);
    }
    private static void changed(int count, long current) {
        if (count != 1) throw conflict("REVIEW_REVISION_CONFLICT", "审阅已发生变化，请保留输入并重新核对。", current);
    }
    private static void requireInserted(int count) { if (count != 1) throw integrity(); }
    private static void matchPreview(String expected, String current) {
        if (!expected.equals(current)) throw conflict("REVIEW_PREVIEW_CHANGED", "审阅预览的来源、范围或日期已变化，请重新预览。", null);
    }
    private static ApiException withdrawn() { return conflict("REVIEW_RECORD_WITHDRAWN", "已撤回审阅不能编辑或改变撤回理由。", null); }
    private static ApiException missing() { return new ApiException(404, "STANDARD_REVIEW_NOT_FOUND", "审阅或确切修订不存在或不可访问。"); }

    private record Existing(ReviewRow row, StandardReviewHistory history) {}
    private record Prepared(StandardReviewSources.Inspection inspection, StandardReviewPreview preview) {}
    private record PreviewFacts(String purpose, StandardReviewDraft draft, StandardReviewSnapshot existingRevision,
        StandardReviewSources.QuestionFacts questionFacts, List<StandardReviewSources.EvidenceFacts> evidenceFacts,
        BasisSeal basis, List<CheckpointEligibility> checkpoints, List<StandardReviewSources.ValidationDate> validationDates,
        List<ReviewLimitation> limitations) {}
}
