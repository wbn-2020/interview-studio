package com.wenjie.studio.graph;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import com.wenjie.studio.graph.GraphDtos.*;
import com.wenjie.studio.knowledge.KnowledgeDtos.LayoutSaved;
import com.wenjie.studio.knowledge.KnowledgeDtos.Position;
import com.wenjie.studio.knowledge.KnowledgeMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class GraphHistoryService {
    private final GraphMapper graph;
    private final KnowledgeMapper knowledge;
    private final IdempotencyService commands;
    private final JsonMapper json;

    public GraphHistoryService(GraphMapper graph, KnowledgeMapper knowledge,
                               IdempotencyService commands, JsonMapper json) {
        this.graph = graph;
        this.knowledge = knowledge;
        this.commands = commands;
        this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public State state(String owner) { return new State(currentRevision(owner), currentViewport(owner)); }

    public LayoutSaved save(String owner, String key, SaveState request) {
        if (request == null) throw GraphRules.invalid("请提供布局。");
        GraphRules.revision(request.expectedRevision());
        GraphRules.positions(request.positions());
        GraphRules.viewport(request.viewport());
        return commands.execute(owner, key, "PUT /api/v1/knowledge/graph/state", request,
                LayoutSaved.class, () -> saveWithinTransaction(owner, request.expectedRevision(),
                        request.positions(), request.viewport()));
    }

    // S1 的坐标保存入口也经过同一历史边界，避免旧客户端制造无法撤销的布局。
    @Transactional(propagation = Propagation.MANDATORY)
    public LayoutSaved saveLegacyWithinTransaction(String owner, long expectedRevision, List<Position> positions) {
        GraphRules.revision(expectedRevision);
        GraphRules.positions(positions);
        return saveWithinTransaction(owner, expectedRevision, positions, currentViewport(owner));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public HistoryPage history(String owner, Long before, int limit) {
        if (before != null && (before < 1 || before > GraphRules.MAX_REVISION)) {
            throw GraphRules.invalid("历史游标必须为正修订号。");
        }
        if (limit < 1 || limit > 50) throw GraphRules.invalid("每页历史数量必须为 1 至 50。");
        var rows = graph.history(owner, before, limit + 1);
        boolean more = rows.size() > limit;
        var items = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
        return new HistoryPage(items, more ? items.getLast().revision() : null);
    }

    public LayoutSaved restore(String owner, long revision, String key, Restore request) {
        if (revision < 1 || revision > GraphRules.MAX_REVISION || request == null) {
            throw GraphRules.invalid("请选择有效布局历史。");
        }
        GraphRules.revision(request.expectedRevision());
        return commands.execute(owner, key, "POST /api/v1/knowledge/graph/history/" + revision + "/restore",
                request, LayoutSaved.class, () -> {
                    expect(request.expectedRevision(), currentRevision(owner));
                    var row = graph.historyEntry(owner, revision);
                    if (row == null) throw new ApiException(404, "HISTORY_NOT_FOUND", "布局历史不存在或不可访问。");
                    Snapshot previous = json.readValue(row.beforeJson(), Snapshot.class);
                    var ids = previous.positions().stream().map(Position::id).toList();
                    Set<String> active = ids.isEmpty() ? Set.of() : knowledge.positionedActiveConcepts(owner, ids)
                            .stream().collect(Collectors.toSet());
                    // 归档节点保留坐标；撤销只触及当前仍可编辑的节点，不复活已移出图谱的内容。
                    var positions = previous.positions().stream().filter(p -> active.contains(p.id())).toList();
                    return saveWithinTransaction(owner, request.expectedRevision(), positions, previous.viewport());
                });
    }

    private LayoutSaved saveWithinTransaction(String owner, long expected, List<Position> positions, Viewport viewport) {
        long current = currentRevision(owner);
        expect(expected, current);
        if (current == GraphRules.MAX_REVISION) throw new ApiException(409, "REVISION_LIMIT", "布局修订号已达到上限。");
        var ids = positions.stream().map(Position::id).toList();
        var nodes = ids.isEmpty() ? List.<com.wenjie.studio.knowledge.KnowledgeRows.Node>of()
                : graph.activeNodes(owner, ids);
        if (nodes.size() != ids.size()) throw new ApiException(409, "LAYOUT_NODES_CHANGED", "布局包含已归档或不可访问的节点，请重新读取。", current);
        var before = nodes.stream().map(n -> new Position(n.id(), n.x(), n.y())).toList();
        Snapshot previous = new Snapshot(before, currentViewport(owner));
        for (Position position : positions) knowledge.updatePosition(owner, position.id(), position.x(), position.y());
        graph.saveViewport(owner, viewport);
        if (knowledge.advanceLayout(owner, current) != 1) throw new ApiException(409, "REVISION_CONFLICT", "布局已变化，请重新读取。", current);
        graph.insertHistory(owner, current + 1, json.writeValueAsString(previous),
                json.writeValueAsString(new Snapshot(positions, viewport)), positions.size(),
                Instant.now().truncatedTo(ChronoUnit.MICROS));
        return new LayoutSaved(current + 1);
    }

    private long currentRevision(String owner) {
        Long revision = knowledge.layoutRevision(owner);
        return revision == null ? 0 : revision;
    }

    private Viewport currentViewport(String owner) {
        Viewport viewport = graph.viewport(owner);
        return viewport == null ? new Viewport(0d, 0d, 1d) : viewport;
    }

    private static void expect(long expected, long actual) {
        if (expected != actual) throw new ApiException(409, "REVISION_CONFLICT", "星图布局已被其他操作修改，请保留本地位置并重新读取。", actual);
    }
}
