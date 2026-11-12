package com.wenjie.studio.ai;

import com.wenjie.studio.bank.QuestionDtos;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.lifecycle.KnowledgeRevalidationQuery;
import com.wenjie.studio.material.MaterialLifecycleService;
import com.wenjie.studio.prepare.PreparationDtos;
import com.wenjie.studio.prepare.PreparationSources;
import com.wenjie.studio.review.ReviewDraftSources;
import com.wenjie.studio.review.ReviewDtos;
import com.wenjie.studio.review.StandardReviewRules;
import com.wenjie.studio.search.UnifiedSearchDtos;
import com.wenjie.studio.search.UnifiedSearchSources;
import com.wenjie.studio.training.TrainingDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.wenjie.studio.ai.SourceQaDtos.*;
import static com.wenjie.studio.search.UnifiedSearchDtos.Domain;

@Service
@Transactional(propagation = Propagation.MANDATORY, readOnly = true, noRollbackFor = RuntimeException.class)
public class SourceQaSources {
    private static final long MAX_REVISION = 9_007_199_254_740_991L;
    private static final Set<String> NORMAL_CHANGES = Set.of("AI_SOURCE_CHANGED",
            "SEARCH_SOURCE_NOT_FOUND", "SEARCH_SOURCE_CHANGED", "MATERIALS_DISABLED",
            "AI_EVALUATION_ACCESS_REQUIRED", "AI_EARLY_FEEDBACK_CONFIRMATION_REQUIRED", "AI_REVIEW_SOURCE_NOT_FOUND");
    private final UnifiedSearchSources sources;
    private final PreparationSources preparation;
    private final ReviewDraftSources reviewAccess;
    private final RelationAiFeedbackSources aiAccess;
    private final SourceQaMapper mapper;
    private final KnowledgeRevalidationQuery revalidation;
    private final MaterialLifecycleService materialLifecycle;
    private final JsonMapper canonical;

    public SourceQaSources(UnifiedSearchSources sources, PreparationSources preparation, ReviewDraftSources reviewAccess,
                           RelationAiFeedbackSources aiAccess, SourceQaMapper mapper, JsonMapper json,
                            KnowledgeRevalidationQuery revalidation, MaterialLifecycleService materialLifecycle) {
        this.sources = sources;
        this.preparation = preparation;
        this.reviewAccess = reviewAccess;
        this.aiAccess = aiAccess;
        this.mapper = mapper;
        this.revalidation = revalidation;
        this.materialLifecycle = materialLifecycle;
        canonical = json.rebuild().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    }

    public Input capture(String owner, PreviewRequest request) {
        requestId(owner);
        String question = request(request);
        var selected = new ArrayList<Source>();
        var checks = new ArrayList<Check>();
        for (Selection selection : request.selections()) {
            Ref ref = selection.source();
            UnifiedSearchDtos.Source original = sources.read(owner, ref.domain(), ref.id(), ref.versionId(),
                    ref.revision(), ref.feedbackId(), ref.reportHash());
            var hit = original.item();
            if (hit.domain() != ref.domain() || !ref.id().equals(hit.id()) || !ref.versionId().equals(hit.versionId())
                    || ref.domain() == Domain.REVIEW_FEEDBACK && !ref.revision().equals(hit.versionNumber())) throw broken();
            String key = "s" + (selected.size() + 1);
            Check check = check(owner, ref, hit, key);
            var fields = new HashMap<String, UnifiedSearchDtos.TextField>();
            for (var field : original.fields()) if (field == null || fields.put(field.id(), field) != null) throw broken();
            var fragments = new ArrayList<Fragment>();
            for (Range range : selection.ranges()) {
                var field = fields.get(range.field());
                if (field == null || field.text() == null || range.end() > field.text().length()
                        || split(field.text(), range.start()) || split(field.text(), range.end())) throw invalid();
                fragments.add(new Fragment(field.id(), field.label(), range.start(), range.end(),
                        field.text().substring(range.start(), range.end())));
            }
            Source source = source(owner, key, ref, hit, List.copyOf(fragments), fields);
            // 合法旧报告的 NUL 不能被清洗，也不能误报为旧数据损坏；此用途明确拒绝外发。
            if (texts(source).stream().anyMatch(text -> text != null && (!unicode(text) || text.indexOf('\0') >= 0))) throw unavailable();
            selected.add(source);
            checks.add(check);
        }
        Input input = new Input(question, List.copyOf(selected), List.copyOf(checks));
        if (!valid(input)) throw broken();
        return input;
    }

    public static Projection projection(Input input) {
        if (!valid(input)) throw broken();
        return new Projection(input.question(), input.sources());
    }

    public boolean current(String owner, Input input) {
        return current(owner, input, null);
    }

    public boolean current(String owner, Input input, Instant frozenAt) {
        requestId(owner);
        if (!valid(input)) throw broken();
        var selections = input.sources().stream().map(source -> new Selection(source.ref(), source.fragments().stream()
                .map(fragment -> new Range(fragment.field(), fragment.start(), fragment.end())).toList())).toList();
        try {
            // 动态最新提交标志和会话状态不进入快照；固定条件、限定和代次才参与新外发核对。
            Input rebuilt = capture(owner, new PreviewRequest(input.question(), selections));
            for (int index = 0; index < input.sources().size(); index++) {
                Source fixed = input.sources().get(index), latest = rebuilt.sources().get(index);
                if (!fixed.equals(withFixedDecision(latest, fixed))) throw broken();
                requireForward(input.checks().get(index), rebuilt.checks().get(index));
                if (fixed.ref().domain() == Domain.KNOWLEDGE && frozenAt != null
                        && revalidation.changedAfter(owner, fixed.ref().id(), fixed.ref().versionId(), frozenAt)) return false;
            }
            return input.equals(rebuilt);
        } catch (ApiException exception) {
            if (NORMAL_CHANGES.contains(exception.code())) return false;
            // 已固定合法清单不应在同一版本突然出现非法原文或未知责任，不能降为普通状态过期。
            if ("INVALID_REQUEST".equals(exception.code()) || "AI_SOURCE_UNAVAILABLE".equals(exception.code())) throw broken();
            throw exception;
        }
    }

    public void requireSelectionAccess(String owner, PreviewRequest request) {
        requestId(owner); request(request);
        for (Selection selection : request.selections()) requireAccess(owner, selection.source(), null);
    }

    public void requireAccess(String owner, Input input) {
        requestId(owner);
        if (!valid(input)) throw broken();
        for (Source source : input.sources()) requireAccess(owner, source.ref(), source.training() == null ? null : source.training().answer());
    }

    public void requireTaskAccess(String owner, String taskId) {
        requestId(owner); requestId(taskId);
        if (!"SOURCE_QA".equals(mapper.purpose(owner, taskId))) throw broken();
        List<SourceQaRows.Binding> rows = mapper.bindings(owner, taskId);
        if (rows.isEmpty() || rows.size() > 5) throw broken();
        var seen = new HashSet<Ref>();
        int total = 0;
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            Ref ref = bindingRef(owner, taskId, index + 1, row);
            if (!seen.add(ref)) throw broken();
            total += row.fragmentCount();
            ReviewDtos.AnswerLink answer = row.answerId() == null ? null
                    : new ReviewDtos.AnswerLink(row.sessionId(), row.questionId(), row.answerId());
            requireAccess(owner, ref, answer);
        }
        if (total > 8) throw broken();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void save(String owner, String taskId, Input input) {
        requestId(owner); requestId(taskId);
        if (!valid(input) || !"SOURCE_QA".equals(mapper.purpose(owner, taskId)) || !mapper.bindings(owner, taskId).isEmpty()) throw broken();
        for (int index = 0; index < input.sources().size(); index++) mapper.insert(binding(owner, taskId, index + 1,
                input.sources().get(index), input.checks().get(index)));
        requireBinding(owner, taskId, input);
    }

    public void requireBinding(String owner, String taskId, Input input) {
        requestId(owner); requestId(taskId);
        String purpose = mapper.purpose(owner, taskId);
        List<SourceQaRows.Binding> rows = mapper.bindings(owner, taskId);
        if (input == null) {
            if ("SOURCE_QA".equals(purpose) || !rows.isEmpty()) throw broken();
            return;
        }
        if (!valid(input) || !"SOURCE_QA".equals(purpose) || rows.size() != input.sources().size()) throw broken();
        for (int index = 0; index < rows.size(); index++) {
            if (!rows.get(index).equals(binding(owner, taskId, index + 1, input.sources().get(index), input.checks().get(index)))) throw broken();
        }
    }

    private Check check(String owner, Ref ref, UnifiedSearchDtos.Hit hit, String key) {
        Long metadata = null, revision = null;
        PreparationDtos.SourceCheck preparationCheck = null;
        PreparationDtos.ExpressionCheck expressionCheck = null;
        switch (ref.domain()) {
            case KNOWLEDGE, QUESTION, RECOLLECTION -> {
                var stamp = mapper.stamp(owner, ref.domain().name(), ref.id());
                if (stamp == null) throw changed();
                if (!owner.equals(stamp.ownerId()) || !ref.id().equals(stamp.id()) || !safe(stamp.revision())) throw broken();
                if (!"ACTIVE".equals(stamp.status()) || !ref.versionId().equals(stamp.currentVersionId())
                        || !hit.currentVersion() || !"ACTIVE".equals(hit.status())) throw changed();
                // 后台会捕获普通过期并保留费用结算；布尔查询避免被捕获异常把外层事务标为回滚。
                if (ref.domain() == Domain.KNOWLEDGE && revalidation.pending(owner, ref.id(), ref.versionId())) throw changed();
                if (ref.domain() == Domain.RECOLLECTION) revision = stamp.revision();
                else metadata = stamp.revision();
            }
            case PREPARATION -> {
                PreparationDtos.Kind kind;
                try { kind = PreparationDtos.Kind.valueOf(hit.kind()); }
                catch (IllegalArgumentException | NullPointerException exception) { throw broken(); }
                if (!preparation.aiAllowed(owner, kind, ref.id(), ref.versionId())) throw changed();
                if (kind == PreparationDtos.Kind.EXPRESSION) {
                    expressionCheck = preparation.checkExpression(owner, ref.id(), ref.versionId());
                    if (expressionCheck == null) throw broken();
                    requirePreparation(expressionCheck.expression(), PreparationDtos.Kind.EXPRESSION, ref);
                    if (expressionCheck.factChecks() == null || expressionCheck.factChecks().isEmpty() || expressionCheck.factChecks().size() > 50) throw broken();
                    var versions = new HashSet<String>();
                    for (var basis : expressionCheck.factChecks()) {
                        requirePreparation(basis, PreparationDtos.Kind.FACT, null);
                        if (!versions.add(basis.versionId())) throw broken();
                    }
                } else {
                    preparationCheck = preparation.check(owner, kind, ref.id(), ref.versionId());
                    requirePreparation(preparationCheck, kind, ref);
                }
                if (!hit.currentVersion() || !"ACTIVE".equals(hit.status())) throw changed();
            }
            case MATERIAL_EXCERPT -> {
                if (!materialLifecycle.aiAllowed(owner, ref.id())) throw changed();
            }
            case REVIEW_FEEDBACK -> { if (!hit.currentVersion()) throw changed(); }
            default -> { }
        }
        return new Check(key, metadata, revision, preparationCheck, expressionCheck);
    }

    private Source source(String owner, String key, Ref ref, UnifiedSearchDtos.Hit hit, List<Fragment> fragments,
                          java.util.Map<String, UnifiedSearchDtos.TextField> fields) {
        TrainingContext training = hit.training() == null ? null : new TrainingContext(hit.training().answer(),
                hit.training().feedbackTiming(), hit.training().conditions());
        FeedbackContext feedback = null;
        if (hit.feedback() != null) {
            var value = hit.feedback(); var decision = value.decision();
            feedback = new FeedbackContext(value.kind(), value.authorLabel(), value.dimension(), value.coverage(),
                    value.condition(), value.basis(), value.limitation(), decision == null ? 0 : decision.revision(),
                    decision == null ? null : decision.choice());
        }
        QuestionContext question = ref.domain() == Domain.QUESTION
                ? new QuestionContext(field(fields, "conditions"), field(fields, "technology"), field(fields, "scenario")) : null;
        FactContext fact = null;
        if (ref.domain() == Domain.PREPARATION && "FACT".equals(hit.kind())) {
            var value = mapper.fact(owner, ref.id(), ref.versionId());
            if (value == null || value.responsibility() == null
                    || !Set.of("OWNER", "PARTICIPANT", "FAMILIAR", "UNVERIFIED").contains(value.responsibility())) throw broken();
            if ("UNVERIFIED".equals(value.responsibility())) throw unavailable();
            fact = new FactContext(value.responsibility(), hit.verification(), hit.projectStatus());
        }
        ExpressionContext expression = ref.domain() == Domain.PREPARATION && "EXPRESSION".equals(hit.kind()) ? new ExpressionContext(true) : null;
        MaterialContext material = null;
        if (ref.domain() == Domain.MATERIAL_EXCERPT) {
            var value = mapper.material(owner, ref.id());
            if (value == null || !Objects.equals(hit.attachmentId(), value.attachmentId()) || !Objects.equals(hit.runId(), value.runId())) throw broken();
            material = new MaterialContext(value.attachmentId(), value.origin(), value.sha256(), value.runId(), value.segmentId(),
                    value.locator(), value.startOffset(), value.endOffset());
        }
        return new Source(key, ref, hit.kind(), hit.title(), hit.versionNumber(), fragments, training, feedback,
                hit.recollection(), hit.aiFeedback(), question, fact, expression, material);
    }

    private void requireAccess(String owner, Ref ref, ReviewDtos.AnswerLink expected) {
        if (!validRef(ref)) throw invalid();
        if (ref.domain() == Domain.TRAINING_ANSWER || ref.domain() == Domain.REVIEW_FEEDBACK) {
            var row = mapper.answer(owner, ref);
            if (row == null) throw missing();
            var answer = new ReviewDtos.AnswerLink(row.sessionId(), row.questionId(), row.answerId());
            if (!owner.equals(row.ownerId()) || !answer(answer) || expected != null && !expected.equals(answer)) throw broken();
            if (ref.domain() == Domain.REVIEW_FEEDBACK) reviewAccess.requireAnswerAccess(owner, answer);
        } else if (ref.domain() == Domain.AI_FEEDBACK) {
            // 仅元数据与原答许可；原键恢复不能因决定推进而重读或重建外发正文。
            ReviewDtos.AnswerLink answer = expected;
            if (answer == null) {
                var row = mapper.answer(owner, ref);
                if (row == null) throw missing();
                answer = new ReviewDtos.AnswerLink(row.sessionId(), row.questionId(), row.answerId());
                if (!owner.equals(row.ownerId()) || !answer(answer)) throw broken();
            }
            aiAccess.requireReadAccess(owner, ref.id(), ref.versionId(), answer);
        } else if (mapper.exists(owner, ref) != 1) throw missing();
    }

    private SourceQaRows.Binding binding(String owner, String taskId, int ordinal, Source source, Check check) {
        var ref = source.ref(); var answer = source.training() == null ? null : source.training().answer();
        Integer revision = null;
        if (source.feedback() != null) revision = source.feedback().decisionRevision();
        else if (source.aiFeedback() != null) revision = source.aiFeedback().decisionRevision();
        AiDtos.FeedbackChoice choice = source.feedback() != null ? source.feedback().decisionChoice()
                : source.aiFeedback() != null ? source.aiFeedback().decisionChoice() : null;
        return new SourceQaRows.Binding(owner, taskId, ordinal, ref.domain().name(), ref.id(), ref.versionId(),
                ref.revision(), ref.feedbackId(), ref.reportHash(), answer == null ? null : answer.sessionId(),
                answer == null ? null : answer.questionId(), answer == null ? null : answer.answerId(),
                revision == null || revision == 0 ? null : revision, choice == null ? null : choice.name(),
                source.fragments().size(), hash(new Fixed(source, check)));
    }

    private static Ref bindingRef(String owner, String taskId, int ordinal, SourceQaRows.Binding row) {
        if (row == null || !owner.equals(row.ownerId()) || !taskId.equals(row.taskId()) || ordinal != row.ordinal()
                || !hash(row.snapshotHash()) || row.fragmentCount() < 1 || row.fragmentCount() > 8) throw broken();
        Domain domain;
        try { domain = Domain.valueOf(row.domain()); }
        catch (IllegalArgumentException | NullPointerException exception) { throw broken(); }
        Ref ref = new Ref(domain, row.sourceId(), row.versionId(), row.feedbackRevision(), row.feedbackId(), row.reportHash());
        if (!validRef(ref)) throw broken();
        boolean training = Set.of(Domain.TRAINING_ANSWER, Domain.REVIEW_FEEDBACK, Domain.AI_FEEDBACK).contains(domain);
        if (training ? !answer(new ReviewDtos.AnswerLink(row.sessionId(), row.questionId(), row.answerId()))
                : row.sessionId() != null || row.questionId() != null || row.answerId() != null) throw broken();
        boolean feedback = domain == Domain.REVIEW_FEEDBACK || domain == Domain.AI_FEEDBACK;
        if (!feedback && (row.decisionRevision() != null || row.decisionChoice() != null)
                || row.decisionRevision() == null != (row.decisionChoice() == null)) throw broken();
        if (row.decisionRevision() != null && (row.decisionRevision() < 1 || row.decisionRevision() > 20
                || !Set.of("ACCEPTED", "DISPUTED", "IGNORED").contains(row.decisionChoice()))) throw broken();
        return ref;
    }

    public static boolean valid(Input input) {
        if (input == null || !question(input.question()) || !input.question().equals(input.question().strip())
                || input.sources() == null || input.sources().isEmpty() || input.sources().size() > 5
                || input.checks() == null || input.checks().size() != input.sources().size()) return false;
        var refs = new HashSet<Ref>();
        int total = 0;
        for (int index = 0; index < input.sources().size(); index++) {
            Source source = input.sources().get(index); Check check = input.checks().get(index);
            if (!validSource(source) || !("s" + (index + 1)).equals(source.sourceKey()) || !refs.add(source.ref())
                    || !validCheck(source, check)) return false;
            total += source.fragments().size();
        }
        return total <= 8;
    }

    private static boolean validSource(Source value) {
        if (value == null || !validRef(value.ref()) || !points(value.title(), 200, true) || value.kind() == null
                || value.fragments() == null || value.fragments().isEmpty() || value.fragments().size() > 8) return false;
        Domain domain = value.ref().domain();
        if (domain == Domain.AI_FEEDBACK || domain == Domain.MATERIAL_EXCERPT) {
            if (value.versionNumber() != null) return false;
        } else if (value.versionNumber() == null || value.versionNumber() < 1) return false;
        for (int index = 0; index < value.fragments().size(); index++) {
            Fragment fragment = value.fragments().get(index);
            if (fragment == null || !fieldAllowed(domain, value.kind(), fragment.field()) || !text(fragment.label(), 200, true)
                    || fragment.start() < 0 || fragment.end() <= fragment.start() || !text(fragment.text(), 8000, false)
                    || (long) fragment.end() - fragment.start() != fragment.text().length()) return false;
            String complete = completeField(value, fragment.field());
            if (complete != null && (fragment.end() > complete.length() || split(complete, fragment.start())
                    || split(complete, fragment.end()) || !fragment.text().equals(complete.substring(fragment.start(), fragment.end())))) return false;
            for (int earlier = 0; earlier < index; earlier++) {
                Fragment previous = value.fragments().get(earlier);
                if (fragment.field().equals(previous.field()) && fragment.start() < previous.end() && previous.start() < fragment.end()) return false;
            }
        }
        int contexts = (value.training() == null ? 0 : 1) + (value.feedback() == null ? 0 : 1)
                + (value.recollection() == null ? 0 : 1) + (value.aiFeedback() == null ? 0 : 1)
                + (value.question() == null ? 0 : 1) + (value.fact() == null ? 0 : 1)
                + (value.expression() == null ? 0 : 1) + (value.material() == null ? 0 : 1);
        return switch (domain) {
            case KNOWLEDGE -> contexts == 0 && Set.of("NOTE", "CONCEPT").contains(value.kind());
            case PREPARATION -> switch (value.kind()) {
                case "TARGET", "PROJECT" -> contexts == 0;
                case "FACT" -> contexts == 1 && value.fact() != null
                        && value.fact().responsibility() != null && Set.of("OWNER", "PARTICIPANT", "FAMILIAR").contains(value.fact().responsibility())
                        && "CONFIRMED".equals(value.fact().verification()) && "ACTIVE".equals(value.fact().projectStatus());
                case "EXPRESSION" -> contexts == 1 && value.expression() != null && value.expression().basisReviewed();
                default -> false;
            };
            case QUESTION -> contexts == 1 && value.question() != null && questionKind(value.kind())
                    && points(value.question().conditions(), 5000, false) && points(value.question().technology(), 2000, false)
                    && points(value.question().scenario(), 2000, false);
            case MATERIAL_EXCERPT -> contexts == 1 && "EXCERPT".equals(value.kind()) && material(value.material());
            case TRAINING_ANSWER -> contexts == 1 && "ANSWER".equals(value.kind()) && training(value.training())
                    && value.ref().id().equals(value.training().answer().answerId())
                    && value.versionNumber() == value.training().conditions().answerNumber();
            case REVIEW_FEEDBACK -> contexts == 2 && training(value.training()) && feedback(value.feedback())
                    && value.kind().equals(value.feedback().kind().name()) && value.ref().revision().equals(value.versionNumber());
            case RECOLLECTION -> contexts == 1 && "RECOLLECTION".equals(value.kind()) && value.recollection() != null
                    && value.versionNumber() <= 1000
                    && value.recollection().baselineKind() != null && value.recollection().questionType() != null
                    && points(value.recollection().uncertainty(), 2000, true)
                    && (value.recollection().baselineKind() != com.wenjie.studio.review.RecollectionDtos.BaselineKind.NONE
                        || value.fragments().stream().noneMatch(fragment -> "answerFragment".equals(fragment.field())));
            case AI_FEEDBACK -> contexts == 2 && "AI_FEEDBACK".equals(value.kind()) && training(value.training())
                    && ai(value.aiFeedback(), value.ref());
        };
    }

    private static boolean validCheck(Source source, Check check) {
        if (check == null || !source.sourceKey().equals(check.sourceKey())) return false;
        Domain domain = source.ref().domain();
        if (domain == Domain.KNOWLEDGE || domain == Domain.QUESTION) return safe(check.metadataRevision())
                && check.sourceRevision() == null && check.preparation() == null && check.expression() == null;
        if (domain == Domain.RECOLLECTION) return check.metadataRevision() == null && safe(check.sourceRevision())
                && check.sourceRevision() <= 1000 && source.versionNumber() <= check.sourceRevision() + 1
                && check.preparation() == null && check.expression() == null;
        if (check.metadataRevision() != null || check.sourceRevision() != null) return false;
        if (domain != Domain.PREPARATION) return check.preparation() == null && check.expression() == null;
        if ("EXPRESSION".equals(source.kind())) return check.preparation() == null && expressionCurrent(check.expression())
                && same(source.ref(), check.expression().expression());
        return check.expression() == null && preparationCurrent(check.preparation()) && same(source.ref(), check.preparation())
                && source.kind().equals(check.preparation().kind().name());
    }

    private static boolean training(TrainingContext value) {
        if (value == null || !answer(value.answer()) || value.feedbackTiming() == null || value.conditions() == null) return false;
        var condition = value.conditions(); var exposure = condition.priorExposure();
        if (condition.mode() == null || condition.answerCondition() == null || condition.questionKind() == null
                || condition.answerNumber() < 1 || condition.answerNumber() > 100 || !time(condition.answerCreatedAt())
                || condition.assistanceEventSequence() < 0 || condition.assistanceEventSequence() > 2000
                || exposure == null || !safe(exposure.authoredCount()) || !safe(exposure.referenceViewCount())
                || !safe(exposure.evaluationAccessCount()) || exposure.lastExposureAt() != null && !time(exposure.lastExposureAt())
                || !points(condition.technology(), 2000, false) || !points(condition.scenario(), 2000, false)
                || !points(condition.questionConditions(), 5000, false)) return false;
        return (condition.mode() == TrainingDtos.Mode.LEARNING) == (condition.answerCondition() == TrainingDtos.AnswerCondition.LEARNING)
                && (condition.answerCondition() != TrainingDtos.AnswerCondition.ASSISTED || condition.assistanceEventSequence() > 0)
                && (condition.answerCondition() != TrainingDtos.AnswerCondition.BEFORE_ASSISTANCE || condition.assistanceEventSequence() == 0);
    }

    private static boolean feedback(FeedbackContext value) {
        return value != null && value.kind() != null && value.dimension() != null && value.coverage() != null
                && points(value.authorLabel(), 120, value.kind() == ReviewDtos.FeedbackKind.EXTERNAL_FEEDBACK)
                && (value.kind() != ReviewDtos.FeedbackKind.SELF_CHECK || value.authorLabel().isEmpty())
                && points(value.condition(), 1000, false) && points(value.basis(), 1000, true) && points(value.limitation(), 1000, true)
                && decision(value.decisionRevision(), value.decisionChoice());
    }

    private static boolean ai(UnifiedSearchDtos.AiFeedbackContext value, Ref ref) {
        return value != null && ref.feedbackId().equals(value.feedbackId()) && ref.reportHash().equals(value.reportHash())
                && value.level() == AiDtos.EvaluationLevel.AI_UNREVIEWED && points(value.reportLimitations(), 2000, true)
                && ModelGateway.EVALUATION_VALIDATION_NOTE.equals(value.validationNote()) && value.dimension() != null && value.coverage() != null
                && points(value.condition(), 1000, false) && points(value.basis(), 1000, true) && points(value.limitation(), 1000, true)
                && decision(value.decisionRevision(), value.decisionChoice());
    }

    private static boolean material(MaterialContext value) {
        if (value == null || !uuid(value.attachmentId()) || !hash(value.sha256()) || !text(value.locator(), 500, true)) return false;
        if ("MANUAL".equals(value.origin())) return value.runId() == null && value.segmentId() == null && value.startOffset() == null && value.endOffset() == null;
        return "PARSED".equals(value.origin()) && uuid(value.runId()) && uuid(value.segmentId())
                && value.startOffset() != null && value.endOffset() != null && value.startOffset() >= 0 && value.endOffset() > value.startOffset();
    }

    private static boolean preparationCurrent(PreparationDtos.SourceCheck value) {
        return value != null && value.kind() != null && uuid(value.itemId()) && uuid(value.versionId())
                && value.state() == PreparationDtos.SourceState.CURRENT && safe(value.sourceGeneration())
                && (value.kind() == PreparationDtos.Kind.FACT ? safe(value.projectGeneration()) : value.projectGeneration() == null);
    }

    private static void requirePreparation(PreparationDtos.SourceCheck value, PreparationDtos.Kind kind, Ref ref) {
        if (value == null || value.kind() != kind || !uuid(value.itemId()) || !uuid(value.versionId()) || value.state() == null
                || ref != null && !same(ref, value)) throw broken();
        if (value.sourceGeneration() != null && !safe(value.sourceGeneration())
                || value.projectGeneration() != null && !safe(value.projectGeneration())) throw broken();
        if (value.state() != PreparationDtos.SourceState.CURRENT) throw changed();
        if (!preparationCurrent(value)) throw broken();
    }

    private static boolean expressionCurrent(PreparationDtos.ExpressionCheck value) {
        if (value == null || !preparationCurrent(value.expression()) || value.expression().kind() != PreparationDtos.Kind.EXPRESSION
                || value.factChecks() == null || value.factChecks().isEmpty() || value.factChecks().size() > 50) return false;
        var seen = new HashSet<String>();
        return value.factChecks().stream().allMatch(check -> preparationCurrent(check) && check.kind() == PreparationDtos.Kind.FACT && seen.add(check.versionId()));
    }

    private static boolean same(Ref ref, PreparationDtos.SourceCheck check) { return ref.id().equals(check.itemId()) && ref.versionId().equals(check.versionId()); }
    private static boolean decision(int revision, AiDtos.FeedbackChoice choice) { return revision >= 0 && revision <= 20 && (revision == 0) == (choice == null); }

    private static Source withFixedDecision(Source latest, Source fixed) {
        FeedbackContext feedback = latest.feedback();
        UnifiedSearchDtos.AiFeedbackContext ai = latest.aiFeedback();
        if (feedback != null) {
            var previous = fixed.feedback();
            forwardDecision(previous.decisionRevision(), previous.decisionChoice(), feedback.decisionRevision(), feedback.decisionChoice());
            feedback = new FeedbackContext(feedback.kind(), feedback.authorLabel(), feedback.dimension(), feedback.coverage(),
                    feedback.condition(), feedback.basis(), feedback.limitation(), previous.decisionRevision(), previous.decisionChoice());
        }
        if (ai != null) {
            var previous = fixed.aiFeedback();
            forwardDecision(previous.decisionRevision(), previous.decisionChoice(), ai.decisionRevision(), ai.decisionChoice());
            ai = new UnifiedSearchDtos.AiFeedbackContext(ai.feedbackId(), ai.reportHash(), ai.level(), ai.reportLimitations(), ai.validationNote(),
                    ai.dimension(), ai.coverage(), ai.condition(), ai.basis(), ai.limitation(), previous.decisionRevision(), previous.decisionChoice());
        }
        return new Source(latest.sourceKey(), latest.ref(), latest.kind(), latest.title(), latest.versionNumber(), latest.fragments(),
                latest.training(), feedback, latest.recollection(), ai, latest.question(), latest.fact(), latest.expression(), latest.material());
    }

    private static void forwardDecision(int before, AiDtos.FeedbackChoice oldChoice, int after, AiDtos.FeedbackChoice choice) {
        if (after < before || after == before && oldChoice != choice) throw broken();
    }

    private static void requireForward(Check fixed, Check latest) {
        forward(fixed.metadataRevision(), latest.metadataRevision());
        forward(fixed.sourceRevision(), latest.sourceRevision());
        forward(fixed.preparation(), latest.preparation());
        if (fixed.expression() != null) {
            if (latest.expression() == null || fixed.expression().factChecks().size() != latest.expression().factChecks().size()) throw broken();
            forward(fixed.expression().expression(), latest.expression().expression());
            for (int index = 0; index < fixed.expression().factChecks().size(); index++) {
                forward(fixed.expression().factChecks().get(index), latest.expression().factChecks().get(index));
            }
        } else if (latest.expression() != null) throw broken();
    }

    private static void forward(PreparationDtos.SourceCheck fixed, PreparationDtos.SourceCheck latest) {
        if (fixed == null) { if (latest != null) throw broken(); return; }
        if (latest == null || fixed.kind() != latest.kind() || !fixed.itemId().equals(latest.itemId()) || !fixed.versionId().equals(latest.versionId())) throw broken();
        forward(fixed.sourceGeneration(), latest.sourceGeneration()); forward(fixed.projectGeneration(), latest.projectGeneration());
    }

    private static void forward(Long fixed, Long latest) {
        if (fixed == null ? latest != null : latest == null || latest < fixed) throw broken();
    }

    private static String completeField(Source source, String field) {
        if ("title".equals(field)) return source.title();
        if (source.question() != null) return switch (field) {
            case "conditions" -> source.question().conditions(); case "technology" -> source.question().technology();
            case "scenario" -> source.question().scenario(); default -> null;
        };
        if (source.feedback() != null) return switch (field) {
            case "condition" -> source.feedback().condition(); case "basis" -> source.feedback().basis();
            case "limitation" -> source.feedback().limitation(); default -> null;
        };
        if (source.aiFeedback() != null) return switch (field) {
            case "condition" -> source.aiFeedback().condition(); case "basis" -> source.aiFeedback().basis();
            case "limitation" -> source.aiFeedback().limitation(); default -> null;
        };
        if (source.recollection() != null && "uncertainty".equals(field)) return source.recollection().uncertainty();
        if (source.material() != null && "locator".equals(field)) return source.material().locator();
        return null;
    }

    private static String request(PreviewRequest request) {
        if (request == null || request.question() == null) throw invalid();
        String question = request.question().strip();
        if (!question(question) || request.selections() == null || request.selections().isEmpty() || request.selections().size() > 5) throw invalid();
        var seen = new HashSet<Ref>();
        int total = 0;
        for (Selection selected : request.selections()) {
            if (selected == null || !validRef(selected.source()) || !seen.add(selected.source()) || selected.ranges() == null
                    || selected.ranges().isEmpty() || selected.ranges().size() > 8) throw invalid();
            total += selected.ranges().size();
            for (int index = 0; index < selected.ranges().size(); index++) {
                Range range = selected.ranges().get(index);
                if (range == null || range.field() == null || !range.field().matches("[A-Za-z][A-Za-z0-9.]{0,99}")
                        || range.start() < 0 || range.end() <= range.start() || (long) range.end() - range.start() > 8000) throw invalid();
                for (int previous = 0; previous < index; previous++) {
                    Range other = selected.ranges().get(previous);
                    if (other.field().equals(range.field()) && range.start() < other.end() && other.start() < range.end()) throw invalid();
                }
            }
        }
        if (total > 8) throw invalid();
        return question;
    }

    private static boolean validRef(Ref ref) {
        if (ref == null || ref.domain() == null || !uuid(ref.id()) || !uuid(ref.versionId())) return false;
        if (ref.domain() == Domain.REVIEW_FEEDBACK) return ref.id().equals(ref.versionId()) && ref.revision() != null
                && ref.revision() >= 1 && ref.revision() <= 100 && ref.feedbackId() == null && ref.reportHash() == null;
        if (ref.revision() != null) return false;
        if (ref.domain() == Domain.AI_FEEDBACK) return ref.feedbackId() != null && ref.feedbackId().matches("f([1-9]|1[0-2])") && hash(ref.reportHash());
        return ref.feedbackId() == null && ref.reportHash() == null
                && (!(ref.domain() == Domain.MATERIAL_EXCERPT || ref.domain() == Domain.TRAINING_ANSWER) || ref.id().equals(ref.versionId()));
    }

    private static boolean fieldAllowed(Domain domain, String kind, String field) {
        if (field == null) return false;
        return switch (domain) {
            case KNOWLEDGE -> Set.of("title", "body").contains(field);
            case PREPARATION -> switch (kind) {
                case "TARGET" -> Set.of("title", "company", "jd").contains(field) || field.matches("requirements\\.([0-9]|[1-4][0-9])\\.text");
                case "PROJECT" -> Set.of("title", "overview", "technology").contains(field);
                case "FACT" -> Set.of("title", "scenario", "problem", "action", "result", "technology").contains(field) || tag(field);
                case "EXPRESSION" -> Set.of("title", "body").contains(field) || tag(field);
                default -> false;
            };
            case QUESTION -> Set.of("title", "prompt", "conditions", "technology", "scenario").contains(field) || tag(field);
            case MATERIAL_EXCERPT -> Set.of("locator", "text").contains(field);
            case TRAINING_ANSWER -> "text".equals(field) || field.matches("diagram\\.nodes\\.([0-9]|[1-7][0-9])\\.(label|description)")
                    || field.matches("diagram\\.edges\\.([0-9]|[1-9][0-9]|1[0-5][0-9])\\.label");
            case REVIEW_FEEDBACK, AI_FEEDBACK -> Set.of("observation", "condition", "basis", "limitation", "suggestion").contains(field);
            case RECOLLECTION -> Set.of("title", "question", "conditions", "answerFragment", "feedback", "uncertainty", "gap").contains(field);
        };
    }

    private static List<String> texts(Source source) {
        var values = new ArrayList<String>();
        values.add(source.title());
        for (Fragment fragment : source.fragments()) { values.add(fragment.label()); values.add(fragment.text()); }
        if (source.training() != null && source.training().conditions() != null) {
            var conditions = source.training().conditions(); values.add(conditions.technology()); values.add(conditions.scenario()); values.add(conditions.questionConditions());
        }
        if (source.feedback() != null) { var value = source.feedback(); values.add(value.authorLabel()); values.add(value.condition()); values.add(value.basis()); values.add(value.limitation()); }
        if (source.aiFeedback() != null) { var value = source.aiFeedback(); values.add(value.condition()); values.add(value.basis()); values.add(value.limitation()); values.add(value.reportLimitations()); values.add(value.validationNote()); }
        if (source.recollection() != null) values.add(source.recollection().uncertainty());
        if (source.question() != null) { values.add(source.question().conditions()); values.add(source.question().technology()); values.add(source.question().scenario()); }
        if (source.material() != null) values.add(source.material().locator());
        return values;
    }

    private static String field(java.util.Map<String, UnifiedSearchDtos.TextField> fields, String key) {
        var field = fields.get(key); if (field == null || field.text() == null) throw broken(); return field.text();
    }
    private String hash(Fixed fixed) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.writeValueAsBytes(fixed))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("缺少 SHA-256", exception); }
    }
    private static boolean question(String value) { return text(value, 2000, true); }
    private static boolean questionKind(String value) { try { QuestionDtos.Type.valueOf(value); return true; } catch (IllegalArgumentException exception) { return false; } }
    private static boolean tag(String field) { return field.matches("tags\\.([0-9]|1[0-9])"); }
    private static boolean answer(ReviewDtos.AnswerLink value) { return value != null && uuid(value.sessionId()) && uuid(value.questionId()) && uuid(value.answerId()); }
    private static boolean text(String value, int limit, boolean required) { return unicode(value) && value.indexOf('\0') < 0 && value.length() <= limit && (!required || !value.isBlank()); }
    private static boolean points(String value, int limit, boolean required) { return unicode(value) && value.indexOf('\0') < 0 && value.codePointCount(0, value.length()) <= limit && (!required || !value.isBlank()); }
    private static boolean unicode(String value) { return value != null && value.codePoints().noneMatch(point -> point >= 0xD800 && point <= 0xDFFF); }
    private static boolean safe(Long value) { return value != null && value >= 0 && value <= MAX_REVISION; }
    private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static boolean time(Instant value) { try { StandardReviewRules.time(value); return true; } catch (ApiException exception) { return false; } }
    private static boolean split(String value, int index) { return index > 0 && index < value.length() && Character.isHighSurrogate(value.charAt(index - 1)) && Character.isLowSurrogate(value.charAt(index)); }
    private static boolean uuid(String value) { try { return value != null && UUID.fromString(value).toString().equals(value); } catch (IllegalArgumentException exception) { return false; } }
    private static void requestId(String value) { if (!uuid(value)) throw invalid(); }
    private static ApiException invalid() { return new ApiException(400, "INVALID_REQUEST", "请检查问题、确切来源身份和原文字段选区。"); }
    private static ApiException changed() { return new ApiException(409, "AI_SOURCE_CHANGED", "所选来源的版本、状态、核实或依据已变化，请重新选择并预览。"); }
    private static ApiException missing() { return new ApiException(404, "SEARCH_SOURCE_NOT_FOUND", "确切来源不存在或不可访问。"); }
    private static ApiException unavailable() { return new ApiException(422, "AI_SOURCE_UNAVAILABLE", "所选原文或必要限定不满足本次问答外发条件；系统不会清洗或改写来源。"); }
    private static ApiException broken() { return new ApiException(409, "SOURCE_QA_SOURCE_INVALID", "问答的固定来源或绑定完整性无法确认。"); }
    private record Fixed(Source source, Check check) { }
}
