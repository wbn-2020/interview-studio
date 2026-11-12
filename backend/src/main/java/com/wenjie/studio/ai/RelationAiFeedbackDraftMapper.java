package com.wenjie.studio.ai;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

@Mapper
@Repository("aiRelationAiFeedbackDraftMapper")
public interface RelationAiFeedbackDraftMapper {
    RelationAiFeedbackDraftRows.Binding binding(@Param("owner") String owner, @Param("taskId") String taskId);
    int insert(@Param("binding") RelationAiFeedbackDraftRows.Binding binding);
}
