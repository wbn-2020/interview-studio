package com.wenjie.studio.training;

import com.wenjie.studio.bank.QuestionDtos;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.growth.GrowthActivitySupport;
import com.wenjie.studio.growth.GrowthEvidenceFilter;
import com.wenjie.studio.growth.GrowthRules;
import com.wenjie.studio.knowledge.KnowledgeDtos;
import com.wenjie.studio.prepare.PreparationDtos;
import com.wenjie.studio.prepare.PreparationSources;
import com.wenjie.studio.review.RepracticeDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.wenjie.studio.growth.GrowthDtos.*;
import static com.wenjie.studio.growth.GrowthRules.*;
import static com.wenjie.studio.training.TrainingGrowthRows.*;

/** 固定答案的成长证据投影，不读取草稿、答案正文或新版题干。 */
@Service
@Transactional(propagation = Propagation.MANDATORY, noRollbackFor = RuntimeException.class)
public class TrainingGrowthQuery {
    private static final EvidenceKind KIND = EvidenceKind.TRAINING_ANSWER;
    private static final Set<ActivityMetric> METRICS = Set.of(ActivityMetric.ANSWERS_SUBMITTED,
            ActivityMetric.QUESTION_INSTANCES_ANSWERED, ActivityMetric.ANSWERS_BEFORE_ASSISTANCE,
            ActivityMetric.ANSWERS_LEARNING, ActivityMetric.ANSWERS_ASSISTED);
    private static final Comparator<SourceIdentity> SOURCE_ORDER = Comparator.comparing(SourceIdentity::kind)
            .thenComparing(SourceIdentity::itemId).thenComparing(SourceIdentity::versionId);
    private static final Comparator<KnowledgeRef> KNOWLEDGE_ORDER = Comparator.comparing(KnowledgeRef::itemId)
            .thenComparing(KnowledgeRef::versionId);
    private final TrainingGrowthMapper mapper;
    private final PreparationSources preparation;
    private final GrowthEvidenceFilter filter;
    private final JsonMapper json;

    public TrainingGrowthQuery(TrainingGrowthMapper mapper, PreparationSources preparation,
                               GrowthEvidenceFilter filter, JsonMapper json) {
        this.mapper = mapper;
        this.preparation = preparation;
        this.filter = filter;
        this.json = json;
    }

    public DomainEvidenceWindow page(String owner, EvidenceKind kind, Scope scope, AssessmentLevel level,
                                      ReviewFlag flag, EvidencePosition after, QueryBudget budget) {
        uuid(owner); requireKind(kind); scope(scope); budget(budget); GrowthRules.position(after, scope, KIND);
        var found = new ArrayList<EvidenceSummary>();
        var cache = new ProjectionCache();
        EvidencePosition last = after;
        int scanned = 0;
        while (scanned < budget.candidateLimit()) {
            int consumeLimit = Math.min(49, Math.min(budget.candidateLimit() - scanned, budget.resultLimit() - found.size()));
            List<Candidate> candidates = candidates(owner, scope, last, consumeLimit + 1);
            if (candidates.isEmpty()) return new DomainEvidenceWindow(List.copyOf(found), scanned, true, false, last);
            List<Candidate> consumed = candidates.subList(0, Math.min(consumeLimit, candidates.size()));
            Map<String, AnswerRow> rows = answers(owner, consumed.stream().map(Candidate::id).toList());
            for (int index = 0; index < consumed.size(); index++) {
                Candidate candidate = candidates.get(index);
                AnswerRow row = rows.get(candidate.id());
                if (row == null || !candidate.occurredAt().equals(row.createdAt())) throw integrity();
                EvidenceSummary value = project(owner, row, cache);
                scanned++;
                last = position(candidate);
                EvidenceSummary matched = filter.match(owner, value, scope, level, flag);
                if (matched != null) found.add(matched);
                // 游标只消耗已核对候选；同一批中后续尚未返回的合格答案必须留给下一页。
                if (found.size() == budget.resultLimit()) {
                    boolean exhausted = index == candidates.size() - 1;
                    return new DomainEvidenceWindow(List.copyOf(found), scanned, exhausted, false, last);
                }
            }
            if (candidates.size() <= consumeLimit) return new DomainEvidenceWindow(List.copyOf(found), scanned, true, false, last);
        }
        return new DomainEvidenceWindow(List.copyOf(found), scanned, false, true, last);
    }

    public List<EvidenceResolution> resolve(String owner, List<EvidenceRef> references) {
        uuid(owner); references(references);
        for (EvidenceRef ref : references) requireKind(ref.kind());
        Map<String, AnswerRow> rows = answers(owner, references.stream().map(EvidenceRef::objectId).toList());
        var cache = new ProjectionCache();
        return references.stream().map(ref -> {
            AnswerRow row = rows.get(ref.objectId());
            return row == null ? unavailable(ref) : resolved(project(owner, row, cache));
        }).toList();
    }

    /** 反馈和复练必须绑定确切三元组，不能仅凭 answerId 继承另一题的条件。 */
    public EvidenceResolution answer(String owner, String sessionId, String questionId, String answerId) {
        uuid(owner); uuid(sessionId); uuid(questionId); uuid(answerId);
        EvidenceRef ref = ref(answerId);
        AnswerRow row = answers(owner, List.of(answerId)).get(answerId);
        if (row == null || !sessionId.equals(row.sessionId()) || !questionId.equals(row.questionId())) return unavailable(ref);
        return resolved(project(owner, row, new ProjectionCache()));
    }

    /** 复练开始可以早于首答；活动归属只能来自当时固定的会话和题目。 */
    public Facets recordedFacets(String owner, String sessionId, String questionId) {
        uuid(owner); uuid(sessionId); uuid(questionId);
        QuestionRow row = mapper.question(owner, sessionId, questionId);
        return row == null ? null : question(owner, sessionId, questionId, row).facets();
    }

    public DomainActivityWindow activity(String owner, Scope recordedScope, Grain grain) {
        uuid(owner); scope(recordedScope); required(grain);
        if (recordedScope.filter().targetMode() != TargetMode.RECORDED_ONLY) throw invalid("活动只按原记录目标归属统计。");
        var collector = GrowthActivitySupport.collector(recordedScope, grain, METRICS);
        var cache = new ProjectionCache();
        EvidencePosition last = null;
        int scanned = 0;
        boolean exhausted = false;
        while (scanned < MAX_CANDIDATES) {
            int consumeLimit = Math.min(49, MAX_CANDIDATES - scanned);
            List<Candidate> candidates = candidates(owner, recordedScope, last, consumeLimit + 1);
            if (candidates.isEmpty()) { exhausted = true; break; }
            List<Candidate> consumed = candidates.subList(0, Math.min(consumeLimit, candidates.size()));
            Map<String, AnswerRow> rows = answers(owner, consumed.stream().map(Candidate::id).toList());
            for (Candidate candidate : consumed) {
                AnswerRow row = rows.get(candidate.id());
                if (row == null || !candidate.occurredAt().equals(row.createdAt())) throw integrity();
                EvidenceSummary value = project(owner, row, cache);
                scanned++;
                last = position(candidate);
                if (filter.match(owner, value, recordedScope, null, null) == null) continue;
                AnswerMeta meta = value.conditions().answer();
                collector.add(ActivityMetric.ANSWERS_SUBMITTED, meta.answerId(), value.occurredAt());
                collector.add(ActivityMetric.QUESTION_INSTANCES_ANSWERED,
                        meta.sessionId() + ":" + meta.questionId(), value.occurredAt());
                ActivityMetric conditionMetric = switch (meta.condition()) {
                    case BEFORE_ASSISTANCE -> ActivityMetric.ANSWERS_BEFORE_ASSISTANCE;
                    case LEARNING -> ActivityMetric.ANSWERS_LEARNING;
                    case ASSISTED -> ActivityMetric.ANSWERS_ASSISTED;
                };
                collector.add(conditionMetric, meta.answerId(), value.occurredAt());
            }
            if (candidates.size() <= consumeLimit) { exhausted = true; break; }
        }
        // 达到预算仍不知道是否读尽时只能报告已观察下界，不能伪装为精确零或完整总数。
        return collector.finish(!exhausted);
    }

    private List<Candidate> candidates(String owner, Scope scope, EvidencePosition after, int limit) {
        String afterId = after == null ? null : evidenceKey(after.evidenceKey()).objectId();
        List<Candidate> rows = mapper.candidates(owner, scope.window().startAt(), scope.window().observedUntil(),
                after == null ? null : after.occurredAt(), afterId, limit);
        if (rows == null || rows.size() > limit) throw integrity();
        EvidencePosition previous = after;
        try {
            for (Candidate row : rows) {
                required(row); uuid(row.id()); time(row.occurredAt());
                if (row.occurredAt().isBefore(scope.window().startAt()) || !row.occurredAt().isBefore(scope.window().observedUntil())) throw integrity();
                EvidencePosition current = position(row);
                if (previous != null && (current.occurredAt().isAfter(previous.occurredAt())
                        || current.occurredAt().equals(previous.occurredAt()) && current.evidenceKey().compareTo(previous.evidenceKey()) <= 0)) throw integrity();
                previous = current;
            }
        } catch (ApiException exception) { throw integrity(); }
        return rows;
    }

    private Map<String, AnswerRow> answers(String owner, List<String> ids) {
        if (ids.isEmpty() || ids.size() > 50) throw integrity();
        List<AnswerRow> rows = mapper.answers(owner, ids);
        if (rows == null || rows.size() > ids.size()) throw integrity();
        var found = new HashMap<String, AnswerRow>();
        for (AnswerRow row : rows) {
            if (row == null || !owner.equals(row.ownerId()) || !ids.contains(row.id()) || found.put(row.id(), row) != null) throw integrity();
        }
        return found;
    }

    private EvidenceSummary project(String owner, AnswerRow row, ProjectionCache cache) {
        try {
            uuid(row.id()); uuid(row.sessionId()); uuid(row.questionId()); time(row.createdAt());
            range(row.versionNumber(), 1, 100, "固定答案版本"); range(row.assistanceEventSequence(), 0, 2000, "作答辅助事件");
            hashText(row.contentHash());
            String questionKey = row.sessionId() + ":" + row.questionId();
            QuestionMeta question = cache.questions.get(questionKey);
            if (question == null) {
                QuestionRow source = mapper.question(owner, row.sessionId(), row.questionId());
                if (source == null) throw integrity();
                question = question(owner, row.sessionId(), row.questionId(), source);
                cache.questions.put(questionKey, question);
            }
            TrainingDtos.AnswerCondition condition = enumValue(row.condition(), TrainingDtos.AnswerCondition.class);
            if (condition == null || condition == TrainingDtos.AnswerCondition.ASSISTED && row.assistanceEventSequence() == 0) throw integrity();
            Exposure exposure = exposure(row.exposureJson());
            var answer = new AnswerMeta(row.sessionId(), row.questionId(), row.id(), question.mode(), question.kind(),
                    condition, row.assistanceEventSequence(), exposure, question.source(), question.repractice(), question.fields());
            var conditions = new Conditions(KIND, answer, null, null, null, null, null, null);
            List<SourceIdentity> answerSources = answerSources(row.referencesJson());
            List<SourceIdentity> preparationRefs = java.util.stream.Stream.concat(question.context().stream(), answerSources.stream())
                    .filter(value -> !"KNOWLEDGE".equals(value.kind())).distinct().sorted(SOURCE_ORDER).toList();
            var preparationChecks = new ArrayList<PreparationDtos.SourceCheck>();
            DirectState direct = question.direct();
            var flags = EnumSet.noneOf(ReviewFlag.class);
            if (direct == DirectState.REVIEW_REQUIRED) flags.add(ReviewFlag.REVIEW_REQUIRED);
            boolean partial = question.kind() != TrainingDtos.QuestionKind.MAIN;
            for (SourceIdentity source : preparationRefs) {
                PreparationDtos.SourceCheck checked = cache.preparation.get(source);
                if (checked == null) {
                    checked = preparation.check(owner, PreparationDtos.Kind.valueOf(source.kind()), source.itemId(), source.versionId());
                    validate(source, checked);
                    cache.preparation.put(source, checked);
                }
                preparationChecks.add(checked);
                if (checked.state() == PreparationDtos.SourceState.UNAVAILABLE) direct = combine(direct, DirectState.UNAVAILABLE);
                else if (checked.state() != PreparationDtos.SourceState.CURRENT
                        && !(checked.kind() == PreparationDtos.Kind.TARGET && checked.state() == PreparationDtos.SourceState.SUPERSEDED)) {
                    direct = combine(direct, DirectState.REVIEW_REQUIRED);
                    flags.add(ReviewFlag.REVIEW_REQUIRED);
                }
                if (checked.kind() == PreparationDtos.Kind.FACT) partial = true;
            }
            List<KnowledgeRef> knowledgeRefs = java.util.stream.Stream.concat(question.knowledge().stream(), answerSources.stream()
                    .filter(value -> "KNOWLEDGE".equals(value.kind())).map(value -> new KnowledgeRef(value.itemId(), value.versionId())))
                    .distinct().sorted(KNOWLEDGE_ORDER).toList();
            List<KnowledgeRow> knowledge = knowledge(owner, knowledgeRefs, cache);
            for (KnowledgeRow source : knowledge) {
                if (source.kind() == null || source.status() == null || source.fixedVersionId() == null
                        || source.currentVersionId() == null || source.currentVersionExistsId() == null) direct = combine(direct, DirectState.UNAVAILABLE);
                else if (source.bodyChanged() == null) direct = combine(direct, DirectState.UNKNOWN);
                else if (KnowledgeDtos.Status.ARCHIVED.name().equals(source.status()) || source.bodyChanged()) {
                    direct = combine(direct, DirectState.REVIEW_REQUIRED);
                    flags.add(ReviewFlag.REVIEW_REQUIRED);
                }
            }
            partial |= !knowledge.isEmpty();
            if (direct == DirectState.UNAVAILABLE) flags.add(ReviewFlag.SOURCE_UNAVAILABLE);
            EvidenceState state = new EvidenceState(direct, RecordCurrency.NOT_APPLICABLE, List.copyOf(flags),
                    partial ? LineageCheck.PARTIAL : LineageCheck.NOT_APPLICABLE);
            EvidenceRef ref = ref(row.id());
            String original = hash("TRAINING_GROWTH_ORIGIN_V1", new Original(ref, row.createdAt(), row.versionNumber(),
                    question.title(), new LegacyConditions(KIND, answer, null, null, null, null, null),
                    question.facets(), question.context(), question.knowledge(), answerSources, row.contentHash()));
            String current = hash("TRAINING_GROWTH_CURRENT_V1", new Current(row.sessionId(), question.generation(),
                    question.head(), List.copyOf(preparationChecks), knowledge.stream().map(value ->
                    new KnowledgeCheck(value.itemId(), value.versionId(), value.currentVersionId(), value.status(), value.bodyChanged())).toList(), state));
            var stamp = new EvidenceStamp(ref, original, current);
            return summary(new EvidenceSummary(ref, row.createdAt(), question.title(), RecordKind.ANSWER,
                    AssessmentLevel.SELF_RECORDED, conditions, question.facets(), state, stamp,
                    new EvidenceRoute(EvidenceRouteKind.TRAINING_ANSWER, ref, row.sessionId(), row.questionId(), row.id(), null, null, null)));
        } catch (ApiException exception) { throw integrity(); }
    }

    private QuestionMeta question(String owner, String sessionId, String questionId, QuestionRow row) {
        try {
            if (!owner.equals(row.ownerId()) || !owner.equals(row.sessionOwnerId()) || !sessionId.equals(row.sessionId())
                    || !sessionId.equals(row.parentSessionId()) || !questionId.equals(row.id())) throw integrity();
            long generation = revision(row.generation());
            TrainingDtos.Mode mode = required(enumValue(row.mode(), TrainingDtos.Mode.class));
            TrainingDtos.QuestionKind kind = required(enumValue(row.questionKind(), TrainingDtos.QuestionKind.class));
            JsonNode value = object(row.metadataJson());
            if (!"OBJECT".equals(string(value, "rootType")) || !"OBJECT".equals(string(value, "contentType"))) throw integrity();
            String sourceId = nullableString(value, "sourceQuestionId"), versionId = nullableString(value, "sourceVersionId");
            if (!Objects.equals(row.sourceQuestionId(), sourceId) || !Objects.equals(row.sourceVersionId(), versionId)
                    || (sourceId == null) != (versionId == null)) throw integrity();
            QuestionRef source = sourceId == null ? null : GrowthRules.question(new QuestionRef(sourceId, versionId));
            if (kind == TrainingDtos.QuestionKind.MAIN && source == null
                    || kind == TrainingDtos.QuestionKind.FOLLOW_UP && source != null) throw integrity();
            String title = text(string(value, "title"), 200, true);
            List<TopicFacet> topics = new ArrayList<>();
            Set<String> topicIds = new HashSet<>();
            for (JsonNode topic : array(value, "topicIds", 20)) {
                if (!topic.isTextual() || !topicIds.add(uuid(topic.stringValue()))) throw integrity();
                topics.add(new TopicFacet(topic.stringValue(), FacetBasis.RECORDED));
            }
            topics.sort(Comparator.comparing(TopicFacet::topicId));
            var dimensions = new ArrayList<DimensionFacet>();
            var dimensionIds = EnumSet.noneOf(QuestionDtos.Dimension.class);
            for (JsonNode dimension : array(value, "dimensions", 6)) {
                if (!dimension.isTextual()) throw integrity();
                QuestionDtos.Dimension parsed = required(enumValue(dimension.stringValue(), QuestionDtos.Dimension.class));
                if (!dimensionIds.add(parsed)) throw integrity();
                dimensions.add(new DimensionFacet(parsed, FacetBasis.RECORDED));
            }
            if (dimensions.isEmpty()) throw integrity();
            dimensions.sort(Comparator.comparing(facet -> facet.dimension().name()));
            var knowledge = new ArrayList<KnowledgeRef>();
            var known = new HashSet<KnowledgeRef>();
            for (JsonNode link : array(value, "knowledgeLinks", 20)) {
                if (!link.isObject() || link.size() != 2) throw integrity();
                var reference = new KnowledgeRef(uuid(string(link, "itemId")), uuid(string(link, "versionId")));
                if (!known.add(reference)) throw integrity();
                knowledge.add(reference);
            }
            knowledge.sort(KNOWLEDGE_ORDER);
            var fields = new RecordedFields(recorded(value, "technology"), recorded(value, "scenario"), recorded(value, "conditions"));
            JsonNode context = object(row.contextJson());
            if (!"ARRAY".equals(string(context, "rootType"))) throw integrity();
            var contexts = new ArrayList<SourceIdentity>();
            var contextIds = new HashSet<String>();
            var targets = new ArrayList<TargetFacet>();
            for (JsonNode link : array(context, "references", 12)) {
                SourceIdentity identity = source(link, true);
                if (!contextIds.add(identity.itemId())) throw integrity();
                contexts.add(identity);
                if ("TARGET".equals(identity.kind())) targets.add(new TargetFacet(new TargetRef(identity.itemId(), identity.versionId()), FacetBasis.RECORDED));
            }
            contexts.sort(SOURCE_ORDER);
            targets.sort(Comparator.comparing(facet -> facet.target().itemId()));
            var facets = new Facets(List.copyOf(targets), List.copyOf(topics), List.copyOf(dimensions), false, null);
            RepracticeContext repractice = null;
            if (row.repracticeId() != null) repractice = new RepracticeContext(uuid(row.repracticeId()),
                    required(enumValue(row.repracticeQuestionMode(), RepracticeDtos.QuestionMode.class)),
                    required(enumValue(row.repracticeBaselineKind(), RepracticeDtos.BaselineKind.class)));
            else if (row.repracticeQuestionMode() != null || row.repracticeBaselineKind() != null) throw integrity();
            DirectState direct = DirectState.AVAILABLE;
            QuestionHead head = null;
            if (source != null) {
                if (row.sourceHeadId() != null && !source.questionId().equals(row.sourceHeadId())) throw integrity();
                if (row.sourceStatus() != null) enumValue(row.sourceStatus(), QuestionDtos.Status.class);
                if (row.sourceCurrentVersionId() != null) uuid(row.sourceCurrentVersionId());
                if (row.sourceFixedVersionId() != null && !source.versionId().equals(row.sourceFixedVersionId())) throw integrity();
                if (row.sourceCurrentVersionExistsId() != null && !row.sourceCurrentVersionExistsId().equals(row.sourceCurrentVersionId())) throw integrity();
                head = new QuestionHead(source.questionId(), row.sourceStatus(), row.sourceCurrentVersionId());
                if (row.sourceHeadId() == null || row.sourceStatus() == null || row.sourceFixedVersionId() == null
                        || row.sourceCurrentVersionId() == null || row.sourceCurrentVersionExistsId() == null) direct = DirectState.UNAVAILABLE;
                else if (QuestionDtos.Status.ARCHIVED.name().equals(row.sourceStatus())) direct = DirectState.REVIEW_REQUIRED;
                // 普通题库升级只改变当前核对摘要，不改写原答，也不自动把旧题判成错误。
            } else if (row.sourceHeadId() != null || row.sourceStatus() != null || row.sourceCurrentVersionId() != null
                    || row.sourceFixedVersionId() != null || row.sourceCurrentVersionExistsId() != null) throw integrity();
            return new QuestionMeta(title, source, mode, kind, generation, repractice, fields, facets,
                    List.copyOf(contexts), List.copyOf(knowledge), head, direct);
        } catch (ApiException exception) { throw integrity(); }
    }

    private Exposure exposure(String raw) {
        JsonNode value = object(raw);
        long authored = integer(value, "authoredCount"), referenceViews = integer(value, "referenceViewCount");
        JsonNode evaluation = value.get("evaluationAccessCount");
        long evaluations = evaluation == null || evaluation.isNull() ? 0 : integer(value, "evaluationAccessCount");
        String last = nullableString(value, "lastExposureAt");
        Instant lastAt;
        try { lastAt = last == null ? null : Instant.parse(last); }
        catch (RuntimeException exception) { throw integrity(); }
        if (lastAt != null) time(lastAt);
        return new Exposure(authored, referenceViews, evaluations, lastAt);
    }

    private List<SourceIdentity> answerSources(String raw) {
        JsonNode value = object(raw);
        if (!"OBJECT".equals(string(value, "rootType")) || !"STRING".equals(string(value, "textType"))) throw integrity();
        String diagramType = nullableString(value, "diagramType");
        if (diagramType != null && !"NULL".equals(diagramType)) {
            if (!"OBJECT".equals(diagramType) || !"ARRAY".equals(string(value, "nodesType"))) throw integrity();
            range(integer(value, "nodeCount"), 0, 80, "答案图节点数量");
        }
        var references = new HashSet<SourceIdentity>();
        for (JsonNode link : array(value, "references", 80)) references.add(source(link, false));
        return references.stream().sorted(SOURCE_ORDER).toList();
    }

    private SourceIdentity source(JsonNode value, boolean context) {
        if (!value.isObject() || !"OBJECT".equals(string(value, "type"))) throw integrity();
        String kind = string(value, "kind");
        if (context) required(enumValue(kind, TrainingDtos.ContextKind.class));
        else required(enumValue(kind, TrainingDtos.LinkKind.class));
        return new SourceIdentity(kind, uuid(string(value, "itemId")), uuid(string(value, "versionId")));
    }

    private List<KnowledgeRow> knowledge(String owner, List<KnowledgeRef> references, ProjectionCache cache) {
        List<KnowledgeRef> missing = references.stream().filter(value -> !cache.knowledge.containsKey(value)).toList();
        for (int offset = 0; offset < missing.size(); offset += 50) {
            List<KnowledgeRef> batch = missing.subList(offset, Math.min(offset + 50, missing.size()));
            List<KnowledgeRow> rows = mapper.knowledge(owner, batch);
            if (rows == null || rows.size() != batch.size()) throw integrity();
            for (KnowledgeRow row : rows) {
                if (row == null) throw integrity();
                var ref = new KnowledgeRef(row.itemId(), row.versionId());
                if (!batch.contains(ref) || cache.knowledge.containsKey(ref)) throw integrity();
                if (row.kind() != null) enumValue(row.kind(), KnowledgeDtos.Kind.class);
                if (row.status() != null) enumValue(row.status(), KnowledgeDtos.Status.class);
                if (row.currentVersionId() != null) uuid(row.currentVersionId());
                if (row.fixedVersionId() != null && !row.versionId().equals(row.fixedVersionId())) throw integrity();
                if (row.currentVersionExistsId() != null && !row.currentVersionExistsId().equals(row.currentVersionId())) throw integrity();
                cache.knowledge.put(ref, row);
            }
        }
        return references.stream().map(cache.knowledge::get).toList();
    }

    private static void validate(SourceIdentity source, PreparationDtos.SourceCheck check) {
        if (check == null || check.kind() == null || !source.kind().equals(check.kind().name())
                || !source.itemId().equals(check.itemId()) || !source.versionId().equals(check.versionId()) || check.state() == null) throw integrity();
        if (check.sourceGeneration() != null) revision(check.sourceGeneration());
        if (check.projectGeneration() != null) revision(check.projectGeneration());
        if (check.state() != PreparationDtos.SourceState.UNAVAILABLE && (check.sourceGeneration() == null
                || check.kind() == PreparationDtos.Kind.FACT && check.projectGeneration() == null)) throw integrity();
        if (check.kind() != PreparationDtos.Kind.FACT && check.projectGeneration() != null) throw integrity();
    }

    private JsonNode object(String raw) {
        if (raw == null) throw integrity();
        try {
            JsonNode value = json.readTree(raw);
            if (value == null || !value.isObject()) throw integrity();
            return value;
        } catch (RuntimeException exception) { throw integrity(); }
    }
    private static JsonNode array(JsonNode value, String key, int maximum) {
        JsonNode field = value.get(key);
        if (field == null || !field.isArray() || field.size() > maximum) throw integrity();
        return field;
    }
    private static String string(JsonNode value, String key) {
        JsonNode field = value.get(key);
        if (field == null || !field.isTextual()) throw integrity();
        return field.stringValue();
    }
    private static String nullableString(JsonNode value, String key) {
        JsonNode field = value.get(key);
        if (field == null || field.isNull()) return null;
        if (!field.isTextual()) throw integrity();
        return field.stringValue();
    }
    private static long integer(JsonNode value, String key) {
        JsonNode field = value.get(key);
        if (field == null || !field.isIntegralNumber() || !field.canConvertToLong()) throw integrity();
        return revision(field.longValue());
    }
    private static boolean recorded(JsonNode value, String key) {
        if (!"STRING".equals(string(value, key + "Type"))) throw integrity();
        long present = integer(value, key);
        if (present > 1) throw integrity();
        return present == 1;
    }
    private static DirectState combine(DirectState left, DirectState right) {
        if (left == DirectState.UNAVAILABLE || right == DirectState.UNAVAILABLE) return DirectState.UNAVAILABLE;
        if (left == DirectState.UNKNOWN || right == DirectState.UNKNOWN) return DirectState.UNKNOWN;
        if (left == DirectState.REVIEW_REQUIRED || right == DirectState.REVIEW_REQUIRED) return DirectState.REVIEW_REQUIRED;
        return DirectState.AVAILABLE;
    }
    private static void requireKind(EvidenceKind kind) { if (kind != KIND) throw invalid("训练成长查询只接受固定回答证据。"); }
    private static EvidenceRef ref(String id) { return new EvidenceRef(KIND, id, null, null, null); }
    private static EvidencePosition position(Candidate row) { return new EvidencePosition(row.occurredAt(), evidenceKey(ref(row.id()))); }
    private static EvidenceResolution resolved(EvidenceSummary value) {
        return resolution(new EvidenceResolution(value.ref(), value, value.state(), value.stamp()));
    }
    private static EvidenceResolution unavailable(EvidenceRef ref) {
        var state = new EvidenceState(DirectState.UNAVAILABLE, RecordCurrency.NOT_APPLICABLE,
                List.of(ReviewFlag.SOURCE_UNAVAILABLE), LineageCheck.NOT_APPLICABLE);
        return resolution(new EvidenceResolution(ref, null, state,
                new EvidenceStamp(ref, null, hash("TRAINING_GROWTH_MISSING_V1", new Missing(ref, state)))));
    }

    private record SourceIdentity(String kind, String itemId, String versionId) {}
    private record QuestionHead(String questionId, String status, String currentVersionId) {}
    private record KnowledgeCheck(String itemId, String versionId, String currentVersionId, String status, Boolean bodyChanged) {}
    private record QuestionMeta(String title, QuestionRef source, TrainingDtos.Mode mode, TrainingDtos.QuestionKind kind,
                                long generation, RepracticeContext repractice, RecordedFields fields, Facets facets,
                                List<SourceIdentity> context, List<KnowledgeRef> knowledge, QuestionHead head, DirectState direct) {}
    // V1 摘要固定旧条件字段，公开载荷扩展不能改变既有证据的原始摘要。
    private record LegacyConditions(EvidenceKind kind, AnswerMeta answer, ManualMeta manual, AiMeta ai,
                                    RecollectionMeta recollection, ReplayMeta replay, MemoryMeta memory) {}
    private record Original(EvidenceRef ref, Instant occurredAt, int version, String title, LegacyConditions conditions,
                            Facets facets, List<SourceIdentity> context, List<KnowledgeRef> questionKnowledge,
                            List<SourceIdentity> answerSources, String answerContentHash) {}
    private record Current(String sessionId, long generation, QuestionHead sourceQuestion,
                           List<PreparationDtos.SourceCheck> preparation, List<KnowledgeCheck> knowledge, EvidenceState state) {}
    private record Missing(EvidenceRef ref, EvidenceState state) {}
    private static final class ProjectionCache {
        private final Map<String, QuestionMeta> questions = new HashMap<>();
        private final Map<SourceIdentity, PreparationDtos.SourceCheck> preparation = new HashMap<>();
        private final Map<KnowledgeRef, KnowledgeRow> knowledge = new HashMap<>();
    }
}
