package com.wenjie.studio.ai;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/review/comparisons/{id}/ai")
public class ComparisonController {
    private final AiService ai;
    private final CurrentOwner owner;
    private final JsonMapper taskRequests = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12)
                            .maxStringLength(262144).maxNumberLength(32).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public ComparisonController(AiService ai, CurrentOwner owner) {
        this.ai = ai;
        this.owner = owner;
    }

    @PostMapping("/preview")
    public AiDtos.Preview preview(@PathVariable("id") String id, @RequestBody JsonNode body) {
        return ai.previewComparison(owner.id(), id, new ComparisonDtos.PreviewRequest(sourceIds(body, false)));
    }

    @PostMapping(value = "/tasks", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public AiDtos.Task create(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                              @RequestBody byte[] raw) {
        JsonNode body = taskBody(raw);
        List<String> sourceIds = sourceIds(body, true);
        JsonNode hash = body.get("expectedManifestHash"), consent = body.get("consentConfirmed");
        if (!hash.isTextual() || !hash.stringValue().matches("[0-9a-f]{64}") || !consent.isBoolean()) throw invalid();
        return ai.createComparison(owner.id(), id, key,
                new ComparisonDtos.Create(sourceIds, hash.stringValue(), consent.booleanValue(), authorization(body.get("authorization"))));
    }

    @GetMapping("/tasks")
    public AiDtos.TaskPage list(@PathVariable("id") String id,
                                @RequestParam(name = "cursor", required = false) String cursor,
                                @RequestParam(name = "limit", defaultValue = "10") int limit) {
        if (limit < 1 || limit > 20) throw invalid();
        if (cursor != null) uuid(cursor);
        return ai.comparisons(owner.id(), id, cursor, limit);
    }

    private static List<String> sourceIds(JsonNode body, boolean create) {
        if (body == null || !body.isObject()) throw invalid();
        Set<String> expected = create ? Set.of("sourceIds", "expectedManifestHash", "consentConfirmed") : Set.of("sourceIds");
        Set<String> names = new HashSet<>();
        for (var field : body.properties()) names.add(field.getKey());
        if (create) names.remove("authorization");
        if (!names.equals(expected)) throw invalid();
        JsonNode ids = body.get("sourceIds");
        if (ids == null || !ids.isArray() || ids.size() < 1 || ids.size() > 10) throw invalid();
        List<String> values = new ArrayList<>();
        Set<String> distinct = new HashSet<>();
        for (JsonNode id : ids) {
            if (!id.isTextual()) throw invalid();
            String value = id.stringValue();
            uuid(value);
            if (!distinct.add(value)) throw invalid();
            values.add(value);
        }
        return List.copyOf(values);
    }

    private static AiAuthorizationDtos.Use authorization(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!value.isObject() || value.size() != 2 || !value.has("id") || !value.has("expectedRevision")
                || !value.get("id").isTextual() || !value.get("expectedRevision").isIntegralNumber()
                || !value.get("expectedRevision").canConvertToLong()) throw invalid();
        String id = value.get("id").stringValue();
        uuid(id);
        long revision = value.get("expectedRevision").longValue();
        if (revision < 0 || revision > 9_007_199_254_740_991L) throw invalid();
        return new AiAuthorizationDtos.Use(id, revision);
    }

    private JsonNode taskBody(byte[] raw) {
        if (raw == null || raw.length == 0 || raw.length > 262144) throw invalid();
        try {
            JsonNode body = taskRequests.readTree(raw);
            if (body == null || !body.isObject()) throw invalid();
            return body;
        } catch (RuntimeException exception) { throw invalid(); }
    }

    private static void uuid(String value) {
        try { if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException exception) { throw invalid(); }
    }
    private static ApiException invalid() { return new ApiException(400, "INVALID_REQUEST", "比较来源、授权字段或分页参数不符合要求。"); }
}
