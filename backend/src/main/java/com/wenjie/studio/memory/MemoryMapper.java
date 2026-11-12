package com.wenjie.studio.memory;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface MemoryMapper {
    MemoryRows.CardRow card(@Param("owner") String owner, @Param("id") String id);
    List<MemoryRows.SummaryRow> cards(@Param("owner") String owner, @Param("status") String status,
            @Param("kind") String kind, @Param("pattern") String pattern, @Param("sourceItemId") String sourceItemId,
            @Param("relatedConceptId") String relatedConceptId, @Param("cursor") String cursor, @Param("limit") int limit);
    MemoryRows.VersionRow version(@Param("owner") String owner, @Param("id") String id, @Param("versionId") String versionId);
    List<MemoryDtos.VersionSummary> versions(@Param("owner") String owner, @Param("id") String id,
                                            @Param("before") Integer before, @Param("limit") int limit);
    MemoryRows.ReferenceRow reference(@Param("owner") String owner, @Param("id") String id, @Param("versionId") String versionId);
    void insertCard(@Param("card") MemoryRows.CardRow card);
    void insertVersion(@Param("version") MemoryRows.VersionRow version, @Param("reference") String reference,
                       @Param("contentBytes") int contentBytes, @Param("sourceCount") int sourceCount,
                       @Param("relatedConceptCount") int relatedConceptCount);
    void insertSource(@Param("owner") String owner, @Param("id") String id, @Param("versionId") String versionId,
                      @Param("itemId") String itemId, @Param("knowledgeVersionId") String knowledgeVersionId,
                      @Param("position") int position);
    void insertConcept(@Param("owner") String owner, @Param("id") String id, @Param("versionId") String versionId,
                       @Param("conceptId") String conceptId, @Param("position") int position);
    int saveContent(@Param("owner") String owner, @Param("id") String id, @Param("expected") long expected,
                    @Param("versionId") String versionId, @Param("number") int number, @Param("now") Instant now);
    int changeStatus(@Param("owner") String owner, @Param("id") String id, @Param("expected") long expected,
                     @Param("status") String status, @Param("now") Instant now);
}
