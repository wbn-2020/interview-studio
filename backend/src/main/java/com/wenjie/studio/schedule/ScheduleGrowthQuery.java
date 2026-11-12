package com.wenjie.studio.schedule;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.growth.GrowthActivitySupport;
import com.wenjie.studio.growth.GrowthRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.wenjie.studio.growth.GrowthDtos.*;

/** 已完成表示用户显式确认已发生，不能由计划开始时间、提醒或复盘记录代替。 */
@Service
@Transactional(propagation = Propagation.MANDATORY, noRollbackFor = RuntimeException.class)
public class ScheduleGrowthQuery {
    private final ScheduleGrowthMapper mapper;

    public ScheduleGrowthQuery(ScheduleGrowthMapper mapper) { this.mapper = mapper; }

    public DomainActivityWindow activity(String owner, Scope recordedScope, Grain grain) {
        GrowthRules.uuid(owner); GrowthRules.scope(recordedScope); GrowthRules.required(grain);
        var collector = GrowthActivitySupport.collector(recordedScope, grain, Set.of(ActivityMetric.INTERVIEWS_COMPLETED));
        if (recordedScope.filter().topicId() != null || recordedScope.filter().dimension() != null) return collector.finish(false);
        int scanned = 0; boolean exhausted = false;
        ScheduleGrowthRows.Candidate after = null;
        Set<String> completed = new HashSet<>();
        while (scanned < GrowthRules.MAX_CANDIDATES) {
            int take = Math.min(49, GrowthRules.MAX_CANDIDATES - scanned);
            var candidates = mapper.candidates(owner, recordedScope.window().startAt(), recordedScope.window().observedUntil(), after, take + 1);
            if (candidates == null || candidates.size() > take + 1) throw GrowthRules.integrity();
            int size = Math.min(take, candidates.size());
            if (size == 0) { exhausted = true; break; }
            List<ScheduleGrowthRows.Candidate> keys = candidates.subList(0, size);
            Map<String, ScheduleGrowthRows.CompletionRow> rows = rows(mapper.completions(owner, keys));
            if (rows.size() != size) throw GrowthRules.integrity();
            for (var candidate : keys) {
                validateCandidate(candidate, after, recordedScope);
                var row = rows.get(key(candidate.eventId(), candidate.revision()));
                TargetRef target = validate(owner, candidate, row);
                // 原面试完成后不可再次变更；重复完成修订不能被静默分配到两个活动桶。
                if (!completed.add(candidate.eventId())) throw GrowthRules.integrity();
                if (recordedScope.filter().target() == null || recordedScope.filter().target().equals(target)) {
                    collector.add(ActivityMetric.INTERVIEWS_COMPLETED, candidate.eventId(), candidate.occurredAt());
                }
                scanned++; after = candidate;
            }
            exhausted = candidates.size() <= take;
            if (exhausted) break;
        }
        return collector.finish(!exhausted);
    }

    private static TargetRef validate(String owner, ScheduleGrowthRows.Candidate candidate, ScheduleGrowthRows.CompletionRow row) {
        if (row == null) throw GrowthRules.integrity();
        try {
            GrowthRules.time(row.startAt());
            if (!Boolean.TRUE.equals(row.metadataTypesValid()) || !owner.equals(row.ownerId()) || !candidate.eventId().equals(row.eventId()) || row.revision() != candidate.revision()
                    || !candidate.occurredAt().equals(row.occurredAt()) || !row.eventId().equals(row.ownedEventId())
                    || !row.eventId().equals(row.snapshotId()) || !"COMPLETED".equals(row.action()) || !"COMPLETED".equals(row.status())
                    || !"COMPLETED".equals(row.snapshotStatus()) || !"INTEGER".equals(row.snapshotRevisionType())
                    || !Long.toString(row.revision()).equals(row.snapshotRevision())
                    || !row.eventId().equals(row.previousEventId()) || row.previousRevision() == null || row.previousRevision() != row.revision() - 1
                    || !"PLANNED".equals(row.previousStatus()) || row.occurredAt().isBefore(row.startAt())
                    || !row.startAt().equals(time(row.snapshotStartAt())) || !row.occurredAt().equals(time(row.snapshotUpdatedAt()))) {
                throw GrowthRules.integrity();
            }
            time(row.snapshotCreatedAt());
            // 目标取完成修订自带的确切版本，不读取当前面试或当前岗位的目标关系。
            if (row.targetId() == null && row.targetVersionId() == null) {
                if (!"null".equals(row.snapshotTargetId()) || !"null".equals(row.snapshotTargetVersionId())
                        || row.ownedTargetVersionId() != null) throw GrowthRules.integrity();
                return null;
            }
            TargetRef target = new TargetRef(row.targetId(), row.targetVersionId()); GrowthRules.target(target);
            if (!target.itemId().equals(row.snapshotTargetId()) || !target.versionId().equals(row.snapshotTargetVersionId())
                    || !target.versionId().equals(row.ownedTargetVersionId())) throw GrowthRules.integrity();
            return target;
        } catch (ApiException | IllegalArgumentException | NullPointerException exception) { throw GrowthRules.integrity(); }
    }

    private static void validateCandidate(ScheduleGrowthRows.Candidate value, ScheduleGrowthRows.Candidate after, Scope scope) {
        try {
            GrowthRules.uuid(value.eventId()); GrowthRules.range(value.revision(), 1, GrowthRules.MAX, "面试完成修订"); GrowthRules.time(value.occurredAt());
            boolean wrongOrder = after != null && (value.occurredAt().isAfter(after.occurredAt())
                    || value.occurredAt().equals(after.occurredAt()) && (value.eventId().compareTo(after.eventId()) < 0
                    || value.eventId().equals(after.eventId()) && value.revision() <= after.revision()));
            if (wrongOrder || value.occurredAt().isBefore(scope.window().startAt()) || !value.occurredAt().isBefore(scope.window().observedUntil())) {
                throw GrowthRules.integrity();
            }
        } catch (ApiException | NullPointerException exception) { throw GrowthRules.integrity(); }
    }
    private static Map<String, ScheduleGrowthRows.CompletionRow> rows(List<ScheduleGrowthRows.CompletionRow> rows) {
        if (rows == null) throw GrowthRules.integrity();
        Map<String, ScheduleGrowthRows.CompletionRow> result = new HashMap<>();
        for (var row : rows) if (row == null || result.put(key(row.eventId(), row.revision()), row) != null) throw GrowthRules.integrity();
        return result;
    }
    private static Instant time(String value) {
        if (value == null || !value.endsWith("Z")) throw GrowthRules.integrity();
        try { Instant result = Instant.parse(value); GrowthRules.time(result); return result; }
        catch (RuntimeException exception) { throw GrowthRules.integrity(); }
    }
    private static String key(String id, long revision) { return id + ":" + revision; }
}
