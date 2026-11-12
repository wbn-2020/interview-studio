package com.wenjie.studio.knowledge;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import com.wenjie.studio.graph.GraphHistoryService;
import com.wenjie.studio.lifecycle.KnowledgeRevalidationPropagation;
import com.wenjie.studio.memory.MemoryInvalidation;
import com.wenjie.studio.knowledge.KnowledgeDtos.*;
import com.wenjie.studio.knowledge.KnowledgeRows.Item;
import com.wenjie.studio.review.ReviewedBasisInvalidation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class KnowledgeService {
    private static final long MAX_REVISION = 9_007_199_254_740_991L;
    private static final int MAX_RELATIONS = 10_000;
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern COLOR_PATTERN = Pattern.compile("#[0-9a-fA-F]{6}");

    private final KnowledgeMapper mapper;
    private final IdempotencyService commands;
    private final KnowledgeReferenceService references;
    private final GraphHistoryService graphHistory;
    private final MemoryInvalidation memoryInvalidation;
    private final ReviewedBasisInvalidation reviewedBasis;
    private final KnowledgeRevalidationPropagation revalidation;

    public KnowledgeService(KnowledgeMapper mapper, IdempotencyService commands,
                            KnowledgeReferenceService references, GraphHistoryService graphHistory,
                            MemoryInvalidation memoryInvalidation, ReviewedBasisInvalidation reviewedBasis,
                            KnowledgeRevalidationPropagation revalidation) {
        this.mapper = mapper;
        this.commands = commands;
        this.references = references;
        this.graphHistory = graphHistory;
        this.memoryInvalidation = memoryInvalidation;
        this.reviewedBasis = reviewedBasis;
        this.revalidation = revalidation;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ItemPage list(String owner, String kind, String status, String query, String cursor, int limit) {
        if (kind != null) enumValue(Kind.class, kind, "知识类型");
        enumValue(Status.class, status, "知识状态");
        if (cursor != null) uuid(cursor);
        range(limit, 1, 100, "每页数量");
        if (query != null && query.length() > 100) throw invalid("标题筛选不能超过 100 个字符。");
        String pattern = query == null || query.isEmpty() ? null : "%" + query.replace("!", "!!")
                .replace("%", "!%").replace("_", "!_") + "%";
        var rows = mapper.listItems(owner, kind, status, pattern, cursor, limit + 1);
        boolean hasMore = rows.size() > limit;
        var page = rows.subList(0, Math.min(rows.size(), limit));
        var links = topicMap(owner, page.stream().map(KnowledgeRows.Summary::id).toList());
        var items = page.stream().map(row -> new ItemSummary(row.id(), Kind.valueOf(row.kind()),
                row.title(), Status.valueOf(row.status()), row.revision(), row.versionNumber(), row.updatedAt(),
                links.getOrDefault(row.id(), List.of()))).toList();
        return new ItemPage(items, hasMore ? items.getLast().id() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ItemDetail detail(String owner, String id) {
        return detailWithinTransaction(owner, uuid(id));
    }

    public ItemDetail create(String owner, String key, CreateItem request) {
        return commands.execute(owner, key, "POST /api/v1/knowledge/items", request, ItemDetail.class,
                () -> createWithinCommand(owner, request));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ItemDetail createWithinCommand(String owner, CreateItem request) {
        requireWriteTransaction();
        uuid(owner);
        required(request);
        if (request.kind() == null) throw invalid("请选择笔记或概念。");
        String title = title(request.title());
        body(request.body());
        List<String> topicIds = topicIds(request.topicIds());
        requireTopics(owner, topicIds);
        String id = newId();
        String versionId = newId();
        Instant now = now();
        mapper.insertItem(owner, id, request.kind().name(), now);
        mapper.insertVersion(owner, id, versionId, 1, title, request.body(), now);
        mapper.insertDraft(id, title, request.body(), now);
        changed(mapper.initializeVersion(owner, id, versionId));
        references.indexPublishedVersion(owner, id, versionId, 1, request.body());
        for (String topicId : topicIds) mapper.insertTopicLink(id, topicId);
        if (request.kind() == Kind.CONCEPT) {
            UUID seed = UUID.fromString(id);
            double x = ((seed.getMostSignificantBits() >>> 16) & 0xffffL) / 65535.0 * 1600 - 800;
            double y = ((seed.getLeastSignificantBits() >>> 16) & 0xffffL) / 65535.0 * 1000 - 500;
            mapper.insertPosition(owner, id, x, y);
            advanceLayout(owner, layoutRevision(owner));
        }
        mapper.insertOutbox(owner, id, versionId, "CREATED", now);
        return detailWithinTransaction(owner, id);
    }

    public ItemDetail saveDraft(String owner, String id, String key, SaveDraft request) {
        uuid(id);
        required(request);
        revision(request.expectedRevision());
        String title = title(request.title());
        body(request.body());
        return commands.execute(owner, key, "PUT /api/v1/knowledge/items/" + id + "/draft", request,
                ItemDetail.class, () -> {
                    Item item = activeItem(owner, id);
                    Draft draft = requireDraft(owner, item.id());
                    expectRevision(request.expectedRevision(), draft.revision());
                    canAdvance(draft.revision());
                    Instant now = now();
                    changed(mapper.updateDraft(owner, id, request.expectedRevision(), title, request.body(), now));
                    mapper.touchItem(owner, id, now);
                    return detailWithinTransaction(owner, id);
                });
    }

    public ItemDetail publish(String owner, String id, String key, Publish request) {
        uuid(id);
        required(request);
        revision(request.expectedRevision());
        if (request.expectedVersionNumber() == null || request.expectedVersionNumber() < 1) {
            throw invalid("发布时必须提供当前版本号。");
        }
        return commands.execute(owner, key, "POST /api/v1/knowledge/items/" + id + "/publish", request,
                ItemDetail.class, () -> {
                    Item item = activeItem(owner, id);
                    Draft draft = requireDraft(owner, id);
                    expectRevision(request.expectedRevision(), draft.revision());
                    if (request.expectedVersionNumber() != item.versionNumber()) {
                        throw new ApiException(409, "VERSION_CONFLICT", "发布版本已变化，请读取当前版本后再发布。",
                                draft.revision());
                    }
                    canAdvance(draft.revision());
                    canAdvance(item.metadataRevision());
                    if (item.versionNumber() == Integer.MAX_VALUE) throw capacity("版本数量已达到上限。");
                    Version previous = requireVersion(owner, id, item.currentVersionId());
                    String versionId = newId();
                    Instant now = now();
                    int number = item.versionNumber() + 1;
                    // 发布只追加版本；同时推进草稿修订，旧页面不能继续消费同一份草稿。
                    mapper.insertVersion(owner, id, versionId, number, draft.title(), draft.body(), now);
                    references.indexPublishedVersion(owner, id, versionId, number, draft.body());
                    changed(mapper.advanceDraftRevision(owner, id, draft.revision(), now));
                    changed(mapper.publishVersion(owner, id, versionId, number, item.metadataRevision(), now));
                    memoryInvalidation.knowledgePublished(owner, id, Kind.valueOf(item.kind()), previous,
                            new Version(versionId, number, draft.title(), draft.body(), now), now);
                    reviewedBasis.knowledgePublished(owner, id, Kind.valueOf(item.kind()), previous,
                            new Version(versionId, number, draft.title(), draft.body(), now), now);
                    revalidation.knowledgePublished(owner, id, previous,
                            new Version(versionId, number, draft.title(), draft.body(), now), now);
                    mapper.insertOutbox(owner, id, versionId, "PUBLISHED", now);
                    return detailWithinTransaction(owner, id);
                });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ItemDetail publishWithinCommand(String owner, String id, String expectedVersionId,
                                            Long expectedDraftRevision, String titleValue, String bodyValue) {
        requireWriteTransaction();
        uuid(owner); uuid(id); uuid(expectedVersionId); revision(expectedDraftRevision);
        String title = title(titleValue);
        body(bodyValue);
        if (bodyValue.isBlank()) throw invalid("采用后的正文不能为空。");
        Item item = activeItem(owner, id);
        Draft draft = requireDraft(owner, id);
        expectRevision(expectedDraftRevision, draft.revision());
        if (!expectedVersionId.equals(item.currentVersionId())) {
            throw new ApiException(409, "VERSION_CONFLICT", "目标发布版本已变化，请重新对照目标内容。", draft.revision());
        }
        Version published = requireVersion(owner, id, item.currentVersionId());
        // 两份基线都需匹配；即使草稿修订已读到最新，也不能覆盖用户尚未发布的内容。
        if (!draft.title().equals(published.title()) || !draft.body().equals(published.body())) {
            throw new ApiException(409, "DRAFT_UNPUBLISHED", "目标存在未发布草稿，请先处理草稿或改为另存。", draft.revision());
        }
        canAdvance(draft.revision());
        canAdvance(item.metadataRevision());
        if (item.versionNumber() == Integer.MAX_VALUE) throw capacity("版本数量已达到上限。");
        String versionId = newId();
        int number = item.versionNumber() + 1;
        Instant now = now();
        mapper.insertVersion(owner, id, versionId, number, title, bodyValue, now);
        references.indexPublishedVersion(owner, id, versionId, number, bodyValue);
        changed(mapper.updateDraft(owner, id, draft.revision(), title, bodyValue, now));
        changed(mapper.publishVersion(owner, id, versionId, number, item.metadataRevision(), now));
        memoryInvalidation.knowledgePublished(owner, id, Kind.valueOf(item.kind()), published,
                new Version(versionId, number, title, bodyValue, now), now);
        reviewedBasis.knowledgePublished(owner, id, Kind.valueOf(item.kind()), published,
                new Version(versionId, number, title, bodyValue, now), now);
        revalidation.knowledgePublished(owner, id, published,
                new Version(versionId, number, title, bodyValue, now), now);
        mapper.insertOutbox(owner, id, versionId, "PUBLISHED", now);
        return detailWithinTransaction(owner, id);
    }

    public ItemDetail setTopics(String owner, String id, String key, SetTopics request) {
        uuid(id);
        required(request);
        revision(request.expectedRevision());
        List<String> topicIds = topicIds(request.topicIds());
        return commands.execute(owner, key, "PUT /api/v1/knowledge/items/" + id + "/topics", request,
                ItemDetail.class, () -> {
                    Item item = activeItem(owner, id);
                    expectRevision(request.expectedRevision(), item.metadataRevision());
                    canAdvance(item.metadataRevision());
                    requireTopics(owner, topicIds);
                    changed(mapper.advanceMetadata(owner, id, item.metadataRevision(), now()));
                    mapper.clearTopics(owner, id);
                    for (String topicId : topicIds) mapper.insertTopicLink(id, topicId);
                    mapper.insertOutbox(owner, id, item.currentVersionId(), "METADATA", now());
                    return detailWithinTransaction(owner, id);
                });
    }

    public ItemDetail changeStatus(String owner, String id, String key, ChangeStatus request, Status target) {
        uuid(id);
        required(request);
        revision(request.expectedRevision());
        String command = target == Status.ARCHIVED ? "archive" : "restore";
        return commands.execute(owner, key, "POST /api/v1/knowledge/items/" + id + "/" + command, request,
                ItemDetail.class, () -> {
                    Item item = requireItem(owner, id);
                    expectRevision(request.expectedRevision(), item.metadataRevision());
                    if (target.name().equals(item.status())) return detailWithinTransaction(owner, id);
                    canAdvance(item.metadataRevision());
                    if (target == Status.ARCHIVED) {
                        Draft draft = requireDraft(owner, id);
                        Version published = requireVersion(owner, id, item.currentVersionId());
                        if (!draft.title().equals(published.title()) || !draft.body().equals(published.body())) {
                            throw new ApiException(409, "DRAFT_UNPUBLISHED", "存在未发布草稿，请先发布或处理草稿再归档。",
                                    draft.revision());
                        }
                    }
                    Instant now = now();
                    changed(mapper.changeStatus(owner, id, target.name(), item.metadataRevision(), now));
                    memoryInvalidation.knowledgeStatusChanged(owner, id, Kind.valueOf(item.kind()), item.currentVersionId(),
                            Status.valueOf(item.status()), target, now);
                    reviewedBasis.knowledgeStatusChanged(owner, id, Kind.valueOf(item.kind()), item.currentVersionId(),
                            Status.valueOf(item.status()), target, now);
                    if (target == Status.ARCHIVED) revalidation.knowledgeArchived(owner, id, item.currentVersionId(), now);
                    if (Kind.CONCEPT.name().equals(item.kind())) advanceLayout(owner, layoutRevision(owner));
                    mapper.insertOutbox(owner, id, item.currentVersionId(),
                            target == Status.ARCHIVED ? "ARCHIVED" : "RESTORED", now);
                    return detailWithinTransaction(owner, id);
                });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public VersionPage versions(String owner, String id, Integer before, int limit) {
        uuid(id);
        if (before != null && before < 1) throw invalid("历史版本游标必须为正整数。");
        range(limit, 1, 50, "每页数量");
        requireItem(owner, id);
        var rows = mapper.versions(owner, id, before, limit + 1);
        boolean hasMore = rows.size() > limit;
        var page = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
        return new VersionPage(page, hasMore ? page.getLast().number() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Version version(String owner, String id, String versionId) {
        requireItem(owner, uuid(id));
        return requireVersion(owner, id, uuid(versionId));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TopicList topics(String owner) {
        return new TopicList(allTopics(owner));
    }

    public Topic createTopic(String owner, String key, CreateTopic request) {
        required(request);
        String name = trimmed(request.name(), 64, "主题名称");
        String normalizedName = name.toLowerCase(Locale.ROOT);
        if (normalizedName.length() > 64) throw invalid("主题名称规范化后超过 64 个字符。");
        if (request.color() == null || !COLOR_PATTERN.matcher(request.color()).matches()) {
            throw invalid("主题颜色必须为 #RRGGBB 格式。");
        }
        return commands.execute(owner, key, "POST /api/v1/knowledge/topics", request, Topic.class, () -> {
            if (mapper.findTopicByName(owner, normalizedName) != null) {
                throw new ApiException(409, "TOPIC_EXISTS", "同名主题已存在，请选择现有主题。");
            }
            if (allTopics(owner).size() >= 200) throw capacity("本阶段最多支持 200 个主题。");
            String id = newId();
            String color = request.color().toUpperCase(Locale.ROOT);
            mapper.insertTopic(owner, id, name, normalizedName, color, now());
            return new Topic(id, name, color);
        });
    }

    public Relation createRelation(String owner, String key, CreateRelation request) {
        return commands.execute(owner, key, "POST /api/v1/knowledge/relations", request, Relation.class,
                () -> createRelationWithinCommand(owner, request));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Relation createRelationWithinCommand(String owner, CreateRelation request) {
        return createRelationWithinCommand(owner, request, false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Relation createNewRelationWithinCommand(String owner, CreateRelation request) {
        return createRelationWithinCommand(owner, request, true);
    }

    private Relation createRelationWithinCommand(String owner, CreateRelation request, boolean requireNew) {
        requireWriteTransaction();
        uuid(owner);
        required(request);
        uuid(request.sourceId());
        uuid(request.targetId());
        if (request.type() == null) throw invalid("请选择关系类型。");
        if (request.sourceId().equals(request.targetId())) {
            throw new ApiException(400, "SELF_RELATION", "知识点不能与自身建立语义关系。");
        }
        boolean symmetric = request.type() == RelationType.RELATED || request.type() == RelationType.CONTRASTS;
        boolean swap = symmetric && request.sourceId().compareTo(request.targetId()) > 0;
        String source = swap ? request.targetId() : request.sourceId();
        String target = swap ? request.sourceId() : request.targetId();
        activeConcept(owner, source);
        activeConcept(owner, target);
        Relation existing = mapper.findRelation(owner, source, target, request.type().name());
        if (existing != null) {
            // AI 采用只为新建关系记录来源，不能给已有手工关系补写生成身份。
            if (requireNew) throw new ApiException(409, "RELATION_EXISTS", "相同端点和类型的关系已存在，请调整或忽略候选。");
            return existing;
        }
        List<Relation> edges = mapper.relations(owner, MAX_RELATIONS + 1);
        if (edges.size() >= MAX_RELATIONS) throw capacity("本阶段最多支持 10000 条语义关系。");
        if (request.type() == RelationType.PRECEDES || request.type() == RelationType.PART_OF) {
            // owner 写锁覆盖检查与插入；归档关系也参与检查，恢复时不会悄悄形成环。
            rejectCycle(edges, request.type(), source, target);
        }
        String id = newId();
        mapper.insertRelation(owner, id, source, target, request.type().name(), now());
        return new Relation(id, source, target, request.type());
    }

    public Removed removeRelation(String owner, String id, String key, RemoveRelation request) {
        uuid(id);
        required(request);
        return commands.execute(owner, key, "POST /api/v1/knowledge/relations/" + id + "/remove", request,
                Removed.class, () -> {
                    mapper.removeRelation(owner, id);
                    return new Removed(true);
                });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Graph graph(String owner, int limit) {
        range(limit, 1, 200, "图谱节点数量");
        // 节点、边和布局修订使用同一读取快照，避免返回无法对应坐标的修订号。
        long revision = layoutRevision(owner);
        var rows = mapper.graphNodes(owner, limit + 1);
        var page = rows.subList(0, Math.min(rows.size(), limit));
        List<String> ids = page.stream().map(KnowledgeRows.Node::id).toList();
        var links = topicMap(owner, ids);
        var edges = ids.isEmpty() ? List.<Relation>of() : mapper.graphEdges(owner, ids, 601);
        boolean truncated = rows.size() > limit || edges.size() > 600;
        var nodes = page.stream().map(row -> new GraphNode(row.id(), row.title(),
                links.getOrDefault(row.id(), List.of()), row.x(), row.y())).toList();
        return new Graph(nodes, List.copyOf(edges.subList(0, Math.min(edges.size(), 600))), allTopics(owner),
                revision, truncated);
    }

    public LayoutSaved saveLayout(String owner, String key, SaveLayout request) {
        required(request);
        revision(request.expectedRevision());
        if (request.positions() == null || request.positions().isEmpty() || request.positions().size() > 200) {
            throw invalid("一次布局保存需要包含 1 至 200 个节点。");
        }
        Set<String> unique = new HashSet<>();
        for (Position position : request.positions()) {
            required(position);
            uuid(position.id());
            if (!unique.add(position.id())) throw invalid("布局中不能重复包含同一节点。");
            coordinate(position.x());
            coordinate(position.y());
        }
        return commands.execute(owner, key, "PUT /api/v1/knowledge/graph/layout", request, LayoutSaved.class,
                () -> graphHistory.saveLegacyWithinTransaction(owner, request.expectedRevision(), request.positions()));
    }

    private ItemDetail detailWithinTransaction(String owner, String id) {
        Item item = requireItem(owner, id);
        Draft draft = requireDraft(owner, id);
        Version published = requireVersion(owner, id, item.currentVersionId());
        var links = topicMap(owner, List.of(id)).getOrDefault(id, List.of());
        return new ItemDetail(id, Kind.valueOf(item.kind()), draft.title(), Status.valueOf(item.status()),
                item.metadataRevision(), item.versionNumber(), item.updatedAt(), links,
                item.currentVersionId(), draft, published);
    }

    private Item requireItem(String owner, String id) {
        Item item = mapper.findItem(owner, id);
        if (item == null) throw new ApiException(404, "KNOWLEDGE_NOT_FOUND", "知识内容不存在或不可访问。");
        return item;
    }

    private Item activeItem(String owner, String id) {
        Item item = requireItem(owner, id);
        if (!Status.ACTIVE.name().equals(item.status())) {
            throw new ApiException(409, "KNOWLEDGE_ARCHIVED", "该内容已归档，请显式恢复后再编辑。", item.metadataRevision());
        }
        return item;
    }

    private void activeConcept(String owner, String id) {
        if (!Kind.CONCEPT.name().equals(activeItem(owner, id).kind())) {
            throw invalid("语义关系的两端必须是知识点。");
        }
    }

    private Draft requireDraft(String owner, String id) {
        Draft draft = mapper.findDraft(owner, id);
        if (draft == null) throw new ApiException(500, "INCONSISTENT_DATA", "知识草稿暂时无法读取。");
        return draft;
    }

    private Version requireVersion(String owner, String id, String versionId) {
        Version version = mapper.findVersion(owner, id, versionId);
        if (version == null) throw new ApiException(404, "VERSION_NOT_FOUND", "指定版本不存在或不可访问。");
        return version;
    }

    private Map<String, List<String>> topicMap(String owner, List<String> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<String, List<String>> links = new HashMap<>();
        for (var link : mapper.topicLinks(owner, ids)) {
            links.computeIfAbsent(link.itemId(), ignored -> new ArrayList<>()).add(link.topicId());
        }
        links.replaceAll((id, values) -> List.copyOf(values));
        return links;
    }

    private List<Topic> allTopics(String owner) {
        var topics = mapper.topics(owner);
        if (topics.size() > 200) throw capacity("主题数量超出本阶段读取边界，请先处理数据规模。");
        return List.copyOf(topics);
    }

    private void requireTopics(String owner, List<String> ids) {
        if (!ids.isEmpty() && mapper.ownedTopicIds(owner, ids).size() != ids.size()) {
            throw new ApiException(404, "TOPIC_NOT_FOUND", "所选主题不存在或不可访问。");
        }
    }

    private long layoutRevision(String owner) {
        Long revision = mapper.layoutRevision(owner);
        return revision == null ? 0 : revision;
    }

    private void advanceLayout(String owner, long current) {
        canAdvance(current);
        changed(mapper.advanceLayout(owner, current));
    }

    private static void rejectCycle(List<Relation> edges, RelationType type, String source, String target) {
        Map<String, List<String>> next = new HashMap<>();
        for (Relation edge : edges) {
            if (edge.type() == type) next.computeIfAbsent(edge.sourceId(), ignored -> new ArrayList<>()).add(edge.targetId());
        }
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(target);
        while (!queue.isEmpty()) {
            String id = queue.removeFirst();
            if (!visited.add(id)) continue;
            if (id.equals(source)) throw new ApiException(409, "RELATION_CYCLE", "该关系会形成循环，请调整方向或端点。");
            if (visited.size() > MAX_RELATIONS + 1) throw capacity("关系遍历超出安全边界。");
            queue.addAll(next.getOrDefault(id, List.of()));
        }
    }

    private static List<String> topicIds(List<String> ids) {
        if (ids == null || ids.size() > 20) throw invalid("每项知识必须提供主题列表，且最多关联 20 个主题。");
        Set<String> seen = new HashSet<>();
        for (String id : ids) {
            uuid(id);
            if (!seen.add(id)) throw invalid("不能重复关联同一主题。");
        }
        return List.copyOf(ids);
    }

    private static String uuid(String value) {
        if (value == null || !UUID_PATTERN.matcher(value).matches()) throw invalid("对象标识必须是标准小写 UUID。");
        return value;
    }

    private static void revision(Long value) {
        if (value == null || value < 0 || value > MAX_REVISION) throw invalid("必须提供有效的预期修订号。");
    }

    private static void expectRevision(long expected, long actual) {
        if (expected != actual) throw new ApiException(409, "REVISION_CONFLICT", "内容已在其他页面修改，请保留输入并读取服务端版本。", actual);
    }

    private static void canAdvance(long value) {
        if (value >= MAX_REVISION) throw capacity("修订号已达到安全上限，暂时不能继续修改。");
    }

    private static void changed(int count) {
        if (count != 1) throw new ApiException(409, "REVISION_CONFLICT", "保存条件已变化，本次修改未提交，请重新读取后处理。");
    }

    private static String title(String value) { return trimmed(value, 200, "标题"); }

    private static String trimmed(String value, int max, String label) {
        if (value == null) throw invalid(label + "不能为空。");
        String result = value.strip();
        if (result.isEmpty() || result.length() > max) throw invalid(label + "长度必须为 1 至 " + max + " 个字符。");
        return result;
    }

    private static void body(String value) {
        if (value == null || value.length() > 100_000) throw invalid("正文不能为空值，且不能超过 100000 个字符。");
    }

    private static void coordinate(Double value) {
        if (value == null || !Double.isFinite(value) || Math.abs(value) > 1_000_000) {
            throw invalid("坐标必须为绝对值不超过 1000000 的有限数值。");
        }
    }

    private static void range(int value, int min, int max, String name) {
        if (value < min || value > max) throw invalid(name + "必须在 " + min + " 至 " + max + " 之间。");
    }

    private static <E extends Enum<E>> void enumValue(Class<E> type, String value, String label) {
        try { Enum.valueOf(type, Objects.requireNonNull(value)); }
        catch (IllegalArgumentException | NullPointerException exception) { throw invalid(label + "无效。"); }
    }

    private static void required(Object value) {
        if (value == null) throw invalid("请求内容不能为空。");
    }

    private static String newId() { return UUID.randomUUID().toString(); }
    private static void requireWriteTransaction() {
        // 跨模块复用知识写入时，必须处于已取得 owner 写锁的外层幂等事务中。
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("知识写入必须处于命令事务中");
        }
    }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static ApiException invalid(String detail) { return new ApiException(400, "INVALID_REQUEST", detail); }
    private static ApiException capacity(String detail) { return new ApiException(422, "CAPACITY_LIMIT", detail); }
}
