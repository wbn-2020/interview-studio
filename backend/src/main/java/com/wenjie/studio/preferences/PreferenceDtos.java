package com.wenjie.studio.preferences;

import java.time.Instant;

public final class PreferenceDtos {
    private PreferenceDtos() {}

    public enum Theme { LIGHT, DARK, SYSTEM }
    public enum Motion { SYSTEM, REDUCED, FULL }

    public record Preferences(long revision, Theme theme, int fontSize, int contentWidth, Motion motion,
                              String timeZone, boolean saveRecoveryNotice, Instant updatedAt) {}
    public record Save(Long expectedRevision, Theme theme, Integer fontSize, Integer contentWidth, Motion motion,
                       String timeZone, Boolean saveRecoveryNotice) {}
    public record UsageCounts(long notes, long concepts, long knowledgeVersions, long boards,
                              long preparationItems, long questions, long trainingSessions, long submittedAnswers,
                              long memoryCards, long attachments, long aiTasks, long materialReservedBytes) {}
    public record Usage(long notes, long concepts, long knowledgeVersions, long boards,
                        long preparationItems, long questions, long trainingSessions, long submittedAnswers,
                        long memoryCards, long attachments, long aiTasks, long materialReservedBytes,
                        long materialQuotaBytes, boolean materialsEnabled, Instant checkedAt) {}
}
