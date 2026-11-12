package com.wenjie.studio.training;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface TrainingMapper {
    TrainingRows.SessionRow session(@Param("owner") String owner, @Param("id") String id);
    TrainingRows.SessionRow active(@Param("owner") String owner);
    List<TrainingRows.SessionRow> sessions(@Param("owner") String owner, @Param("status") String status,
                                          @Param("cursor") String cursor, @Param("limit") int limit);
    List<TrainingRows.QuestionRow> questions(@Param("owner") String owner, @Param("sessionId") String sessionId);
    TrainingRows.QuestionRow question(@Param("owner") String owner, @Param("sessionId") String sessionId,
                                      @Param("id") String id);
    TrainingRows.AnswerRow answer(@Param("owner") String owner, @Param("sessionId") String sessionId,
                                  @Param("questionId") String questionId, @Param("id") String id);
    List<TrainingDtos.AnswerSummary> answers(@Param("owner") String owner, @Param("sessionId") String sessionId,
            @Param("questionId") String questionId, @Param("before") Integer before, @Param("limit") int limit);
    List<TrainingDtos.Event> events(@Param("owner") String owner, @Param("sessionId") String sessionId,
                                    @Param("before") Integer before, @Param("limit") int limit);
    TrainingDtos.PriorExposure exposure(@Param("owner") String owner, @Param("sourceQuestionId") String sourceQuestionId);
    TrainingRows.BoardImportRow boardImport(@Param("owner") String owner, @Param("sessionId") String sessionId,
                                            @Param("importId") String importId);
    void insertSession(@Param("session") TrainingRows.SessionRow session);
    void insertQuestion(@Param("question") TrainingRows.QuestionRow question);
    void insertAnswer(@Param("answer") TrainingRows.AnswerRow answer);
    int insertQuestionKnowledgeSnapshot(@Param("owner") String owner, @Param("sessionId") String sessionId,
            @Param("questionId") String questionId, @Param("itemId") String itemId, @Param("versionId") String versionId);
    int insertDraftKnowledgeReference(@Param("owner") String owner, @Param("sessionId") String sessionId,
            @Param("questionId") String questionId, @Param("itemId") String itemId, @Param("versionId") String versionId);
    int insertAnswerKnowledgeReference(@Param("owner") String owner, @Param("sessionId") String sessionId,
            @Param("questionId") String questionId, @Param("answerId") String answerId,
            @Param("itemId") String itemId, @Param("versionId") String versionId);
    int clearDraftKnowledgeReferences(@Param("owner") String owner, @Param("sessionId") String sessionId,
                                     @Param("questionId") String questionId);
    void insertBoardImport(@Param("entry") TrainingRows.BoardImportRow entry);
    void insertEvent(@Param("owner") String owner, @Param("sessionId") String sessionId,
                     @Param("event") TrainingDtos.Event event);
    int incrementEventCount(@Param("owner") String owner, @Param("id") String id, @Param("expectedCount") int expectedCount);
    int changeState(@Param("owner") String owner, @Param("id") String id, @Param("expected") long expected,
            @Param("status") String status, @Param("materialAccess") boolean materialAccess,
            @Param("observationAt") Instant observationAt, @Param("pauseStartedAt") Instant pauseStartedAt,
            @Param("startedAt") Instant startedAt, @Param("endedAt") Instant endedAt,
            @Param("endChecklistJson") String endChecklistJson, @Param("now") Instant now);
    int grantAssistance(@Param("owner") String owner, @Param("id") String id, @Param("expected") long expected,
                         @Param("sequence") int sequence, @Param("now") Instant now);
    int incrementQuestionCount(@Param("owner") String owner, @Param("id") String id,
                                @Param("expected") long expected, @Param("now") Instant now);
    int updateTiming(@Param("owner") String owner, @Param("id") String id,
            @Param("observedMillis") long observedMillis, @Param("unobservedMillis") long unobservedMillis,
            @Param("pausedMillis") long pausedMillis, @Param("observationAt") Instant observationAt,
            @Param("pauseStartedAt") Instant pauseStartedAt);
    int freezeExposure(@Param("owner") String owner, @Param("sessionId") String sessionId,
                        @Param("questionId") String questionId, @Param("exposureJson") String exposureJson);
    int saveDraft(@Param("owner") String owner, @Param("sessionId") String sessionId,
            @Param("id") String id, @Param("expected") long expected,
            @Param("draftJson") String draftJson, @Param("savedAt") Instant savedAt);
    int submit(@Param("owner") String owner, @Param("sessionId") String sessionId, @Param("id") String id,
                @Param("expected") long expected, @Param("answerId") String answerId, @Param("answerCount") int answerCount);
    int changeQuestionStatus(@Param("owner") String owner, @Param("sessionId") String sessionId,
            @Param("id") String id, @Param("expected") long expected, @Param("status") String status);
}
