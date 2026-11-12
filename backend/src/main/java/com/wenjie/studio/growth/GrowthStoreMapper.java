package com.wenjie.studio.growth;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

import static com.wenjie.studio.growth.GrowthStoreRows.*;

@Mapper
public interface GrowthStoreMapper {
    UseRow use(@Param("owner") String owner, @Param("id") String id);
    UseRow useByIdentity(@Param("owner") String owner, @Param("targetVersionId") String targetVersionId,
                         @Param("requirementId") String requirementId, @Param("evidenceKey") String evidenceKey);
    List<UseRow> uses(@Param("owner") String owner, @Param("targetId") String targetId,
                      @Param("targetVersionId") String targetVersionId, @Param("requirementId") String requirementId,
                      @Param("status") String status, @Param("asOf") Instant asOf,
                      @Param("afterCreatedAt") Instant afterCreatedAt, @Param("afterId") String afterId,
                      @Param("limit") int limit);
    List<UseHistoryRow> useHistory(@Param("owner") String owner, @Param("id") String id,
                                   @Param("before") Long before, @Param("limit") int limit);
    int insertUse(@Param("row") UseRow row);
    int updateUse(@Param("row") UseRow row, @Param("expected") long expected);
    int insertUseHistory(@Param("row") UseHistoryRow row);

    PreferenceRow preference(@Param("owner") String owner, @Param("id") String id);
    PreferenceRow preferenceBySubject(@Param("owner") String owner, @Param("subjectKind") String subjectKind,
                                       @Param("subjectKey") String subjectKey);
    List<PreferenceRow> preferences(@Param("owner") String owner, @Param("visibility") String visibility,
                                    @Param("subjectKind") String subjectKind, @Param("asOf") Instant asOf,
                                    @Param("afterCreatedAt") Instant afterCreatedAt, @Param("afterId") String afterId,
                                    @Param("limit") int limit);
    List<PreferenceHistoryRow> preferenceHistory(@Param("owner") String owner, @Param("id") String id,
                                                 @Param("before") Long before, @Param("limit") int limit);
    int insertPreference(@Param("row") PreferenceRow row);
    int updatePreference(@Param("row") PreferenceRow row, @Param("expected") long expected);
    int insertPreferenceHistory(@Param("row") PreferenceHistoryRow row);
}
