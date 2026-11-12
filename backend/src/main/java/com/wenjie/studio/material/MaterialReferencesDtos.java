package com.wenjie.studio.material;

import java.time.Instant;
import java.util.List;

public final class MaterialReferencesDtos {
    private MaterialReferencesDtos() {}

    public enum Kind {
        PREPARATION_VERSIONS, AI_KNOWLEDGE_INPUTS, AI_QUESTION_INPUTS, AI_MATERIAL_QA_INPUTS,
        AI_FACT_INPUTS, AI_RELATION_INPUTS, AI_SOURCE_QA_INPUTS, RELATION_EVIDENCE, STANDARD_REVIEW_EVIDENCE
    }
    public enum TargetKind { PREPARATION, AI_TASK, RELATION, STANDARD_REVIEW }
    public record Source(String excerptId, String attachmentId, String origin, String runId,
                         String segmentId, Instant createdAt) {}
    public record Entry(String referenceId, long referenceRevision, TargetKind targetKind, String targetId,
                        String targetVersionId, Integer targetVersionNumber, Long targetRevision, String status,
                        boolean targetCurrent, String anchorItemId, String otherItemId, String anchorVersionId) {}
    public record Page(Kind kind, String coverage, Source source, List<Entry> items, String nextCursor) {}
}
