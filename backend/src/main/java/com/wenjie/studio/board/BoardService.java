package com.wenjie.studio.board;

import com.wenjie.studio.board.BoardDtos.*;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import com.wenjie.studio.knowledge.KnowledgeDtos;
import com.wenjie.studio.knowledge.KnowledgeService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class BoardService {
    private static final long MAX_REVISION = 9_007_199_254_740_991L;
    private final BoardMapper mapper;
    private final IdempotencyService commands;
    private final KnowledgeService knowledge;
    private final JsonMapper json;

    public BoardService(BoardMapper mapper, IdempotencyService commands, KnowledgeService knowledge, JsonMapper json) {
        this.mapper = mapper;
        this.commands = commands;
        this.knowledge = knowledge;
        this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page list(String owner, String cursor, int limit) {
        if (cursor != null) uuid(cursor);
        pageSize(limit);
        var rows = mapper.list(owner, cursor, limit + 1);
        var items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        return new Page(items, rows.size() > limit ? items.getLast().id() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detail detail(String owner, String id) {
        Detail stored = stored(owner, uuid(id));
        // 当前画布显示最新发布版本；历史入口始终直接读不可变快照。
        return new Detail(stored.id(), stored.kind(), stored.title(), stored.revision(), stored.updatedAt(),
                stored.nodes(), stored.edges(), stored.viewport(), references(owner, stored.nodes()));
    }

    public Detail create(String owner, String key, Create request) {
        required(request);
        if (request.kind() == null) throw invalid("请选择脑图或白板。");
        String title = title(request.title());
        return commands.execute(owner, key, "POST /api/v1/knowledge/boards", request, Detail.class, () -> {
            Detail result = new Detail(UUID.randomUUID().toString(), request.kind(), title, 0, now(),
                    List.of(), List.of(), new Viewport(0.0, 0.0, 1.0), List.of());
            String serialized = json.writeValueAsString(result);
            mapper.insert(owner, result, serialized);
            recordHistory(owner, result, serialized);
            return result;
        });
    }

    public Detail save(String owner, String id, String key, Save request) {
        uuid(id);
        validate(request);
        return commands.execute(owner, key, "PUT /api/v1/knowledge/boards/" + id, request, Detail.class, () -> {
            Detail previous = stored(owner, id);
            expect(request.expectedRevision(), previous.revision());
            return persist(owner, previous, request);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public HistoryPage history(String owner, String id, Long before, int limit) {
        stored(owner, uuid(id));
        if (before != null) revision(before);
        pageSize(limit);
        var rows = mapper.history(owner, id, before, limit + 1);
        var items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        return new HistoryPage(items, rows.size() > limit ? items.getLast().revision() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detail snapshot(String owner, String id, long revision) {
        stored(owner, uuid(id));
        revision(revision);
        return requireSnapshot(owner, id, revision);
    }

    public Detail restore(String owner, String id, long historicalRevision, String key, Restore request) {
        uuid(id);
        revision(historicalRevision);
        required(request);
        revision(request.expectedRevision());
        return commands.execute(owner, key, "POST /api/v1/knowledge/boards/" + id + "/history/"
                + historicalRevision + "/restore", request, Detail.class, () -> {
            Detail previous = stored(owner, id);
            expect(request.expectedRevision(), previous.revision());
            Detail historical = requireSnapshot(owner, id, historicalRevision);
            // 恢复生成新修订，不覆盖旧记录；不可用引用会拒绝整个保存而非丢弃卡片。
            Save recovered = new Save(previous.revision(), historical.title(), historical.nodes(),
                    historical.edges(), historical.viewport());
            validate(recovered);
            return persist(owner, previous, recovered);
        });
    }

    public ConvertedText convertText(String owner, String id, String nodeId, String key, ConvertText request) {
        uuid(id); uuid(nodeId); required(request); revision(request.expectedRevision());
        String title = title(request.title());
        return commands.execute(owner, key, "POST /api/v1/knowledge/boards/" + id + "/nodes/" + nodeId
                + "/convert", request, ConvertedText.class, () -> {
            Detail previous = stored(owner, id);
            expect(request.expectedRevision(), previous.revision());
            Node node = previous.nodes().stream().filter(value -> value.id().equals(nodeId)).findFirst()
                    .orElseThrow(() -> new ApiException(404, "BOARD_NODE_NOT_FOUND", "画布节点不存在。"));
            if (node.type() != NodeType.TEXT) throw new ApiException(409, "BOARD_NODE_NOT_TEXT", "仅临时文本可以转为笔记。");
            // 领域方法共享外层 owner 锁与事务，不另开幂等回执，避免重复创建或部分提交。
            var item = knowledge.createWithinCommand(owner,
                    new KnowledgeDtos.CreateItem(KnowledgeDtos.Kind.NOTE, title, node.text(), List.of()));
            List<Node> nodes = previous.nodes().stream().map(value -> value.id().equals(nodeId)
                    ? new Node(value.id(), NodeType.REFERENCE, item.id(), "", value.x(), value.y(),
                    value.parentId(), value.collapsed(), value.group()) : value).toList();
            Detail board = persist(owner, previous, new Save(previous.revision(), previous.title(), nodes,
                    previous.edges(), previous.viewport()));
            return new ConvertedText(board, item.id());
        });
    }

    public ConvertedEdge convertEdge(String owner, String id, String edgeId, String key, ConvertEdge request) {
        uuid(id); uuid(edgeId); required(request); revision(request.expectedRevision());
        if (request.type() == null) throw invalid("请选择正式关系类型。");
        return commands.execute(owner, key, "POST /api/v1/knowledge/boards/" + id + "/edges/" + edgeId
                + "/convert", request, ConvertedEdge.class, () -> {
            Detail board = stored(owner, id);
            expect(request.expectedRevision(), board.revision());
            Edge edge = board.edges().stream().filter(value -> value.id().equals(edgeId)).findFirst()
                    .orElseThrow(() -> new ApiException(404, "BOARD_EDGE_NOT_FOUND", "画布连线不存在。"));
            Map<String, Node> nodes = new HashMap<>();
            board.nodes().forEach(node -> nodes.put(node.id(), node));
            Node source = nodes.get(edge.source());
            Node target = nodes.get(edge.target());
            if (source.type() != NodeType.REFERENCE || target.type() != NodeType.REFERENCE) {
                throw invalid("正式关系两端必须引用知识概念。");
            }
            var relation = knowledge.createRelationWithinCommand(owner,
                    new KnowledgeDtos.CreateRelation(source.itemId(), target.itemId(), request.type()));
            return new ConvertedEdge(relation);
        });
    }

    private Detail persist(String owner, Detail previous, Save request) {
        if (previous.revision() >= MAX_REVISION) throw new ApiException(422, "CAPACITY_EXCEEDED", "画布修订数量已达到上限。");
        Detail result = new Detail(previous.id(), previous.kind(), title(request.title()), previous.revision() + 1,
                now(), List.copyOf(request.nodes()), List.copyOf(request.edges()), request.viewport(),
                references(owner, request.nodes()));
        String serialized = json.writeValueAsString(result);
        if (mapper.update(owner, result, previous.revision(), serialized) != 1) {
            throw new ApiException(409, "REVISION_CONFLICT", "画布已更新，请比较服务器版本后处理本地修改。");
        }
        recordHistory(owner, result, serialized);
        return result;
    }

    private void recordHistory(String owner, Detail board, String serialized) {
        mapper.insertHistory(owner, board, serialized);
        for (Reference reference : board.references()) mapper.insertReference(owner, board.id(), board.revision(), reference);
    }

    private Detail stored(String owner, String id) {
        var row = mapper.find(owner, id);
        if (row == null) throw new ApiException(404, "BOARD_NOT_FOUND", "画布不存在或不可访问。");
        return json.readValue(row.snapshotJson(), Detail.class);
    }

    private Detail requireSnapshot(String owner, String id, long revision) {
        String snapshot = mapper.snapshot(owner, id, revision);
        if (snapshot == null) throw new ApiException(404, "BOARD_REVISION_NOT_FOUND", "画布历史修订不存在。");
        return json.readValue(snapshot, Detail.class);
    }

    private List<Reference> references(String owner, List<Node> nodes) {
        List<String> ids = nodes.stream().filter(node -> node.type() == NodeType.REFERENCE)
                .map(Node::itemId).distinct().sorted().toList();
        if (ids.isEmpty()) return List.of();
        var refs = mapper.references(owner, ids);
        if (refs.size() != ids.size()) throw new ApiException(409, "REFERENCE_UNAVAILABLE", "画布包含不存在或不可访问的知识引用，请保留本地内容并核对来源。");
        return List.copyOf(refs);
    }

    private static void validate(Save request) {
        required(request); revision(request.expectedRevision()); title(request.title());
        if (request.nodes() == null || request.nodes().size() > 100) throw invalid("画布最多包含 100 个节点。");
        if (request.edges() == null || request.edges().size() > 200) throw invalid("画布最多包含 200 条连线。");
        required(request.viewport()); coordinate(request.viewport().x()); coordinate(request.viewport().y());
        if (request.viewport().zoom() == null || !Double.isFinite(request.viewport().zoom())
                || request.viewport().zoom() < 0.05 || request.viewport().zoom() > 10) throw invalid("缩放比例需要在 0.05–10 之间。");
        Map<String, Node> nodes = new HashMap<>();
        int total = 0;
        for (Node node : request.nodes()) {
            required(node); uuid(node.id()); coordinate(node.x()); coordinate(node.y());
            if (nodes.put(node.id(), node) != null) throw invalid("画布节点 ID 不能重复。");
            if (node.type() == null || node.collapsed() == null || node.text() == null) throw invalid("节点类型、折叠状态和文本不能为空。");
            if (node.group() != null && (node.group().isBlank() || node.group().length() > 40)) throw invalid("分组名称需要为 1–40 个字符或 null。");
            if (node.type() == NodeType.REFERENCE) {
                uuid(node.itemId());
                if (!node.text().isEmpty()) throw invalid("引用节点通过知识对象读取内容，不保存另一份正文。");
            } else {
                if (node.itemId() != null || node.text().length() > 5000) throw invalid("临时文本不绑定知识 ID，且最多 5000 个字符。");
                total += node.text().length();
            }
        }
        if (total > 50000) throw invalid("画布临时文本总计不能超过 50000 个字符。");
        for (Node node : nodes.values()) {
            Set<String> ancestors = new HashSet<>();
            String current = node.id();
            while (current != null) {
                if (!ancestors.add(current)) throw invalid("脑图父子层级不能形成循环。");
                Node ancestor = nodes.get(current);
                if (ancestor == null) throw invalid("父节点必须属于当前画布。");
                current = ancestor.parentId();
            }
        }
        Set<String> edgeIds = new HashSet<>();
        for (Edge edge : request.edges()) {
            required(edge); uuid(edge.id()); uuid(edge.source()); uuid(edge.target());
            if (!edgeIds.add(edge.id())) throw invalid("画布连线 ID 不能重复。");
            if (edge.source().equals(edge.target())) throw invalid("画布连线不能连接节点自身。");
            if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) throw invalid("连线两端必须属于当前画布。");
            if (edge.label() == null || edge.label().length() > 100) throw invalid("连线标签最多 100 个字符。");
        }
    }

    private static void expect(long expected, long actual) {
        if (expected != actual) throw new ApiException(409, "REVISION_CONFLICT", "画布已在其他页面更新，请比较服务器版本后再保存。", actual);
    }
    private static void required(Object value) { if (value == null) throw invalid("请求内容不完整。"); }
    private static void revision(Long value) { if (value == null || value < 0 || value > MAX_REVISION) throw invalid("修订号无效。"); }
    private static void pageSize(int value) { if (value < 1 || value > 50) throw invalid("每页数量需要在 1–50 之间。"); }
    private static String title(String value) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > 200) throw invalid("标题需要为 1–200 个字符。");
        return value.trim();
    }
    private static void coordinate(Double value) {
        if (value == null || !Double.isFinite(value) || Math.abs(value) > 1_000_000) throw invalid("画布坐标需要在 ±1000000 范围内。");
    }
    private static String uuid(String value) {
        try { if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException failure) { throw invalid("对象标识必须为标准小写 UUID。"); }
        return value;
    }
    private static ApiException invalid(String message) { return new ApiException(400, "INVALID_REQUEST", message); }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
}
