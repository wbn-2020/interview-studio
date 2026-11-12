package com.wenjie.studio.training;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface TrainingGrowthMapper {
    List<TrainingGrowthRows.Candidate> candidates(@Param("owner") String owner,
            @Param("startAt") Instant startAt, @Param("observedUntil") Instant observedUntil,
            @Param("afterAt") Instant afterAt, @Param("afterId") String afterId, @Param("limit") int limit);
    List<TrainingGrowthRows.AnswerRow> answers(@Param("owner") String owner, @Param("ids") List<String> ids);
    TrainingGrowthRows.QuestionRow question(@Param("owner") String owner,
            @Param("sessionId") String sessionId, @Param("questionId") String questionId);
    List<TrainingGrowthRows.KnowledgeRow> knowledge(@Param("owner") String owner,
            @Param("references") List<TrainingGrowthRows.KnowledgeRef> references);
}
