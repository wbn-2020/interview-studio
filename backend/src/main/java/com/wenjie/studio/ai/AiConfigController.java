package com.wenjie.studio.ai;

import com.wenjie.studio.ai.AiConfigDtos.*;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.CurrentOwner;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

@RestController
@RequestMapping("/api/v1/ai/config")
public final class AiConfigController {
    private static final Set<String> CONFIG_FIELDS = Set.of("enabled", "protocol", "provider", "endpoint", "model",
            "configVersion", "priceVersion", "currency", "inputPriceMicrosPerMillion", "outputPriceMicrosPerMillion",
            "dailyBudgetMicros", "maxOutputTokens", "timeoutSeconds", "maxTokensField", "jsonMode", "streaming");
    private static final Set<String> SAVE_FIELDS = union(CONFIG_FIELDS, Set.of("expectedRevision", "apiKey"));
    private static final Set<String> TEST_FIELDS = union(CONFIG_FIELDS, Set.of("apiKey", "costAcknowledged"));
    private static final Set<String> LONG_FIELDS = Set.of("expectedRevision", "inputPriceMicrosPerMillion",
            "outputPriceMicrosPerMillion", "dailyBudgetMicros");
    private static final Set<String> INT_FIELDS = Set.of("maxOutputTokens", "timeoutSeconds");
    private static final Set<String> BOOLEAN_FIELDS = Set.of("enabled", "jsonMode", "streaming", "costAcknowledged");
    private static final Set<String> TEXT_FIELDS = Set.of("protocol", "provider", "endpoint", "model", "apiKey",
            "configVersion", "priceVersion", "currency", "maxTokensField");
    private static final String COST_WARNING = "AI connectivity test may incur provider charges.";
    private final AiConfigService service;
    private final CurrentOwner owner;
    private final JsonMapper requests;

    public AiConfigController(AiConfigService service, CurrentOwner owner) {
        this.service = service;
        this.owner = owner;
        requests = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8)
                                .maxStringLength(16_384).maxNumberLength(32).build()).build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @GetMapping
    public ResponseEntity<Status> status() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.status(owner.id()));
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Status> save(@RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        JsonNode body = body(raw);
        exact(body, SAVE_FIELDS);
        types(body, false);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.save(owner.id(), key, decode(body, SaveRequest.class)));
    }

    @PostMapping(value = "/test", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TestResult> test(@RequestBody byte[] raw) {
        JsonNode body = body(raw);
        if (!body.has("costAcknowledged") || !body.get("costAcknowledged").isBoolean()) throw invalid();
        allowed(body, TEST_FIELDS);
        types(body, true);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-AI-Test-Cost-Warning", COST_WARNING)
                .header("Warning", "299 - \"" + COST_WARNING + "\"")
                .body(service.test(owner.id(), decode(body, TestRequest.class)));
    }

    private JsonNode body(byte[] raw) {
        if (raw == null || raw.length == 0 || raw.length > 32 * 1024) throw invalid();
        try {
            JsonNode body = requests.readTree(raw);
            if (body == null || !body.isObject()) throw invalid();
            return body;
        } catch (RuntimeException exception) { throw invalid(); }
    }

    private static void exact(JsonNode body, Set<String> fields) {
        if (body.size() != fields.size() || body.properties().stream().anyMatch(entry -> !fields.contains(entry.getKey()))) {
            throw invalid();
        }
    }

    private static void allowed(JsonNode body, Set<String> fields) {
        if (body.properties().stream().anyMatch(entry -> !fields.contains(entry.getKey()))) throw invalid();
    }

    private static void types(JsonNode body, boolean optional) {
        for (var entry : body.properties()) {
            String name = entry.getKey();
            JsonNode value = entry.getValue();
            if (optional && value.isNull()) continue;
            if (LONG_FIELDS.contains(name) && (!value.isIntegralNumber() || !value.canConvertToLong())) throw invalid();
            if (INT_FIELDS.contains(name) && (!value.isIntegralNumber() || !value.canConvertToInt())) throw invalid();
            if (BOOLEAN_FIELDS.contains(name) && !value.isBoolean()) throw invalid();
            if (TEXT_FIELDS.contains(name) && !value.isTextual()
                    && !("apiKey".equals(name) && value.isNull())) throw invalid();
        }
    }

    private <T> T decode(JsonNode body, Class<T> type) {
        try { return requests.treeToValue(body, type); }
        catch (RuntimeException exception) { throw invalid(); }
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        return java.util.stream.Stream.concat(first.stream(), second.stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static ApiException invalid() {
        return new ApiException(400, "INVALID_REQUEST", "AI 配置请求字段或类型不正确。");
    }
}
