package com.wenjie.studio.lifecycle;

import java.util.List;

public final class KnowledgeReferencesDtos {
    private KnowledgeReferencesDtos() {}

    public enum Kind {
        RELATIONS, CONCEPT_NOTES, MAIN_NOTES, BODY_LINKS, BOARD_SNAPSHOTS,
        PREPARATION_VERSIONS, QUESTION_VERSIONS, MEMORY_VERSIONS, MEMORY_CONCEPTS, RELATION_SUPPLEMENTS,
        AI_KNOWLEDGE_ADOPTIONS, AI_QUESTION_ADOPTIONS, AI_RELATION_INPUTS, AI_CARD_INPUTS, AI_SOURCE_QA_INPUTS,
        STANDARD_REVIEW_EVIDENCE, INTERVIEW_MATERIALS,
        TODAY_PLACEMENTS, AI_KNOWLEDGE_INPUTS, AI_QUESTION_INPUTS,
        TRAINING_QUESTION_SNAPSHOTS, TRAINING_DRAFT_DIAGRAMS, TRAINING_ANSWER_DIAGRAMS, MEMORY_ATTEMPT_SOURCES,
        TODAY_PLACEMENT_HISTORY, MEMORY_REVIEW_DEPENDENCIES, MEMORY_REVIEW_OBSERVATIONS,
        MEMORY_IMPACT_DEPENDENCIES, MEMORY_IMPACT_PREVIOUS, MEMORY_IMPACT_CURRENT
    }

    public enum TargetKind {
        KNOWLEDGE, BOARD, PREPARATION, QUESTION, MEMORY_CARD, RELATION, AI_TASK, STANDARD_REVIEW, INTERVIEW_EVENT,
        TODAY_PLACEMENT, TRAINING_QUESTION, TRAINING_ANSWER, MEMORY_ATTEMPT, MEMORY_SOURCE_HISTORY
    }

    public record Source(String id, String kind, String status, long revision, String currentVersionId,
                         String versionId, int versionNumber, String title, boolean selectedVersionCurrent) {}

    public record Entry(String referenceId, long referenceRevision, TargetKind targetKind, String targetId,
                        String targetVersionId, Integer targetVersionNumber, Long targetRevision, String title,
                        String status, boolean targetCurrent, String anchorItemId, String otherItemId,
                        String anchorVersionId) {}

    public record Page(Kind kind, boolean versionFilterApplied, String coverage,
                       Source source, List<Entry> items, String nextCursor) {}
}
