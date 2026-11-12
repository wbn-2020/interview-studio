package com.wenjie.studio.search;

public final class UnifiedSearchAiRows {
    private UnifiedSearchAiRows() { }

    public record Report(String ownerId, String taskId, String attemptId, String sessionId,
                         String questionId, String answerId, Long reportBytes) { }
}
