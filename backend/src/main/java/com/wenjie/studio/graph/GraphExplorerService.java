package com.wenjie.studio.graph;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.graph.GraphDtos.Path;
import com.wenjie.studio.knowledge.KnowledgeDtos.*;
import com.wenjie.studio.knowledge.KnowledgeMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class GraphExplorerService {
    private static final int MAX_EDGES = 10_000;
    private final GraphMapper graph;
    private final KnowledgeMapper knowledge;

    public GraphExplorerService(GraphMapper graph, KnowledgeMapper knowledge) {
        this.graph = graph;
        this.knowledge = knowledge;
    }

    public Graph neighborhood(String owner, String centerId, int depth, int limit) {
        activeConcept(owner, centerId);
        if (depth < 1 || depth > 2 || limit < 1 || limit > 200) throw GraphRules.invalid("邻域深度为 1 至 2，节点上限为 1 至 200。");
        var rows = graph.activeRelations(owner, null, MAX_EDGES + 1);
        boolean truncated = rows.size() > MAX_EDGES;
        var edges = rows.subList(0, Math.min(rows.size(), MAX_EDGES));
        Set<String> ids = new LinkedHashSet<>(List.of(centerId));
        Set<String> frontier = Set.of(centerId);
        for (int level = 0; level < depth; level++) {
            Set<String> next = new LinkedHashSet<>();
            for (Relation edge : edges) {
                String neighbor = frontier.contains(edge.sourceId()) ? edge.targetId()
                        : frontier.contains(edge.targetId()) ? edge.sourceId() : null;
                if (neighbor == null || ids.contains(neighbor)) continue;
                if (ids.size() < limit) { ids.add(neighbor); next.add(neighbor); }
                else truncated = true;
            }
            frontier = next;
        }
        var nodes = graph.activeNodes(owner, List.copyOf(ids));
        var topics = new HashMap<String, List<String>>();
        for (var link : knowledge.topicLinks(owner, List.copyOf(ids))) {
            topics.computeIfAbsent(link.itemId(), ignored -> new ArrayList<>()).add(link.topicId());
        }
        var visibleEdges = edges.stream().filter(edge -> ids.contains(edge.sourceId()) && ids.contains(edge.targetId())).toList();
        truncated |= visibleEdges.size() > 600;
        var visibleNodes = nodes.stream().map(node -> new GraphNode(node.id(), node.title(),
                topics.getOrDefault(node.id(), List.of()), node.x(), node.y())).toList();
        Long revision = knowledge.layoutRevision(owner);
        return new Graph(visibleNodes, List.copyOf(visibleEdges.subList(0, Math.min(visibleEdges.size(), 600))),
                knowledge.topics(owner), revision == null ? 0 : revision, truncated);
    }

    public Path path(String owner, String fromId, String toId, String type) {
        activeConcept(owner, fromId);
        activeConcept(owner, toId);
        if (type != null) {
            try { RelationType.valueOf(type); }
            catch (IllegalArgumentException exception) { throw GraphRules.invalid("未知关系类型。"); }
        }
        if (fromId.equals(toId)) return new Path(List.of(fromId), List.of(), true, false);
        var rows = graph.activeRelations(owner, type, MAX_EDGES + 1);
        boolean truncated = rows.size() > MAX_EDGES;
        Map<String, List<Step>> outgoing = new HashMap<>();
        for (Relation edge : rows.subList(0, Math.min(rows.size(), MAX_EDGES))) {
            outgoing.computeIfAbsent(edge.sourceId(), ignored -> new ArrayList<>()).add(new Step(edge.targetId(), edge));
            // 路径遵守语义方向，只有明确对称的两类关系才允许反向经过。
            if (edge.type() == RelationType.RELATED || edge.type() == RelationType.CONTRASTS) {
                outgoing.computeIfAbsent(edge.targetId(), ignored -> new ArrayList<>()).add(new Step(edge.sourceId(), edge));
            }
        }
        var pending = new ArrayDeque<String>();
        var previous = new HashMap<String, Step>();
        pending.add(fromId);
        previous.put(fromId, null);
        while (!pending.isEmpty() && !previous.containsKey(toId)) {
            String current = pending.removeFirst();
            for (Step step : outgoing.getOrDefault(current, List.of())) {
                if (previous.containsKey(step.nodeId())) continue;
                previous.put(step.nodeId(), new Step(current, step.edge()));
                pending.addLast(step.nodeId());
            }
        }
        if (!previous.containsKey(toId)) return new Path(List.of(), List.of(), false, truncated);
        var ids = new ArrayList<String>();
        var edges = new ArrayList<Relation>();
        String current = toId;
        while (!current.equals(fromId)) {
            ids.add(current);
            Step step = previous.get(current);
            edges.add(step.edge());
            current = step.nodeId();
        }
        ids.add(fromId);
        Collections.reverse(ids);
        Collections.reverse(edges);
        return new Path(List.copyOf(ids), List.copyOf(edges), true, truncated);
    }

    private void activeConcept(String owner, String id) {
        GraphRules.uuid(id);
        var item = knowledge.findItem(owner, id);
        if (item == null || !"CONCEPT".equals(item.kind()) || !"ACTIVE".equals(item.status())) {
            throw new ApiException(404, "CONCEPT_NOT_FOUND", "概念不存在、已归档或不可访问。");
        }
    }

    private record Step(String nodeId, Relation edge) {}
}
