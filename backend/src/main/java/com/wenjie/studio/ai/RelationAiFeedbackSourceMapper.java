package com.wenjie.studio.ai;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RelationAiFeedbackSourceMapper {
    RelationAiFeedbackSourceRows.Metadata metadata(@Param("owner") String owner, @Param("taskId") String taskId);
    RelationAiFeedbackSourceRows.Candidate candidate(@Param("owner") String owner, @Param("taskId") String taskId);
    RelationAiFeedbackSourceRows.Decision latestDecision(@Param("owner") String owner, @Param("taskId") String taskId,
                                                        @Param("feedbackId") String feedbackId);
    RelationAiFeedbackSourceRows.Decision decision(@Param("owner") String owner, @Param("taskId") String taskId,
                                                  @Param("feedbackId") String feedbackId, @Param("revision") int revision);
}
