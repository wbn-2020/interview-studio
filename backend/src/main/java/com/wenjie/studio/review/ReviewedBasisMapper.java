package com.wenjie.studio.review;

import com.wenjie.studio.review.ReviewedBasisRows.ChangeRow;
import com.wenjie.studio.review.ReviewedBasisRows.GenerationRow;
import com.wenjie.studio.review.ReviewedBasisRows.KnowledgeRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ReviewedBasisMapper {
    KnowledgeRow knowledge(@Param("owner") String owner, @Param("itemId") String itemId,
                           @Param("versionId") String versionId);
    GenerationRow generation(@Param("owner") String owner, @Param("kind") String kind, @Param("id") String id);
    GenerationRow lockGeneration(@Param("owner") String owner, @Param("kind") String kind, @Param("id") String id);
    ChangeRow firstAfter(@Param("owner") String owner, @Param("kind") String kind, @Param("id") String id,
                         @Param("baseline") long baseline);
    long upstreamCountAfter(@Param("owner") String owner, @Param("id") String id, @Param("baseline") long baseline);
    ChangeRow firstUpstreamAfter(@Param("owner") String owner, @Param("id") String id,
                                @Param("versionId") String versionId, @Param("baseline") long baseline);
    int insertGeneration(@Param("row") GenerationRow row);
    int updateGeneration(@Param("row") GenerationRow row, @Param("expected") long expected);
    int insertChange(@Param("row") ChangeRow row);
}
