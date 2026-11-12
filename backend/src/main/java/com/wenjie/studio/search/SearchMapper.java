package com.wenjie.studio.search;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SearchMapper {
    SearchRows.Projection projection(@Param("owner") String owner);
    long pendingCount(@Param("owner") String owner);
    long maximumEvent(@Param("owner") String owner);
    void queueBuild(@Param("owner") String owner, @Param("generation") String generation, @Param("boundary") long boundary);
    void advanceScan(@Param("owner") String owner, @Param("generation") String generation,
                     @Param("cursor") String cursor, @Param("phase") String phase);
    void activate(@Param("owner") String owner, @Param("generation") String generation);
    void markError(@Param("owner") String owner, @Param("code") String code);
    void clearError(@Param("owner") String owner);
    List<String> scanIds(@Param("owner") String owner, @Param("cursor") String cursor, @Param("limit") int limit);
    List<SearchRows.Content> contents(@Param("owner") String owner, @Param("ids") List<String> ids);
    List<SearchRows.TopicLink> topicLinks(@Param("owner") String owner, @Param("ids") List<String> ids);
    List<SearchRows.Event> pendingEvents(@Param("owner") String owner, @Param("limit") int limit);
    List<SearchRows.Event> buildEvents(@Param("owner") String owner, @Param("generation") String generation,
                                      @Param("boundary") long boundary, @Param("limit") int limit);
    void acknowledge(@Param("owner") String owner, @Param("ids") List<Long> ids);
    void applyBuildEvent(@Param("owner") String owner, @Param("generation") String generation, @Param("eventId") long eventId);
}
