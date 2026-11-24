package com.wenjie.studio.ai;

/** Web 运行时配置契约；响应不携带密钥，测试请求只允许固定连通性字段。 */
public final class AiConfigDtos {
    private AiConfigDtos() { }

    public record Status(long revision, String source, boolean canEdit, boolean enabled, boolean configured,
                         String protocol, String provider, String endpoint, String model, String configVersion,
                         String priceVersion, String currency, long inputPriceMicrosPerMillion,
                         long outputPriceMicrosPerMillion, long dailyBudgetMicros, int maxOutputTokens,
                         int timeoutSeconds, String maxTokensField, boolean jsonMode, boolean streaming,
                         boolean apiKeyConfigured) { }

    public record SaveRequest(Long expectedRevision, Boolean enabled, String protocol, String provider, String endpoint,
                              String model, String apiKey, String configVersion, String priceVersion, String currency,
                              Long inputPriceMicrosPerMillion, Long outputPriceMicrosPerMillion, Long dailyBudgetMicros,
                              Integer maxOutputTokens, Integer timeoutSeconds, String maxTokensField,
                              Boolean jsonMode, Boolean streaming) {
        @Override public String toString() { return "SaveRequest[apiKey=<hidden>,expectedRevision=" + expectedRevision + "]"; }
    }

    public record TestRequest(Boolean enabled, String protocol, String provider, String endpoint, String model,
                              String apiKey, String configVersion, String priceVersion, String currency,
                              Long inputPriceMicrosPerMillion, Long outputPriceMicrosPerMillion, Long dailyBudgetMicros,
                              Integer maxOutputTokens, Integer timeoutSeconds, String maxTokensField,
                              Boolean jsonMode, Boolean streaming, Boolean costAcknowledged) {
        @Override public String toString() { return "TestRequest[apiKey=<hidden>,costAcknowledged=" + costAcknowledged + "]"; }
    }

    public record TestResult(boolean ok, int status, long latencyMs, String errorCode) { }
}
