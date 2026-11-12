package com.wenjie.studio.knowledge;

import com.wenjie.studio.knowledge.KnowledgeDtos.*;
import com.wenjie.studio.knowledge.KnowledgeRows.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface KnowledgeMapper {
    Item findItem(@Param("owner") String owner, @Param("id") String id);
    Draft findDraft(@Param("owner") String owner, @Param("id") String id);
    Version findVersion(@Param("owner") String owner, @Param("itemId") String itemId,
                        @Param("versionId") String versionId);
    List<Summary> listItems(@Param("owner") String owner, @Param("kind") String kind,
                            @Param("status") String status, @Param("pattern") String pattern,
                            @Param("cursor") String cursor, @Param("limit") int limit);
    List<TopicLink> topicLinks(@Param("owner") String owner, @Param("ids") List<String> ids);
    List<VersionSummary> versions(@Param("owner") String owner, @Param("id") String id,
                                  @Param("before") Integer before, @Param("limit") int limit);
    List<Topic> topics(@Param("owner") String owner);
    List<String> ownedTopicIds(@Param("owner") String owner, @Param("ids") List<String> ids);
    Topic findTopicByName(@Param("owner") String owner, @Param("normalizedName") String normalizedName);
    List<Relation> relations(@Param("owner") String owner, @Param("limit") int limit);
    Relation findRelation(@Param("owner") String owner, @Param("source") String source,
                          @Param("target") String target, @Param("type") String type);
    List<Node> graphNodes(@Param("owner") String owner, @Param("limit") int limit);
    List<Relation> graphEdges(@Param("owner") String owner, @Param("ids") List<String> ids,
                              @Param("limit") int limit);
    Long layoutRevision(@Param("owner") String owner);
    List<String> positionedActiveConcepts(@Param("owner") String owner, @Param("ids") List<String> ids);

    void insertItem(@Param("owner") String owner, @Param("id") String id, @Param("kind") String kind,
                    @Param("now") Instant now);
    void insertVersion(@Param("owner") String owner, @Param("itemId") String itemId,
                       @Param("id") String id, @Param("number") int number,
                       @Param("title") String title, @Param("body") String body, @Param("now") Instant now);
    void insertDraft(@Param("id") String id, @Param("title") String title, @Param("body") String body,
                     @Param("now") Instant now);
    int initializeVersion(@Param("owner") String owner, @Param("id") String id,
                          @Param("versionId") String versionId);
    int updateDraft(@Param("owner") String owner, @Param("id") String id,
                    @Param("expectedRevision") long expectedRevision, @Param("title") String title,
                    @Param("body") String body, @Param("now") Instant now);
    int advanceDraftRevision(@Param("owner") String owner, @Param("id") String id,
                             @Param("expectedRevision") long expectedRevision, @Param("now") Instant now);
    int publishVersion(@Param("owner") String owner, @Param("id") String id,
                       @Param("versionId") String versionId, @Param("number") int number,
                       @Param("expectedRevision") long expectedRevision, @Param("now") Instant now);
    void touchItem(@Param("owner") String owner, @Param("id") String id, @Param("now") Instant now);
    int advanceMetadata(@Param("owner") String owner, @Param("id") String id,
                        @Param("expectedRevision") long expectedRevision, @Param("now") Instant now);
    int changeStatus(@Param("owner") String owner, @Param("id") String id, @Param("status") String status,
                     @Param("expectedRevision") long expectedRevision, @Param("now") Instant now);
    void clearTopics(@Param("owner") String owner, @Param("id") String id);
    void insertTopicLink(@Param("itemId") String itemId, @Param("topicId") String topicId);
    void insertTopic(@Param("owner") String owner, @Param("id") String id, @Param("name") String name,
                     @Param("normalizedName") String normalizedName, @Param("color") String color,
                     @Param("now") Instant now);
    void insertRelation(@Param("owner") String owner, @Param("id") String id,
                        @Param("source") String source, @Param("target") String target,
                        @Param("type") String type, @Param("now") Instant now);
    void removeRelation(@Param("owner") String owner, @Param("id") String id);
    void insertPosition(@Param("owner") String owner, @Param("id") String id,
                        @Param("x") double x, @Param("y") double y);
    void updatePosition(@Param("owner") String owner, @Param("id") String id,
                        @Param("x") double x, @Param("y") double y);
    int advanceLayout(@Param("owner") String owner, @Param("expectedRevision") long expectedRevision);
    void insertOutbox(@Param("owner") String owner, @Param("itemId") String itemId,
                      @Param("versionId") String versionId, @Param("eventType") String eventType,
                      @Param("now") Instant now);
}
