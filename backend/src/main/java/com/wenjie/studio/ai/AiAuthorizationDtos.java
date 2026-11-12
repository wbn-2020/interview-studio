package com.wenjie.studio.ai;

import java.time.Instant;
import java.util.List;

public final class AiAuthorizationDtos {
    private AiAuthorizationDtos() {}

    public enum State { ACTIVE, REVOKED }
    public enum EffectiveState { ACTIVE, REVOKED, EXPIRED, RECONFIRM_REQUIRED, CONFIG_CHANGED, UNAVAILABLE }
    public enum Mode { ONCE, CONTINUING }

    public record Use(String id, Long expectedRevision) {}
    public record Create(String taskId, String expectedManifestHash, Integer validDays, Boolean confirmed) {}
    public record Revoke(Long expectedRevision, Boolean confirmed) {}
    public record MatchRequest(AiDtos.Manifest manifest) {}
    public record Entry(String id, AiDtos.Purpose purpose, String provider, String model, String protocol,
                        String configVersion, String priceVersion, String sourceTaskId, String sourceManifestHash,
                        String scopeHash, long revision, State state, EffectiveState effectiveState,
                        Instant expiresAt, Instant createdAt, Instant revokedAt) {}
    public record Page(List<Entry> items, String nextCursor) {}
    public record Matches(String manifestHash, String scopeHash, List<Entry> items) {}
    public record Revocation(Entry authorization, int queuedStopped, int runningStopRequested) {}
    public record Usage(String attemptId, int attemptNumber, Mode mode, String authorizationId,
                        Long authorizationRevision, String scopeHash, EffectiveState effectiveState, Instant createdAt) {}
    public record UsagePage(List<Usage> items) {}
}
