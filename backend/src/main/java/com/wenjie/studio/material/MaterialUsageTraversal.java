package com.wenjie.studio.material;

import com.wenjie.studio.ai.AiDtos;
import com.wenjie.studio.ai.AiManifestDigest;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.lifecycle.KnowledgeMaterialLineage;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.wenjie.studio.material.MaterialGuards.uuid;

/**
 * 沿原始不可变登记取完整使用闭包。组织、主题、白板和题单不属于正文采用边；
 * 固定训练快照只作路径节点，不能因此重写历史答案或制造一个可编辑的历史目标。
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class MaterialUsageTraversal {
    private static final int MAX_NODES = 500;
    private static final int MAX_BINDINGS = 2500;
    private static final int MAX_DEPTH = 100;
    private static final int MAX_JSON_BYTES = 262_144;
    private static final Set<String> TARGETS = Set.of("KNOWLEDGE", "PREPARATION", "QUESTION", "RELATION", "STANDARD_REVIEW");
    private final MaterialUsageTraversalMapper mapper;
    private final MaterialDependencyImpactMapper tasks;
    private final KnowledgeMaterialLineage lineage;
    private final AiManifestDigest digest;
    private final JsonMapper json;

    public MaterialUsageTraversal(MaterialUsageTraversalMapper mapper, MaterialDependencyImpactMapper tasks,
                                  KnowledgeMaterialLineage lineage, AiManifestDigest digest) {
        this.mapper = mapper; this.tasks = tasks; this.lineage = lineage; this.digest = digest;
        json = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                                .maxStringLength(1_048_576).maxNumberLength(32).build()).build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    public record Node(String domain, String id, int minimum) {}
    public record Step(Node source, MaterialUsageTraversalMapper.Edge edge) {}
    public record Route(List<Step> steps) {
        public MaterialUsageTraversalMapper.Edge target() { return steps.getLast().edge(); }
    }
    public record StatusImpact(String id, String itemId, String versionId, Instant createdAt) {}

    public List<Route> collect(String owner, String excerptId) {
        uuid(owner); uuid(excerptId);
        var minimums = new HashMap<String, Integer>();
        var cached = new HashMap<String, List<MaterialUsageTraversalMapper.Edge>>();
        var targets = new LinkedHashMap<String, Route>();
        var root = new Node("MATERIAL_EXCERPT", excerptId, 0);
        var nodes = new ArrayDeque<Node>();
        var routes = new ArrayDeque<List<Step>>();
        nodes.add(root); routes.add(List.of());
        int count = 0;
        while (!nodes.isEmpty()) {
            Node source = nodes.removeFirst(); List<Step> parent = routes.removeFirst();
            String nodeKey = source.domain() + "~" + source.id();
            Integer expanded = minimums.get(nodeKey);
            if (expanded != null && expanded <= source.minimum()) continue;
            minimums.put(nodeKey, source.minimum());
            if (minimums.size() > MAX_NODES) throw limit();
            var edges = cached.get(nodeKey);
            if (edges == null) {
                // 首次按对象读取全部原登记，后来从更低版本到达时重用全量行，不能漏掉较早采用。
                edges = mapper.edges(owner, source.domain(), source.id(), 0, MAX_BINDINGS - count + 1);
                if (edges == null) throw broken();
                count += edges.size();
                if (count > MAX_BINDINGS) throw limit();
                var seen = new HashSet<String>();
                for (var edge : edges) {
                    shape(edge);
                    if (!seen.add(edge.key())) throw broken();
                }
                cached.put(nodeKey, List.copyOf(edges));
            }
            if ("TASK".equals(source.domain()) && !edges.isEmpty()) task(owner, source.id(), true);
            for (var edge : edges) {
                if (edge.sourceNumber() < source.minimum()) continue;
                if (parent.size() >= MAX_DEPTH) throw limit();
                var path = new ArrayList<>(parent); path.add(new Step(source, edge));
                Route route = new Route(List.copyOf(path));
                if ("TASK".equals(edge.domain())) {
                    AiDtos.Manifest manifest = task(owner, edge.id(), false);
                    if (!witness(owner, source, edge, manifest)) throw broken();
                }
                if (TARGETS.contains(edge.domain())) {
                    String key = edge.domain() + "~" + edge.id() + "~" + edge.versionId() + "~" + edge.revision();
                    targets.putIfAbsent(key, route);
                    if (targets.size() > MAX_NODES) throw limit();
                }
                if (!Set.of("RELATION", "STANDARD_REVIEW").contains(edge.domain())) {
                    nodes.addLast(new Node(edge.domain(), edge.id(), edge.number() == null ? 0 : edge.number()));
                    routes.addLast(route.steps());
                }
            }
        }
        // 知识目标另核对实际来源。依赖提示不宣称正文复制，材料外发许可仍由真实祖先独立判定。
        for (Route route : targets.values()) {
            var edge = route.target();
            if ("KNOWLEDGE".equals(edge.domain())) lineage.excerptIds(owner, List.of(new AiDtos.SourceLink(edge.id(), edge.versionId())));
        }
        int bytes = 0;
        for (Route route : targets.values()) {
            bytes += encode(route).getBytes(StandardCharsets.UTF_8).length;
            if (bytes > 4_194_304) throw limit();
        }
        return List.copyOf(targets.values());
    }

    public void save(String owner, String impactId, String excerptId, Route route) {
        uuid(owner); uuid(impactId); uuid(excerptId);
        String value = encode(route);
        if (mapper.insertPath(owner, impactId, excerptId, value) != 1) throw broken();
    }

    public boolean prove(String owner, MaterialDependencyImpactMapper.Row impact) {
        var saved = mapper.path(owner, impact.id());
        if (saved == null || !owner.equals(saved.ownerId()) || !impact.id().equals(saved.impactId())
                || !impact.excerptId().equals(saved.excerptId()) || saved.pathJson() == null
                || saved.pathJson().getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) throw broken();
        Route route;
        try { route = json.readValue(saved.pathJson(), Route.class); }
        catch (RuntimeException exception) { throw broken(); }
        if (route == null || route.steps() == null || route.steps().isEmpty() || route.steps().size() > MAX_DEPTH
                || !fingerprint(route).equals(impact.bindingId())) throw broken();
        var previous = new Node("MATERIAL_EXCERPT", impact.excerptId(), 0);
        var visited = new HashSet<String>();
        for (Step step : route.steps()) {
            if (step == null || !previous.equals(step.source())) throw broken();
            shape(step.edge());
            if (step.edge().sourceNumber() < previous.minimum()
                    || !visited.add(previous.domain() + "~" + previous.id() + "~" + previous.minimum())) throw broken();
            var actual = mapper.edges(owner, previous.domain(), previous.id(), 0, MAX_BINDINGS + 1);
            if (actual == null || actual.size() > MAX_BINDINGS || actual.stream().filter(step.edge()::equals).count() != 1) throw broken();
            if ("TASK".equals(previous.domain())) task(owner, previous.id(), true);
            if ("TASK".equals(step.edge().domain())) {
                AiDtos.Manifest manifest = task(owner, step.edge().id(), false);
                if (!witness(owner, previous, step.edge(), manifest)) throw broken();
            }
            previous = new Node(step.edge().domain(), step.edge().id(), step.edge().number() == null ? 0 : step.edge().number());
        }
        var target = route.target();
        return target.domain().equals(impact.domain()) && target.id().equals(impact.targetId())
                && java.util.Objects.equals(target.versionId(), impact.targetVersionId())
                && java.util.Objects.equals(target.number(), impact.targetVersionNumber())
                && java.util.Objects.equals(target.revision(), impact.targetRevision())
                && target.title().equals(impact.targetTitle());
    }

    public String fingerprint(Route route) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(encode(route).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 不可用", exception); }
    }

    public boolean legacyKnowledge(String owner, String correctionId, Route route) {
        var target = route.target();
        return "KNOWLEDGE".equals(target.domain())
                && mapper.legacyKnowledge(owner, correctionId, target.id(), target.versionId());
    }

    public List<StatusImpact> statusChanged(String owner, MaterialLifecycleDtos.Event event, List<Route> routes) {
        uuid(owner);
        if (event == null || routes == null || routes.size() > MAX_NODES
                || !Set.of(MaterialLifecycleDtos.Action.ARCHIVE, MaterialLifecycleDtos.Action.RESTORE).contains(event.action())) throw broken();
        var knowledge = new ArrayList<StatusImpact>();
        for (Route route : routes) {
            String impactId = UUID.randomUUID().toString();
            if (route.steps().isEmpty() || !event.excerptId().equals(route.steps().getFirst().source().id())
                    || mapper.insertStatus(owner, event, impactId, fingerprint(route), route.target(), encode(route)) != 1) throw broken();
            if ("KNOWLEDGE".equals(route.target().domain())) {
                knowledge.add(new StatusImpact(impactId, route.target().id(), route.target().versionId(), event.createdAt()));
            }
        }
        return List.copyOf(knowledge);
    }

    private AiDtos.Manifest task(String owner, String taskId, boolean succeeded) {
        var task = tasks.task(owner, taskId);
        if (task == null || !owner.equals(task.ownerId()) || !taskId.equals(task.id())
                || succeeded && !"SUCCEEDED".equals(task.status())
                || task.manifestJson() == null || task.manifestJson().getBytes(StandardCharsets.UTF_8).length > 131_072) throw broken();
        AiDtos.Manifest manifest;
        try { manifest = json.readValue(task.manifestJson(), AiDtos.Manifest.class); }
        catch (RuntimeException exception) { throw broken(); }
        if (manifest == null || manifest.purpose() == null || !manifest.purpose().name().equals(task.purpose())
                || !java.util.Objects.equals(task.manifestHash(), digest.hash(manifest))) throw broken();
        try { AiDtos.Status.valueOf(task.status()); }
        catch (IllegalArgumentException | NullPointerException exception) { throw broken(); }
        return manifest;
    }

    private boolean witness(String owner, Node source, MaterialUsageTraversalMapper.Edge edge, AiDtos.Manifest manifest) {
        return switch (source.domain()) {
            case "MATERIAL_EXCERPT" ->
                    manifest.materialDraftSources() != null && manifest.materialDraftSources().stream().anyMatch(value -> source.id().equals(value.excerptId()))
                    || manifest.factDraft() != null && manifest.factDraft().materials() != null
                        && manifest.factDraft().materials().stream().anyMatch(value -> source.id().equals(value.excerptId()))
                    || manifest.relationEvidence() != null && manifest.relationEvidence().materials() != null
                        && manifest.relationEvidence().materials().stream().anyMatch(value -> source.id().equals(value.excerptId()));
            case "KNOWLEDGE", "PREPARATION" ->
                    manifest.sources() != null && manifest.sources().stream().anyMatch(value -> source.id().equals(value.itemId())
                        && value.number() == edge.sourceNumber()
                        && mapper.versionWitness(owner, source.domain(), source.id(), value.versionId(), value.number()))
                    || "PREPARATION".equals(source.domain()) && manifest.training() != null && manifest.training().context() != null
                        && manifest.training().context().stream().anyMatch(value -> source.id().equals(value.itemId())
                            && value.number() == edge.sourceNumber()
                            && mapper.versionWitness(owner, source.domain(), source.id(), value.versionId(), value.number()));
            case "TRAINING_QUESTION" ->
                    manifest.trainingDraft() != null && source.id().equals(manifest.trainingDraft().questionId())
                    || manifest.training() != null && source.id().equals(manifest.training().questionId())
                    || manifest.evaluation() != null && source.id().equals(manifest.evaluation().questionId());
            case "TRAINING_ANSWER" ->
                    manifest.trainingDraft() != null && source.id().equals(manifest.trainingDraft().answerId())
                    || manifest.training() != null && source.id().equals(manifest.training().answerId())
                    || manifest.evaluation() != null && source.id().equals(manifest.evaluation().answerId())
                    || manifest.factDraft() != null && manifest.factDraft().training() != null
                        && source.id().equals(manifest.factDraft().training().answerId())
                    || manifest.relationEvidence() != null && manifest.relationEvidence().training() != null
                        && source.id().equals(manifest.relationEvidence().training().answerId());
            case "TASK" ->
                    manifest.trainingDraft() != null && source.id().equals(manifest.trainingDraft().feedbackTaskId())
                    || manifest.relationAiFeedback() != null && source.id().equals(manifest.relationAiFeedback().taskId());
            default -> false;
        };
    }

    private String encode(Route route) {
        if (route == null || route.steps() == null || route.steps().isEmpty() || route.steps().size() > MAX_DEPTH) throw broken();
        String value = json.writeValueAsString(route);
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) throw limit();
        return value;
    }
    private static void shape(MaterialUsageTraversalMapper.Edge edge) {
        if (edge == null || edge.key() == null || edge.key().isEmpty() || edge.key().length() > 200
                || edge.sourceNumber() == null || edge.sourceNumber() < 0 || edge.domain() == null
                || !Set.of("KNOWLEDGE", "PREPARATION", "QUESTION", "RELATION", "STANDARD_REVIEW",
                    "TASK", "TRAINING_QUESTION", "TRAINING_ANSWER").contains(edge.domain())
                || edge.title() == null || edge.title().isBlank() || edge.title().codePointCount(0, edge.title().length()) > 200
                || edge.createdAt() == null) throw broken();
        uuid(edge.id());
        if (Set.of("KNOWLEDGE", "PREPARATION", "QUESTION").contains(edge.domain())) {
            uuid(edge.versionId());
            if (edge.number() == null || edge.number() < 1 || edge.revision() != null) throw broken();
        } else if (edge.versionId() != null || edge.number() != null
                || "STANDARD_REVIEW".equals(edge.domain()) && (edge.revision() == null || edge.revision() < 0)
                || !"STANDARD_REVIEW".equals(edge.domain()) && edge.revision() != null) throw broken();
        if (edge.taskId() != null) uuid(edge.taskId());
    }
    private static ApiException limit() {
        return new ApiException(422, "MATERIAL_DEPENDENCY_LIMIT", "材料完整使用闭包超过 500 个对象、2500 条登记或 100 层路径，本次变更未提交。");
    }
    private static IllegalStateException broken() { return new IllegalStateException("材料实际使用路径的原始登记或固定清单不一致"); }
}
