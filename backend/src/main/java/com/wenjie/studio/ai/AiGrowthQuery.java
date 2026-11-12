package com.wenjie.studio.ai;

import com.wenjie.studio.ai.AiGrowthRows.Candidate;

import com.wenjie.studio.bank.QuestionDtos;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.growth.GrowthActivitySupport;
import com.wenjie.studio.growth.GrowthEvidenceFilter;
import com.wenjie.studio.growth.GrowthRules;
import com.wenjie.studio.training.TrainingDtos;
import com.wenjie.studio.training.TrainingGrowthQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import static com.wenjie.studio.ai.AiDtos.*;
import static com.wenjie.studio.ai.AiGrowthRows.*;
import static com.wenjie.studio.growth.GrowthDtos.*;
import static com.wenjie.studio.growth.GrowthRules.*;

/** 固定 AI 观察的只读目录；不授予评价权限，不调用模型，也不返回报告或原答正文。 */
@Service
@Transactional(propagation = Propagation.MANDATORY, noRollbackFor = RuntimeException.class)
public class AiGrowthQuery {
    private static final EvidenceKind KIND = EvidenceKind.AI_OBSERVATION;
    private static final Set<ActivityMetric> METRICS = Set.of(ActivityMetric.AI_OBSERVATIONS_PUBLISHED);
    private final AiGrowthMapper mapper;
    private final TrainingGrowthQuery training;
    private final GrowthEvidenceFilter filter;
    private final JsonMapper json;

    public AiGrowthQuery(AiGrowthMapper mapper, TrainingGrowthQuery training, GrowthEvidenceFilter filter, JsonMapper json) {
        this.mapper = mapper;
        this.training = training;
        this.filter = filter;
        this.json = json.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    public DomainEvidenceWindow page(String owner, EvidenceKind kind, Scope scope, AssessmentLevel level,
                                      ReviewFlag flag, EvidencePosition after, QueryBudget budget) {
        uuid(owner); requireKind(kind); scope(scope); budget(budget); GrowthRules.position(after, scope, KIND);
        var found = new ArrayList<EvidenceSummary>();
        Scan result = scan(owner, scope, after, budget.candidateLimit(), budget.resultLimit(), level, flag, found::add);
        return new DomainEvidenceWindow(List.copyOf(found), result.scanned(), result.exhausted(), result.scanLimited(), result.last());
    }

    public List<EvidenceResolution> resolve(String owner, List<EvidenceRef> references) {
        uuid(owner); references(references);
        for (EvidenceRef ref : references) requireKind(ref.kind());
        Map<String, TaskRow> rows = tasks(owner, references.stream().map(EvidenceRef::objectId).distinct().toList());
        var projections = new HashMap<String, Projection>();
        var results = new ArrayList<EvidenceResolution>(references.size());
        for (EvidenceRef ref : references) {
            TaskRow row = rows.get(ref.objectId());
            if (row == null || !"ANSWER_EVALUATION".equals(row.purpose()) || !"SUCCEEDED".equals(row.status())) {
                results.add(unavailable(ref));
                continue;
            }
            Projection projection = projections.computeIfAbsent(row.id(), ignored -> project(owner, row));
            EvidenceSummary value = observation(row, projection, ref.partId());
            results.add(value == null ? unavailable(ref) : resolved(value));
        }
        return List.copyOf(results);
    }

    public DomainActivityWindow activity(String owner, Scope recordedScope, Grain grain) {
        uuid(owner); scope(recordedScope); required(grain);
        if (recordedScope.filter().targetMode() != TargetMode.RECORDED_ONLY) throw invalid("AI 活动只归属于评价原答记录的目标。");
        var collector = GrowthActivitySupport.collector(recordedScope, grain, METRICS);
        Scan result = scan(owner, recordedScope, null, MAX_CANDIDATES, Integer.MAX_VALUE, null, null,
                value -> collector.add(ActivityMetric.AI_OBSERVATIONS_PUBLISHED, evidenceKey(value.ref()), value.occurredAt()));
        return collector.finish(result.scanLimited());
    }

    private Scan scan(String owner, Scope scope, EvidencePosition after, int candidateLimit, int resultLimit,
                      AssessmentLevel level, ReviewFlag flag, Consumer<EvidenceSummary> accept) {
        int scanned = 0, found = 0;
        EvidencePosition last = after;
        if (!scope.window().startAt().isBefore(scope.window().observedUntil())) return new Scan(0, true, false, last);
        while (scanned < candidateLimit && found < resultLimit) {
            int consumeLimit = Math.min(49, Math.min(candidateLimit - scanned, resultLimit - found));
            List<Candidate> candidates = candidates(owner, scope, last, consumeLimit + 1);
            int consumeCount = Math.min(consumeLimit, candidates.size());
            if (consumeCount == 0) return new Scan(scanned, true, false, last);
            List<Candidate> consumed = candidates.subList(0, consumeCount);
            Map<String, TaskRow> rows = tasks(owner, consumed.stream().map(Candidate::taskId).distinct().toList());
            var projections = new HashMap<String, Projection>();
            for (Candidate candidate : consumed) {
                TaskRow row = rows.get(candidate.taskId());
                if (row == null || !candidate.occurredAt().equals(row.finishedAt())) throw integrity();
                Projection projection = projections.computeIfAbsent(row.id(), ignored -> project(owner, row));
                EvidenceSummary value = observation(row, projection, candidate.partId());
                if (value == null) throw integrity();
                scanned++;
                last = position(candidate);
                EvidenceSummary matched = filter.match(owner, value, scope, level, flag);
                if (matched != null) { accept.accept(matched); found++; }
            }
            // 额外一项只判断是否还有候选；同一报告的剩余 fN 不计入本页，也不推进游标。
            boolean exhausted = candidates.size() <= consumeCount;
            if (exhausted || found == resultLimit || scanned == candidateLimit) {
                return new Scan(scanned, exhausted, !exhausted && scanned == candidateLimit && found < resultLimit, last);
            }
        }
        throw integrity();
    }

    private List<Candidate> candidates(String owner, Scope scope, EvidencePosition after, int limit) {
        EvidenceRef previousRef = after == null ? null : evidenceKey(after.evidenceKey());
        List<Candidate> rows = mapper.candidates(owner, scope.window().startAt(), scope.window().observedUntil(),
                after == null ? null : after.occurredAt(), previousRef == null ? null : previousRef.objectId(),
                previousRef == null ? null : previousRef.partId(), limit);
        if (rows == null || rows.size() > limit) throw integrity();
        EvidencePosition previous = after;
        try {
            for (Candidate row : rows) {
                required(row); evidence(ref(row.taskId(), row.partId())); time(row.occurredAt());
                if (row.occurredAt().isBefore(scope.window().startAt()) || !row.occurredAt().isBefore(scope.window().observedUntil())) throw integrity();
                EvidencePosition current = position(row);
                if (previous != null && (current.occurredAt().isAfter(previous.occurredAt())
                        || current.occurredAt().equals(previous.occurredAt()) && current.evidenceKey().compareTo(previous.evidenceKey()) <= 0)) throw integrity();
                previous = current;
            }
        } catch (ApiException exception) { throw integrity(); }
        return rows;
    }

    private Map<String, TaskRow> tasks(String owner, List<String> ids) {
        if (ids.isEmpty() || ids.size() > 50) throw integrity();
        List<TaskRow> rows = mapper.tasks(owner, ids);
        if (rows == null || rows.size() > ids.size()) throw integrity();
        var result = new HashMap<String, TaskRow>();
        for (TaskRow row : rows) {
            if (row == null || !owner.equals(row.ownerId()) || !ids.contains(row.id()) || result.put(row.id(), row) != null) throw integrity();
        }
        return result;
    }

    private Projection project(String owner, TaskRow row) {
        try {
            uuid(row.id()); text(row.title(), 200, true); uuid(row.currentAttemptId()); time(row.finishedAt());
            if (!"ANSWER_EVALUATION".equals(row.purpose()) || !"ANSWER_EVALUATION".equals(row.manifestPurpose())
                    || !"SUCCEEDED".equals(row.status()) || !"SUCCEEDED".equals(row.attemptStatus())
                    || !row.currentAttemptId().equals(row.attemptId()) || !owner.equals(row.attemptOwnerId())
                    || !row.id().equals(row.attemptTaskId()) || !row.id().equals(row.bindingTaskId())
                    || !Objects.equals(row.answerId(), row.accessAnswerId()) || row.accessAnswerId() == null) throw integrity();
            EvaluationInput input = read(row.evaluationJson(), EvaluationInput.class);
            EvaluationReport report = read(row.candidateJson(), EvaluationReport.class);
            validateInput(input);
            if (!input.sessionId().equals(row.sessionId()) || !input.questionId().equals(row.questionId())
                    || !input.answerId().equals(row.answerId()) || !input.question().title().equals(row.title())) throw integrity();
            validateReport(report, input);
            EvidenceResolution answer = training.answer(owner, row.sessionId(), row.questionId(), row.answerId());
            resolution(answer);
            InputRow current = mapper.input(owner, row.id());
            if (current == null) throw integrity();
            DirectState inputState = validateCurrent(input, current, answer);
            StandardMeta standard = input.standard() == null ? null : new StandardMeta(
                    new QuestionRef(input.standard().questionId(), input.standard().versionId()),
                    enumValue(input.standard().status(), QuestionDtos.StandardStatus.class));
            var observations = new HashMap<String, ObservationMeta>();
            for (EvaluationObservation item : report.observations()) {
                observations.put(item.id(), new ObservationMeta(item.dimension(), item.coverage()));
            }
            Map<String, DecisionMeta> decisions = decisions(owner, row, observations.keySet());
            return new Projection(answer, current, inputState, standard, Map.copyOf(observations), decisions,
                    hash("AI_GROWTH_INPUT_V1", input), hash("AI_GROWTH_REPORT_V1", report));
        } catch (ApiException exception) { throw integrity(); }
    }

    private DirectState validateCurrent(EvaluationInput input, InputRow current, EvidenceResolution answer) {
        if (current.sessionId() == null || current.questionId() == null || current.answerId() == null
                || answer.summary() == null) return DirectState.UNAVAILABLE;
        AnswerMeta meta = answer.summary().conditions().answer();
        if (!input.sessionId().equals(current.sessionId()) || !input.questionId().equals(current.questionId())
                || !input.answerId().equals(current.answerId()) || current.answerNumber() == null
                || input.answerNumber() != current.answerNumber() || !input.mode().equals(current.mode())
                || !input.feedbackTiming().equals(current.feedbackTiming()) || !input.answerCondition().equals(current.answerCondition())
                || !input.mode().equals(meta.mode().name()) || !input.answerCondition().equals(meta.condition().name())
                || !Boolean.TRUE.equals(current.questionMatches()) || !Boolean.TRUE.equals(current.answerMatches())
                || current.sessionStatus() == null || !Set.of("RUNNING", "PAUSED", "ENDED").contains(current.sessionStatus())) throw integrity();
        var standard = input.standard();
        if (standard == null) {
            if (current.sourceQuestionId() != null || current.sourceVersionId() != null || meta.sourceQuestion() != null
                    || !Boolean.TRUE.equals(current.standardMatches()) || meta.questionKind() == TrainingDtos.QuestionKind.MAIN) throw integrity();
            return DirectState.AVAILABLE;
        }
        var identity = new QuestionRef(standard.questionId(), standard.versionId());
        if (!standard.questionId().equals(current.sourceQuestionId()) || !standard.versionId().equals(current.sourceVersionId())
                || !identity.equals(meta.sourceQuestion()) || meta.questionKind() == TrainingDtos.QuestionKind.FOLLOW_UP) throw integrity();
        if (current.standardVersionId() == null) return DirectState.UNAVAILABLE;
        if (!standard.versionId().equals(current.standardVersionId()) || !Boolean.TRUE.equals(current.standardMatches())) throw integrity();
        return DirectState.AVAILABLE;
    }

    private Map<String, DecisionMeta> decisions(String owner, TaskRow task, Set<String> observations) {
        List<DecisionRow> rows = mapper.decisions(owner, task.id());
        if (rows == null || rows.size() > observations.size()) throw integrity();
        var result = new HashMap<String, DecisionMeta>();
        for (DecisionRow row : rows) {
            if (row == null || !owner.equals(row.ownerId()) || !task.id().equals(row.taskId())
                    || !observations.contains(row.feedbackId()) || result.containsKey(row.feedbackId())) throw integrity();
            uuid(row.id()); range(row.revision(), 1, 20, "AI 反馈决定修订"); time(row.createdAt());
            if (row.createdAt().isBefore(task.finishedAt())) throw integrity();
            var decision = new DecisionMeta(row.id(), row.revision(), required(enumValue(row.choice(), FeedbackChoice.class)), row.createdAt());
            result.put(row.feedbackId(), decision);
        }
        return Map.copyOf(result);
    }

    private EvidenceSummary observation(TaskRow row, Projection projection, String partId) {
        ObservationMeta observation = projection.observations().get(partId);
        if (observation == null || projection.answer().summary() == null) return null;
        EvidenceSummary answer = projection.answer().summary();
        DecisionMeta decision = projection.decisions().get(partId);
        var flags = EnumSet.noneOf(ReviewFlag.class);
        flags.addAll(answer.state().reviewFlags());
        DirectState direct = answer.state().directState();
        if (projection.inputState() == DirectState.UNAVAILABLE) { direct = DirectState.UNAVAILABLE; flags.add(ReviewFlag.SOURCE_UNAVAILABLE); }
        if (decision != null && decision.choice() == FeedbackChoice.DISPUTED) {
            flags.add(ReviewFlag.DISPUTED); flags.add(ReviewFlag.REVIEW_REQUIRED);
            if (direct == DirectState.AVAILABLE) direct = DirectState.REVIEW_REQUIRED;
        }
        var state = new EvidenceState(direct, RecordCurrency.NOT_APPLICABLE, List.copyOf(flags), answer.state().lineageCheck());
        var meta = new AiMeta(answer.conditions().answer(), observation.dimension(), observation.coverage(), decision, projection.standard());
        var conditions = new Conditions(KIND, null, null, meta, null, null, null, null);
        var facets = new Facets(answer.facets().targets(), answer.facets().topics(),
                List.of(new DimensionFacet(observation.dimension(), FacetBasis.RECORDED)), false, null);
        EvidenceRef ref = ref(row.id(), partId);
        String origin = hash("AI_GROWTH_ORIGIN_V1", new Original(ref, row.finishedAt(), row.title(), projection.inputHash(),
                projection.reportHash(), answer.stamp().originHash()));
        // 费用核对和任务 updatedAt 不属于评价发生事实；决定、固定依赖才改变当前摘要。
        String current = hash("AI_GROWTH_CURRENT_V1", new Current(ref, origin, row.status(), row.currentAttemptId(),
                row.attemptStatus(), row.finishedAt(), answer.stamp().currentHash(), projection.current(), decision, state));
        var stamp = new EvidenceStamp(ref, origin, current);
        return summary(new EvidenceSummary(ref, row.finishedAt(), row.title(), RecordKind.AI_FEEDBACK, AssessmentLevel.AI_UNREVIEWED,
                conditions, facets, state, stamp, new EvidenceRoute(EvidenceRouteKind.AI_OBSERVATION, ref,
                row.sessionId(), row.questionId(), row.answerId(), null, null, null)));
    }

    private void validateInput(EvaluationInput input) {
        required(input); uuid(input.sessionId()); uuid(input.questionId()); uuid(input.answerId());
        range(input.answerNumber(), 1, 100, "评价原答版本");
        required(enumValue(input.mode(), TrainingDtos.Mode.class));
        required(enumValue(input.feedbackTiming(), TrainingDtos.FeedbackTiming.class));
        required(enumValue(input.answerCondition(), TrainingDtos.AnswerCondition.class));
        var question = required(input.question()); required(question.type());
        text(question.title(), 200, true); text(question.prompt(), 10_000, true);
        text(question.conditions(), 5000, false); text(question.technology(), 2000, false); text(question.scenario(), 2000, false);
        var answer = required(input.answer()); text(answer.text(), 20_000, true);
        range(required(answer.nodes()).size(), 0, 80, "评价原答节点"); range(required(answer.edges()).size(), 0, 160, "评价原答连线");
        var nodes = new HashSet<String>();
        for (FollowUpNode node : answer.nodes()) {
            required(node); if (!nodes.add(uuid(node.id()))) throw integrity();
            text(node.label(), 200, true); text(node.description(), 5000, false);
        }
        var edges = new HashSet<String>();
        for (FollowUpEdge edge : answer.edges()) {
            required(edge); if (!edges.add(uuid(edge.id())) || nodes.contains(edge.id())
                    || !nodes.contains(edge.source()) || !nodes.contains(edge.target())) throw integrity();
            text(edge.label(), 200, false);
        }
        if (input.standard() != null) {
            var standard = input.standard(); uuid(standard.questionId()); uuid(standard.versionId());
            required(enumValue(standard.status(), QuestionDtos.StandardStatus.class)); text(standard.referenceAnswer(), 20_000, false);
            range(required(standard.checkpoints()).size(), 0, 30, "固定参考检查点");
            var checkpoints = new HashSet<String>();
            for (var item : standard.checkpoints()) {
                required(item); if (!checkpoints.add(uuid(item.id()))) throw integrity(); text(item.text(), 500, true);
            }
        }
    }

    private void validateReport(EvaluationReport report, EvaluationInput input) {
        required(report); if (report.level() != EvaluationLevel.AI_UNREVIEWED) throw integrity();
        text(report.summary(), 2000, true); text(report.limitations(), 2000, true); text(report.validationNote(), 2000, true);
        range(required(report.observations()).size(), 1, 12, "固定评价观察数");
        int ordinal = 0, totalText = report.summary().codePointCount(0, report.summary().length())
                + report.limitations().codePointCount(0, report.limitations().length());
        for (EvaluationObservation item : report.observations()) {
            required(item); if (!("f" + ++ordinal).equals(item.id())) throw integrity();
            required(item.dimension()); required(item.coverage()); text(item.observation(), 2000, true);
            text(item.condition(), 1000, false); text(item.basis(), 1000, true);
            text(item.limitation(), 1000, true); text(item.suggestion(), 1000, true);
            range(required(item.citations()).size(), 0, 6, "固定评价引用数");
            boolean answerEvidence = false;
            var identities = new HashSet<String>();
            for (EvaluationCitation citation : item.citations()) {
                required(citation); required(citation.kind()); text(citation.quote(), 500, true);
                String source = citationSource(citation, input);
                if (source == null || citation.start() < 0 || citation.end() <= citation.start() || citation.end() > source.length()
                        || !source.substring(citation.start(), citation.end()).equals(citation.quote())
                        || !identities.add(citation.kind() + ":" + citation.elementId() + ":" + citation.start() + ":" + citation.end())) throw integrity();
                answerEvidence |= switch (citation.kind()) {
                    case ANSWER_TEXT, ANSWER_NODE_LABEL, ANSWER_NODE_DESCRIPTION, ANSWER_EDGE_LABEL -> true;
                    default -> false;
                };
                totalText += citation.quote().codePointCount(0, citation.quote().length());
            }
            for (String field : List.of(item.observation(), item.condition(), item.basis(), item.limitation(), item.suggestion())) {
                totalText += field.codePointCount(0, field.length());
            }
            if (totalText > 24_000) throw integrity();
            if (item.coverage() == EvaluationCoverage.MENTIONED && !answerEvidence
                    || item.coverage() == EvaluationCoverage.NOT_MENTIONED && (answerEvidence || item.citations().isEmpty())) throw integrity();
        }
    }

    private static String citationSource(EvaluationCitation citation, EvaluationInput input) {
        boolean element = switch (citation.kind()) {
            case ANSWER_NODE_LABEL, ANSWER_NODE_DESCRIPTION, ANSWER_EDGE_LABEL, CHECKPOINT -> true;
            default -> false;
        };
        if (element) uuid(citation.elementId()); else if (citation.elementId() != null) throw integrity();
        return switch (citation.kind()) {
            case ANSWER_TEXT -> input.answer().text();
            case QUESTION_PROMPT -> input.question().prompt();
            case QUESTION_CONDITIONS -> input.question().conditions();
            case REFERENCE -> input.standard() == null ? null : input.standard().referenceAnswer();
            case CHECKPOINT -> input.standard() == null ? null : input.standard().checkpoints().stream()
                    .filter(value -> value.id().equals(citation.elementId())).map(QuestionDtos.Checkpoint::text).findFirst().orElse(null);
            case ANSWER_NODE_LABEL -> input.answer().nodes().stream().filter(value -> value.id().equals(citation.elementId()))
                    .map(FollowUpNode::label).findFirst().orElse(null);
            case ANSWER_NODE_DESCRIPTION -> input.answer().nodes().stream().filter(value -> value.id().equals(citation.elementId()))
                    .map(FollowUpNode::description).findFirst().orElse(null);
            case ANSWER_EDGE_LABEL -> input.answer().edges().stream().filter(value -> value.id().equals(citation.elementId()))
                    .map(FollowUpEdge::label).findFirst().orElse(null);
        };
    }

    private <T> T read(String raw, Class<T> type) {
        try { return required(json.readValue(required(raw), type)); }
        catch (RuntimeException exception) { throw integrity(); }
    }

    private static void requireKind(EvidenceKind kind) { if (kind != KIND) throw invalid("AI 成长查询只接受固定评价观察。"); }
    private static EvidenceRef ref(String taskId, String partId) { return new EvidenceRef(KIND, taskId, null, null, partId); }
    private static EvidencePosition position(Candidate value) { return new EvidencePosition(value.occurredAt(), evidenceKey(ref(value.taskId(), value.partId()))); }
    private static EvidenceResolution resolved(EvidenceSummary value) {
        return resolution(new EvidenceResolution(value.ref(), value, value.state(), value.stamp()));
    }
    private static EvidenceResolution unavailable(EvidenceRef ref) {
        var state = new EvidenceState(DirectState.UNAVAILABLE, RecordCurrency.NOT_APPLICABLE,
                List.of(ReviewFlag.SOURCE_UNAVAILABLE), LineageCheck.NOT_APPLICABLE);
        return resolution(new EvidenceResolution(ref, null, state,
                new EvidenceStamp(ref, null, hash("AI_GROWTH_MISSING_V1", new Missing(ref, state)))));
    }

    private record Scan(int scanned, boolean exhausted, boolean scanLimited, EvidencePosition last) {}
    private record ObservationMeta(QuestionDtos.Dimension dimension, EvaluationCoverage coverage) {}
    private record Projection(EvidenceResolution answer, InputRow current, DirectState inputState, StandardMeta standard,
                              Map<String, ObservationMeta> observations, Map<String, DecisionMeta> decisions,
                              String inputHash, String reportHash) {}
    private record Original(EvidenceRef ref, Instant occurredAt, String title, String inputHash, String reportHash, String answerOriginHash) {}
    private record Current(EvidenceRef ref, String originHash, String status, String attemptId, String attemptStatus,
                           Instant finishedAt, String answerCurrentHash, InputRow input, DecisionMeta decision, EvidenceState state) {}
    private record Missing(EvidenceRef ref, EvidenceState state) {}
}
