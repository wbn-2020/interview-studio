package com.wenjie.studio.search;

import com.wenjie.studio.ai.AiDtos;
import com.wenjie.studio.ai.RelationAiFeedbackSources;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.knowledge.RelationAiFeedbackEvidenceDtos;
import com.wenjie.studio.review.ReviewDtos.AnswerLink;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.wenjie.studio.search.UnifiedSearchDtos.*;

@Service
@Transactional(propagation = Propagation.MANDATORY, readOnly = true, noRollbackFor = ApiException.class)
public class UnifiedSearchAiSources {
    private static final int REPORT_BATCH = 16;
    private static final int MAX_REPORTS = 64;
    private static final long MAX_REPORT_BYTES = 1_048_576;
    private static final long MAX_SCAN_BYTES = 8_388_608;
    private final UnifiedSearchAiMapper mapper;
    private final RelationAiFeedbackSources reports;
    private final UnifiedSearchHistorySources history;

    public UnifiedSearchAiSources(UnifiedSearchAiMapper mapper, RelationAiFeedbackSources reports,
                                  UnifiedSearchHistorySources history) {
        this.mapper = mapper;
        this.reports = reports;
        this.history = history;
    }

    public Page search(String owner, String query, int limit, int offset) {
        if (!uuid(owner) || !query(query) || limit < 1 || limit > 50 || offset < 0 || offset > 1000) throw invalid();
        var hits = new ArrayList<Hit>();
        int scanned = 0, matched = 0;
        long scannedBytes = 0;
        String afterId = null;
        boolean exhausted = false, budgetLimited = false;
        scan: while (scanned < MAX_REPORTS) {
            int batchLimit = Math.min(REPORT_BATCH, MAX_REPORTS - scanned);
            List<UnifiedSearchAiRows.Report> batch = mapper.reports(owner, afterId, batchLimit);
            if (batch == null || batch.size() > batchLimit) throw broken();
            for (var row : batch) {
                directory(owner, row);
                if (afterId != null && row.taskId().compareTo(afterId) >= 0) throw broken();
                // 同一 RR 快照先核原文字节预算，超出剩余预算的报告不加载到应用内存。
                if (row.reportBytes() > MAX_SCAN_BYTES - scannedBytes) {
                    budgetLimited = true;
                    break scan;
                }
                List<RelationAiFeedbackEvidenceDtos.Source> observations = observations(owner, row.taskId());
                var first = observations.getFirst();
                AnswerLink answer = new AnswerLink(row.sessionId(), row.questionId(), row.answerId());
                if (!row.attemptId().equals(first.attemptId()) || !answer.equals(first.answer())) throw broken();
                TrainingContext training = history.trainingContext(owner, answer);
                scanned++; scannedBytes += row.reportBytes(); afterId = row.taskId();
                for (var observation : observations) {
                    Source source = source(observation, training);
                    List<Fragment> fragments = UnifiedSearchText.fragments(source.fields(), query);
                    if (fragments.isEmpty()) continue;
                    if (matched++ < offset) continue;
                    hits.add(located(source.item(), fragments));
                    if (hits.size() > limit) break scan;
                }
            }
            if (batch.size() < batchLimit) { exhausted = true; break; }
        }
        boolean extraHit = hits.size() > limit;
        boolean pageLimited = extraHit && offset + limit > 1000;
        boolean hasMore = extraHit && !pageLimited;
        boolean scanLimited = pageLimited || !extraHit && (budgetLimited || !exhausted && scanned == MAX_REPORTS);
        return new Page(Domain.AI_FEEDBACK, query, "ACTIVE", MatchMode.LITERAL, true, null,
                List.copyOf(hits.subList(0, Math.min(hits.size(), limit))), limit, offset, hasMore, scanLimited);
    }

    public Source read(String owner, String taskId, String attemptId, String feedbackId, String reportHash) {
        if (!uuid(owner) || !uuid(taskId) || !uuid(attemptId) || !feedbackId(feedbackId) || !hash(reportHash)) throw invalid();
        List<RelationAiFeedbackEvidenceDtos.Source> observations = observations(owner, taskId);
        var first = observations.getFirst();
        if (!attemptId.equals(first.attemptId()) || !reportHash.equals(first.reportHash())) throw changed();
        var selected = observations.stream().filter(item -> feedbackId.equals(item.feedbackId())).findFirst().orElseThrow(UnifiedSearchAiSources::missing);
        return source(selected, history.trainingContext(owner, selected.answer()));
    }

    public void requireAccess(String owner, Hit hit) {
        if (!uuid(owner)) throw invalid();
        if (hit == null || hit.domain() != Domain.AI_FEEDBACK || !uuid(hit.id()) || !uuid(hit.versionId())
                || hit.training() == null || hit.aiFeedback() == null || !feedbackId(hit.aiFeedback().feedbackId())
                || !hash(hit.aiFeedback().reportHash()) || hit.aiFeedback().level() != AiDtos.EvaluationLevel.AI_UNREVIEWED) throw broken();
        try { reports.requireReadAccess(owner, hit.id(), hit.versionId(), hit.training().answer()); }
        catch (ApiException exception) { throw translated(exception); }
        catch (IllegalStateException exception) { throw broken(); }
    }

    private List<RelationAiFeedbackEvidenceDtos.Source> observations(String owner, String taskId) {
        List<RelationAiFeedbackEvidenceDtos.Source> values;
        try { values = reports.readObservations(owner, taskId); }
        catch (ApiException exception) { throw translated(exception); }
        catch (IllegalStateException exception) { throw broken(); }
        if (values == null || values.isEmpty() || values.size() > 12) throw broken();
        var first = values.getFirst();
        if (first == null || !uuid(first.attemptId()) || !hash(first.reportHash())) throw broken();
        for (int index = 0; index < values.size(); index++) {
            var item = values.get(index);
            if (item == null || !taskId.equals(item.taskId()) || !("f" + (index + 1)).equals(item.feedbackId())
                    || !first.attemptId().equals(item.attemptId()) || !first.reportHash().equals(item.reportHash())
                    || first.answer() == null || !first.answer().equals(item.answer())) throw broken();
        }
        return values;
    }

    private static Source source(RelationAiFeedbackEvidenceDtos.Source item, TrainingContext training) {
        if (training == null || training.conditions() == null || !item.answer().equals(training.answer())
                || item.answerNumber() != training.conditions().answerNumber()
                || !item.answerCondition().equals(training.conditions().answerCondition().name())
                || item.level() != AiDtos.EvaluationLevel.AI_UNREVIEWED) throw broken();
        var context = new AiFeedbackContext(item.feedbackId(), item.reportHash(), item.level(), item.reportLimitations(),
                item.validationNote(), item.dimension(), item.coverage(), item.condition(), item.basis(), item.limitation(),
                item.decisionRevision(), item.decisionChoice());
        // 完整限定随结果保留；正文坐标只覆盖观察五字段，不包含报告摘要、引用或决定理由。
        var hit = new Hit(Domain.AI_FEEDBACK, item.taskId(), item.attemptId(), null, "AI_FEEDBACK",
                "AI 评价 · 第 " + item.answerNumber() + " 次提交 · " + item.feedbackId(), "AI_UNREVIEWED", true,
                null, null, null, null, List.of(), training, null, null, context);
        return new Source(hit, List.of(new TextField("observation", "观察", item.observation()),
                new TextField("condition", "条件", item.condition()), new TextField("basis", "依据", item.basis()),
                new TextField("limitation", "限制", item.limitation()), new TextField("suggestion", "改进建议", item.suggestion())));
    }

    private static Hit located(Hit hit, List<Fragment> fragments) {
        return new Hit(hit.domain(), hit.id(), hit.versionId(), hit.versionNumber(), hit.kind(), hit.title(), hit.status(),
                hit.currentVersion(), hit.verification(), hit.projectStatus(), hit.attachmentId(), hit.runId(), fragments,
                hit.training(), hit.feedback(), hit.recollection(), hit.aiFeedback());
    }

    private static void directory(String owner, UnifiedSearchAiRows.Report row) {
        if (row == null || !owner.equals(row.ownerId()) || !uuid(row.taskId()) || !uuid(row.attemptId())
                || !uuid(row.sessionId()) || !uuid(row.questionId()) || !uuid(row.answerId())
                || row.reportBytes() == null || row.reportBytes() <= 0 || row.reportBytes() > MAX_REPORT_BYTES) throw broken();
    }

    private static ApiException translated(ApiException exception) {
        return switch (exception.code()) {
            case "RELATION_AI_FEEDBACK_SOURCE_NOT_FOUND", "AI_REVIEW_SOURCE_NOT_FOUND" -> missing();
            case "RELATION_AI_FEEDBACK_SOURCE_CHANGED", "AI_SOURCE_CHANGED" -> changed();
            default -> exception;
        };
    }
    private static boolean query(String value) {
        return value != null && !value.isBlank() && value.length() <= 100
                && value.codePoints().noneMatch(point -> point >= 0xD800 && point <= 0xDFFF);
    }
    private static boolean feedbackId(String value) { return value != null && value.matches("f([1-9]|1[0-2])"); }
    private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static boolean uuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException exception) { return false; }
    }
    private static ApiException invalid() { return new ApiException(400, "INVALID_REQUEST", "请检查 AI 评价身份、报告摘要、检索词和分页范围。"); }
    private static ApiException missing() { return new ApiException(404, "SEARCH_SOURCE_NOT_FOUND", "该成功 AI 评价或确切观察不存在或不可访问。"); }
    private static ApiException changed() { return new ApiException(409, "SEARCH_SOURCE_CHANGED", "AI 评价的成功尝试、报告或原答身份已变化，请重新检索。"); }
    private static ApiException broken() { return new ApiException(409, "SEARCH_SOURCE_INVALID", "AI 评价来源完整性无法确认，请在原复盘中检查后重试。"); }
}
