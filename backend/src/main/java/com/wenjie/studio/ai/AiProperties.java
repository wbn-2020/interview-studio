package com.wenjie.studio.ai;

import com.wenjie.studio.common.ApiException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ConfigurationProperties("studio.ai")
public final class AiProperties {
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private final AiDtos.Configuration configuration;
    private final String apiKey;
    private final URI endpoint;
    private final String fingerprint;
    private final String runtimeEpoch = UUID.randomUUID().toString();

    public AiProperties(@DefaultValue("false") boolean enabled,
                        @DefaultValue("CHAT_COMPLETIONS") String protocol,
                        @DefaultValue("") String provider, @DefaultValue("") String endpoint,
                        @DefaultValue("") String model, @DefaultValue("") String apiKey,
                        @DefaultValue("") String configVersion, @DefaultValue("") String priceVersion,
                        @DefaultValue("") String currency,
                        @DefaultValue("-1") long inputPriceMicrosPerMillion,
                        @DefaultValue("-1") long outputPriceMicrosPerMillion,
                        @DefaultValue("0") long dailyBudgetMicros,
                        @DefaultValue("1024") int maxOutputTokens,
                        @DefaultValue("60") int timeoutSeconds,
                        @DefaultValue("max_tokens") String maxTokensField,
                        @DefaultValue("false") boolean jsonMode,
                        @DefaultValue("false") boolean streaming) {
        this.apiKey = apiKey == null ? "" : apiKey;
        this.endpoint = endpoint(endpoint);
        String safeProvider = publicText(provider, 120), safeModel = publicText(model, 200);
        String safeConfig = publicText(configVersion, 80), safePrice = publicText(priceVersion, 80);
        String safeCurrency = currency != null && currency.matches("[A-Z]{3}") ? currency : "";
        String safeProtocol = protocol != null && List.of("CHAT_COMPLETIONS", "RESPONSES").contains(protocol) ? protocol : "";
        String tokenField = "RESPONSES".equals(safeProtocol) ? "max_output_tokens"
                : maxTokensField != null && List.of("max_tokens", "max_completion_tokens").contains(maxTokensField) ? maxTokensField : "";
        boolean configured = !safeProvider.isEmpty() && !safeModel.isEmpty() && this.endpoint != null
                && !safeConfig.isEmpty() && !safePrice.isEmpty() && !safeCurrency.isEmpty()
                && validKey(this.apiKey) && !safeProtocol.isEmpty() && !tokenField.isEmpty()
                && price(inputPriceMicrosPerMillion) && price(outputPriceMicrosPerMillion)
                && dailyBudgetMicros > 0 && dailyBudgetMicros <= MAX_SAFE_INTEGER
                && maxOutputTokens >= 1 && maxOutputTokens <= 16_384
                && timeoutSeconds >= 1 && timeoutSeconds <= 120;
        configuration = new AiDtos.Configuration(enabled, configured, safeProtocol, safeProvider,
                this.endpoint == null ? "" : this.endpoint.toASCIIString(), safeModel, safeConfig, safePrice,
                safeCurrency, price(inputPriceMicrosPerMillion) ? inputPriceMicrosPerMillion : -1,
                price(outputPriceMicrosPerMillion) ? outputPriceMicrosPerMillion : -1,
                dailyBudgetMicros > 0 && dailyBudgetMicros <= MAX_SAFE_INTEGER ? dailyBudgetMicros : 0,
                maxOutputTokens >= 1 && maxOutputTokens <= 16_384 ? maxOutputTokens : 0,
                timeoutSeconds >= 1 && timeoutSeconds <= 120 ? timeoutSeconds : 0, 32 * 1024, 3, tokenField, jsonMode,
                streaming ? Boolean.TRUE : null);
        // 指纹只含公开配置；轮换密钥须递增 configVersion，不能把密钥摘要暴露成公共标识。
        Map<String, Object> publicConfiguration = new HashMap<>(Map.ofEntries(Map.entry("enabled", enabled), Map.entry("configured", configured),
                        Map.entry("protocol", safeProtocol), Map.entry("provider", safeProvider), Map.entry("endpoint", configuration.endpoint()),
                        Map.entry("model", safeModel), Map.entry("configVersion", safeConfig), Map.entry("priceVersion", safePrice),
                        Map.entry("currency", safeCurrency), Map.entry("inputPrice", inputPriceMicrosPerMillion),
                        Map.entry("outputPrice", outputPriceMicrosPerMillion), Map.entry("dailyBudget", dailyBudgetMicros),
                        Map.entry("maxOutputTokens", maxOutputTokens), Map.entry("timeoutSeconds", timeoutSeconds),
                        Map.entry("maxTokensField", tokenField), Map.entry("jsonMode", jsonMode)));
        // 只有显式开启才改变指纹；默认关闭保留旧键集合及排序后的序列化内容。
        if (streaming) publicConfiguration.put("streaming", true);
        fingerprint = digest(JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build()
                .writeValueAsString(publicConfiguration));
    }

    public AiDtos.Configuration configuration() { return configuration; }
    public String fingerprint() { return fingerprint; }
    public String runtimeEpoch() { return runtimeEpoch; }

    public void requireReady() {
        if (!configuration.enabled()) throw new ApiException(409, "AI_DISABLED", "AI 功能尚未启用。");
        if (!configuration.configured()) throw new ApiException(409, "AI_NOT_CONFIGURED", "AI 服务配置尚未完成，请由服务器管理员核对。");
    }

    URI endpoint() { return endpoint; }
    String apiKey() { return apiKey; }

    @Override public String toString() { return "AiProperties[configuration=" + configuration + "]"; }

    private static boolean price(long value) { return value >= 0 && value <= MAX_SAFE_INTEGER; }

    private static String publicText(String value, int maximum) {
        if (value == null) return "";
        String result = value.strip();
        return result.isEmpty() || result.codePointCount(0, result.length()) > maximum
                || result.codePoints().anyMatch(Character::isISOControl) ? "" : result;
    }

    private static boolean validKey(String key) {
        return !key.isEmpty() && key.length() <= 8192 && key.chars().allMatch(value -> value >= 33 && value <= 126);
    }

    private static URI endpoint(String value) {
        try {
            if (value == null || value.length() > 2048) return null;
            URI uri = URI.create(value.strip());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65_535
                    || uri.getRawPath() == null || uri.getRawPath().isEmpty()) return null;
            return uri;
        } catch (IllegalArgumentException exception) { return null; }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("当前运行时不支持配置摘要"); }
    }
}
