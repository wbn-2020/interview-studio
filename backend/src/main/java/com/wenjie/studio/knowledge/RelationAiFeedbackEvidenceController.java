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

import static com.wenjie.studio.knowledge.RelationAiFeedbackEvidenceDtos.*;

@RestController
@RequestMapping("/api/v1/knowledge")
public class RelationAiFeedbackEvidenceController {
    private static final Set<String> CREATE_FIELDS = Set.of("reason", "source", "confirmed");
    private static final Set<String> SOURCE_FIELDS = Set.of("taskId", "attemptId", "feedbackId", "reportHash",
            "expectedDecisionRevision", "answer", "fragments");
    private static final Set<String> ANSWER_FIELDS = Set.of("sessionId", "questionId", "answerId");
    private static final Set<String> FRAGMENT_FIELDS = Set.of("field", "start", "end", "quote");
    private final RelationAiFeedbackEvidenceService service;
    private final CurrentOwner owner;
    private final JsonMapper requests;

    public RelationAiFeedbackEvidenceController(RelationAiFeedbackEvidenceService service, CurrentOwner owner) {
        this.service = service;
        this.owner = owner;
        // 原始字节阶段拒绝重复键，固定报告摘要与尝试身份不能被后值替换。
        requests = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8)
                                .maxStringLength(262144).maxNumberLength(32).build()).build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @GetMapping("/relation-ai-feedback-sources/{taskId}/feedback/{feedbackId}")
    public Source source(@PathVariable("taskId") String taskId, @PathVariable("feedbackId") String feedbackId) {
        return service.source(owner.id(), taskId, feedbackId);
    }

    @GetMapping("/relations/{relationId}/ai-feedback-evidence")
    public Page page(@PathVariable("relationId") String relationId,
                     @RequestParam(name = "cursor", required = false) String cursor,
                     @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return service.page(owner.id(), relationId, cursor, limit);
    }

    @GetMapping("/relations/{relationId}/ai-feedback-evidence/{evidenceId}")
    public Detail detail(@PathVariable("relationId") String relationId, @PathVariable("evidenceId") String evidenceId) {
        return service.detail(owner.id(), relationId, evidenceId);
    }

    @PostMapping(value = "/relations/{relationId}/ai-feedback-evidence", consumes = MediaType.APPLICATION_JSON_VALUE)
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
            for (String name : Set.of("taskId", "attemptId", "feedbackId", "reportHash")) {
                if (!source.get(name).isTextual()) throw invalid();
            }
            if (!integer(source.get("expectedDecisionRevision"))) throw invalid();
            JsonNode answer = source.get("answer");
            fields(answer, ANSWER_FIELDS);
            for (String name : ANSWER_FIELDS) if (!answer.get(name).isTextual()) throw invalid();
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
    private static ApiException invalid() { return new ApiException(400, "INVALID_REQUEST", "关系 AI 反馈证据请求字段或类型无效。"); }
}
