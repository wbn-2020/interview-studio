package com.wenjie.studio.knowledge;

import com.wenjie.studio.common.CurrentOwner;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
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
import static com.wenjie.studio.knowledge.KnowledgeTextImportDtos.*;

@RestController
@RequestMapping(value = "/api/v1/knowledge/text-imports", produces = MediaType.APPLICATION_JSON_VALUE)
public class KnowledgeTextImportController {
    private static final Set<String> FIELDS = Set.of("fileName", "source", "title", "body", "topicIds", "createCopy");
    private static final Set<String> TAGGED_FIELDS = Set.of("fileName", "source", "title", "body", "topicIds", "createCopy", "tags");
    private final CurrentOwner owner;
    private final KnowledgeTextImportService service;
    private final JsonMapper requests;

    public KnowledgeTextImportController(CurrentOwner owner, KnowledgeTextImportService service) {
        this.owner = owner; this.service = service;
        requests = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4)
                                .maxStringLength(100000).maxNumberLength(16).build()).build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Saved> create(@RequestHeader("Idempotency-Key") String key,
                                       @RequestParam MultiValueMap<String, String> query, @RequestBody byte[] raw) {
        if (!query.isEmpty() || raw == null || raw.length == 0 || raw.length > 1_048_576) throw invalid("导入请求大小或查询参数无效。");
        Create request;
        try {
            JsonNode body = requests.readTree(raw);
            if (body == null || !body.isObject() || body.size() != FIELDS.size()
                    || FIELDS.stream().anyMatch(field -> !body.has(field))
                    || !body.get("fileName").isTextual() || !body.get("source").isTextual()
                    || !body.get("title").isTextual() || !body.get("body").isTextual()
                    || !body.get("topicIds").isArray() || body.get("topicIds").size() > 20
                    || !body.get("createCopy").isBoolean()) throw invalid("导入请求字段或类型无效。");
            for (JsonNode topic : body.get("topicIds")) if (!topic.isTextual()) throw invalid("主题标识须为字符串。");
            request = requests.treeToValue(body, Create.class);
        } catch (RuntimeException exception) { throw invalid("导入请求字段或类型无效。"); }
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.create(owner.id(), key, request));
    }

    @PostMapping(value = "/with-tags", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SavedWithTags> createWithTags(@RequestHeader("Idempotency-Key") String key,
            @RequestParam MultiValueMap<String, String> query, @RequestBody byte[] raw) {
        if (!query.isEmpty() || raw == null || raw.length == 0 || raw.length > 1_048_576) throw invalid("导入请求大小或查询参数无效。");
        CreateWithTags request;
        try {
            JsonNode body = requests.readTree(raw);
            if (body == null || !body.isObject() || body.size() != TAGGED_FIELDS.size()
                    || TAGGED_FIELDS.stream().anyMatch(field -> !body.has(field))
                    || !body.get("fileName").isTextual() || !body.get("source").isTextual()
                    || !body.get("title").isTextual() || !body.get("body").isTextual()
                    || !body.get("topicIds").isArray() || body.get("topicIds").size() > 20
                    || !body.get("createCopy").isBoolean() || !body.get("tags").isArray()
                    || body.get("tags").isEmpty() || body.get("tags").size() > 20) {
                throw invalid("带标签导入请求字段或类型无效。");
            }
            for (JsonNode topic : body.get("topicIds")) if (!topic.isTextual()) throw invalid("主题标识须为字符串。");
            for (JsonNode tag : body.get("tags")) if (!tag.isTextual()) throw invalid("标签须为字符串。");
            request = requests.treeToValue(body, CreateWithTags.class);
        } catch (RuntimeException exception) { throw invalid("带标签导入请求字段或类型无效。"); }
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.createWithTags(owner.id(), key, request));
    }

    @GetMapping
    public ResponseEntity<Page> list(@RequestParam MultiValueMap<String, String> query) {
        if (query.entrySet().stream().anyMatch(entry -> !Set.of("sourceSha256", "cursor", "limit").contains(entry.getKey())
                || entry.getValue().size() != 1)) throw invalid("导入记录查询参数无效或重复。");
        String limit = query.getFirst("limit");
        if (limit != null && !limit.matches("(?:[1-9]|[1-4][0-9]|50)")) throw invalid("每页数量须为1至50。");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(owner.id(),
                query.getFirst("sourceSha256"), query.getFirst("cursor"), limit == null ? 20 : Integer.parseInt(limit)));
    }
}
