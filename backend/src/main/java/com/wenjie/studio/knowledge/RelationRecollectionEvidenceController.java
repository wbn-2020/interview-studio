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
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

import static com.wenjie.studio.knowledge.RelationRecollectionEvidenceDtos.*;

@RestController
@RequestMapping("/api/v1/knowledge")
public class RelationRecollectionEvidenceController {
    private static final Set<String> CREATE_FIELDS = Set.of("reason", "source", "confirmed");
    private static final Set<String> SOURCE_FIELDS = Set.of("recollectionId", "versionId", "expectedRevision", "fragments");
    private static final Set<String> FRAGMENT_FIELDS = Set.of("field", "start", "end", "quote");
    private final RelationRecollectionEvidenceService service;
    private final CurrentOwner owner;
    private final JsonMapper requests;

    public RelationRecollectionEvidenceController(RelationRecollectionEvidenceService service, CurrentOwner owner) {
        this.service = service;
        this.owner = owner;
        // 原始请求先拒绝重复键与类型强转，避免覆盖用户已核对的回忆版本或原文范围。
        requests = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8)
                                .maxStringLength(262144).maxNumberLength(32).build()).build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @GetMapping("/relations/{relationId}/recollection-evidence")
    public Page page(@PathVariable("relationId") String relationId,
                     @RequestParam(name = "cursor", required = false) String cursor,
                     @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return service.page(owner.id(), relationId, cursor, limit);
    }

    @GetMapping("/relations/{relationId}/recollection-evidence/{evidenceId}")
    public Detail detail(@PathVariable("relationId") String relationId, @PathVariable("evidenceId") String evidenceId) {
        return service.detail(owner.id(), relationId, evidenceId);
    }

    @PostMapping(value = "/relations/{relationId}/recollection-evidence", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Entry create(@PathVariable("relationId") String relationId,
                        @RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        if (raw == null || raw.length == 0 || raw.length > 262144) throw invalid();
        Create request;
        try {
            JsonNode root = requests.readTree(raw);
            fields(root, CREATE_FIELDS);
            if (!root.get("reason").isTextual() || !root.get("confirmed").isBoolean()) throw invalid();
            JsonNode source = root.get("source");
            fields(source, SOURCE_FIELDS);
            if (!source.get("recollectionId").isTextual() || !source.get("versionId").isTextual()
                    || !source.get("expectedRevision").isIntegralNumber() || !source.get("expectedRevision").canConvertToLong()) throw invalid();
            JsonNode fragments = source.get("fragments");
            if (!fragments.isArray() || fragments.isEmpty() || fragments.size() > 8) throw invalid();
            for (JsonNode fragment : fragments) {
                fields(fragment, FRAGMENT_FIELDS);
                if (!fragment.get("field").isTextual() || !fragment.get("quote").isTextual()
                        || !integer(fragment.get("start")) || !integer(fragment.get("end"))) throw invalid();
            }
            request = requests.treeToValue(root, Create.class);
        } catch (RuntimeException exception) { throw invalid(); }
        return service.create(owner.id(), relationId, key, request);
    }

    private static void fields(JsonNode value, Set<String> names) {
        if (value == null || !value.isObject() || value.size() != names.size() || names.stream().anyMatch(name -> !value.has(name))) throw invalid();
    }
    private static boolean integer(JsonNode value) { return value != null && value.isIntegralNumber() && value.canConvertToInt(); }
    private static ApiException invalid() { return new ApiException(400, "INVALID_REQUEST", "关系回忆证据请求字段或类型无效。"); }
}
