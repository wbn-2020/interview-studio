package com.wenjie.studio.knowledge;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import com.wenjie.studio.graph.GraphHistoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.wenjie.studio.knowledge.KnowledgeDtos.*;
import static com.wenjie.studio.knowledge.KnowledgeGuards.*;
import static com.wenjie.studio.knowledge.KnowledgeMergeDtos.*;

@Service
public class KnowledgeMergeService {
    private static final int MAX_RELATIONS = 10_000;
    private static final int MAX_BINDINGS = 10_000;
    private static final int MAX_REDIRECTS = 100;
    private final KnowledgeMergeMapper merges;
    private final KnowledgeMapper items;
    private final KnowledgeService knowledge;
    private final OrganizationService organization;
    private final IdempotencyService commands;
    private final GraphHistoryService layouts;
    private final JsonMapper canonical;

    public KnowledgeMergeService(KnowledgeMergeMapper merges, KnowledgeMapper items, KnowledgeService knowledge,
                                 OrganizationService organization, IdempotencyService commands,
                                 GraphHistoryService layouts, JsonMapper json) {
        this.merges = merges;
        this.items = items;
        this.knowledge = knowledge;
        this.organization = organization;
        this.commands = commands;
        this.layouts = layouts;
        this.canonical = json.rebuild().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 20)
    public Preview preview(String owner, PreviewRequest request) {
        required(request);
        pair(request.sourceId(), request.targetId());
        Snapshot snapshot = snapshot(owner, request.sourceId(), request.targetId());
        Plan plan = plan(snapshot, request.sourceId(), request.targetId());
        var source = snapshot.source();
        var target = snapshot.target();
        List<ImpactCount> impacts = new ArrayList<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (var binding : snapshot.bindings()) counts.merge(binding.kind(), 1L, Long::sum);
        counts.forEach((kind, count) -> impacts.add(new ImpactCount(kind, count)));
        impacts.add(new ImpactCount("RELATIONS_REPLACED", plan.moves().stream().filter(m -> m.outcome().equals("REPLACED")).count()));
        impacts.add(new ImpactCount("RELATIONS_DEDUPLICATED", plan.moves().stream().filter(m -> m.outcome().equals("DEDUPLICATED")).count()));
        impacts.add(new ImpactCount("RELATIONS_SELF_REMOVED", plan.moves().stream().filter(m -> m.outcome().equals("SELF_REMOVED")).count()));
        return new Preview(digest(snapshot), snapshot.layoutRevision(), source, target,
                suggestedLabels(target.organization().aliases(), source.organization().aliases(), source.item().title()),
                suggestedLabels(target.organization().tags(), source.organization().tags(), null),
                union(target.item().topicIds(), source.item().topicIds()),
                union(target.organization().noteIds(), source.organization().noteIds()), List.copyOf(impacts));
    }

    public Result commit(String owner, String key, Commit request) {
        Selection selected = selection(request);
        return commands.execute(owner, key, "POST /api/v1/knowledge/concept-merges", request, Result.class,
                () -> commitWithinCommand(owner, request, selected));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 20)
    public Resolution resolution(String owner, String id) {
        uuid(id);
        requireConcept(owner, id);
        List<MergeRecord> path = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String current = id;
        while (true) {
            if (!seen.add(current)) throw inconsistent("概念合并记录存在循环，暂时不能解析。");
            MergeRecord record = merges.bySource(owner, current);
            if (record == null) return new Resolution(id, current, List.copyOf(path));
            if (path.size() == MAX_REDIRECTS) throw capacity("概念合并链超出安全读取边界。");
            requireConcept(owner, record.targetId());
            path.add(record);
            current = record.targetId();
        }
    }

    private Result commitWithinCommand(String owner, Commit request, Selection selected) {
        // 外层命令持有 owner 写锁；摘要覆盖证据身份，不能只比较数量或覆盖已变化的预览。
        Snapshot snapshot = snapshot(owner, request.sourceId(), request.targetId());
        if (!request.expectedDigest().equals(digest(snapshot))) {
            throw new ApiException(409, "MERGE_PREVIEW_CHANGED", "知识、关系、引用或布局已变化，请重新预览并确认。");
        }
        Plan plan = plan(snapshot, request.sourceId(), request.targetId());
        if (!selected.topicIds().isEmpty()
                && items.ownedTopicIds(owner, selected.topicIds()).size() != selected.topicIds().size()) {
            throw new ApiException(404, "TOPIC_NOT_FOUND", "所选主题不存在或不可访问。");
        }
        ItemDetail source = snapshot.source().item();
        ItemDetail target = snapshot.target().item();
        if (target.revision() == MAX_REVISION) throw capacity("目标知识修订号已达到上限。");
        String mergeId = UUID.randomUUID().toString();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        // 先校验全结果的有向无环约束，再在同一事务内移除旧边、创建新边；历史理由仍指向旧身份。
        for (Move move : plan.moves()) items.removeRelation(owner, move.original().id());
        for (Relation relation : plan.created()) {
            items.insertRelation(owner, relation.id(), relation.sourceId(), relation.targetId(), relation.type().name(), now);
        }
        if (items.advanceMetadata(owner, target.id(), target.revision(), now) != 1) throw changed();
        items.clearTopics(owner, target.id());
        for (String topicId : selected.topicIds()) items.insertTopicLink(target.id(), topicId);
        var targetOrganization = organization.saveWithinCommand(owner, target.id(),
                new OrganizationDtos.OrganizationRequest(snapshot.target().organization().revision(),
                        selected.aliases(), selected.tags(), request.favorite(), selected.noteIds(), request.mainNoteId()));
        if (request.useSourcePosition() && (!Objects.equals(snapshot.source().position().x(), snapshot.target().position().x())
                || !Objects.equals(snapshot.source().position().y(), snapshot.target().position().y()))) {
            layouts.saveLegacyWithinTransaction(owner, snapshot.layoutRevision(), List.of(new Position(target.id(),
                    snapshot.source().position().x(), snapshot.source().position().y())));
        }
        // 复用归档失效传播，冻结卡片、训练证据、白板历史等不自动改绑到目标概念。
        knowledge.changeStatusWithinCommand(owner, source.id(), source.revision(), Status.ARCHIVED);
        items.insertOutbox(owner, target.id(), target.currentVersionId(), "METADATA", now);
        ItemDetail result = knowledge.detail(owner, target.id());
        var record = new MergeRecord(mergeId, source.id(), target.id(), source.currentVersionId(),
                target.currentVersionId(), result.revision(), now);
        var audit = new Audit(request, snapshot.source().organization(), snapshot.target().organization(),
                source.topicIds(), target.topicIds());
        int inserted = merges.insert(owner, new KnowledgeMergeMapper.SavedMerge(record, source.title(), target.title(),
                source.revision(), target.revision(), snapshot.source().organization().revision(),
                snapshot.target().organization().revision(), snapshot.layoutRevision(), layoutRevision(owner),
                canonical.writeValueAsString(audit)));
        if (inserted != 1) throw inconsistent("概念合并记录未能保存，本次变更未提交。");
        for (Move move : plan.moves()) {
            if (merges.insertRelationMapping(owner, mergeId, move.original(),
                    move.replacement() == null ? null : move.replacement().id(), move.outcome(), now) != 1) {
                throw inconsistent("关系迁移记录未能保存，本次变更未提交。");
            }
        }
        return new Result(record, result, targetOrganization);
    }

    private Snapshot snapshot(String owner, String sourceId, String targetId) {
        uuid(owner);
        pair(sourceId, targetId);
        List<Relation> relations = List.copyOf(items.relations(owner, MAX_RELATIONS + 1));
        if (relations.size() > MAX_RELATIONS) throw capacity("语义关系超出合并读取边界。");
        Side source = side(owner, sourceId, relations);
        Side target = side(owner, targetId, relations);
        List<KnowledgeMergeMapper.Binding> bindings = List.copyOf(merges.bindings(owner, List.of(sourceId, targetId), MAX_BINDINGS + 1));
        if (bindings.size() > MAX_BINDINGS) throw capacity("关联证据超出一次合并的安全边界，请先减少关联范围。");
        Set<String> endpoints = new LinkedHashSet<>();
        endpoints.add(targetId);
        for (Relation relation : source.relations()) {
            if (!relation.sourceId().equals(sourceId)) endpoints.add(relation.sourceId());
            if (!relation.targetId().equals(sourceId)) endpoints.add(relation.targetId());
        }
        List<String> activeEndpoints = merges.activeConceptIds(owner, endpoints.stream().sorted().toList());
        List<Topic> topics = List.copyOf(items.topics(owner));
        if (topics.size() > 200) throw capacity("主题超出合并读取边界。");
        return new Snapshot(source, target, layoutRevision(owner), relations, bindings, topics,
                endpoints.stream().sorted().toList(), List.copyOf(activeEndpoints));
    }

    private Side side(String owner, String id, List<Relation> relations) {
        ItemDetail detail = knowledge.detail(owner, id);
        if (detail.kind() != Kind.CONCEPT) throw invalid("只能合并两个知识概念。");
        if (detail.status() != Status.ACTIVE || merges.bySource(owner, id) != null) {
            throw new ApiException(409, "KNOWLEDGE_MERGED_OR_ARCHIVED", "请选择尚未合并的活跃知识概念。");
        }
        if (!detail.draft().title().equals(detail.published().title())
                || !detail.draft().body().equals(detail.published().body())) {
            throw new ApiException(409, "DRAFT_UNPUBLISHED", "概念存在未发布草稿，请先处理草稿再合并。", detail.draft().revision());
        }
        Position position = merges.position(owner, id);
        if (position == null) throw inconsistent("活跃概念缺少布局坐标，暂时不能合并。");
        return new Side(detail, organization.get(owner, id), position, relations.stream()
                .filter(relation -> relation.sourceId().equals(id) || relation.targetId().equals(id)).toList());
    }

    private Plan plan(Snapshot snapshot, String sourceId, String targetId) {
        if (!new HashSet<>(snapshot.activeEndpoints()).containsAll(snapshot.requiredEndpoints())) {
            throw new ApiException(409, "MERGE_RELATION_ENDPOINT_ARCHIVED", "待迁移关系包含已归档或不可访问的概念，请先处理这些关系。");
        }
        Map<String, Relation> result = new LinkedHashMap<>();
        Set<String> oldIds = new HashSet<>();
        snapshot.source().relations().forEach(relation -> oldIds.add(relation.id()));
        for (Relation relation : snapshot.relations()) if (!oldIds.contains(relation.id())) result.put(relationKey(relation), relation);
        List<Move> moves = new ArrayList<>();
        List<Relation> created = new ArrayList<>();
        for (Relation original : snapshot.source().relations()) {
            String source = original.sourceId().equals(sourceId) ? targetId : original.sourceId();
            String target = original.targetId().equals(sourceId) ? targetId : original.targetId();
            if (source.equals(target)) {
                moves.add(new Move(original, null, "SELF_REMOVED"));
                continue;
            }
            boolean symmetric = original.type() == RelationType.RELATED || original.type() == RelationType.CONTRASTS;
            if (symmetric && source.compareTo(target) > 0) {
                String swap = source; source = target; target = swap;
            }
            Relation replacement = new Relation(UUID.randomUUID().toString(), source, target, original.type());
            Relation existing = result.get(relationKey(replacement));
            if (existing != null) moves.add(new Move(original, existing, "DEDUPLICATED"));
            else {
                result.put(relationKey(replacement), replacement);
                created.add(replacement);
                moves.add(new Move(original, replacement, "REPLACED"));
            }
        }
        if (result.size() > MAX_RELATIONS) throw capacity("合并后关系数量超出安全边界。");
        rejectCycles(result.values().stream().toList(), RelationType.PRECEDES);
        rejectCycles(result.values().stream().toList(), RelationType.PART_OF);
        return new Plan(List.copyOf(moves), List.copyOf(created));
    }

    private static void rejectCycles(List<Relation> relations, RelationType type) {
        Map<String, List<String>> next = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        for (Relation relation : relations) if (relation.type() == type) {
            next.computeIfAbsent(relation.sourceId(), ignored -> new ArrayList<>()).add(relation.targetId());
            indegree.putIfAbsent(relation.sourceId(), 0);
            indegree.merge(relation.targetId(), 1, Integer::sum);
        }
        ArrayDeque<String> ready = new ArrayDeque<>();
        indegree.forEach((id, count) -> { if (count == 0) ready.addLast(id); });
        int visited = 0;
        while (!ready.isEmpty()) {
            String id = ready.removeFirst();
            visited++;
            for (String target : next.getOrDefault(id, List.of())) {
                if (indegree.compute(target, (ignored, count) -> count - 1) == 0) ready.addLast(target);
            }
        }
        if (visited != indegree.size()) throw new ApiException(409, "RELATION_CYCLE", "概念合并会形成" + type.name() + "关系循环，请先调整关系。");
    }

    private void requireConcept(String owner, String id) {
        var item = items.findItem(owner, id);
        if (item == null) throw new ApiException(404, "KNOWLEDGE_NOT_FOUND", "知识概念不存在或不可访问。");
        if (!Kind.CONCEPT.name().equals(item.kind())) throw invalid("合并身份仅适用于知识概念。");
    }

    private static Selection selection(Commit request) {
        required(request);
        pair(request.sourceId(), request.targetId());
        if (request.expectedDigest() == null || !request.expectedDigest().matches("[0-9a-f]{64}")) throw invalid("请提供合并预览摘要。");
        if (!Boolean.TRUE.equals(request.confirmed())) throw invalid("必须明确确认保留目标正文并合并概念。");
        required(request.favorite()); required(request.useSourcePosition());
        List<String> aliases = labels(request.aliases(), 100, "别名");
        List<String> tags = labels(request.tags(), 40, "标签");
        List<String> topics = ids(request.topicIds(), 20, "主题");
        List<String> notes = ids(request.noteIds(), 100, "关联笔记");
        if (request.mainNoteId() != null && !notes.contains(uuid(request.mainNoteId()))) throw invalid("主笔记必须属于最终关联笔记。");
        return new Selection(aliases, tags, topics, notes);
    }

    private static List<String> labels(List<String> values, int maxLength, String label) {
        if (values == null || values.size() > 20) throw invalid(label + "必须提供列表且最多20项。");
        Map<String, String> unique = new LinkedHashMap<>();
        for (String value : values) {
            if (value == null || value.strip().isEmpty() || value.strip().length() > maxLength) throw invalid(label + "长度无效。");
            unique.putIfAbsent(value.strip().toLowerCase(Locale.ROOT), value.strip());
        }
        return List.copyOf(unique.values());
    }

    private static List<String> ids(List<String> values, int max, String label) {
        if (values == null || values.size() > max) throw invalid(label + "列表超出允许范围。");
        Set<String> seen = new HashSet<>();
        for (String value : values) if (!seen.add(uuid(value))) throw invalid(label + "不能重复。");
        return List.copyOf(values);
    }

    private static void pair(String source, String target) {
        uuid(source); uuid(target);
        if (source.equals(target)) throw invalid("合并源和目标必须是不同的概念。");
    }

    private static List<String> suggestedLabels(List<String> target, List<String> source, String sourceTitle) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String value : target) values.putIfAbsent(value.toLowerCase(Locale.ROOT), value);
        if (sourceTitle != null) values.putIfAbsent(sourceTitle.toLowerCase(Locale.ROOT), sourceTitle);
        for (String value : source) values.putIfAbsent(value.toLowerCase(Locale.ROOT), value);
        // 预览不截断超限候选；最终列表必须由用户明确取舍后提交。
        return List.copyOf(values.values());
    }

    private static List<String> union(List<String> target, List<String> source) {
        Set<String> result = new LinkedHashSet<>(target); result.addAll(source);
        return List.copyOf(result);
    }
    private static String relationKey(Relation relation) { return relation.type() + "/" + relation.sourceId() + "/" + relation.targetId(); }
    private long layoutRevision(String owner) { Long value = items.layoutRevision(owner); return value == null ? 0 : value; }
    private String digest(Snapshot snapshot) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.writeValueAsString(snapshot).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("当前运行时不支持SHA-256", exception); }
    }
    private static ApiException capacity(String message) { return new ApiException(422, "CAPACITY_LIMIT", message); }
    private static ApiException inconsistent(String message) { return new ApiException(500, "INCONSISTENT_DATA", message); }
    private static ApiException changed() { return new ApiException(409, "REVISION_CONFLICT", "目标概念已变化，本次合并未提交。"); }

    private record Snapshot(Side source, Side target, long layoutRevision, List<Relation> relations,
                            List<KnowledgeMergeMapper.Binding> bindings, List<Topic> topics,
                            List<String> requiredEndpoints, List<String> activeEndpoints) {}
    private record Selection(List<String> aliases, List<String> tags, List<String> topicIds, List<String> noteIds) {}
    private record Move(Relation original, Relation replacement, String outcome) {}
    private record Plan(List<Move> moves, List<Relation> created) {}
    private record Audit(Commit choices, OrganizationDtos.Organization sourceOrganization,
                         OrganizationDtos.Organization targetOrganization, List<String> sourceTopicIds,
                         List<String> targetTopicIds) {}
}
