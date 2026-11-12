package com.wenjie.studio.ai;

/** 来源台账只保存确切身份；原文固定在任务清单中。 */
public final class RelationEvidenceRows {
    private RelationEvidenceRows() {}

    public record SourceRow(String ownerId, String taskId, String kind, String sessionId,
                            String questionId, String answerId) {}
    public record MaterialRow(String ownerId, String taskId, String kind, String excerptId, int ordinal) {}
}
