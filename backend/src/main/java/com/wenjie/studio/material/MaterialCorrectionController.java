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
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

import static com.wenjie.studio.material.MaterialCorrectionDtos.*;

@RestController
@RequestMapping(value = "/api/v1/materials/excerpts/{id}/correction", produces = MediaType.APPLICATION_JSON_VALUE)
public class MaterialCorrectionController {
    private static final Set<String> FIELDS = Set.of("expectedRevision", "replacementExcerptId",
            "expectedReplacementRevision", "reason", "confirmed");
    private final CurrentOwner owner;
    private final MaterialCorrectionService service;
    private final JsonMapper requests;

    public MaterialCorrectionController(CurrentOwner owner, MaterialCorrectionService service) {
        this.owner = owner; this.service = service;
        requests = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4)
                                .maxStringLength(10000).maxNumberLength(32).build()).build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @GetMapping
    public ResponseEntity<State> state(@PathVariable("id") String id, @RequestParam MultiValueMap<String, String> query) {
        parameters(query);
        return noStore(service.state(owner.id(), id));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<State> create(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                                       @RequestParam MultiValueMap<String, String> query, @RequestBody byte[] raw) {
        parameters(query);
        if (raw == null || raw.length == 0 || raw.length > 20000) throw MaterialGuards.invalid("正文更正请求大小无效。");
        Create request;
        try {
            JsonNode body = requests.readTree(raw);
            if (body == null || !body.isObject() || body.size() != FIELDS.size()
                    || FIELDS.stream().anyMatch(field -> !body.has(field))
                    || !body.get("expectedRevision").isIntegralNumber() || !body.get("expectedRevision").canConvertToLong()
                    || !body.get("expectedReplacementRevision").isIntegralNumber()
                    || !body.get("expectedReplacementRevision").canConvertToLong()
                    || !body.get("replacementExcerptId").isTextual() || !body.get("reason").isTextual()
                    || !body.get("confirmed").isBoolean()) throw MaterialGuards.invalid("正文更正请求字段或类型无效。");
            request = requests.treeToValue(body, Create.class);
        } catch (RuntimeException exception) { throw MaterialGuards.invalid("正文更正请求字段或类型无效。"); }
        return noStore(service.create(owner.id(), id, key, request));
    }

    private static void parameters(MultiValueMap<String, String> query) {
        if (!query.isEmpty()) throw MaterialGuards.invalid("正文更正不接受查询参数。");
    }
    private static <T> ResponseEntity<T> noStore(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
