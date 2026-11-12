package com.wenjie.studio.review;

import com.wenjie.studio.ai.AiDtos;
import com.wenjie.studio.ai.AiDtos.EvaluationInput;
import com.wenjie.studio.ai.AiDtos.EvaluationObservation;
import com.wenjie.studio.ai.AiDtos.FeedbackChoice;
import com.wenjie.studio.ai.AiService;
import com.wenjie.studio.ai.TrainingEvaluations;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.wenjie.studio.review.ReviewDtos.*;
import static com.wenjie.studio.review.ReviewRows.*;
import static com.wenjie.studio.review.ReviewRules.*;

@Service
public class ReviewService {
    private final ReviewMapper mapper;
    private final TrainingEvaluations evaluations;
    private final AiService ai;
    private final RecollectionSources recollections;
    private final IdempotencyService commands;
    private final JsonMapper json;

    public ReviewService(ReviewMapper mapper, TrainingEvaluations evaluations, AiService ai,
                         IdempotencyService commands, JsonMapper json, RecollectionSources recollections) {
        this.mapper = mapper;
        this.evaluations = evaluations;
        this.ai = ai;
        this.recollections = recollections;
        this.commands = commands;
        this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EvaluationInput context(String owner, AnswerLink answer) {
        uuid(owner); ReviewRules.answer(answer); requireAccess(owner, answer);
        return evaluations.input(owner, answer.sessionId(), answer.questionId(), answer.answerId());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FeedbackPage feedbacks(String owner, AnswerLink answer, String cursor, int limit) {
        context(owner, answer); page(limit, 50); if (cursor != null) uuid(cursor);
        List<FeedbackRow> rows = mapper.feedbacks(owner, answer.sessionId(), answer.questionId(), answer.answerId(), cursor, limit + 1);
        List<FeedbackSummary> items = rows.stream().limit(limit).map(row -> {
            FeedbackContent content = savedContent(requireFeedbackRevision(owner, row.id(), row.revision()));
            return new FeedbackSummary(row.id(), link(row), row.revision(), content.kind(), content.observation(), row.updatedAt());
        }).toList();
        return new FeedbackPage(items, rows.size() > limit ? items.getLast().id() : null);
    }

    public Feedback createFeedback(String owner, String key, CreateFeedback request) {
        if (request == null) throw invalid("反馈请求不能为空。");
        ReviewRules.answer(request.answer());
        return commands.execute(owner, key, "POST /api/v1/review/feedback", request, Feedback.class, () -> {
            ReviewRules.content(request.content(), context(owner, request.answer()));
            String contentJson = bounded(request.content(), 131_072);
            Instant now = now();
            AnswerLink answer = request.answer();
            FeedbackRow row = new FeedbackRow(newId(), owner, answer.sessionId(), answer.questionId(), answer.answerId(), 1, now, now);
            mapper.insertFeedback(row);
            mapper.insertFeedbackRevision(new FeedbackRevisionRow(owner, row.id(), 1, contentJson, now));
            return feedbackView(owner, row, 1);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Feedback feedback(String owner, String id, Integer revision) {
        FeedbackRow row = requireFeedback(owner, id);
        if (revision != null) feedbackRevision(revision);
        return feedbackView(owner, row, revision == null ? row.revision() : revision);
    }

    public Feedback saveFeedback(String owner, String id, String key, SaveFeedback request) {
        uuid(id); if (request == null) throw invalid("反馈请求不能为空。");
        feedbackRevision(request.expectedRevision());
        return commands.execute(owner, key, "PUT /api/v1/review/feedback/" + id, request, Feedback.class, () -> {
            FeedbackRow before = requireFeedback(owner, id);
            expect(request.expectedRevision(), before.revision());
            if (before.revision() >= MAX_FEEDBACK_REVISION) throw limit("反馈已达到 100 次修订上限。");
            ReviewRules.content(request.content(), context(owner, link(before)));
            Instant now = now();
            int next = before.revision() + 1;
            // 人工更正追加独立版本；既有决定和动作仍指向原来的确切反馈版本。
            mapper.insertFeedbackRevision(new FeedbackRevisionRow(owner, id, next, bounded(request.content(), 131_072), now));
            FeedbackRow after = new FeedbackRow(id, owner, before.sessionId(), before.questionId(), before.answerId(), next, before.createdAt(), now);
            changed(mapper.updateFeedback(after, before.revision()));
            return feedbackView(owner, after, next);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FeedbackHistoryPage feedbackHistory(String owner, String id, Integer before, int limit) {
        FeedbackRow row = requireFeedback(owner, id); requireAccess(owner, link(row)); page(limit, 50);
        if (before != null && (before < 1 || before > MAX_FEEDBACK_REVISION + 1)) throw invalid("反馈历史游标无效。");
        List<FeedbackRevisionRow> rows = mapper.feedbackHistory(owner, id, before, limit + 1);
        List<Feedback> items = rows.stream().limit(limit).map(version -> feedbackView(owner, row, version)).toList();
        return new FeedbackHistoryPage(items, rows.size() > limit ? items.getLast().revision() : null);
    }

    public Feedback decideFeedback(String owner, String id, String key, DecideFeedback request) {
        uuid(id); if (request == null || request.choice() == null) throw invalid("请选择反馈决定。");
        feedbackRevision(request.feedbackRevision()); decisionRevision(request.expectedRevision());
        text(request.reason(), 1000, request.choice() == FeedbackChoice.DISPUTED);
        return commands.execute(owner, key, "POST /api/v1/review/feedback/" + id + "/decisions", request, Feedback.class, () -> {
            FeedbackRow row = requireFeedback(owner, id);
            Feedback feedback = feedbackView(owner, row, request.feedbackRevision());
            Decision current = latest(feedback.decisions());
            int revision = current == null ? 0 : current.revision();
            expectDecision(request.expectedRevision(), revision);
            if (revision >= 20) throw limit("这版反馈已达到 20 次决定上限。");
            mapper.insertDecision(new DecisionRow(newId(), owner, id, request.feedbackRevision(), revision + 1,
                    request.choice().name(), request.reason(), now()));
            return feedbackView(owner, row, request.feedbackRevision());
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ActionPage actions(String owner, ActionStatus status, String cursor, int limit) {
        uuid(owner); page(limit, 50); if (cursor != null) uuid(cursor);
        List<ActionSummaryRow> rows = mapper.actions(owner, status == null ? null : status.name(), cursor, limit + 1);
        List<ActionSummary> items = rows.stream().limit(limit).map(row -> new ActionSummary(row.id(), row.revision(), row.title(),
                ActionStatus.valueOf(row.status()), row.mergedInto(), row.sourceCount(), row.trackingCount(), row.updatedAt())).toList();
        return new ActionPage(items, rows.size() > limit ? items.getLast().id() : null);
    }

    public Action createAction(String owner, String key, CreateAction request) {
        if (request == null) throw invalid("动作请求不能为空。");
        text(request.title(), 200, true); text(request.detail(), 2000, false);
        if (request.sources() == null || request.sources().isEmpty() || request.sources().size() > 10) throw invalid("动作须选择 1 至 10 个来源。");
        Set<String> identities = new HashSet<>();
        for (SourceRequest source : request.sources()) {
            String identity = sourceIdentity(source);
            if (!identities.add(identity)) throw invalid("同一动作不能重复选择同一反馈修订。");
        }
        return commands.execute(owner, key, "POST /api/v1/review/actions", request, Action.class, () -> {
            String id = newId();
            Instant now = now();
            List<SourceRow> sources = new ArrayList<>();
            for (SourceRequest source : request.sources()) sources.add(resolveSource(owner, id, source, now));
            ActionRow row = new ActionRow(id, owner, 0, request.title(), request.detail(), ActionStatus.OPEN.name(), null, now, now);
            mapper.insertAction(row);
            sources.forEach(mapper::insertSource);
            return remember(owner, row, "CREATE");
        });
    }

    public Action createRecollectionAction(String owner, String id, String key, CreateRecollectionAction request) {
        uuid(id); if (request == null) throw invalid("回忆动作请求不能为空。");
        RecollectionRules.revision(request.expectedRevision()); uuid(request.versionId());
        text(request.title(), 200, true); text(request.detail(), 2000, false);
        text(request.observation(), 5000, true); text(request.verification(), 2000, true);
        return commands.execute(owner, key, "POST /api/v1/review/recollections/" + id + "/actions", request, Action.class, () -> {
            RecollectionDtos.Version version = recollections.requireCurrent(owner, id, request.versionId(), request.expectedRevision());
            String actionId = newId();
            Instant now = now();
            RecollectionSource snapshot = new RecollectionSource(id, version.id(), version.number(),
                    version.content().baselineKind(), request.observation());
            ActionRow action = new ActionRow(actionId, owner, 0, request.title(), request.detail(), ActionStatus.OPEN.name(), null, now, now);
            // 回忆观察只固定所选版本和人工文字，不补造正式答案、反馈或接受决定。
            SourceRow source = new SourceRow(newId(), owner, actionId, actionId, SourceKind.RECOLLECTION.name(),
                    null, null, null, null, null, null, null, null, null, null, null,
                    request.verification(), Tracking.TRACKING.name(), "", now, now, id, version.id(), bounded(snapshot, 65_536));
            mapper.insertAction(action);
            mapper.insertSource(source);
            return remember(owner, action, "CREATE");
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Action action(String owner, String id) { return actionView(owner, requireAction(owner, id)); }

    public Action saveAction(String owner, String id, String key, SaveAction request) {
        uuid(id); if (request == null) throw invalid("动作请求不能为空。");
        actionRevision(request.expectedRevision()); text(request.title(), 200, true); text(request.detail(), 2000, false);
        return commands.execute(owner, key, "PUT /api/v1/review/actions/" + id, request, Action.class, () -> {
            ActionRow before = changeable(owner, id, request.expectedRevision());
            ActionRow after = new ActionRow(id, owner, before.revision() + 1, request.title(), request.detail(),
                    ActionStatus.OPEN.name(), null, before.createdAt(), now());
            changed(mapper.updateAction(after, before.revision()));
            return remember(owner, after, "EDIT");
        });
    }

    public Action saveSource(String owner, String id, String sourceId, String key, SaveSource request) {
        uuid(id); uuid(sourceId); if (request == null || request.tracking() == null) throw invalid("请选择来源跟踪状态。");
        actionRevision(request.expectedRevision()); text(request.verification(), 2000, true);
        text(request.trackingReason(), 1000, request.tracking() == Tracking.DROPPED);
        return commands.execute(owner, key, "PUT /api/v1/review/actions/" + id + "/sources/" + sourceId, request, Action.class, () -> {
            ActionRow before = changeable(owner, id, request.expectedRevision());
            SourceRow source = mapper.source(owner, id, sourceId);
            if (source == null) throw missing();
            ActionSource saved = sourceView(owner, source);
            if (saved.kind() == SourceKind.RECOLLECTION && saved.tracking() == Tracking.DROPPED
                    && request.tracking() == Tracking.TRACKING
                    && recollections.state(owner, saved.recollection().recollectionId(), saved.recollection().versionId())
                    != RecollectionDtos.SourceState.CURRENT) {
                throw new ApiException(409, "REVIEW_SOURCE_CHANGED", "回忆来源已补记或归档，请核对当前版本后再恢复跟踪。");
            }
            Instant now = now();
            SourceRow after = new SourceRow(source.id(), owner, id, source.originActionId(), source.kind(),
                    source.sessionId(), source.questionId(), source.answerId(), source.feedbackId(), source.feedbackRevision(),
                    source.taskId(), source.observationId(), source.manualKind(), source.authorLabel(), source.observationJson(),
                    source.decisionJson(), request.verification(), request.tracking().name(), request.trackingReason(), source.createdAt(), now,
                    source.recollectionId(), source.recollectionVersionId(), source.recollectionJson());
            changed(mapper.updateSource(after));
            ActionRow action = new ActionRow(id, owner, before.revision() + 1, before.title(), before.detail(),
                    before.status(), null, before.createdAt(), now);
            changed(mapper.updateAction(action, before.revision()));
            return remember(owner, action, "SOURCE");
        });
    }

    public Action merge(String owner, String id, String key, MergeAction request) {
        uuid(id); if (request == null) throw invalid("合并请求不能为空。");
        uuid(request.sourceActionId()); actionRevision(request.expectedRevision()); actionRevision(request.sourceExpectedRevision());
        if (id.equals(request.sourceActionId())) throw invalid("动作不能合并到自身。");
        return commands.execute(owner, key, "POST /api/v1/review/actions/" + id + "/merge", request, Action.class, () -> {
            ActionRow target = changeable(owner, id, request.expectedRevision());
            ActionRow source = changeable(owner, request.sourceActionId(), request.sourceExpectedRevision());
            List<SourceRow> incoming = mapper.sources(owner, source.id());
            int targetCount = mapper.sources(owner, target.id()).size();
            if (incoming.isEmpty() || targetCount == 0) throw new IllegalStateException("待合并动作缺少来源");
            if (incoming.size() + targetCount > 20) throw limit("合并后动作最多保留 20 个来源，请分别跟踪现有动作。");
            Instant now = now();
            // 来源 ID、原动作和采用快照保持不变；两端 CAS、全部来源迁移及历史与回执同事务提交。
            if (mapper.moveSources(owner, source.id(), target.id()) != incoming.size()) throw new IllegalStateException("合并来源数量与受锁清单不一致");
            ActionRow targetAfter = new ActionRow(target.id(), owner, target.revision() + 1, target.title(), target.detail(),
                    ActionStatus.OPEN.name(), null, target.createdAt(), now);
            ActionRow sourceAfter = new ActionRow(source.id(), owner, source.revision() + 1, source.title(), source.detail(),
                    ActionStatus.MERGED.name(), target.id(), source.createdAt(), now);
            changed(mapper.updateAction(targetAfter, target.revision()));
            changed(mapper.updateAction(sourceAfter, source.revision()));
            Action result = remember(owner, targetAfter, "MERGE_IN");
            remember(owner, sourceAfter, "MERGE_OUT");
            return result;
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ActionHistoryPage actionHistory(String owner, String id, Long before, int limit) {
        requireAction(owner, id); page(limit, 20);
        if (before != null && (before < 0 || before > MAX_ACTION_REVISION + 1)) throw invalid("动作历史游标无效。");
        List<HistoryRow> rows = mapper.actionHistory(owner, id, before, limit + 1);
        List<ActionRevision> items = rows.stream().limit(limit).map(row -> {
            Action snapshot = json.readValue(row.snapshotJson(), Action.class);
            if (!id.equals(snapshot.id()) || row.revision() != snapshot.revision()) throw new IllegalStateException("动作历史身份不一致");
            for (ActionSource source : snapshot.sources()) requireSourceAccess(owner, source);
            return new ActionRevision(row.change(), snapshot);
        }).toList();
        return new ActionHistoryPage(items, rows.size() > limit ? items.getLast().snapshot().revision() : null);
    }

    private SourceRow resolveSource(String owner, String actionId, SourceRequest request, Instant now) {
        AnswerLink answer;
        FeedbackKind manualKind = null;
        String author = null;
        EvaluationObservation observation;
        Decision decision;
        if (request.kind() == SourceKind.MANUAL) {
            Feedback feedback = feedbackView(owner, requireFeedback(owner, request.feedbackId()), request.feedbackRevision());
            FeedbackContent content = feedback.content();
            answer = feedback.answer(); manualKind = content.kind(); author = content.authorLabel();
            observation = new EvaluationObservation(feedback.id(), content.dimension(), content.coverage(), content.observation(),
                    content.condition(), content.basis(), content.limitation(), content.suggestion(), content.citations());
            decision = latest(feedback.decisions());
        } else if (request.kind() == SourceKind.AI_EVALUATION) {
            AiDtos.Task task = ai.detail(owner, request.taskId());
            if (task.task().purpose() != AiDtos.Purpose.ANSWER_EVALUATION || task.task().status() != AiDtos.Status.SUCCEEDED
                    || task.evaluation() == null || task.evaluationReport() == null
                    || task.evaluationReport().level() != AiDtos.EvaluationLevel.AI_UNREVIEWED) throw missing();
            var binding = task.evaluation();
            answer = new AnswerLink(binding.sessionId(), binding.questionId(), binding.answerId());
            requireAccess(owner, answer);
            List<EvaluationObservation> matches = task.evaluationReport().observations().stream()
                    .filter(item -> request.observationId().equals(item.id())).toList();
            if (matches.size() != 1) throw missing();
            observation = matches.getFirst();
            AiDtos.FeedbackDecision current = task.evaluationDecisions().stream()
                    .filter(item -> request.observationId().equals(item.feedbackId()))
                    .max(Comparator.comparingInt(AiDtos.FeedbackDecision::revision)).orElse(null);
            decision = current == null ? null : new Decision(current.id(), current.revision(), current.choice(), current.reason(), current.createdAt());
        } else throw invalid("回忆来源须从确切回忆版本创建动作。");
        // 创建动作不隐式接受反馈；保存用户看见的决定修订，后来改判不会倒改采用时依据。
        expectDecision(request.expectedDecisionRevision(), decision == null ? 0 : decision.revision());
        return new SourceRow(newId(), owner, actionId, actionId, request.kind().name(), answer.sessionId(), answer.questionId(), answer.answerId(),
                request.feedbackId(), request.feedbackRevision(), request.taskId(), request.observationId(),
                manualKind == null ? null : manualKind.name(), author, bounded(observation, 131_072),
                decision == null ? null : bounded(decision, 16_384), request.verification(), Tracking.TRACKING.name(), "", now, now,
                null, null, null);
    }

    private String sourceIdentity(SourceRequest request) {
        if (request == null || request.kind() == null) throw invalid("请选择合法的动作来源。");
        if (request.kind() != SourceKind.MANUAL && request.kind() != SourceKind.AI_EVALUATION) {
            throw invalid("回忆来源须从确切回忆版本创建动作。");
        }
        decisionRevision(request.expectedDecisionRevision()); text(request.verification(), 2000, true);
        if (request.kind() == SourceKind.MANUAL) {
            uuid(request.feedbackId()); feedbackRevision(request.feedbackRevision());
            if (request.taskId() != null || request.observationId() != null) throw invalid("人工来源不能包含 AI 任务身份。");
            return "MANUAL:" + request.feedbackId() + ":" + request.feedbackRevision();
        }
        uuid(request.taskId());
        if (request.feedbackId() != null || request.feedbackRevision() != null || request.observationId() == null
                || !request.observationId().matches("f([1-9]|1[0-2])")) throw invalid("AI 来源身份无效。");
        return "AI_EVALUATION:" + request.taskId() + ":" + request.observationId();
    }

    private FeedbackRow requireFeedback(String owner, String id) {
        FeedbackRow row = mapper.feedback(uuid(owner), uuid(id));
        if (row == null) throw missing();
        return row;
    }

    private FeedbackRevisionRow requireFeedbackRevision(String owner, String id, int revision) {
        FeedbackRevisionRow row = mapper.feedbackRevision(owner, id, revision);
        if (row == null) throw missing();
        return row;
    }

    private FeedbackContent savedContent(FeedbackRevisionRow revision) { return json.readValue(revision.contentJson(), FeedbackContent.class); }

    private Feedback feedbackView(String owner, FeedbackRow row, int revision) {
        return feedbackView(owner, row, requireFeedbackRevision(owner, row.id(), revision));
    }

    private Feedback feedbackView(String owner, FeedbackRow row, FeedbackRevisionRow revision) {
        requireAccess(owner, link(row));
        List<Decision> decisions = mapper.decisions(owner, row.id(), revision.revision()).stream()
                .map(item -> new Decision(item.id(), item.revision(), FeedbackChoice.valueOf(item.choice()), item.reason(), item.createdAt())).toList();
        return new Feedback(row.id(), link(row), revision.revision(), savedContent(revision), decisions, row.createdAt(), revision.createdAt());
    }

    private ActionRow requireAction(String owner, String id) {
        ActionRow row = mapper.action(uuid(owner), uuid(id));
        if (row == null) throw missing();
        return row;
    }

    private ActionRow changeable(String owner, String id, long expected) {
        ActionRow row = requireAction(owner, id);
        if (!ActionStatus.OPEN.name().equals(row.status())) throw new ApiException(409, "REVIEW_ACTION_MERGED", "动作已合并，请查看合并去向及历史。");
        expect(expected, row.revision());
        if (row.revision() >= MAX_ACTION_REVISION) throw limit("动作已达到 1000 次修订上限。");
        return row;
    }

    private Action actionView(String owner, ActionRow row) {
        List<ActionSource> sources = mapper.sources(owner, row.id()).stream().map(source -> sourceView(owner, source)).toList();
        return new Action(row.id(), row.revision(), row.title(), row.detail(), ActionStatus.valueOf(row.status()),
                row.mergedInto(), sources, row.createdAt(), row.updatedAt());
    }

    private ActionSource sourceView(String owner, SourceRow row) {
        if (!owner.equals(row.ownerId())) throw brokenSource();
        SourceKind kind = SourceKind.valueOf(row.kind());
        RecollectionSource recollection = null;
        AnswerLink answer = null;
        if (kind == SourceKind.RECOLLECTION) {
            if (row.sessionId() != null || row.questionId() != null || row.answerId() != null
                    || row.feedbackId() != null || row.feedbackRevision() != null || row.taskId() != null || row.observationId() != null
                    || row.manualKind() != null || row.authorLabel() != null || row.observationJson() != null || row.decisionJson() != null
                    || row.recollectionId() == null || row.recollectionVersionId() == null || row.recollectionJson() == null
                    || row.recollectionJson().getBytes(StandardCharsets.UTF_8).length > 65_536) throw brokenSource();
            recollection = json.readValue(row.recollectionJson(), RecollectionSource.class);
            if (recollection == null || !row.recollectionId().equals(recollection.recollectionId())
                    || !row.recollectionVersionId().equals(recollection.versionId())) throw brokenSource();
        } else {
            if (row.recollectionId() != null || row.recollectionVersionId() != null || row.recollectionJson() != null) throw brokenSource();
            answer = new AnswerLink(row.sessionId(), row.questionId(), row.answerId());
        }
        ActionSource source = new ActionSource(row.id(), row.originActionId(), kind, answer,
                row.feedbackId(), row.feedbackRevision(), row.taskId(), row.observationId(),
                row.manualKind() == null ? null : FeedbackKind.valueOf(row.manualKind()), row.authorLabel(),
                row.observationJson() == null ? null : json.readValue(row.observationJson(), EvaluationObservation.class),
                row.decisionJson() == null ? null : json.readValue(row.decisionJson(), Decision.class), row.verification(),
                Tracking.valueOf(row.tracking()), row.trackingReason(), row.createdAt(), row.updatedAt(), recollection);
        requireSourceAccess(owner, source);
        return source;
    }

    private void requireSourceAccess(String owner, ActionSource source) {
        if (source == null || source.kind() == null) throw brokenSource();
        if (source.kind() == SourceKind.RECOLLECTION) {
            RecollectionSource recollection = source.recollection();
            if (recollection == null || source.answer() != null || source.feedbackId() != null || source.feedbackRevision() != null
                    || source.taskId() != null || source.observationId() != null || source.manualKind() != null
                    || source.authorLabel() != null || source.observation() != null || source.decision() != null) throw brokenSource();
            // 读取历史只核对本人确切版本；当前是否可用于新复练由写命令另行判断。
            RecollectionDtos.Version version = recollections.version(owner, recollection.recollectionId(), recollection.versionId());
            if (version.number() != recollection.versionNumber() || version.content().baselineKind() != recollection.baselineKind()) throw brokenSource();
            try { text(recollection.observation(), 5000, true); }
            catch (ApiException exception) { throw new IllegalStateException("已保存回忆观察不符合约束", exception); }
        } else {
            if (source.recollection() != null || source.answer() == null || source.observation() == null) throw brokenSource();
            requireAccess(owner, source.answer());
        }
    }

    private Action remember(String owner, ActionRow row, String change) {
        Action snapshot = actionView(owner, row);
        mapper.insertHistory(new HistoryRow(owner, row.id(), row.revision(), change, bounded(snapshot, 4_194_304), row.updatedAt()));
        return snapshot;
    }

    private void requireAccess(String owner, AnswerLink answer) {
        evaluations.requireAccess(owner, answer.sessionId(), answer.questionId(), answer.answerId());
    }

    private String bounded(Object value, int maximum) {
        String encoded = json.writeValueAsString(value);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > maximum) throw limit("复盘记录超过保存上限，请缩减文字和来源数量。");
        return encoded;
    }

    private static AnswerLink link(FeedbackRow row) { return new AnswerLink(row.sessionId(), row.questionId(), row.answerId()); }
    private static IllegalStateException brokenSource() { return new IllegalStateException("动作来源类型与确切身份不一致"); }
    private static Decision latest(List<Decision> decisions) { return decisions.stream().max(Comparator.comparingInt(Decision::revision)).orElse(null); }
    private static String newId() { return UUID.randomUUID().toString(); }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static void changed(int count) { if (count != 1) throw new IllegalStateException("复盘事务中的受锁记录发生变化"); }
    private static void expect(long expected, long actual) {
        if (expected != actual) throw new ApiException(409, "REVIEW_REVISION_CONFLICT", "记录已变化，请核对最新修订后保留原输入重试。", actual);
    }
    private static void expectDecision(int expected, int actual) {
        if (expected != actual) throw new ApiException(409, "REVIEW_DECISION_CONFLICT", "反馈决定已变化，请核对决定历史后保留原输入重试。", (long) actual);
    }
}
