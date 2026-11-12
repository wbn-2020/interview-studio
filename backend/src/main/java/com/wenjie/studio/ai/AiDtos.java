package com.wenjie.studio.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wenjie.studio.bank.QuestionDtos;
import com.wenjie.studio.knowledge.KnowledgeDtos.Kind;
import com.wenjie.studio.knowledge.KnowledgeDtos.RelationType;
import com.wenjie.studio.knowledge.RelationAiFeedbackEvidenceDtos;
import com.wenjie.studio.material.MaterialDtos;
import com.wenjie.studio.material.MaterialLifecycleDtos;
import com.wenjie.studio.memory.MemoryDtos;
import com.wenjie.studio.prepare.PreparationDtos;
import com.wenjie.studio.training.TrainingDtos;

import java.time.Instant;
import java.util.List;

public final class AiDtos {
    private AiDtos() {}

    public enum Purpose { KNOWLEDGE_DRAFT, QUESTION_DRAFT, FOLLOW_UP, ANSWER_EVALUATION, MATERIAL_QA, EXPRESSION_DRAFT, PREPARATION_PLAN, RELATION_DRAFT, ANSWER_COMPARISON, MEMORY_CARD_DRAFT, JD_REQUIREMENT_DRAFT, FACT_DRAFT, ANSWER_REWRITE, SOURCE_QA }
    public enum Status { QUEUED, RUNNING, VALIDATING, SUCCEEDED, FAILED, UNKNOWN, CANCEL_REQUESTED, CANCELED }
    public enum FeeStatus { RESERVED, REPORTED, UNKNOWN, RELEASED, RECONCILED }
    public enum AdoptionMode { NEW, EXISTING }
    public enum DecisionStatus { ADOPTED, IGNORED }
    public enum OriginState { CURRENT, SUPERSEDED, ARCHIVED, UNAVAILABLE, WITHDRAWN, PROJECT_ARCHIVED, UNVERIFIED, REVIEW_REQUIRED }

    public record Configuration(boolean enabled, boolean configured, String protocol, String provider, String endpoint, String model,
                                String configVersion, String priceVersion, String currency,
                                long inputPriceMicrosPerMillion, long outputPriceMicrosPerMillion,
                                long dailyBudgetMicros, int maxOutputTokens, int timeoutSeconds,
                                int maxInputBytes, int maxAttempts, String maxTokensField, boolean jsonMode,
                                @JsonInclude(JsonInclude.Include.NON_NULL) Boolean streaming) {
        // 旧调用与历史清单保持缺省字段，关闭流式时不能新增 false 改变原配置和清单摘要。
        public Configuration(boolean enabled, boolean configured, String protocol, String provider, String endpoint, String model,
                             String configVersion, String priceVersion, String currency,
                             long inputPriceMicrosPerMillion, long outputPriceMicrosPerMillion,
                             long dailyBudgetMicros, int maxOutputTokens, int timeoutSeconds,
                             int maxInputBytes, int maxAttempts, String maxTokensField, boolean jsonMode) {
            this(enabled, configured, protocol, provider, endpoint, model, configVersion, priceVersion, currency,
                    inputPriceMicrosPerMillion, outputPriceMicrosPerMillion, dailyBudgetMicros, maxOutputTokens,
                    timeoutSeconds, maxInputBytes, maxAttempts, maxTokensField, jsonMode, null);
        }
    }
    public record Budget(String date, String currency, long limitMicros, long reportedMicros,
                         long reservedMicros, long unknownMicros, long remainingMicros) {}
    public record Settings(Configuration configuration, Budget budget) {}
    public record SourceLink(String itemId, String versionId) {}
    public record PreparationLink(PreparationDtos.Kind kind, String itemId, String versionId) {}
    public record Source(String itemId, String versionId, int number, String kind, String title, String body) {}
    public record PreviewRequest(Purpose purpose, String instruction, List<SourceLink> sources) {}
    public record PreparationDraftPreviewRequest(Purpose purpose, String instruction, List<SourceLink> sources,
                                                List<PreparationLink> preparationSources) {}
    public record CreatePreparationDraft(Purpose purpose, String instruction, List<SourceLink> sources,
                                         List<PreparationLink> preparationSources, String expectedManifestHash,
                                         Boolean consentConfirmed,
                                         @JsonInclude(JsonInclude.Include.NON_NULL) AiAuthorizationDtos.Use authorization) {}
    public record MaterialDraftPreviewRequest(Purpose purpose, String instruction, List<SourceLink> sources,
                                             List<PreparationLink> preparationSources, List<String> excerptIds) {}
    public record CreateMaterialDraft(Purpose purpose, String instruction, List<SourceLink> sources,
                                      List<PreparationLink> preparationSources, List<String> excerptIds,
                                      String expectedManifestHash, Boolean consentConfirmed,
                                      @JsonInclude(JsonInclude.Include.NON_NULL) AiAuthorizationDtos.Use authorization) {}
    public record ExpressionDraftPreviewRequest(PreparationDtos.ExpressionType type, String instruction,
                                               List<SourceLink> factSources) {}
    public record CreateExpressionDraft(PreparationDtos.ExpressionType type, String instruction,
                                        List<SourceLink> factSources, String expectedManifestHash,
                                        Boolean consentConfirmed,
                                        @JsonInclude(JsonInclude.Include.NON_NULL) AiAuthorizationDtos.Use authorization) {}
    public record PreparationPlanInput(String startDate, String endDate, String zoneId,
                                       Integer totalMinutes, String gapSummary) {}
    public record PreparationPlanPreviewRequest(SourceLink target, PreparationPlanInput plan, String instruction) {}
    public record CreatePreparationPlan(SourceLink target, PreparationPlanInput plan, String instruction,
                                        String expectedManifestHash, Boolean consentConfirmed,
                                        @JsonInclude(JsonInclude.Include.NON_NULL) AiAuthorizationDtos.Use authorization) {}
    public enum TrainingDraftField { QUESTION_PROMPT, QUESTION_CONDITIONS, ANSWER_TEXT, ANSWER_NODE_LABEL,
        ANSWER_NODE_DESCRIPTION, ANSWER_EDGE_LABEL, FEEDBACK_OBSERVATION, FEEDBACK_CONDITION,
        FEEDBACK_BASIS, FEEDBACK_LIMITATION, FEEDBACK_SUGGESTION }
    public record TrainingDraftFragment(TrainingDraftField field, String elementId, Integer start, Integer end, String quote) {}
    public record TrainingDraftFeedback(String taskId, List<String> feedbackIds) {}
    public record TrainingDraftPreviewRequest(String sessionId, String questionId, String answerId, String instruction,
                                              List<TrainingDraftFragment> fragments, TrainingDraftFeedback feedback) {}
    public record CreateTrainingDraft(String sessionId, String questionId, String answerId, String instruction,
                                      List<TrainingDraftFragment> fragments, TrainingDraftFeedback feedback,
                                      String expectedManifestHash, Boolean consentConfirmed,
                                      @JsonInclude(JsonInclude.Include.NON_NULL) AiAuthorizationDtos.Use authorization) {}
    public record TrainingDraftInput(String sessionId, String questionId, String answerId, int answerNumber,
                                     String answerCondition, String sourceQuestionId, String sourceVersionId,
                                     FollowUpQuestion question, List<TrainingDraftFragment> fragments,
                                     String feedbackTaskId, EvaluationLevel feedbackLevel, String feedbackLimitations,
                                     List<EvaluationObservation> feedback) {}
    public record TrainingDraftCitation(TrainingDraftField field, String elementId, int start, int end, String quote) {}
    public record TrainingDraftOrigin(String sessionId, String questionId, String answerId, int answerNumber,
                                      String questionTitle, String feedbackTaskId, List<String> feedbackIds, OriginState state) {}
    public record Manifest(Purpose purpose, String instruction, List<Source> sources, Configuration configuration,
                           int inputTokenAllowance, long reservedMicros, String promptVersion, FollowUpInput training,
                           EvaluationInput evaluation, List<TrainingDtos.ContextCheck> contextChecks,
                           MaterialQaInput materialQa, List<PreparationDtos.SourceCheck> preparationChecks,
                           List<MaterialDtos.AiExcerpt> materialDraftSources, PreparationDtos.ExpressionType expressionType,
                           PreparationPlanInput preparationPlan, TrainingDraftInput trainingDraft,
                           List<PreparationDtos.ExpressionCheck> expressionChecks, ComparisonDtos.Input comparisonInput,
                           ComparisonDtos.Checks comparisonChecks, ReviewDraftDtos.Input reviewDraft,
                           MemoryDtos.Kind cardDraftType, FactDraftDtos.Input factDraft,
                           FactDraftDtos.Input relationEvidence,
                           RelationAiFeedbackEvidenceDtos.Snapshot relationAiFeedback,
                           @JsonInclude(JsonInclude.Include.NON_NULL) SourceQaDtos.Input sourceQa,
                           @JsonInclude(JsonInclude.Include.NON_NULL) List<MaterialLifecycleDtos.Check> materialChecks,
                           @JsonInclude(JsonInclude.Include.NON_NULL) List<MaterialLifecycleDtos.Check> knowledgeMaterialChecks) {
        public Manifest(Purpose purpose, String instruction, List<Source> sources, Configuration configuration,
                        int inputTokenAllowance, long reservedMicros, String promptVersion, FollowUpInput training,
                        EvaluationInput evaluation, List<TrainingDtos.ContextCheck> contextChecks,
                        MaterialQaInput materialQa, List<PreparationDtos.SourceCheck> preparationChecks,
                        List<MaterialDtos.AiExcerpt> materialDraftSources, PreparationDtos.ExpressionType expressionType,
                        PreparationPlanInput preparationPlan, TrainingDraftInput trainingDraft,
                        List<PreparationDtos.ExpressionCheck> expressionChecks, ComparisonDtos.Input comparisonInput,
                        ComparisonDtos.Checks comparisonChecks, ReviewDraftDtos.Input reviewDraft,
                        MemoryDtos.Kind cardDraftType, FactDraftDtos.Input factDraft,
                        FactDraftDtos.Input relationEvidence, RelationAiFeedbackEvidenceDtos.Snapshot relationAiFeedback,
                        SourceQaDtos.Input sourceQa, List<MaterialLifecycleDtos.Check> materialChecks) {
            this(purpose, instruction, sources, configuration, inputTokenAllowance, reservedMicros, promptVersion,
                    training, evaluation, contextChecks, materialQa, preparationChecks, materialDraftSources,
                    expressionType, preparationPlan, trainingDraft, expressionChecks, comparisonInput,
                    comparisonChecks, reviewDraft, cardDraftType, factDraft, relationEvidence, relationAiFeedback,
                    sourceQa, materialChecks, null);
        }
        public Manifest(Purpose purpose, String instruction, List<Source> sources, Configuration configuration,
                        int inputTokenAllowance, long reservedMicros, String promptVersion, FollowUpInput training,
                        EvaluationInput evaluation, List<TrainingDtos.ContextCheck> contextChecks,
                        MaterialQaInput materialQa, List<PreparationDtos.SourceCheck> preparationChecks,
                        List<MaterialDtos.AiExcerpt> materialDraftSources, PreparationDtos.ExpressionType expressionType,
                        PreparationPlanInput preparationPlan, TrainingDraftInput trainingDraft,
                        List<PreparationDtos.ExpressionCheck> expressionChecks, ComparisonDtos.Input comparisonInput,
                        ComparisonDtos.Checks comparisonChecks, ReviewDraftDtos.Input reviewDraft,
                        MemoryDtos.Kind cardDraftType, FactDraftDtos.Input factDraft,
                        FactDraftDtos.Input relationEvidence, RelationAiFeedbackEvidenceDtos.Snapshot relationAiFeedback,
                        SourceQaDtos.Input sourceQa) {
            this(purpose, instruction, sources, configuration, inputTokenAllowance, reservedMicros, promptVersion,
                    training, evaluation, contextChecks, materialQa, preparationChecks, materialDraftSources,
                    expressionType, preparationPlan, trainingDraft, expressionChecks, comparisonInput,
                    comparisonChecks, reviewDraft, cardDraftType, factDraft, relationEvidence, relationAiFeedback,
                    sourceQa, null);
        }
        public Manifest(Purpose purpose, String instruction, List<Source> sources, Configuration configuration,
                        int inputTokenAllowance, long reservedMicros, String promptVersion, FollowUpInput training,
                        EvaluationInput evaluation, List<TrainingDtos.ContextCheck> contextChecks,
                        MaterialQaInput materialQa, List<PreparationDtos.SourceCheck> preparationChecks,
                        List<MaterialDtos.AiExcerpt> materialDraftSources, PreparationDtos.ExpressionType expressionType,
                        PreparationPlanInput preparationPlan, TrainingDraftInput trainingDraft,
                        List<PreparationDtos.ExpressionCheck> expressionChecks, ComparisonDtos.Input comparisonInput,
                        ComparisonDtos.Checks comparisonChecks, ReviewDraftDtos.Input reviewDraft,
                        MemoryDtos.Kind cardDraftType, FactDraftDtos.Input factDraft,
                        FactDraftDtos.Input relationEvidence, RelationAiFeedbackEvidenceDtos.Snapshot relationAiFeedback) {
            this(purpose, instruction, sources, configuration, inputTokenAllowance, reservedMicros, promptVersion,
                    training, evaluation, contextChecks, materialQa, preparationChecks, materialDraftSources,
                    expressionType, preparationPlan, trainingDraft, expressionChecks, comparisonInput,
                    comparisonChecks, reviewDraft, cardDraftType, factDraft, relationEvidence, relationAiFeedback, null);
        }
        public Manifest(Purpose purpose, String instruction, List<Source> sources, Configuration configuration,
                        int inputTokenAllowance, long reservedMicros, String promptVersion, FollowUpInput training,
                        EvaluationInput evaluation, List<TrainingDtos.ContextCheck> contextChecks,
                        MaterialQaInput materialQa, List<PreparationDtos.SourceCheck> preparationChecks,
                        List<MaterialDtos.AiExcerpt> materialDraftSources, PreparationDtos.ExpressionType expressionType,
                        PreparationPlanInput preparationPlan, TrainingDraftInput trainingDraft,
                        List<PreparationDtos.ExpressionCheck> expressionChecks, ComparisonDtos.Input comparisonInput,
                        ComparisonDtos.Checks comparisonChecks, ReviewDraftDtos.Input reviewDraft,
                        MemoryDtos.Kind cardDraftType, FactDraftDtos.Input factDraft, FactDraftDtos.Input relationEvidence) {
            this(purpose, instruction, sources, configuration, inputTokenAllowance, reservedMicros, promptVersion,
                    training, evaluation, contextChecks, materialQa, preparationChecks, materialDraftSources,
                    expressionType, preparationPlan, trainingDraft, expressionChecks, comparisonInput,
                    comparisonChecks, reviewDraft, cardDraftType, factDraft, relationEvidence, null);
        }
        // 旧用途的构造入口保留；新增关系依据只由专用预览入口显式提供。
        public Manifest(Purpose purpose, String instruction, List<Source> sources, Configuration configuration,
                        int inputTokenAllowance, long reservedMicros, String promptVersion, FollowUpInput training,
                        EvaluationInput evaluation, List<TrainingDtos.ContextCheck> contextChecks,
                        MaterialQaInput materialQa, List<PreparationDtos.SourceCheck> preparationChecks,
                        List<MaterialDtos.AiExcerpt> materialDraftSources, PreparationDtos.ExpressionType expressionType,
                        PreparationPlanInput preparationPlan, TrainingDraftInput trainingDraft,
                        List<PreparationDtos.ExpressionCheck> expressionChecks, ComparisonDtos.Input comparisonInput,
                        ComparisonDtos.Checks comparisonChecks, ReviewDraftDtos.Input reviewDraft,
                        MemoryDtos.Kind cardDraftType, FactDraftDtos.Input factDraft) {
            this(purpose, instruction, sources, configuration, inputTokenAllowance, reservedMicros, promptVersion,
                    training, evaluation, contextChecks, materialQa, preparationChecks, materialDraftSources,
                    expressionType, preparationPlan, trainingDraft, expressionChecks, comparisonInput,
                    comparisonChecks, reviewDraft, cardDraftType, factDraft, null);
        }
    }
    public record Preview(Manifest manifest, String manifestHash, Budget budget) {}
    public record CreateTask(Purpose purpose, String instruction, List<SourceLink> sources,
                             String expectedManifestHash, Boolean consentConfirmed,
                             @JsonInclude(JsonInclude.Include.NON_NULL) AiAuthorizationDtos.Use authorization) {}
    public record RelationDraftPreviewRequest(String instruction, List<SourceLink> sources) {}
    public record CreateRelationDraft(String instruction, List<SourceLink> sources, String expectedManifestHash,
                                      Boolean consentConfirmed,
                                      @JsonInclude(JsonInclude.Include.NON_NULL) AiAuthorizationDtos.Use authorization) {}
    public record RelationEvidencePreviewRequest(String instruction, List<SourceLink> sources,
                                                 FactDraftDtos.Selection source) {}
    public record CreateRelationEvidenceDraft(String instruction, List<SourceLink> sources,
                                               FactDraftDtos.Selection source, String expectedManifestHash,
                                               Boolean consentConfirmed,
                                               @JsonInclude(JsonInclude.Include.NON_NULL) AiAuthorizationDtos.Use authorization) {}
    public record RelationReviewPreviewRequest(String instruction, List<SourceLink> sources,
                                                ReviewDraftDtos.SourceSelection source) {}
    public record CreateRelationReviewDraft(String instruction, List<SourceLink> sources,
                                              ReviewDraftDtos.SourceSelection source, String expectedManifestHash,
                                              Boolean consentConfirmed,
                                              @JsonInclude(JsonInclude.Include.NON_NULL) AiAuthorizationDtos.Use authorization) {}
    public record RelationAiFeedbackPreviewRequest(String instruction, List<SourceLink> sources,
                                                   RelationAiFeedbackEvidenceDtos.Selection source) {}
    public record CreateRelationAiFeedbackDraft(String instruction, List<SourceLink> sources,
                                                RelationAiFeedbackEvidenceDtos.Selection source,
                                                String expectedManifestHash, Boolean consentConfirmed,
                                                @JsonInclude(JsonInclude.Include.NON_NULL) AiAuthorizationDtos.Use authorization) {}
    public record RelationSuggestion(String id, String sourceItemId, String targetItemId, RelationType type,
                                     String reason, List<Citation> citations, List<MaterialCitation> materialCitations,
                                     List<TrainingDraftCitation> trainingCitations, List<ReviewDraftDtos.Citation> reviewCitations) {
        public RelationSuggestion(String id, String sourceItemId, String targetItemId, RelationType type,
                                  String reason, List<Citation> citations, List<MaterialCitation> materialCitations,
                                  List<TrainingDraftCitation> trainingCitations) {
            this(id, sourceItemId, targetItemId, type, reason, citations, materialCitations, trainingCitations, null);
        }
        public RelationSuggestion(String id, String sourceItemId, String targetItemId, RelationType type,
                                  String reason, List<Citation> citations) {
            this(id, sourceItemId, targetItemId, type, reason, citations, null, null, null);
        }
    }
    public record RelationCandidate(String summary, String limitations, List<RelationSuggestion> relations,
                                    String validationNote) {}
    public record RelationContent(String sourceItemId, String targetItemId, RelationType type, String reason) {}
    public record AdoptRelation(Long expectedRevision, RelationContent content, Boolean confirmed) {}
    public record SupplementRelation(Long expectedRevision, String relationId, RelationContent content, Boolean confirmed) {}
    public record RelationDecision(String candidateId, DecisionStatus status, String relationId,
                                   RelationContent content, Instant createdAt, String supplementId) {}
    public record RelationOrigin(String taskId, RelationSuggestion candidate, RelationDecision decision,
                                 List<OriginSource> sources, boolean relationExists,
                                 FactDraftDtos.Input relationEvidence, OriginState relationEvidenceState,
                                 ReviewDraftDtos.Input reviewDraft, OriginState reviewDraftState,
                                 RelationAiFeedbackEvidenceDtos.Snapshot relationAiFeedback, OriginState relationAiFeedbackState) {
        public RelationOrigin(String taskId, RelationSuggestion candidate, RelationDecision decision,
                              List<OriginSource> sources, boolean relationExists,
                              FactDraftDtos.Input relationEvidence, OriginState relationEvidenceState,
                              ReviewDraftDtos.Input reviewDraft, OriginState reviewDraftState) {
            this(taskId, candidate, decision, sources, relationExists, relationEvidence, relationEvidenceState,
                    reviewDraft, reviewDraftState, null, null);
        }
        public RelationOrigin(String taskId, RelationSuggestion candidate, RelationDecision decision,
                              List<OriginSource> sources, boolean relationExists,
                              FactDraftDtos.Input relationEvidence, OriginState relationEvidenceState) {
            this(taskId, candidate, decision, sources, relationExists, relationEvidence, relationEvidenceState, null, null);
        }
        public RelationOrigin(String taskId, RelationSuggestion candidate, RelationDecision decision,
                              List<OriginSource> sources, boolean relationExists) {
            this(taskId, candidate, decision, sources, relationExists, null, null, null, null);
        }
    }
    public record RetryTask(Long expectedRevision, Boolean consentConfirmed, Boolean acknowledgeUnknownCost,
                            @JsonInclude(JsonInclude.Include.NON_NULL) AiAuthorizationDtos.Use authorization) {}
    public record ChangeTask(Long expectedRevision) {}
    public record AdoptCandidate(Long expectedRevision, AdoptionMode mode, Kind kind, String targetItemId,
                                 String expectedTargetVersionId, Long expectedTargetDraftRevision,
                                 String title, String body, Boolean confirmed) {}
    public record AdoptQuestion(Long expectedRevision, QuestionDtos.QuestionInput content, Boolean confirmed) {}
    public record AdoptExpression(Long expectedRevision, AdoptionMode mode, String targetItemId,
                                  String expectedTargetVersionId, Long expectedTargetRevision,
                                  PreparationDtos.ExpressionContent content, Boolean confirmed) {}
    public record PreparationPlanStep(String date, String title, Integer minutes, String detail) {}
    public record PreparationPlanContent(String title, String summary, List<PreparationPlanStep> steps) {}
    public record PreparationPlanCandidate(String title, String summary, List<PreparationPlanStep> steps,
                                           String limitations, String validationNote) {}
    public record AdoptPreparationPlan(Long expectedRevision, PreparationPlanContent content, Boolean confirmed) {}
    public record PreparationPlanTaskLink(int step, String taskId) {}
    public record PreparationPlanDecision(DecisionStatus status, PreparationPlanContent content,
                                          List<PreparationPlanTaskLink> tasks, Instant createdAt) {}
    public record Reconcile(Long expectedRevision, Long amountMicros, String note) {}
    public record Summary(String id, Purpose purpose, Status status, long revision, String title,
                          String provider, String model, String currency, int attemptCount,
                          Instant createdAt, Instant updatedAt, String errorCode) {}
    public record Attempt(String id, int number, Status status, FeeStatus feeStatus, long reservedMicros,
                          Long accountedMicros, Integer promptTokens, Integer completionTokens,
                          String providerRequestId, String errorCode, String reconciliationNote,
                          Instant createdAt, Instant sentAt, Instant finishedAt) {}
    public record Task(Summary task, Manifest manifest, List<Attempt> attempts, Candidate candidate,
                       boolean sourcesCurrent, CandidateDecision decision,
                       QuestionCandidate questionCandidate, QuestionDecision questionDecision, FollowUpLink followUp,
                       EvaluationLink evaluation, EvaluationReport evaluationReport, List<FeedbackDecision> evaluationDecisions,
                       MaterialAnswer materialAnswer, ExpressionCandidate expressionCandidate,
                       ExpressionDecision expressionDecision, PreparationPlanCandidate preparationPlanCandidate,
                       PreparationPlanDecision preparationPlanDecision, RelationCandidate relationCandidate,
                       List<RelationDecision> relationDecisions, ComparisonDtos.Link comparison,
                       ComparisonDtos.Candidate comparisonCandidate, CardDraftDtos.Candidate cardCandidate,
                       CardDraftDtos.Decision cardDecision, JdRequirementsDtos.Candidate jdRequirementsCandidate,
                       JdRequirementsDtos.Decision jdRequirementsDecision, FactDraftDtos.Candidate factCandidate,
                        FactDraftDtos.Decision factDecision, EvaluationLink rewrite, RewriteDtos.Candidate rewriteCandidate,
                        @JsonInclude(JsonInclude.Include.NON_NULL) SourceQaDtos.Answer sourceQaAnswer,
                        @JsonInclude(JsonInclude.Include.NON_NULL) String sourceQaManifestHash) {
        public Task(Summary task, Manifest manifest, List<Attempt> attempts, Candidate candidate,
                    boolean sourcesCurrent, CandidateDecision decision, QuestionCandidate questionCandidate,
                    QuestionDecision questionDecision, FollowUpLink followUp, EvaluationLink evaluation,
                    EvaluationReport evaluationReport, List<FeedbackDecision> evaluationDecisions, MaterialAnswer materialAnswer,
                    ExpressionCandidate expressionCandidate, ExpressionDecision expressionDecision,
                    PreparationPlanCandidate preparationPlanCandidate, PreparationPlanDecision preparationPlanDecision,
                    RelationCandidate relationCandidate, List<RelationDecision> relationDecisions,
                    ComparisonDtos.Link comparison, ComparisonDtos.Candidate comparisonCandidate,
                    CardDraftDtos.Candidate cardCandidate, CardDraftDtos.Decision cardDecision,
                    JdRequirementsDtos.Candidate jdRequirementsCandidate, JdRequirementsDtos.Decision jdRequirementsDecision,
                    FactDraftDtos.Candidate factCandidate, FactDraftDtos.Decision factDecision,
                    EvaluationLink rewrite, RewriteDtos.Candidate rewriteCandidate) {
            this(task, manifest, attempts, candidate, sourcesCurrent, decision, questionCandidate, questionDecision,
                    followUp, evaluation, evaluationReport, evaluationDecisions, materialAnswer, expressionCandidate,
                    expressionDecision, preparationPlanCandidate, preparationPlanDecision, relationCandidate,
                    relationDecisions, comparison, comparisonCandidate, cardCandidate, cardDecision,
                    jdRequirementsCandidate, jdRequirementsDecision, factCandidate, factDecision, rewrite,
                    rewriteCandidate, null, null);
        }
    }
    public record CandidateDecision(DecisionStatus status, AdoptionMode mode, String targetItemId,
                                    String targetVersionId, Instant createdAt) {}
    public record QuestionDecision(DecisionStatus status, String targetQuestionId, String targetVersionId,
                                  Instant createdAt) {}
    public record ExpressionDecision(DecisionStatus status, AdoptionMode mode, String targetItemId,
                                     String targetVersionId, Instant createdAt) {}
    public record OriginSource(String itemId, String versionId, int number, String title, OriginState state, String kind) {}
    public record MaterialOriginSource(String excerptId, String attachmentId, String fileName, String sha256,
                                       String origin, String runId, String segmentId, String locator,
                                       Integer startOffset, Integer endOffset, OriginState state) {}
    public record Origin(String taskId, String targetVersionId, int targetVersionNumber, Instant adoptedAt,
                         boolean direct, List<OriginSource> sources, List<MaterialOriginSource> materialSources,
                         TrainingDraftOrigin trainingSource, ReviewDraftDtos.Origin reviewSource) {}
    public record OriginPage(List<Origin> items, Integer nextCursor) {}
    public record TaskPage(List<Summary> items, String nextCursor) {}
    public record Citation(String itemId, String versionId, int start, int end, String quote) {}
    public record Section(String heading, String body, List<Citation> citations, List<MaterialCitation> materialCitations,
                          List<TrainingDraftCitation> trainingCitations, List<ReviewDraftDtos.Citation> reviewCitations) {}
    public record Candidate(String title, List<Section> sections, String validationNote) {}
    public record QuestionCandidate(String title, String prompt, String conditions, String technology,
                                    String scenario, QuestionDtos.Type type, Integer difficulty,
                                    List<QuestionDtos.Dimension> dimensions, String referenceAnswer,
                                    List<String> checkpoints, List<Citation> citations, String validationNote,
                                    List<MaterialCitation> materialCitations) {}
    public record ExpressionCandidate(String title, PreparationDtos.ExpressionType type, String body,
                                      List<Citation> citations, String validationNote) {}
    public record FollowUpPreviewRequest(String answerId) {}
    public record CreateFollowUp(String answerId, String expectedManifestHash, Boolean consentConfirmed,
                                @JsonInclude(JsonInclude.Include.NON_NULL) AiAuthorizationDtos.Use authorization) {}
    public record FollowUpQuestion(String title, String prompt, String conditions, String technology,
                                   String scenario, QuestionDtos.Type type) {}
    public record FollowUpNode(String id, String label, String description) {}
    public record FollowUpEdge(String id, String source, String target, String label) {}
    public record FollowUpAnswer(String text, List<FollowUpNode> nodes, List<FollowUpEdge> edges) {}
    public record FollowUpContext(String kind, String itemId, String versionId, int number, String title, String body) {}
    public record FollowUpInput(String sessionId, String questionId, String answerId, String rootQuestionId,
                                long generation, int answerNumber, String answerCondition, FollowUpQuestion question,
                                FollowUpAnswer answer, List<FollowUpContext> context) {}
    public record FollowUpCitation(String answerId, int start, int end, String quote) {}
    public record FollowUpCandidate(String title, String prompt, String conditions,
                                    List<FollowUpCitation> citations, String validationNote) {}
    public record FollowUpLink(String sessionId, String questionId, String answerId, String rootQuestionId,
                               String generatedQuestionId) {}
    public record FollowUpTask(Summary task, Manifest manifest, List<Attempt> attempts,
                               boolean eligible, String generatedQuestionId) {}
    public record FollowUpPage(List<Summary> items, String nextCursor) {}

    public enum MaterialQaMode { MATERIAL, GENERAL }
    public enum MaterialAnswerState { SUPPORTED, INSUFFICIENT, GENERAL }
    public record MaterialQaPreviewRequest(MaterialQaMode mode, String question, List<String> excerptIds) {}
    public record CreateMaterialQa(MaterialQaMode mode, String question, List<String> excerptIds,
                                   String expectedManifestHash, Boolean consentConfirmed,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) AiAuthorizationDtos.Use authorization) {}
    public record MaterialQaInput(MaterialQaMode mode, String question, List<MaterialDtos.AiExcerpt> excerpts) {}
    public record MaterialCitation(String excerptId, int start, int end, String quote) {}
    public record MaterialAnswer(MaterialAnswerState state, String answer, String limitations,
                                 List<MaterialCitation> citations, String validationNote) {}

    public enum EvaluationLevel { AI_UNREVIEWED }
    public enum EvaluationCoverage { MENTIONED, NOT_MENTIONED, UNCERTAIN }
    public enum EvaluationEvidenceKind { ANSWER_TEXT, ANSWER_NODE_LABEL, ANSWER_NODE_DESCRIPTION,
        ANSWER_EDGE_LABEL, QUESTION_PROMPT, QUESTION_CONDITIONS, REFERENCE, CHECKPOINT }
    public enum FeedbackChoice { ACCEPTED, DISPUTED, IGNORED }
    public record EvaluationAccessRequest(String answerId, Long expectedSessionRevision, Boolean confirmed,
                                          Boolean earlyFeedbackConfirmed) {}
    public record EvaluationAccess(String sessionId, String questionId, String answerId, boolean allowed,
                                   boolean earlyFeedback, Instant grantedAt) {}
    public record EvaluationPreviewRequest(String answerId) {}
    public record CreateEvaluation(String answerId, String expectedManifestHash, Boolean consentConfirmed,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) AiAuthorizationDtos.Use authorization) {}
    public record EvaluationStandard(String questionId, String versionId, String status, String referenceAnswer,
                                     List<QuestionDtos.Checkpoint> checkpoints) {}
    public record EvaluationInput(String sessionId, String questionId, String answerId, int answerNumber,
                                  String mode, String feedbackTiming, String answerCondition,
                                  FollowUpQuestion question, FollowUpAnswer answer, EvaluationStandard standard) {}
    public record EvaluationCitation(EvaluationEvidenceKind kind, String elementId, int start, int end, String quote) {}
    public record EvaluationObservation(String id, QuestionDtos.Dimension dimension, EvaluationCoverage coverage,
                                        String observation, String condition, String basis, String limitation,
                                        String suggestion, List<EvaluationCitation> citations) {}
    public record EvaluationReport(EvaluationLevel level, String summary, String limitations,
                                   List<EvaluationObservation> observations, String validationNote) {}
    public record EvaluationLink(String sessionId, String questionId, String answerId, int answerNumber) {}
    public record DecideFeedback(Long expectedRevision, FeedbackChoice choice, String reason) {}
    public record FeedbackDecision(String id, String feedbackId, int revision, FeedbackChoice choice,
                                   String reason, Instant createdAt) {}
    public record EvaluationTaskSummary(Summary task, String questionId, String answerId, int answerNumber) {}
    public record EvaluationPage(List<EvaluationTaskSummary> items, String nextCursor) {}
    public record EvaluationCoverageItem(String questionId, String title, String status, int answerCount,
                                         String latestAnswerId, int taskCount, int completedCount,
                                         int pendingCount, int failedCount) {}
    public record EvaluationSessionCoverage(List<EvaluationCoverageItem> items) {}
}
