package com.wenjie.studio.growth;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import com.wenjie.studio.prepare.GrowthTargetQuery;
import com.wenjie.studio.prepare.PreparationDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.wenjie.studio.growth.GrowthDtos.*;
import static com.wenjie.studio.growth.GrowthRules.*;
import static com.wenjie.studio.growth.GrowthStoreRows.*;

/** 个人跨目标判断保留确切身份；当前适用性始终重新核对原证据。 */
@Service
public class GrowthUseService {
    private static final Logger LOG = LoggerFactory.getLogger(GrowthUseService.class);
    private final GrowthStoreMapper mapper;
    private final GrowthTargetQuery targets;
    private final GrowthEvidenceQuery evidence;
    private final IdempotencyService commands;
    private final JsonMapper json;

    public GrowthUseService(GrowthStoreMapper mapper, GrowthTargetQuery targets, GrowthEvidenceQuery evidence,
                            IdempotencyService commands, JsonMapper json) {
        this.mapper = mapper; this.targets = targets; this.evidence = evidence; this.commands = commands; this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UsePreview preview(String owner, CreateUsePreview request) {
        uuid(owner); createPreview(request);
        var target = targets.require(owner, request.target());
        target.requirement(request.requirementId()); requireCurrent(target);
        var source = resolution(evidence.require(owner, request.evidence()));
        return readResponse(json, preview(owner, PreviewIntent.CREATE, null, request, target, source, now()));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UsePreview previewChange(String owner, String id, ChangeUsePreview request) {
        uuid(owner); uuid(id); changePreview(request);
        var saved = requireUse(owner, id); expected(saved, request.expectedRevision());
        var target = savedTarget(owner, saved);
        return readResponse(json, preview(owner, PreviewIntent.CHANGE, saved, identity(saved), target,
                resolution(evidence.resolve(owner, saved.evidence())), now()));
    }

    public EvidenceUse create(String owner, String key, CreateUse request) {
        uuid(owner); createUse(request);
        return commands.execute(owner, key, "POST /api/v1/growth/target-evidence", request, EvidenceUse.class, () -> {
            var target = targets.require(owner, request.request().target());
            target.requirement(request.request().requirementId());
            var existing = mapper.useByIdentity(owner, request.request().target().versionId(),
                    request.request().requirementId(), evidenceKey(request.request().evidence()));
            if (existing != null) {
                var saved = snapshot(owner, existing); sameIdentity(saved, request.request());
                throw conflict("EVIDENCE_USE_EXISTS", "此目标要求与证据已有个人关联，请按原身份读取后修改或恢复。", saved.revision());
            }
            requireCurrent(target);
            var source = resolution(evidence.require(owner, request.request().evidence()));
            var preview = preview(owner, PreviewIntent.CREATE, null, request.request(), target, source, now());
            checkPreview(request.expectedPreviewHash(), preview); positiveAllowed(request.coverage(), source);
            Instant time = now();
            var saved = new EvidenceUse(UUID.randomUUID().toString(), 0, request.request().target(),
                    request.request().requirementId(), request.request().evidence(), request.coverage(),
                    request.comparability(), request.reason(), UseStatus.ACTIVE, time, time);
            storedUse(saved);
            // owner 锁由幂等边界持有；当前行、历史与回执一起提交，不能分别成功。
            if (mapper.insertUse(row(owner, saved)) != 1) throw integrity();
            appendHistory(owner, saved, UseChangeKind.CREATED, source.stamp(), target.stamp(saved.requirementId()), preview.previewHash());
            return readResponse(json, saved);
        });
    }

    public EvidenceUse change(String owner, String id, String key, ChangeUse request) {
        uuid(owner); uuid(id); changeUse(request);
        return commands.execute(owner, key, "PUT /api/v1/growth/target-evidence/" + id, request, EvidenceUse.class, () -> {
            var before = requireUse(owner, id); expected(before, request.expectedRevision());
            var target = savedTarget(owner, before);
            var source = resolution(evidence.resolve(owner, before.evidence()));
            var preview = preview(owner, PreviewIntent.CHANGE, before, identity(before), target, source, now());
            checkPreview(request.expectedPreviewHash(), preview);
            boolean unchanged = before.coverage() == request.coverage() && before.comparability().equals(request.comparability())
                    && before.reason().equals(request.reason()) && before.status() == request.status();
            // 无变化不造历史；仍先检查本次 CAS、资料权限及当前预览，旧判断的失效由读取状态表达。
            if (unchanged) return readResponse(json, before);
            if (request.status() == UseStatus.ACTIVE) {
                requireCurrent(target); positiveAllowed(request.coverage(), source);
            }
            Instant time = now(); clock(time, before.updatedAt());
            var after = new EvidenceUse(id, next(before.revision()), before.target(), before.requirementId(), before.evidence(),
                    request.coverage(), request.comparability(), request.reason(), request.status(), before.createdAt(), time);
            storedUse(after);
            if (mapper.updateUse(row(owner, after), before.revision()) != 1) {
                throw conflict("EVIDENCE_USE_REVISION_CONFLICT", "个人关联已变化，请保留输入并重新读取。", before.revision());
            }
            UseChangeKind change = before.status() == after.status() ? UseChangeKind.UPDATED
                    : after.status() == UseStatus.WITHDRAWN ? UseChangeKind.WITHDRAWN : UseChangeKind.RESTORED;
            appendHistory(owner, after, change, source.stamp(), target.stamp(after.requirementId()), preview.previewHash());
            return readResponse(json, after);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UseLookup get(String owner, String id) {
        uuid(owner); uuid(id); Instant asOf = now();
        var saved = requireUse(owner, id);
        var found = entry(owner, saved, savedTarget(owner, saved));
        return readResponse(json, new UseLookup(context(asOf), found));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UseIdentityLookup byIdentity(String owner, UseIdentityQuery query) {
        uuid(owner); required(query);
        var identity = new CreateUsePreview(target(query.targetId(), query.targetVersionId()), uuid(query.requirementId()),
                evidence(new EvidenceRef(query.kind(), query.objectId(), query.versionId(), query.revision(), query.partId())));
        createPreview(identity); Instant asOf = now();
        var row = mapper.useByIdentity(owner, identity.target().versionId(), identity.requirementId(), evidenceKey(identity.evidence()));
        UseEntry found = null;
        if (row != null) {
            var saved = snapshot(owner, row); sameIdentity(saved, identity);
            found = entry(owner, saved, savedTarget(owner, saved));
        } else {
            targets.require(owner, identity.target()).requirement(identity.requirementId());
            resolution(evidence.require(owner, identity.evidence()));
        }
        return readResponse(json, new UseIdentityLookup(context(asOf), identity, found));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UsePage directory(String owner, UseDirectoryQuery query) {
        uuid(owner); required(query);
        TargetRef ref = target(query.targetId(), query.targetVersionId()); target(ref);
        if (query.requirementId() != null) uuid(query.requirementId());
        int limit = pageSize(query.limit()); Instant checked = now();
        var cursor = directoryCursor(query.cursor(), owner, CursorPurpose.USE_DIRECTORY, checked);
        Instant asOf = cursor == null ? checked : cursor.asOf();
        String scope = useDirectoryScopeHash(ref, query.requirementId(), query.status());
        if (cursor != null) cursorScope(cursor.scopeHash(), scope);
        var target = targets.require(owner, ref);
        if (query.requirementId() != null) target.requirement(query.requirementId());
        var position = cursor == null ? null : cursor.position();
        var rows = mapper.uses(owner, ref.itemId(), ref.versionId(), query.requirementId(), name(query.status()), asOf,
                position == null ? null : position.createdAt(), position == null ? null : position.id(), limit);
        requireRows(rows, limit);
        var saved = rows.stream().map(row -> snapshot(owner, row)).toList();
        validateDirectory(saved, ref, query.requirementId(), query.status(), asOf, position);
        var entries = entries(owner, saved, target);
        String nextCursor = null;
        if (saved.size() == limit) {
            var last = saved.getLast();
            // 满页后单独探测后续一行，所有 SQL 读取批次仍不超过 50。
            var next = mapper.uses(owner, ref.itemId(), ref.versionId(), query.requirementId(), name(query.status()), asOf,
                    last.createdAt(), last.id(), 1);
            requireRows(next, 1);
            if (!next.isEmpty()) nextCursor = cursor(new DirectoryCursor(1, CursorPurpose.USE_DIRECTORY, ownerFingerprint(owner),
                    scope, asOf, cursor == null ? checked : cursor.issuedAt(), new DirectoryPosition(last.createdAt(), last.id())));
        }
        return readResponse(json, new UsePage(context(asOf), ref, query.requirementId(), query.status(), entries, nextCursor));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UseHistoryPage history(String owner, String id, Long before, int limit) {
        uuid(owner); uuid(id); pageSize(limit); nullableRevision(before);
        var current = requireUse(owner, id);
        var rows = mapper.useHistory(owner, id, before, limit); requireRows(rows, limit);
        if (before == null && (rows.isEmpty() || rows.getFirst().revision() != current.revision())) throw integrity();
        var items = new ArrayList<UseHistory>(); Long previous = before;
        for (var row : rows) {
            var item = history(owner, current, row);
            if (previous != null && item.snapshot().revision() >= previous) throw integrity();
            previous = item.snapshot().revision(); items.add(item);
        }
        Long nextBefore = null;
        if (items.size() == limit) {
            long last = items.getLast().snapshot().revision();
            var next = mapper.useHistory(owner, id, last, 1); requireRows(next, 1);
            if (!next.isEmpty()) nextBefore = last;
        }
        return readResponse(json, new UseHistoryPage(List.copyOf(items), nextBefore));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TargetCoverage coverage(String owner, TargetRef ref) {
        uuid(owner); target(ref); Instant asOf = now();
        var target = targets.require(owner, ref);
        var totals = new HashMap<String, EnumMap<CoverageMetric, Long>>();
        for (var requirement : target.requirements()) {
            var values = new EnumMap<CoverageMetric, Long>(CoverageMetric.class);
            for (var metric : CoverageMetric.values()) values.put(metric, 0L);
            totals.put(requirement.id(), values);
        }
        int scanned = 0; boolean more = false; DirectoryPosition position = null; Capability failure = null;
        try {
            do {
                // 探测行只判断是否还有候选；一次最多读取 50 行，实际解析最多 500 条。
                int size = Math.min(MAX_RESULTS - 1, MAX_CANDIDATES - scanned);
                var rows = mapper.uses(owner, ref.itemId(), ref.versionId(), null, null, asOf,
                        position == null ? null : position.createdAt(), position == null ? null : position.id(), size + 1);
                requireRows(rows, size + 1);
                var saved = rows.stream().limit(size).map(row -> snapshot(owner, row)).toList();
                validateDirectory(saved, ref, null, null, asOf, position);
                scanned += saved.size();
                for (var entry : entries(owner, saved, target)) add(totals.get(entry.requirement().id()), entry);
                more = rows.size() > size;
                if (!saved.isEmpty()) {
                    var last = saved.getLast(); position = new DirectoryPosition(last.createdAt(), last.id());
                }
            } while (more && scanned < MAX_CANDIDATES);
        } catch (DataAccessException exception) {
            failure = failed(CapabilityReason.SOURCE_READ_FAILED, exception);
        } catch (IllegalStateException exception) {
            failure = failed(CapabilityReason.SOURCE_INTEGRITY_FAILED, exception);
        } catch (ApiException exception) {
            if (exception.status() != 503) throw exception;
            failure = failed(CapabilityReason.SOURCE_READ_FAILED, exception);
        }
        var result = new ArrayList<RequirementCoverage>();
        CountMode mode = failure != null ? CountMode.UNAVAILABLE : more ? CountMode.LOWER_BOUND : CountMode.EXACT;
        for (var requirement : target.requirements()) {
            var counts = new ArrayList<CoverageCount>(); var values = totals.get(requirement.id());
            for (var metric : CoverageMetric.values()) counts.add(new CoverageCount(metric,
                    new Count(failure == null ? values.get(metric) : null, mode)));
            Boolean empty = failure != null ? null : values.get(CoverageMetric.ACTIVE_USES) > 0 ? Boolean.FALSE : more ? null : Boolean.TRUE;
            result.add(new RequirementCoverage(requirement, List.copyOf(counts), empty));
        }
        return readResponse(json, new TargetCoverage(context(asOf), target.target(), target.current(),
                failure == null ? new Capability(Availability.READY, CapabilityReason.NONE) : failure,
                List.copyOf(result), more, scanned));
    }

    private UsePreview preview(String owner, PreviewIntent intent, EvidenceUse saved, CreateUsePreview identity,
                               GrowthTargetQuery.Projection target, EvidenceResolution source, Instant asOf) {
        var requirement = target.requirement(identity.requirementId());
        var comparison = comparison(requirement, source);
        var limits = limitations(source);
        var metadata = target.target();
        var state = source.state();
        // 摘要绑定原身份和当前依赖，排除每次请求的时钟与显示标签，避免重试时无意义失效。
        var basis = new PreviewBasis(owner, intent, saved == null ? null : saved.id(), saved == null ? null : saved.revision(),
                identity, new TargetBasis(metadata.ref(), metadata.itemRevision(), metadata.status(), metadata.currentVersionId(),
                metadata.sourceGeneration(), metadata.capturedOn()), hash("GROWTH_REQUIREMENT_V1", requirement), source.stamp(),
                state.directState(), state.reviewFlags().stream().sorted().toList(), state.lineageCheck(), comparison, limits,
                target.current() && state.directState() == DirectState.AVAILABLE);
        return new UsePreview(context(asOf), intent, saved == null ? null : saved.id(), saved == null ? null : saved.revision(),
                metadata, requirement, source, comparison, hash("GROWTH_EVIDENCE_USE_V1", basis), limits);
    }

    private record TargetBasis(TargetRef ref, long itemRevision, PreparationDtos.Status status, String currentVersionId,
                               long sourceGeneration, java.time.LocalDate capturedOn) {}
    private record PreviewBasis(String owner, PreviewIntent intent, String useId, Long useRevision, CreateUsePreview identity,
                                TargetBasis target, String requirementHash, EvidenceStamp sourceStamp, DirectState directState,
                                List<ReviewFlag> reviewFlags, LineageCheck lineageCheck, Comparability comparability,
                                List<Limitation> limitations, boolean positiveAllowed) {}

    private static Comparability comparison(RequirementMeta requirement, EvidenceResolution source) {
        var left = new HashSet<>(requirement.dimensions()); var right = new HashSet<String>();
        if (source.summary() != null) source.summary().facets().dimensions().forEach(value -> right.add(value.dimension().name()));
        MatchState match = left.isEmpty() || right.isEmpty() ? MatchState.UNKNOWN : left.equals(right) ? MatchState.MATCH
                : left.stream().noneMatch(right::contains) ? MatchState.DIFFERENT : MatchState.UNKNOWN;
        String reason = switch (match) {
            case MATCH -> "明确记录的考察维度集合一致，仍需人工核对要求语义。";
            case DIFFERENT -> "双方明确记录的考察维度没有交集。";
            case UNKNOWN -> "考察维度缺失或仅部分重叠，不能自动确定适用范围。";
        };
        return new Comparability(new ComparisonItem(match, reason),
                new ComparisonItem(MatchState.UNKNOWN, "目标未记录结构化场景约束，请人工核对。"),
                new ComparisonItem(MatchState.UNKNOWN, "目标未记录结构化技术版本约束，请人工核对。"),
                new ComparisonItem(MatchState.UNKNOWN, "目标未记录结构化辅助条件约束，请结合原证据条件核对。"),
                new ComparisonItem(MatchState.UNKNOWN, "请核对证据发生时间与目标记录日期；系统不设置自动失效期限。"));
    }

    private static List<Limitation> limitations(EvidenceResolution source) {
        var values = new ArrayList<Limitation>();
        if (source.state().lineageCheck() == LineageCheck.PARTIAL) values.add(Limitation.INDIRECT_LINEAGE_NOT_CHECKED);
        values.add(Limitation.TARGET_SCENARIO_UNSTRUCTURED); values.add(Limitation.TARGET_TECHNOLOGY_UNSTRUCTURED);
        values.add(Limitation.TARGET_ASSISTANCE_UNSTRUCTURED); values.add(Limitation.NO_AUTOMATIC_DATE_EXPIRY);
        values.add(Limitation.SEMANTICS_NOT_CERTIFIED); return List.copyOf(values);
    }

    private static void checkPreview(String expected, UsePreview preview) {
        if (!expected.equals(preview.previewHash())) throw conflict("GROWTH_PREVIEW_CHANGED", "目标、要求或证据状态已变化，请保留输入并重新预览。", null);
    }
    private static void positiveAllowed(Coverage coverage, EvidenceResolution source) {
        if (positive(coverage) && source.state().directState() != DirectState.AVAILABLE) {
            throw conflict("GROWTH_POSITIVE_COVERAGE_UNAVAILABLE", "当前证据不能保存为正向覆盖，请先复核来源或使用非正向说明。", null);
        }
    }
    private static void requireCurrent(GrowthTargetQuery.Projection target) {
        if (!target.current()) throw conflict("GROWTH_TARGET_NOT_CURRENT", "目标已归档或此版本已非当前版本；历史关联可撤回。", null);
    }
    private static void expected(EvidenceUse saved, long expected) {
        if (saved.revision() != expected) throw conflict("EVIDENCE_USE_REVISION_CONFLICT", "个人关联已变化，请保留输入并重新读取。", saved.revision());
    }
    private static CreateUsePreview identity(EvidenceUse saved) { return new CreateUsePreview(saved.target(), saved.requirementId(), saved.evidence()); }
    private static void sameIdentity(EvidenceUse saved, CreateUsePreview identity) {
        if (!identity(saved).equals(identity)) throw integrity();
    }

    private EvidenceUse requireUse(String owner, String id) {
        var row = mapper.use(owner, id);
        if (row == null) throw new ApiException(404, "EVIDENCE_USE_NOT_FOUND", "个人证据关联不存在。");
        var saved = snapshot(owner, row); if (!id.equals(saved.id())) throw integrity(); return saved;
    }
    private GrowthTargetQuery.Projection savedTarget(String owner, EvidenceUse saved) {
        try {
            var target = targets.require(owner, saved.target()); target.requirement(saved.requirementId()); return target;
        } catch (ApiException exception) {
            if (exception.status() == 404) throw integrity(); throw exception;
        }
    }
    private UseEntry entry(String owner, EvidenceUse saved, GrowthTargetQuery.Projection target) {
        return entry(saved, target, resolution(evidence.resolve(owner, saved.evidence())));
    }
    private List<UseEntry> entries(String owner, List<EvidenceUse> saved, GrowthTargetQuery.Projection target) {
        if (saved.isEmpty()) return List.of();
        var references = saved.stream().map(EvidenceUse::evidence).distinct().toList();
        var resolved = evidence.resolve(owner, references);
        if (resolved == null || resolved.size() != references.size()) throw integrity();
        var byRef = new HashMap<EvidenceRef, EvidenceResolution>();
        for (int i = 0; i < references.size(); i++) {
            var value = resolution(resolved.get(i));
            if (!references.get(i).equals(value.ref()) || byRef.put(value.ref(), value) != null) throw integrity();
        }
        return saved.stream().map(value -> entry(value, target, byRef.get(value.evidence()))).toList();
    }
    private static UseEntry entry(EvidenceUse saved, GrowthTargetQuery.Projection target, EvidenceResolution source) {
        if (!saved.target().equals(target.target().ref()) || !saved.evidence().equals(source.ref())) throw integrity();
        RequirementMeta requirement;
        try { requirement = target.requirement(saved.requirementId()); }
        catch (ApiException exception) { throw integrity(); }
        EffectiveUseState state;
        if (saved.status() == UseStatus.WITHDRAWN) state = EffectiveUseState.WITHDRAWN;
        else if (!target.current()) state = EffectiveUseState.TARGET_NOT_CURRENT;
        else state = switch (source.state().directState()) {
            case UNAVAILABLE -> EffectiveUseState.SOURCE_UNAVAILABLE;
            case UNKNOWN -> EffectiveUseState.SOURCE_UNKNOWN;
            case REVIEW_REQUIRED -> EffectiveUseState.SOURCE_REVIEW_REQUIRED;
            case AVAILABLE -> positive(saved.coverage()) ? EffectiveUseState.EFFECTIVE_POSITIVE : EffectiveUseState.NON_POSITIVE;
        };
        return new UseEntry(saved, target.target(), requirement, source, state);
    }

    private EvidenceUse snapshot(String owner, UseRow row) {
        try {
            if (row == null || !owner.equals(row.ownerId())) throw integrity();
            var ref = new EvidenceRef(required(enumValue(row.evidenceKind(), EvidenceKind.class)), row.evidenceObjectId(), row.evidenceVersionId(),
                    row.evidenceRevision(), row.evidencePartId());
            if (!evidenceKey(ref).equals(row.evidenceKey())) throw integrity();
            var value = new EvidenceUse(row.id(), row.revision(), new TargetRef(row.targetId(), row.targetVersionId()), row.requirementId(), ref,
                    required(enumValue(row.coverage(), Coverage.class)), stored(row.comparabilityJson(), Comparability.class), row.reason(),
                    required(enumValue(row.status(), UseStatus.class)), row.createdAt(), row.updatedAt());
            storedUse(value); return value;
        } catch (IllegalArgumentException | ApiException exception) { throw integrity(); }
    }
    private UseRow row(String owner, EvidenceUse use) {
        var ref = use.evidence();
        return new UseRow(use.id(), owner, use.target().itemId(), use.target().versionId(), use.requirementId(), ref.kind().name(),
                ref.objectId(), ref.versionId(), ref.revision(), ref.partId(), evidenceKey(ref), use.revision(), use.coverage().name(),
                json.writeValueAsString(use.comparability()), use.reason(), use.status().name(), use.createdAt(), use.updatedAt());
    }
    private void appendHistory(String owner, EvidenceUse saved, UseChangeKind change, EvidenceStamp sourceStamp,
                               String targetStamp, String previewHash) {
        var item = new UseHistory(change, saved, sourceStamp, targetStamp, previewHash, saved.updatedAt());
        GrowthRules.history(json, item); stamp(sourceStamp); hashText(targetStamp); hashText(previewHash);
        if (mapper.insertUseHistory(new UseHistoryRow(owner, saved.id(), saved.revision(), change.name(), json.writeValueAsString(saved),
                json.writeValueAsString(sourceStamp), targetStamp, previewHash, saved.updatedAt())) != 1) throw integrity();
    }
    private UseHistory history(String owner, EvidenceUse current, UseHistoryRow row) {
        try {
            if (!owner.equals(row.ownerId()) || !current.id().equals(row.useId())) throw integrity();
            var saved = stored(row.snapshotJson(), EvidenceUse.class); storedUse(saved);
            if (!saved.id().equals(current.id()) || saved.revision() != row.revision() || saved.revision() > current.revision()
                    || !saved.createdAt().equals(current.createdAt()) || !saved.updatedAt().equals(row.createdAt())
                    || saved.revision() == current.revision() && !saved.equals(current)) throw integrity();
            sameIdentity(saved, identity(current));
            var sourceStamp = stored(row.sourceStampJson(), EvidenceStamp.class); stamp(sourceStamp);
            if (!sourceStamp.ref().equals(saved.evidence())) throw integrity();
            hashText(row.targetStamp()); hashText(row.previewHash());
            var change = required(enumValue(row.changeKind(), UseChangeKind.class));
            if ((saved.revision() == 0) != (change == UseChangeKind.CREATED)
                    || change == UseChangeKind.WITHDRAWN && saved.status() != UseStatus.WITHDRAWN
                    || (change == UseChangeKind.CREATED || change == UseChangeKind.RESTORED) && saved.status() != UseStatus.ACTIVE) throw integrity();
            return GrowthRules.history(json, new UseHistory(change, saved, sourceStamp, row.targetStamp(), row.previewHash(), row.createdAt()));
        } catch (IllegalArgumentException | ApiException exception) { throw integrity(); }
    }
    private static <T> T stored(String value, Class<T> type) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > HISTORY_BYTES) throw integrity();
        try { return decode(value.getBytes(StandardCharsets.UTF_8), type); }
        catch (ApiException exception) { throw integrity(); }
    }
    private static void requireRows(List<?> rows, int maximum) {
        if (rows == null || rows.size() > maximum || rows.stream().anyMatch(Objects::isNull)) throw integrity();
    }
    private static void validateDirectory(List<EvidenceUse> rows, TargetRef target, String requirementId, UseStatus status,
                                           Instant asOf, DirectoryPosition position) {
        var previous = position;
        for (var row : rows) {
            if (!row.target().equals(target) || requirementId != null && !requirementId.equals(row.requirementId())
                    || status != null && row.status() != status || !row.createdAt().isBefore(asOf)) throw integrity();
            if (previous != null && (row.createdAt().isAfter(previous.createdAt())
                    || row.createdAt().equals(previous.createdAt()) && row.id().compareTo(previous.id()) <= 0)) throw integrity();
            previous = new DirectoryPosition(row.createdAt(), row.id());
        }
    }
    private static void add(EnumMap<CoverageMetric, Long> totals, UseEntry entry) {
        if (totals == null) throw integrity();
        if (entry.use().status() == UseStatus.WITHDRAWN) { increment(totals, CoverageMetric.WITHDRAWN_USES); return; }
        increment(totals, CoverageMetric.ACTIVE_USES);
        increment(totals, switch (entry.use().coverage()) {
            case SUPPORTS -> CoverageMetric.SAVED_SUPPORTS;
            case PARTIAL -> CoverageMetric.SAVED_PARTIAL;
            case CONTEXT_ONLY -> CoverageMetric.CONTEXT_ONLY;
            case INSUFFICIENT -> CoverageMetric.INSUFFICIENT;
        });
        if (positive(entry.use().coverage())) increment(totals, entry.effectiveState() == EffectiveUseState.EFFECTIVE_POSITIVE
                ? CoverageMetric.EFFECTIVE_POSITIVE : CoverageMetric.INVALIDATED_POSITIVE);
    }
    private static void increment(Map<CoverageMetric, Long> totals, CoverageMetric metric) { totals.put(metric, totals.get(metric) + 1); }
    private static Capability failed(CapabilityReason reason, RuntimeException exception) {
        // 只记录类别，避免把 JDBC 绑定值、原文或完整异常消息带进日志；HTTP trace 仍由统一链路关联。
        LOG.warn("Growth target coverage unavailable: reason={}, type={}", reason, exception.getClass().getSimpleName());
        return new Capability(Availability.ERROR, reason);
    }
    private static String name(Enum<?> value) { return value == null ? null : value.name(); }
    private static Context context(Instant asOf) { Instant checked = now(); return new Context(asOf, checked, checked); }
}
