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

import static com.wenjie.studio.knowledge.RelationEvidenceDtos.*;

@RestController
@RequestMapping("/api/v1/knowledge/relations/{relationId}/evidence")
public class RelationEvidenceController {
    private static final Set<String> CREATE_FIELDS = Set.of("reason", "source", "confirmed");
    private static final Set<String> SOURCE_FIELDS = Set.of("kind", "excerptIds", "training");
    private static final Set<String> TRAINING_FIELDS = Set.of("sessionId", "questionId", "answerId", "fragments");
    private static final Set<String> FRAGMENT_FIELDS = Set.of("field", "elementId", "start", "end", "quote");
    private final RelationEvidenceService service;
    private final CurrentOwner owner;
    private final JsonMapper requests;

    public RelationEvidenceController(RelationEvidenceService service, CurrentOwner owner) {
        this.service = service;
        this.owner = owner;
        // 原始字节解析保留重复键边界，避免请求中的后值替换已确认的来源。
        requests = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8)
                                .maxStringLength(262144).maxNumberLength(32).build()).build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @GetMapping
    public Page page(@PathVariable("relationId") String relationId,
                     @RequestParam(name = "cursor", required = false) String cursor,
                     @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return service.page(owner.id(), relationId, cursor, limit);
    }

    @GetMapping("/{evidenceId}")
    public Detail detail(@PathVariable("relationId") String relationId, @PathVariable("evidenceId") String evidenceId) {
        return service.detail(owner.id(), relationId, evidenceId);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Entry create(@PathVariable("relationId") String relationId,
                        @RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        if (raw == null || raw.length == 0 || raw.length > 262144) throw invalid();
        Create request;
        try {
            JsonNode body = requests.readTree(raw);
            fields(body, CREATE_FIELDS);
            if (!body.get("reason").isTextual() || !body.get("confirmed").isBoolean()) throw invalid();
            JsonNode source = body.get("source");
            fields(source, SOURCE_FIELDS);
            if (!source.get("kind").isTextual() || !source.get("excerptIds").isArray()) throw invalid();
            for (JsonNode id : source.get("excerptIds")) if (!id.isTextual()) throw invalid();
            if ("MATERIAL".equals(source.get("kind").asText())) {
                if (!source.get("training").isNull() || source.get("excerptIds").isEmpty()
                        || source.get("excerptIds").size() > 5) throw invalid();
            } else if ("TRAINING".equals(source.get("kind").asText())) {
                if (!source.get("excerptIds").isEmpty()) throw invalid();
                JsonNode training = source.get("training");
                fields(training, TRAINING_FIELDS);
                for (String field : Set.of("sessionId", "questionId", "answerId")) {
                    if (!training.get(field).isTextual()) throw invalid();
                }
                JsonNode fragments = training.get("fragments");
                if (!fragments.isArray() || fragments.isEmpty() || fragments.size() > 8) throw invalid();
                for (JsonNode fragment : fragments) {
                    fields(fragment, FRAGMENT_FIELDS);
                    if (!fragment.get("field").isTextual() || !fragment.get("quote").isTextual()
                            || !(fragment.get("elementId").isNull() || fragment.get("elementId").isTextual())) throw invalid();
                    for (String field : Set.of("start", "end")) {
                        if (!fragment.get(field).isIntegralNumber() || !fragment.get(field).canConvertToInt()) throw invalid();
                    }
                }
            } else throw invalid();
            request = requests.treeToValue(body, Create.class);
        } catch (RuntimeException exception) { throw invalid(); }
        return service.create(owner.id(), relationId, key, request);
    }

    private static void fields(JsonNode value, Set<String> names) {
        if (value == null || !value.isObject() || value.size() != names.size()
                || names.stream().anyMatch(name -> !value.has(name))) throw invalid();
    }
    private static ApiException invalid() { return new ApiException(400, "INVALID_REQUEST", "关系证据请求字段或类型无效。"); }
}
