package com.wenjie.studio.schedule;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import com.wenjie.studio.knowledge.KnowledgeMapper;
import com.wenjie.studio.prepare.PreparationMapper;
import com.wenjie.studio.prepare.PreparationRows;
import com.wenjie.studio.schedule.ScheduleDtos.*;
import com.wenjie.studio.schedule.SchedulePreparationDtos.EventReference;
import com.wenjie.studio.schedule.SchedulePreparationDtos.RescheduleBasis;
import com.wenjie.studio.schedule.ScheduleRows.EventRow;
import com.wenjie.studio.schedule.ScheduleRows.HistoryRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class ScheduleService {
    private static final long MAX_REVISION = 9_007_199_254_740_991L;
    private static final Instant MIN_TIME = Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant MAX_TIME = Instant.parse("9999-12-31T23:59:59.999999Z");
    private static final DateTimeFormatter LOCAL_MINUTE = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final Set<String> ZONES = ZoneId.getAvailableZoneIds();
    private final ScheduleMapper mapper;
    private final PreparationMapper preparation;
    private final KnowledgeMapper knowledge;
    private final IdempotencyService commands;
    private final JsonMapper json;

    public ScheduleService(ScheduleMapper mapper, PreparationMapper preparation, KnowledgeMapper knowledge,
                           IdempotencyService commands, JsonMapper json) {
        this.mapper = mapper;
        this.preparation = preparation;
        this.knowledge = knowledge;
        this.commands = commands;
        this.json = json.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    public TimeResolution resolve(String owner, JsonNode node) {
        ResolveTime request = read(node, ResolveTime.class);
        LocalDateTime local = local(request.localStart());
        var offsets = zone(request.zoneId()).getRules().getValidOffsets(local);
        var options = offsets.stream().map(offset -> new TimeOption(offset.getId(), inRange(local.toInstant(offset)))).toList();
        return new TimeResolution(offsets.isEmpty() ? ResolutionState.GAP
                : offsets.size() == 1 ? ResolutionState.UNIQUE : ResolutionState.OVERLAP, options);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EventPage list(String owner, String from, String to, String targetId, Status status,
                          String cursor, int limit) {
        limit(limit);
        Instant fromTime = instant(from);
        Instant toTime = instant(to);
        if (!fromTime.isBefore(toTime) || Duration.between(fromTime, toTime).compareTo(Duration.ofDays(366)) > 0) {
            throw invalid("安排查询须提供不超过 366 天的有效 UTC 区间。");
        }
        if (targetId != null) {
            var target = preparation.item(owner, uuid(targetId));
            if (target == null) throw sourceUnavailable();
            if (!target.kind().equals("TARGET")) throw invalid("目标筛选必须指向岗位目标。");
        }
        Cursor after = cursor(cursor);
        Instant asOf = now();
        var rows = mapper.events(owner, fromTime, toTime, targetId, status == null ? null : status.name(),
                after.time(), after.id(), limit + 1);
        return page(owner, rows, limit, asOf, false);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EventPage reminders(String owner, String cursor, int limit) {
        limit(limit);
        Cursor after = cursor(cursor);
        Instant asOf = now();
        return page(owner, mapper.reminders(owner, asOf, after.time(), after.id(), limit + 1), limit, asOf, true);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public InterviewEvent detail(String owner, String id) {
        EventRow row = requireEvent(owner, uuid(id));
        return view(owner, row, materials(owner, row), now());
    }

    public InterviewEvent create(String owner, String key, JsonNode node) {
        EventInput request = checkInput(read(node, EventInput.class));
        return commands.execute(owner, key, "POST /api/v1/schedule/interviews", node, InterviewEvent.class, () -> {
            newEventText(request);
            String targetVersion = targetVersion(owner, request.targetId(), null);
            validateMaterials(owner, request.materials(), Set.of());
            ResolvedTime time = resolve(request.time());
            Instant now = now();
            EventRow row = new EventRow(UUID.randomUUID().toString(), owner, request.targetId(), targetVersion,
                    request.title(), request.round(), request.location(), request.meetingUrl(), request.notes(),
                    local(time.localStart()), time.zoneId(), time.offset(), time.startAt(), time.endAt(),
                    time.durationMinutes(), request.remindBeforeMinutes(), remindAt(time, request.remindBeforeMinutes()),
                    null, Status.PLANNED.name(), 0, now, now);
            return persist(owner, null, row, request.materials(), Action.CREATED, "", now);
        });
    }

    public InterviewEvent edit(String owner, String id, String key, JsonNode node) {
        uuid(id);
        EditEvent request = read(node, EditEvent.class);
        revision(request.expectedRevision());
        EventInput input = checkInput(new EventInput(request.title(), request.targetId(), request.round(),
                request.location(), request.meetingUrl(), request.notes(), request.time(),
                request.remindBeforeMinutes(), request.materials()));
        return commands.execute(owner, key, "PUT /api/v1/schedule/interviews/" + id, node, InterviewEvent.class, () -> {
            newEventText(input);
            EventRow before = changeable(owner, id, request.expectedRevision());
            unchangedTime(before, input.time());
            List<MaterialLink> previous = materials(owner, before);
            validateMaterials(owner, input.materials(), Set.copyOf(previous));
            String targetVersion = targetVersion(owner, input.targetId(), before);
            Instant now = now();
            Instant acknowledged = input.remindBeforeMinutes() == before.remindBeforeMinutes()
                    ? before.reminderAcknowledgedAt() : null;
            return persist(owner, before, next(before, input, targetVersion, savedTime(before), acknowledged,
                    Status.PLANNED, now), input.materials(), Action.UPDATED, "", now);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReschedulePreview preview(String owner, String id, JsonNode node) {
        PreviewReschedule request = read(node, PreviewReschedule.class);
        revision(request.expectedRevision());
        lead(request.remindBeforeMinutes());
        EventRow before = changeable(owner, uuid(id), request.expectedRevision());
        ResolvedTime time = resolve(request.time());
        return new ReschedulePreview(savedTime(before), time, remindAt(time, request.remindBeforeMinutes()), false);
    }

    public InterviewEvent reschedule(String owner, String id, String key, JsonNode node) {
        uuid(id);
        Reschedule request = read(node, Reschedule.class);
        revision(request.expectedRevision());
        lead(request.remindBeforeMinutes());
        String reason = text(request.reason(), 2000, true, "改期原因").strip();
        return commands.execute(owner, key, "POST /api/v1/schedule/interviews/" + id + "/reschedule",
                node, InterviewEvent.class, () -> {
                    newText(reason, "改期原因");
                    EventRow before = changeable(owner, id, request.expectedRevision());
                    ResolvedTime time = resolve(request.time());
                    List<MaterialLink> links = materials(owner, before);
                    Instant now = now();
                    boolean changed = !time.equals(savedTime(before))
                            || request.remindBeforeMinutes() != before.remindBeforeMinutes();
                    // 只有实际改期或改变提前量才重置提醒，备注编辑不能重新制造到期提醒。
                    EventRow after = next(before, storedInput(before, links, request.remindBeforeMinutes()),
                            before.targetVersionId(), time, changed ? null : before.reminderAcknowledgedAt(), Status.PLANNED, now);
                    return persist(owner, before, after, links, Action.RESCHEDULED, reason, now);
                });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RescheduleBasis rescheduleBasis(String owner, String eventId, long expectedRevision,
                                            TimeInput time, int remindBeforeMinutes) {
        uuid(owner); uuid(eventId); revision(expectedRevision); lead(remindBeforeMinutes);
        EventRow before = changeable(owner, eventId, expectedRevision);
        ResolvedTime resolved = resolve(time);
        // 原时间直接取持久化 Instant，只有新时间按当前时区规则解析。
        return new RescheduleBasis(new EventReference(before.id(), before.revision(), Status.valueOf(before.status()),
                before.targetId(), before.targetVersionId()), savedTime(before), resolved, remindAt(resolved, remindBeforeMinutes));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public InterviewEvent rescheduleWithinCommand(String owner, String eventId, long expectedRevision,
                                                   ResolvedTime resolvedTime, int remindBeforeMinutes,
                                                   String reason, Instant now) {
        // 联动协调器已取得同 owner 命令锁；面试和准备事项必须共享本次事务与时刻。
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("面试联动改期必须处于命令事务中");
        }
        uuid(owner); uuid(eventId); revision(expectedRevision); lead(remindBeforeMinutes);
        inRange(required(now)); rescheduleReason(reason); required(resolvedTime);
        ResolvedTime checked = resolve(new TimeInput(resolvedTime.localStart(), resolvedTime.zoneId(),
                resolvedTime.offset(), resolvedTime.durationMinutes()));
        if (!checked.equals(resolvedTime)) throw invalid("改期时间须使用本次服务端解析的完整结果。");
        remindAt(checked, remindBeforeMinutes);
        EventRow before = changeable(owner, eventId, expectedRevision);
        if (now.isBefore(before.updatedAt())) {
            throw new ApiException(409, "RESCHEDULE_CLOCK_CHANGED", "服务器时间早于最近修改，请重新核对改期计划。");
        }
        List<MaterialLink> links = materials(owner, before);
        boolean changed = !checked.equals(savedTime(before)) || remindBeforeMinutes != before.remindBeforeMinutes();
        EventRow after = next(before, storedInput(before, links, remindBeforeMinutes), before.targetVersionId(), checked,
                changed ? null : before.reminderAcknowledgedAt(), Status.PLANNED, now);
        return persist(owner, before, after, links, Action.RESCHEDULED, reason, now);
    }

    public InterviewEvent decide(String owner, String id, String key, JsonNode node, Status status) {
        uuid(id);
        DecideEvent request = read(node, DecideEvent.class);
        revision(request.expectedRevision());
        String reason = text(request.reason(), 2000, status == Status.CANCELLED, "结果说明").strip();
        String action = status == Status.CANCELLED ? "cancel" : "complete";
        return commands.execute(owner, key, "POST /api/v1/schedule/interviews/" + id + "/" + action,
                node, InterviewEvent.class, () -> {
                    newText(reason, "结果说明");
                    EventRow before = changeable(owner, id, request.expectedRevision());
                    Instant now = now();
                    if (status == Status.COMPLETED && now.isBefore(before.startAt())) {
                        throw new ApiException(409, "EVENT_NOT_STARTED", "计划开始前不能把面试标为完成。");
                    }
                    List<MaterialLink> links = materials(owner, before);
                    EventRow after = next(before, storedInput(before, links, before.remindBeforeMinutes()),
                            before.targetVersionId(), savedTime(before), before.reminderAcknowledgedAt(), status, now);
                    return persist(owner, before, after, links,
                            status == Status.CANCELLED ? Action.CANCELLED : Action.COMPLETED, reason, now);
                });
    }

    public InterviewEvent acknowledge(String owner, String id, String key, JsonNode node) {
        uuid(id);
        Acknowledge request = read(node, Acknowledge.class);
        revision(request.expectedRevision());
        return commands.execute(owner, key, "POST /api/v1/schedule/interviews/" + id + "/acknowledge",
                node, InterviewEvent.class, () -> {
                    EventRow before = changeable(owner, id, request.expectedRevision());
                    Instant now = now();
                    if (now.isBefore(before.remindAt())) throw new ApiException(409, "REMINDER_NOT_DUE", "该提醒尚未到期。");
                    List<MaterialLink> links = materials(owner, before);
                    if (before.reminderAcknowledgedAt() != null) return view(owner, before, links, now);
                    EventRow after = next(before, storedInput(before, links, before.remindBeforeMinutes()),
                            before.targetVersionId(), savedTime(before), now, Status.PLANNED, now);
                    return persist(owner, before, after, links, Action.ACKNOWLEDGED, "", now);
                });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public HistoryPage history(String owner, String id, Long before, int limit) {
        requireEvent(owner, uuid(id));
        limit(limit);
        if (before != null) revision(before);
        var rows = mapper.histories(owner, id, before, limit + 1);
        boolean more = rows.size() > limit;
        var page = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        return new HistoryPage(page, more ? page.getLast().revision() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public InterviewEvent history(String owner, String id, long revision) {
        uuid(id);
        revision(revision);
        String snapshot = mapper.snapshot(owner, id, revision);
        if (snapshot == null) throw notFound();
        return json.readValue(snapshot, InterviewEvent.class);
    }

    private EventPage page(String owner, List<EventRow> rows, int limit, Instant asOf, boolean reminders) {
        boolean more = rows.size() > limit;
        List<EventRow> page = rows.subList(0, Math.min(limit, rows.size()));
        String nextCursor = more ? (reminders ? page.getLast().remindAt() : page.getLast().startAt())
                + "~" + page.getLast().id() : null;
        return new EventPage(page.stream().map(row -> view(owner, row, materials(owner, row), asOf)).toList(), nextCursor, asOf);
    }

    private InterviewEvent persist(String owner, EventRow before, EventRow after, List<MaterialLink> links,
                                    Action action, String reason, Instant now) {
        if (before == null) mapper.insertEvent(after);
        else if (mapper.updateEvent(after, before.revision()) != 1) {
            throw new ApiException(409, "REVISION_CONFLICT", "面试安排已变化，本次修改未提交。");
        }
        InterviewEvent result = view(owner, after, links, now);
        // 每个修订保留独立关系和快照；当前版本变更不能改写旧修订的来源或提醒状态。
        mapper.insertHistory(new HistoryRow(owner, after.id(), after.revision(), action.name(), after.targetId(),
                after.targetVersionId(), after.title(), after.startAt(), after.status(), reason, json.writeValueAsString(result), now));
        for (int i = 0; i < links.size(); i++) {
            MaterialLink link = links.get(i);
            mapper.insertMaterial(owner, after.id(), after.revision(), i, link.kind().name(), link.itemId(), link.versionId());
        }
        return result;
    }

    private EventRow next(EventRow before, EventInput input, String targetVersion, ResolvedTime time,
                           Instant acknowledged, Status status, Instant now) {
        return new EventRow(before.id(), before.ownerId(), input.targetId(), targetVersion, input.title(), input.round(),
                input.location(), input.meetingUrl(), input.notes(), local(time.localStart()), time.zoneId(), time.offset(),
                time.startAt(), time.endAt(), time.durationMinutes(), input.remindBeforeMinutes(),
                remindAt(time, input.remindBeforeMinutes()), acknowledged, status.name(), before.revision() + 1,
                before.createdAt(), now);
    }

    private EventInput storedInput(EventRow row, List<MaterialLink> materials, int lead) {
        return new EventInput(row.title(), row.targetId(), row.round(), row.location(), row.meetingUrl(), row.notes(), null, lead, materials);
    }

    private InterviewEvent view(String owner, EventRow row, List<MaterialLink> links, Instant asOf) {
        ReferenceState targetState = null;
        if (row.targetId() != null) {
            PreparationRows.ItemRow target = preparation.item(owner, row.targetId());
            PreparationRows.VersionRow version = preparation.version(owner, row.targetId(), row.targetVersionId());
            if (target == null || version == null || !target.kind().equals("TARGET")) throw sourceUnavailable();
            targetState = !target.status().equals("ACTIVE") ? ReferenceState.ARCHIVED
                    : !target.currentVersionId().equals(row.targetVersionId()) ? ReferenceState.SUPERSEDED : ReferenceState.CURRENT;
        }
        return new InterviewEvent(row.id(), row.targetId(), row.targetVersionId(), targetState, row.title(), row.round(),
                row.location(), row.meetingUrl(), row.notes(), savedTime(row), links,
                links.stream().map(link -> reference(owner, link)).toList(), row.remindBeforeMinutes(), row.remindAt(),
                row.reminderAcknowledgedAt(), Status.valueOf(row.status()), timing(row, asOf), row.revision(), row.createdAt(), row.updatedAt());
    }

    private static Timing timing(EventRow row, Instant asOf) {
        if (!row.status().equals(Status.PLANNED.name())) return Timing.CLOSED;
        if (!asOf.isBefore(row.endAt())) return Timing.PAST;
        if (!asOf.isBefore(row.startAt())) return Timing.IN_PROGRESS;
        return asOf.isBefore(row.remindAt()) ? Timing.UPCOMING : Timing.DUE;
    }

    private List<MaterialLink> materials(String owner, EventRow row) {
        return mapper.materials(owner, row.id(), row.revision()).stream().map(value ->
                new MaterialLink(MaterialKind.valueOf(value.sourceKind()), value.sourceItemId(), value.sourceVersionId())).toList();
    }

    private String targetVersion(String owner, String targetId, EventRow before) {
        if (targetId == null) return null;
        // 目标身份不变就保留原版本，修改安排备注不等于采用了目标的新要求。
        if (before != null && targetId.equals(before.targetId())) return before.targetVersionId();
        var target = preparation.item(owner, targetId);
        if (target == null || !target.kind().equals("TARGET")) throw sourceUnavailable();
        if (!target.status().equals("ACTIVE")) throw new ApiException(409, "SOURCE_CHANGED", "目标已归档，请重新选择当前目标。");
        if (preparation.version(owner, targetId, target.currentVersionId()) == null) throw sourceUnavailable();
        return target.currentVersionId();
    }

    private void validateMaterials(String owner, List<MaterialLink> links, Set<MaterialLink> preserved) {
        for (MaterialLink link : links) {
            ReferenceState state = reference(owner, link).state();
            if (state == ReferenceState.CURRENT || preserved.contains(link)) continue;
            if (state == ReferenceState.UNVERIFIED || state == ReferenceState.WITHDRAWN) {
                throw new ApiException(409, "FACT_NOT_CONFIRMED", "新增材料中的事实尚未确认或已撤回。");
            }
            if (state == ReferenceState.REVIEW_REQUIRED) {
                throw new ApiException(409, "SOURCE_REVIEW_REQUIRED", "表达素材的事实依据已变化，请先复核后再关联。");
            }
            throw new ApiException(409, "SOURCE_CHANGED", "新增材料已改版或归档，请重新选择当前有效版本。");
        }
    }

    private MaterialReference reference(String owner, MaterialLink link) {
        if (link.kind() == MaterialKind.KNOWLEDGE) {
            var item = knowledge.findItem(owner, link.itemId());
            var version = knowledge.findVersion(owner, link.itemId(), link.versionId());
            if (item == null || version == null || (!item.kind().equals("NOTE") && !item.kind().equals("CONCEPT"))) throw sourceUnavailable();
            ReferenceState state = !item.status().equals("ACTIVE") ? ReferenceState.ARCHIVED
                    : !Objects.equals(item.currentVersionId(), version.id()) ? ReferenceState.SUPERSEDED : ReferenceState.CURRENT;
            return new MaterialReference(link.kind(), link.itemId(), version.id(), version.title(), version.number(), state);
        }
        var item = preparation.item(owner, link.itemId());
        var version = preparation.version(owner, link.itemId(), link.versionId());
        if (item == null || version == null || !item.kind().equals(link.kind().name())) throw sourceUnavailable();
        ReferenceState state;
        if ("WITHDRAWN".equals(item.verification())) state = ReferenceState.WITHDRAWN;
        else if (!item.status().equals("ACTIVE") || (item.kind().equals("FACT") && !projectActive(owner, item.projectId()))) state = ReferenceState.ARCHIVED;
        else if ("UNVERIFIED".equals(item.verification())) state = ReferenceState.UNVERIFIED;
        else if (!item.currentVersionId().equals(version.id())) state = ReferenceState.SUPERSEDED;
        else if (item.kind().equals("EXPRESSION") && preparation.hasInvalidFactBasis(owner, version.id())) state = ReferenceState.REVIEW_REQUIRED;
        else state = ReferenceState.CURRENT;
        return new MaterialReference(link.kind(), item.id(), version.id(), version.title(), version.versionNumber(), state);
    }

    private boolean projectActive(String owner, String id) {
        var project = preparation.item(owner, id);
        return project != null && project.kind().equals("PROJECT") && project.status().equals("ACTIVE");
    }

    private EventInput checkInput(EventInput value) {
        required(value.time());
        lead(value.remindBeforeMinutes());
        if (value.targetId() != null) uuid(value.targetId());
        List<MaterialLink> links = value.materials() == null ? List.of() : value.materials();
        if (links.size() > 50 || links.stream().anyMatch(Objects::isNull)
                || new HashSet<>(links).size() != links.size()) throw invalid("关联材料最多 50 项，不能含空项或重复项。");
        for (MaterialLink link : links) {
            required(link);
            required(link.kind());
            uuid(link.itemId());
            uuid(link.versionId());
        }
        return new EventInput(text(value.title(), 200, true, "标题").strip(), value.targetId(),
                text(value.round(), 100, false, "面试轮次"), text(value.location(), 500, false, "地点"),
                url(value.meetingUrl()), text(value.notes(), 10000, false, "备注"), value.time(), value.remindBeforeMinutes(), List.copyOf(links));
    }

    private ResolvedTime resolve(TimeInput input) {
        required(input);
        duration(input.durationMinutes());
        LocalDateTime local = local(input.localStart());
        var offsets = zone(input.zoneId()).getRules().getValidOffsets(local);
        if (offsets.isEmpty()) throw new ApiException(400, "TIME_GAP", "该时区不存在这个本地时刻，请选择其他时间。");
        ZoneOffset offset;
        if (input.offset() == null) {
            if (offsets.size() != 1) throw new ApiException(400, "TIME_OFFSET_REQUIRED", "这个本地时刻出现两次，请明确选择时区偏移。");
            offset = offsets.getFirst();
        } else {
            offset = offset(input.offset());
            if (!offsets.contains(offset)) throw new ApiException(400, "INVALID_TIME_OFFSET", "所选偏移不属于该时区的这个本地时刻。");
        }
        Instant start = inRange(local.toInstant(offset));
        return new ResolvedTime(LOCAL_MINUTE.format(local), input.zoneId(), offset.getId(), start,
                inRange(start.plusSeconds(input.durationMinutes() * 60L)), input.durationMinutes());
    }

    private static void unchangedTime(EventRow row, TimeInput input) {
        required(input);
        duration(input.durationMinutes());
        // 已保存 Instant 是安排依据；普通编辑仅比较原输入，不用新版时区规则重新解析它。
        if (!local(input.localStart()).equals(row.localStart()) || !Objects.equals(input.zoneId(), row.zoneId())
                || input.durationMinutes() != row.durationMinutes()
                || (input.offset() != null && !offset(input.offset()).equals(ZoneOffset.of(row.offset())))) {
            throw new ApiException(409, "TIME_CHANGE_REQUIRES_RESCHEDULE", "改变面试时间须先查看改期预览，再提交改期原因。");
        }
    }

    private static ResolvedTime savedTime(EventRow row) {
        return new ResolvedTime(LOCAL_MINUTE.format(row.localStart()), row.zoneId(), row.offset(), row.startAt(), row.endAt(), row.durationMinutes());
    }

    private static Instant remindAt(ResolvedTime time, int lead) { return inRange(time.startAt().minusSeconds(lead * 60L)); }

    private EventRow requireEvent(String owner, String id) {
        EventRow row = mapper.event(owner, id);
        if (row == null) throw notFound();
        return row;
    }

    private EventRow changeable(String owner, String id, long expected) {
        EventRow row = requireEvent(owner, id);
        if (row.revision() != expected) throw new ApiException(409, "REVISION_CONFLICT", "面试安排已被其他操作修改，请保留输入后重新读取。", row.revision());
        if (row.revision() == MAX_REVISION) throw new ApiException(409, "REVISION_LIMIT", "面试安排修订号已达到上限。");
        if (!row.status().equals(Status.PLANNED.name())) throw new ApiException(409, "EVENT_CLOSED", "已完成或取消的面试安排仅可查看历史。");
        return row;
    }

    private <T> T read(JsonNode node, Class<T> type) {
        if (node == null || !node.isObject()) throw invalid("请求内容必须为对象。");
        try { return json.readValue(node.toString(), type); }
        catch (tools.jackson.core.JacksonException exception) { throw invalid("请求字段或类型不正确，请检查整数、必填项及未知字段。"); }
    }

    private static LocalDateTime local(String value) {
        try {
            if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}")) throw new DateTimeException("format");
            LocalDateTime parsed = LocalDateTime.parse(value, LOCAL_MINUTE);
            if (parsed.getYear() < 1000 || parsed.getYear() > 9999) throw new DateTimeException("range");
            return parsed;
        } catch (DateTimeException exception) { throw new ApiException(400, "INVALID_LOCAL_TIME", "本地时间须为有效的 yyyy-MM-dd'T'HH:mm，年份在 1000 至 9999 之间。"); }
    }

    private static ZoneId zone(String value) {
        if (value == null || (!value.equals("UTC") && !ZONES.contains(value))) {
            throw new ApiException(400, "INVALID_ZONE", "请选择有效的 IANA 时区或 UTC，不能用固定偏移替代时区。");
        }
        return ZoneId.of(value);
    }

    private static ZoneOffset offset(String value) {
        try {
            if (value == null || value.length() > 9) throw new DateTimeException("offset");
            return ZoneOffset.of(value);
        } catch (DateTimeException exception) { throw new ApiException(400, "INVALID_TIME_OFFSET", "时区偏移格式无效。"); }
    }

    private static Instant instant(String value) {
        try {
            if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,6})?Z")) throw new DateTimeException("instant");
            return inRange(OffsetDateTime.parse(value).toInstant());
        } catch (DateTimeException exception) { throw invalid("查询时刻须为 UTC ISO 时间，最多精确到微秒。"); }
    }

    private static Instant inRange(Instant value) {
        if (value.isBefore(MIN_TIME) || value.isAfter(MAX_TIME) || value.getNano() % 1000 != 0) {
            throw new ApiException(400, "INVALID_TIME_RANGE", "时间或提醒超出可保存范围。");
        }
        return value;
    }

    private static Cursor cursor(String value) {
        if (value == null) return new Cursor(null, null);
        String[] parts = value.split("~", -1);
        if (value.length() > 100 || parts.length != 2) throw invalid("安排游标无效。");
        Instant time = instant(parts[0]);
        if (!time.toString().equals(parts[0])) throw invalid("安排游标须使用服务器返回的原始格式。");
        return new Cursor(time, uuid(parts[1]));
    }

    private static String url(String value) {
        String checked = text(value, 2000, false, "会议链接").strip();
        if (checked.isEmpty()) return checked;
        try {
            URI uri = URI.create(checked);
            if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) throw invalid("会议链接只允许不含登录信息的 HTTP(S) 地址。");
            return checked;
        } catch (IllegalArgumentException exception) { throw invalid("会议链接格式不正确。"); }
    }

    private static String text(String value, int max, boolean required, String name) {
        if (value == null) {
            if (required) throw invalid(name + "不能为空。");
            return "";
        }
        if (value.length() > max || (required && value.isBlank())) throw invalid(name + "为空或超过长度上限。");
        return value;
    }

    private static void newEventText(EventInput value) {
        newText(value.title(), "标题");
        newText(value.round(), "面试轮次");
        newText(value.location(), "地点");
        newText(value.meetingUrl(), "会议链接");
        newText(value.notes(), "备注");
    }

    private static void newText(String value, String name) {
        // 新字符规则只在命令首次执行时检查；旧成功请求须先按原哈希恢复幂等回执。
        if (value.indexOf('\0') >= 0 || value.codePoints().anyMatch(point -> point >= 0xD800 && point <= 0xDFFF)) {
            throw invalid(name + "不能包含 NUL 或不完整的 Unicode 字符。");
        }
    }

    private static void rescheduleReason(String value) {
        if (value == null || value.codePointCount(0, value.length()) < 1 || value.codePointCount(0, value.length()) > 2000
                || value.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))) {
            throw invalid("改期原因须为 1 至 2000 个字符的非空文字。");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0) throw invalid("改期原因不能包含 NUL。");
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) throw invalid("改期原因包含不完整 Unicode。");
                i++;
            } else if (Character.isLowSurrogate(c)) throw invalid("改期原因包含不完整 Unicode。");
        }
    }

    private static void duration(Integer value) { if (value == null || value < 1 || value > 1440) throw invalid("持续时间须为 1 至 1440 的整数分钟。"); }
    private static void lead(Integer value) { if (value == null || value < 0 || value > 10080) throw invalid("提醒提前量须为 0 至 10080 的整数分钟。"); }
    private static void revision(Long value) { if (value == null || value < 0 || value > MAX_REVISION) throw invalid("预期修订号无效。"); }
    private static void limit(int value) { if (value < 1 || value > 50) throw invalid("每页数量须为 1 至 50。"); }
    private static <T> T required(T value) { if (value == null) throw invalid("请求内容或必填字段不能为空。"); return value; }
    private static String uuid(String value) {
        try { if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException exception) { throw invalid("对象标识须为标准小写 UUID。"); }
        return value;
    }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static ApiException invalid(String detail) { return new ApiException(400, "INVALID_REQUEST", detail); }
    private static ApiException notFound() { return new ApiException(404, "SCHEDULE_NOT_FOUND", "面试安排不存在或不可访问。"); }
    private static ApiException sourceUnavailable() { return new ApiException(404, "SOURCE_UNAVAILABLE", "引用来源不存在或不可访问。"); }
    private record Cursor(Instant time, String id) {}
}
