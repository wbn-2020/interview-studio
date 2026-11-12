package com.wenjie.studio.lifecycle;

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
import static com.wenjie.studio.lifecycle.KnowledgeRevalidationDtos.*;
import static com.wenjie.studio.lifecycle.KnowledgeRevalidationRules.*;
import static com.wenjie.studio.lifecycle.PreparationKnowledgeDtos.Context;
import static com.wenjie.studio.lifecycle.PreparationKnowledgeRules.*;

@RestController
@RequestMapping(value = "/api/v1/lifecycle", produces = MediaType.APPLICATION_JSON_VALUE)
public class PreparationKnowledgeController {
    private static final Set<String> QUERY = Set.of("state", "limit", "cursor");
    private static final Set<String> FIELDS = Set.of("expectedRevision", "expectedTargetRevision",
            "expectedTargetVersionId", "choice", "reason", "confirmed");
    private final PreparationKnowledgeService service;
    private final CurrentOwner owner;
    private final JsonMapper requests;

    public PreparationKnowledgeController(PreparationKnowledgeService service, CurrentOwner owner) {
        this.service = service; this.owner = owner;
        requests = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4)
                                .maxStringLength(REQUEST_BYTES).maxNumberLength(32).build()).build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @GetMapping("/preparation/{itemId}/knowledge-impacts")
    public ResponseEntity<PreparationKnowledgeDtos.Page> preparation(@PathVariable("itemId") String itemId,
                                                                    @RequestParam MultiValueMap<String, String> query) {
        return list(itemId, Context.PREPARATION, query);
    }
    @GetMapping("/knowledge/{itemId}/preparation-impacts")
    public ResponseEntity<PreparationKnowledgeDtos.Page> knowledge(@PathVariable("itemId") String itemId,
                                                                  @RequestParam MultiValueMap<String, String> query) {
        return list(itemId, Context.KNOWLEDGE, query);
    }
    @GetMapping("/preparation-knowledge-impacts/{impactId}")
    public ResponseEntity<PreparationKnowledgeDtos.Impact> detail(@PathVariable("impactId") String impactId,
                                                                  @RequestParam MultiValueMap<String, String> query) {
        if (!query.isEmpty()) throw invalidRequest("影响详情不接受查询参数。");
        return response(service.detail(owner.id(), impactId));
    }
    @PostMapping(value = "/knowledge/{itemId}/preparation-impacts/{impactId}/resolve", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PreparationKnowledgeDtos.Impact> resolve(@PathVariable("itemId") String itemId,
            @PathVariable("impactId") String impactId, @RequestHeader("Idempotency-Key") String key,
            @RequestParam MultiValueMap<String, String> query, @RequestBody byte[] raw) {
        if (!query.isEmpty()) throw invalidRequest("影响处理不接受查询参数。");
        return response(service.resolve(owner.id(), itemId, impactId, key, decode(raw)));
    }

    private ResponseEntity<PreparationKnowledgeDtos.Page> list(String itemId, Context context, MultiValueMap<String, String> query) {
        for (var entry : query.entrySet()) {
            if (!QUERY.contains(entry.getKey()) || entry.getValue().size() != 1 || entry.getValue().getFirst() == null
                    || entry.getValue().getFirst().isEmpty()) throw invalidRequest("查询参数未知、重复或为空。");
        }
        State state;
        try { state = State.valueOf(query.getFirst("state")); }
        catch (IllegalArgumentException | NullPointerException exception) { throw invalidRequest("须指定 PENDING／ALL 状态。"); }
        String rawLimit = query.getFirst("limit");
        if (rawLimit != null && !rawLimit.matches("(?:[1-9]|1[0-9]|20)")) throw invalidRequest("每页数量须为 1 至 20。");
        int limit = rawLimit == null ? 20 : Integer.parseInt(rawLimit);
        return response(service.list(owner.id(), itemId, context, state, query.getFirst("cursor"), limit));
    }
    private Resolve decode(byte[] raw) {
        if (raw == null || raw.length == 0 || raw.length > REQUEST_BYTES) throw invalidRequest("请求须为不超过16 KiB的 JSON 对象。");
        JsonNode body;
        try { body = requests.readTree(raw); }
        catch (RuntimeException exception) { throw invalidRequest("JSON 格式无效、字段重复或嵌套超限。"); }
        if (body == null || !body.isObject() || body.size() != FIELDS.size()) throw invalidRequest("须提供完整的六个处理字段。");
        for (var field : body.properties()) {
            String name = field.getKey(); JsonNode value = field.getValue();
            if (!FIELDS.contains(name)) throw invalidRequest("处理请求包含未知字段。");
            if (name.equals("expectedRevision") || name.equals("expectedTargetRevision")) {
                if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalidRequest("预期修订须为整数。");
            } else if (name.equals("confirmed")) {
                if (!value.isBoolean() || !value.booleanValue()) throw invalidRequest("须明确确认本次处理。");
            } else if (!value.isTextual()) throw invalidRequest("标识、处理方式和理由须为字符串。");
        }
        try { return requests.treeToValue(body, Resolve.class); }
        catch (RuntimeException exception) { throw invalidRequest("处理字段、类型或枚举无效。"); }
    }
    private <T> ResponseEntity<T> response(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
