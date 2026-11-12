package com.wenjie.studio.growth;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import com.wenjie.studio.prepare.GrowthTargetQuery;
import com.wenjie.studio.today.TodayDtos;
import com.wenjie.studio.today.TodayService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import static com.wenjie.studio.growth.GrowthDtos.*;
import static com.wenjie.studio.growth.GrowthRules.*;
import static com.wenjie.studio.growth.GrowthStoreRows.*;

/** 隐藏只保存成长建议的显示意图；恢复时重新读取原主体，不恢复旧建议快照。 */
@Service
public class GrowthPreferenceService {
    private final GrowthStoreMapper mapper;
    private final GrowthNextStepMapper sources;
    private final GrowthTargetQuery targets;
    private final TodayService today;
    private final IdempotencyService commands;
    private final JsonMapper json;

    public GrowthPreferenceService(GrowthStoreMapper mapper, GrowthNextStepMapper sources, GrowthTargetQuery targets,
                                   TodayService today, IdempotencyService commands, JsonMapper json) {
        this.mapper = mapper; this.sources = sources; this.targets = targets;
        this.today = today; this.commands = commands; this.json = json;
    }

    public NextStepPreference create(String owner, String key, CreatePreference request) {
        uuid(owner); createPreference(request);
        return available(() -> commands.execute(owner, key, "POST /api/v1/growth/next-step-preferences", request,
                NextStepPreference.class, () -> {
            var identity = identity(request.subject());
            var existing = find(owner, identity);
            if (existing != null) throw conflict("NEXT_STEP_PREFERENCE_EXISTS", "此主体已有显示偏好，请读取后修改或恢复。", existing.revision());
            var current = current(owner, identity, now());
            checkSubject(request.subject(), current);
            if (identity.kind() == SubjectKind.TARGET_REQUIREMENT && !current.currentTarget()) throw changed();
            Instant time = now();
            var saved = new NextStepPreference(UUID.randomUUID().toString(), 0, identity, request.visibility(), time, time);
            storedPreference(saved);
            // owner 锁、当前行、不可变历史和回执共用 IdempotencyService 的写事务。
            if (mapper.insertPreference(row(owner, saved)) != 1) throw integrity();
            appendHistory(owner, saved, PreferenceChangeKind.CREATED);
            return readResponse(json, saved);
        }));
    }

    public NextStepPreference change(String owner, String id, String key, SavePreference request) {
        uuid(owner); uuid(id); savePreference(request);
        return available(() -> commands.execute(owner, key, "PUT /api/v1/growth/next-step-preferences/" + id, request,
                NextStepPreference.class, () -> {
            var before = requirePreference(owner, id);
            if (before.revision() != request.expectedRevision()) throw revisionConflict(before.revision());
            var current = current(owner, before.subject(), now());
            if (request.expectedSubject() == null) {
                // null 是明确清除失效旧项隐藏的意图，不能作为跳过当前来源校验的通用入口。
                if (current.subject() != null || request.visibility() != Visibility.VISIBLE) throw changed();
            } else {
                if (!before.subject().equals(identity(request.expectedSubject()))) throw changed();
                checkSubject(request.expectedSubject(), current);
            }
            if (request.visibility() == Visibility.HIDDEN && before.subject().kind() == SubjectKind.TARGET_REQUIREMENT
                    && !current.currentTarget()) throw changed();
            if (before.visibility() == request.visibility()) return readResponse(json, before);
            Instant time = now(); clock(time, before.updatedAt());
            var saved = new NextStepPreference(id, next(before.revision()), before.subject(), request.visibility(), before.createdAt(), time);
            storedPreference(saved);
            if (mapper.updatePreference(row(owner, saved), before.revision()) != 1) throw revisionConflict(before.revision());
            appendHistory(owner, saved, PreferenceChangeKind.UPDATED);
            return readResponse(json, saved);
        }));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PreferenceLookup get(String owner, String id) {
        uuid(owner); uuid(id);
        return available(() -> {
            Instant asOf = now();
            var result = entry(owner, requirePreference(owner, id), asOf);
            return readResponse(json, new PreferenceLookup(context(asOf), result));
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PreferenceSubjectLookup bySubject(String owner, PreferenceSubjectQuery query) {
        uuid(owner); required(query); required(query.subjectKind());
        SubjectIdentity identity;
        if (query.subjectKind() == SubjectKind.SOURCE) {
            if (query.targetId() != null || query.targetVersionId() != null || query.requirementId() != null) throw invalid("来源主体不能携带目标要求。");
            identity = identity(new SubjectIdentity(query.subjectKind(), new TodayDtos.SourceRef(query.sourceKind(), query.sourceId()), null));
        } else {
            if (query.sourceKind() != null || query.sourceId() != null) throw invalid("目标要求不能携带来源身份。");
            identity = identity(new SubjectIdentity(query.subjectKind(), null,
                    new RequirementIdentity(target(query.targetId(), query.targetVersionId()), query.requirementId())));
        }
        return available(() -> {
            Instant asOf = now(); var saved = find(owner, identity);
            if (saved == null) requireExists(owner, identity);
            var result = saved == null ? null : entry(owner, saved, asOf);
            return readResponse(json, new PreferenceSubjectLookup(context(asOf), identity, result));
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PreferencePage directory(String owner, PreferenceQuery query) {
        uuid(owner); required(query); int limit = pageSize(query.limit());
        return available(() -> {
            Instant checked = now();
            var cursor = directoryCursor(query.cursor(), owner, CursorPurpose.PREFERENCE_DIRECTORY, checked);
            Instant asOf = cursor == null ? checked : cursor.asOf();
            String scope = preferenceDirectoryScopeHash(query.visibility(), query.subjectKind());
            if (cursor != null) cursorScope(cursor.scopeHash(), scope);
            var position = cursor == null ? null : cursor.position();
            var saved = directoryRows(owner, query, asOf, position, limit);
            var entries = new ArrayList<PreferenceEntry>();
            for (var value : saved) entries.add(entry(owner, value, checked));
            String nextCursor = null;
            // 满 50 行时另做单行探测，使每次 mapper 读取都保持在 50 行以内。
            if (saved.size() == limit) {
                var last = saved.getLast(); var after = new DirectoryPosition(last.createdAt(), last.id());
                if (!directoryRows(owner, query, asOf, after, 1).isEmpty()) {
                    nextCursor = cursor(new DirectoryCursor(1, CursorPurpose.PREFERENCE_DIRECTORY, ownerFingerprint(owner), scope, asOf,
                            cursor == null ? checked : cursor.issuedAt(), after));
                }
            }
            return readResponse(json, new PreferencePage(context(asOf), query.visibility(), query.subjectKind(), List.copyOf(entries), nextCursor));
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PreferenceHistoryPage history(String owner, String id, Long before, int limit) {
        uuid(owner); uuid(id); nullableRevision(before); pageSize(limit);
        return available(() -> {
            var current = requirePreference(owner, id);
            var rows = mapper.preferenceHistory(owner, id, before, limit); requireRows(rows, limit);
            if (before == null && (rows.isEmpty() || rows.getFirst().revision() != current.revision())) throw integrity();
            var items = new ArrayList<PreferenceHistory>(); Long previous = before; Instant previousTime = null;
            for (var row : rows) {
                var item = history(owner, current, row);
                if (previous != null && item.snapshot().revision() >= previous
                        || previousTime != null && item.createdAt().isAfter(previousTime)) throw integrity();
                previous = item.snapshot().revision(); previousTime = item.createdAt(); items.add(item);
            }
            Long nextBefore = null;
            if (items.size() == limit) {
                var last = items.getLast();
                var tail = mapper.preferenceHistory(owner, id, last.snapshot().revision(), 1); requireRows(tail, 1);
                if (!tail.isEmpty()) {
                    var probe = history(owner, current, tail.getFirst());
                    if (probe.snapshot().revision() >= last.snapshot().revision() || probe.createdAt().isAfter(last.createdAt())) throw integrity();
                    nextBefore = last.snapshot().revision();
                }
            }
            return readResponse(json, new PreferenceHistoryPage(List.copyOf(items), nextBefore));
        });
    }

    NextStepPreference find(String owner, SubjectIdentity identity) {
        identity(identity);
        var row = mapper.preferenceBySubject(owner, identity.kind().name(), subjectKey(identity));
        if (row == null) return null;
        var saved = snapshot(owner, row);
        if (!saved.subject().equals(identity)) throw integrity();
        return saved;
    }

    private NextStepPreference requirePreference(String owner, String id) {
        var row = mapper.preference(owner, id);
        if (row == null) throw new ApiException(404, "NEXT_STEP_PREFERENCE_NOT_FOUND", "建议显示偏好不存在。");
        var saved = snapshot(owner, row);
        if (!id.equals(saved.id())) throw integrity(); return saved;
    }

    private void requireExists(String owner, SubjectIdentity identity) {
        if (identity.kind() == SubjectKind.SOURCE) {
            if (!sources.sourceExists(owner, identity.source().kind().name(), identity.source().id())) throw missingSubject();
        } else {
            try { targets.require(owner, identity.requirement().target()).requirement(identity.requirement().requirementId()); }
            catch (ApiException exception) { if (exception.status() == 404) throw missingSubject(); throw exception; }
        }
    }

    private record Current(Subject subject, String title, NextStepContinuation continuation, DirectState state, boolean currentTarget) {}

    private Current current(String owner, SubjectIdentity identity, Instant checked) {
        if (identity.kind() == SubjectKind.SOURCE) {
            if (!sources.sourceExists(owner, identity.source().kind().name(), identity.source().id())) return unavailableSubject();
            try {
                var item = today.sourceForPlan(owner, identity.source(), "UTC", checked);
                if (item == null || !identity.source().equals(item.source())) throw integrity();
                var subject = subject(new Subject(SubjectKind.SOURCE, item.source(), item.stamp(), null, null));
                text(item.title(), 200, true); required(item.continuation());
                DirectState state = item.reasons().contains(TodayDtos.Reason.MEMORY_SOURCE_REVIEW) ? DirectState.REVIEW_REQUIRED : DirectState.AVAILABLE;
                return new Current(subject, item.title(), new NextStepContinuation(NextStepRoute.SOURCE, item.continuation(), null), state, false);
            } catch (ApiException exception) {
                if (exception.status() == 400 || exception.status() == 404 || exception.status() == 503) throw unavailable();
                throw exception;
            }
        }
        try {
            var target = targets.require(owner, identity.requirement().target());
            var requirement = target.requirement(identity.requirement().requirementId());
            return new Current(subject(new Subject(SubjectKind.TARGET_REQUIREMENT, null, null, identity.requirement(), target.target().itemRevision())),
                    requirementTitle(target.target(), requirement), new NextStepContinuation(NextStepRoute.TARGET_REQUIREMENT, null, identity.requirement()),
                    DirectState.AVAILABLE, target.current());
        } catch (ApiException exception) { if (exception.status() == 404) return unavailableSubject(); throw exception; }
    }

    private PreferenceEntry entry(String owner, NextStepPreference saved, Instant checked) {
        var current = current(owner, saved.subject(), checked);
        return new PreferenceEntry(saved, current.subject(), current.title(), current.continuation(), current.state());
    }

    static String requirementTitle(TargetMeta target, RequirementMeta requirement) {
        String value = target.title() + " · 要求 " + requirement.ordinal();
        return value.codePointCount(0, value.length()) <= 200 ? value : value.substring(0, value.offsetByCodePoints(0, 199)) + "…";
    }

    private static Current unavailableSubject() { return new Current(null, "来源暂不可用", null, DirectState.UNAVAILABLE, false); }
    private static void checkSubject(Subject expected, Current current) {
        if (current.subject() == null || !expected.equals(current.subject())) throw changed();
    }

    private List<NextStepPreference> directoryRows(String owner, PreferenceQuery query, Instant asOf, DirectoryPosition position, int limit) {
        var rows = mapper.preferences(owner, name(query.visibility()), name(query.subjectKind()), asOf,
                position == null ? null : position.createdAt(), position == null ? null : position.id(), limit);
        requireRows(rows, limit);
        var saved = new ArrayList<NextStepPreference>(); var previous = position;
        for (var row : rows) {
            var value = snapshot(owner, row);
            if (query.visibility() != null && value.visibility() != query.visibility()
                    || query.subjectKind() != null && value.subject().kind() != query.subjectKind()
                    || !value.createdAt().isBefore(asOf)) throw integrity();
            if (previous != null && (value.createdAt().isAfter(previous.createdAt())
                    || value.createdAt().equals(previous.createdAt()) && value.id().compareTo(previous.id()) <= 0)) throw integrity();
            previous = new DirectoryPosition(value.createdAt(), value.id()); saved.add(value);
        }
        return List.copyOf(saved);
    }

    private static NextStepPreference snapshot(String owner, PreferenceRow row) {
        try {
            if (row == null || !owner.equals(row.ownerId())) throw integrity();
            var kind = required(enumValue(row.subjectKind(), SubjectKind.class));
            var source = row.sourceKind() == null && row.sourceId() == null ? null
                    : new TodayDtos.SourceRef(enumValue(row.sourceKind(), TodayDtos.SourceKind.class), row.sourceId());
            var requirement = row.targetId() == null && row.targetVersionId() == null && row.requirementId() == null ? null
                    : new RequirementIdentity(new TargetRef(row.targetId(), row.targetVersionId()), row.requirementId());
            var identity = identity(new SubjectIdentity(kind, source, requirement));
            if (!subjectKey(identity).equals(row.subjectKey())) throw integrity();
            var value = new NextStepPreference(row.id(), row.revision(), identity, required(enumValue(row.visibility(), Visibility.class)), row.createdAt(), row.updatedAt());
            storedPreference(value); return value;
        } catch (IllegalArgumentException | ApiException exception) { throw integrity(); }
    }

    private static PreferenceRow row(String owner, NextStepPreference value) {
        var subject = value.subject(); var source = subject.source(); var requirement = subject.requirement();
        return new PreferenceRow(value.id(), owner, subject.kind().name(), subjectKey(subject),
                source == null ? null : source.kind().name(), source == null ? null : source.id(),
                requirement == null ? null : requirement.target().itemId(), requirement == null ? null : requirement.target().versionId(),
                requirement == null ? null : requirement.requirementId(), value.revision(), value.visibility().name(), value.createdAt(), value.updatedAt());
    }

    private void appendHistory(String owner, NextStepPreference saved, PreferenceChangeKind change) {
        GrowthRules.history(json, new PreferenceHistory(change, saved, saved.updatedAt()));
        if (mapper.insertPreferenceHistory(new PreferenceHistoryRow(owner, saved.id(), saved.revision(), change.name(),
                json.writeValueAsString(saved), saved.updatedAt())) != 1) throw integrity();
    }

    private PreferenceHistory history(String owner, NextStepPreference current, PreferenceHistoryRow row) {
        try {
            if (!owner.equals(row.ownerId()) || !current.id().equals(row.preferenceId()) || row.snapshotJson() == null) throw integrity();
            byte[] raw = row.snapshotJson().getBytes(StandardCharsets.UTF_8);
            if (raw.length > HISTORY_BYTES) throw integrity();
            var saved = decode(raw, NextStepPreference.class); storedPreference(saved);
            var change = required(enumValue(row.changeKind(), PreferenceChangeKind.class));
            if (!saved.id().equals(current.id()) || !saved.subject().equals(current.subject()) || saved.revision() != row.revision()
                    || saved.revision() > current.revision() || !saved.createdAt().equals(current.createdAt())
                    || !saved.updatedAt().equals(row.createdAt()) || saved.updatedAt().isAfter(current.updatedAt())
                    || saved.revision() == current.revision() && !saved.equals(current)
                    || (saved.revision() == 0) != (change == PreferenceChangeKind.CREATED)) throw integrity();
            return GrowthRules.history(json, new PreferenceHistory(change, saved, row.createdAt()));
        } catch (IllegalArgumentException | ApiException exception) { throw integrity(); }
    }

    private static <T> T available(Supplier<T> operation) {
        try { return operation.get(); }
        catch (ApiException exception) { if (exception.status() == 503) throw unavailable(); throw exception; }
        catch (RuntimeException exception) { throw unavailable(); }
    }
    private static void requireRows(List<?> rows, int maximum) {
        if (rows == null || rows.size() > maximum || rows.stream().anyMatch(Objects::isNull)) throw integrity();
    }
    private static String name(Enum<?> value) { return value == null ? null : value.name(); }
    private static Context context(Instant asOf) { Instant checked = now(); return new Context(asOf, checked, checked); }
    private static ApiException changed() { return conflict("NEXT_STEP_SUBJECT_CHANGED", "建议主体或当前来源已变化，请重新读取后保存。", null); }
    private static ApiException revisionConflict(long revision) { return conflict("NEXT_STEP_PREFERENCE_REVISION_CONFLICT", "显示偏好已变化，请保留意图并重新读取。", revision); }
    private static ApiException missingSubject() { return new ApiException(404, "NEXT_STEP_SUBJECT_NOT_FOUND", "建议主体不存在。"); }
}
