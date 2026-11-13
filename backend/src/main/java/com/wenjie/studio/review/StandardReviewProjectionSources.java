package com.wenjie.studio.review;

import com.wenjie.studio.ai.AiDtos;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.material.MaterialProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static com.wenjie.studio.review.StandardReviewDtos.*;
import static com.wenjie.studio.review.StandardReviewRules.*;

/** 只为观察选中的检查项解析来源；成长查询不能借此加载标准或知识全文。 */
@Service
@Transactional(propagation = Propagation.MANDATORY, readOnly = true, noRollbackFor = RuntimeException.class)
public class StandardReviewProjectionSources {
    private final StandardReviewService standards;
    private final StandardReviewProjectionMapper mapper;
    private final ReviewedBasisInvalidation generations;
    private final MaterialProperties materialProperties;

    public StandardReviewProjectionSources(StandardReviewService standards, StandardReviewProjectionMapper mapper,
                                            ReviewedBasisInvalidation generations, MaterialProperties materialProperties) {
        this.standards = standards;
        this.mapper = mapper;
        this.generations = generations;
        this.materialProperties = materialProperties;
    }

    public ScopedStandard read(String owner, RevisionRef reference, String checkpointId) {
        return read(owner, reference, checkpointId, new ReadContext());
    }

    public ScopedStandard read(String owner, RevisionRef reference, String checkpointId, ReadContext context) {
        uuid(owner); ref(reference); uuid(checkpointId); required(context).owner(owner);
        var stored = context.standards.computeIfAbsent(reference, value -> standards.projectionBasis(owner, value));
        StandardReviewSnapshot snapshot = stored.snapshot();
        if (!reference.id().equals(snapshot.id()) || reference.revision() != snapshot.revision()) throw integrity();
        StandardCheckpointReview checkpoint = snapshot.content().checkpoints().stream()
                .filter(value -> checkpointId.equals(value.checkpointId())).findFirst()
                .orElseThrow(() -> conflict("OBSERVATION_STANDARD_MISMATCH", "该审阅修订未包含观察所用检查项。", null));
        var ids = new HashSet<>(checkpoint.evidenceIds());
        List<ReviewEvidence> selected = snapshot.content().evidence().stream().filter(value -> ids.contains(value.id())).toList();
        if (selected.size() != ids.size()) throw integrity();

        var current = new LinkedHashMap<DirectRef, DirectStamp>();
        var sourceGenerations = context.generations;
        var recordedSources = new HashMap<DirectRef, DirectStamp>();
        snapshot.basis().directSources().forEach(stamp -> recordedSources.put(stamp.source(), stamp));
        var evidenceSources = new HashMap<String, DirectRef>();
        DirectRef questionSource = new DirectRef(DirectKind.QUESTION, snapshot.question().questionId());
        var questionKey = new QuestionKey(snapshot.question(), checkpointId);
        var questionRow = context.questions.computeIfAbsent(questionKey, value -> Optional.ofNullable(
                mapper.question(owner, value.question().questionId(), value.question().versionId(), value.checkpointId()))).orElse(null);
        current.put(questionSource, question(owner, snapshot.question(), questionRow, sourceGenerations));

        Map<String, StandardReviewProjectionRows.Knowledge> knowledge = knowledge(owner, selected, context);
        Map<String, StandardReviewProjectionRows.Material> materials = materials(owner, selected, context);
        var evidenceChecks = new ArrayList<EvidenceCheck>();
        for (ReviewEvidence evidence : selected) {
            switch (evidence.kind()) {
                case KNOWLEDGE -> {
                    var citation = evidence.knowledge();
                    var row = knowledge.get(evidence.id());
                    DirectRef source = new DirectRef(DirectKind.KNOWLEDGE, citation.itemId());
                    evidenceSources.put(evidence.id(), source);
                    DirectStamp stamp = knowledge(owner, citation.itemId(), row, sourceGenerations);
                    put(current, source, stamp);
                    boolean exists = row != null && citation.versionId().equals(row.versionId()) && stamp != null;
                    BasisState state = exists ? state(stamp) : BasisState.UNAVAILABLE;
                    Boolean matches = state == BasisState.AVAILABLE ? row.quoteMatches() : null;
                    if (Boolean.FALSE.equals(matches)) { state = BasisState.UNAVAILABLE; matches = null; }
                    DirectStamp baseline = recordedSources.get(source);
                    if (baseline == null) throw integrity();
                    var upstreamKey = new UpstreamKey(source, citation.versionId(), baseline.generation());
                    DirectChange upstream = context.upstreamChanges.computeIfAbsent(upstreamKey, value -> Optional.ofNullable(
                            generations.firstUpstreamAfter(owner, value.source().id(), value.versionId(), value.generation()))).orElse(null);
                    evidenceChecks.add(new EvidenceCheck(evidence.id(), evidence.kind(), state, matches, upstream));
                }
                case MATERIAL_EXCERPT -> {
                    var row = materials.get(evidence.id());
                    DirectStamp stamp = material(owner, evidence.material().excerptId(), row, sourceGenerations);
                    if (row != null) {
                        DirectRef source = new DirectRef(DirectKind.MATERIAL_ATTACHMENT, row.attachmentId());
                        evidenceSources.put(evidence.id(), source);
                        put(current, source, stamp);
                    }
                    BasisState state = row != null && row.chainValid() ? state(stamp) : BasisState.UNAVAILABLE;
                    Boolean matches = state == BasisState.AVAILABLE ? row.quoteMatches() : null;
                    if (Boolean.FALSE.equals(matches)) { state = BasisState.UNAVAILABLE; matches = null; }
                    evidenceChecks.add(new EvidenceCheck(evidence.id(), evidence.kind(), state, matches, null));
                }
                case EXTERNAL_QUOTE -> evidenceChecks.add(new EvidenceCheck(evidence.id(), evidence.kind(), BasisState.EXTERNAL_RECORDED, null, null));
            }
        }

        var basisChecks = new ArrayList<BasisCheck>();
        var bases = new HashMap<DirectRef, BasisCheck>();
        // 保持原快照的来源顺序，使成长摘要与原域单项读取的字段顺序一致。
        for (DirectStamp recorded : snapshot.basis().directSources()) {
            if (!current.containsKey(recorded.source())) continue;
            DirectStamp actual = current.get(recorded.source());
            var changeKey = new ChangeKey(recorded.source(), recorded.generation());
            DirectChange first = context.changes.computeIfAbsent(changeKey, value -> Optional.ofNullable(
                    generations.firstAfter(owner, value.source(), value.generation()))).orElse(null);
            long upstreamCount = context.upstreamCounts.computeIfAbsent(changeKey, value ->
                    generations.upstreamCountAfter(owner, value.source(), value.generation()));
            long generation = actual == null ? generation(owner, recorded.source(), sourceGenerations) : actual.generation();
            if (generation < recorded.generation() || upstreamCount > generation - recorded.generation()
                    || generation > recorded.generation() && first == null
                    || first != null && first.generation() != recorded.generation() + 1) throw integrity();
            BasisCheck check = new BasisCheck(recorded.source(), recorded, actual, state(actual), first, upstreamCount);
            basisChecks.add(check); bases.put(recorded.source(), check);
        }
        if (bases.size() != current.size()) throw integrity();
        var reasons = EnumSet.noneOf(EligibilityReason.class);
        if (snapshot.revision() != stored.currentRevision()) reasons.add(EligibilityReason.RECORD_REVISED);
        if (stored.currentStatus() != RecordStatus.ACTIVE) reasons.add(EligibilityReason.RECORD_WITHDRAWN);
        if (stored.materialReviewRequired()) reasons.add(EligibilityReason.DIRECT_BASIS_CHANGED);
        if (checkpoint.decision() != CheckpointDecision.SUPPORTED_WITHIN_SCOPE) reasons.add(EligibilityReason.CHECKPOINT_NOT_SUPPORTED);
        if (questionRow == null || !questionRow.checkpointPresent()) reasons.add(EligibilityReason.BASIS_UNAVAILABLE);
        addReasons(bases.get(questionSource), true, reasons);
        for (EvidenceCheck evidence : evidenceChecks) {
            if (evidence.state() == BasisState.UNAVAILABLE || evidence.state() == BasisState.ARCHIVED) reasons.add(EligibilityReason.BASIS_UNAVAILABLE);
            if (evidence.kind() != BasisKind.EXTERNAL_QUOTE) addReasons(bases.get(evidenceSources.get(evidence.evidenceId())), false, reasons);
            if (evidence.firstUnreviewedUpstreamChange() != null) reasons.add(EligibilityReason.DIRECT_BASIS_CHANGED);
        }
        CheckpointEligibility eligibility = new CheckpointEligibility(checkpointId, reasons.isEmpty(), List.copyOf(reasons));
        return new ScopedStandard(snapshot.question(), hash("reviewed-observation-standard-v1\n", snapshot),
                stored.currentRevision(), stored.currentStatus(), eligibility, List.copyOf(basisChecks),
                List.copyOf(evidenceChecks), limitations(selected));
    }

    private DirectStamp question(String owner, QuestionVersionRef question, StandardReviewProjectionRows.Question row,
                                 Map<DirectRef, Long> sourceGenerations) {
        if (row == null) return null;
        if (!owner.equals(row.ownerId()) || !question.questionId().equals(row.id()) || !question.versionId().equals(row.versionId())) throw integrity();
        hashText(row.sourceContentHash());
        // 缺标记不能退回全文加载或直接相信历史基线，用户须先显式查看／保存这份标准。
        if (row.sealedContentHash() == null && row.fingerprint() == null && row.sealedAt() == null) {
            throw new ApiException(503, "REVIEW_PROJECTION_UNAVAILABLE", "该题目版本尚未建立投影校验，请先显式查看或保存其审阅记录。");
        }
        if (!row.sourceContentHash().equals(row.sealedContentHash()) || row.fingerprint() == null || row.sealedAt() == null) throw integrity();
        hashText(row.fingerprint()); time(row.sealedAt());
        if (row.currentVersionId() == null) return null;
        DirectRef source = new DirectRef(DirectKind.QUESTION, row.id());
        return stamp(new DirectStamp(source, generation(owner, source, sourceGenerations), row.currentVersionId(), row.status(), row.fingerprint()));
    }

    private Map<String, StandardReviewProjectionRows.Knowledge> knowledge(String owner, List<ReviewEvidence> evidence, ReadContext context) {
        var requested = new LinkedHashMap<AiDtos.Citation, StandardReviewProjectionRows.KnowledgeRequest>();
        for (ReviewEvidence value : evidence) if (value.kind() == BasisKind.KNOWLEDGE) {
            var citation = value.knowledge();
            if (!context.knowledge.containsKey(citation)) requested.putIfAbsent(citation,
                    new StandardReviewProjectionRows.KnowledgeRequest(value.id(), citation.itemId(), citation.versionId(), citation.start(), citation.end(), citation.quote()));
        }
        if (!requested.isEmpty()) {
            var byId = new HashMap<String, AiDtos.Citation>();
            requested.forEach((citation, request) -> byId.put(request.evidenceId(), citation));
            var loaded = new HashMap<AiDtos.Citation, StandardReviewProjectionRows.Knowledge>();
            for (var row : mapper.knowledge(owner, List.copyOf(requested.values()))) {
                AiDtos.Citation citation = byId.get(row.evidenceId());
                if (citation == null || !owner.equals(row.ownerId()) || !citation.itemId().equals(row.id())
                        || row.versionId() != null && !citation.versionId().equals(row.versionId())
                        || loaded.put(citation, row) != null) throw integrity();
            }
            // 查询成功且所有行身份核对后才缓存缺失；异常不得被转成“没有来源”。
            requested.keySet().forEach(citation -> context.knowledge.put(citation, Optional.ofNullable(loaded.get(citation))));
        }
        var result = new HashMap<String, StandardReviewProjectionRows.Knowledge>();
        for (ReviewEvidence value : evidence) if (value.kind() == BasisKind.KNOWLEDGE) {
            var row = context.knowledge.get(value.knowledge()).orElse(null);
            if (row != null) result.put(value.id(), new StandardReviewProjectionRows.Knowledge(value.id(), row.id(), row.ownerId(),
                    row.kind(), row.status(), row.currentVersionId(), row.versionId(), row.currentFingerprint(), row.quoteMatches()));
        }
        return result;
    }

    private Map<String, StandardReviewProjectionRows.Material> materials(String owner, List<ReviewEvidence> evidence, ReadContext context) {
        var requested = new LinkedHashMap<AiDtos.MaterialCitation, StandardReviewProjectionRows.MaterialRequest>();
        var excerptIds = new HashSet<String>();
        for (ReviewEvidence value : evidence) if (value.kind() == BasisKind.MATERIAL_EXCERPT) {
            var citation = value.material(); excerptIds.add(citation.excerptId());
            if (!context.materials.containsKey(citation)) requested.putIfAbsent(citation,
                    new StandardReviewProjectionRows.MaterialRequest(value.id(), citation.excerptId(), citation.start(), citation.end(), citation.quote()));
        }
        var result = new HashMap<String, StandardReviewProjectionRows.Material>();
        if (excerptIds.isEmpty()) return result;
        if (!materialProperties.enabled()) throw new ApiException(503, "MATERIALS_DISABLED", "服务器尚未启用附件资料。");
        if (excerptIds.size() > 5) throw basisLimit("一次最多引用 5 个不同材料摘录。");
        if (!requested.isEmpty()) {
            var byId = new HashMap<String, AiDtos.MaterialCitation>();
            requested.forEach((citation, request) -> byId.put(request.evidenceId(), citation));
            var loaded = new HashMap<AiDtos.MaterialCitation, StandardReviewProjectionRows.Material>();
            for (var row : mapper.materials(owner, List.copyOf(requested.values()))) {
                AiDtos.MaterialCitation citation = byId.get(row.evidenceId());
                if (citation == null || !owner.equals(row.ownerId()) || !citation.excerptId().equals(row.excerptId())
                        || loaded.put(citation, row) != null) throw integrity();
            }
            requested.keySet().forEach(citation -> context.materials.put(citation, Optional.ofNullable(loaded.get(citation))));
        }
        for (ReviewEvidence value : evidence) if (value.kind() == BasisKind.MATERIAL_EXCERPT) {
            var row = context.materials.get(value.material()).orElse(null);
            if (row != null) result.put(value.id(), new StandardReviewProjectionRows.Material(value.id(), row.excerptId(), row.ownerId(),
                    row.attachmentId(), row.attachmentStatus(), row.attachmentFingerprint(), row.chainValid(), row.quoteMatches()));
        }
        return result;
    }

    private DirectStamp knowledge(String owner, String itemId, StandardReviewProjectionRows.Knowledge row,
                                  Map<DirectRef, Long> sourceGenerations) {
        if (row == null) return null;
        if (!owner.equals(row.ownerId()) || !itemId.equals(row.id())) throw integrity();
        if (!("NOTE".equals(row.kind()) || "CONCEPT".equals(row.kind())) || row.currentVersionId() == null || row.currentFingerprint() == null) return null;
        DirectRef source = new DirectRef(DirectKind.KNOWLEDGE, row.id());
        return stamp(new DirectStamp(source, generation(owner, source, sourceGenerations), row.currentVersionId(), row.status(), row.currentFingerprint()));
    }

    private DirectStamp material(String owner, String excerptId, StandardReviewProjectionRows.Material row,
                                 Map<DirectRef, Long> sourceGenerations) {
        if (row == null) return null;
        if (!owner.equals(row.ownerId()) || !excerptId.equals(row.excerptId())) throw integrity();
        uuid(row.attachmentId());
        if (row.attachmentStatus() == null && row.attachmentFingerprint() == null) return null;
        DirectRef source = new DirectRef(DirectKind.MATERIAL_ATTACHMENT, row.attachmentId());
        return stamp(new DirectStamp(source, generation(owner, source, sourceGenerations), null, row.attachmentStatus(), row.attachmentFingerprint()));
    }

    private long generation(String owner, DirectRef source, Map<DirectRef, Long> sourceGenerations) {
        return sourceGenerations.computeIfAbsent(source, value -> generations.generation(owner, value));
    }

    private static void put(Map<DirectRef, DirectStamp> values, DirectRef source, DirectStamp stamp) {
        if (values.containsKey(source) && !Objects.equals(values.get(source), stamp)) throw integrity();
        values.put(source, stamp);
    }

    private static void addReasons(BasisCheck check, boolean question, EnumSet<EligibilityReason> reasons) {
        if (check == null || check.current() == null || check.state() == BasisState.UNAVAILABLE) reasons.add(EligibilityReason.BASIS_UNAVAILABLE);
        else if (check.state() == BasisState.ARCHIVED) reasons.add(question ? EligibilityReason.QUESTION_ARCHIVED : EligibilityReason.BASIS_UNAVAILABLE);
        if (check != null && StandardReviewSources.basisChanged(check)) reasons.add(EligibilityReason.DIRECT_BASIS_CHANGED);
    }

    private static BasisState state(DirectStamp stamp) {
        if (stamp == null) return BasisState.UNAVAILABLE;
        if ("ARCHIVED".equals(stamp.status())) return BasisState.ARCHIVED;
        return "ACTIVE".equals(stamp.status()) || "READY".equals(stamp.status()) ? BasisState.AVAILABLE : BasisState.UNAVAILABLE;
    }

    private static List<ReviewLimitation> limitations(List<ReviewEvidence> selected) {
        var values = EnumSet.of(ReviewLimitation.USER_RECORDED_REVIEW, ReviewLimitation.REVIEWER_IDENTITY_NOT_VERIFIED,
                ReviewLimitation.TECHNICAL_SEMANTICS_NOT_VERIFIED, ReviewLimitation.DIRECT_DEPENDENCIES_ONLY);
        for (ReviewEvidence evidence : selected) {
            if (evidence.kind() == BasisKind.EXTERNAL_QUOTE) values.add(ReviewLimitation.EXTERNAL_CONTENT_NOT_FETCHED);
            if (evidence.kind() == BasisKind.MATERIAL_EXCERPT) values.add(ReviewLimitation.MATERIAL_BYTES_NOT_RECHECKED);
        }
        return List.copyOf(values);
    }

    public record ScopedStandard(QuestionVersionRef question, String standardBasisHash, long currentRevision,
        RecordStatus currentStatus, CheckpointEligibility checkpoint, List<BasisCheck> basisChecks,
        List<EvidenceCheck> evidenceChecks, List<ReviewLimitation> limitations) {}

    /** 由一次最多 50 条的投影读取创建并丢弃，不保存到 Bean，也不进入摘要或跨请求复用。 */
    public static final class ReadContext {
        private String owner;
        private final Map<RevisionRef, StandardReviewService.ProjectionBasis> standards = new HashMap<>();
        private final Map<QuestionKey, Optional<StandardReviewProjectionRows.Question>> questions = new HashMap<>();
        private final Map<DirectRef, Long> generations = new HashMap<>();
        private final Map<ChangeKey, Optional<DirectChange>> changes = new HashMap<>();
        private final Map<ChangeKey, Long> upstreamCounts = new HashMap<>();
        private final Map<UpstreamKey, Optional<DirectChange>> upstreamChanges = new HashMap<>();
        private final Map<AiDtos.Citation, Optional<StandardReviewProjectionRows.Knowledge>> knowledge = new HashMap<>();
        private final Map<AiDtos.MaterialCitation, Optional<StandardReviewProjectionRows.Material>> materials = new HashMap<>();

        private void owner(String value) {
            if (owner == null) owner = value;
            else if (!owner.equals(value)) throw integrity();
        }
    }

    private record QuestionKey(QuestionVersionRef question, String checkpointId) {}
    private record ChangeKey(DirectRef source, long generation) {}
    private record UpstreamKey(DirectRef source, String versionId, long generation) {}
}
