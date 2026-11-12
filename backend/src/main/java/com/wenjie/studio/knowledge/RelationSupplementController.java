package com.wenjie.studio.knowledge;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.CurrentOwner;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

import static com.wenjie.studio.knowledge.RelationSupplementDtos.*;

@RestController
@RequestMapping("/api/v1/knowledge/relations")
public class RelationSupplementController {
    private static final Set<String> CREATE_FIELDS = Set.of("reason", "sources", "confirmed");
    private static final Set<String> SOURCE_FIELDS = Set.of("itemId", "versionId");
    private final RelationSupplementService service;
    private final CurrentOwner owner;
    private final JsonMapper requests;

    public RelationSupplementController(RelationSupplementService service, CurrentOwner owner) {
        this.service = service;
        this.owner = owner;
        // 在读取原始字节时拒绝重复键，不能让后面的字段覆盖已确认的来源或理由。
        requests = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8)
                                .maxStringLength(262144).maxNumberLength(32).build()).build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @GetMapping("/match")
    public JsonNode match(@RequestParam("sourceId") String sourceId, @RequestParam("targetId") String targetId,
                          @RequestParam("type") KnowledgeDtos.RelationType type) {
        return requests.valueToTree(service.match(owner.id(), sourceId, targetId, type));
    }

    @GetMapping("/{relationId}/supplements")
    public Page page(@PathVariable("relationId") String relationId,
                     @RequestParam(name = "cursor", required = false) String cursor,
                     @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return service.page(owner.id(), relationId, cursor, limit);
    }

    @PostMapping(value = "/{relationId}/supplements", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Supplement create(@PathVariable("relationId") String relationId,
                             @RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        if (raw == null || raw.length == 0 || raw.length > 262144) throw invalid();
        Create request;
        try {
            JsonNode body = requests.readTree(raw);
            fields(body, CREATE_FIELDS);
            if (!body.get("reason").isTextual() || !body.get("confirmed").isBoolean() || !body.get("sources").isArray()) throw invalid();
            for (JsonNode source : body.get("sources")) {
                fields(source, SOURCE_FIELDS);
                if (!source.get("itemId").isTextual() || !source.get("versionId").isTextual()) throw invalid();
            }
            request = requests.treeToValue(body, Create.class);
        } catch (RuntimeException exception) { throw invalid(); }
        return service.create(owner.id(), relationId, key, request);
    }

    private static void fields(JsonNode value, Set<String> names) {
        if (value == null || !value.isObject() || value.size() != names.size() || names.stream().anyMatch(name -> !value.has(name))) throw invalid();
    }
    private static ApiException invalid() { return new ApiException(400, "INVALID_REQUEST", "关系补充请求字段或类型无效。"); }
}
