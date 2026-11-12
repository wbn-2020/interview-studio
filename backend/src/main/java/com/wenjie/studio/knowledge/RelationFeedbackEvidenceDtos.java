package com.wenjie.studio.knowledge;

import com.wenjie.studio.ai.AiDtos;
import com.wenjie.studio.ai.ReviewDraftDtos;
import com.wenjie.studio.review.ReviewDtos;

import java.time.Instant;
import java.util.List;

public final class RelationFeedbackEvidenceDtos {
    private RelationFeedbackEvidenceDtos() {}

    public record Selection(ReviewDraftDtos.Kind kind, String feedbackId, Integer feedbackRevision,
                            Integer expectedDecisionRevision, ReviewDtos.AnswerLink answer,
                            List<ReviewDraftDtos.Fragment> fragments) {}
    public record Create(String reason, Selection source, Boolean confirmed) {}
    public record Entry(String id, String relationId, String sourceId, String targetId,
                        KnowledgeDtos.RelationType type, String reason, ReviewDraftDtos.Kind sourceKind,
                        String feedbackId, int feedbackRevision, int sourceCount, Instant createdAt) {}
    public record Page(String relationId, boolean relationExists, List<Entry> items, String nextCursor) {}
    public record Detail(Entry entry, boolean relationExists, ReviewDraftDtos.Input source,
                         AiDtos.OriginState sourceState) {}
}
