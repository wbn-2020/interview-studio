package com.wenjie.studio.knowledge;

import com.wenjie.studio.knowledge.OrganizationDtos.Connection;
import com.wenjie.studio.knowledge.OrganizationRows.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface OrganizationMapper {
    OrganizationRow organization(@Param("owner") String owner, @Param("id") String id);
    List<Association> associations(@Param("owner") String owner, @Param("id") String id);
    void ensureOrganization(@Param("owner") String owner, @Param("id") String id, @Param("now") Instant now);
    int saveOrganization(@Param("owner") String owner, @Param("id") String id,
                         @Param("revision") long revision, @Param("aliases") String aliases,
                         @Param("tags") String tags, @Param("favorite") boolean favorite,
                         @Param("mainNoteId") String mainNoteId, @Param("now") Instant now);
    void visit(@Param("owner") String owner, @Param("id") String id, @Param("now") Instant now);
    void insertAssociation(@Param("owner") String owner, @Param("id") String id,
                           @Param("conceptId") String conceptId, @Param("noteId") String noteId,
                           @Param("now") Instant now);
    void removeAssociation(@Param("owner") String owner, @Param("id") String id);
    List<Connection> connections(@Param("owner") String owner, @Param("id") String id,
                                 @Param("type") String type, @Param("cursor") String cursor,
                                 @Param("limit") int limit);
    List<LibraryItem> library(@Param("owner") String owner, @Param("collection") String collection,
                              @Param("kind") String kind, @Param("status") String status,
                              @Param("pattern") String pattern, @Param("topicId") String topicId,
                              @Param("cursorId") String cursorId,
                              @Param("cursorTime") Instant cursorTime, @Param("limit") int limit);
    List<String> availableTargetIds(@Param("owner") String owner, @Param("ids") List<String> ids);
    List<UnscannedVersion> unscannedVersions(@Param("owner") String owner, @Param("limit") int limit);
    boolean referenceScanExists(@Param("owner") String owner, @Param("versionId") String versionId);
    void insertReference(@Param("owner") String owner, @Param("id") String id,
                         @Param("itemId") String itemId, @Param("versionId") String versionId,
                         @Param("versionNumber") int versionNumber, @Param("targetId") String targetId,
                         @Param("ordinal") int ordinal, @Param("lineStart") int lineStart,
                         @Param("lineEnd") int lineEnd, @Param("excerpt") String excerpt,
                         @Param("now") Instant now);
    void markReferencesScanned(@Param("owner") String owner, @Param("itemId") String itemId,
                               @Param("versionId") String versionId, @Param("unresolved") int unresolved,
                               @Param("parserVersion") String parserVersion, @Param("now") Instant now);
}
