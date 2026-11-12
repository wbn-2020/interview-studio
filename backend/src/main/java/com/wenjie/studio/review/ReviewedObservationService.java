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
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.wenjie.studio.review.ReviewedObservationDtos.*;
import static com.wenjie.studio.review.ReviewedObservationRows.*;
import static com.wenjie.studio.review.StandardReviewDtos.*;
import static com.wenjie.studio.review.StandardReviewRules.*;

@Service
public class ReviewedObservationService {
    private static final String PATH = "/api/v1/review/observations";
    private final ReviewedObservationMapper store;
    private final ReviewedObservationSources sources;
    private final IdempotencyService commands;
    private final JsonMapper json;

    public ReviewedObservationService(ReviewedObservationMapper store, ReviewedObservationSources sources,
                                      IdempotencyService commands, JsonMapper json) {
        this.store = store;
        this.sources = sources;
        this.commands = commands;
        this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ObservationPage list(String owner, ReviewDtos.AnswerLink answer, RecordStatus status, String cursor, int limit) {
        uuid(owner); ReviewedObservationRules.answer(answer); page(limit);
        if (cursor != null) uuid(cursor);
        // 即使没有观察记录也先核确切答案的评价访问，不能用空列表绕过原答访问边界。
        sources.requireAnswerAccess(owner, answer);
        String state = status == null ? null : status.name();
        List<ObservationRow> rows = store.observations(owner, answer.sessionId(), answer.questionId(), answer.answerId(), state, cursor, limit);
        var items = new ArrayList<ObservationSummary>();
        String previous = cursor;
        for (ObservationRow row : rows) {
            validateRow(owner, row.id(), row);
            if (!answer.equals(rowAnswer(row)) || status != null && !status.name().equals(row.status())
                    || previous != null && row.id().compareTo(previous) <= 0) throw integrity();
            ReviewedObservation snapshot = current(owner, row).snapshot();
            var reading = sources.read(owner, snapshot, row.revision(), status(row.status()));
            items.add(summary(snapshot, reading.eligibility()));
            previous = row.id();
        }
        boolean more = rows.size() == limit && !store.observations(owner, answer.sessionId(), answer.questionId(), answer.answerId(),
                state, rows.getLast().id(), 1).isEmpty();
        return readResponse(json, new ObservationPage(List.copyOf(items), more ? rows.getLast().id() : null));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ObservationDetail get(String owner, String id, Long revision) {
        if (revision != null) revision(revision);
        ObservationRow row = requireRow(owner, id);
        sources.requireAnswerAccess(owner, rowAnswer(row));
        ObservationHistory current = current(owner, row);
        ReviewedObservation snapshot = revision == null || revision == row.revision()
                ? current.snapshot() : readRevision(owner, row, revision, current.snapshot().question()).snapshot();
        var reading = sources.read(owner, snapshot, row.revision(), status(row.status()));
        return readResponse(json, new ObservationDetail(snapshot, reading.answerConditions(), reading.eligibility()));
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true, noRollbackFor = RuntimeException.class)
    public ProjectionBasis projectionBasis(String owner, RevisionRef ref) {
        required(ref); uuid(ref.id()); revision(ref.revision());
        ObservationRow row = requireRow(owner, ref.id());
        sources.requireAnswerAccess(owner, rowAnswer(row));
        // 内部投影沿同一历史完整性检查，不经返回正文的HTTP接口，也不触发标准查看曝光。
        ObservationHistory current = current(owner, row);
        ObservationHistory history = ref.revision() == row.revision() ? current
                : readRevision(owner, row, ref.revision(), current.snapshot().question());
        return new ProjectionBasis(history, row.revision(), status(row.status()));
    }

    public record ProjectionBasis(ObservationHistory history, long currentRevision, RecordStatus currentStatus) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ObservationPreview preview(String owner, ObservationDraft draft) {
        uuid(owner); bounded(json, required(draft), REQUEST_BYTES);
        Instant now = now();
        ReviewedObservationRules.observationDraft(draft, now);
        Existing existing = existing(owner, draft, now);
        return readResponse(json, prepare(owner, draft, existing, now).preview());
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, timeout = 30)
    public ReviewedObservation create(String owner, String key, SaveObservation request) {
        return commands.execute(owner, key, "POST " + PATH, request, ReviewedObservation.class,
                () -> sources.requireAnswerAccess(owner, required(required(request).draft()).answer()), () -> {
            Instant now = now();
            validateSave(request, now);
            if (request.draft().id() != null) throw invalid("新建观察不能提供对象标识或预期修订。");
            Prepared prepared = prepare(owner, request.draft(), null, now);
            matchPreview(request.expectedPreviewHash(), prepared.preview().previewHash());
            String id = UUID.randomUUID().toString();
            ObservationBinding binding = prepared.inspection().binding();
            ReviewedObservation snapshot = new ReviewedObservation(id, 0, RecordStatus.ACTIVE, request.draft().answer(),
                    binding.question(), request.draft().checkpointId(), request.draft().content(), binding, now, now);
            ObservationHistory history = checkedHistory(RecordChange.CREATED, snapshot, null);
            requireInserted(store.insertObservation(row(owner, snapshot)));
            append(owner, history);
            return snapshot;
        });
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, timeout = 30)
    public ReviewedObservation save(String owner, String id, String key, SaveObservation request) {
        uuid(id);
        return commands.execute(owner, key, "PUT " + PATH + "/" + id, request, ReviewedObservation.class,
                () -> sources.requireAnswerAccess(owner, rowAnswer(requireRow(owner, id))), () -> {
            Instant now = now();
            validateSave(request, now);
            if (!id.equals(request.draft().id())) throw invalid("路径与观察草稿标识不一致。");
            Existing existing = existing(owner, request.draft(), now);
            Prepared prepared = prepare(owner, request.draft(), existing, now);
            matchPreview(request.expectedPreviewHash(), prepared.preview().previewHash());
            ReviewedObservation before = existing.history().snapshot();
            ObservationBinding binding = prepared.inspection().binding();
            // 原答、题目快照和确切审阅摘要都相同才是空操作；修改理由单独变化不制造历史。
            if (before.content().equals(request.draft().content()) && before.binding().equals(binding)) return before;
            ReviewedObservation after = new ReviewedObservation(id, next(before.revision()), RecordStatus.ACTIVE,
                    before.answer(), before.question(), before.checkpointId(), request.draft().content(), binding, before.createdAt(), now);
            ObservationHistory history = checkedHistory(RecordChange.REVISED, after, request.draft().changeReason());
            append(owner, history);
            changed(store.updateObservation(row(owner, after), before.revision()), before.revision());
            return after;
        });
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, timeout = 30)
    public ObservationHistory withdraw(String owner, String id, String key, WithdrawRecord request) {
        uuid(id);
        // 同键回执也须重新核原答访问；编辑／撤回的答案只取 owner 限定当前行，不能信任请求替换身份。
        return commands.execute(owner, key, "POST " + PATH + "/" + id + "/withdraw", request, ObservationHistory.class,
                () -> sources.requireAnswerAccess(owner, rowAnswer(requireRow(owner, id))), () -> {
            bounded(json, StandardReviewRules.withdraw(request), REQUEST_BYTES);
            ObservationRow row = requireRow(owner, id);
            ObservationHistory before = current(owner, row);
            expect(row.revision(), request.expectedRevision());
            if (before.snapshot().status() == RecordStatus.WITHDRAWN) {
                if (Objects.equals(before.changeReason(), request.reason())) return before;
                throw withdrawn();
            }
            Instant now = now();
            clock(now, row.updatedAt());
            ReviewedObservation snapshot = before.snapshot();
            ReviewedObservation after = new ReviewedObservation(id, next(snapshot.revision()), RecordStatus.WITHDRAWN,
                    snapshot.answer(), snapshot.question(), snapshot.checkpointId(), snapshot.content(), snapshot.binding(), snapshot.createdAt(), now);
            ObservationHistory history = checkedHistory(RecordChange.WITHDRAWN, after, request.reason());
            // 原答访问仍须有效；撤回复制历史绑定，不要求已失效的标准重新获得支持资格。
            append(owner, history);
            changed(store.updateObservation(row(owner, after), row.revision()), row.revision());
            return history;
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ObservationHistoryPage history(String owner, String id, Long before, int limit) {
        page(limit); if (before != null) revision(before);
        ObservationRow row = requireRow(owner, id);
        sources.requireAnswerAccess(owner, rowAnswer(row));
        ObservationHistory current = current(owner, row);
        List<RevisionRow> rows = store.history(owner, id, before, limit);
        var items = new ArrayList<ObservationHistory>();
        Long previous = before;
        for (RevisionRow revision : rows) {
            if (previous != null && revision.revision() >= previous) throw integrity();
            ObservationHistory history = read(owner, row, revision);
            if (!current.snapshot().question().equals(history.snapshot().question())) throw integrity();
            items.add(history);
            previous = revision.revision();
        }
        boolean more = rows.size() == limit && !store.history(owner, id, rows.getLast().revision(), 1).isEmpty();
        // 历史保留当时原文与绑定，不附上容易被误读为当时结论的当前资格。
        return readResponse(json, new ObservationHistoryPage(List.copyOf(items), more ? rows.getLast().revision() : null));
    }

    private void validateSave(SaveObservation request, Instant now) {
        bounded(json, required(request), REQUEST_BYTES);
        hashText(request.expectedPreviewHash());
        ReviewedObservationRules.observationDraft(request.draft(), now);
    }

    private Existing existing(String owner, ObservationDraft draft, Instant now) {
        if (draft.id() == null) return null;
        ObservationRow row = requireRow(owner, draft.id());
        ObservationHistory history = current(owner, row);
        expect(row.revision(), draft.expectedRevision());
        if (history.snapshot().status() == RecordStatus.WITHDRAWN) throw withdrawn();
        if (!history.snapshot().answer().equals(draft.answer()) || !history.snapshot().checkpointId().equals(draft.checkpointId())) {
            throw invalid("观察创建后不能改变确切答案或检查项。");
        }
        clock(now, row.updatedAt());
        return new Existing(row, history);
    }

    private Prepared prepare(String owner, ObservationDraft draft, Existing existing, Instant now) {
        ReviewedObservationSources.Inspection inspected = sources.inspect(owner, draft, now);
        if (existing != null) {
            ObservationBinding recorded = existing.history().snapshot().binding();
            ObservationBinding actual = inspected.binding();
            // 同一提交和题实例快照不可变；摘要不符是完整性错误，不能借编辑追加一份“修正后”的历史。
            if (!recorded.question().equals(actual.question()) || !recorded.answerHash().equals(actual.answerHash())
                    || !recorded.questionSnapshotHash().equals(actual.questionSnapshotHash())
                    || recorded.standardReview().equals(actual.standardReview()) && !recorded.standardBasisHash().equals(actual.standardBasisHash())) throw integrity();
        }
        PreviewFacts facts = new PreviewFacts("reviewed-observation", draft, existing == null ? null : existing.history().snapshot(),
                inspected.binding(), inspected.answerConditions(), inspected.standardEligibility(), inspected.citationChecks(),
                inspected.validationDates(), inspected.limitations());
        String hash = hash("reviewed-observation-preview-v1\n", facts);
        return new Prepared(inspected, new ObservationPreview(draft, inspected.binding(), inspected.answerConditions(),
                inspected.standardEligibility(), hash, inspected.limitations()));
    }

    private ObservationRow requireRow(String owner, String id) {
        ObservationRow row = store.observation(uuid(owner), uuid(id));
        if (row == null) throw missing();
        validateRow(owner, id, row);
        return row;
    }

    private static void validateRow(String owner, String id, ObservationRow row) {
        try {
            uuid(row.id()); uuid(row.ownerId()); ReviewedObservationRules.answer(rowAnswer(row)); uuid(row.checkpointId());
            revision(row.revision()); status(row.status()); time(row.createdAt()); time(row.updatedAt());
            if (!owner.equals(row.ownerId()) || !id.equals(row.id()) || row.updatedAt().isBefore(row.createdAt())) throw integrity();
        } catch (ApiException exception) { throw integrity(); }
    }

    private ObservationHistory current(String owner, ObservationRow row) {
        RevisionRow revision = store.revision(owner, row.id(), row.revision());
        if (revision == null || revision.revision() != row.revision()) throw integrity();
        return read(owner, row, revision);
    }

    private ObservationHistory readRevision(String owner, ObservationRow row, long number, QuestionVersionRef question) {
        RevisionRow revision = store.revision(owner, row.id(), number);
        if (revision == null) throw missing();
        if (revision.revision() != number) throw integrity();
        ObservationHistory history = read(owner, row, revision);
        if (!question.equals(history.snapshot().question())) throw integrity();
        return history;
    }

    private ObservationHistory read(String owner, ObservationRow current, RevisionRow row) {
        ReviewedObservation snapshot = readStored(row.snapshotJson(), ReviewedObservation.class);
        if (!owner.equals(row.ownerId()) || !current.id().equals(row.observationId()) || row.revision() != snapshot.revision()
                || !row.checkpointId().equals(snapshot.checkpointId()) || !current.checkpointId().equals(snapshot.checkpointId())
                || !row.standardReviewId().equals(snapshot.content().standardReview().id())
                || row.standardReviewRevision() != snapshot.content().standardReview().revision()
                || !current.id().equals(snapshot.id()) || !rowAnswer(current).equals(snapshot.answer())
                || !current.createdAt().equals(snapshot.createdAt()) || !row.createdAt().equals(snapshot.revisedAt())
                || row.revision() > current.revision() || snapshot.revisedAt().isAfter(current.updatedAt())
                || row.revision() < current.revision() && snapshot.status() == RecordStatus.WITHDRAWN) throw integrity();
        if (row.revision() == current.revision() && (!current.status().equals(snapshot.status().name())
                || !current.updatedAt().equals(snapshot.revisedAt()))) throw integrity();
        try {
            return checkedHistory(RecordChange.valueOf(row.changeKind()), snapshot, row.changeReason());
        } catch (RuntimeException exception) { throw new IllegalStateException("受审观察历史事件与快照不一致", exception); }
    }

    private ObservationHistory checkedHistory(RecordChange change, ReviewedObservation snapshot, String reason) {
        return StandardReviewRules.history(json, ReviewedObservationRules.history(new ObservationHistory(change, snapshot, reason)));
    }

    private void append(String owner, ObservationHistory history) {
        ReviewedObservation snapshot = history.snapshot();
        requireInserted(store.insertRevision(new RevisionRow(owner, snapshot.id(), snapshot.revision(), snapshot.checkpointId(),
                snapshot.content().standardReview().id(), snapshot.content().standardReview().revision(), history.change().name(),
                json.writeValueAsString(snapshot), history.changeReason(), snapshot.revisedAt())));
    }

    private static ObservationSummary summary(ReviewedObservation snapshot, ObservationEligibility eligibility) {
        return new ObservationSummary(snapshot.id(), snapshot.revision(), snapshot.status(), snapshot.answer(), snapshot.question(),
                snapshot.checkpointId(), snapshot.content().standardReview(), snapshot.content().coverage(), snapshot.content().conclusion(),
                snapshot.content().observedOn(), snapshot.content().observationZoneId(), snapshot.createdAt(), snapshot.revisedAt(), eligibility);
    }

    private static ObservationRow row(String owner, ReviewedObservation snapshot) {
        return new ObservationRow(snapshot.id(), owner, snapshot.answer().sessionId(), snapshot.answer().questionId(), snapshot.answer().answerId(),
                snapshot.checkpointId(), snapshot.revision(), snapshot.status().name(), snapshot.createdAt(), snapshot.revisedAt());
    }

    private static ReviewDtos.AnswerLink rowAnswer(ObservationRow row) { return new ReviewDtos.AnswerLink(row.sessionId(), row.questionId(), row.answerId()); }
    private static void page(int limit) { if (limit < 1 || limit > 50) throw invalid("每页数量须为 1 至 50。"); }
    private static void expect(long current, long expected) {
        if (current != expected) throw conflict("REVIEW_REVISION_CONFLICT", "观察已发生变化，请保留输入并重新核对。", current);
    }
    private static void changed(int count, long current) {
        if (count != 1) throw conflict("REVIEW_REVISION_CONFLICT", "观察已发生变化，请保留输入并重新核对。", current);
    }
    private static void requireInserted(int count) { if (count != 1) throw integrity(); }
    private static void matchPreview(String expected, String current) {
        if (!expected.equals(current)) throw conflict("REVIEW_PREVIEW_CHANGED", "观察预览的答案、审阅依据或日期已变化，请重新预览。", null);
    }
    private static ApiException withdrawn() { return conflict("REVIEW_RECORD_WITHDRAWN", "已撤回观察不能编辑或改变撤回理由。", null); }
    private static ApiException missing() { return new ApiException(404, "REVIEWED_OBSERVATION_NOT_FOUND", "观察或确切修订不存在或不可访问。"); }

    private record Existing(ObservationRow row, ObservationHistory history) {}
    private record Prepared(ReviewedObservationSources.Inspection inspection, ObservationPreview preview) {}
    private record PreviewFacts(String purpose, ObservationDraft draft, ReviewedObservation existingRevision,
        ObservationBinding binding, RecordedAnswerConditions answerConditions, StandardEligibility standardEligibility,
        List<ReviewedObservationSources.CitationCheck> citationChecks, List<ReviewedObservationSources.ValidationDate> validationDates,
        List<ReviewLimitation> limitations) {}
}
