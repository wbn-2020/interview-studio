package com.wenjie.studio.knowledge;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import com.wenjie.studio.knowledge.KnowledgeDtos.Relation;
import com.wenjie.studio.knowledge.KnowledgeDtos.RelationType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.wenjie.studio.knowledge.RelationEvidenceDtos.*;
import static com.wenjie.studio.knowledge.RelationEvidenceRows.*;

@Service
public class RelationEvidenceService {
    private static final int MAX_SNAPSHOT_BYTES = 1_048_576;
    private static final Set<String> SNAPSHOT_FIELDS = Set.of("kind", "materials", "training");
    private static final Set<String> MATERIAL_FIELDS = Set.of("excerptId", "attachmentId", "fileName", "sha256", "origin",
            "runId", "segmentId", "locator", "text", "startOffset", "endOffset");
    private static final Set<String> TRAINING_FIELDS = Set.of("sessionId", "questionId", "answerId", "answerNumber",
            "answerCondition", "fragments");
    private static final Set<String> FRAGMENT_FIELDS = Set.of("field", "elementId", "start", "end", "quote");
    private final RelationEvidenceMapper mapper;
    private final RelationEvidenceSources sources;
    private final RelationSupplementService relations;
    private final IdempotencyService commands;
    private final JsonMapper snapshots;

    public RelationEvidenceService(RelationEvidenceMapper mapper, RelationEvidenceSources sources,
                                   RelationSupplementService relations, IdempotencyService commands) {
        this.mapper = mapper;
        this.sources = sources;
        this.relations = relations;
        this.commands = commands;
        snapshots = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8)
                                .maxStringLength(MAX_SNAPSHOT_BYTES).maxNumberLength(32).build()).build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page page(String owner, String relationId, String cursor, int limit) {
        uuid(owner); uuid(relationId);
        if (cursor != null) uuid(cursor);
        if (limit < 1 || limit > 20) throw invalid();
        Relation live = relation(owner, mapper.relation(owner, relationId));
        if (live == null && !mapper.hasHistory(owner, relationId)) throw relationMissing();
        List<EntryRow> rows = mapper.entries(owner, relationId, cursor, limit + 1);
        if (rows == null || rows.size() > limit + 1) throw broken();
        List<Entry> values = new ArrayList<>();
        Relation identity = live;
        String previous = cursor;
        // 列表只读元数据；已删边的历史也必须保持同一固定端点与类型。
        for (EntryRow row : rows) {
            Entry entry = view(owner, row);
            Relation tuple = tuple(entry);
            if (!relationId.equals(entry.relationId()) || previous != null && entry.id().compareTo(previous) <= 0
                    || identity != null && !identity.equals(tuple)) throw broken();
            identity = tuple;
            previous = entry.id();
            if (values.size() < limit) values.add(entry);
        }
        return new Page(relationId, live != null, List.copyOf(values), rows.size() > limit ? values.getLast().id() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detail detail(String owner, String relationId, String evidenceId) {
        uuid(owner); uuid(relationId); uuid(evidenceId);
        EntryRow row = mapper.entry(owner, evidenceId);
        if (row == null || !relationId.equals(row.relationId())) throw evidenceMissing();
        Entry entry = view(owner, row);
        Relation live = relation(owner, mapper.relation(owner, relationId));
        if (live != null && !live.equals(tuple(entry))) throw broken();
        SnapshotRow saved = mapper.snapshot(owner, evidenceId);
        if (saved == null || !owner.equals(saved.ownerId()) || !evidenceId.equals(saved.id())) throw broken();
        Snapshot snapshot = snapshot(saved.snapshotJson());
        verifyBindings(owner, row, snapshot);
        // 原文不可用与台账损坏分开处理；数据库、JSON 或绑定错误不能降级为不可用提示。
        return new Detail(entry, live != null, snapshot, sources.current(owner, snapshot));
    }

    public Entry create(String owner, String relationId, String key, Create request) {
        uuid(owner); uuid(relationId);
        if (request == null || !Boolean.TRUE.equals(request.confirmed())
                || !RelationEvidenceSources.validSelection(request.source())) throw invalid();
        reason(request.reason());
        // 首次写入在 owner 锁内固定来源；回执重放返回原元数据，不重新要求边或来源当前可用。
        return commands.execute(owner, key, "POST /api/v1/knowledge/relations/" + relationId + "/evidence",
                request, Entry.class, () -> {
                    Relation relation = relations.requireActiveWithinCommand(owner, relationId);
                    Snapshot snapshot = sources.capture(owner, request.source());
                    if (!RelationEvidenceSources.validSnapshot(snapshot) || !selected(snapshot).equals(request.source())) throw broken();
                    String stored = snapshots.writeValueAsString(snapshot);
                    if (stored.getBytes(StandardCharsets.UTF_8).length > MAX_SNAPSHOT_BYTES) {
                        throw new ApiException(422, "RELATION_EVIDENCE_TOO_LARGE", "所选证据超出保存上限，请减少所选原文后重试。");
                    }
                    TrainingInput training = snapshot.training();
                    EntryRow row = new EntryRow(owner, UUID.randomUUID().toString(), relation.id(), relation.sourceId(),
                            relation.targetId(), relation.type().name(), request.reason(), snapshot.kind().name(), count(snapshot),
                            training == null ? null : training.sessionId(), training == null ? null : training.questionId(),
                            training == null ? null : training.answerId(), Instant.now().truncatedTo(ChronoUnit.MICROS));
                    if (mapper.insertEntry(row, stored) != 1) throw broken();
                    for (int index = 0; index < snapshot.materials().size(); index++) {
                        MaterialBindingRow binding = new MaterialBindingRow(owner, row.id(), Kind.MATERIAL.name(),
                                snapshot.materials().get(index).excerptId(), index + 1);
                        if (mapper.insertMaterial(binding) != 1) throw broken();
                    }
                    return view(owner, row);
                });
    }

    private void verifyBindings(String owner, EntryRow row, Snapshot snapshot) {
        if (!row.sourceKind().equals(snapshot.kind().name()) || row.sourceCount() != count(snapshot)) throw broken();
        if (snapshot.kind() == Kind.TRAINING) {
            TrainingInput training = snapshot.training();
            if (!training.sessionId().equals(row.sessionId()) || !training.questionId().equals(row.questionId())
                    || !training.answerId().equals(row.answerId())) throw broken();
        }
        List<MaterialBindingRow> bindings = mapper.materials(owner, row.id());
        if (bindings == null || bindings.size() != snapshot.materials().size()) throw broken();
        for (int index = 0; index < bindings.size(); index++) {
            MaterialBindingRow binding = bindings.get(index);
            if (binding == null || !owner.equals(binding.ownerId()) || !row.id().equals(binding.evidenceId())
                    || !Kind.MATERIAL.name().equals(binding.sourceKind()) || binding.ordinal() != index + 1
                    || !snapshot.materials().get(index).excerptId().equals(binding.excerptId())) throw broken();
        }
    }

    private Snapshot snapshot(String value) {
        if (value == null || value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > MAX_SNAPSHOT_BYTES) throw broken();
        try {
            JsonNode tree = snapshots.readTree(value);
            fields(tree, SNAPSHOT_FIELDS);
            if (!tree.get("kind").isTextual() || !tree.get("materials").isArray()) throw broken();
            if ("MATERIAL".equals(tree.get("kind").asText())) {
                if (!tree.get("training").isNull() || tree.get("materials").isEmpty() || tree.get("materials").size() > 5) throw broken();
                for (JsonNode material : tree.get("materials")) {
                    fields(material, MATERIAL_FIELDS);
                    for (String name : Set.of("excerptId", "attachmentId", "fileName", "sha256", "origin", "locator", "text")) {
                        if (!material.get(name).isTextual()) throw broken();
                    }
                    for (String name : Set.of("runId", "segmentId")) if (!nullableText(material.get(name))) throw broken();
                    for (String name : Set.of("startOffset", "endOffset")) {
                        if (!(material.get(name).isNull() || integer(material.get(name)))) throw broken();
                    }
                }
            } else if ("TRAINING".equals(tree.get("kind").asText())) {
                if (!tree.get("materials").isEmpty()) throw broken();
                JsonNode training = tree.get("training");
                fields(training, TRAINING_FIELDS);
                for (String name : Set.of("sessionId", "questionId", "answerId", "answerCondition")) {
                    if (!training.get(name).isTextual()) throw broken();
                }
                if (!integer(training.get("answerNumber")) || !training.get("fragments").isArray()
                        || training.get("fragments").isEmpty() || training.get("fragments").size() > 8) throw broken();
                for (JsonNode fragment : training.get("fragments")) {
                    fields(fragment, FRAGMENT_FIELDS);
                    if (!fragment.get("field").isTextual() || !fragment.get("quote").isTextual()
                            || !nullableText(fragment.get("elementId")) || !integer(fragment.get("start"))
                            || !integer(fragment.get("end"))) throw broken();
                }
            } else throw broken();
            Snapshot result = snapshots.treeToValue(tree, Snapshot.class);
            if (!RelationEvidenceSources.validSnapshot(result)) throw broken();
            return result;
        } catch (RuntimeException exception) { throw broken(); }
    }

    private static Entry view(String owner, EntryRow row) {
        try {
            if (row == null || !owner.equals(row.ownerId()) || row.createdAt() == null) throw broken();
            uuid(row.id()); reason(row.reason());
            Relation relation = new Relation(row.relationId(), row.sourceId(), row.targetId(), RelationType.valueOf(row.type()));
            checkedTuple(relation);
            Kind kind = Kind.valueOf(row.sourceKind());
            if (row.sourceCount() < 1 || row.sourceCount() > (kind == Kind.MATERIAL ? 5 : 8)) throw broken();
            if (kind == Kind.TRAINING) { uuid(row.sessionId()); uuid(row.questionId()); uuid(row.answerId()); }
            else if (row.sessionId() != null || row.questionId() != null || row.answerId() != null) throw broken();
            return new Entry(row.id(), relation.id(), relation.sourceId(), relation.targetId(), relation.type(),
                    row.reason(), kind, row.sourceCount(), row.createdAt());
        } catch (ApiException | IllegalArgumentException exception) { throw broken(); }
    }

    private static Relation relation(String owner, RelationSupplementRows.RelationRow row) {
        if (row == null) return null;
        try {
            if (!owner.equals(row.ownerId()) || !"CONCEPT".equals(row.sourceKind()) || !"CONCEPT".equals(row.targetKind())) throw broken();
            Relation relation = new Relation(row.id(), row.sourceId(), row.targetId(), RelationType.valueOf(row.type()));
            checkedTuple(relation);
            return relation;
        } catch (ApiException | IllegalArgumentException exception) { throw broken(); }
    }

    private static Selection selected(Snapshot snapshot) {
        TrainingInput training = snapshot.training();
        return new Selection(snapshot.kind(), snapshot.materials().stream().map(source -> source.excerptId()).toList(),
                training == null ? null : new TrainingSelection(training.sessionId(), training.questionId(), training.answerId(), training.fragments()));
    }
    private static int count(Snapshot snapshot) { return snapshot.kind() == Kind.MATERIAL ? snapshot.materials().size() : snapshot.training().fragments().size(); }
    private static Relation tuple(Entry entry) { return new Relation(entry.relationId(), entry.sourceId(), entry.targetId(), entry.type()); }
    private static void checkedTuple(Relation value) {
        if (value == null || value.type() == null) throw invalid();
        uuid(value.id()); uuid(value.sourceId()); uuid(value.targetId());
        if (value.sourceId().equals(value.targetId())
                || (value.type() == RelationType.RELATED || value.type() == RelationType.CONTRASTS)
                && value.sourceId().compareTo(value.targetId()) > 0) throw invalid();
    }
    private static void fields(JsonNode value, Set<String> names) {
        if (value == null || !value.isObject() || value.size() != names.size()
                || names.stream().anyMatch(name -> !value.has(name))) throw broken();
    }
    private static boolean nullableText(JsonNode value) { return value != null && (value.isNull() || value.isTextual()); }
    private static boolean integer(JsonNode value) { return value != null && value.isIntegralNumber() && value.canConvertToInt(); }
    private static void reason(String value) {
        if (value == null || value.isEmpty() || value.length() > 1000 || value.indexOf('\0') >= 0
                || value.codePoints().anyMatch(point -> point >= 0xD800 && point <= 0xDFFF)
                || value.codePoints().allMatch(point -> Character.isWhitespace(point) || Character.isSpaceChar(point))) throw invalid();
    }
    private static void uuid(String value) {
        try { if (value == null || !UUID.fromString(value).toString().equals(value)) throw invalid(); }
        catch (IllegalArgumentException exception) { throw invalid(); }
    }
    private static ApiException invalid() { return new ApiException(400, "INVALID_REQUEST", "关系证据的身份、理由、来源或确认字段无效。"); }
    private static ApiException relationMissing() { return new ApiException(404, "RELATION_NOT_FOUND", "关系不存在或不可访问。"); }
    private static ApiException evidenceMissing() { return new ApiException(404, "RELATION_EVIDENCE_NOT_FOUND", "关系证据不存在或不可访问。"); }
    private static IllegalStateException broken() { return new IllegalStateException("关系证据记录与固定身份或确切来源不一致"); }
}
