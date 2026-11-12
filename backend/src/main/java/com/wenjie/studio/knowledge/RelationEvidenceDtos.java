package com.wenjie.studio.knowledge;

import com.wenjie.studio.ai.AiDtos.TrainingDraftFragment;
import com.wenjie.studio.material.MaterialDtos.AiExcerpt;

import java.time.Instant;
import java.util.List;

public final class RelationEvidenceDtos {
    private RelationEvidenceDtos() {}

    public enum Kind { MATERIAL, TRAINING }
    public record TrainingSelection(String sessionId, String questionId, String answerId,
                                    List<TrainingDraftFragment> fragments) {}
    public record Selection(Kind kind, List<String> excerptIds, TrainingSelection training) {}
    public record Create(String reason, Selection source, Boolean confirmed) {}
    public record TrainingInput(String sessionId, String questionId, String answerId, int answerNumber,
                                String answerCondition, List<TrainingDraftFragment> fragments) {}
    public record Snapshot(Kind kind, List<AiExcerpt> materials, TrainingInput training) {}
    public record Entry(String id, String relationId, String sourceId, String targetId,
                        KnowledgeDtos.RelationType type, String reason, Kind sourceKind, int sourceCount,
                        Instant createdAt) {}
    public record Page(String relationId, boolean relationExists, List<Entry> items, String nextCursor) {}
    public record Detail(Entry entry, boolean relationExists, Snapshot source, boolean sourceAvailable) {}
}
