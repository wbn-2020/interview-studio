package com.wenjie.studio.search;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UnifiedSearchMapper {
    UnifiedSearchRows.VersionRow knowledgeVersion(@Param("owner") String owner, @Param("id") String id,
                                                  @Param("versionId") String versionId);
    UnifiedSearchRows.VersionRow preparationVersion(@Param("owner") String owner, @Param("id") String id,
                                                    @Param("versionId") String versionId);
    UnifiedSearchRows.VersionRow questionVersion(@Param("owner") String owner, @Param("id") String id,
                                                 @Param("versionId") String versionId);
    UnifiedSearchRows.ExcerptRow excerpt(@Param("owner") String owner, @Param("id") String id);
    List<UnifiedSearchRows.VersionRow> preparations(@Param("owner") String owner, @Param("status") String status,
                                                   @Param("pattern") String pattern, @Param("afterId") String afterId,
                                                   @Param("limit") int limit);
    List<UnifiedSearchRows.VersionRow> questions(@Param("owner") String owner, @Param("status") String status,
                                                @Param("pattern") String pattern, @Param("afterId") String afterId,
                                                @Param("limit") int limit);
    List<UnifiedSearchRows.ExcerptRow> excerpts(@Param("owner") String owner, @Param("pattern") String pattern,
                                               @Param("afterId") String afterId, @Param("limit") int limit);
    boolean incompleteExcerptBaseline(@Param("owner") String owner, @Param("pattern") String pattern,
                                      @Param("afterId") String afterId);
}
