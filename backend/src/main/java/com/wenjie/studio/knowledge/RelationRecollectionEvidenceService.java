package com.wenjie.studio.knowledge;

import com.wenjie.studio.ai.AiDtos;
import com.wenjie.studio.ai.ReviewDraftDtos;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import com.wenjie.studio.knowledge.KnowledgeDtos.Relation;
import com.wenjie.studio.knowledge.KnowledgeDtos.RelationType;
import com.wenjie.studio.review.RecollectionDtos;
import com.wenjie.studio.review.RecollectionSources;
import com.wenjie.studio.review.ReviewDraftSources;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.wenjie.studio.knowledge.RelationRecollectionEvidenceDtos.*;
import static com.wenjie.studio.knowledge.RelationRecollectionEvidenceRows.*;

@Service
public class RelationRecollectionEvidenceService {
    private static final int MAX_SNAPSHOT_BYTES = 1_048_576;
    private static final Set<ReviewDraftDtos.Field> RECOLLECTION_FIELDS = Set.of(ReviewDraftDtos.Field.QUESTION,
            ReviewDraftDtos.Field.CONDITIONS, ReviewDraftDtos.Field.ANSWER_FRAGMENT, ReviewDraftDtos.Field.FEEDBACK,
            ReviewDraftDtos.Field.UNCERTAINTY, ReviewDraftDtos.Field.GAP);
    private static final Set<String> INPUT_FIELDS = Set.of("kind", "feedback", "recollection");
    private static final Set<String> RECOLLECTION_INPUT_FIELDS = Set.of("recollectionId", "versionId", "versionNumber",
            "sourceRevision", "title", "type", "baselineKind", "uncertainty", "fragments");
    private static final Set<String> FRAGMENT_FIELDS = Set.of("field", "start", "end", "quote");
    private static final Set<AiDtos.OriginState> STATES = Set.of(AiDtos.OriginState.CURRENT, AiDtos.OriginState.SUPERSEDED,
            AiDtos.OriginState.ARCHIVED, AiDtos.OriginState.REVIEW_REQUIRED, AiDtos.OriginState.UNAVAILABLE);
    private final RelationRecollectionEvidenceMapper mapper;
    private final RelationSupplementService relations;
    private final ReviewDraftSources sources;
    private final RecollectionSources recollections;
    private final IdempotencyService commands;
    private final JsonMapper snapshots;

    public RelationRecollectionEvidenceService(RelationRecollectionEvidenceMapper mapper, RelationSupplementService relations,
                                               ReviewDraftSources sources, RecollectionSources recollections,
                                               IdempotencyService commands) {
        this.mapper = mapper;
        this.relations = relations;
        this.sources = sources;
        this.recollections = recollections;
        this.commands = commands;
        snapshots = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8)
                                .maxStringLength(MAX_SNAPSHOT_BYTES).maxNumberLength(32).build()).build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
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
        // 列表与翻页探测只读元数据；删除边后也不能把另一条边的历史拼到原身份下。
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
        // 快照正文读取前先核本人历史版本归属；归档、补记与恢复不改变这份确切版本。
        RecollectionDtos.Version version = recollections.version(owner, row.recollectionId(), row.versionId());
        SnapshotRow saved = mapper.snapshot(owner, evidenceId);
        if (saved == null || !owner.equals(saved.ownerId()) || !evidenceId.equals(saved.id())) throw broken();
        ReviewDraftDtos.Input source = snapshot(saved.snapshotJson());
        verifyBindings(row, source);
        sources.requireAccess(owner, source);
        verifyVersion(version, source.recollection());
        // 当前状态与不可变内容分别核对，不能让归档或版本替代掩盖原文／限定损坏。
        AiDtos.OriginState state = sources.state(owner, source);
        if (state == null || !STATES.contains(state)) throw broken();
        return new Detail(entry, live != null, source, state);
    }

    public Entry create(String owner, String relationId, String key, Create request) {
        uuid(owner); uuid(relationId);
        if (request == null || !Boolean.TRUE.equals(request.confirmed())) throw invalid();
        reason(request.reason()); selection(request.source());
        ReviewDraftDtos.SourceSelection selection = sourceSelection(request.source());
        // 原键回放先核历史归属与训练访问；当前边、当前回忆修订仅约束首次追加。
        return commands.execute(owner, key, "POST /api/v1/knowledge/relations/" + relationId + "/recollection-evidence",
                request, Entry.class, () -> sources.requireAccess(owner, selection), () -> {
                    Relation relation = relations.requireActiveWithinCommand(owner, relationId);
                    ReviewDraftDtos.Input source = capture(owner, selection);
                    var fixed = source.recollection();
                    String stored = snapshots.writeValueAsString(source);
                    if (stored.getBytes(StandardCharsets.UTF_8).length > MAX_SNAPSHOT_BYTES) {
                        throw new ApiException(422, "RELATION_RECOLLECTION_EVIDENCE_TOO_LARGE", "所选回忆证据超出保存上限，请减少片段后重试。");
                    }
                    EntryRow row = new EntryRow(owner, UUID.randomUUID().toString(), relation.id(), relation.sourceId(),
                            relation.targetId(), relation.type().name(), request.reason(), fixed.recollectionId(), fixed.versionId(),
                            fixed.versionNumber(), fixed.sourceRevision(), fixed.fragments().size(),
                            Instant.now().truncatedTo(ChronoUnit.MICROS), fixed.versionNumber());
                    if (mapper.insertEntry(row, stored) != 1) throw broken();
                    EntryRow recorded = mapper.entry(owner, row.id());
                    if (!row.equals(recorded)) throw broken();
                    verifyBindings(recorded, source);
                    return view(owner, recorded);
                });
    }

    private ReviewDraftDtos.Input capture(String owner, ReviewDraftDtos.SourceSelection selected) {
        ReviewDraftDtos.Input source;
        try { source = sources.input(owner, selected); }
        catch (ApiException exception) {
            if ("AI_SOURCE_CHANGED".equals(exception.code())) throw sourceChanged();
            throw exception;
        }
        if (!valid(source)) throw broken();
        var fixed = source.recollection();
        var requested = selected.recollection();
        if (!fixed.recollectionId().equals(requested.recollectionId()) || !fixed.versionId().equals(requested.versionId())
                || fixed.sourceRevision() != requested.expectedRevision()) throw sourceChanged();
        if (!fixed.fragments().equals(requested.fragments())) throw broken();
        return source;
    }

    private static void verifyBindings(EntryRow row, ReviewDraftDtos.Input source) {
        var fixed = source.recollection();
        if (!row.recollectionId().equals(fixed.recollectionId()) || !row.versionId().equals(fixed.versionId())
                || row.versionNumber() != fixed.versionNumber() || row.sourceRevision() != fixed.sourceRevision()
                || row.sourceCount() != fixed.fragments().size()) throw broken();
    }

    private static void verifyVersion(RecollectionDtos.Version version, ReviewDraftDtos.RecollectionInput fixed) {
        if (version == null || !fixed.versionId().equals(version.id()) || fixed.versionNumber() != version.number()) throw broken();
        var content = version.content();
        if (content == null || !fixed.title().equals(content.title()) || fixed.type() != content.type()
                || fixed.baselineKind() != content.baselineKind() || !fixed.uncertainty().equals(content.uncertainty())) throw broken();
        for (var fragment : fixed.fragments()) {
            String original = switch (fragment.field()) {
                case QUESTION -> content.question(); case CONDITIONS -> content.conditions();
                case ANSWER_FRAGMENT -> content.answerFragment(); case FEEDBACK -> content.feedback();
                case UNCERTAINTY -> content.uncertainty(); case GAP -> content.gap(); default -> null;
            };
            int start = fragment.start(), end = fragment.end();
            if (original == null || start < 0 || end <= start || end > original.length()
                    || split(original, start) || split(original, end) || !fragment.quote().equals(original.substring(start, end))) throw broken();
        }
    }

    private ReviewDraftDtos.Input snapshot(String value) {
        if (value == null || value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > MAX_SNAPSHOT_BYTES) throw broken();
        try {
            JsonNode root = snapshots.readTree(value);
            fields(root, INPUT_FIELDS);
            if (!root.get("kind").isTextual() || !"RECOLLECTION".equals(root.get("kind").stringValue()) || !root.get("feedback").isNull()) throw broken();
            JsonNode recollection = root.get("recollection");
            fields(recollection, RECOLLECTION_INPUT_FIELDS);
            for (String name : Set.of("recollectionId", "versionId", "title", "type", "baselineKind", "uncertainty")) {
                if (!recollection.get(name).isTextual()) throw broken();
            }
            if (!integer(recollection.get("versionNumber")) || !recollection.get("sourceRevision").isIntegralNumber()
                    || !recollection.get("sourceRevision").canConvertToLong()) throw broken();
            JsonNode fragments = recollection.get("fragments");
            if (!fragments.isArray() || fragments.isEmpty() || fragments.size() > 8) throw broken();
            for (JsonNode fragment : fragments) {
                fields(fragment, FRAGMENT_FIELDS);
                if (!fragment.get("field").isTextual() || !fragment.get("quote").isTextual()
                        || !integer(fragment.get("start")) || !integer(fragment.get("end"))) throw broken();
            }
            ReviewDraftDtos.Input source = snapshots.treeToValue(root, ReviewDraftDtos.Input.class);
            if (!valid(source)) throw broken();
            return source;
        } catch (RuntimeException exception) { throw new IllegalStateException("关系回忆证据的固定快照结构无效", exception); }
    }

    private static Entry view(String owner, EntryRow row) {
        try {
            if (row == null || !owner.equals(row.ownerId()) || row.createdAt() == null) throw broken();
            uuid(row.id()); uuid(row.recollectionId()); uuid(row.versionId()); reason(row.reason());
            Relation relation = new Relation(row.relationId(), row.sourceId(), row.targetId(), RelationType.valueOf(row.type()));
            checkedTuple(relation);
            if (row.versionNumber() < 1 || row.versionNumber() > 1000 || row.sourceRevision() < 0 || row.sourceRevision() > 1000
                    || row.versionNumber() > row.sourceRevision() + 1 || row.sourceCount() < 1 || row.sourceCount() > 8
                    || !Objects.equals(row.actualVersionNumber(), row.versionNumber())) throw broken();
            return new Entry(row.id(), relation.id(), relation.sourceId(), relation.targetId(), relation.type(), row.reason(),
                    row.recollectionId(), row.versionId(), row.versionNumber(), row.sourceRevision(), row.sourceCount(), row.createdAt());
        } catch (ApiException | IllegalArgumentException | NullPointerException exception) { throw broken(); }
    }

    private static Relation relation(String owner, RelationSupplementRows.RelationRow row) {
        if (row == null) return null;
        try {
            if (!owner.equals(row.ownerId()) || !"CONCEPT".equals(row.sourceKind()) || !"CONCEPT".equals(row.targetKind())) throw broken();
            Relation result = new Relation(row.id(), row.sourceId(), row.targetId(), RelationType.valueOf(row.type()));
            checkedTuple(result);
            return result;
        } catch (ApiException | IllegalArgumentException | NullPointerException exception) { throw broken(); }
    }

    private static void selection(ReviewDraftDtos.RecollectionSelection value) {
        if (value == null || value.expectedRevision() == null || value.expectedRevision() < 0 || value.expectedRevision() > 1000
                || value.fragments() == null || value.fragments().isEmpty() || value.fragments().size() > 8) throw invalid();
        uuid(value.recollectionId()); uuid(value.versionId());
        for (int index = 0; index < value.fragments().size(); index++) {
            var fragment = value.fragments().get(index);
            if (fragment == null || fragment.field() == null || !RECOLLECTION_FIELDS.contains(fragment.field())
                    || fragment.start() == null || fragment.end() == null || fragment.start() < 0 || fragment.end() <= fragment.start()
                    || fragment.quote() == null || fragment.quote().isBlank() || fragment.quote().length() > 8000
                    || fragment.quote().indexOf('\0') >= 0
                    || fragment.quote().codePoints().anyMatch(point -> point >= 0xD800 && point <= 0xDFFF)
                    || (long) fragment.end() - fragment.start() != fragment.quote().length()) throw invalid();
            for (int previous = 0; previous < index; previous++) {
                var other = value.fragments().get(previous);
                if (fragment.field() == other.field() && fragment.start() < other.end() && other.start() < fragment.end()) throw invalid();
            }
        }
    }

    private static ReviewDraftDtos.SourceSelection sourceSelection(ReviewDraftDtos.RecollectionSelection value) {
        return new ReviewDraftDtos.SourceSelection(ReviewDraftDtos.Kind.RECOLLECTION, null, value);
    }
    private static boolean valid(ReviewDraftDtos.Input value) {
        return ReviewDraftSources.valid(value) && value.kind() == ReviewDraftDtos.Kind.RECOLLECTION && value.feedback() == null;
    }
    private static boolean split(String value, int position) {
        return position > 0 && position < value.length() && Character.isHighSurrogate(value.charAt(position - 1)) && Character.isLowSurrogate(value.charAt(position));
    }
    private static Relation tuple(Entry entry) { return new Relation(entry.relationId(), entry.sourceId(), entry.targetId(), entry.type()); }
    private static void checkedTuple(Relation value) {
        if (value == null || value.type() == null) throw invalid();
        uuid(value.id()); uuid(value.sourceId()); uuid(value.targetId());
        if (value.sourceId().equals(value.targetId()) || (value.type() == RelationType.RELATED || value.type() == RelationType.CONTRASTS)
                && value.sourceId().compareTo(value.targetId()) > 0) throw invalid();
    }
    private static void fields(JsonNode value, Set<String> names) {
        if (value == null || !value.isObject() || value.size() != names.size() || names.stream().anyMatch(name -> !value.has(name))) throw broken();
    }
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
    private static ApiException invalid() { return new ApiException(400, "INVALID_REQUEST", "关系回忆证据的身份、修订、理由、片段或确认字段无效。"); }
    private static ApiException sourceChanged() { return new ApiException(409, "RELATION_RECOLLECTION_SOURCE_CHANGED", "回忆修订、当前版本或所选原文已变化，请保留输入后重新核对。"); }
    private static ApiException relationMissing() { return new ApiException(404, "RELATION_NOT_FOUND", "关系不存在或不可访问。"); }
    private static ApiException evidenceMissing() { return new ApiException(404, "RELATION_RECOLLECTION_EVIDENCE_NOT_FOUND", "关系回忆证据不存在或不可访问。"); }
    private static IllegalStateException broken() { return new IllegalStateException("关系回忆证据与固定关系身份、回忆版本或原文不一致"); }
}
