package com.wenjie.studio.ai;

import java.time.LocalDateTime;

public final class AiConfigRows {
    private AiConfigRows() { }

    public record RuntimeConfig(String ownerId, long revision, boolean enabled, String protocol, String provider,
                                String endpoint, String model, byte[] apiKeyCiphertext, String configVersion,
                                String priceVersion, String currency, long inputPriceMicrosPerMillion,
                                long outputPriceMicrosPerMillion, long dailyBudgetMicros, int maxOutputTokens,
                                int timeoutSeconds, String maxTokensField, boolean jsonMode, boolean streaming,
                                LocalDateTime createdAt, LocalDateTime updatedAt) { }
}
