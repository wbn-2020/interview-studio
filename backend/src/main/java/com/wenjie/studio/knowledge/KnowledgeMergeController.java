package com.wenjie.studio.knowledge;

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

import static com.wenjie.studio.knowledge.KnowledgeGuards.invalid;
import static com.wenjie.studio.knowledge.KnowledgeMergeDtos.*;

@RestController
@RequestMapping(value = "/api/v1/knowledge", produces = MediaType.APPLICATION_JSON_VALUE)
public class KnowledgeMergeController {
    private static final Set<String> PREVIEW_FIELDS = Set.of("sourceId", "targetId");
    private static final Set<String> COMMIT_FIELDS = Set.of("sourceId", "targetId", "expectedDigest", "aliases", "tags",
            "topicIds", "noteIds", "mainNoteId", "favorite", "useSourcePosition", "confirmed");
    private final CurrentOwner owner;
    private final KnowledgeMergeService service;
    private final JsonMapper requests;

    public KnowledgeMergeController(CurrentOwner owner, KnowledgeMergeService service) {
        this.owner = owner;
        this.service = service;
        requests = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4)
                                .maxStringLength(1000).maxNumberLength(16).build()).build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @PostMapping(value = "/concept-merges/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Preview> preview(@RequestParam MultiValueMap<String, String> query, @RequestBody byte[] raw) {
        if (!query.isEmpty()) throw invalid("合并预览不接受查询参数。");
        PreviewRequest request;
        try {
            JsonNode body = object(raw, PREVIEW_FIELDS);
            text(body, "sourceId"); text(body, "targetId");
            request = requests.treeToValue(body, PreviewRequest.class);
        } catch (RuntimeException exception) { throw invalid("合并预览请求字段或类型无效。"); }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.preview(owner.id(), request));
    }

    @PostMapping(value = "/concept-merges", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Result> commit(@RequestHeader("Idempotency-Key") String key,
                                         @RequestParam MultiValueMap<String, String> query, @RequestBody byte[] raw) {
        if (!query.isEmpty()) throw invalid("概念合并不接受查询参数。");
        Commit request;
        try {
            JsonNode body = object(raw, COMMIT_FIELDS);
            text(body, "sourceId"); text(body, "targetId"); text(body, "expectedDigest");
            textArray(body, "aliases", 20); textArray(body, "tags", 20);
            textArray(body, "topicIds", 20); textArray(body, "noteIds", 100);
            if ((!body.get("mainNoteId").isNull() && !body.get("mainNoteId").isTextual())
                    || !body.get("favorite").isBoolean() || !body.get("useSourcePosition").isBoolean()
                    || !body.get("confirmed").isBoolean()) throw invalid("合并确认字段无效。");
            request = requests.treeToValue(body, Commit.class);
        } catch (RuntimeException exception) { throw invalid("概念合并请求字段或类型无效。"); }
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.commit(owner.id(), key, request));
    }

    @GetMapping("/items/{id}/merge")
    public ResponseEntity<Resolution> resolution(@PathVariable("id") String id, @RequestParam MultiValueMap<String, String> query) {
        if (!query.isEmpty()) throw invalid("合并身份查询不接受查询参数。");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.resolution(owner.id(), id));
    }

    private JsonNode object(byte[] raw, Set<String> fields) {
        if (raw == null || raw.length == 0 || raw.length > 65_536) throw invalid("合并请求大小无效。");
        JsonNode body = requests.readTree(raw);
        if (body == null || !body.isObject() || body.size() != fields.size()
                || fields.stream().anyMatch(field -> !body.has(field))) throw invalid("合并请求字段无效。");
        return body;
    }
    private static void text(JsonNode body, String field) { if (!body.get(field).isTextual()) throw invalid("合并字段须为文本。"); }
    private static void textArray(JsonNode body, String field, int maximum) {
        JsonNode values = body.get(field);
        if (!values.isArray() || values.size() > maximum) throw invalid("合并列表超出允许范围。");
        for (JsonNode value : values) if (!value.isTextual()) throw invalid("合并列表须为文本。");
    }
}
