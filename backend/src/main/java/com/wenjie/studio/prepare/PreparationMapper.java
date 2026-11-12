package com.wenjie.studio.prepare;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface PreparationMapper {
    PreparationRows.ItemRow item(@Param("owner") String owner, @Param("id") String id);
    PreparationDtos.SourceCheck sourceCheck(@Param("owner") String owner, @Param("kind") String kind,
                                            @Param("id") String id, @Param("versionId") String versionId);
    PreparationDtos.SourceCheck expressionSourceCheck(@Param("owner") String owner, @Param("id") String id,
                                                      @Param("versionId") String versionId);
    List<PreparationRows.ExpressionBasisRow> expressionBasis(@Param("owner") String owner,
            @Param("itemId") String itemId, @Param("versionId") String versionId);
    List<PreparationRows.ItemRow> items(@Param("owner") String owner, @Param("kind") String kind,
            @Param("status") String status, @Param("pattern") String pattern,
            @Param("verification") String verification, @Param("favorite") Boolean favorite,
            @Param("projectId") String projectId, @Param("cursor") String cursor, @Param("limit") int limit);
    PreparationRows.VersionRow version(@Param("owner") String owner, @Param("itemId") String itemId,
                                       @Param("versionId") String versionId);
    PreparationRows.VersionRow versionById(@Param("owner") String owner, @Param("versionId") String versionId);
    List<PreparationDtos.VersionSummary> versions(@Param("owner") String owner, @Param("id") String id,
                                                 @Param("before") Integer before, @Param("limit") int limit);
    List<PreparationRows.ReferenceRow> references(@Param("owner") String owner, @Param("versionId") String versionId);
    List<PreparationRows.ItemRow> impacts(@Param("owner") String owner, @Param("id") String id,
                                         @Param("cursor") String cursor, @Param("limit") int limit);
    boolean hasInvalidFactBasis(@Param("owner") String owner, @Param("versionId") String versionId);
    List<PreparationRows.DecisionRow> decisions(@Param("owner") String owner, @Param("id") String id,
                                               @Param("beforeTime") Instant beforeTime,
                                               @Param("beforeId") String beforeId, @Param("limit") int limit);
    void insertItem(@Param("item") PreparationRows.ItemRow item);
    void insertVersion(@Param("version") PreparationRows.VersionRow version);
    void insertReference(@Param("owner") String owner, @Param("itemId") String itemId,
            @Param("versionId") String versionId, @Param("sourceKind") String sourceKind,
            @Param("sourceItemId") String sourceItemId, @Param("sourceVersionId") String sourceVersionId,
            @Param("useType") String useType);
    int saveContent(@Param("owner") String owner, @Param("id") String id, @Param("expected") long expected,
            @Param("versionId") String versionId, @Param("number") int number,
            @Param("title") String title, @Param("now") Instant now);
    int changeStatus(@Param("owner") String owner, @Param("id") String id, @Param("expected") long expected,
                     @Param("status") String status, @Param("now") Instant now);
    int setVerification(@Param("owner") String owner, @Param("id") String id, @Param("expected") long expected,
                        @Param("verification") String verification, @Param("now") Instant now);
    int setFavorite(@Param("owner") String owner, @Param("id") String id, @Param("expected") long expected,
                    @Param("favorite") boolean favorite, @Param("now") Instant now);
    void insertDecision(@Param("owner") String owner, @Param("id") String id, @Param("itemId") String itemId,
            @Param("versionId") String versionId, @Param("decision") String decision,
            @Param("reason") String reason, @Param("now") Instant now);
    PreparationDtos.Focus focus(@Param("owner") String owner);
    void ensureFocus(@Param("owner") String owner);
    int setFocus(@Param("owner") String owner, @Param("expected") long expected, @Param("targetId") String targetId);
}
