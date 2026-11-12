package com.wenjie.studio.review;

import com.wenjie.studio.bank.QuestionDtos.Checkpoint;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.lifecycle.KnowledgeRevalidationQuery;
import com.wenjie.studio.material.MaterialDtos.AiExcerpt;
import com.wenjie.studio.material.MaterialSources;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.wenjie.studio.review.StandardReviewDtos.*;

@Service
@Transactional(propagation = Propagation.MANDATORY, noRollbackFor = RuntimeException.class)
public class StandardReviewSources {
    private final StandardReviewSourceMapper mapper;
    private final MaterialSources materials;
    private final ReviewedBasisInvalidation generations;
    private final KnowledgeRevalidationQuery revalidation;
    private final JsonMapper json;

    public StandardReviewSources(StandardReviewSourceMapper mapper, MaterialSources materials,
                                 ReviewedBasisInvalidation generations, KnowledgeRevalidationQuery revalidation, JsonMapper json) {
        this.mapper = mapper;
        this.materials = materials;
        this.generations = generations;
        this.revalidation = revalidation;
        this.json = json;
    }

    public Inspection inspect(String owner, StandardReviewDraft draft, Instant now) {
        StandardReviewRules.uuid(owner);
        StandardReviewRules.draft(draft, now);
        if (!mapper.referenceExposure(owner, draft.referenceExposureId(), draft.question().questionId(), draft.question().versionId())) {
            throw missing();
        }
        Sources sources = read(owner, draft.question(), draft.content(), null);
        var stamps = sources.current().values().stream().sorted(Comparator
                .comparing((DirectStamp stamp) -> stamp.source().kind()).thenComparing(stamp -> stamp.source().id())).toList();
        BasisSeal basis = StandardReviewRules.basis(new BasisSeal(draft.question(), stamps));
        for (var stamp : stamps) generations.checkClock(owner, stamp.source(), now);
        var checks = stamps.stream().map(stamp -> new BasisCheck(stamp.source(), stamp, stamp, state(stamp), null, 0)).toList();
        return new Inspection(basis, sources.evidenceChecks(), checkpoints(draft.content(), sources, checks, true, RecordStatus.ACTIVE),
                limitations(draft.content()), sources.questionFacts(), sources.evidenceFacts(),
                List.of(new ValidationDate(draft.content().reviewZoneId(), StandardReviewRules.localDate(now, draft.content().reviewZoneId()))));
    }

    public StandardEligibility eligibility(String owner, StandardReviewSnapshot snapshot,
                                           long currentRevision, RecordStatus currentStatus) {
        StandardReviewRules.uuid(owner);
        StandardReviewRules.snapshot(snapshot);
        StandardReviewRules.revision(currentRevision);
        StandardReviewRules.status(currentStatus);
        Sources sources = read(owner, snapshot.question(), snapshot.content(), snapshot.basis());
        var checks = new ArrayList<BasisCheck>();
        for (DirectStamp recorded : snapshot.basis().directSources()) {
            DirectStamp current = sources.current().get(recorded.source());
            if (current == null && recorded.source().kind() == DirectKind.MATERIAL_ATTACHMENT) {
                current = attachmentStamp(owner, recorded.source().id());
            }
            DirectChange firstChange = generations.firstAfter(owner, recorded.source(), recorded.generation());
            long upstreamCount = generations.upstreamCountAfter(owner, recorded.source(), recorded.generation());
            long currentGeneration = current == null ? generations.generation(owner, recorded.source()) : current.generation();
            // 变更号与事件必须原子追加；来源缺失也不能掩盖已经断裂的账本。
            if (currentGeneration < recorded.generation() || upstreamCount > currentGeneration - recorded.generation()
                    || currentGeneration > recorded.generation() && firstChange == null
                    || firstChange != null && firstChange.generation() != recorded.generation() + 1) {
                throw StandardReviewRules.integrity();
            }
            checks.add(new BasisCheck(recorded.source(), recorded, current, state(current), firstChange, upstreamCount));
        }
        boolean revisionCurrent = snapshot.revision() == currentRevision;
        return new StandardEligibility(currentRevision, currentStatus, revisionCurrent, List.copyOf(checks), sources.evidenceChecks(),
                checkpoints(snapshot.content(), sources, checks, revisionCurrent, currentStatus), LineageCheck.PARTIAL, limitations(snapshot.content()));
    }

    public void requireQuestion(String owner, QuestionVersionRef question) {
        StandardReviewRules.uuid(owner);
        StandardReviewRules.question(question);
        var row = mapper.question(owner, question.questionId(), question.versionId());
        if (row == null) throw missing();
        if (!owner.equals(row.ownerId()) || !question.questionId().equals(row.id()) || !question.versionId().equals(row.versionId())) {
            throw StandardReviewRules.integrity();
        }
    }

    public void recordReferenceExposure(String owner, QuestionVersionRef question, String exposureId, Instant now) {
        requireQuestion(owner, question);
        StandardReviewRules.uuid(exposureId);
        StandardReviewRules.time(now);
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("标准查看曝光须在可写事务中记录");
        }
        if (mapper.insertReferenceExposure(owner, exposureId, question.questionId(), question.versionId(), now) != 1) {
            throw StandardReviewRules.integrity();
        }
    }

    private Sources read(String owner, QuestionVersionRef question, StandardReviewContent content, BasisSeal recordedBasis) {
        boolean strict = recordedBasis == null;
        var recorded = new HashMap<DirectRef, DirectStamp>();
        if (recordedBasis != null) recordedBasis.directSources().forEach(stamp -> recorded.put(stamp.source(), stamp));
        var row = mapper.question(owner, question.questionId(), question.versionId());
        if (row == null && strict) throw missing();
        var current = new LinkedHashMap<DirectRef, DirectStamp>();
        List<Checkpoint> actualCheckpoints = row == null ? List.of() : readCheckpoints(row.checkpointsJson());
        QuestionFacts questionFacts = null;
        if (row != null) {
            if (!owner.equals(row.ownerId()) || !question.questionId().equals(row.id()) || !question.versionId().equals(row.versionId())) {
                throw StandardReviewRules.integrity();
            }
            DirectRef source = new DirectRef(DirectKind.QUESTION, row.id());
            String fingerprint = StandardReviewRules.hash("review-question-source-v1\n",
                    new QuestionFingerprint(row.versionId(), row.promptJson(), row.referenceAnswer(), actualCheckpoints));
            DirectStamp stamp = row.currentVersionId() == null ? null : StandardReviewRules.stamp(new DirectStamp(source,
                    generations.generation(owner, source), row.currentVersionId(), row.status(), fingerprint));
            if (stamp != null) current.put(source, stamp);
            if (strict) active(state(stamp));
            List<CheckpointFact> checkpointFacts = actualCheckpoints.stream()
                    .map(checkpoint -> new CheckpointFact(checkpoint.id(), digest(checkpoint.text()))).toList();
            questionFacts = new QuestionFacts(question, row.currentVersionId(), row.status(), fingerprint,
                    digest(row.referenceAnswer()), checkpointFacts);
        }
        if (strict) checkpointOrder(content.checkpoints(), actualCheckpoints);

        Map<String, AiExcerpt> excerpts = materialExcerpts(owner, content.evidence(), strict);
        var evidenceChecks = new ArrayList<EvidenceCheck>();
        var evidenceFacts = new ArrayList<EvidenceFacts>();
        var evidenceSources = new HashMap<String, DirectRef>();
        for (ReviewEvidence evidence : content.evidence()) {
            switch (evidence.kind()) {
                case KNOWLEDGE -> {
                    var citation = evidence.knowledge();
                    var knowledge = mapper.knowledge(owner, citation.itemId(), citation.versionId());
                    DirectRef source = new DirectRef(DirectKind.KNOWLEDGE, citation.itemId());
                    evidenceSources.put(evidence.id(), source);
                    DirectStamp stamp = knowledgeStamp(owner, knowledge);
                    if (stamp != null) current.put(source, stamp);
                    boolean exists = knowledge != null && citation.versionId().equals(knowledge.versionId())
                            && knowledge.body() != null && stamp != null;
                    if (!exists && strict) throw missing();
                    if (exists && strict) revalidation.requireClear(owner, citation.itemId(), citation.versionId());
                    BasisState sourceState = exists ? state(stamp) : BasisState.UNAVAILABLE;
                    Boolean matches = sourceState == BasisState.AVAILABLE
                            ? quoteMatches(knowledge.body(), citation.start(), citation.end(), citation.quote()) : null;
                    if (strict) { active(sourceState); if (!Boolean.TRUE.equals(matches)) throw StandardReviewRules.citationInvalid(); }
                    else if (Boolean.FALSE.equals(matches)) { sourceState = BasisState.UNAVAILABLE; matches = null; }
                    DirectStamp baseline = recorded.get(source);
                    if (!strict && baseline == null) throw StandardReviewRules.integrity();
                    // 同一知识可被不同检查项引用不同版本，采用影响必须绑定确切证据而非整个对象。
                    DirectChange upstream = strict ? null : generations.firstUpstreamAfter(owner, citation.itemId(),
                            citation.versionId(), baseline.generation());
                    evidenceChecks.add(new EvidenceCheck(evidence.id(), evidence.kind(), sourceState, matches, upstream));
                    KnowledgeFacts facts = !exists ? null : new KnowledgeFacts(citation.itemId(), citation.versionId(),
                            knowledge.kind(), digest(knowledge.body()), knowledge.currentVersionId(), digest(knowledge.currentBody()),
                            citation.versionId().equals(knowledge.currentVersionId()), knowledge.body().equals(knowledge.currentBody()));
                    evidenceFacts.add(new EvidenceFacts(evidence.id(), evidence.kind(), source, stamp, sourceState, matches, facts, null, null));
                }
                case MATERIAL_EXCERPT -> {
                    var citation = evidence.material();
                    AiExcerpt excerpt = excerpts.get(citation.excerptId());
                    DirectRef source = excerpt == null ? null : new DirectRef(DirectKind.MATERIAL_ATTACHMENT, excerpt.attachmentId());
                    DirectStamp stamp = excerpt == null ? null : attachmentStamp(owner, excerpt.attachmentId());
                    if (stamp != null) current.put(source, stamp);
                    if (source != null) evidenceSources.put(evidence.id(), source);
                    BasisState sourceState = excerpt == null ? BasisState.UNAVAILABLE : state(stamp);
                    Boolean matches = sourceState == BasisState.AVAILABLE
                            ? quoteMatches(excerpt.text(), citation.start(), citation.end(), citation.quote()) : null;
                    if (strict) { active(sourceState); if (!Boolean.TRUE.equals(matches)) throw StandardReviewRules.citationInvalid(); }
                    else if (Boolean.FALSE.equals(matches)) { sourceState = BasisState.UNAVAILABLE; matches = null; }
                    evidenceChecks.add(new EvidenceCheck(evidence.id(), evidence.kind(), sourceState, matches, null));
                    MaterialFacts facts = excerpt == null ? null : new MaterialFacts(excerpt.excerptId(), excerpt.attachmentId(),
                            excerpt.sha256(), excerpt.origin(), excerpt.runId(), excerpt.segmentId(), digest(excerpt.locator()),
                            digest(excerpt.text()), excerpt.startOffset(), excerpt.endOffset());
                    evidenceFacts.add(new EvidenceFacts(evidence.id(), evidence.kind(), source, stamp, sourceState, matches, null, facts, null));
                }
                case EXTERNAL_QUOTE -> {
                    evidenceChecks.add(new EvidenceCheck(evidence.id(), evidence.kind(), BasisState.EXTERNAL_RECORDED, null, null));
                    evidenceFacts.add(new EvidenceFacts(evidence.id(), evidence.kind(), null, null, BasisState.EXTERNAL_RECORDED,
                            null, null, null, new ExternalFacts(StandardReviewRules.hash("review-external-record-v1\n", evidence.external()))));
                }
            }
        }
        return new Sources(questionFacts, List.copyOf(actualCheckpoints), current, List.copyOf(evidenceChecks),
                List.copyOf(evidenceFacts), evidenceSources);
    }

    private Map<String, AiExcerpt> materialExcerpts(String owner, List<ReviewEvidence> evidence, boolean strict) {
        List<String> ids = evidence.stream().filter(item -> item.kind() == BasisKind.MATERIAL_EXCERPT)
                .map(item -> item.material().excerptId()).distinct().toList();
        if (ids.size() > 5) throw StandardReviewRules.basisLimit("一次最多引用 5 个不同材料摘录。");
        var excerpts = new HashMap<String, AiExcerpt>();
        if (ids.isEmpty()) return excerpts;
        if (strict) {
            try { for (var excerpt : materials.read(owner, ids)) excerpts.put(excerpt.excerptId(), excerpt); }
            catch (ApiException exception) { if (missingMaterial(exception)) throw missing(); throw exception; }
        } else {
            // 历史缺失只影响使用该摘录的检查项；停用与读取故障仍向外传播。
            for (String id : ids) {
                try { excerpts.put(id, materials.read(owner, List.of(id)).getFirst()); }
                catch (ApiException exception) { if (!missingMaterial(exception)) throw exception; }
            }
        }
        return excerpts;
    }

    private DirectStamp knowledgeStamp(String owner, StandardReviewSourceRows.Knowledge row) {
        if (row == null) return null;
        if (!owner.equals(row.ownerId())) throw StandardReviewRules.integrity();
        if (!("NOTE".equals(row.kind()) || "CONCEPT".equals(row.kind())) || row.currentVersionId() == null || row.currentBody() == null) return null;
        DirectRef source = new DirectRef(DirectKind.KNOWLEDGE, row.id());
        // 正文摘要与变更号承载语义基线；仅标题发布的新版本号属于展示事实。
        return StandardReviewRules.stamp(new DirectStamp(source, generations.generation(owner, source),
                row.currentVersionId(), row.status(), digest(row.currentBody())));
    }

    private DirectStamp attachmentStamp(String owner, String id) {
        var row = mapper.attachment(owner, id);
        if (row == null) return null;
        if (!owner.equals(row.ownerId()) || !id.equals(row.id())) throw StandardReviewRules.integrity();
        DirectRef source = new DirectRef(DirectKind.MATERIAL_ATTACHMENT, id);
        return StandardReviewRules.stamp(new DirectStamp(source, generations.generation(owner, source), null, row.status(), row.sha256()));
    }

    private List<CheckpointEligibility> checkpoints(StandardReviewContent content, Sources sources, List<BasisCheck> basisChecks,
                                                     boolean revisionCurrent, RecordStatus currentStatus) {
        Map<DirectRef, BasisCheck> bases = new HashMap<>();
        for (BasisCheck check : basisChecks) bases.put(check.source(), check);
        Map<String, EvidenceCheck> evidence = new HashMap<>();
        for (EvidenceCheck check : sources.evidenceChecks()) evidence.put(check.evidenceId(), check);
        var actualIds = new HashSet<String>();
        sources.checkpoints().forEach(checkpoint -> actualIds.add(checkpoint.id()));
        BasisCheck question = basisChecks.stream().filter(check -> check.source().kind() == DirectKind.QUESTION).findFirst().orElse(null);
        var results = new ArrayList<CheckpointEligibility>();
        for (StandardCheckpointReview checkpoint : content.checkpoints()) {
            var reasons = EnumSet.noneOf(EligibilityReason.class);
            if (!revisionCurrent) reasons.add(EligibilityReason.RECORD_REVISED);
            if (currentStatus != RecordStatus.ACTIVE) reasons.add(EligibilityReason.RECORD_WITHDRAWN);
            if (checkpoint.decision() != CheckpointDecision.SUPPORTED_WITHIN_SCOPE) reasons.add(EligibilityReason.CHECKPOINT_NOT_SUPPORTED);
            if (!actualIds.contains(checkpoint.checkpointId())) reasons.add(EligibilityReason.BASIS_UNAVAILABLE);
            addBasisReasons(question, true, reasons);
            for (String id : checkpoint.evidenceIds()) {
                EvidenceCheck check = evidence.get(id);
                if (check == null || check.state() == BasisState.UNAVAILABLE || check.state() == BasisState.ARCHIVED
                        || Boolean.FALSE.equals(check.quoteMatches())) reasons.add(EligibilityReason.BASIS_UNAVAILABLE);
                if (check != null && check.kind() != BasisKind.EXTERNAL_QUOTE) {
                    addBasisReasons(bases.get(sources.evidenceSources().get(id)), false, reasons);
                    if (check.firstUnreviewedUpstreamChange() != null) reasons.add(EligibilityReason.DIRECT_BASIS_CHANGED);
                }
            }
            results.add(new CheckpointEligibility(checkpoint.checkpointId(), reasons.isEmpty(), List.copyOf(reasons)));
        }
        return List.copyOf(results);
    }

    private static void addBasisReasons(BasisCheck check, boolean question, EnumSet<EligibilityReason> reasons) {
        if (check == null || check.current() == null || check.state() == BasisState.UNAVAILABLE) {
            reasons.add(EligibilityReason.BASIS_UNAVAILABLE);
        } else if (check.state() == BasisState.ARCHIVED) {
            reasons.add(question ? EligibilityReason.QUESTION_ARCHIVED : EligibilityReason.BASIS_UNAVAILABLE);
        }
        if (check != null && basisChanged(check)) {
            reasons.add(EligibilityReason.DIRECT_BASIS_CHANGED);
        }
    }

    static boolean basisChanged(BasisCheck check) {
        if (check.firstUnreviewedChange() != null && check.firstUnreviewedChange().kind() != DirectChangeKind.KNOWLEDGE_UPSTREAM_CHANGED) return true;
        DirectStamp recorded = check.recorded(), current = check.current();
        // 上游事件由每条证据单独判断；其余事件即使正文改回也会留下未被扣除的变更号。
        return current != null && (current.generation() - recorded.generation() != check.upstreamChangeCount()
                || !recorded.status().equals(current.status()) || !recorded.fingerprint().equals(current.fingerprint())
                || recorded.source().kind() != DirectKind.KNOWLEDGE && !Objects.equals(recorded.currentVersionId(), current.currentVersionId()));
    }

    static boolean sameBasis(DirectStamp recorded, DirectStamp current) {
        return recorded != null && current != null && recorded.source().equals(current.source())
                && recorded.generation() == current.generation() && recorded.status().equals(current.status())
                && recorded.fingerprint().equals(current.fingerprint())
                && (recorded.source().kind() == DirectKind.KNOWLEDGE || Objects.equals(recorded.currentVersionId(), current.currentVersionId()));
    }

    private List<Checkpoint> readCheckpoints(String raw) {
        List<Checkpoint> checkpoints = List.copyOf(Arrays.asList(json.readValue(raw, Checkpoint[].class)));
        var ids = new HashSet<String>();
        if (checkpoints.size() > 30) throw StandardReviewRules.integrity();
        for (Checkpoint checkpoint : checkpoints) {
            if (checkpoint == null || !ids.add(checkpoint.id()) || checkpoint.text() == null) throw StandardReviewRules.integrity();
            StandardReviewRules.uuid(checkpoint.id());
        }
        return checkpoints;
    }

    private static void checkpointOrder(List<StandardCheckpointReview> reviews, List<Checkpoint> checkpoints) {
        var positions = new HashMap<String, Integer>();
        for (int i = 0; i < checkpoints.size(); i++) positions.put(checkpoints.get(i).id(), i);
        int previous = -1;
        for (StandardCheckpointReview review : reviews) {
            Integer position = positions.get(review.checkpointId());
            if (position == null || position <= previous) throw StandardReviewRules.invalid("受审检查项须按确切题目版本中的原顺序选择。");
            previous = position;
        }
    }

    private static BasisState state(DirectStamp stamp) {
        if (stamp == null) return BasisState.UNAVAILABLE;
        if ("ARCHIVED".equals(stamp.status())) return BasisState.ARCHIVED;
        return "ACTIVE".equals(stamp.status()) || "READY".equals(stamp.status()) ? BasisState.AVAILABLE : BasisState.UNAVAILABLE;
    }

    private static void active(BasisState state) {
        if (state == BasisState.UNAVAILABLE) throw missing();
        if (state != BasisState.AVAILABLE) throw new ApiException(409, "STANDARD_REVIEW_NOT_ELIGIBLE", "新审阅的题目与内部依据须处于可用状态。");
    }

    private static boolean quoteMatches(String original, int start, int end, String quote) {
        return original != null && quote != null && start >= 0 && end > start && end <= original.length()
                && !StandardReviewRules.splitSurrogate(original, start) && !StandardReviewRules.splitSurrogate(original, end)
                && quote.equals(original.substring(start, end));
    }

    private static boolean missingMaterial(ApiException exception) {
        return exception.status() == 404 && "MATERIAL_NOT_FOUND".equals(exception.code());
    }

    private static String digest(String value) {
        return StandardReviewRules.sha256(StandardReviewRules.required(value).getBytes(StandardCharsets.UTF_8));
    }

    private static List<ReviewLimitation> limitations(StandardReviewContent content) {
        var values = EnumSet.of(ReviewLimitation.USER_RECORDED_REVIEW, ReviewLimitation.REVIEWER_IDENTITY_NOT_VERIFIED,
                ReviewLimitation.TECHNICAL_SEMANTICS_NOT_VERIFIED, ReviewLimitation.DIRECT_DEPENDENCIES_ONLY);
        for (ReviewEvidence evidence : content.evidence()) {
            if (evidence.kind() == BasisKind.EXTERNAL_QUOTE) values.add(ReviewLimitation.EXTERNAL_CONTENT_NOT_FETCHED);
            if (evidence.kind() == BasisKind.MATERIAL_EXCERPT) values.add(ReviewLimitation.MATERIAL_BYTES_NOT_RECHECKED);
        }
        return List.copyOf(values);
    }

    private static ApiException missing() {
        return new ApiException(404, "REVIEW_SOURCE_NOT_FOUND", "确切题目、参考曝光或依据来源不存在或不可访问。");
    }

    public record Inspection(BasisSeal basis, List<EvidenceCheck> evidenceChecks, List<CheckpointEligibility> checkpoints,
                             List<ReviewLimitation> limitations, QuestionFacts questionFacts, List<EvidenceFacts> evidenceFacts,
                             List<ValidationDate> validationDates) {}
    public record ValidationDate(String zoneId, LocalDate localDate) {}
    public record CheckpointFact(String checkpointId, String textFingerprint) {}
    public record QuestionFacts(QuestionVersionRef question, String currentVersionId, String status, String contentFingerprint,
                                String referenceFingerprint, List<CheckpointFact> checkpoints) {}
    public record EvidenceFacts(String evidenceId, BasisKind kind, DirectRef source, DirectStamp current, BasisState state,
                                Boolean quoteMatches, KnowledgeFacts knowledge, MaterialFacts material, ExternalFacts external) {}
    public record KnowledgeFacts(String itemId, String versionId, String kind, String versionFingerprint, String currentVersionId,
                                 String currentFingerprint, boolean versionCurrent, boolean currentBodyMatches) {}
    public record MaterialFacts(String excerptId, String attachmentId, String sha256, String origin, String runId, String segmentId,
                                String locatorFingerprint, String excerptFingerprint, Integer startOffset, Integer endOffset) {}
    public record ExternalFacts(String recordedFingerprint) {}
    private record QuestionFingerprint(String versionId, String promptJson, String referenceAnswer, List<Checkpoint> checkpoints) {}
    private record Sources(QuestionFacts questionFacts, List<Checkpoint> checkpoints, Map<DirectRef, DirectStamp> current,
                           List<EvidenceCheck> evidenceChecks, List<EvidenceFacts> evidenceFacts, Map<String, DirectRef> evidenceSources) {}
}
