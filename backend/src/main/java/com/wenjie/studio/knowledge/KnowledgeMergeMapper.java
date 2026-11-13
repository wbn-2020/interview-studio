package com.wenjie.studio.knowledge;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface KnowledgeMergeMapper {
    record Binding(String kind, String id, String versionId, long revision) {}
    record SavedMerge(KnowledgeMergeDtos.MergeRecord record, String sourceTitle, String targetTitle,
                      long sourceRevisionBefore, long targetRevisionBefore,
                      long sourceOrganizationRevision, long targetOrganizationRevision,
                      long layoutRevisionBefore, long layoutRevisionAfter, String choicesJson) {}

    KnowledgeMergeDtos.MergeRecord bySource(@Param("owner") String owner, @Param("sourceId") String sourceId);
    KnowledgeDtos.Position position(@Param("owner") String owner, @Param("id") String id);
    List<String> activeConceptIds(@Param("owner") String owner, @Param("ids") List<String> ids);
    List<Binding> bindings(@Param("owner") String owner, @Param("ids") List<String> ids,
                           @Param("limit") int limit);
    int insert(@Param("owner") String owner, @Param("merge") SavedMerge merge);
    int insertRelationMapping(@Param("owner") String owner, @Param("mergeId") String mergeId,
                              @Param("original") KnowledgeDtos.Relation original,
                              @Param("targetRelationId") String targetRelationId,
                              @Param("outcome") String outcome, @Param("now") Instant now);
}
