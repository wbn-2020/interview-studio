package com.wenjie.studio.ai;

import com.wenjie.studio.ai.AiDtos.*;
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
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;

import java.util.Set;

@RestController
@RequestMapping("/api/v1/ai")
public class AiController {
    private static final Set<String> INTEGERS = Set.of("expectedRevision", "amountMicros", "expectedTargetDraftRevision",
            "expectedTargetRevision", "difficulty", "totalMinutes", "minutes", "start", "end", "feedbackRevision");
    private static final Set<String> BOOLEANS = Set.of("consentConfirmed", "acknowledgeUnknownCost", "confirmed");
    private static final Set<String> ADOPT_FIELDS = Set.of("expectedRevision", "mode", "kind", "targetItemId",
            "expectedTargetVersionId", "expectedTargetDraftRevision", "title", "body", "confirmed");
    private static final Set<String> ADOPT_QUESTION_FIELDS = Set.of("expectedRevision", "content", "confirmed");
    private static final Set<String> QUESTION_FIELDS = Set.of("title", "prompt", "conditions", "technology", "scenario",
            "type", "difficulty", "dimensions", "topicIds", "knowledgeLinks", "tags", "source", "referenceAnswer", "checkpoints");
    private static final Set<String> EXPRESSION_PREVIEW_FIELDS = Set.of("type", "instruction", "factSources");
    private static final Set<String> EXPRESSION_CREATE_FIELDS = Set.of("type", "instruction", "factSources", "expectedManifestHash", "consentConfirmed");
    private static final Set<String> ADOPT_EXPRESSION_FIELDS = Set.of("expectedRevision", "mode", "targetItemId",
            "expectedTargetVersionId", "expectedTargetRevision", "content", "confirmed");
    private static final Set<String> EXPRESSION_CONTENT_FIELDS = Set.of("title", "type", "body", "factVersionIds", "tags");
    private static final Set<String> PREPARATION_PLAN_PREVIEW_FIELDS = Set.of("target", "plan", "instruction");
    private static final Set<String> PREPARATION_PLAN_CREATE_FIELDS = Set.of("target", "plan", "instruction", "expectedManifestHash", "consentConfirmed");
    private static final Set<String> TARGET_LINK_FIELDS = Set.of("itemId", "versionId");
    private static final Set<String> PREPARATION_PLAN_INPUT_FIELDS = Set.of("startDate", "endDate", "zoneId", "totalMinutes", "gapSummary");
    private static final Set<String> ADOPT_PREPARATION_PLAN_FIELDS = Set.of("expectedRevision", "content", "confirmed");
    private static final Set<String> PREPARATION_PLAN_CONTENT_FIELDS = Set.of("title", "summary", "steps");
    private static final Set<String> PREPARATION_PLAN_STEP_FIELDS = Set.of("date", "title", "minutes", "detail");
    private static final Set<String> TRAINING_DRAFT_PREVIEW_FIELDS = Set.of("sessionId", "questionId", "answerId",
            "instruction", "fragments", "feedback");
    private static final Set<String> TRAINING_DRAFT_CREATE_FIELDS = Set.of("sessionId", "questionId", "answerId",
            "instruction", "fragments", "feedback", "expectedManifestHash", "consentConfirmed");
    private static final Set<String> TRAINING_DRAFT_FRAGMENT_FIELDS = Set.of("field", "elementId", "start", "end", "quote");
    private static final Set<String> TRAINING_DRAFT_FEEDBACK_FIELDS = Set.of("taskId", "feedbackIds");
    private static final Set<String> REVIEW_DRAFT_PREVIEW_FIELDS = Set.of("instruction", "source");
    private static final Set<String> REVIEW_DRAFT_CREATE_FIELDS = Set.of("instruction", "source", "expectedManifestHash", "consentConfirmed");
    private static final Set<String> REVIEW_DRAFT_SOURCE_FIELDS = Set.of("kind", "feedback", "recollection");
    private static final Set<String> REVIEW_DRAFT_FEEDBACK_FIELDS = Set.of("feedbackId", "feedbackRevision", "fragments");
    private static final Set<String> REVIEW_DRAFT_RECOLLECTION_FIELDS = Set.of("recollectionId", "versionId", "expectedRevision", "fragments");
    private static final Set<String> REVIEW_DRAFT_FRAGMENT_FIELDS = Set.of("field", "start", "end", "quote");
    private static final Set<String> CARD_DRAFT_PREVIEW_FIELDS = Set.of("kind", "instruction", "sources");
    private static final Set<String> CARD_DRAFT_CREATE_FIELDS = Set.of("kind", "instruction", "sources", "expectedManifestHash", "consentConfirmed");
    private static final Set<String> CARD_DRAFT_ADOPT_FIELDS = Set.of("expectedRevision", "content", "confirmed");
    private static final Set<String> CARD_CONTENT_FIELDS = Set.of("kind", "title", "prompt", "reference", "sources", "relatedConceptIds");
    private static final Set<String> JD_REQUIREMENTS_PREVIEW_FIELDS = Set.of("targetItemId", "targetVersionId", "instruction");
    private static final Set<String> JD_REQUIREMENTS_CREATE_FIELDS = Set.of("targetItemId", "targetVersionId", "instruction", "expectedManifestHash", "consentConfirmed");
    private static final Set<String> JD_REQUIREMENTS_ADOPT_FIELDS = Set.of("expectedRevision", "expectedTargetRevision", "requirements", "confirmed");
    private static final Set<String> JD_REQUIREMENTS_SELECTION_FIELDS = Set.of("candidateNumber", "text", "dimensions", "priority");
    private static final Set<String> FACT_DRAFT_PREVIEW_FIELDS = Set.of("instruction", "source");
    private static final Set<String> FACT_DRAFT_CREATE_FIELDS = Set.of("instruction", "source", "expectedManifestHash", "consentConfirmed");
    private static final Set<String> FACT_DRAFT_SOURCE_FIELDS = Set.of("kind", "excerptIds", "training");
    private static final Set<String> FACT_DRAFT_TRAINING_FIELDS = Set.of("sessionId", "questionId", "answerId", "fragments");
    private static final Set<String> FACT_DRAFT_ADOPT_FIELDS = Set.of("expectedRevision", "projectId", "expectedProjectVersionId",
            "expectedProjectRevision", "content", "confirmed");
    private static final Set<String> FACT_DRAFT_CONTENT_FIELDS = Set.of("title", "scenario", "problem", "action", "result", "technology", "tags");
    private static final Set<String> REWRITE_PREVIEW_FIELDS = Set.of("sessionId", "questionId", "answerId");
    private static final Set<String> REWRITE_CREATE_FIELDS = Set.of("sessionId", "questionId", "answerId", "expectedManifestHash", "consentConfirmed");
    private static final Set<String> RELATION_PREVIEW_FIELDS = Set.of("instruction", "sources");
    private static final Set<String> RELATION_CREATE_FIELDS = Set.of("instruction", "sources", "expectedManifestHash", "consentConfirmed");
    private static final Set<String> RELATION_EVIDENCE_PREVIEW_FIELDS = Set.of("instruction", "sources", "source");
    private static final Set<String> RELATION_EVIDENCE_CREATE_FIELDS = Set.of("instruction", "sources", "source", "expectedManifestHash", "consentConfirmed");
    private static final Set<String> RELATION_REVIEW_PREVIEW_FIELDS = Set.of("instruction", "sources", "source");
    private static final Set<String> RELATION_REVIEW_CREATE_FIELDS = Set.of("instruction", "sources", "source", "expectedManifestHash", "consentConfirmed");
    private static final Set<String> RELATION_AI_FEEDBACK_SOURCE_FIELDS = Set.of("taskId", "attemptId", "feedbackId", "reportHash",
            "expectedDecisionRevision", "answer", "fragments");
    private static final Set<String> RELATION_AI_FEEDBACK_ANSWER_FIELDS = Set.of("sessionId", "questionId", "answerId");
    private static final Set<String> ADOPT_RELATION_FIELDS = Set.of("expectedRevision", "content", "confirmed");
    private static final Set<String> SUPPLEMENT_RELATION_FIELDS = Set.of("expectedRevision", "relationId", "content", "confirmed");
    private static final Set<String> RELATION_CONTENT_FIELDS = Set.of("sourceItemId", "targetItemId", "type", "reason");
    private static final Set<String> CHANGE_TASK_FIELDS = Set.of("expectedRevision");
    private final AiService service;
    private final CurrentOwner owner;
    private final JsonMapper requests;
    private final JsonMapper draftRequests;

    public AiController(AiService service, CurrentOwner owner, JsonMapper json) {
        this.service = service;
        this.owner = owner;
        requests = json.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
        // 原始字节阶段拒绝重复键，避免进入选区授权后再丢失被覆盖的字段。
        draftRequests = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12)
                                .maxStringLength(262144).maxNumberLength(32).build()).build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @GetMapping("/settings")
    public Settings settings() { return service.settings(owner.id()); }

    @PostMapping("/preview")
    public Preview preview(@RequestBody JsonNode request) {
        return service.preview(owner.id(), decode(request, PreviewRequest.class));
    }

    @PostMapping("/relation-drafts/preview")
    public Preview previewRelationDraft(@RequestBody JsonNode request) {
        exactFields(request, RELATION_PREVIEW_FIELDS);
        relationDraftSources(request);
        return service.previewRelationDraft(owner.id(), decode(request, RelationDraftPreviewRequest.class));
    }

    @PostMapping(value = "/relation-drafts/tasks", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Task createRelationDraft(@RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        JsonNode request = draftBody(raw);
        createFields(request, RELATION_CREATE_FIELDS);
        relationDraftSources(request);
        return service.createRelationDraft(owner.id(), key, decode(request, CreateRelationDraft.class));
    }

    @PostMapping(value = "/relation-evidence-drafts/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Preview previewRelationEvidenceDraft(@RequestBody byte[] raw) {
        JsonNode request = draftBody(raw);
        exactFields(request, RELATION_EVIDENCE_PREVIEW_FIELDS);
        relationEvidenceFields(request);
        return service.previewRelationEvidenceDraft(owner.id(), decodeFactRequest(request, RelationEvidencePreviewRequest.class));
    }

    @PostMapping(value = "/relation-evidence-drafts/tasks", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Task createRelationEvidenceDraft(@RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        JsonNode request = draftBody(raw);
        createFields(request, RELATION_EVIDENCE_CREATE_FIELDS);
        relationEvidenceFields(request);
        if (!request.get("expectedManifestHash").isTextual() || !request.get("consentConfirmed").isBoolean()) throw invalid();
        return service.createRelationEvidenceDraft(owner.id(), key, decodeFactRequest(request, CreateRelationEvidenceDraft.class));
    }

    @PostMapping(value = "/relation-review-drafts/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Preview previewRelationReviewDraft(@RequestBody byte[] raw) {
        JsonNode request = draftBody(raw);
        exactFields(request, RELATION_REVIEW_PREVIEW_FIELDS);
        relationReviewFields(request);
        return service.previewRelationReviewDraft(owner.id(), decodeFactRequest(request, RelationReviewPreviewRequest.class));
    }

    @PostMapping(value = "/relation-review-drafts/tasks", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Task createRelationReviewDraft(@RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        JsonNode request = draftBody(raw);
        createFields(request, RELATION_REVIEW_CREATE_FIELDS);
        relationReviewFields(request);
        if (!request.get("expectedManifestHash").isTextual() || !request.get("consentConfirmed").isBoolean()) throw invalid();
        return service.createRelationReviewDraft(owner.id(), key, decodeFactRequest(request, CreateRelationReviewDraft.class));
    }

    @PostMapping(value = "/relation-ai-feedback-drafts/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Preview previewRelationAiFeedbackDraft(@RequestBody byte[] raw) {
        JsonNode request = draftBody(raw);
        exactFields(request, RELATION_REVIEW_PREVIEW_FIELDS);
        relationAiFeedbackFields(request);
        return service.previewRelationAiFeedbackDraft(owner.id(), decodeFactRequest(request, RelationAiFeedbackPreviewRequest.class));
    }

    @PostMapping(value = "/relation-ai-feedback-drafts/tasks", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Task createRelationAiFeedbackDraft(@RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        JsonNode request = draftBody(raw);
        createFields(request, RELATION_REVIEW_CREATE_FIELDS);
        relationAiFeedbackFields(request);
        if (!request.get("expectedManifestHash").isTextual() || !request.get("consentConfirmed").isBoolean()) throw invalid();
        return service.createRelationAiFeedbackDraft(owner.id(), key, decodeFactRequest(request, CreateRelationAiFeedbackDraft.class));
    }

    @GetMapping("/relation-drafts/tasks")
    public TaskPage relationGraphTasks(@RequestParam(name = "cursor", required = false) String cursor,
                                       @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return service.relationGraphTasks(owner.id(), cursor, limit);
    }

    @PostMapping("/preparation-drafts/preview")
    public Preview previewPreparationDraft(@RequestBody JsonNode request) {
        return service.previewPreparationDraft(owner.id(), decode(request, PreparationDraftPreviewRequest.class));
    }

    @PostMapping(value = "/preparation-drafts/tasks", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Task createPreparationDraft(@RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        JsonNode request = draftBody(raw);
        return service.createPreparationDraft(owner.id(), key, decode(request, CreatePreparationDraft.class));
    }

    @PostMapping("/material-drafts/preview")
    public Preview previewMaterialDraft(@RequestBody JsonNode request) {
        return service.previewMaterialDraft(owner.id(), decode(request, MaterialDraftPreviewRequest.class));
    }

    @PostMapping(value = "/material-drafts/tasks", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Task createMaterialDraft(@RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        JsonNode request = draftBody(raw);
        return service.createMaterialDraft(owner.id(), key, decode(request, CreateMaterialDraft.class));
    }

    @PostMapping("/training-drafts/preview")
    public Preview previewTrainingDraft(@RequestBody JsonNode request) {
        exactFields(request, TRAINING_DRAFT_PREVIEW_FIELDS);
        trainingDraftFields(request);
        return service.previewTrainingDraft(owner.id(), decode(request, TrainingDraftPreviewRequest.class));
    }

    @PostMapping(value = "/training-drafts/tasks", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Task createTrainingDraft(@RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        JsonNode request = draftBody(raw);
        createFields(request, TRAINING_DRAFT_CREATE_FIELDS);
        trainingDraftFields(request);
        return service.createTrainingDraft(owner.id(), key, decode(request, CreateTrainingDraft.class));
    }

    @PostMapping(value = "/review-drafts/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Preview previewReviewDraft(@RequestBody byte[] raw) {
        JsonNode request = draftBody(raw);
        exactFields(request, REVIEW_DRAFT_PREVIEW_FIELDS);
        reviewDraftFields(request);
        return service.previewReviewDraft(owner.id(), decode(request, ReviewDraftDtos.PreviewRequest.class));
    }

    @PostMapping(value = "/review-drafts/tasks", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Task createReviewDraft(@RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        JsonNode request = draftBody(raw);
        createFields(request, REVIEW_DRAFT_CREATE_FIELDS);
        reviewDraftFields(request);
        return service.createReviewDraft(owner.id(), decode(request, ReviewDraftDtos.CreateRequest.class), key);
    }

    @PostMapping(value = "/card-drafts/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Preview previewCardDraft(@RequestBody byte[] raw) {
        JsonNode request = draftBody(raw);
        exactFields(request, CARD_DRAFT_PREVIEW_FIELDS);
        relationDraftSources(request);
        return service.previewCardDraft(owner.id(), decode(request, CardDraftDtos.PreviewRequest.class));
    }

    @PostMapping(value = "/card-drafts/tasks", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Task createCardDraft(@RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        JsonNode request = draftBody(raw);
        createFields(request, CARD_DRAFT_CREATE_FIELDS);
        relationDraftSources(request);
        return service.createCardDraft(owner.id(), decode(request, CardDraftDtos.CreateRequest.class), key);
    }

    @PostMapping(value = "/tasks/{id}/adopt-card", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Task adoptCard(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                          @RequestBody byte[] raw) {
        JsonNode request = draftBody(raw);
        exactFields(request, CARD_DRAFT_ADOPT_FIELDS);
        JsonNode content = request.get("content");
        exactFields(content, CARD_CONTENT_FIELDS);
        relationDraftSources(content);
        JsonNode concepts = content.get("relatedConceptIds");
        if (!concepts.isArray()) throw invalid();
        for (JsonNode concept : concepts) if (!concept.isTextual()) throw invalid();
        return service.adoptCard(owner.id(), id, key, decode(request, CardDraftDtos.Adopt.class));
    }

    @PostMapping("/expression-drafts/preview")
    public Preview previewExpressionDraft(@RequestBody JsonNode request) {
        exactFields(request, EXPRESSION_PREVIEW_FIELDS);
        return service.previewExpressionDraft(owner.id(), decode(request, ExpressionDraftPreviewRequest.class));
    }

    @PostMapping(value = "/fact-drafts/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Preview previewFactDraft(@RequestBody byte[] raw) {
        JsonNode request = draftBody(raw);
        exactFields(request, FACT_DRAFT_PREVIEW_FIELDS);
        factDraftFields(request);
        return service.previewFactDraft(owner.id(), decodeFactRequest(request, FactDraftDtos.PreviewRequest.class));
    }

    @PostMapping(value = "/answer-rewrites/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Preview previewRewrite(@RequestBody byte[] raw) {
        JsonNode request = draftBody(raw);
        exactFields(request, REWRITE_PREVIEW_FIELDS);
        for (String field : REWRITE_PREVIEW_FIELDS) if (!request.get(field).isTextual()) throw invalid();
        return service.previewRewrite(owner.id(), decodeFactRequest(request, RewriteDtos.PreviewRequest.class));
    }

    @PostMapping(value = "/answer-rewrites/tasks", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Task createRewrite(@RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        JsonNode request = draftBody(raw);
        createFields(request, REWRITE_CREATE_FIELDS);
        for (String field : Set.of("sessionId", "questionId", "answerId", "expectedManifestHash")) {
            if (!request.get(field).isTextual()) throw invalid();
        }
        if (!request.get("consentConfirmed").isBoolean()) throw invalid();
        return service.createRewrite(owner.id(), key, decodeFactRequest(request, RewriteDtos.CreateRequest.class));
    }

    @GetMapping("/answer-rewrites/tasks")
    public EvaluationPage rewrites(@RequestParam("sessionId") String sessionId,
            @RequestParam(name = "questionId", required = false) String questionId,
            @RequestParam(name = "answerId", required = false) String answerId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return service.rewrites(owner.id(), sessionId, questionId, answerId, cursor, limit);
    }

    @PostMapping(value = "/fact-drafts/tasks", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Task createFactDraft(@RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        JsonNode request = draftBody(raw);
        createFields(request, FACT_DRAFT_CREATE_FIELDS);
        factDraftFields(request);
        if (!request.get("expectedManifestHash").isTextual() || !request.get("consentConfirmed").isBoolean()) throw invalid();
        return service.createFactDraft(owner.id(), decodeFactRequest(request, FactDraftDtos.CreateRequest.class), key);
    }

    @PostMapping(value = "/tasks/{id}/adopt-fact", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Task adoptFact(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                          @RequestBody byte[] raw) {
        JsonNode request = draftBody(raw);
        exactFields(request, FACT_DRAFT_ADOPT_FIELDS);
        for (String field : Set.of("expectedRevision", "expectedProjectRevision")) {
            JsonNode value = request.get(field);
            if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
        }
        for (String field : Set.of("projectId", "expectedProjectVersionId")) {
            if (!request.get(field).isTextual()) throw invalid();
        }
        if (!request.get("confirmed").isBoolean()) throw invalid();
        JsonNode content = request.get("content");
        exactFields(content, FACT_DRAFT_CONTENT_FIELDS);
        for (String field : Set.of("title", "scenario", "problem", "action", "result", "technology")) {
            if (!content.get(field).isTextual()) throw invalid();
        }
        if (!content.get("tags").isArray()) throw invalid();
        for (JsonNode tag : content.get("tags")) if (!tag.isTextual()) throw invalid();
        return service.adoptFact(owner.id(), id, key, decodeFactRequest(request, FactDraftDtos.Adopt.class));
    }

    @PostMapping(value = "/jd-requirements/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Preview previewJdRequirements(@RequestBody byte[] raw) {
        JsonNode request = draftBody(raw);
        exactFields(request, JD_REQUIREMENTS_PREVIEW_FIELDS);
        return service.previewJdRequirements(owner.id(), decode(request, JdRequirementsDtos.PreviewRequest.class));
    }

    @PostMapping(value = "/jd-requirements/tasks", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Task createJdRequirements(@RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        JsonNode request = draftBody(raw);
        createFields(request, JD_REQUIREMENTS_CREATE_FIELDS);
        return service.createJdRequirements(owner.id(), decode(request, JdRequirementsDtos.CreateRequest.class), key);
    }

    @PostMapping(value = "/tasks/{id}/adopt-requirements", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Task adoptRequirements(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                                  @RequestBody byte[] raw) {
        JsonNode request = draftBody(raw);
        exactFields(request, JD_REQUIREMENTS_ADOPT_FIELDS);
        // 新分支逐字段保留数字类型，不扩大旧用途请求的宽松解析范围。
        for (String field : Set.of("expectedRevision", "expectedTargetRevision")) {
            JsonNode value = request.get(field);
            if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
        }
        if (!request.get("confirmed").isBoolean()) throw invalid();
        JsonNode requirements = request.get("requirements");
        if (!requirements.isArray()) throw invalid();
        for (JsonNode requirement : requirements) {
            exactFields(requirement, JD_REQUIREMENTS_SELECTION_FIELDS);
            for (String field : Set.of("candidateNumber", "priority")) {
                JsonNode value = requirement.get(field);
                if (!value.isIntegralNumber() || !value.canConvertToInt()) throw invalid();
            }
            if (!requirement.get("text").isTextual() || !requirement.get("dimensions").isArray()) throw invalid();
            for (JsonNode dimension : requirement.get("dimensions")) if (!dimension.isTextual()) throw invalid();
        }
        JdRequirementsDtos.Adopt adoption;
        try { adoption = requests.treeToValue(request, JdRequirementsDtos.Adopt.class); }
        catch (RuntimeException exception) { throw invalid(); }
        return service.adoptRequirements(owner.id(), id, key, adoption);
    }

    @PostMapping(value = "/expression-drafts/tasks", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Task createExpressionDraft(@RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        JsonNode request = draftBody(raw);
        createFields(request, EXPRESSION_CREATE_FIELDS);
        return service.createExpressionDraft(owner.id(), key, decode(request, CreateExpressionDraft.class));
    }

    @PostMapping("/preparation-plans/preview")
    public Preview previewPreparationPlan(@RequestBody JsonNode request) {
        exactFields(request, PREPARATION_PLAN_PREVIEW_FIELDS);
        exactFields(request.get("target"), TARGET_LINK_FIELDS);
        exactFields(request.get("plan"), PREPARATION_PLAN_INPUT_FIELDS);
        return service.previewPreparationPlan(owner.id(), decode(request, PreparationPlanPreviewRequest.class));
    }

    @PostMapping(value = "/preparation-plans/tasks", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Task createPreparationPlan(@RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        JsonNode request = draftBody(raw);
        createFields(request, PREPARATION_PLAN_CREATE_FIELDS);
        exactFields(request.get("target"), TARGET_LINK_FIELDS);
        exactFields(request.get("plan"), PREPARATION_PLAN_INPUT_FIELDS);
        return service.createPreparationPlan(owner.id(), key, decode(request, CreatePreparationPlan.class));
    }

    @PostMapping("/material-qa/preview")
    public Preview previewMaterialQa(@RequestBody JsonNode request) {
        return service.previewMaterialQa(owner.id(), decode(request, MaterialQaPreviewRequest.class));
    }

    @PostMapping(value = "/material-qa/tasks", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Task createMaterialQa(@RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        JsonNode request = draftBody(raw);
        return service.createMaterialQa(owner.id(), key, decode(request, CreateMaterialQa.class));
    }

    @GetMapping("/tasks")
    public TaskPage tasks(@RequestParam(name = "cursor", required = false) String cursor,
                           @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return service.list(owner.id(), cursor, limit);
    }

    @PostMapping(value = "/tasks", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Task create(@RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        JsonNode request = draftBody(raw);
        return service.create(owner.id(), key, decode(request, CreateTask.class));
    }

    @GetMapping("/tasks/{id}")
    public Task task(@PathVariable("id") String id) { return service.detail(owner.id(), id); }

    @GetMapping("/tasks/{id}/relation-graph")
    public RelationGraphDtos.Projection relationGraph(@PathVariable("id") String id) {
        return service.relationGraph(owner.id(), id);
    }

    @PostMapping("/tasks/{id}/adopt")
    public Task adopt(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                      @RequestBody JsonNode request) {
        exactFields(request, ADOPT_FIELDS);
        return service.adopt(owner.id(), id, key, decode(request, AdoptCandidate.class));
    }

    @PostMapping("/tasks/{id}/adopt-question")
    public Task adoptQuestion(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                              @RequestBody JsonNode request) {
        exactFields(request, ADOPT_QUESTION_FIELDS);
        exactFields(request.get("content"), QUESTION_FIELDS);
        return service.adoptQuestion(owner.id(), id, key, decode(request, AdoptQuestion.class));
    }

    @PostMapping("/tasks/{id}/adopt-expression")
    public Task adoptExpression(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                                @RequestBody JsonNode request) {
        exactFields(request, ADOPT_EXPRESSION_FIELDS);
        exactFields(request.get("content"), EXPRESSION_CONTENT_FIELDS);
        return service.adoptExpression(owner.id(), id, key, decode(request, AdoptExpression.class));
    }

    @PostMapping("/tasks/{id}/adopt-preparation-plan")
    public Task adoptPreparationPlan(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                                    @RequestBody JsonNode request) {
        exactFields(request, ADOPT_PREPARATION_PLAN_FIELDS);
        exactFields(request.get("content"), PREPARATION_PLAN_CONTENT_FIELDS);
        JsonNode steps = request.get("content").get("steps");
        if (steps == null || !steps.isArray()) throw invalid();
        for (JsonNode step : steps) exactFields(step, PREPARATION_PLAN_STEP_FIELDS);
        return service.adoptPreparationPlan(owner.id(), id, key, decode(request, AdoptPreparationPlan.class));
    }

    @PostMapping("/tasks/{id}/relations/{candidateId}/adopt")
    public Task adoptRelation(@PathVariable("id") String id, @PathVariable("candidateId") String candidateId,
                              @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode request) {
        exactFields(request, ADOPT_RELATION_FIELDS);
        exactFields(request.get("content"), RELATION_CONTENT_FIELDS);
        return service.adoptRelation(owner.id(), id, candidateId, key, decode(request, AdoptRelation.class));
    }

    @PostMapping("/tasks/{id}/relations/{candidateId}/ignore")
    public Task ignoreRelation(@PathVariable("id") String id, @PathVariable("candidateId") String candidateId,
                               @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode request) {
        exactFields(request, CHANGE_TASK_FIELDS);
        return service.ignoreRelation(owner.id(), id, candidateId, key, decode(request, ChangeTask.class));
    }

    @PostMapping(value = "/tasks/{id}/relations/{candidateId}/supplement", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Task supplementRelation(@PathVariable("id") String id, @PathVariable("candidateId") String candidateId,
                                   @RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        JsonNode request = draftBody(raw);
        exactFields(request, SUPPLEMENT_RELATION_FIELDS);
        JsonNode expected = request.get("expectedRevision");
        if (!expected.isIntegralNumber() || !expected.canConvertToLong()
                || expected.longValue() < 0 || expected.longValue() > 9_007_199_254_740_991L
                || !request.get("relationId").isTextual() || !request.get("confirmed").isBoolean()) throw invalid();
        JsonNode content = request.get("content");
        exactFields(content, RELATION_CONTENT_FIELDS);
        for (String field : RELATION_CONTENT_FIELDS) if (!content.get(field).isTextual()) throw invalid();
        return service.supplementRelation(owner.id(), id, candidateId, key, decodeFactRequest(request, SupplementRelation.class));
    }

    @GetMapping("/relations/{relationId}/origin")
    public JsonNode relationOrigin(@PathVariable("relationId") String relationId) {
        // 无 AI 来源也返回可解析的 JSON null，避免空响应被客户端误判为查询失败。
        return requests.valueToTree(service.relationOrigin(owner.id(), relationId));
    }

    @PostMapping("/tasks/{id}/ignore")
    public Task ignore(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                       @RequestBody JsonNode request) {
        return service.ignore(owner.id(), id, key, decode(request, ChangeTask.class));
    }

    @PostMapping("/tasks/{id}/feedback/{feedbackId}/decisions")
    public Task decideFeedback(@PathVariable("id") String id, @PathVariable("feedbackId") String feedbackId,
                               @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode request) {
        return service.decideFeedback(owner.id(), id, feedbackId, key, decode(request, DecideFeedback.class));
    }

    @GetMapping("/knowledge/{itemId}/versions/{versionId}/origins")
    public OriginPage origins(@PathVariable("itemId") String itemId, @PathVariable("versionId") String versionId,
                              @RequestParam(name = "before", required = false) Integer before,
                              @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return service.origins(owner.id(), itemId, versionId, before, limit);
    }

    @GetMapping("/memory-cards/{cardId}/versions/{versionId}/origins")
    public OriginPage cardOrigins(@PathVariable("cardId") String cardId, @PathVariable("versionId") String versionId,
                                  @RequestParam(name = "before", required = false) Integer before,
                                  @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return service.cardOrigins(owner.id(), cardId, versionId, before, limit);
    }

    @GetMapping("/targets/{targetId}/versions/{versionId}/requirement-origins")
    public JdRequirementsDtos.OriginPage requirementOrigins(@PathVariable("targetId") String targetId,
            @PathVariable("versionId") String versionId,
            @RequestParam(name = "before", required = false) Integer before,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return service.requirementOrigins(owner.id(), targetId, versionId, before, limit);
    }

    @GetMapping("/facts/{factId}/versions/{versionId}/origins")
    public FactDraftDtos.OriginPage factOrigins(@PathVariable("factId") String factId,
            @PathVariable("versionId") String versionId,
            @RequestParam(name = "before", required = false) Integer before,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return service.factOrigins(owner.id(), factId, versionId, before, limit);
    }

    @GetMapping("/question-origins")
    public OriginPage questionOrigins(@RequestParam("questionId") String questionId,
                                      @RequestParam("versionId") String versionId,
                                      @RequestParam(name = "beforeVersion", required = false) Integer before,
                                      @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return service.questionOrigins(owner.id(), questionId, versionId, before, limit);
    }

    @GetMapping("/expression-origins")
    public OriginPage expressionOrigins(@RequestParam("expressionId") String expressionId,
                                        @RequestParam("versionId") String versionId,
                                        @RequestParam(name = "beforeVersion", required = false) Integer before,
                                        @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return service.expressionOrigins(owner.id(), expressionId, versionId, before, limit);
    }

    @PostMapping("/tasks/{id}/cancel")
    public Task cancel(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                         @RequestBody JsonNode request) {
        return service.cancel(owner.id(), id, key, decode(request, ChangeTask.class));
    }

    @PostMapping(value = "/tasks/{id}/retry", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Task retry(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                        @RequestBody byte[] raw) {
        JsonNode request = draftBody(raw);
        return service.retry(owner.id(), id, key, decode(request, RetryTask.class));
    }

    @PostMapping("/tasks/{id}/attempts/{attemptId}/reconcile")
    public Task reconcile(@PathVariable("id") String id, @PathVariable("attemptId") String attemptId,
                            @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode request) {
        return service.reconcile(owner.id(), id, attemptId, key, decode(request, Reconcile.class));
    }

    private <T> T decode(JsonNode body, Class<T> type) {
        if (body == null || !body.isObject()) throw invalid();
        authorizationFields(body);
        rejectCoercion(body);
        try { return requests.treeToValue(body, type); }
        catch (RuntimeException exception) { throw invalid(); }
    }

    private void rejectCoercion(JsonNode node) {
        // 金额、修订号与授权值必须保留客户端原始类型，不能把小数截断或把字符串解释成授权。
        for (var field : node.properties()) {
            JsonNode value = field.getValue();
            if (value == null || value.isNull()) continue;
            String name = field.getKey();
            if (INTEGERS.contains(name)) {
                if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
            } else if (BOOLEANS.contains(name)) {
                if (!value.isBoolean()) throw invalid();
            } else if (value.isObject()) rejectCoercion(value);
            else if (value.isArray()) {
                for (JsonNode entry : value) {
                    if (entry.isObject()) rejectCoercion(entry);
                    else if (!entry.isTextual()) throw invalid();
                }
            } else if (!value.isTextual()) throw invalid();
        }
    }

    private static void exactFields(JsonNode request, Set<String> fields) {
        if (request == null || !request.isObject() || request.size() != fields.size()
                || fields.stream().anyMatch(field -> !request.has(field))) throw invalid();
    }

    private static void createFields(JsonNode request, Set<String> requiredFields) {
        if (request == null || !request.isObject()
                || request.size() != requiredFields.size() + (request.has("authorization") ? 1 : 0)
                || requiredFields.stream().anyMatch(field -> !request.has(field))) throw invalid();
        authorizationFields(request);
    }

    private static void authorizationFields(JsonNode request) {
        JsonNode authorization = request.get("authorization");
        if (authorization == null || authorization.isNull()) return;
        exactFields(authorization, Set.of("id", "expectedRevision"));
        if (!authorization.get("id").isTextual() || !authorization.get("expectedRevision").isIntegralNumber()
                || !authorization.get("expectedRevision").canConvertToLong()) throw invalid();
    }

    private static void trainingDraftFields(JsonNode request) {
        JsonNode fragments = request.get("fragments");
        if (fragments == null || !fragments.isArray()) throw invalid();
        for (JsonNode fragment : fragments) exactFields(fragment, TRAINING_DRAFT_FRAGMENT_FIELDS);
        JsonNode feedback = request.get("feedback");
        if (feedback == null) throw invalid();
        if (!feedback.isNull()) {
            exactFields(feedback, TRAINING_DRAFT_FEEDBACK_FIELDS);
            if (!feedback.get("feedbackIds").isArray()) throw invalid();
        }
    }

    private static void factDraftFields(JsonNode request) {
        if (!request.get("instruction").isTextual()) throw invalid();
        JsonNode source = request.get("source");
        exactFields(source, FACT_DRAFT_SOURCE_FIELDS);
        if (!source.get("kind").isTextual() || !source.get("excerptIds").isArray()) throw invalid();
        for (JsonNode id : source.get("excerptIds")) if (!id.isTextual()) throw invalid();
        if ("MATERIAL".equals(source.get("kind").asText())) {
            if (!source.get("training").isNull()) throw invalid();
        } else if ("TRAINING".equals(source.get("kind").asText())) {
            if (!source.get("excerptIds").isEmpty()) throw invalid();
            JsonNode training = source.get("training");
            exactFields(training, FACT_DRAFT_TRAINING_FIELDS);
            for (String field : Set.of("sessionId", "questionId", "answerId")) {
                if (!training.get(field).isTextual()) throw invalid();
            }
            if (!training.get("fragments").isArray()) throw invalid();
            for (JsonNode fragment : training.get("fragments")) {
                exactFields(fragment, TRAINING_DRAFT_FRAGMENT_FIELDS);
                if (!fragment.get("field").isTextual() || !fragment.get("quote").isTextual()
                        || !(fragment.get("elementId").isNull() || fragment.get("elementId").isTextual())) throw invalid();
                for (String field : Set.of("start", "end")) {
                    JsonNode value = fragment.get(field);
                    if (!value.isIntegralNumber() || !value.canConvertToInt()) throw invalid();
                }
            }
        } else throw invalid();
    }

    private <T> T decodeFactRequest(JsonNode request, Class<T> type) {
        // 新用途在原始字节和逐字段校验之后解码，保留旧请求的字段及摘要协议。
        authorizationFields(request);
        try { return draftRequests.treeToValue(request, type); }
        catch (RuntimeException exception) { throw invalid(); }
    }

    private static void relationDraftSources(JsonNode request) {
        JsonNode sources = request.get("sources");
        if (sources == null || !sources.isArray()) throw invalid();
        for (JsonNode source : sources) exactFields(source, TARGET_LINK_FIELDS);
    }

    private static void relationEvidenceFields(JsonNode request) {
        factDraftFields(request);
        relationDraftSources(request);
        for (JsonNode source : request.get("sources")) {
            if (!source.get("itemId").isTextual() || !source.get("versionId").isTextual()) throw invalid();
        }
    }

    private static void relationReviewFields(JsonNode request) {
        if (!request.get("instruction").isTextual()) throw invalid();
        relationDraftSources(request);
        for (JsonNode source : request.get("sources")) {
            if (!source.get("itemId").isTextual() || !source.get("versionId").isTextual()) throw invalid();
        }
        reviewDraftFields(request);
        JsonNode source = request.get("source");
        boolean recollection = "RECOLLECTION".equals(source.get("kind").asText());
        JsonNode selected = source.get(recollection ? "recollection" : "feedback");
        if (recollection) {
            if (!selected.get("recollectionId").isTextual() || !selected.get("versionId").isTextual()
                    || !selected.get("expectedRevision").isIntegralNumber()
                    || !selected.get("expectedRevision").canConvertToLong()) throw invalid();
        } else if (!selected.get("feedbackId").isTextual() || !selected.get("feedbackRevision").isIntegralNumber()
                || !selected.get("feedbackRevision").canConvertToInt()) throw invalid();
        for (JsonNode fragment : selected.get("fragments")) {
            if (!fragment.get("field").isTextual() || !fragment.get("quote").isTextual()) throw invalid();
            for (String field : Set.of("start", "end")) {
                if (!fragment.get(field).isIntegralNumber() || !fragment.get(field).canConvertToInt()) throw invalid();
            }
        }
    }

    private static void reviewDraftFields(JsonNode request) {
        JsonNode source = request.get("source");
        exactFields(source, REVIEW_DRAFT_SOURCE_FIELDS);
        JsonNode kind = source.get("kind");
        if (!kind.isTextual()) throw invalid();
        JsonNode selected;
        if ("SELF_CHECK".equals(kind.asText()) || "EXTERNAL_FEEDBACK".equals(kind.asText())) {
            if (!source.get("recollection").isNull()) throw invalid();
            selected = source.get("feedback");
            exactFields(selected, REVIEW_DRAFT_FEEDBACK_FIELDS);
        } else if ("RECOLLECTION".equals(kind.asText())) {
            if (!source.get("feedback").isNull()) throw invalid();
            selected = source.get("recollection");
            exactFields(selected, REVIEW_DRAFT_RECOLLECTION_FIELDS);
        } else throw invalid();
        JsonNode fragments = selected.get("fragments");
        if (!fragments.isArray()) throw invalid();
        for (JsonNode fragment : fragments) exactFields(fragment, REVIEW_DRAFT_FRAGMENT_FIELDS);
    }

    private static void relationAiFeedbackFields(JsonNode request) {
        if (!request.get("instruction").isTextual()) throw invalid();
        relationDraftSources(request);
        for (JsonNode source : request.get("sources")) {
            if (!source.get("itemId").isTextual() || !source.get("versionId").isTextual()) throw invalid();
        }
        JsonNode source = request.get("source");
        exactFields(source, RELATION_AI_FEEDBACK_SOURCE_FIELDS);
        for (String field : Set.of("taskId", "attemptId", "feedbackId", "reportHash")) {
            if (!source.get(field).isTextual()) throw invalid();
        }
        if (!source.get("expectedDecisionRevision").isIntegralNumber()
                || !source.get("expectedDecisionRevision").canConvertToInt()) throw invalid();
        JsonNode answer = source.get("answer");
        exactFields(answer, RELATION_AI_FEEDBACK_ANSWER_FIELDS);
        for (String field : RELATION_AI_FEEDBACK_ANSWER_FIELDS) if (!answer.get(field).isTextual()) throw invalid();
        JsonNode fragments = source.get("fragments");
        if (!fragments.isArray() || fragments.isEmpty() || fragments.size() > 8) throw invalid();
        for (JsonNode fragment : fragments) {
            exactFields(fragment, REVIEW_DRAFT_FRAGMENT_FIELDS);
            if (!fragment.get("field").isTextual() || !fragment.get("quote").isTextual()) throw invalid();
            for (String field : Set.of("start", "end")) {
                if (!fragment.get(field).isIntegralNumber() || !fragment.get(field).canConvertToInt()) throw invalid();
            }
        }
    }

    private JsonNode draftBody(byte[] raw) {
        if (raw == null || raw.length == 0 || raw.length > 262144) throw invalid();
        try {
            JsonNode body = draftRequests.readTree(raw);
            if (body == null || !body.isObject()) throw invalid();
            return body;
        } catch (RuntimeException exception) { throw invalid(); }
    }

    private static ApiException invalid() { return new ApiException(400, "INVALID_REQUEST", "请求字段或类型不符合 AI 接口要求。"); }
}
