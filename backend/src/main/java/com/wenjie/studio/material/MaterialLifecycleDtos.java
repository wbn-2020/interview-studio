package com.wenjie.studio.material;

import java.time.Instant;
import java.util.List;

public final class MaterialLifecycleDtos {
    private MaterialLifecycleDtos() {}

    public enum Status { ACTIVE, ARCHIVED }
    public enum AiUse { ALLOWED, REVOKED }
    public enum Action { ARCHIVE, RESTORE, REVOKE_AI, ALLOW_AI }
    public enum StatusFilter { ACTIVE, ARCHIVED, ALL }
    public enum AiUseFilter { ALLOWED, REVOKED, ALL }

    public record State(String excerptId, String attachmentId, Status status, AiUse aiUse, long revision,
                        Instant createdAt, Instant updatedAt, boolean hasReferences) {}
    public record Change(Long expectedRevision, Action action, String reason, Boolean confirmed) {}
    public record Event(String id, String excerptId, Action action, Status previousStatus, AiUse previousAiUse,
                        long previousRevision, Status status, AiUse aiUse, long revision, String reason,
                        Instant createdAt) {}
    public record HistoryPage(String excerptId, List<Event> items, String nextCursor) {}
    public record Check(String excerptId, Long revision) {}
}
