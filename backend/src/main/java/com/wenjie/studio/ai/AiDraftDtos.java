package com.wenjie.studio.ai;

public final class AiDraftDtos {
    private AiDraftDtos() {}

    public enum CloseReason { FINISHED, UNAVAILABLE, ACCESS_CHANGED, RECONNECT }
    public record Snapshot(String taskId, String attemptId, long sequence, String text) {}
    public record Delta(String taskId, String attemptId, long sequence, int offset, String text) {}
    public record Heartbeat(String taskId, String attemptId) {}
    public record Closed(String taskId, String attemptId, CloseReason reason) {}
}
