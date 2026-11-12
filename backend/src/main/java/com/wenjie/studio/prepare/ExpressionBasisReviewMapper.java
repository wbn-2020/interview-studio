package com.wenjie.studio.prepare;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

import static com.wenjie.studio.prepare.ExpressionBasisReviewRows.*;

@Mapper
public interface ExpressionBasisReviewMapper {
    long revision(@Param("owner") String owner, @Param("itemId") String itemId);
    ReviewRow latest(@Param("owner") String owner, @Param("itemId") String itemId, @Param("versionId") String versionId);
    List<ReviewRow> history(@Param("owner") String owner, @Param("itemId") String itemId,
                           @Param("before") Long before, @Param("limit") int limit);
    List<BasisRow> basis(@Param("owner") String owner, @Param("reviewId") String reviewId);
    int insertReview(@Param("owner") String owner, @Param("row") ReviewRow row);
    int insertBasis(@Param("owner") String owner, @Param("review") ReviewRow review, @Param("row") BasisRow row);
    int confirmItem(@Param("owner") String owner, @Param("itemId") String itemId,
                    @Param("versionId") String versionId, @Param("expected") long expected,
                    @Param("now") Instant now);
}
