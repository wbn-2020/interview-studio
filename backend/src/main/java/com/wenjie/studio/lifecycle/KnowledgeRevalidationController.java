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

@RestController
@RequestMapping(value = "/api/v1/lifecycle/knowledge", produces = MediaType.APPLICATION_JSON_VALUE)
public class KnowledgeRevalidationController {
    private static final Set<String> QUERY_FIELDS = Set.of("direction", "state", "limit", "cursor");
    private static final Set<String> REQUEST_FIELDS = Set.of("expectedRevision", "expectedTargetRevision",
            "expectedTargetVersionId", "choice", "reason", "confirmed");
    private final KnowledgeRevalidationService service;
    private final CurrentOwner owner;
    private final JsonMapper requests;

    public KnowledgeRevalidationController(KnowledgeRevalidationService service, CurrentOwner owner) {
        this.service = service; this.owner = owner;
        requests = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4)
                                .maxStringLength(REQUEST_BYTES).maxNumberLength(32).build()).build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @GetMapping("/{itemId}/revalidation-impacts")
    public ResponseEntity<Page> list(@PathVariable("itemId") String itemId, @RequestParam MultiValueMap<String, String> query) {
        for (var entry : query.entrySet()) {
            if (!QUERY_FIELDS.contains(entry.getKey()) || entry.getValue().size() != 1
                    || entry.getValue().getFirst() == null || entry.getValue().getFirst().isEmpty()) {
                throw invalid("查询参数未知、重复或为空。");
            }
        }
        Direction direction; State state;
        try {
            direction = Direction.valueOf(query.getFirst("direction"));
            state = State.valueOf(query.getFirst("state"));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw invalid("须指定 SOURCE／TARGET 方向与 PENDING／ALL 状态。");
        }
        String rawLimit = query.getFirst("limit");
        if (rawLimit != null && !rawLimit.matches("(?:[1-9]|1[0-9]|20)")) throw invalid("每页数量须为 1 至 20。");
        int limit = rawLimit == null ? 20 : Integer.parseInt(rawLimit);
        return response(service.list(owner.id(), itemId, direction, state, query.getFirst("cursor"), limit));
    }

    @PostMapping(value = "/{itemId}/revalidation-impacts/{impactId}/resolve", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Impact> resolve(@PathVariable("itemId") String itemId, @PathVariable("impactId") String impactId,
                                          @RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        return response(service.resolve(owner.id(), itemId, impactId, key, decode(raw)));
    }

    @GetMapping("/{itemId}/revalidation-impacts/{impactId}/lineage")
    public ResponseEntity<Lineage> lineage(@PathVariable("itemId") String itemId, @PathVariable("impactId") String impactId,
                                           @RequestParam MultiValueMap<String, String> query) {
        if (!query.isEmpty()) throw invalid("采用路径详情不接受查询参数。");
        return response(service.lineage(owner.id(), itemId, impactId));
    }

    private Resolve decode(byte[] raw) {
        if (raw == null || raw.length == 0 || raw.length > REQUEST_BYTES) throw invalid("请求须为不超过 16 KiB 的 JSON 对象。");
        JsonNode body;
        try { body = requests.readTree(raw); }
        catch (RuntimeException exception) { throw invalid("JSON 格式无效、字段重复或嵌套超限。"); }
        if (body == null || !body.isObject() || body.size() != REQUEST_FIELDS.size()) throw invalid("必须提供完整的六个处理字段。");
        for (var field : body.properties()) {
            String name = field.getKey(); JsonNode value = field.getValue();
            if (!REQUEST_FIELDS.contains(name)) throw invalid("处理请求包含未知字段。");
            if (name.equals("expectedRevision") || name.equals("expectedTargetRevision")) {
                if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalid("预期修订须为整数。");
            } else if (name.equals("confirmed")) {
                if (!value.isBoolean() || !value.booleanValue()) throw invalid("须明确确认本次处理。");
            } else if (!value.isTextual()) throw invalid("标识、处理方式和理由须为字符串。");
        }
        try { return requests.treeToValue(body, Resolve.class); }
        catch (RuntimeException exception) { throw invalid("处理字段、类型或枚举无效。"); }
    }

    private <T> ResponseEntity<T> response(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
