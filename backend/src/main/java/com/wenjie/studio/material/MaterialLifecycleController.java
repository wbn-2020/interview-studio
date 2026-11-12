package com.wenjie.studio.material;

import com.wenjie.studio.common.CurrentOwner;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

import static com.wenjie.studio.material.MaterialLifecycleDtos.*;

@RestController
@RequestMapping(value = "/api/v1/materials/excerpts/{id}/lifecycle", produces = MediaType.APPLICATION_JSON_VALUE)
public class MaterialLifecycleController {
    private static final Set<String> CHANGE_FIELDS = Set.of("expectedRevision", "action", "reason", "confirmed");
    private final CurrentOwner owner;
    private final MaterialLifecycleService service;
    private final JsonMapper requests;

    public MaterialLifecycleController(CurrentOwner owner, MaterialLifecycleService service) {
        this.owner = owner;
        this.service = service;
        requests = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4)
                                .maxStringLength(10000).maxNumberLength(32).build()).build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @GetMapping
    public ResponseEntity<State> state(@PathVariable("id") String id, @RequestParam MultiValueMap<String, String> query) {
        parameters(query, Set.of());
        return noStore(service.state(owner.id(), id));
    }

    @PostMapping(value = "/changes", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<State> change(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                                       @RequestParam MultiValueMap<String, String> query, @RequestBody byte[] raw) {
        parameters(query, Set.of());
        if (raw == null || raw.length == 0 || raw.length > 20000) throw MaterialGuards.invalid("生命周期请求大小无效。");
        Change request;
        try {
            JsonNode body = requests.readTree(raw);
            if (body == null || !body.isObject() || body.size() != CHANGE_FIELDS.size()
                    || CHANGE_FIELDS.stream().anyMatch(field -> !body.has(field))
                    || !body.get("expectedRevision").isIntegralNumber() || !body.get("expectedRevision").canConvertToLong()
                    || !body.get("action").isTextual() || !body.get("reason").isTextual()
                    || !body.get("confirmed").isBoolean()) throw MaterialGuards.invalid("生命周期请求字段或类型无效。");
            request = requests.treeToValue(body, Change.class);
        } catch (RuntimeException exception) { throw MaterialGuards.invalid("生命周期请求字段或类型无效。"); }
        return noStore(service.change(owner.id(), id, key, request));
    }

    @GetMapping("/history")
    public ResponseEntity<HistoryPage> history(@PathVariable("id") String id,
                                              @RequestParam MultiValueMap<String, String> query) {
        parameters(query, Set.of("after", "limit"));
        String rawLimit = query.getFirst("limit");
        if (rawLimit != null && !rawLimit.matches("(?:[1-9]|1[0-9]|20)")) throw MaterialGuards.invalid("每页数量须为 1–20。");
        return noStore(service.history(owner.id(), id, query.getFirst("after"), rawLimit == null ? 20 : Integer.parseInt(rawLimit)));
    }

    private static void parameters(MultiValueMap<String, String> query, Set<String> allowed) {
        for (var entry : query.entrySet()) {
            if (!allowed.contains(entry.getKey()) || entry.getValue().size() != 1
                    || entry.getValue().getFirst() == null || entry.getValue().getFirst().isEmpty()) {
                throw MaterialGuards.invalid("查询参数未知、重复或为空。");
            }
        }
    }
    private static <T> ResponseEntity<T> noStore(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
