package com.wenjie.studio.ai;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.CurrentOwner;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
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

@RestController
@RequestMapping("/api/v1/ai/source-qa")
public class SourceQaController {
    private static final Set<String> PREVIEW_FIELDS = Set.of("question", "selections");
    private static final Set<String> CREATE_FIELDS = Set.of("question", "selections", "expectedManifestHash", "consentConfirmed");
    private static final Set<String> SELECTION_FIELDS = Set.of("source", "ranges");
    private static final Set<String> SOURCE_FIELDS = Set.of("domain", "id", "versionId", "revision", "feedbackId", "reportHash");
    private static final Set<String> RANGE_FIELDS = Set.of("field", "start", "end");
    private static final Set<String> AUTHORIZATION_FIELDS = Set.of("id", "expectedRevision");
    private final AiService service;
    private final CurrentOwner owner;
    private final JsonMapper requests;

    public SourceQaController(AiService service, CurrentOwner owner) {
        this.service = service;
        this.owner = owner;
        // 必须在转成对象之前拒绝重复键和隐式类型转换，避免授权选区被另一同名字段替换。
        requests = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12)
                                .maxStringLength(262144).maxNumberLength(32).build()).build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @PostMapping(value = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public AiDtos.Preview preview(@RequestBody byte[] raw) {
        return service.previewSourceQa(owner.id(), decode(raw, false, SourceQaDtos.PreviewRequest.class));
    }

    @PostMapping(value = "/tasks", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public AiDtos.Task create(@RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        return service.createSourceQa(owner.id(), key, decode(raw, true, SourceQaDtos.CreateRequest.class));
    }

    private <T> T decode(byte[] raw, boolean creating, Class<T> type) {
        if (raw == null || raw.length == 0 || raw.length > 262144) throw invalid();
        try {
            JsonNode body = requests.readTree(raw);
            exact(body, creating ? CREATE_FIELDS : PREVIEW_FIELDS, creating ? Set.of("authorization") : Set.of());
            if (!body.get("question").isTextual()) throw invalid();
            JsonNode selections = body.get("selections");
            if (!selections.isArray() || selections.isEmpty() || selections.size() > 5) throw invalid();
            int rangeCount = 0;
            for (JsonNode selection : selections) {
                exact(selection, SELECTION_FIELDS, Set.of());
                JsonNode source = selection.get("source");
                exact(source, SOURCE_FIELDS, Set.of());
                for (String field : Set.of("domain", "id", "versionId")) if (!source.get(field).isTextual()) throw invalid();
                JsonNode revision = source.get("revision");
                if (!revision.isNull() && (!revision.isIntegralNumber() || !revision.canConvertToInt())) throw invalid();
                for (String field : Set.of("feedbackId", "reportHash")) {
                    if (!source.get(field).isNull() && !source.get(field).isTextual()) throw invalid();
                }
                JsonNode ranges = selection.get("ranges");
                if (!ranges.isArray() || ranges.isEmpty() || ranges.size() > 8) throw invalid();
                rangeCount += ranges.size();
                if (rangeCount > 8) throw invalid();
                for (JsonNode range : ranges) {
                    exact(range, RANGE_FIELDS, Set.of());
                    if (!range.get("field").isTextual()) throw invalid();
                    for (String field : Set.of("start", "end")) {
                        if (!range.get(field).isIntegralNumber() || !range.get(field).canConvertToInt()) throw invalid();
                    }
                }
            }
            if (creating) {
                if (!body.get("expectedManifestHash").isTextual() || !body.get("consentConfirmed").isBoolean()) throw invalid();
                JsonNode authorization = body.get("authorization");
                if (authorization != null && !authorization.isNull()) {
                    exact(authorization, AUTHORIZATION_FIELDS, Set.of());
                    if (!authorization.get("id").isTextual() || !authorization.get("expectedRevision").isIntegralNumber()
                            || !authorization.get("expectedRevision").canConvertToLong()) throw invalid();
                }
            }
            return requests.treeToValue(body, type);
        } catch (RuntimeException exception) { throw invalid(); }
    }

    private static void exact(JsonNode node, Set<String> required, Set<String> optional) {
        if (node == null || !node.isObject() || !node.propertyNames().containsAll(required)
                || node.propertyNames().stream().anyMatch(field -> !required.contains(field) && !optional.contains(field))) throw invalid();
    }

    private static ApiException invalid() {
        return new ApiException(400, "INVALID_REQUEST", "跨域问答请求的字段或类型不符合要求，请重新核对来源选区。");
    }
}
