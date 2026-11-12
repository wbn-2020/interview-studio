package com.wenjie.studio.planning;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import com.wenjie.studio.planning.PlanningDtos.*;
import com.wenjie.studio.planning.PlanningRows.HistoryRow;
import com.wenjie.studio.planning.PlanningRows.TaskRow;
import com.wenjie.studio.prepare.PreparationDtos.Kind;
import com.wenjie.studio.prepare.PreparationDtos.SourceState;
import com.wenjie.studio.prepare.PreparationMapper;
import com.wenjie.studio.prepare.PreparationSources;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.wenjie.studio.planning.PlanningRules.*;

@Service
public class PlanningService {
    private final PlanningMapper mapper;
    private final PreparationMapper preparation;
    private final PreparationSources sources;
    private final IdempotencyService commands;
    private final JsonMapper json;

    public PlanningService(PlanningMapper mapper, PreparationMapper preparation, PreparationSources sources,
                           IdempotencyService commands, JsonMapper json) {
        this.mapper = mapper;
        this.preparation = preparation;
        this.sources = sources;
        this.commands = commands;
        this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TaskPage list(String owner, Status status, String targetId, String from, String to,
                         String cursor, int limit) {
        uuid(owner);
        limit(limit);
        if (cursor != null) uuid(cursor);
        if (targetId != null) {
            var target = preparation.item(owner, uuid(targetId));
            if (target == null || !target.kind().equals(Kind.TARGET.name())) throw sourceUnavailable();
        }
        if ((from == null) != (to == null)) throw invalid("日期筛选须同时提供起止日期。");
        LocalDate first = date(from), last = date(to);
        // 按保存的本地日期做闭区间筛选，不用当前时区把日期换算成 UTC，也不移动过去的安排。
        if (first != null && (last.isBefore(first) || ChronoUnit.DAYS.between(first, last) >= 366)) {
            throw invalid("日期筛选须为包含首尾且不超过 366 天的有效区间。");
        }
        var rows = mapper.tasks(owner, status == null ? null : status.name(), targetId, first, last, cursor, limit + 1);
        var page = rows.stream().limit(limit).map(this::snapshot).toList();
        return new TaskPage(page, rows.size() > limit ? page.getLast().id() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TaskDetail detail(String owner, String id) {
        return detail(owner, requireTask(uuid(owner), uuid(id)));
    }

    public TaskDetail create(String owner, String key, CreateTask request, JsonNode original) {
        if (request == null) throw invalid("请求内容不能为空。");
        content(request.content());
        return commands.execute(owner, key, "POST /api/v1/prepare/tasks", original, TaskDetail.class, () -> {
            TargetSnapshot target = target(owner, request.target());
            TaskRow row = create(owner, target, null, null, request.content(), now());
            return detail(owner, row);
        });
    }

    public TaskDetail save(String owner, String id, String key, SaveTask request, JsonNode original) {
        uuid(id);
        if (request == null) throw invalid("请求内容不能为空。");
        revision(request.expectedRevision());
        content(request.content());
        return commands.execute(owner, key, "PUT /api/v1/prepare/tasks/" + id, original, TaskDetail.class, () -> {
            TaskRow before = changeable(owner, id, request.expectedRevision());
            TaskRow after = next(before, request.content(), Status.valueOf(before.status()), before.startedAt(), null, now());
            update(before, after, "EDIT");
            return detail(owner, after);
        });
    }

    public TaskDetail status(String owner, String id, String key, ChangeStatus request, JsonNode original) {
        uuid(id);
        if (request == null || request.status() == null) throw invalid("任务状态不能为空。");
        revision(request.expectedRevision());
        return commands.execute(owner, key, "POST /api/v1/prepare/tasks/" + id + "/status", original,
                TaskDetail.class, () -> {
                    TaskRow before = changeable(owner, id, request.expectedRevision());
                    Status current = Status.valueOf(before.status()), next = request.status();
                    boolean allowed = switch (current) {
                        case TODO -> next == Status.IN_PROGRESS || next == Status.PAUSED || next == Status.DONE || next == Status.CANCELLED;
                        case IN_PROGRESS -> next == Status.PAUSED || next == Status.DONE || next == Status.CANCELLED;
                        case PAUSED -> (next == Status.TODO && before.startedAt() == null)
                                || next == Status.IN_PROGRESS || next == Status.DONE || next == Status.CANCELLED;
                        case DONE, CANCELLED -> false;
                    };
                    if (!allowed) throw new ApiException(409, "TASK_STATUS_CONFLICT", "该任务不能按所选方式变更状态。");
                    Instant now = now();
                    // 暂停和恢复保留首次显式开始；直接勾选完成不能伪造实际开始或学习证据。
                    Instant started = next == Status.IN_PROGRESS && before.startedAt() == null ? now : before.startedAt();
                    TaskRow after = next(before, snapshot(before).content(), next, started,
                            next == Status.DONE ? now : null, now);
                    update(before, after, "STATUS");
                    return detail(owner, after);
                });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public HistoryPage history(String owner, String id, Long before, int limit) {
        requireTask(uuid(owner), uuid(id));
        limit(limit);
        if (before != null) revision(before);
        var rows = mapper.history(owner, id, before, limit + 1);
        var page = rows.stream().limit(limit).map(row ->
                new Revision(row.action(), json.readValue(row.snapshotJson(), PreparationTask.class))).toList();
        return new HistoryPage(page, rows.size() > limit ? page.getLast().snapshot().revision() : null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<PreparationTask> adopt(String owner, TargetLink target, String aiTaskId, List<Content> contents) {
        // AI 外层命令已经持有 owner 锁并复核全部授权；任务、采用决定与回执必须一起提交。
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("准备建议采用必须处于命令事务中");
        }
        uuid(owner);
        uuid(aiTaskId);
        if (target == null) throw invalid("准备建议须绑定明确的目标版本。");
        if (contents == null || contents.isEmpty() || contents.size() > 20 || contents.stream().anyMatch(value -> value == null)) {
            throw invalid("一次采用须包含 1 至 20 个准备任务。");
        }
        List<Content> values = List.copyOf(contents);
        values.forEach(PlanningRules::content);
        TargetSnapshot fixedTarget = target(owner, target);
        Instant now = now();
        var results = new ArrayList<PreparationTask>();
        for (int i = 0; i < values.size(); i++) {
            results.add(snapshot(create(owner, fixedTarget, aiTaskId, i + 1, values.get(i), now)));
        }
        return List.copyOf(results);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PreparationTask rescheduleDateWithinCommand(String owner, String taskId, long expectedRevision,
                                                        LocalDate plannedDate, Instant now) {
        // 来源日期锁由持有同 owner 锁的协调器核对，此处只改任务原日期并保留原区域。
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("准备任务联动改期必须处于命令事务中");
        }
        uuid(owner); uuid(taskId); revision(expectedRevision);
        if (plannedDate == null) throw invalid("联动改期日期不能为空。");
        date(plannedDate.toString());
        if (now == null || now.getNano() % 1000 != 0 || now.isBefore(Instant.parse("1000-01-01T00:00:00Z"))
                || now.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"))) {
            throw invalid("联动改期时刻须在 1000 至 9999 年范围内且精确到微秒。");
        }
        TaskRow before = requireTask(owner, taskId);
        if (before.revision() != expectedRevision) {
            throw new ApiException(409, "REVISION_CONFLICT", "准备任务已变化，请重新核对改期计划。", before.revision());
        }
        if (!Status.TODO.name().equals(before.status()) || before.startedAt() != null || before.plannedDate() == null) {
            throw new ApiException(409, "PREPARATION_LINK_NOT_MOVABLE", "准备任务已开始、暂停、结束或没有原安排日期，不能联动改期。");
        }
        date(before.plannedDate().toString()); zone(before.zoneId());
        if (plannedDate.equals(before.plannedDate())) return snapshot(before);
        if (before.revision() == MAX_REVISION) throw new ApiException(409, "REVISION_LIMIT", "准备任务修订号已达到上限。");
        if (now.isBefore(before.updatedAt())) {
            throw new ApiException(409, "RESCHEDULE_CLOCK_CHANGED", "服务器时间早于最近修改，请重新核对改期计划。");
        }
        Content content = new Content(before.title(), before.detail(), before.minutes(), plannedDate.toString(), before.zoneId());
        TaskRow after = next(before, content, Status.TODO, before.startedAt(), before.completedAt(), now);
        update(before, after, "RESCHEDULE");
        return snapshot(after);
    }

    private TaskRow create(String owner, TargetSnapshot target, String aiTaskId, Integer sourceStep,
                           Content content, Instant now) {
        TaskRow row = new TaskRow(UUID.randomUUID().toString(), owner, 0, content.title(), content.detail(),
                content.minutes(), date(content.plannedDate()), content.zoneId(), Status.TODO.name(),
                target == null ? null : target.itemId(), target == null ? null : target.versionId(),
                target == null ? null : target.number(), target == null ? null : target.title(), aiTaskId, sourceStep,
                null, null, now, now);
        mapper.insertTask(row);
        history(row, "CREATE");
        return row;
    }

    private void update(TaskRow before, TaskRow after, String action) {
        if (mapper.updateTask(after, before.revision()) != 1) {
            throw new ApiException(409, "REVISION_CONFLICT", "准备任务已变化，本次修改未提交。");
        }
        history(after, action);
    }

    private void history(TaskRow row, String action) {
        mapper.insertHistory(new HistoryRow(row.ownerId(), row.id(), row.revision(), action,
                json.writeValueAsString(snapshot(row)), row.updatedAt()));
    }

    private TaskRow next(TaskRow before, Content content, Status status, Instant startedAt,
                         Instant completedAt, Instant now) {
        // 目标和 AI 来源创建后保持不变；人工调整只产生任务修订，不改写生成来源。
        return new TaskRow(before.id(), before.ownerId(), before.revision() + 1, content.title(), content.detail(),
                content.minutes(), date(content.plannedDate()), content.zoneId(), status.name(), before.targetItemId(),
                before.targetVersionId(), before.targetVersionNumber(), before.targetTitle(), before.sourceAiTaskId(),
                before.sourceStep(), startedAt, completedAt, before.createdAt(), now);
    }

    private TargetSnapshot target(String owner, TargetLink link) {
        if (link == null) return null;
        uuid(link.itemId());
        uuid(link.versionId());
        var checked = sources.check(owner, Kind.TARGET, link.itemId(), link.versionId());
        if (checked.state() == SourceState.UNAVAILABLE) throw sourceUnavailable();
        if (checked.state() != SourceState.CURRENT) {
            throw new ApiException(409, "SOURCE_CHANGED", "目标版本已改版或归档，请重新选择当前目标。");
        }
        var version = preparation.version(owner, link.itemId(), link.versionId());
        if (version == null) throw new IllegalStateException("准备任务目标版本不完整");
        return new TargetSnapshot(version.itemId(), version.id(), version.versionNumber(), version.title());
    }

    private TaskDetail detail(String owner, TaskRow row) {
        // 详情只查询原目标的可用性；目标变化不替换旧标题，也不阻止人工完成已有任务。
        return new TaskDetail(snapshot(row), row.targetItemId() == null ? null
                : sources.check(owner, Kind.TARGET, row.targetItemId(), row.targetVersionId()));
    }

    private PreparationTask snapshot(TaskRow row) {
        TargetSnapshot target = row.targetItemId() == null ? null
                : new TargetSnapshot(row.targetItemId(), row.targetVersionId(), row.targetVersionNumber(), row.targetTitle());
        Content content = new Content(row.title(), row.detail(), row.minutes(),
                row.plannedDate() == null ? null : row.plannedDate().toString(), row.zoneId());
        return new PreparationTask(row.id(), row.revision(), content, Status.valueOf(row.status()), target,
                row.sourceAiTaskId(), row.sourceStep(), row.startedAt(), row.completedAt(), row.createdAt(), row.updatedAt());
    }

    private TaskRow requireTask(String owner, String id) {
        TaskRow row = mapper.task(owner, id);
        if (row == null) throw new ApiException(404, "PREPARATION_TASK_NOT_FOUND", "准备任务不存在或不可访问。");
        return row;
    }

    private TaskRow changeable(String owner, String id, long expected) {
        TaskRow row = requireTask(owner, id);
        if (row.revision() != expected) {
            throw new ApiException(409, "REVISION_CONFLICT", "准备任务已被其他操作修改，请保留输入后重新读取。", row.revision());
        }
        if (row.revision() == MAX_REVISION) throw new ApiException(409, "REVISION_LIMIT", "准备任务修订号已达到上限。");
        if (row.status().equals(Status.DONE.name()) || row.status().equals(Status.CANCELLED.name())) {
            throw new ApiException(409, "TASK_CLOSED", "已完成或取消的准备任务仅可查看历史。");
        }
        return row;
    }

    private static ApiException sourceUnavailable() { return new ApiException(404, "SOURCE_UNAVAILABLE", "目标来源不存在或不可访问。"); }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
}
