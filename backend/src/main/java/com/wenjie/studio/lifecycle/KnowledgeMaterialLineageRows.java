package com.wenjie.studio.lifecycle;

import java.time.Instant;

public final class KnowledgeMaterialLineageRows {
    private KnowledgeMaterialLineageRows() {}

    public record VersionRow(String ownerId, String itemId, String versionId, Integer versionNumber,
                             String title, String body, String itemOwnerId, String itemKind, String itemStatus) {}
    public record AdoptionRow(String ownerId, String taskId, String status, String mode,
                              String targetItemId, String targetVersionId, String taskOwnerId, String actualTaskId,
                              String purpose, String taskStatus, String manifestJson, String manifestHash,
                              String itemOwnerId, String actualItemId, String itemKind, String itemStatus,
                              String versionOwnerId, String versionItemId, String actualVersionId,
                              Integer targetVersionNumber, String targetProjectId, String actualProjectId,
                              Instant taskCreatedAt) {}
    public record KnowledgeBinding(String ownerId, String taskId, String itemId, String versionId,
                                   Integer versionNumber, String title, Integer ordinal) {}
    public record MaterialBinding(String ownerId, String taskId, String excerptId, Integer ordinal,
                                  String excerptOwnerId, String actualExcerptId, String attachmentId,
                                  String attachmentSha256, String attachmentOwnerId, String actualAttachmentId,
                                  String fileName, String actualSha256, String origin, String runId, String segmentId,
                                  String locator, String text, Integer startOffset, Integer endOffset, String kind) {}
    public record PreparationBinding(String ownerId, String taskId, String itemId, String versionId,
                                     String kind, Integer versionNumber, String title, Integer ordinal) {}
    public record PreparationVersionRow(String ownerId, String itemId, String versionId, Integer versionNumber,
                                        String title, String contentJson, String itemOwnerId, String itemKind,
                                        String itemStatus, String projectId, String projectOwnerId,
                                        String actualProjectId, String projectKind) {}
    public record ProjectGenerationRow(String ownerId, String itemId, String kind, String status,
                                       String projectId, String verification, Long sourceGeneration, Long revision,
                                       String currentVersionId, Integer versionNumber, String versionOwnerId,
                                       String versionItemId, String actualVersionId, Integer actualVersionNumber) {}
    public record ProjectGenerationEventRow(String ownerId, String id, String rootItemId, String rootKind,
                                            String rootTitle, String rootProjectId, String changeKind,
                                            String previousVersionId, Integer previousVersionNumber,
                                            Long previousGeneration, Long previousRevision, String previousStatus,
                                            String previousVerification, String currentVersionId,
                                            Integer currentVersionNumber, Long currentGeneration, Long currentRevision,
                                            String currentStatus, String currentVerification, Instant createdAt,
                                            String previousOwnerId, String previousItemId, String actualPreviousId,
                                            Integer actualPreviousNumber, String previousTitle, String currentOwnerId,
                                            String currentItemId, String actualCurrentId, Integer actualCurrentNumber) {}
    public record PreparationMaterialBinding(String ownerId, String itemId, String versionId, String excerptId,
                                             String excerptOwnerId, String actualExcerptId, String attachmentId,
                                             String attachmentSha256, String attachmentOwnerId,
                                             String actualAttachmentId, String actualSha256) {}
    public record PreparationReferenceBinding(String ownerId, String targetItemId, String targetVersionId,
                                              String sourceKind, String preparationItemId,
                                              String preparationVersionId, String knowledgeItemId,
                                              String knowledgeVersionId, String collectionItemId,
                                              String collectionVersionId, String useType) {}
    public record FactSourceBinding(String ownerId, String taskId, String kind, String sessionId,
                                    String questionId, String answerId) {}
    public record TrainingSourceBinding(String ownerId, String taskId, String sessionId, String questionId,
                                        String answerId, String evaluationTaskId) {}
    public record TrainingAnswerRow(String ownerId, String sessionId, String questionId, String answerId,
                                    Integer answerNumber, String answerCondition, String answerContentJson,
                                    String questionOwnerId, String actualQuestionId, String questionSessionId,
                                    String sessionOwnerId, String actualSessionId, Instant createdAt) {}
    public record AnswerKnowledgeBinding(String ownerId, String sessionId, String questionId, String answerId,
                                         String itemId, String versionId) {}
    public record TrainingQuestionRow(String ownerId, String sessionId, String questionId, String sourceQuestionId,
                                      String sourceVersionId, String title, String stage, String questionKind,
                                      String rootQuestionId, String parentQuestionId, String parentAnswerId,
                                      String aiTaskId, String snapshotJson, String sessionOwnerId, String actualSessionId,
                                      Integer positionIndex) {}
    public record BankVersionRow(String ownerId, String questionId, String versionId, Integer versionNumber,
                                 String title, String promptJson, String itemOwnerId, String actualQuestionId,
                                 String itemStatus) {}
    public record TrainingQuestionKnowledgeBinding(String ownerId, String sessionId, String questionId,
                                                   String itemId, String versionId) {}
    public record BankKnowledgeBinding(String ownerId, String questionId, String versionId, String knowledgeItemId,
                                      String knowledgeVersionId, Integer positionIndex) {}
    public record EvaluationSourceRow(String ownerId, String taskId, String purpose, String taskStatus,
                                      String currentAttemptId, Integer attemptCount, String manifestJson,
                                      String manifestHash, String candidateJson, String bindingOwnerId,
                                      String bindingTaskId, String sessionId, String questionId, String answerId,
                                      String attemptOwnerId, String attemptTaskId, String attemptId,
                                      Integer attemptNumber, String attemptStatus, String sessionOwnerId,
                                      String actualSessionId, String sessionMode, String sessionConfigJson) {}
    public record BankStandardRow(String ownerId, String questionId, String versionId, String standardStatus,
                                  String referenceAnswer, String checkpointsJson, String itemOwnerId,
                                  String actualQuestionId) {}
    public record FollowUpSourceRow(String ownerId, String taskId, String purpose, String taskStatus,
                                    String currentAttemptId, Integer attemptCount, String manifestJson,
                                    String manifestHash, String candidateJson, String bindingOwnerId,
                                    String bindingTaskId, String sessionId, String parentQuestionId,
                                    String parentAnswerId, String rootQuestionId, Long generation,
                                    String generatedQuestionId, String attemptOwnerId, String attemptTaskId,
                                    String attemptId, Integer attemptNumber, String attemptStatus,
                                    String sessionOwnerId, String actualSessionId, String sessionContextJson) {}
    public record ReplaySourceRow(String id, String ownerId, String sessionId, String questionId,
                                  String questionMode, String baselineKind, String baselineSessionId,
                                  String baselineQuestionId, String baselineAnswerId, String recollectionId,
                                  String recollectionVersionId, Long recollectionRevision,
                                  String sessionOwnerId, String actualSessionId, String sessionMode,
                                  String sessionConfigJson, Integer questionCount, String actualQuestionId) {}
    public record ReplayAnswerRow(String ownerId, String answerId, String sessionId, String questionId,
                                  String sessionOwnerId, String actualSessionId, String sessionStatus) {}
    public record RecollectionVersionRow(String ownerId, String recollectionId, String versionId,
                                         Integer versionNumber, String title, String baselineKind, String contentJson,
                                         String itemOwnerId, String actualItemId, String itemStatus) {}
}
