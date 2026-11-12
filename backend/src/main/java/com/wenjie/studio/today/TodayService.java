package com.wenjie.studio.today;

import com.wenjie.studio.knowledge.KnowledgeDtos;
import com.wenjie.studio.memory.MemoryAttemptDtos;
import com.wenjie.studio.memory.MemoryDtos;
import com.wenjie.studio.memory.MemoryReviewDtos;
import com.wenjie.studio.memory.MemoryReviewService;
import com.wenjie.studio.memory.MemoryTodayDtos;
import com.wenjie.studio.memory.MemoryTodayQuery;
import com.wenjie.studio.planning.PlanningDtos;
import com.wenjie.studio.review.RepracticeService;
import com.wenjie.studio.review.ReviewDtos;
import com.wenjie.studio.review.TodayActionProgress;
import com.wenjie.studio.schedule.ScheduleDtos;
import com.wenjie.studio.training.TrainingDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.wenjie.studio.today.TodayDtos.*;
import static com.wenjie.studio.today.TodayRows.*;
import static com.wenjie.studio.today.TodayRules.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class TodayService {
    private static final Logger LOG = LoggerFactory.getLogger(TodayService.class);
    private static final Capability READY = new Capability(Availability.READY, AvailabilityReason.NONE);
    private final TodayMapper mapper;
    private final TodayPlanMapper plans;
    private final MemoryTodayQuery memory;
    private final MemoryReviewService memoryReviews;
    private final RepracticeService review;
    private final JsonMapper json;

    public TodayService(TodayMapper mapper, TodayPlanMapper plans, MemoryTodayQuery memory,
                        MemoryReviewService memoryReviews, RepracticeService review, JsonMapper json) {
        this.mapper = mapper; this.plans = plans; this.memory = memory;
        this.memoryReviews = memoryReviews; this.review = review; this.json = json;
    }

    public TodayOverview overview(String owner, OverviewQuery request) {
        Instant now = now();
        Context context = request(owner, request.zoneId(), request.targetId(), Boolean.TRUE.equals(request.includeOverBudget()), now, now, null, true);
        Map<SourceKind, Map<Section, Slice>> sources = new EnumMap<>(SourceKind.class);
        List<SourceSectionCapability> capabilities = new ArrayList<>();
        MemoryQueueInfo queue = null;
        // 同来源四区段共享 100 个已展开候选；预览没有续页游标，来源页始终从首页重新读取。
        for (SourceKind kind : SourceKind.values()) {
            Map<Section, Slice> sections = new EnumMap<>(Section.class);
            int remaining = 100;
            for (Section section : Section.values()) {
                Slice value = read(context, kind, section, null, 5, remaining);
                remaining -= value.scanned(); sections.put(section, value);
                capabilities.add(new SourceSectionCapability(kind, section, value.capability(), value.count(), value.scanLimited()));
                if (value.memoryQueue() != null) queue = value.memoryQueue();
            }
            sources.put(kind, sections);
        }
        List<SectionPreview> previews = new ArrayList<>();
        for (Section section : Section.values()) {
            List<TodayItem> items = new ArrayList<>();
            Map<SourceKind, Integer> positions = new EnumMap<>(SourceKind.class);
            List<SourceKind> order = previewOrder(section);
            order.forEach(kind -> positions.put(kind, 0));
            while (items.size() < 5) {
                int smallest = Integer.MAX_VALUE;
                for (SourceKind kind : order) {
                    var candidates = sources.get(kind).get(section).items();
                    int position = positions.get(kind);
                    if (position < candidates.size()) smallest = Math.min(smallest, orderValue(candidates.get(position)));
                }
                if (smallest == Integer.MAX_VALUE) break;
                // 同顺序值按固定来源轮询；先耗尽更小值，再进入下一值，不把跨来源活动时间混成一条时间轴。
                for (SourceKind kind : order) {
                    var candidates = sources.get(kind).get(section).items();
                    int position = positions.get(kind);
                    if (items.size() < 5 && position < candidates.size() && orderValue(candidates.get(position)) == smallest) {
                        items.add(candidates.get(position)); positions.put(kind, position + 1);
                    }
                }
            }
            previews.add(new SectionPreview(section, List.copyOf(items)));
        }
        return bounded(json, new TodayOverview(context.day(), new Filter(context.target(), context.overBudget()),
                List.copyOf(capabilities), List.copyOf(previews), queue), PAGE_BYTES);
    }

    public TodayPage items(String owner, ItemsQuery request) {
        requirePage(request.kind(), request.section(), request.limit());
        boolean over = Boolean.TRUE.equals(request.includeOverBudget());
        if (over && (request.kind() != SourceKind.MEMORY_CARD || request.section() != Section.TODAY)) throw invalid("超建议范围仅用于回忆今日队列。");
        Instant serverTime = now(); uuid(owner);
        Cursor key = cursor(request.cursor(), owner, request.kind(), request.section(), request.targetId(), over,
                request.zoneId(), null, null, serverTime);
        Context context = request(owner, request.zoneId(), request.targetId(), over,
                key == null ? serverTime : key.asOf(), serverTime, null, request.kind() == SourceKind.MEMORY_CARD);
        checkPlacementCursor(context, key);
        checkMemoryCursor(context, key);
        Slice value = read(context, request.kind(), request.section(), key, request.limit(), SCAN_LIMIT);
        return bounded(json, new TodayPage(context.day(), new Filter(context.target(), context.overBudget()), request.kind(),
                request.section(), value.capability(), value.count(), value.scanLimited(), value.items(), value.nextCursor(), value.memoryQueue()), PAGE_BYTES);
    }

    public CalendarPage calendar(String owner, CalendarQuery request) {
        requirePage(request.kind(), Section.TODAY, request.limit());
        Instant serverTime = now(); uuid(owner);
        CalendarWindow window = window(request.from(), request.to(), request.zoneId());
        Cursor key = cursor(request.cursor(), owner, request.kind(), null, request.targetId(), false,
                request.zoneId(), request.from(), request.to(), serverTime);
        Context context = request(owner, request.zoneId(), request.targetId(), false,
                key == null ? serverTime : key.asOf(), serverTime, window, request.kind() == SourceKind.MEMORY_CARD);
        checkPlacementCursor(context, key);
        checkMemoryCursor(context, key);
        Slice value = read(context, request.kind(), null, key, request.limit(), SCAN_LIMIT);
        return bounded(json, new CalendarPage(context.day(), window, request.kind(), context.target(), value.capability(),
                value.count(), value.scanLimited(), value.items(), value.nextCursor()), PAGE_BYTES);
    }

    public SourceDetail source(String owner, SourceRef source, SourceQuery request) {
        if (source == null || source.kind() == null) throw invalid("来源类型必填。");
        uuid(owner); uuid(source.id());
        // 权限与存在性先查原身份；投影损坏不能把现有对象伪装成删除，更不能泄露其他 owner 的标题。
        if (!mapper.sourceExists(owner, source.kind().name(), source.id())) throw missing();
        Instant now = now();
        Context context = request(owner, request.zoneId(), null, false, now, now, null, source.kind() == SourceKind.MEMORY_CARD);
        try {
            TodayItem item = source(context, source);
            if (source.kind() == SourceKind.MEMORY_CARD && item.section() == null && !context.settings().configured()
                    && dueAndUsable(item, now)) {
                return bounded(json, new SourceDetail(context.day(), source,
                        new Capability(Availability.NOT_CONFIGURED, AvailabilityReason.MEMORY_SETTINGS_REQUIRED), null, null), PAGE_BYTES);
            }
            return bounded(json, new SourceDetail(context.day(), source, READY, item,
                    item.section() == null ? exclusion(item, context) : null), PAGE_BYTES);
        } catch (ProjectionFailure failure) {
            LOG.warn("今日来源摘要一致性检查失败，kind={}，id={}", source.kind(), source.id());
            return new SourceDetail(context.day(), source, failed().capability(), null, null);
        }
    }

    /** 计划命令已持有 owner 事务；复用同一个调用时刻与真实摘要，不把未配置回忆包装成 HTTP 的空 item。 */
    public TodayItem sourceForPlan(String owner, SourceRef source, String zoneId, Instant now) {
        if (source == null) throw invalid("来源类型必填。");
        return sourcesForPlan(owner, List.of(source), zoneId, now).getFirst();
    }

    /** 仅展开请求中的候选；同批回忆共用一次原队列排名，训练与动作也共用各自的窄摘要读取。 */
    public List<TodayItem> sourcesForPlan(String owner, List<SourceRef> sources, String zoneId, Instant now) {
        uuid(owner); time(now);
        if (sources == null || sources.isEmpty() || sources.size() > 100) throw invalid("安排候选须为 1 至 100 个不同来源。");
        HashSet<SourceRef> seen = new HashSet<>();
        for (SourceRef source : sources) {
            if (source == null || source.kind() == null || !seen.add(source)) throw invalid("安排候选来源无效或重复。");
            uuid(source.id());
        }
        Context context = request(owner, zoneId, null, false, now, now, null,
                sources.stream().anyMatch(source -> source.kind() == SourceKind.MEMORY_CARD));
        Map<SourceRef, TodayItem> result = new HashMap<>();
        for (int offset = 0; offset < sources.size(); offset += 50) {
            List<SourceRef> batch = sources.subList(offset, Math.min(offset + 50, sources.size()));
            loadPlacements(context, batch);
            for (SourceKind kind : SourceKind.values()) {
                List<String> ids = batch.stream().filter(source -> source.kind() == kind).map(SourceRef::id).toList();
                if (ids.isEmpty()) continue;
                List<TodayItem> items = switch (kind) {
                    case KNOWLEDGE_DRAFT -> ids.stream().map(id -> local(() -> knowledge(existing(mapper.knowledge(owner, id)), context))).toList();
                    case TRAINING_SESSION -> training(ids.stream().map(id -> existing(mapper.training(owner, id))).toList(), context);
                    case PREPARATION_TASK -> ids.stream().map(id -> local(() -> task(existing(mapper.task(owner, id)), context))).toList();
                    case REVIEW_ACTION -> actions(ids.stream().map(id -> existing(mapper.action(owner, id))).toList(), context);
                    case INTERVIEW_EVENT -> ids.stream().map(id -> local(() -> interview(existing(mapper.interview(owner, id)), context))).toList();
                    case MEMORY_CARD -> {
                        for (String id : ids) if (!mapper.sourceExists(owner, kind.name(), id)) throw missing();
                        var projected = memory.sources(owner, ids, now, now);
                        checkProjectionIds(ids, projected.items());
                        yield projected.items().stream().map(value -> local(() -> memoryItem(value, context))).toList();
                    }
                };
                if (items.size() != ids.size()) throw integrity();
                for (int index = 0; index < items.size(); index++) {
                    TodayItem item = items.get(index);
                    if (!item.source().equals(new SourceRef(kind, ids.get(index))) || result.put(item.source(), item) != null) throw integrity();
                }
            }
        }
        return sources.stream().map(result::get).toList();
    }

    private TodayItem source(Context context, SourceRef source) {
        loadPlacements(context, List.of(source));
        String owner = context.owner();
        return switch (source.kind()) {
            case KNOWLEDGE_DRAFT -> local(() -> knowledge(mapper.knowledge(owner, source.id()), context));
            case TRAINING_SESSION -> training(List.of(require(mapper.training(owner, source.id()))), context).getFirst();
            case PREPARATION_TASK -> local(() -> task(mapper.task(owner, source.id()), context));
            case REVIEW_ACTION -> actions(List.of(require(mapper.action(owner, source.id()))), context).getFirst();
            case INTERVIEW_EVENT -> local(() -> interview(mapper.interview(owner, source.id()), context));
            case MEMORY_CARD -> {
                var projection = memory.sources(owner, List.of(source.id()), context.day().asOf(), context.day().serverTime());
                checkProjectionIds(List.of(source.id()), projection.items());
                yield local(() -> memoryItem(projection.items().getFirst(), context));
            }
        };
    }

    private Context request(String owner, String zone, String target, boolean over, Instant asOf, Instant serverTime,
                            CalendarWindow window, boolean withMemory) {
        uuid(owner);
        if (target != null && !mapper.targetExists(owner, uuid(target))) throw missing();
        DayContext day = context(zone, asOf, serverTime);
        MemoryReviewDtos.Settings settings = withMemory && target == null ? memoryReviews.settings(owner) : null;
        long generation = plans.placementGeneration(owner); storedRevision(generation);
        return new Context(owner, day, target, over, window, settings, generation, new HashMap<>());
    }

    private Slice read(Context context, SourceKind kind, Section section, Cursor cursor, int limit, int scanBudget) {
        if (!applicable(kind, section, context.calendar()) || context.target() != null
                && (kind == SourceKind.KNOWLEDGE_DRAFT || kind == SourceKind.MEMORY_CARD)) return empty();
        if (scanBudget == 0) return new Slice(READY, lower(0), true, List.of(), null, null, 0);
        try {
            if (kind == SourceKind.MEMORY_CARD) return memory(context, section, cursor, limit, scanBudget);
            Query query = query(context, section);
            return switch (kind) {
                case KNOWLEDGE_DRAFT -> scan(context, kind, section, cursor, limit, scanBudget, query, mapper::knowledgePage,
                        rows -> rows.stream().map(row -> local(() -> knowledge(row, context))).toList(),
                        row -> placedKey(context, section, row.orderValue(), row.placementDate(), row.draftSavedAt(), row.id()));
                case TRAINING_SESSION -> scan(context, kind, section, cursor, limit, scanBudget, query, mapper::trainingPage,
                        rows -> training(rows, context), row -> placedKey(context, section, row.orderValue(), row.placementDate(), row.lastActivityAt(), row.id()));
                case PREPARATION_TASK -> scan(context, kind, section, cursor, limit, scanBudget, query, mapper::taskPage,
                        rows -> rows.stream().map(row -> local(() -> task(row, context))).toList(), row ->
                                context.calendar() || section == Section.ATTENTION || section == Section.TODAY
                                        ? new Key(row.orderValue(), null, null, row.plannedDate(), row.id()) : new Key(row.orderValue(), null, row.updatedAt(), null, row.id()));
                case REVIEW_ACTION -> scan(context, kind, section, cursor, limit, scanBudget, query, mapper::actionPage,
                        rows -> actions(rows, context), row -> placedKey(context, section, row.orderValue(), row.placementDate(), row.updatedAt(), row.id()));
                case INTERVIEW_EVENT -> scan(context, kind, section, cursor, limit, scanBudget, query, mapper::interviewPage,
                        rows -> rows.stream().map(row -> local(() -> interview(row, context))).toList(), row ->
                                new Key(row.orderValue(), null, !context.calendar() && section == Section.ATTENTION ? row.remindAt() : row.startAt(), null, row.id()));
                case MEMORY_CARD -> throw new IllegalStateException("回忆须使用原领域窄查询");
            };
        } catch (ProjectionFailure failure) {
            LOG.warn("今日来源摘要一致性检查失败，kind={}，section={}", kind, section);
            Slice failed = failed();
            // 失败前已消费的摘要不能为同一 overview 重新获得扫描额度；保守占用本次分配的额度。
            return new Slice(failed.capability(), failed.count(), false, List.of(), null, null, scanBudget);
        }
    }

    private <R> Slice scan(Context context, SourceKind kind, Section section, Cursor cursor, int limit, int budget,
                           Query query, Function<Query, List<R>> fetch, Function<List<R>, List<TodayItem>> project,
                           Function<R, Key> keyOf) {
        Key position = cursor == null ? null : new Key(cursor.afterOrderValue(), cursor.afterRank(), cursor.afterInstant(), cursor.afterDate(), cursor.afterId());
        List<TodayItem> items = new ArrayList<>();
        int scanned = 0; boolean more = false;
        while (scanned < budget && items.size() < limit) {
            int size = Math.min(Math.min(50, budget - scanned), limit - items.size());
            List<R> rows = fetch.apply(query.after(position, size + 1));
            List<R> selected = rows.stream().limit(size).toList();
            if (selected.isEmpty()) { more = false; break; }
            loadPlacements(context, selected.stream().map(row -> new SourceRef(kind, keyOf.apply(row).id())).toList());
            List<TodayItem> projected = project.apply(selected);
            if (projected.size() != selected.size()) throw integrity();
            for (int index = 0; index < selected.size(); index++) {
                TodayItem item = projected.get(index); scanned++; position = keyOf.apply(selected.get(index));
                if (orderValue(item) != position.orderValue()) throw integrity();
                if ((context.calendar() || item.section() == section) && (context.target() == null
                        || item.targetRefs().stream().anyMatch(ref -> ref.itemId().equals(context.target())))) items.add(item);
            }
            more = rows.size() > size;
            if (!more) break;
        }
        // 完整首页才能证明全量；后续末页只给本次核实的下界，不能把零行当作全库为空。
        Count count = cursor == null && !more ? exact(items.size()) : lower(items.size());
        return new Slice(READY, count, more && scanned == budget, List.copyOf(items),
                more ? next(context, kind, section, position) : null, null, scanned);
    }

    private Slice memory(Context context, Section section, Cursor cursor, int limit, int budget) {
        if (!context.calendar() && section == Section.TODAY && !context.settings().configured()) {
            return unavailable(Availability.NOT_CONFIGURED, AvailabilityReason.MEMORY_SETTINGS_REQUIRED);
        }
        MemoryTodayDtos.OrderKey position = cursor == null ? null
                : new MemoryTodayDtos.OrderKey(cursor.afterOrderValue(), cursor.afterRank(), cursor.afterInstant(), cursor.afterId());
        List<TodayItem> items = new ArrayList<>();
        int scanned = 0; boolean more = false; Long exactTotal = null; MemoryQueueInfo info = null;
        while (scanned < budget && items.size() < limit) {
            int size = Math.min(Math.min(50, budget - scanned), limit - items.size());
            MemoryTodayDtos.OrderedPage page;
            if (context.calendar()) page = memory.orderedCalendar(context.owner(), context.window().startAt(), context.window().endAt(),
                    context.day().asOf(), position, size);
            else if (section == Section.CONTINUE) page = memory.orderedOpen(context.owner(), context.day().asOf(), position, size);
            else if (section == Section.ATTENTION) page = memory.orderedAttention(context.owner(), context.day().asOf(), position, size);
            else page = memory.orderedQueue(context.owner(), context.day().asOf(), context.day().serverTime(), position, size, context.overBudget());
            if (page.items().size() > size || page.items().isEmpty() && page.hasMore()) throw integrity();
            more = page.hasMore();
            if (context.calendar()) exactTotal = page.exactTotal();
            if (page.queueInfo() != null) {
                var queue = page.queueInfo();
                info = new MemoryQueueInfo(queue.budget(), exact(queue.eligibleDueCards()), exact(queue.recommendedCards()), queue.nextDueAt(), queue.includeOverBudget());
            }
            List<String> ids = page.items().stream().map(value -> value.projection().card().id()).toList();
            List<MemoryTodayDtos.CardProjection> projected = page.items().stream().map(MemoryTodayDtos.OrderedCard::projection).toList();
            if (context.calendar() && !ids.isEmpty()) {
                // 排程决定日历范围；摘要再读取原 B 推荐，不能把日历中的每张 due 卡伪装成可开始队列项。
                projected = memory.sources(context.owner(), ids, context.day().asOf(), context.day().serverTime()).items();
                checkProjectionIds(ids, projected);
            }
            loadPlacements(context, ids.stream().map(id -> new SourceRef(SourceKind.MEMORY_CARD, id)).toList());
            for (int index = 0; index < page.items().size(); index++) {
                var projection = projected.get(index);
                TodayItem item = local(() -> memoryItem(projection, context));
                position = page.items().get(index).key(); scanned++;
                if (orderValue(item) != position.orderValue()) throw integrity();
                if (context.calendar() || item.section() == section) items.add(item);
            }
            if (!more) break;
        }
        // 原 B 总数不含今日抑制；只有日历独立 COUNT 或完整消费的首页能证明本请求的展示总数。
        Count count = exactTotal != null ? exact(exactTotal) : cursor == null && !more ? exact(items.size()) : lower(items.size());
        Key key = position == null ? null : new Key(position.orderValue(), position.phaseRank(), position.time(), null, position.id());
        return new Slice(READY, count, more && scanned == budget, List.copyOf(items),
                more ? next(context, SourceKind.MEMORY_CARD, section, key) : null, info, scanned);
    }

    private Query query(Context context, Section section) {
        DayContext day = context.day();
        Instant upcomingEnd;
        try { upcomingEnd = day.localDate().plusDays(8).atStartOfDay(ZoneId.of(day.zoneId())).toInstant(); time(upcomingEnd); }
        catch (DateTimeException exception) { throw invalid("未来七日超出可保存范围。"); }
        return new Query(context.owner(), context.target(), section == null ? null : section.name(), day.asOf(), day.startAt(), day.endAt(), upcomingEnd,
                context.calendar() ? context.window().from() : null, context.calendar() ? context.window().to() : null,
                context.calendar() ? context.window().startAt() : null, context.calendar() ? context.window().endAt() : null,
                day.asOf().atOffset(ZoneOffset.MIN).toLocalDate(), day.asOf().atOffset(ZoneOffset.MAX).toLocalDate(), null, null, null, null, 0);
    }

    private static Key placedKey(Context context, Section section, int order, LocalDate date, Instant activity, String id) {
        return new Key(order, null, context.calendar() ? null : activity,
                context.calendar() || section == Section.ATTENTION || section == Section.TODAY ? date : null, id);
    }

    private String next(Context context, SourceKind kind, Section section, Key key) {
        var settings = context.settings();
        boolean configured = kind == SourceKind.MEMORY_CARD && settings != null && settings.configured();
        return encode(new Cursor(2, context.calendar() ? "today-calendar" : "today-items", context.owner(), kind, section,
                context.target(), context.overBudget(), context.day().zoneId(), context.day().localDate(), context.day().asOf(),
                context.calendar() ? context.window().from() : null, context.calendar() ? context.window().to() : null,
                sort(kind, section, context.calendar()), key.instant(), key.date(), key.rank(), key.id(), null,
                configured ? settings.revision() : null, configured ? settings.zoneId() : null,
                configured ? context.day().serverTime().atZone(ZoneId.of(settings.zoneId())).toLocalDate() : null,
                context.placementGeneration(), key.orderValue()));
    }

    private static void checkPlacementCursor(Context context, Cursor cursor) {
        if (cursor != null && cursor.placementGeneration() != context.placementGeneration()) throw changed();
    }

    private void checkMemoryCursor(Context context, Cursor cursor) {
        if (cursor == null || cursor.kind() != SourceKind.MEMORY_CARD) return;
        var settings = context.settings();
        boolean configured = settings != null && settings.configured();
        if (!Objects.equals(cursor.memorySettingsRevision(), configured ? settings.revision() : null)
                || !Objects.equals(cursor.memoryZoneId(), configured ? settings.zoneId() : null)
                || !Objects.equals(cursor.memoryLocalDate(), configured ? context.day().serverTime().atZone(ZoneId.of(settings.zoneId())).toLocalDate() : null)) throw changed();
        if (configured && !cursor.memoryLocalDate().equals(context.day().asOf().atZone(ZoneId.of(settings.zoneId())).toLocalDate())) throw changed();
    }

    private static List<SourceKind> previewOrder(Section section) {
        return switch (section) {
            case CONTINUE -> List.of(SourceKind.TRAINING_SESSION, SourceKind.MEMORY_CARD, SourceKind.PREPARATION_TASK, SourceKind.KNOWLEDGE_DRAFT);
            case ATTENTION -> List.of(SourceKind.INTERVIEW_EVENT, SourceKind.MEMORY_CARD, SourceKind.PREPARATION_TASK,
                    SourceKind.REVIEW_ACTION, SourceKind.TRAINING_SESSION, SourceKind.KNOWLEDGE_DRAFT);
            case TODAY -> List.of(SourceKind.INTERVIEW_EVENT, SourceKind.PREPARATION_TASK, SourceKind.MEMORY_CARD,
                    SourceKind.TRAINING_SESSION, SourceKind.REVIEW_ACTION, SourceKind.KNOWLEDGE_DRAFT);
            case BACKLOG -> List.of(SourceKind.INTERVIEW_EVENT, SourceKind.PREPARATION_TASK);
        };
    }

    private static boolean applicable(SourceKind kind, Section section, boolean calendar) {
        if (calendar) return true;
        return switch (kind) {
            case KNOWLEDGE_DRAFT, TRAINING_SESSION -> section != Section.BACKLOG;
            case PREPARATION_TASK -> true;
            case MEMORY_CARD -> section != Section.BACKLOG;
            case REVIEW_ACTION -> section == Section.ATTENTION || section == Section.TODAY;
            case INTERVIEW_EVENT -> section != Section.CONTINUE;
        };
    }

    private static void requirePage(SourceKind kind, Section section, Integer limit) {
        if (kind == null || section == null || limit == null || limit < 1 || limit > 50) throw invalid("来源、区段和 1 至 50 的分页数量必填。");
    }

    private static Slice empty() { return new Slice(READY, exact(0), false, List.of(), null, null, 0); }
    private static Slice failed() { return unavailable(Availability.ERROR, AvailabilityReason.SOURCE_INTEGRITY_FAILED); }
    private static Slice unavailable(Availability state, AvailabilityReason reason) {
        return new Slice(new Capability(state, reason), new Count(null, CountMode.UNAVAILABLE), false, List.of(), null, null, 0);
    }

    private TodayItem knowledge(KnowledgeRow row, Context context) {
        require(row);
        if (!row.complete() || row.draftRevision() == null || row.currentVersionId() == null || row.draftSavedAt() == null) throw integrity();
        storedId(row.currentVersionId()); storedRevision(row.itemRevision()); storedRevision(row.draftRevision()); storedTime(row.draftSavedAt());
        var status = KnowledgeDtos.Status.valueOf(row.status());
        Section section = status == KnowledgeDtos.Status.ACTIVE && row.unpublishedDraft() ? Section.CONTINUE : null;
        var stamp = new SourceStamp(SourceKind.KNOWLEDGE_DRAFT,
                new KnowledgeStamp(row.itemRevision(), row.draftRevision(), row.currentVersionId()), null, null, null, null, null);
        var state = new SourceStatus(SourceKind.KNOWLEDGE_DRAFT,
                new KnowledgeState(KnowledgeDtos.Kind.valueOf(row.kind()), status, row.unpublishedDraft(), row.draftSavedAt()), null, null, null, null, null);
        Continuation continuation = itemRoute(Route.KNOWLEDGE, row.id());
        return item(context, SourceKind.KNOWLEDGE_DRAFT, row.id(), row.title(), section, stamp, state,
                row.unpublishedDraft() ? List.of(Reason.UNPUBLISHED_DRAFT) : List.of(), List.of(), noneTime(), null,
                unknownEstimate(), continuation, null, List.of(new AvailableAction(ActionKind.OPEN_SOURCE, continuation)), List.of());
    }

    private List<TodayItem> training(List<TrainingRow> rows, Context context) {
        List<String> ids = rows.stream().map(TrainingRow::id).toList();
        var questions = mapper.trainingQuestions(context.owner(), ids).stream().collect(Collectors.groupingBy(QuestionRow::sessionId));
        var targets = mapper.trainingTargets(context.owner(), ids).stream().collect(Collectors.groupingBy(TargetRow::sourceId));
        if (questions.keySet().stream().anyMatch(id -> !ids.contains(id)) || targets.keySet().stream().anyMatch(id -> !ids.contains(id))) throw integrity();
        return rows.stream().map(row -> local(() -> training(row, questions.getOrDefault(row.id(), List.of()), targets.getOrDefault(row.id(), List.of()), context))).toList();
    }

    private TodayItem training(TrainingRow row, List<QuestionRow> questions, List<TargetRow> targets, Context context) {
        if (row.questionCount() < 1 || row.questionCount() > 60 || questions.size() != row.questionCount()) throw integrity();
        storedRevision(row.revision()); storedRevision(row.generation()); storedTime(row.updatedAt()); storedTime(row.lastActivityAt());
        if (row.repracticeId() != null) storedId(row.repracticeId());
        int drafts = 0; Instant lastDraft = null;
        List<Object> fingerprint = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (QuestionRow question : questions.stream().sorted(Comparator.comparingInt(QuestionRow::positionIndex).thenComparing(QuestionRow::id)).toList()) {
            if (!seen.add(question.id()) || question.positionIndex() < 0 || question.positionIndex() >= 60
                    || question.answerCount() < 0 || question.answerCount() > 100
                    || (question.latestAnswerId() == null) != (question.answerCount() == 0)
                    || question.hasDraft() != (question.draftSavedAt() != null)) throw integrity();
            storedId(question.id()); storedRevision(question.revision()); TrainingDtos.QuestionStatus.valueOf(question.status());
            if (question.latestAnswerId() != null) storedId(question.latestAnswerId());
            if (question.hasDraft()) {
                drafts++; storedTime(question.draftSavedAt());
                if (lastDraft == null || lastDraft.isBefore(question.draftSavedAt())) lastDraft = question.draftSavedAt();
            }
            fingerprint.add(Arrays.asList(question.id(), question.revision(), question.status(), question.hasDraft(),
                    question.draftSavedAt() == null ? null : MICRO_TIME.format(question.draftSavedAt()), question.latestAnswerId(), question.answerCount(), question.positionIndex()));
        }
        Instant activity = lastDraft != null && row.updatedAt().isBefore(lastDraft) ? lastDraft : row.updatedAt();
        if (drafts != row.savedDraftCount() || !Objects.equals(lastDraft, row.lastDraftSavedAt()) || !activity.equals(row.lastActivityAt())) throw integrity();
        var status = TrainingDtos.Status.valueOf(row.status());
        Section section = status == TrainingDtos.Status.ENDED ? null : Section.CONTINUE;
        List<Reason> reasons = new ArrayList<>();
        switch (status) {
            case DRAFT -> reasons.add(Reason.TRAINING_DRAFT);
            case RUNNING -> reasons.add(Reason.TRAINING_RUNNING);
            case PAUSED -> reasons.add(Reason.TRAINING_PAUSED);
            case ENDED -> { }
        }
        if (drafts > 0) reasons.add(Reason.SAVED_ANSWER_DRAFT);
        var stamp = new SourceStamp(SourceKind.TRAINING_SESSION, null, new TrainingStamp(row.revision(), row.generation(), hash(fingerprint)), null, null, null, null);
        var state = new SourceStatus(SourceKind.TRAINING_SESSION, null,
                new TrainingState(TrainingDtos.Mode.valueOf(row.mode()), status, drafts, lastDraft, activity, row.repracticeId()), null, null, null, null);
        Continuation continuation = itemRoute(Route.TRAINING, row.id());
        return item(context, SourceKind.TRAINING_SESSION, row.id(), row.title(), section, stamp, state, reasons,
                targets.stream().map(value -> new TargetRef(value.itemId(), value.versionId())).toList(), noneTime(), null,
                unknownEstimate(), continuation, null,
                List.of(new AvailableAction(section == null ? ActionKind.OPEN_SOURCE : ActionKind.CONTINUE_TRAINING, continuation)), List.of());
    }

    private TodayItem task(TaskRow row, Context context) {
        require(row); storedRevision(row.revision()); storedTime(row.updatedAt()); storedZone(row.zoneId());
        if (row.minutes() < 1 || row.minutes() > 1440) throw integrity();
        if (row.plannedDate() != null && (row.plannedDate().getYear() < 1000 || row.plannedDate().getYear() > 9999)) throw integrity();
        var status = PlanningDtos.Status.valueOf(row.status());
        if ((status == PlanningDtos.Status.DONE) != (row.completedAt() != null)
                || status == PlanningDtos.Status.IN_PROGRESS && row.startedAt() == null) throw integrity();
        if (row.startedAt() != null) storedTime(row.startedAt());
        if (row.completedAt() != null) storedTime(row.completedAt());
        Section section = null; List<Reason> reasons = new ArrayList<>();
        if (status == PlanningDtos.Status.IN_PROGRESS) { section = Section.CONTINUE; reasons.add(Reason.TASK_IN_PROGRESS); }
        if (status == PlanningDtos.Status.TODO) {
            if (row.plannedDate() == null) { section = Section.BACKLOG; reasons.add(Reason.TASK_UNSCHEDULED); }
            else {
                LocalDate current = context.day().asOf().atZone(ZoneId.of(row.zoneId())).toLocalDate();
                if (row.plannedDate().isBefore(current)) { section = Section.ATTENTION; reasons.add(Reason.TASK_OVERDUE); }
                else if (row.plannedDate().equals(current)) { section = Section.TODAY; reasons.add(Reason.TASK_TODAY); }
            }
        }
        var stamp = new SourceStamp(SourceKind.PREPARATION_TASK, null, null, new TaskStamp(row.revision()), null, null, null);
        var state = new SourceStatus(SourceKind.PREPARATION_TASK, null, null, new TaskState(status, row.startedAt(), row.completedAt()), null, null, null);
        SourceTime time = row.plannedDate() == null ? noneTime() : new SourceTime(TimeKind.LOCAL_DATE, null, row.plannedDate(), row.zoneId(), ZoneBasis.SOURCE);
        Continuation continuation = itemRoute(Route.PREPARATION_TASKS, row.id());
        return item(context, SourceKind.PREPARATION_TASK, row.id(), row.title(), section, stamp, state, reasons,
                targets(row.targetItemId(), row.targetVersionId()), time, null, new Estimate(row.minutes(), EstimateBasis.SOURCE),
                continuation, null, List.of(new AvailableAction(ActionKind.OPEN_SOURCE, continuation)), List.of());
    }

    private List<TodayItem> actions(List<ActionRow> rows, Context context) {
        List<String> ids = rows.stream().map(ActionRow::id).toList();
        var projected = review.todayProgress(context.owner(), ids);
        if (projected.size() != ids.size()) throw integrity();
        Map<String, TodayActionProgress> progress = new java.util.HashMap<>();
        for (var value : projected) if (!ids.contains(value.actionId()) || progress.put(value.actionId(), value) != null) throw integrity();
        return rows.stream().map(row -> local(() -> action(row, require(progress.get(row.id())), context))).toList();
    }

    private TodayItem action(ActionRow row, TodayActionProgress progress, Context context) {
        storedRevision(row.revision()); storedTime(row.updatedAt());
        if (row.revision() != progress.revision() || !row.status().equals(progress.status().name())
                || !Objects.equals(row.mergedInto(), progress.mergedInto()) || !row.updatedAt().equals(progress.updatedAt())
                || progress.sourceCount() < 0 || progress.sourceCount() > 20 || progress.trackingCount() < 0
                || progress.trackingCount() > progress.sourceCount() || progress.pendingCount() < 0
                || progress.pendingCount() > progress.trackingCount() || progress.progressHash() == null
                || !progress.progressHash().matches("[0-9a-f]{64}")) throw integrity();
        if (row.mergedInto() != null) storedId(row.mergedInto());
        Section section = progress.status() == ReviewDtos.ActionStatus.OPEN && progress.pendingCount() > 0 ? Section.ATTENTION : null;
        var stamp = new SourceStamp(SourceKind.REVIEW_ACTION, null, null, null, null, new ActionStamp(row.revision(), progress.progressHash()), null);
        var state = new SourceStatus(SourceKind.REVIEW_ACTION, null, null, null, null,
                new ActionState(progress.status(), progress.mergedInto(), progress.sourceCount(), progress.trackingCount()), null);
        Continuation continuation = new Continuation(Route.REVIEW, null, new ActionQuery(row.id()), null);
        return item(context, SourceKind.REVIEW_ACTION, row.id(), row.title(), section, stamp, state,
                section == null ? List.of() : List.of(Reason.ACTION_PENDING),
                progress.targetRefs().stream().map(value -> new TargetRef(value.itemId(), value.versionId())).toList(),
                noneTime(), null, unknownEstimate(), continuation, progress.pendingCount(),
                List.of(new AvailableAction(ActionKind.OPEN_REVIEW_ACTION, continuation)), List.of());
    }

    private TodayItem interview(InterviewRow row, Context context) {
        require(row); storedRevision(row.revision()); storedTime(row.startAt()); storedTime(row.endAt()); storedTime(row.remindAt()); storedZone(row.zoneId());
        if (!row.startAt().isBefore(row.endAt()) || row.remindAt().isAfter(row.startAt())) throw integrity();
        var status = ScheduleDtos.Status.valueOf(row.status());
        Instant asOf = context.day().asOf();
        ScheduleDtos.Timing timing = status != ScheduleDtos.Status.PLANNED ? ScheduleDtos.Timing.CLOSED
                : !asOf.isBefore(row.endAt()) ? ScheduleDtos.Timing.PAST : !asOf.isBefore(row.startAt()) ? ScheduleDtos.Timing.IN_PROGRESS
                : asOf.isBefore(row.remindAt()) ? ScheduleDtos.Timing.UPCOMING : ScheduleDtos.Timing.DUE;
        boolean reminder = status == ScheduleDtos.Status.PLANNED && row.reminderAcknowledgedAt() == null && !row.remindAt().isAfter(asOf);
        Section section = null; List<Reason> reasons = new ArrayList<>();
        if (status == ScheduleDtos.Status.PLANNED) {
            if (reminder) reasons.add(Reason.INTERVIEW_REMINDER);
            if (timing == ScheduleDtos.Timing.PAST) reasons.add(Reason.INTERVIEW_TIME_PASSED);
            if (timing == ScheduleDtos.Timing.IN_PROGRESS) reasons.add(Reason.INTERVIEW_IN_PROGRESS);
            if (reminder || timing == ScheduleDtos.Timing.PAST) section = Section.ATTENTION;
            if (row.startAt().isBefore(context.day().endAt()) && row.endAt().isAfter(context.day().startAt())) {
                reasons.add(Reason.INTERVIEW_TODAY); if (section == null) section = Section.TODAY;
            }
            Instant end = context.day().localDate().plusDays(8).atStartOfDay(ZoneId.of(context.day().zoneId())).toInstant();
            if (!row.startAt().isBefore(context.day().endAt()) && row.startAt().isBefore(end)) {
                reasons.add(Reason.INTERVIEW_UPCOMING); if (section == null) section = Section.BACKLOG;
            }
        }
        var stamp = new SourceStamp(SourceKind.INTERVIEW_EVENT, null, null, null, null, null, new InterviewStamp(row.revision()));
        var state = new SourceStatus(SourceKind.INTERVIEW_EVENT, null, null, null, null, null, new InterviewState(status, timing, reminder));
        Continuation continuation = itemRoute(Route.SCHEDULE, row.id());
        return item(context, SourceKind.INTERVIEW_EVENT, row.id(), row.title(), section, stamp, state, reasons,
                targets(row.targetId(), row.targetVersionId()), new SourceTime(TimeKind.INSTANT, row.startAt(), null, row.zoneId(), ZoneBasis.SOURCE),
                new EventWindow(row.startAt(), row.endAt(), row.zoneId(), row.remindAt()), unknownEstimate(), continuation, null,
                List.of(new AvailableAction(ActionKind.OPEN_INTERVIEW, continuation)), List.of());
    }

    private TodayItem memoryItem(MemoryTodayDtos.CardProjection value, Context context) {
        require(value); var card = require(value.card()); var gate = value.gate(); var schedule = value.schedule();
        var eligibility = require(value.eligibility()); var open = value.openAttempt(); var recommendation = value.queueItem();
        if (card.revision() < 0 || card.revision() > 1000 || eligibility.blockers() == null || eligibility.warnings() == null
                || eligibility.basisReady() != eligibility.blockers().isEmpty()) throw integrity();
        storedId(card.versionId());
        if (gate != null) { if (!card.id().equals(gate.cardId())) throw integrity(); storedRevision(gate.revision()); }
        if (schedule != null) {
            if (!card.id().equals(schedule.cardId()) || schedule.state() == null
                    || (schedule.status() == MemoryReviewDtos.ScheduleStatus.ACTIVE) != (schedule.pauseReason() == null)) throw integrity();
            storedRevision(schedule.revision()); storedTime(schedule.effectiveDueAt());
        }
        if (open != null) {
            if (!card.id().equals(open.cardId()) || !card.versionId().equals(open.cardVersionId()) || card.status() != MemoryDtos.Status.ACTIVE
                    || open.state() != MemoryAttemptDtos.State.THINKING && open.state() != MemoryAttemptDtos.State.REVEALED) throw integrity();
            storedId(open.id()); storedRevision(open.revision()); storedTime(open.startedAt()); storedTime(open.updatedAt());
        }
        if (recommendation != null && (gate == null || schedule == null || open != null || card.status() != MemoryDtos.Status.ACTIVE
                || schedule.status() != MemoryReviewDtos.ScheduleStatus.ACTIVE || schedule.effectiveDueAt().isAfter(context.day().asOf())
                || !card.id().equals(recommendation.cardId())
                || !card.versionId().equals(recommendation.cardVersionId()) || card.revision() != recommendation.cardRevision()
                || gate.revision() != recommendation.gateRevision() || schedule.revision() != recommendation.scheduleRevision()
                || !schedule.effectiveDueAt().equals(recommendation.effectiveDueAt()) || !eligibility.basisReady())) throw integrity();
        Section section = null; List<Reason> reasons = new ArrayList<>(); List<Warning> warnings = new ArrayList<>();
        if (open != null) { section = Section.CONTINUE; reasons.add(Reason.MEMORY_OPEN_ATTEMPT); }
        else if (card.status() == MemoryDtos.Status.ACTIVE && !eligibility.blockers().isEmpty()) {
            section = Section.ATTENTION; reasons.add(Reason.MEMORY_SOURCE_REVIEW);
        } else if (recommendation != null) {
            reasons.add(Reason.MEMORY_DUE);
            if (recommendation.recommended() || context.overBudget()) section = Section.TODAY;
            else reasons.add(Reason.MEMORY_NOT_RECOMMENDED);
            if (recommendation.requiresBudgetConfirmation()) reasons.add(Reason.MEMORY_BUDGET_CONFIRMATION);
            warnings.add(Warning.MEMORY_QUEUE_RECHECK_REQUIRED);
        }
        if (eligibility.warnings().contains(MemoryReviewDtos.Warning.ARCHIVED_CONCEPT_WITH_OTHER_SOURCE)) warnings.add(Warning.ARCHIVED_CONCEPT_WITH_OTHER_SOURCE);
        var settings = context.settings();
        var stamp = new SourceStamp(SourceKind.MEMORY_CARD, null, null, null,
                new MemoryStamp(card.revision(), card.versionId(), gate == null ? null : gate.revision(), schedule == null ? null : schedule.revision(),
                        open == null ? null : open.id(), open == null ? null : open.revision(), settings != null && settings.configured() ? settings.revision() : null), null, null);
        var state = new SourceStatus(SourceKind.MEMORY_CARD, null, null, null,
                new MemoryState(card.status(), schedule == null ? null : schedule.status(), schedule == null ? null : schedule.pauseReason(),
                        schedule == null ? null : schedule.state().phase(), eligibility,
                        open == null ? null : new OpenMemoryAttempt(open.id(), open.revision(), open.mode(), open.countRegular(), open.state(), open.startedAt(), open.updatedAt()),
                        recommendation == null ? null : new QueueRecommendation(recommendation.budgetKind(), recommendation.countedToday(), recommendation.recommended(), recommendation.requiresBudgetConfirmation())), null, null);
        Continuation management = itemRoute(Route.MEMORY_CARDS, card.id());
        Continuation continuation = open != null ? new Continuation(Route.MEMORY_RECALL, null, null, new AttemptQuery(open.id()))
                : section == Section.TODAY ? new Continuation(Route.MEMORY_RECALL, null, null, null) : management;
        List<AvailableAction> actions = new ArrayList<>();
        actions.add(new AvailableAction(ActionKind.OPEN_SOURCE, management));
        if (open != null) actions.add(new AvailableAction(ActionKind.CONTINUE_MEMORY_ATTEMPT, continuation));
        else if (section == Section.TODAY) actions.add(new AvailableAction(ActionKind.OPEN_MEMORY_QUEUE, continuation));
        else if (section == Section.ATTENTION) actions.add(new AvailableAction(ActionKind.REVIEW_SOURCE, management));
        SourceTime time = schedule == null ? noneTime() : new SourceTime(TimeKind.INSTANT, schedule.effectiveDueAt(), null, context.day().zoneId(), ZoneBasis.DISPLAY);
        return item(context, SourceKind.MEMORY_CARD, card.id(), card.title(), section, stamp, state, reasons, List.of(), time, null,
                unknownEstimate(), continuation, null, actions, warnings);
    }

    private TodayItem item(Context context, SourceKind kind, String id, String title, Section section, SourceStamp stamp,
                           SourceStatus state, List<Reason> reasons, List<TargetRef> targets, SourceTime time, EventWindow event,
                           Estimate estimate, Continuation continuation, Integer pending, List<AvailableAction> actions, List<Warning> warnings) {
        storedId(id);
        // 各来源允许的合法原文可包含 emoji；摘要按码点计长，不把代理对误算成两个字或改写标题。
        if (title == null || title.isBlank() || title.codePointCount(0, title.length()) > 200) throw integrity();
        // 旧面试标题允许 NUL 且按 UTF-16 限长；只读安排保留来源原文，其他来源维持严格字符边界。
        if (kind == SourceKind.INTERVIEW_EVENT ? title.length() > 200 : title.indexOf('\0') >= 0) throw integrity();
        for (int index = 0; index < title.length(); index++) {
            char character = title.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= title.length() || !Character.isLowSurrogate(title.charAt(index + 1))) throw integrity();
                index++;
            } else if (Character.isLowSurrogate(character)) throw integrity();
        }
        List<TargetRef> targetRefs = targets.stream().peek(value -> { storedId(value.itemId()); storedId(value.versionId()); })
                .distinct().sorted(Comparator.comparing(TargetRef::itemId).thenComparing(TargetRef::versionId)).toList();
        EnumSet<Warning> uniqueWarnings = EnumSet.noneOf(Warning.class); uniqueWarnings.addAll(warnings);
        if (time.zoneBasis() == ZoneBasis.SOURCE && !time.zoneId().equals(context.day().zoneId())) uniqueWarnings.add(Warning.SOURCE_ZONE_DIFFERS);
        EnumSet<Reason> uniqueReasons = EnumSet.noneOf(Reason.class); uniqueReasons.addAll(reasons);
        TodayItem source = new TodayItem(new SourceRef(kind, id), stamp, title, section, state, List.copyOf(uniqueReasons), targetRefs,
                time, event, estimate, null, continuation, pending, List.copyOf(actions), List.copyOf(uniqueWarnings));
        return bounded(json, place(source, context), ITEM_BYTES);
    }

    private static ExclusionReason exclusion(TodayItem item, Context context) {
        ExclusionReason source = switch (item.source().kind()) {
            case KNOWLEDGE_DRAFT -> item.sourceStatus().knowledge().status() == KnowledgeDtos.Status.ARCHIVED ? ExclusionReason.KNOWLEDGE_ARCHIVED
                    : !item.sourceStatus().knowledge().unpublishedDraft() ? ExclusionReason.NO_UNPUBLISHED_DRAFT : null;
            case TRAINING_SESSION -> item.sourceStatus().training().status() == TrainingDtos.Status.ENDED ? ExclusionReason.TRAINING_ENDED : null;
            case PREPARATION_TASK -> switch (item.sourceStatus().task().status()) {
                case PAUSED -> ExclusionReason.TASK_PAUSED;
                case DONE -> ExclusionReason.TASK_DONE;
                case CANCELLED -> ExclusionReason.TASK_CANCELLED;
                case TODO -> item.time().localDate() != null && item.time().localDate().isAfter(context.day().asOf().atZone(ZoneId.of(item.time().zoneId())).toLocalDate())
                        ? ExclusionReason.TASK_FUTURE : null;
                case IN_PROGRESS -> null;
            };
            case REVIEW_ACTION -> item.sourceStatus().action().status() == ReviewDtos.ActionStatus.MERGED ? ExclusionReason.ACTION_MERGED
                    : item.pendingCount() == null || item.pendingCount() == 0 ? ExclusionReason.ACTION_NO_PENDING : null;
            case INTERVIEW_EVENT -> item.sourceStatus().interview().status() != ScheduleDtos.Status.PLANNED ? ExclusionReason.INTERVIEW_CLOSED
                    : item.reasons().isEmpty() ? ExclusionReason.INTERVIEW_OUTSIDE_WINDOW : null;
            case MEMORY_CARD -> {
                var state = item.sourceStatus().memory();
                if (state.cardStatus() != MemoryDtos.Status.ACTIVE) yield ExclusionReason.MEMORY_BLOCKED;
                if (state.openAttempt() != null || !state.eligibility().blockers().isEmpty()) yield null;
                if (state.scheduleStatus() == null) yield ExclusionReason.MEMORY_NO_SCHEDULE;
                if (state.scheduleStatus() == MemoryReviewDtos.ScheduleStatus.PAUSED) yield ExclusionReason.MEMORY_PAUSED;
                if (item.time().instant().isAfter(context.day().asOf())) yield ExclusionReason.MEMORY_FUTURE_DUE;
                if (state.recommendation() == null || !state.recommendation().recommended()) yield ExclusionReason.MEMORY_NOT_RECOMMENDED;
                yield null;
            }
        };
        if (source != null) return source;
        var placement = item.placement();
        if (placement != null) {
            if (placement.visibility() == TodayPlanDtos.Visibility.PAUSED) return ExclusionReason.PLACEMENT_PAUSED;
            if (suppressed(placement, context)) return ExclusionReason.PLACEMENT_SUPPRESSED_TODAY;
            if (future(placement, context)) return ExclusionReason.PLACEMENT_FUTURE;
        }
        throw integrity();
    }

    private void loadPlacements(Context context, List<SourceRef> sources) {
        List<SourceRef> missing = sources.stream().distinct().filter(source -> !context.placements().containsKey(source)).toList();
        if (missing.isEmpty()) return;
        if (missing.size() > 50) throw integrity();
        var rows = plans.placementsBySources(context.owner(), missing);
        Map<SourceRef, TodayPlanDtos.Placement> loaded = new HashMap<>();
        for (var row : rows) {
            if (!context.owner().equals(row.ownerId()) || (row.suppressedDate() == null) != (row.suppressedZoneId() == null)) throw integrity();
            var value = local(() -> TodayPlanRows.placement(row));
            if (!missing.contains(value.source()) || loaded.put(value.source(), value) != null) throw integrity();
            storedId(value.id()); storedRevision(value.revision()); storedTime(value.createdAt()); storedTime(value.updatedAt());
            if (value.orderValue() < 0 || value.orderValue() > 1_000_000 || value.priority() < 1 || value.priority() > 5
                    || (value.plannedDate() == null) != (value.planZoneId() == null)
                    || value.estimatedMinutes() != null && (value.estimatedMinutes() < 1 || value.estimatedMinutes() > 1440)
                    || value.updatedAt().isBefore(value.createdAt())) throw integrity();
            if (value.planZoneId() != null) storedZone(value.planZoneId());
            if (value.plannedDate() != null && (value.plannedDate().getYear() < 1000 || value.plannedDate().getYear() > 9999)) throw integrity();
            if (value.suppressedOn() != null) {
                storedZone(value.suppressedOn().zoneId());
                LocalDate day = value.suppressedOn().localDate();
                if (day == null || day.getYear() < 1000 || day.getYear() > 9999) throw integrity();
            }
        }
        missing.forEach(source -> context.placements().put(source, loaded.get(source)));
    }

    private TodayItem place(TodayItem item, Context context) {
        if (!context.placements().containsKey(item.source())) throw integrity();
        var placement = context.placements().get(item.source());
        if (placement == null) return item;
        boolean movable = movable(item.source().kind());
        boolean canEstimate = movable || item.source().kind() == SourceKind.MEMORY_CARD;
        if (!movable && (placement.plannedDate() != null || placement.visibility() != TodayPlanDtos.Visibility.VISIBLE)
                || !canEstimate && placement.estimatedMinutes() != null
                || placement.dateLocked() && item.source().kind() != SourceKind.PREPARATION_TASK
                && (!movable || placement.plannedDate() == null)) throw integrity();
        SourceTime time = movable && placement.plannedDate() != null
                ? new SourceTime(TimeKind.LOCAL_DATE, null, placement.plannedDate(), placement.planZoneId(), ZoneBasis.PLACEMENT) : item.time();
        Estimate estimate = canEstimate && placement.estimatedMinutes() != null
                ? new Estimate(placement.estimatedMinutes(), EstimateBasis.USER) : item.estimate();
        Section section = item.section();
        EnumSet<Reason> reasons = EnumSet.noneOf(Reason.class); reasons.addAll(item.reasons());
        EnumSet<Warning> warnings = EnumSet.noneOf(Warning.class); warnings.addAll(item.warnings());
        boolean paused = placement.visibility() == TodayPlanDtos.Visibility.PAUSED;
        boolean hidden = suppressed(placement, context);
        boolean future = future(placement, context);
        if (section != null) {
            if (protectedContinuation(item)) {
                if (paused || hidden || future) warnings.add(Warning.PLACEMENT_CONTINUATION_RETAINED);
            } else if (paused || hidden) section = null;
            else if (movable && placement.plannedDate() != null) {
                LocalDate current = context.day().asOf().atZone(ZoneId.of(placement.planZoneId())).toLocalDate();
                if (placement.plannedDate().isBefore(current)) { section = Section.ATTENTION; reasons.add(Reason.PLACEMENT_OVERDUE); }
                else if (placement.plannedDate().equals(current)) { section = Section.TODAY; reasons.add(Reason.PLACEMENT_TODAY); }
                else section = null;
            }
        }
        if (time.zoneBasis() == ZoneBasis.PLACEMENT && !time.zoneId().equals(context.day().zoneId())) warnings.add(Warning.SOURCE_ZONE_DIFFERS);
        return new TodayItem(item.source(), item.stamp(), item.title(), section, item.sourceStatus(), List.copyOf(reasons), item.targetRefs(), time,
                item.eventWindow(), estimate, placement, item.continuation(), item.pendingCount(), item.availableActions(), List.copyOf(warnings));
    }

    private static boolean movable(SourceKind kind) {
        return kind == SourceKind.KNOWLEDGE_DRAFT || kind == SourceKind.TRAINING_SESSION || kind == SourceKind.REVIEW_ACTION;
    }
    private static boolean protectedContinuation(TodayItem item) {
        return switch (item.source().kind()) {
            case TRAINING_SESSION -> item.sourceStatus().training().status() == TrainingDtos.Status.RUNNING || item.sourceStatus().training().status() == TrainingDtos.Status.PAUSED;
            case PREPARATION_TASK -> item.sourceStatus().task().status() == PlanningDtos.Status.IN_PROGRESS;
            case MEMORY_CARD -> item.sourceStatus().memory().openAttempt() != null;
            case INTERVIEW_EVENT -> item.sourceStatus().interview().status() == ScheduleDtos.Status.PLANNED
                    && (item.sourceStatus().interview().reminderPending() || item.sourceStatus().interview().timing() == ScheduleDtos.Timing.PAST
                    || item.sourceStatus().interview().timing() == ScheduleDtos.Timing.IN_PROGRESS);
            default -> false;
        };
    }
    private static boolean suppressed(TodayPlanDtos.Placement placement, Context context) {
        return placement.suppressedOn() != null && placement.suppressedOn().zoneId().equals(context.day().zoneId())
                && placement.suppressedOn().localDate().equals(context.day().localDate());
    }
    private static boolean future(TodayPlanDtos.Placement placement, Context context) {
        return placement.plannedDate() != null && placement.plannedDate().isAfter(context.day().asOf().atZone(ZoneId.of(placement.planZoneId())).toLocalDate());
    }
    private static int orderValue(TodayItem item) { return item.placement() == null ? 500_000 : item.placement().orderValue(); }

    private static boolean dueAndUsable(TodayItem item, Instant now) {
        var state = item.sourceStatus().memory();
        return state.cardStatus() == MemoryDtos.Status.ACTIVE && state.eligibility().basisReady()
                && state.openAttempt() == null && state.scheduleStatus() == MemoryReviewDtos.ScheduleStatus.ACTIVE
                && item.time().instant() != null && !item.time().instant().isAfter(now);
    }

    private static void checkProjectionIds(List<String> ids, List<MemoryTodayDtos.CardProjection> values) {
        if (values == null || values.size() != ids.size() || new HashSet<>(ids).size() != ids.size()) throw integrity();
        for (int index = 0; index < ids.size(); index++) {
            if (values.get(index) == null || values.get(index).card() == null || !ids.get(index).equals(values.get(index).card().id())) throw integrity();
        }
    }

    private static List<TargetRef> targets(String itemId, String versionId) {
        if ((itemId == null) != (versionId == null)) throw integrity();
        return itemId == null ? List.of() : List.of(new TargetRef(itemId, versionId));
    }
    private static Continuation itemRoute(Route route, String id) { return new Continuation(route, new ItemQuery(id), null, null); }
    private static SourceTime noneTime() { return new SourceTime(TimeKind.NONE, null, null, null, null); }
    private static Estimate unknownEstimate() { return new Estimate(null, EstimateBasis.UNKNOWN); }
    private static void storedRevision(long value) { if (value < 0 || value > MAX_INTEGER) throw integrity(); }
    private static void storedId(String value) {
        try { uuid(value); } catch (com.wenjie.studio.common.ApiException exception) { throw integrity(); }
    }
    private static void storedTime(Instant value) {
        try { time(value); } catch (com.wenjie.studio.common.ApiException exception) { throw integrity(); }
    }
    private static void storedZone(String value) {
        try { com.wenjie.studio.planning.PlanningRules.zone(value); } catch (com.wenjie.studio.common.ApiException exception) { throw integrity(); }
    }

    private record Context(String owner, DayContext day, String target, boolean overBudget, CalendarWindow window,
                           MemoryReviewDtos.Settings settings, long placementGeneration,
                           Map<SourceRef, TodayPlanDtos.Placement> placements) { boolean calendar() { return window != null; } }
    private record Slice(Capability capability, Count count, boolean scanLimited, List<TodayItem> items,
                         String nextCursor, MemoryQueueInfo memoryQueue, int scanned) {}

    private static <T> T require(T value) { if (value == null) throw integrity(); return value; }
    private static <T> T existing(T value) { if (value == null) throw missing(); return value; }
    private static <T> T local(Supplier<T> projection) {
        try { return projection.get(); }
        catch (IllegalArgumentException | DateTimeException exception) { throw integrity(); }
    }
}
