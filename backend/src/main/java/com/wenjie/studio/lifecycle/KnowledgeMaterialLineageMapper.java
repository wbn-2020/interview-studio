package com.wenjie.studio.lifecycle;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

import static com.wenjie.studio.lifecycle.KnowledgeMaterialLineageRows.*;

@Mapper
public interface KnowledgeMaterialLineageMapper {
    List<VersionRow> version(@Param("owner") String owner, @Param("itemId") String itemId,
                             @Param("versionId") String versionId, @Param("limit") int limit);
    List<AdoptionRow> adoptions(@Param("owner") String owner, @Param("itemId") String itemId,
                               @Param("through") int through, @Param("knownTaskIds") List<String> knownTaskIds,
                               @Param("limit") int limit);
    List<AdoptionRow> expressionAdoptions(@Param("owner") String owner, @Param("itemId") String itemId,
                                         @Param("through") int through, @Param("knownTaskIds") List<String> knownTaskIds,
                                         @Param("limit") int limit);
    List<AdoptionRow> factAdoptions(@Param("owner") String owner, @Param("itemId") String itemId,
                                   @Param("through") int through, @Param("knownTaskIds") List<String> knownTaskIds,
                                   @Param("limit") int limit);
    List<AdoptionRow> questionAdoptions(@Param("owner") String owner, @Param("itemId") String itemId,
                                       @Param("through") int through, @Param("knownTaskIds") List<String> knownTaskIds,
                                       @Param("limit") int limit);
    List<KnowledgeBinding> knowledgeBindings(@Param("owner") String owner, @Param("taskId") String taskId,
                                             @Param("limit") int limit);
    List<KnowledgeBinding> questionKnowledgeBindings(@Param("owner") String owner, @Param("taskId") String taskId,
                                                     @Param("limit") int limit);
    List<MaterialBinding> materialBindings(@Param("owner") String owner, @Param("taskId") String taskId,
                                           @Param("limit") int limit);
    List<PreparationBinding> preparationBindings(@Param("owner") String owner, @Param("taskId") String taskId,
                                                 @Param("limit") int limit);
    List<PreparationVersionRow> preparationVersion(@Param("owner") String owner, @Param("itemId") String itemId,
                                                   @Param("versionId") String versionId, @Param("limit") int limit);
    List<ProjectGenerationRow> projectGeneration(@Param("owner") String owner, @Param("itemId") String itemId,
                                                 @Param("limit") int limit);
    List<ProjectGenerationEventRow> projectGenerationEvents(@Param("owner") String owner, @Param("itemId") String itemId,
                                                            @Param("generation") long generation, @Param("limit") int limit);
    List<PreparationMaterialBinding> preparationMaterials(@Param("owner") String owner,
                                                         @Param("versionId") String versionId,
                                                         @Param("limit") int limit);
    List<PreparationReferenceBinding> preparationReferences(@Param("owner") String owner,
                                                           @Param("versionId") String versionId,
                                                           @Param("limit") int limit);
    List<FactSourceBinding> factSources(@Param("owner") String owner, @Param("taskId") String taskId,
                                       @Param("limit") int limit);
    List<TrainingSourceBinding> trainingSources(@Param("owner") String owner, @Param("taskId") String taskId,
                                               @Param("limit") int limit);
    List<TrainingAnswerRow> trainingAnswer(@Param("owner") String owner, @Param("answerId") String answerId,
                                          @Param("limit") int limit);
    List<AnswerKnowledgeBinding> answerKnowledgeBindings(@Param("owner") String owner,
                                                        @Param("answerId") String answerId, @Param("limit") int limit);
    List<TrainingQuestionRow> trainingQuestion(@Param("owner") String owner, @Param("questionId") String questionId,
                                               @Param("limit") int limit);
    List<BankVersionRow> bankVersion(@Param("owner") String owner, @Param("itemId") String itemId,
                                     @Param("versionId") String versionId, @Param("limit") int limit);
    List<TrainingQuestionKnowledgeBinding> trainingQuestionKnowledgeBindings(@Param("owner") String owner,
                                                                            @Param("questionId") String questionId,
                                                                            @Param("limit") int limit);
    List<BankKnowledgeBinding> bankKnowledgeBindings(@Param("owner") String owner, @Param("versionId") String versionId,
                                                    @Param("limit") int limit);
    List<EvaluationSourceRow> evaluationSource(@Param("owner") String owner, @Param("taskId") String taskId,
                                               @Param("limit") int limit);
    List<BankStandardRow> bankStandard(@Param("owner") String owner, @Param("itemId") String itemId,
                                       @Param("versionId") String versionId, @Param("limit") int limit);
    List<FollowUpSourceRow> followUpSource(@Param("owner") String owner, @Param("taskId") String taskId,
                                          @Param("limit") int limit);
    List<ReplaySourceRow> replaySource(@Param("owner") String owner, @Param("sessionId") String sessionId,
                                      @Param("limit") int limit);
    List<ReplayAnswerRow> replayAnswer(@Param("owner") String owner, @Param("answerId") String answerId,
                                      @Param("limit") int limit);
    List<RecollectionVersionRow> recollectionVersion(@Param("owner") String owner, @Param("itemId") String itemId,
                                                    @Param("versionId") String versionId, @Param("limit") int limit);
    List<MaterialBinding> factMaterials(@Param("owner") String owner, @Param("taskId") String taskId,
                                        @Param("limit") int limit);
}
