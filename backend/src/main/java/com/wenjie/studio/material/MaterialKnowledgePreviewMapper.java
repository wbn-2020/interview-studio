package com.wenjie.studio.material;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MaterialKnowledgePreviewMapper {
    List<Binding> materialBindings(@Param("owner") String owner, @Param("excerptId") String excerptId,
                                   @Param("limit") int limit);
    List<Binding> knowledgeBindings(@Param("owner") String owner, @Param("itemId") String itemId,
                                    @Param("limit") int limit);
    NodeRow node(@Param("owner") String owner, @Param("itemId") String itemId, @Param("versionId") String versionId);

    record Binding(String ownerId, String taskId, String excerptId, String sourceItemId, String sourceVersionId,
                   Integer sourceVersionNumber, String sourceTitle, Integer ordinal,
                   String taskOwnerId, String actualTaskId, String purpose, String taskStatus,
                   String decisionOwnerId, String decisionTaskId, String decisionStatus, String mode,
                   String targetItemId, String targetVersionId,
                   String questionDecisionOwnerId, String questionDecisionTaskId, String questionDecisionStatus,
                   String questionTargetItemId, String questionTargetVersionId,
                   String sourceItemOwnerId, String sourceVersionOwnerId, String actualSourceItemId,
                   String actualSourceVersionId, Integer actualSourceVersionNumber, String actualSourceTitle,
                   String targetItemOwnerId, String targetVersionOwnerId, String actualTargetItemId,
                   String actualTargetVersionId, Integer actualTargetVersionNumber) {}
    record NodeRow(String ownerId, String itemId, String versionId, Integer versionNumber, String title,
                   String itemOwnerId, String itemKind, String itemStatus, String currentVersionId,
                   Integer currentVersionNumber, String currentOwnerId, String currentItemId,
                   String actualCurrentVersionId, Integer actualCurrentVersionNumber) {}
}
