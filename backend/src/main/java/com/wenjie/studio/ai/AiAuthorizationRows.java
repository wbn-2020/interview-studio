package com.wenjie.studio.ai;

import java.time.Instant;

public final class AiAuthorizationRows {
    private AiAuthorizationRows() {}

    public record EntryRow(String ownerId, String id, String purpose, String provider, String model, String protocol,
                           String configVersion, String priceVersion, String sourceTaskId, String sourceManifestHash,
                           String scopeHash, String configurationFingerprint, String runtimeEpoch, long revision,
                           String state, Instant expiresAt, Instant createdAt, Instant revokedAt) {}
}
