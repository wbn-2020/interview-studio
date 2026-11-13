package com.wenjie.studio.knowledge;

import com.wenjie.studio.ai.AiDtos;
import com.wenjie.studio.ai.RelationAiFeedbackSources;
import com.wenjie.studio.ai.ReviewDraftDtos;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import com.wenjie.studio.knowledge.KnowledgeDtos.Relation;
import com.wenjie.studio.knowledge.KnowledgeDtos.RelationType;
import com.wenjie.studio.review.ReviewDraftSources;
import com.wenjie.studio.review.ReviewDtos.AnswerLink;
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

import static com.wenjie.studio.knowledge.RelationAiFeedbackEvidenceDtos.*;
import static com.wenjie.studio.knowledge.RelationAiFeedbackEvidenceRows.*;

@Service
public class RelationAiFeedbackEvidenceService {
    private static final int MAX_SNAPSHOT_BYTES = 1_048_576;
    private static final Set<ReviewDraftDtos.Field> FEEDBACK_FIELDS = Set.of(ReviewDraftDtos.Field.OBSERVATION,
            ReviewDraftDtos.Field.CONDITION, ReviewDraftDtos.Field.BASIS, ReviewDraftDtos.Field.LIMITATION, ReviewDraftDtos.Field.SUGGESTION);
    private static final Set<String> SNAPSHOT_FIELDS = Set.of("taskId", "attemptId", "feedbackId", "reportHash", "answer",
            "answerNumber", "answerCondition", "level", "reportLimitations", "validationNote", "dimension", "coverage",
            "decisionRevision", "decisionChoice", "condition", "basis", "limitation", "fragments");
    private static final Set<String> ANSWER_FIELDS = Set.of("sessionId", "questionId", "answerId");
    private static final Set<String> FRAGMENT_FIELDS = Set.of("field", "start", "end", "quote");
    private final RelationAiFeedbackEvidenceMapper mapper;
    private final RelationSupplementService relations;
    private final RelationAiFeedbackSources sources;
    private final ReviewDraftSources access;
    private final IdempotencyService commands;
    private final JsonMapper snapshots;

    public RelationAiFeedbackEvidenceService(RelationAiFeedbackEvidenceMapper mapper, RelationSupplementService relations,
                                             RelationAiFeedbackSources sources, ReviewDraftSources access, IdempotencyService commands) {
        this.mapper = mapper;
        this.relations = relations;
        this.sources = sources;
        this.access = access;
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
    public Source source(String owner, String taskId, String feedbackId) {
        uuid(owner); uuid(taskId); feedbackId(feedbackId);
        return sources.read(owner, taskId, feedbackId);
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
        for (EntryRow row : rows) {
            Entry entry = view(owner, row);
            // 人工理由也可能复述反馈；列表及下一页探测行都必须通过原答的当前查看条件。
            access.requireAnswerAccess(owner, answer(row));
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
        access.requireAnswerAccess(owner, answer(row));
        Relation live = relation(owner, mapper.relation(owner, relationId));
        if (live != null && !live.equals(tuple(entry))) throw broken();
        SnapshotRow saved = mapper.snapshot(owner, evidenceId);
        if (saved == null || !owner.equals(saved.ownerId()) || !evidenceId.equals(saved.id())) throw broken();
        Snapshot source = snapshot(saved.snapshotJson());
        verifyBindings(row, source);
        // 报告或决定变化不替换保存的原文；权限拒绝与结构损坏不能降级成普通不可用。
        AiDtos.OriginState state = sources.state(owner, source);
        if (state == null || !Set.of(AiDtos.OriginState.CURRENT, AiDtos.OriginState.REVIEW_REQUIRED,
                AiDtos.OriginState.UNAVAILABLE).contains(state)) throw broken();
        if (state == AiDtos.OriginState.CURRENT && relations.materialReviewRequired(owner, relationId, row.createdAt())) {
            state = AiDtos.OriginState.REVIEW_REQUIRED;
        }
        return new Detail(entry, live != null, source, state);
    }

    public Entry create(String owner, String relationId, String key, Create request) {
        uuid(owner); uuid(relationId);
        if (request == null || !Boolean.TRUE.equals(request.confirmed())) throw invalid();
        reason(request.reason()); selection(request.source());
        // 同键恢复只返回原元数据，但仍在回执前核对原答权限；边和来源当前性只限制首次追加。
        return commands.execute(owner, key, "POST /api/v1/knowledge/relations/" + relationId + "/ai-feedback-evidence",
                request, Entry.class, () -> access.requireAnswerAccess(owner, request.source().answer()), () -> {
                    Relation relation = relations.requireActiveWithinCommand(owner, relationId);
                    Snapshot source = sources.capture(owner, request.source());
                    if (!RelationAiFeedbackSources.validSnapshot(source)) throw broken();
                    if (!selected(source).equals(request.source())) throw sourceChanged();
                    String stored = snapshots.writeValueAsString(source);
                    if (stored.getBytes(StandardCharsets.UTF_8).length > MAX_SNAPSHOT_BYTES) {
                        throw new ApiException(422, "RELATION_AI_FEEDBACK_EVIDENCE_TOO_LARGE", "所选 AI 反馈证据超出保存上限，请减少片段后重试。");
                    }
                    Integer decisionRevision = source.decisionRevision() == 0 ? null : source.decisionRevision();
                    String decisionChoice = source.decisionChoice() == null ? null : source.decisionChoice().name();
                    EntryRow row = new EntryRow(owner, UUID.randomUUID().toString(), relation.id(), relation.sourceId(),
                            relation.targetId(), relation.type().name(), request.reason(), source.taskId(), source.attemptId(),
                            source.feedbackId(), source.reportHash(), source.fragments().size(), source.answer().sessionId(),
                            source.answer().questionId(), source.answer().answerId(), decisionRevision, decisionChoice,
                            Instant.now().truncatedTo(ChronoUnit.MICROS), decisionRevision, decisionChoice);
                    if (mapper.insertEntry(row, stored) != 1) throw broken();
                    EntryRow recorded = mapper.entry(owner, row.id());
                    if (!row.equals(recorded)) throw broken();
                    verifyBindings(recorded, source);
                    return view(owner, recorded);
                });
    }

    private static void verifyBindings(EntryRow row, Snapshot source) {
        if (!row.taskId().equals(source.taskId()) || !row.attemptId().equals(source.attemptId())
                || !row.feedbackId().equals(source.feedbackId()) || !row.reportHash().equals(source.reportHash())
                || row.sourceCount() != source.fragments().size() || !answer(row).equals(source.answer())
                || (row.decisionRevision() == null ? 0 : row.decisionRevision()) != source.decisionRevision()
                || !Objects.equals(row.decisionChoice(), source.decisionChoice() == null ? null : source.decisionChoice().name())) throw broken();
    }

    private Snapshot snapshot(String value) {
        if (value == null || value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > MAX_SNAPSHOT_BYTES) throw broken();
        try {
            JsonNode root = snapshots.readTree(value);
            fields(root, SNAPSHOT_FIELDS);
            for (String name : Set.of("taskId", "attemptId", "feedbackId", "reportHash", "answerCondition", "level",
                    "reportLimitations", "validationNote", "dimension", "coverage", "condition", "basis", "limitation")) {
                if (!root.get(name).isTextual()) throw broken();
            }
            if (!integer(root.get("answerNumber")) || !integer(root.get("decisionRevision"))
                    || !(root.get("decisionChoice").isNull() || root.get("decisionChoice").isTextual())) throw broken();
            JsonNode answer = root.get("answer");
            fields(answer, ANSWER_FIELDS);
            for (String name : ANSWER_FIELDS) if (!answer.get(name).isTextual()) throw broken();
            JsonNode fragments = root.get("fragments");
            if (!fragments.isArray() || fragments.isEmpty() || fragments.size() > 8) throw broken();
            for (JsonNode fragment : fragments) {
                fields(fragment, FRAGMENT_FIELDS);
                if (!fragment.get("field").isTextual() || !fragment.get("quote").isTextual()
                        || !integer(fragment.get("start")) || !integer(fragment.get("end"))) throw broken();
            }
            Snapshot source = snapshots.treeToValue(root, Snapshot.class);
            if (!RelationAiFeedbackSources.validSnapshot(source)) throw broken();
            return source;
        } catch (RuntimeException exception) { throw new IllegalStateException("关系 AI 反馈证据的固定快照结构无效", exception); }
    }

    private static Entry view(String owner, EntryRow row) {
        try {
            if (row == null || !owner.equals(row.ownerId()) || row.createdAt() == null) throw broken();
            uuid(row.id()); uuid(row.taskId()); uuid(row.attemptId()); feedbackId(row.feedbackId());
            hash(row.reportHash()); answer(answer(row)); reason(row.reason());
            Relation relation = new Relation(row.relationId(), row.sourceId(), row.targetId(), RelationType.valueOf(row.type()));
            checkedTuple(relation);
            if (row.sourceCount() < 1 || row.sourceCount() > 8) throw broken();
            if (row.decisionRevision() == null) {
                if (row.decisionChoice() != null || row.actualDecisionRevision() != null || row.actualDecisionChoice() != null) throw broken();
            } else {
                if (row.decisionRevision() < 1 || row.decisionRevision() > 20 || row.decisionChoice() == null
                        || !row.decisionRevision().equals(row.actualDecisionRevision())
                        || !row.decisionChoice().equals(row.actualDecisionChoice())) throw broken();
                AiDtos.FeedbackChoice.valueOf(row.decisionChoice());
            }
            return new Entry(row.id(), relation.id(), relation.sourceId(), relation.targetId(), relation.type(), row.reason(),
                    row.taskId(), row.attemptId(), row.feedbackId(), row.sourceCount(), row.createdAt());
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

    private static void selection(Selection value) {
        if (value == null || value.expectedDecisionRevision() == null || value.expectedDecisionRevision() < 0
                || value.expectedDecisionRevision() > 20 || value.fragments() == null || value.fragments().isEmpty()
                || value.fragments().size() > 8) throw invalid();
        uuid(value.taskId()); uuid(value.attemptId()); feedbackId(value.feedbackId()); hash(value.reportHash()); answer(value.answer());
        for (int index = 0; index < value.fragments().size(); index++) {
            var fragment = value.fragments().get(index);
            if (fragment == null || fragment.field() == null || !FEEDBACK_FIELDS.contains(fragment.field())
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

    private static Selection selected(Snapshot source) {
        return new Selection(source.taskId(), source.attemptId(), source.feedbackId(), source.reportHash(),
                source.decisionRevision(), source.answer(), source.fragments());
    }
    private static AnswerLink answer(EntryRow row) { return new AnswerLink(row.sessionId(), row.questionId(), row.answerId()); }
    private static void answer(AnswerLink value) {
        if (value == null) throw invalid();
        uuid(value.sessionId()); uuid(value.questionId()); uuid(value.answerId());
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
    private static void feedbackId(String value) { if (value == null || !value.matches("f([1-9]|1[0-2])")) throw invalid(); }
    private static void hash(String value) { if (value == null || !value.matches("[0-9a-f]{64}")) throw invalid(); }
    private static void uuid(String value) {
        try { if (value == null || !UUID.fromString(value).toString().equals(value)) throw invalid(); }
        catch (IllegalArgumentException exception) { throw invalid(); }
    }
    private static ApiException invalid() { return new ApiException(400, "INVALID_REQUEST", "关系 AI 反馈证据的身份、摘要、决定、理由、片段或确认字段无效。"); }
    private static ApiException sourceChanged() { return new ApiException(409, "RELATION_AI_FEEDBACK_SOURCE_CHANGED", "所选 AI 报告、反馈决定、确切提交或片段已变化，请保留输入后重新核对。"); }
    private static ApiException relationMissing() { return new ApiException(404, "RELATION_NOT_FOUND", "关系不存在或不可访问。"); }
    private static ApiException evidenceMissing() { return new ApiException(404, "RELATION_AI_FEEDBACK_EVIDENCE_NOT_FOUND", "关系 AI 反馈证据不存在或不可访问。"); }
    private static IllegalStateException broken() { return new IllegalStateException("关系 AI 反馈证据与固定报告、确切提交或决定不一致"); }
}
