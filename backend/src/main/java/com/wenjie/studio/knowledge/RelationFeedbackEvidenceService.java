package com.wenjie.studio.knowledge;

import com.wenjie.studio.ai.AiDtos;
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

import static com.wenjie.studio.knowledge.RelationFeedbackEvidenceDtos.*;
import static com.wenjie.studio.knowledge.RelationFeedbackEvidenceRows.*;

@Service
public class RelationFeedbackEvidenceService {
    private static final int MAX_SNAPSHOT_BYTES = 1_048_576;
    private static final Set<ReviewDraftDtos.Kind> KINDS = Set.of(ReviewDraftDtos.Kind.SELF_CHECK, ReviewDraftDtos.Kind.EXTERNAL_FEEDBACK);
    private static final Set<ReviewDraftDtos.Field> FEEDBACK_FIELDS = Set.of(ReviewDraftDtos.Field.OBSERVATION,
            ReviewDraftDtos.Field.CONDITION, ReviewDraftDtos.Field.BASIS, ReviewDraftDtos.Field.LIMITATION, ReviewDraftDtos.Field.SUGGESTION);
    private static final Set<String> INPUT_FIELDS = Set.of("kind", "feedback", "recollection");
    private static final Set<String> FEEDBACK_INPUT_FIELDS = Set.of("feedbackId", "feedbackRevision", "answer", "answerNumber",
            "answerCondition", "dimension", "coverage", "decisionRevision", "decisionChoice", "condition", "basis", "limitation", "fragments");
    private static final Set<String> ANSWER_FIELDS = Set.of("sessionId", "questionId", "answerId");
    private static final Set<String> FRAGMENT_FIELDS = Set.of("field", "start", "end", "quote");
    private final RelationFeedbackEvidenceMapper mapper;
    private final RelationSupplementService relations;
    private final ReviewDraftSources sources;
    private final IdempotencyService commands;
    private final JsonMapper snapshots;

    public RelationFeedbackEvidenceService(RelationFeedbackEvidenceMapper mapper, RelationSupplementService relations,
                                           ReviewDraftSources sources, IdempotencyService commands) {
        this.mapper = mapper;
        this.relations = relations;
        this.sources = sources;
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
    public ReviewDraftDtos.FeedbackText source(String owner, String feedbackId, int revision) {
        uuid(owner); uuid(feedbackId);
        if (revision < 1 || revision > 100) throw invalid();
        try { return sources.readFeedbackSource(owner, feedbackId, revision); }
        catch (ApiException exception) {
            if ("AI_SOURCE_CHANGED".equals(exception.code())) throw sourceChanged();
            throw exception;
        }
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
            // 理由也可能包含反馈原文；包括下一页探测行在内，不能绕过确切提交的查看条件。
            sources.requireAnswerAccess(owner, answer(row));
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
        sources.requireAnswerAccess(owner, answer(row));
        Relation live = relation(owner, mapper.relation(owner, relationId));
        if (live != null && !live.equals(tuple(entry))) throw broken();
        SnapshotRow saved = mapper.snapshot(owner, evidenceId);
        if (saved == null || !owner.equals(saved.ownerId()) || !evidenceId.equals(saved.id())) throw broken();
        ReviewDraftDtos.Input source = snapshot(saved.snapshotJson());
        verifyBindings(row, source);
        // 权限在原文读取前单独核对；state 保留失效含义，不能用 current 把拒绝访问吞成 false。
        AiDtos.OriginState state = sources.state(owner, source);
        if (state == null || !Set.of(AiDtos.OriginState.CURRENT, AiDtos.OriginState.SUPERSEDED,
                AiDtos.OriginState.REVIEW_REQUIRED, AiDtos.OriginState.UNAVAILABLE).contains(state)) throw broken();
        return new Detail(entry, live != null, source, state);
    }

    public Entry create(String owner, String relationId, String key, Create request) {
        uuid(owner); uuid(relationId);
        if (request == null || !Boolean.TRUE.equals(request.confirmed())) throw invalid();
        reason(request.reason()); selection(request.source());
        // 原键只恢复元数据；来源当前性仅限制首次追加，评价权限每次都在回执读取前重新核对。
        return commands.execute(owner, key, "POST /api/v1/knowledge/relations/" + relationId + "/feedback-evidence",
                request, Entry.class, () -> sources.requireAnswerAccess(owner, request.source().answer()), () -> {
                    Relation relation = relations.requireActiveWithinCommand(owner, relationId);
                    ReviewDraftDtos.Input source = capture(owner, request.source());
                    var fixed = source.feedback();
                    String stored = snapshots.writeValueAsString(source);
                    if (stored.getBytes(StandardCharsets.UTF_8).length > MAX_SNAPSHOT_BYTES) {
                        throw new ApiException(422, "RELATION_FEEDBACK_EVIDENCE_TOO_LARGE", "所选反馈证据超出保存上限，请减少所选片段后重试。");
                    }
                    Integer decisionRevision = fixed.decisionRevision() == 0 ? null : fixed.decisionRevision();
                    String decisionChoice = fixed.decisionChoice() == null ? null : fixed.decisionChoice().name();
                    EntryRow row = new EntryRow(owner, UUID.randomUUID().toString(), relation.id(), relation.sourceId(),
                            relation.targetId(), relation.type().name(), request.reason(), source.kind().name(), fixed.feedbackId(),
                            fixed.feedbackRevision(), fixed.fragments().size(), fixed.answer().sessionId(), fixed.answer().questionId(),
                            fixed.answer().answerId(), decisionRevision, decisionChoice, Instant.now().truncatedTo(ChronoUnit.MICROS),
                            decisionRevision, decisionChoice);
                    if (mapper.insertEntry(row, stored) != 1) throw broken();
                    EntryRow recorded = mapper.entry(owner, row.id());
                    if (!row.equals(recorded)) throw broken();
                    verifyBindings(recorded, source);
                    return view(owner, recorded);
                });
    }

    private ReviewDraftDtos.Input capture(String owner, Selection selection) {
        ReviewDraftDtos.Input source;
        try {
            source = sources.input(owner, new ReviewDraftDtos.SourceSelection(selection.kind(),
                    new ReviewDraftDtos.FeedbackSelection(selection.feedbackId(), selection.feedbackRevision(), selection.fragments()), null));
        } catch (ApiException exception) {
            if ("AI_SOURCE_CHANGED".equals(exception.code())) throw sourceChanged();
            throw exception;
        }
        if (!ReviewDraftSources.valid(source) || source.kind() == null || !KINDS.contains(source.kind())) throw broken();
        var fixed = source.feedback();
        if (source.kind() != selection.kind() || !fixed.feedbackId().equals(selection.feedbackId())
                || fixed.feedbackRevision() != selection.feedbackRevision() || !fixed.answer().equals(selection.answer())
                || fixed.decisionRevision() != selection.expectedDecisionRevision()) throw sourceChanged();
        if (!fixed.fragments().equals(selection.fragments())) throw broken();
        return source;
    }

    private void verifyBindings(EntryRow row, ReviewDraftDtos.Input source) {
        var fixed = source.feedback();
        if (!row.sourceKind().equals(source.kind().name()) || !row.feedbackId().equals(fixed.feedbackId())
                || row.feedbackRevision() != fixed.feedbackRevision() || row.sourceCount() != fixed.fragments().size()
                || !answer(row).equals(fixed.answer())
                || (row.decisionRevision() == null ? 0 : row.decisionRevision()) != fixed.decisionRevision()
                || !Objects.equals(row.decisionChoice(), fixed.decisionChoice() == null ? null : fixed.decisionChoice().name())) throw broken();
    }

    private ReviewDraftDtos.Input snapshot(String value) {
        if (value == null || value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > MAX_SNAPSHOT_BYTES) throw broken();
        try {
            JsonNode root = snapshots.readTree(value);
            fields(root, INPUT_FIELDS);
            if (!root.get("kind").isTextual() || !root.get("recollection").isNull()) throw broken();
            JsonNode feedback = root.get("feedback");
            fields(feedback, FEEDBACK_INPUT_FIELDS);
            for (String name : Set.of("feedbackId", "answerCondition", "dimension", "coverage", "condition", "basis", "limitation")) {
                if (!feedback.get(name).isTextual()) throw broken();
            }
            for (String name : Set.of("feedbackRevision", "answerNumber", "decisionRevision")) if (!integer(feedback.get(name))) throw broken();
            if (!(feedback.get("decisionChoice").isNull() || feedback.get("decisionChoice").isTextual())) throw broken();
            JsonNode answer = feedback.get("answer");
            fields(answer, ANSWER_FIELDS);
            for (String name : ANSWER_FIELDS) if (!answer.get(name).isTextual()) throw broken();
            JsonNode fragments = feedback.get("fragments");
            if (!fragments.isArray() || fragments.isEmpty() || fragments.size() > 8) throw broken();
            for (JsonNode fragment : fragments) {
                fields(fragment, FRAGMENT_FIELDS);
                if (!fragment.get("field").isTextual() || !fragment.get("quote").isTextual()
                        || !integer(fragment.get("start")) || !integer(fragment.get("end"))) throw broken();
            }
            ReviewDraftDtos.Input source = snapshots.treeToValue(root, ReviewDraftDtos.Input.class);
            if (!ReviewDraftSources.valid(source) || source.kind() == null || !KINDS.contains(source.kind())) throw broken();
            return source;
        } catch (RuntimeException exception) { throw new IllegalStateException("关系反馈证据的固定快照结构无效", exception); }
    }

    private static Entry view(String owner, EntryRow row) {
        try {
            if (row == null || !owner.equals(row.ownerId()) || row.createdAt() == null) throw broken();
            uuid(row.id()); uuid(row.feedbackId()); answer(answer(row)); reason(row.reason());
            Relation relation = new Relation(row.relationId(), row.sourceId(), row.targetId(), RelationType.valueOf(row.type()));
            checkedTuple(relation);
            ReviewDraftDtos.Kind kind = ReviewDraftDtos.Kind.valueOf(row.sourceKind());
            if (!KINDS.contains(kind) || row.feedbackRevision() < 1 || row.feedbackRevision() > 100
                    || row.sourceCount() < 1 || row.sourceCount() > 8) throw broken();
            if (row.decisionRevision() == null) {
                if (row.decisionChoice() != null || row.actualDecisionRevision() != null || row.actualDecisionChoice() != null) throw broken();
            } else {
                if (row.decisionRevision() < 1 || row.decisionRevision() > 20 || row.decisionChoice() == null
                        || !row.decisionRevision().equals(row.actualDecisionRevision())
                        || !row.decisionChoice().equals(row.actualDecisionChoice())) throw broken();
                AiDtos.FeedbackChoice.valueOf(row.decisionChoice());
            }
            return new Entry(row.id(), relation.id(), relation.sourceId(), relation.targetId(), relation.type(), row.reason(),
                    kind, row.feedbackId(), row.feedbackRevision(), row.sourceCount(), row.createdAt());
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
        if (value == null || value.kind() == null || !KINDS.contains(value.kind())
                || value.feedbackRevision() == null || value.feedbackRevision() < 1 || value.feedbackRevision() > 100
                || value.expectedDecisionRevision() == null || value.expectedDecisionRevision() < 0 || value.expectedDecisionRevision() > 20
                || value.fragments() == null || value.fragments().isEmpty() || value.fragments().size() > 8) throw invalid();
        uuid(value.feedbackId()); answer(value.answer());
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
    private static void uuid(String value) {
        try { if (value == null || !UUID.fromString(value).toString().equals(value)) throw invalid(); }
        catch (IllegalArgumentException exception) { throw invalid(); }
    }
    private static ApiException invalid() { return new ApiException(400, "INVALID_REQUEST", "关系反馈证据的身份、修订、理由、片段或确认字段无效。"); }
    private static ApiException sourceChanged() { return new ApiException(409, "RELATION_FEEDBACK_SOURCE_CHANGED", "所选反馈修订、决定、确切提交或片段已变化，请保留输入后重新核对。"); }
    private static ApiException relationMissing() { return new ApiException(404, "RELATION_NOT_FOUND", "关系不存在或不可访问。"); }
    private static ApiException evidenceMissing() { return new ApiException(404, "RELATION_FEEDBACK_EVIDENCE_NOT_FOUND", "关系反馈证据不存在或不可访问。"); }
    private static IllegalStateException broken() { return new IllegalStateException("关系反馈证据与固定身份、修订或决定不一致"); }
}
