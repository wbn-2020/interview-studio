package com.wenjie.studio.memory;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.growth.GrowthActivitySupport;
import com.wenjie.studio.growth.GrowthEvidenceFilter;
import com.wenjie.studio.growth.GrowthRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import static com.wenjie.studio.growth.GrowthDtos.*;
import static com.wenjie.studio.memory.MemoryAttemptDtos.*;
import static com.wenjie.studio.memory.MemoryReviewDtos.*;

/** 不可变 RATE 是成长事实；当前卡片资格仅决定能否继续把这份自评用作依据。 */
@Service
@Transactional(propagation = Propagation.MANDATORY, noRollbackFor = RuntimeException.class)
public class MemoryGrowthQuery {
    private static final Set<ActivityMetric> METRICS = Set.of(ActivityMetric.MEMORY_RATINGS,
            ActivityMetric.MEMORY_CARDS_RATED, ActivityMetric.MEMORY_COUNTED_RATINGS,
            ActivityMetric.MEMORY_UNCOUNTED_RATINGS);
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16)
                            .maxStringLength(128 * 1024).maxNumberLength(32).build()).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    private final MemoryGrowthMapper mapper;
    private final MemoryAttemptMapper attempts;
    private final GrowthEvidenceFilter filter;

    public MemoryGrowthQuery(MemoryGrowthMapper mapper, MemoryAttemptMapper attempts, GrowthEvidenceFilter filter) {
        this.mapper = mapper; this.attempts = attempts; this.filter = filter;
    }

    public DomainEvidenceWindow page(String owner, EvidenceKind kind, Scope scope, AssessmentLevel level,
                                     ReviewFlag flag, EvidencePosition after, QueryBudget budget) {
        GrowthRules.uuid(owner); kind(kind); GrowthRules.scope(scope); GrowthRules.budget(budget);
        GrowthRules.position(after, scope, kind);
        List<EvidenceSummary> items = new ArrayList<>();
        int scanned = 0; boolean exhausted = false; EvidencePosition consumed = after;
        while (scanned < budget.candidateLimit() && items.size() < budget.resultLimit()) {
            int take = Math.min(49, Math.min(budget.candidateLimit() - scanned, budget.resultLimit() - items.size()));
            var candidates = candidates(owner, scope, consumed, take);
            int size = Math.min(take, candidates.size());
            if (size == 0) { exhausted = true; break; }
            List<String> ids = candidates.subList(0, size).stream().map(MemoryGrowthRows.Candidate::id).toList();
            Map<String, EvidenceResolution> resolved = read(owner, ids, selectedTopic(scope));
            int processed = 0;
            for (int i = 0; i < size && items.size() < budget.resultLimit(); i++) {
                var candidate = candidates.get(i); var value = resolved.get(candidate.id());
                // 同一一致快照中候选必须仍可解析；故障不能被过滤成“没有评分”。
                if (value == null || value.summary() == null
                        || !candidate.occurredAt().equals(value.summary().occurredAt())) throw GrowthRules.integrity();
                EvidenceSummary matched = filter.match(owner, value.summary(), scope, level, flag);
                if (matched != null) items.add(matched);
                scanned++; processed++;
                consumed = position(candidate);
            }
            exhausted = processed == size && candidates.size() <= take;
            if (exhausted) break;
        }
        return bounded(new DomainEvidenceWindow(List.copyOf(items), scanned, exhausted,
                !exhausted && scanned == budget.candidateLimit(), consumed), GrowthRules.PAGE_BYTES);
    }

    public List<EvidenceResolution> resolve(String owner, List<EvidenceRef> references) {
        GrowthRules.uuid(owner); GrowthRules.references(references);
        references.forEach(value -> kind(value.kind()));
        var found = read(owner, references.stream().map(EvidenceRef::objectId).toList(), null);
        var result = references.stream().map(ref -> found.containsKey(ref.objectId()) ? found.get(ref.objectId()) : unavailable(ref)).toList();
        return bounded(result, GrowthRules.PAGE_BYTES);
    }

    public DomainActivityWindow activity(String owner, Scope recordedScope, Grain grain) {
        GrowthRules.uuid(owner); GrowthRules.scope(recordedScope); GrowthRules.required(grain);
        var collector = GrowthActivitySupport.collector(recordedScope, grain, METRICS);
        // 卡片没有原生目标或考察项。用户后来建立的证据关联不能迁移已经发生的练习活动。
        if (recordedScope.filter().target() != null || recordedScope.filter().dimension() != null
                || (recordedScope.filter().topicId() != null && recordedScope.filter().topicMode() == TopicMode.RECORDED_ONLY)) {
            return collector.finish(false);
        }
        int scanned = 0; boolean exhausted = false; EvidencePosition consumed = null;
        while (scanned < GrowthRules.MAX_CANDIDATES) {
            int take = Math.min(49, GrowthRules.MAX_CANDIDATES - scanned);
            var candidates = candidates(owner, recordedScope, consumed, take);
            int size = Math.min(take, candidates.size());
            if (size == 0) { exhausted = true; break; }
            var resolved = read(owner, candidates.subList(0, size).stream().map(MemoryGrowthRows.Candidate::id).toList(),
                    selectedTopic(recordedScope));
            for (int i = 0; i < size; i++) {
                var candidate = candidates.get(i); var value = resolved.get(candidate.id());
                if (value == null || value.summary() == null
                        || !candidate.occurredAt().equals(value.summary().occurredAt())) throw GrowthRules.integrity();
                EvidenceSummary summary = value.summary();
                if (recordedScope.filter().topicId() == null || !summary.facets().topics().isEmpty()) {
                    var memory = summary.conditions().memory();
                    collector.add(ActivityMetric.MEMORY_RATINGS, candidate.id(), candidate.occurredAt());
                    collector.add(ActivityMetric.MEMORY_CARDS_RATED, memory.cardId(), candidate.occurredAt());
                    collector.add(memory.countRegular() ? ActivityMetric.MEMORY_COUNTED_RATINGS : ActivityMetric.MEMORY_UNCOUNTED_RATINGS,
                            candidate.id(), candidate.occurredAt());
                }
                scanned++; consumed = position(candidate);
            }
            exhausted = candidates.size() <= take;
            if (exhausted) break;
        }
        return bounded(collector.finish(!exhausted), GrowthRules.ACTIVITY_BYTES);
    }

    private List<MemoryGrowthRows.Candidate> candidates(String owner, Scope scope, EvidencePosition after, int take) {
        String afterId = after == null ? null : GrowthRules.evidenceKey(after.evidenceKey()).objectId();
        var result = mapper.candidates(owner, scope.window().startAt(), scope.window().observedUntil(),
                after == null ? null : after.occurredAt(), afterId, take + 1);
        if (result == null || result.size() > take + 1) throw GrowthRules.integrity();
        EvidencePosition previous = after;
        for (var row : result) {
            try {
                GrowthRules.uuid(row.id()); GrowthRules.time(row.occurredAt());
                if (row.occurredAt().isBefore(scope.window().startAt()) || !row.occurredAt().isBefore(scope.window().observedUntil())) {
                    throw GrowthRules.integrity();
                }
                var next = position(row);
                if (previous != null && (next.occurredAt().isAfter(previous.occurredAt())
                        || (next.occurredAt().equals(previous.occurredAt()) && next.evidenceKey().compareTo(previous.evidenceKey()) <= 0))) {
                    throw GrowthRules.integrity();
                }
                previous = next;
            } catch (ApiException exception) { throw GrowthRules.integrity(); }
        }
        return result;
    }

    private Map<String, EvidenceResolution> read(String owner, List<String> ids, String topicId) {
        var rows = index(mapper.ratings(owner, ids), MemoryGrowthRows.RatingRow::id);
        if (!new HashSet<>(ids).containsAll(rows.keySet())) throw GrowthRules.integrity();
        List<String> cardIds = rows.values().stream().map(MemoryGrowthRows.RatingRow::cardId).distinct().toList();
        if (cardIds.isEmpty()) return Map.of();
        var cards = index(attempts.cards(owner, cardIds), MemoryRows.CardRow::id);
        var gates = index(attempts.gates(owner, cardIds), Gate::cardId);
        Set<String> versionIds = new LinkedHashSet<>();
        rows.values().stream().map(MemoryGrowthRows.RatingRow::cardVersionId).filter(Objects::nonNull).forEach(versionIds::add);
        cards.values().stream().map(MemoryRows.CardRow::currentVersionId).filter(Objects::nonNull).forEach(versionIds::add);
        var versions = versionIds.isEmpty() ? Map.<String, MemoryGrowthRows.VersionRow>of()
                : index(mapper.versions(owner, List.copyOf(versionIds)), MemoryGrowthRows.VersionRow::id);
        Map<String, List<MemoryGrowthRows.SourceRow>> sources = new HashMap<>();
        if (!versionIds.isEmpty()) for (var source : mapper.sources(owner, List.copyOf(versionIds))) {
            if (!versionIds.contains(source.cardVersionId())) throw GrowthRules.integrity();
            sources.computeIfAbsent(source.cardVersionId(), ignored -> new ArrayList<>()).add(source);
        }
        Set<String> topics = topicId == null || versionIds.isEmpty() ? Set.of()
                : new HashSet<>(mapper.topicVersions(owner, List.copyOf(versionIds), topicId));
        if (!versionIds.containsAll(topics)) throw GrowthRules.integrity();
        Map<String, EvidenceResolution> result = new HashMap<>();
        for (var row : rows.values()) {
            EvidenceRef ref = ref(row.id());
            var original = versions.get(row.cardVersionId());
            if (row.storedAttemptId() == null || original == null) { result.put(row.id(), unavailable(ref)); continue; }
            var card = cards.get(row.cardId());
            var current = card == null ? null : versions.get(card.currentVersionId());
            try {
                result.put(row.id(), project(owner, row, original, card, current, gates.get(row.cardId()), sources,
                        topicId != null && topics.contains(original.id()) ? topicId : null));
            } catch (ApiException | IllegalArgumentException | NullPointerException exception) { throw GrowthRules.integrity(); }
        }
        return result;
    }

    private EvidenceResolution project(String owner, MemoryGrowthRows.RatingRow row, MemoryGrowthRows.VersionRow original,
                                       MemoryRows.CardRow card, MemoryGrowthRows.VersionRow current, Gate gate,
                                       Map<String, List<MemoryGrowthRows.SourceRow>> allSources, String topicId) {
        AttemptEvent event = parse(row.eventJson(), AttemptEvent.class, MemoryAttemptRules.EVENT_BYTES);
        StartSnapshot start = parse(row.startJson(), StartSnapshot.class, MemoryAttemptRules.SNAPSHOT_BYTES);
        validate(owner, row, original, event, start);
        var originalSources = sourceSnapshots(original, allSources.getOrDefault(original.id(), List.of()));
        if (!sourceIdentities(start.sources()).equals(sourceIdentities(originalSources))) throw GrowthRules.integrity();
        for (var source : originalSources) {
            var recorded = start.sources().stream().filter(value -> value.itemId().equals(source.itemId())
                    && value.versionId().equals(source.versionId())).findFirst().orElseThrow(GrowthRules::integrity);
            if (source.state() != SourceState.UNAVAILABLE && (recorded.kind() != source.kind()
                    || !recorded.versionNumber().equals(source.versionNumber()))) throw GrowthRules.integrity();
        }
        var currentSources = current == null ? List.<SourceSnapshot>of()
                : sourceSnapshots(current, allSources.getOrDefault(current.id(), List.of()));
        if (card != null) {
            GrowthRules.uuid(card.id()); GrowthRules.uuid(card.currentVersionId());
            if (!owner.equals(card.ownerId()) || !row.cardId().equals(card.id()) || !Set.of("ACTIVE", "ARCHIVED").contains(card.status())) {
                throw GrowthRules.integrity();
            }
            GrowthRules.range(card.revision(), 0, 1000, "卡片修订"); GrowthRules.range(card.versionNumber(), 1, 1000, "卡片版本");
            if (current != null && (!card.id().equals(current.cardId()) || card.versionNumber() != current.versionNumber()
                    || !card.currentVersionId().equals(original.currentVersionId()) || original.basisChanged() == null)) throw GrowthRules.integrity();
        }
        if (gate != null) {
            if (!row.cardId().equals(gate.cardId())) throw GrowthRules.integrity();
            GrowthRules.revision(gate.revision()); GrowthRules.revision(gate.lastHistorySequence()); GrowthRules.time(gate.updatedAt());
            if (gate.lastReviewDecisionId() != null) GrowthRules.uuid(gate.lastReviewDecisionId());
        }
        Eligibility eligibility = card == null ? null : MemoryReviewRules.eligibility(card, gate, currentSources);
        boolean unavailable = card == null || current == null || gate == null
                || originalSources.stream().anyMatch(source -> source.state() == SourceState.UNAVAILABLE)
                || currentSources.stream().anyMatch(source -> source.state() == SourceState.UNAVAILABLE);
        // gate 是当前观察结果，不能使已发生的评分重新计量；旧来源本身仍按原确切版本复核。
        boolean review = !unavailable && (Boolean.TRUE.equals(original.basisChanged()) || !eligibility.basisReady()
                || Boolean.TRUE.equals(row.upstreamChanged())
                || originalSources.stream().anyMatch(source -> source.state() == SourceState.ARCHIVED
                    || Boolean.TRUE.equals(source.bodyChanged()) || Boolean.TRUE.equals(source.upstreamReviewRequired())));
        EvidenceState state = new EvidenceState(unavailable ? DirectState.UNAVAILABLE : review ? DirectState.REVIEW_REQUIRED : DirectState.AVAILABLE,
                RecordCurrency.NOT_APPLICABLE, unavailable ? List.of(ReviewFlag.SOURCE_UNAVAILABLE)
                : review ? List.of(ReviewFlag.REVIEW_REQUIRED) : List.of(), LineageCheck.PARTIAL);
        Rated rated = event.rated(); var schedule = rated.schedule();
        MemoryMeta meta = new MemoryMeta(row.cardId(), original.id(), row.attemptId(), row.attemptSequence(), start.mode(),
                start.countRegular(), rated.rating(), rated.day(), rated.budget(), schedule == null ? null : schedule.changeId(),
                schedule == null ? null : schedule.sequence(), rated.thinkingElapsedMs(), rated.totalElapsedMs());
        EvidenceRef ref = ref(row.id());
        // 原排程含合法 FSRS 小数；按已校验的不可变存储文本取摘要，不套用成长计数的整数编码。
        String originHash = GrowthRules.hash("GROWTH_MEMORY_ORIGIN_V1", new Origin(ref, row.cardId(), row.attemptSequence(),
                original.versionNumber(), original.kind(), original.title(),
                GrowthRules.hash("GROWTH_MEMORY_EVENT_V1", row.eventJson()),
                GrowthRules.hash("GROWTH_MEMORY_START_V1", row.startJson())));
        String currentHash = GrowthRules.hash("GROWTH_MEMORY_CURRENT_V1", new Current(originHash, state, card, current, gate,
                original.basisChanged(), row.upstreamChanged(), originalSources, currentSources, eligibility));
        EvidenceStamp stamp = new EvidenceStamp(ref, originHash, currentHash);
        var facets = new Facets(List.of(), topicId == null ? List.of() : List.of(new TopicFacet(topicId, FacetBasis.CURRENT_CLASSIFICATION)),
                List.of(), true, null);
        var summary = GrowthRules.summary(new EvidenceSummary(ref, row.occurredAt(), original.title(), RecordKind.MEMORY_SELF_RATING,
                AssessmentLevel.SELF_RECORDED, new Conditions(EvidenceKind.MEMORY_RATING, null, null, null, null, null, meta, null),
                facets, state, stamp, new EvidenceRoute(EvidenceRouteKind.MEMORY_ATTEMPT, ref, null, null, null, null, row.cardId(), row.attemptId())));
        return GrowthRules.resolution(new EvidenceResolution(ref, summary, state, stamp));
    }

    private void validate(String owner, MemoryGrowthRows.RatingRow row, MemoryGrowthRows.VersionRow version,
                          AttemptEvent event, StartSnapshot start) {
        GrowthRules.uuid(row.id()); GrowthRules.uuid(row.cardId()); GrowthRules.uuid(row.attemptId());
        GrowthRules.time(row.occurredAt()); GrowthRules.time(row.startedAt()); GrowthRules.time(row.revealedAt());
        GrowthRules.range(row.attemptSequence(), 1, GrowthRules.MAX, "尝试序号"); GrowthRules.revision(row.attemptRevision());
        if (!row.attemptId().equals(row.storedAttemptId()) || !row.attemptId().equals(event.attemptId()) || !row.id().equals(event.id())
                || !row.cardId().equals(version.cardId()) || !Objects.equals(row.storedAttemptSequence(), row.attemptSequence())
                || !Objects.equals(row.storedAttemptRevision(), row.attemptRevision()) || row.eventSequence() != 3
                || !Objects.equals(row.lastEventSequence(), row.eventSequence()) || event.sequence() != row.eventSequence()
                || event.attemptRevision() != row.attemptRevision() || event.kind() != EventKind.RATE
                || event.fromState() != State.REVEALED || event.toState() != State.RATED || !"RATED".equals(row.state())
                || !row.occurredAt().equals(event.occurredAt()) || !row.occurredAt().equals(row.ratedAt()) || !row.occurredAt().equals(row.closedAt())
                || event.actor() == null || event.actor().kind() != ActorKind.OWNER || !owner.equals(event.actor().ownerId())
                || event.started() != null || event.revealed() != null || event.skipped() != null || event.invalidated() != null || event.rated() == null
                || !version.id().equals(start.cardVersionId()) || !version.id().equals(row.cardVersionId()) || version.versionNumber() != start.cardVersionNumber()
                || start.mode() == null || !start.mode().name().equals(row.mode()) || start.countRegular() != row.countRegular()
                || !Objects.equals(row.storedCountRegular(), row.countRegular()) || !Objects.equals(start.cramBatchId(), row.cramBatchId())
                || row.upstreamChanged() == null) {
            throw GrowthRules.integrity();
        }
        GrowthRules.range(start.cardRevision(), 0, 1000, "原卡片修订"); GrowthRules.revision(start.gateRevision()); GrowthRules.zone(start.zoneId());
        if (start.mode() == Mode.REGULAR) {
            if (!start.countRegular() || start.cramBatchId() != null) throw GrowthRules.integrity();
        } else GrowthRules.uuid(start.cramBatchId());
        if (start.sources() == null || start.sources().isEmpty() || start.sources().size() > 10) throw GrowthRules.integrity();
        for (var source : start.sources()) {
            GrowthRules.uuid(source.itemId()); GrowthRules.uuid(source.versionId()); GrowthRules.required(source.kind());
            GrowthRules.range(GrowthRules.required(source.versionNumber()), 1, Integer.MAX_VALUE, "原知识版本"); GrowthRules.uuid(source.currentVersionId());
            if (source.state() == null || source.state() == SourceState.UNAVAILABLE || !Boolean.FALSE.equals(source.bodyChanged())
                    || Boolean.TRUE.equals(source.upstreamReviewRequired())
                    || source.state() == SourceState.CURRENT && !source.versionId().equals(source.currentVersionId())
                    || source.state() == SourceState.SUPERSEDED && source.versionId().equals(source.currentVersionId())
                    || source.state() == SourceState.ARCHIVED && source.kind() == com.wenjie.studio.knowledge.KnowledgeDtos.Kind.NOTE) {
                throw GrowthRules.integrity();
            }
        }
        if (start.sources().stream().noneMatch(MemoryReviewRules::usable)) throw GrowthRules.integrity();
        var rated = event.rated(); var day = GrowthRules.required(rated.day());
        if (rated.rating() == null || !rated.rating().name().equals(row.rating()) || row.revealedAt().isBefore(row.startedAt())
                || row.occurredAt().isBefore(row.revealedAt())
                || rated.thinkingElapsedMs() != Duration.between(row.startedAt(), row.revealedAt()).toMillis()
                || rated.totalElapsedMs() != Duration.between(row.startedAt(), row.occurredAt()).toMillis()) throw GrowthRules.integrity();
        GrowthRules.zone(day.zoneId()); GrowthRules.date(day.localDate()); GrowthRules.time(day.windowStart()); GrowthRules.time(day.windowEnd());
        // 原评分日由 RATE 保存。成长展示时区和后续设置均不参与重算原日界、预算或排程序号。
        if (row.occurredAt().isBefore(day.windowStart()) || !row.occurredAt().isBefore(day.windowEnd())) throw GrowthRules.integrity();
        if (row.countRegular()) {
            var budget = GrowthRules.required(rated.budget()); var schedule = GrowthRules.required(rated.schedule());
            GrowthRules.required(budget.kind()); GrowthRules.uuid(budget.firstCountedEventId()); GrowthRules.uuid(schedule.changeId());
            if (!Set.of("NEW", "REVIEW").contains(row.basisKind()) || start.admission() == null || start.schedule() == null
                    || !start.schedule().equals(schedule.before()) || schedule.after() == null
                    || !row.cardId().equals(schedule.before().cardId()) || !row.cardId().equals(schedule.after().cardId())
                    || schedule.after().revision() != schedule.before().revision() + 1
                    || schedule.sequence() != schedule.after().lastHistorySequence()
                    || schedule.sequence() != schedule.before().lastHistorySequence() + 1
                    || !row.id().equals(schedule.after().lastRatedEventId()) || !row.occurredAt().equals(schedule.after().updatedAt())
                    || (budget.firstForCardToday() != row.id().equals(budget.firstCountedEventId()))) throw GrowthRules.integrity();
        } else if (row.basisKind() != null || rated.budget() != null || rated.schedule() != null || start.schedule() != null || start.admission() != null
                || day.settingsRevision() != null || !day.zoneId().equals(start.zoneId())) throw GrowthRules.integrity();
    }

    private List<SourceSnapshot> sourceSnapshots(MemoryGrowthRows.VersionRow version, List<MemoryGrowthRows.SourceRow> rows) {
        GrowthRules.uuid(version.id()); GrowthRules.uuid(version.cardId()); GrowthRules.range(version.versionNumber(), 1, 1000, "卡片版本");
        GrowthRules.range(version.sourceCount(), 1, 10, "卡片来源"); GrowthRules.range(version.relatedConceptCount(), 0, 20, "关联概念");
        MemoryDtos.Kind.valueOf(version.kind()); GrowthRules.text(version.title(), 200, true);
        if (rows.size() != version.sourceCount()) throw GrowthRules.integrity();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i); GrowthRules.uuid(row.itemId()); GrowthRules.uuid(row.versionId());
            if (!version.id().equals(row.cardVersionId()) || row.positionIndex() != i
                    || (row.status() != null && !Set.of("ACTIVE", "ARCHIVED").contains(row.status()))) throw GrowthRules.integrity();
        }
        var result = MemoryReviewRules.sources(rows.stream().map(MemoryGrowthRows.SourceRow::source).toList());
        for (var source : result) if (source.state() != SourceState.UNAVAILABLE) {
            GrowthRules.range(GrowthRules.required(source.versionNumber()), 1, Integer.MAX_VALUE, "知识版本");
            GrowthRules.uuid(source.currentVersionId());
        }
        sourceIdentities(result); return result;
    }

    private static Set<String> sourceIdentities(List<SourceSnapshot> sources) {
        Set<String> values = new HashSet<>();
        for (var source : sources) if (!values.add(source.itemId() + ":" + source.versionId())) throw GrowthRules.integrity();
        return values;
    }
    private static <T> T parse(String value, Class<T> type, int maximum) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > maximum) throw GrowthRules.integrity();
        try {
            var node = JSON.readTree(value);
            if (type == StartSnapshot.class && node != null && node.get("sources") != null && node.get("sources").isArray()) {
                // 仅兼容旧来源快照的新增可空尾字段；其余缺字段仍由严格解析拒绝，不重写历史文本及摘要。
                for (var source : node.get("sources")) if (source instanceof ObjectNode object && !object.has("upstreamReviewRequired")) {
                    object.putNull("upstreamReviewRequired");
                }
            }
            return GrowthRules.required(JSON.treeToValue(node, type));
        }
        catch (RuntimeException exception) { throw GrowthRules.integrity(); }
    }
    private static <T> Map<String, T> index(List<T> rows, Function<T, String> identity) {
        if (rows == null) throw GrowthRules.integrity();
        Map<String, T> result = new HashMap<>();
        for (T row : rows) if (row == null || result.put(identity.apply(row), row) != null) throw GrowthRules.integrity();
        return result;
    }
    private static EvidenceResolution unavailable(EvidenceRef ref) {
        var state = new EvidenceState(DirectState.UNAVAILABLE, RecordCurrency.NOT_APPLICABLE,
                List.of(ReviewFlag.SOURCE_UNAVAILABLE), LineageCheck.PARTIAL);
        var stamp = new EvidenceStamp(ref, null, GrowthRules.hash("GROWTH_MEMORY_UNAVAILABLE_V1", ref));
        return GrowthRules.resolution(new EvidenceResolution(ref, null, state, stamp));
    }
    private static EvidenceRef ref(String id) { return new EvidenceRef(EvidenceKind.MEMORY_RATING, id, null, null, null); }
    private static EvidencePosition position(MemoryGrowthRows.Candidate row) {
        return new EvidencePosition(row.occurredAt(), GrowthRules.evidenceKey(ref(row.id())));
    }
    private static String selectedTopic(Scope scope) {
        return scope.filter().topicMode() == TopicMode.INCLUDE_CURRENT_CLASSIFICATION ? scope.filter().topicId() : null;
    }
    private static void kind(EvidenceKind kind) {
        if (kind != EvidenceKind.MEMORY_RATING) throw GrowthRules.invalid("记忆成长查询仅接受 MEMORY_RATING。");
    }
    private static <T> T bounded(T value, int maximum) {
        if (JSON.writeValueAsBytes(value).length > maximum) throw GrowthRules.limit("记忆成长元数据响应超过字节上限。");
        return value;
    }
    private record Origin(EvidenceRef ref, String cardId, long attemptSequence, int cardVersionNumber, String kind,
                          String title, String eventHash, String startHash) {}
    private record Current(String originHash, EvidenceState state, MemoryRows.CardRow card,
                           MemoryGrowthRows.VersionRow currentVersion, Gate gate, Boolean basisChanged,
                           Boolean upstreamChanged, List<SourceSnapshot> originalSources,
                           List<SourceSnapshot> currentSources, Eligibility eligibility) {}
}
