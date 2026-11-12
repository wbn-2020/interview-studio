package com.wenjie.studio.planning;

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

/** 完成活动只依据显式状态历史，不让后来的任务安排或目标版本重写发生时归属。 */
@Service
@Transactional(propagation = Propagation.MANDATORY, noRollbackFor = RuntimeException.class)
public class PlanningGrowthQuery {
    private final PlanningGrowthMapper mapper;

    public PlanningGrowthQuery(PlanningGrowthMapper mapper) { this.mapper = mapper; }

    public DomainActivityWindow activity(String owner, Scope recordedScope, Grain grain) {
        GrowthRules.uuid(owner); GrowthRules.scope(recordedScope); GrowthRules.required(grain);
        var collector = GrowthActivitySupport.collector(recordedScope, grain, Set.of(ActivityMetric.TASKS_DONE));
        // 普通任务没有主题或考察项；不能借正文关键词或当前目标标签生成不存在的分类。
        if (recordedScope.filter().topicId() != null || recordedScope.filter().dimension() != null) return collector.finish(false);
        int scanned = 0; boolean exhausted = false;
        PlanningGrowthRows.Candidate after = null;
        Set<String> completed = new HashSet<>();
        while (scanned < GrowthRules.MAX_CANDIDATES) {
            int take = Math.min(49, GrowthRules.MAX_CANDIDATES - scanned);
            var candidates = mapper.candidates(owner, recordedScope.window().startAt(), recordedScope.window().observedUntil(), after, take + 1);
            if (candidates == null || candidates.size() > take + 1) throw GrowthRules.integrity();
            int size = Math.min(take, candidates.size());
            if (size == 0) { exhausted = true; break; }
            List<PlanningGrowthRows.Candidate> keys = candidates.subList(0, size);
            Map<String, PlanningGrowthRows.CompletionRow> rows = rows(mapper.completions(owner, keys));
            if (rows.size() != size) throw GrowthRules.integrity();
            for (var candidate : keys) {
                validateCandidate(candidate, after, recordedScope);
                var row = rows.get(key(candidate.taskId(), candidate.revision()));
                TargetRef target = validate(owner, candidate, row);
                // 当前原域 DONE 为终态；重复完成历史是完整性异常，不能拆到多个日期后再悄悄去重。
                if (!completed.add(candidate.taskId())) throw GrowthRules.integrity();
                if (recordedScope.filter().target() == null || recordedScope.filter().target().equals(target)) {
                    collector.add(ActivityMetric.TASKS_DONE, candidate.taskId(), candidate.occurredAt());
                }
                scanned++; after = candidate;
            }
            exhausted = candidates.size() <= take;
            if (exhausted) break;
        }
        return collector.finish(!exhausted);
    }

    private static TargetRef validate(String owner, PlanningGrowthRows.Candidate candidate, PlanningGrowthRows.CompletionRow row) {
        if (row == null) throw GrowthRules.integrity();
        try {
            if (!Boolean.TRUE.equals(row.metadataTypesValid()) || !owner.equals(row.ownerId()) || !candidate.taskId().equals(row.taskId()) || row.revision() != candidate.revision()
                    || !candidate.occurredAt().equals(row.occurredAt()) || !row.taskId().equals(row.ownedTaskId())
                    || !row.taskId().equals(row.snapshotId()) || !"STATUS".equals(row.action()) || !"DONE".equals(row.status())
                    || !"INTEGER".equals(row.snapshotRevisionType()) || !Long.toString(row.revision()).equals(row.snapshotRevision())
                    || !row.taskId().equals(row.previousTaskId()) || row.previousRevision() == null || row.previousRevision() != row.revision() - 1
                    || !Set.of("TODO", "IN_PROGRESS", "PAUSED").contains(row.previousStatus()) || !"null".equals(row.previousCompletedAt())
                    || !row.occurredAt().equals(time(row.completedAt())) || !row.occurredAt().equals(time(row.updatedAt()))) {
                throw GrowthRules.integrity();
            }
            time(row.createdAt());
            if (row.startedAt() == null) throw GrowthRules.integrity();
            if (!"null".equals(row.startedAt())) time(row.startedAt());
            if ("NULL".equals(row.targetType())) {
                if (row.targetId() != null || row.targetVersionId() != null || row.ownedTargetVersionId() != null) throw GrowthRules.integrity();
                return null;
            }
            if (!"OBJECT".equals(row.targetType())) throw GrowthRules.integrity();
            TargetRef target = new TargetRef(row.targetId(), row.targetVersionId()); GrowthRules.target(target);
            if (!target.versionId().equals(row.ownedTargetVersionId())) throw GrowthRules.integrity();
            return target;
        } catch (ApiException | IllegalArgumentException | NullPointerException exception) { throw GrowthRules.integrity(); }
    }

    private static void validateCandidate(PlanningGrowthRows.Candidate value, PlanningGrowthRows.Candidate after, Scope scope) {
        try {
            GrowthRules.uuid(value.taskId()); GrowthRules.range(value.revision(), 1, GrowthRules.MAX, "任务完成修订"); GrowthRules.time(value.occurredAt());
            boolean wrongOrder = after != null && (value.occurredAt().isAfter(after.occurredAt())
                    || value.occurredAt().equals(after.occurredAt()) && (value.taskId().compareTo(after.taskId()) < 0
                    || value.taskId().equals(after.taskId()) && value.revision() <= after.revision()));
            if (wrongOrder || value.occurredAt().isBefore(scope.window().startAt()) || !value.occurredAt().isBefore(scope.window().observedUntil())) {
                throw GrowthRules.integrity();
            }
        } catch (ApiException | NullPointerException exception) { throw GrowthRules.integrity(); }
    }
    private static Map<String, PlanningGrowthRows.CompletionRow> rows(List<PlanningGrowthRows.CompletionRow> rows) {
        if (rows == null) throw GrowthRules.integrity();
        Map<String, PlanningGrowthRows.CompletionRow> result = new HashMap<>();
        for (var row : rows) if (row == null || result.put(key(row.taskId(), row.revision()), row) != null) throw GrowthRules.integrity();
        return result;
    }
    private static Instant time(String value) {
        if (value == null || !value.endsWith("Z")) throw GrowthRules.integrity();
        try { Instant result = Instant.parse(value); GrowthRules.time(result); return result; }
        catch (RuntimeException exception) { throw GrowthRules.integrity(); }
    }
    private static String key(String id, long revision) { return id + ":" + revision; }
}
