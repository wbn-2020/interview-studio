package com.wenjie.studio.ai;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.CurrentOwner;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;

import java.util.Set;

import static com.wenjie.studio.ai.AiDtos.*;

@RestController
@RequestMapping("/api/v1/training/sessions/{sessionId}")
public class TrainingEvaluationController {
    private final TrainingEvaluations evaluations;
    private final AiService ai;
    private final CurrentOwner owner;
    private final JsonMapper requests;
    private final JsonMapper taskRequests = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12)
                            .maxStringLength(262144).maxNumberLength(32).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public TrainingEvaluationController(TrainingEvaluations evaluations, AiService ai, CurrentOwner owner, JsonMapper json) {
        this.evaluations = evaluations;
        this.ai = ai;
        this.owner = owner;
        requests = json.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @GetMapping("/questions/{questionId}/evaluations/access")
    public EvaluationAccess access(@PathVariable("sessionId") String sessionId, @PathVariable("questionId") String questionId,
                                   @RequestParam("answerId") String answerId) {
        return evaluations.access(owner.id(), sessionId, questionId, answerId);
    }

    @PostMapping("/questions/{questionId}/evaluations/access")
    public EvaluationAccess grant(@PathVariable("sessionId") String sessionId, @PathVariable("questionId") String questionId,
                                  @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode body) {
        return evaluations.grantAccess(owner.id(), sessionId, questionId, key, decode(body, EvaluationAccessRequest.class));
    }

    @PostMapping("/questions/{questionId}/evaluations/preview")
    public Preview preview(@PathVariable("sessionId") String sessionId, @PathVariable("questionId") String questionId,
                           @RequestBody JsonNode body) {
        return ai.previewEvaluation(owner.id(), sessionId, questionId, decode(body, EvaluationPreviewRequest.class));
    }

    @PostMapping(value = "/questions/{questionId}/evaluations", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Task create(@PathVariable("sessionId") String sessionId, @PathVariable("questionId") String questionId,
                       @RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        JsonNode body = taskBody(raw);
        return ai.createEvaluation(owner.id(), sessionId, questionId, key, decode(body, CreateEvaluation.class));
    }

    @GetMapping("/evaluations")
    public EvaluationPage list(@PathVariable("sessionId") String sessionId,
                               @RequestParam(name = "questionId", required = false) String questionId,
                               @RequestParam(name = "cursor", required = false) String cursor,
                               @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return ai.evaluations(owner.id(), sessionId, questionId, cursor, limit);
    }

    @GetMapping("/evaluations/coverage")
    public EvaluationSessionCoverage coverage(@PathVariable("sessionId") String sessionId) {
        return ai.evaluationCoverage(owner.id(), sessionId);
    }

    private <T> T decode(JsonNode body, Class<T> type) {
        if (body == null || !body.isObject()) throw invalid();
        for (var field : body.properties()) {
            JsonNode value = field.getValue();
            if (value.isNull()) continue;
            if ("authorization".equals(field.getKey())) {
                if (!value.isObject() || value.size() != 2 || !value.has("id") || !value.has("expectedRevision")
                        || !value.get("id").isTextual() || !value.get("expectedRevision").isIntegralNumber()
                        || !value.get("expectedRevision").canConvertToLong()) throw invalid();
            } else if ("expectedSessionRevision".equals(field.getKey())) {
                if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
            } else if (Set.of("confirmed", "earlyFeedbackConfirmed", "consentConfirmed").contains(field.getKey())) {
                if (!value.isBoolean()) throw invalid();
            } else if (!value.isTextual()) throw invalid();
        }
        try { return requests.treeToValue(body, type); }
        catch (RuntimeException exception) { throw invalid(); }
    }

    private JsonNode taskBody(byte[] raw) {
        if (raw == null || raw.length == 0 || raw.length > 262144) throw invalid();
        try {
            JsonNode body = taskRequests.readTree(raw);
            if (body == null || !body.isObject()) throw invalid();
            return body;
        } catch (RuntimeException exception) { throw invalid(); }
    }

    private static ApiException invalid() { return new ApiException(400, "INVALID_REQUEST", "请求字段或类型不符合评价接口要求。"); }
}
