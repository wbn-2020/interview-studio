package com.wenjie.studio.material;

import com.wenjie.studio.common.CurrentOwner;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

import static com.wenjie.studio.material.MaterialDependencyImpactDtos.*;
import static com.wenjie.studio.material.MaterialGuards.invalid;

@RestController
@RequestMapping(value = "/api/v1/lifecycle", produces = MediaType.APPLICATION_JSON_VALUE)
public class MaterialDependencyImpactController {
    private static final int REQUEST_BYTES = 16_384;
    private static final Set<String> QUERY = Set.of("state", "cursor", "limit");
    private static final Set<String> FIELDS = Set.of("expectedRevision", "expectedTargetVersionId", "expectedTargetRevision", "choice", "reason", "confirmed");
    private final MaterialDependencyImpactService service;
    private final CurrentOwner owner;
    private final JsonMapper json;

    public MaterialDependencyImpactController(MaterialDependencyImpactService service, CurrentOwner owner) {
        this.service = service; this.owner = owner;
        json = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4)
                                .maxStringLength(REQUEST_BYTES).maxNumberLength(32).build()).build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @GetMapping("/material-excerpts/{excerptId}/dependency-impacts")
    public ResponseEntity<Page> list(@PathVariable("excerptId") String excerptId, @RequestParam MultiValueMap<String, String> query) {
        for (var field : query.entrySet()) {
            if (!QUERY.contains(field.getKey()) || field.getValue().size() != 1
                    || field.getValue().getFirst() == null || field.getValue().getFirst().isEmpty()) throw invalid("查询参数未知、重复或为空。");
        }
        State state;
        try { state = State.valueOf(query.getFirst("state")); }
        catch (IllegalArgumentException | NullPointerException exception) { throw invalid("须指定 PENDING／ALL 状态。"); }
        String limit = query.getFirst("limit");
        if (limit != null && !limit.matches("(?:[1-9]|1[0-9]|20)")) throw invalid("每页数量须为 1–20。");
        return response(service.list(owner.id(), excerptId, state, query.getFirst("cursor"), limit == null ? 20 : Integer.parseInt(limit)));
    }

    @GetMapping("/material-dependency-impacts/{impactId}")
    public ResponseEntity<Impact> detail(@PathVariable("impactId") String impactId, @RequestParam MultiValueMap<String, String> query) {
        noQuery(query);
        return response(service.detail(owner.id(), impactId));
    }

    @PostMapping(value = "/material-dependency-impacts/{impactId}/resolve", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Impact> resolve(@PathVariable("impactId") String impactId, @RequestHeader("Idempotency-Key") String key,
                                        @RequestParam MultiValueMap<String, String> query, @RequestBody byte[] raw) {
        noQuery(query);
        return response(service.resolve(owner.id(), impactId, key, decode(raw)));
    }

    private Resolve decode(byte[] raw) {
        if (raw == null || raw.length == 0 || raw.length > REQUEST_BYTES) throw invalid("请求须为不超过 16 KiB 的 JSON 对象。");
        JsonNode body;
        try { body = json.readTree(raw); }
        catch (RuntimeException exception) { throw invalid("JSON 格式无效、字段重复或嵌套超限。"); }
        if (body == null || !body.isObject() || body.size() != FIELDS.size()) throw invalid("须提供完整的六个处理字段。");
        for (var field : body.properties()) {
            String name = field.getKey(); JsonNode value = field.getValue();
            if (!FIELDS.contains(name)) throw invalid("请求包含未知字段。");
            switch (name) {
                case "expectedRevision" -> { if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalid("预期修订须为整数。"); }
                case "expectedTargetRevision" -> {
                    if (!value.isNull() && (!value.isIntegralNumber() || !value.canConvertToLong())) throw invalid("目标修订须为整数或 null。");
                }
                case "expectedTargetVersionId" -> { if (!value.isNull() && !value.isTextual()) throw invalid("目标版本须为字符串或 null。"); }
                case "confirmed" -> { if (!value.isBoolean() || !value.booleanValue()) throw invalid("须明确确认本次处理。"); }
                default -> { if (!value.isTextual()) throw invalid("处理方式和理由须为字符串。"); }
            }
        }
        try { return json.treeToValue(body, Resolve.class); }
        catch (RuntimeException exception) { throw invalid("处理字段、类型或枚举无效。"); }
    }
    private static void noQuery(MultiValueMap<String, String> query) { if (!query.isEmpty()) throw invalid("详情和处理不接受查询参数。"); }
    private static <T> ResponseEntity<T> response(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
