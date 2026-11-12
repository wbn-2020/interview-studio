package com.wenjie.studio.ai;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface AiMapper {
    String eventCursor(@Param("owner") String owner);
    void advanceEventCursor(@Param("owner") String owner, @Param("cursor") String cursor);
    AiRows.FollowUpRow followUp(@Param("owner") String owner, @Param("taskId") String taskId);
    void insertFollowUp(@Param("binding") AiRows.FollowUpRow binding);
    int setGeneratedQuestion(@Param("owner") String owner, @Param("taskId") String taskId, @Param("questionId") String questionId);
    int pendingFollowUps(@Param("owner") String owner, @Param("sessionId") String sessionId,
                        @Param("rootQuestionId") String rootQuestionId, @Param("excludedTaskId") String excludedTaskId);
    List<AiRows.SummaryRow> followUpTasks(@Param("owner") String owner, @Param("sessionId") String sessionId,
                                        @Param("cursor") String cursor, @Param("limit") int limit);
    AiRows.TaskRow task(@Param("owner") String owner, @Param("id") String id);
    boolean taskExists(@Param("owner") String owner, @Param("id") String id);
    List<AiRows.SummaryRow> tasks(@Param("owner") String owner, @Param("cursor") String cursor, @Param("limit") int limit);
    List<AiRows.SummaryRow> relationGraphTasks(@Param("owner") String owner, @Param("cursor") String cursor,
                                             @Param("limit") int limit);
    AiRows.AttemptRow attempt(@Param("owner") String owner, @Param("taskId") String taskId, @Param("id") String id);
    List<AiRows.AttemptRow> attempts(@Param("owner") String owner, @Param("taskId") String taskId);
    List<AiRows.TaskRow> pending(@Param("owner") String owner);
    List<AiRows.TaskRow> pendingAuthorizationTasks(@Param("owner") String owner, @Param("authorizationId") String authorizationId);
    AiRows.TaskRow queued(@Param("owner") String owner);
    int runningCount(@Param("owner") String owner);
    int pendingCount(@Param("owner") String owner);
    AiRows.BudgetRow budget(@Param("owner") String owner, @Param("currency") String currency, @Param("date") LocalDate date);
    void insertTask(@Param("task") AiRows.TaskRow task);
    int insertDraftKnowledgeInput(@Param("owner") String owner, @Param("taskId") String taskId,
                                  @Param("source") AiDtos.Source source, @Param("ordinal") int ordinal);
    void insertAttempt(@Param("attempt") AiRows.AttemptRow attempt);
    void insertMaterialQaSource(@Param("owner") String owner, @Param("taskId") String taskId,
                                @Param("excerptId") String excerptId, @Param("ordinal") int ordinal);
    void insertMaterialDraftSource(@Param("owner") String owner, @Param("taskId") String taskId,
                                   @Param("excerptId") String excerptId, @Param("ordinal") int ordinal);
    void insertTrainingDraftSource(@Param("source") AiRows.TrainingDraftSourceRow source);
    void insertPreparationSource(@Param("owner") String owner, @Param("taskId") String taskId,
                                 @Param("source") AiDtos.Source source, @Param("ordinal") int ordinal);
    int updateTask(@Param("task") AiRows.TaskRow task, @Param("expected") long expected);
    int updateAttempt(@Param("attempt") AiRows.AttemptRow attempt,
                       @Param("expectedStatus") String expectedStatus, @Param("expectedToken") String expectedToken);
    AiRows.DecisionRow decision(@Param("owner") String owner, @Param("taskId") String taskId);
    void insertDecision(@Param("decision") AiRows.DecisionRow decision);
    void insertKnowledgeSource(@Param("owner") String owner, @Param("taskId") String taskId,
                                @Param("source") AiDtos.Source source, @Param("ordinal") int ordinal);
    List<AiRows.OriginRow> origins(@Param("owner") String owner, @Param("itemId") String itemId,
                                  @Param("through") int through, @Param("before") Integer before,
                                  @Param("limit") int limit);
    List<AiRows.OriginSourceRow> originSources(@Param("owner") String owner, @Param("taskIds") List<String> taskIds);
    List<AiRows.PreparationOriginSourceRow> preparationOriginSources(@Param("owner") String owner,
                                                                  @Param("taskIds") List<String> taskIds);
    List<AiRows.MaterialDraftOriginRow> materialDraftOriginSources(@Param("owner") String owner,
                                                                @Param("taskIds") List<String> taskIds);
    List<AiRows.TrainingDraftSourceRow> trainingDraftOriginSources(@Param("owner") String owner,
                                                                 @Param("taskIds") List<String> taskIds);
    List<AiRows.PreparationOriginSourceRow> authorizationPreparationSources(@Param("owner") String owner, @Param("taskId") String taskId);
    List<AiRows.MaterialDraftOriginRow> authorizationMaterialDraftSources(@Param("owner") String owner, @Param("taskId") String taskId);
    List<AiRows.MaterialDraftOriginRow> authorizationMaterialQaSources(@Param("owner") String owner, @Param("taskId") String taskId);
    AiRows.QuestionDecisionRow questionDecision(@Param("owner") String owner, @Param("taskId") String taskId);
    void insertQuestionDecision(@Param("decision") AiRows.QuestionDecisionRow decision);
    AiRows.ExpressionDecisionRow expressionDecision(@Param("owner") String owner, @Param("taskId") String taskId);
    void insertExpressionDecision(@Param("decision") AiRows.ExpressionDecisionRow decision);
    AiRows.PreparationPlanDecisionRow preparationPlanDecision(@Param("owner") String owner, @Param("taskId") String taskId);
    void insertPreparationPlanDecision(@Param("decision") AiRows.PreparationPlanDecisionRow decision);
    List<AiRows.RelationDecisionRow> relationDecisions(@Param("owner") String owner, @Param("taskId") String taskId);
    AiRows.RelationDecisionRow relationDecision(@Param("owner") String owner, @Param("taskId") String taskId,
                                                @Param("candidateId") String candidateId);
    void insertRelationDecision(@Param("decision") AiRows.RelationDecisionRow decision);
    void insertRelationSource(@Param("owner") String owner, @Param("taskId") String taskId,
                               @Param("source") AiDtos.Source source, @Param("ordinal") int ordinal);
    List<AiRows.RelationSourceRow> relationSources(@Param("owner") String owner, @Param("taskId") String taskId);
    AiRows.RelationDecisionRow relationOrigin(@Param("owner") String owner, @Param("relationId") String relationId);
    boolean relationExists(@Param("owner") String owner, @Param("relationId") String relationId);
    void insertQuestionSource(@Param("owner") String owner, @Param("taskId") String taskId,
                               @Param("source") AiDtos.Source source, @Param("ordinal") int ordinal);
    List<AiRows.OriginRow> questionOrigins(@Param("owner") String owner, @Param("questionId") String questionId,
                                          @Param("through") int through, @Param("before") Integer before,
                                          @Param("limit") int limit);
    List<AiRows.OriginSourceRow> questionOriginSources(@Param("owner") String owner, @Param("taskIds") List<String> taskIds);
    List<AiRows.OriginRow> expressionOrigins(@Param("owner") String owner, @Param("expressionId") String expressionId,
                                            @Param("through") int through, @Param("before") Integer before,
                                            @Param("limit") int limit);
    List<AiRows.ExpressionOriginSourceRow> expressionOriginSources(@Param("owner") String owner,
                                                                 @Param("taskIds") List<String> taskIds);
}
