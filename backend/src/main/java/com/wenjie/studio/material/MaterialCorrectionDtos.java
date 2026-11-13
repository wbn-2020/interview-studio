package com.wenjie.studio.material;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

public final class MaterialCorrectionDtos {
    private MaterialCorrectionDtos() {}

    public record Create(Long expectedRevision, String replacementExcerptId, Long expectedReplacementRevision,
                         String reason, Boolean confirmed) {}
    public record Statement(String id, String excerptId, String replacementExcerptId, long sourceRevision,
                            long replacementRevision, String reason, Instant createdAt) {}
    public record State(String excerptId, String attachmentId, long lifecycleRevision,
                        @JsonInclude(JsonInclude.Include.ALWAYS) Statement correction) {}
}
