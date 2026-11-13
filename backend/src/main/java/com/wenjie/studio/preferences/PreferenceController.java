package com.wenjie.studio.preferences;

import com.wenjie.studio.common.CurrentOwner;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

import static com.wenjie.studio.preferences.PreferenceDtos.*;
import static com.wenjie.studio.preferences.PreferenceService.invalid;

@RestController
@RequestMapping(value = "/api/v1/preferences", produces = MediaType.APPLICATION_JSON_VALUE)
public class PreferenceController {
    private static final Set<String> FIELDS = Set.of("expectedRevision", "theme", "fontSize", "contentWidth",
            "motion", "timeZone", "saveRecoveryNotice");
    private final CurrentOwner owner;
    private final PreferenceService service;
    private final JsonMapper requests;

    public PreferenceController(CurrentOwner owner, PreferenceService service) {
        this.owner = owner; this.service = service;
        requests = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(3)
                                .maxStringLength(128).maxNumberLength(16).build()).build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @GetMapping
    public ResponseEntity<Preferences> get(@RequestParam MultiValueMap<String, String> query) {
        if (!query.isEmpty()) throw invalid("个人设置读取不接受查询参数。");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(owner.id()));
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Preferences> save(@RequestHeader("Idempotency-Key") String key,
            @RequestParam MultiValueMap<String, String> query, @RequestBody byte[] raw) {
        if (!query.isEmpty() || raw == null || raw.length == 0 || raw.length > 4096)
            throw invalid("个人设置请求大小或查询参数无效。");
        Save request;
        try {
            JsonNode body = requests.readTree(raw);
            if (body == null || !body.isObject() || body.size() != FIELDS.size()
                    || FIELDS.stream().anyMatch(field -> !body.has(field))
                    || !body.get("expectedRevision").isIntegralNumber()
                    || !body.get("fontSize").isIntegralNumber() || !body.get("contentWidth").isIntegralNumber()
                    || !body.get("theme").isTextual() || !body.get("motion").isTextual()
                    || !body.get("timeZone").isTextual() || !body.get("saveRecoveryNotice").isBoolean())
                throw invalid("个人设置字段或类型无效。");
            request = requests.treeToValue(body, Save.class);
        } catch (RuntimeException exception) { throw invalid("个人设置字段或类型无效。"); }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.save(owner.id(), key, request));
    }

    @GetMapping("/usage")
    public ResponseEntity<Usage> usage(@RequestParam MultiValueMap<String, String> query) {
        if (!query.isEmpty()) throw invalid("用量读取不接受查询参数。");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.usage(owner.id()));
    }
}
