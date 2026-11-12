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
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;

import java.util.Set;

import static com.wenjie.studio.ai.AiDtos.*;

/** 当前训练可用的纯追问出口；所有任务须复核同场绑定，不能借任务 ID 读取资料任务。 */
@RestController
@RequestMapping("/api/v1/training/sessions/{sessionId}")
public class TrainingFollowUpController {
    private final AiService service;
    private final CurrentOwner owner;
    private final JsonMapper requests;
    private final JsonMapper taskRequests = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12)
                            .maxStringLength(262144).maxNumberLength(32).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public TrainingFollowUpController(AiService service, CurrentOwner owner, JsonMapper json) {
        this.service = service;
        this.owner = owner;
        requests = json.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @GetMapping("/follow-ups/settings")
    public Settings settings(@PathVariable("sessionId") String sessionId) {
        return service.followUpSettings(owner.id(), sessionId);
    }

    @PostMapping("/questions/{questionId}/follow-ups/preview")
    public Preview preview(@PathVariable("sessionId") String sessionId, @PathVariable("questionId") String questionId,
                           @RequestBody JsonNode request) {
        return service.previewFollowUp(owner.id(), sessionId, questionId, decode(request, FollowUpPreviewRequest.class));
    }

    @PostMapping(value = "/questions/{questionId}/follow-ups", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public FollowUpTask create(@PathVariable("sessionId") String sessionId, @PathVariable("questionId") String questionId,
                                @RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        JsonNode request = taskBody(raw);
        return service.createFollowUp(owner.id(), sessionId, questionId, key, decode(request, CreateFollowUp.class));
    }

    @GetMapping("/follow-ups")
    public FollowUpPage list(@PathVariable("sessionId") String sessionId,
                            @RequestParam(name = "cursor", required = false) String cursor,
                            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return service.followUps(owner.id(), sessionId, cursor, limit);
    }

    @GetMapping("/follow-ups/{taskId}")
    public FollowUpTask task(@PathVariable("sessionId") String sessionId, @PathVariable("taskId") String taskId) {
        return service.followUp(owner.id(), sessionId, taskId);
    }

    @PostMapping("/follow-ups/{taskId}/cancel")
    public FollowUpTask cancel(@PathVariable("sessionId") String sessionId, @PathVariable("taskId") String taskId,
                              @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode request) {
        return service.cancelFollowUp(owner.id(), sessionId, taskId, key, decode(request, ChangeTask.class));
    }

    @PostMapping(value = "/follow-ups/{taskId}/retry", consumes = MediaType.APPLICATION_JSON_VALUE)
    public FollowUpTask retry(@PathVariable("sessionId") String sessionId, @PathVariable("taskId") String taskId,
                             @RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        JsonNode request = taskBody(raw);
        return service.retryFollowUp(owner.id(), sessionId, taskId, key, decode(request, RetryTask.class));
    }

    @PostMapping("/follow-ups/{taskId}/attempts/{attemptId}/reconcile")
    public FollowUpTask reconcile(@PathVariable("sessionId") String sessionId, @PathVariable("taskId") String taskId,
                                 @PathVariable("attemptId") String attemptId,
                                 @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode request) {
        return service.reconcileFollowUp(owner.id(), sessionId, taskId, attemptId, key, decode(request, Reconcile.class));
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
            } else if (Set.of("expectedRevision", "amountMicros").contains(field.getKey())) {
                if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
            } else if (Set.of("consentConfirmed", "acknowledgeUnknownCost").contains(field.getKey())) {
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

    private static ApiException invalid() { return new ApiException(400, "INVALID_REQUEST", "请求字段或类型不符合训练追问接口要求。"); }
}
