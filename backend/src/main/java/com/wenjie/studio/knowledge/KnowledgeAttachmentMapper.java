package com.wenjie.studio.knowledge;

import com.wenjie.studio.knowledge.OrganizationRows.UnscannedVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface KnowledgeAttachmentMapper {
    record Attachment(String id, String sha256) {}

    List<Attachment> readyAttachments(@Param("owner") String owner, @Param("ids") List<String> ids);
    List<UnscannedVersion> unscannedVersions(@Param("owner") String owner, @Param("limit") int limit);
    boolean scanExists(@Param("owner") String owner, @Param("versionId") String versionId);
    void insertReference(@Param("owner") String owner, @Param("id") String id,
                         @Param("itemId") String itemId, @Param("versionId") String versionId,
                         @Param("versionNumber") int versionNumber, @Param("attachmentId") String attachmentId,
                         @Param("sha256") String sha256, @Param("ordinal") int ordinal,
                         @Param("kind") String kind, @Param("lineStart") int lineStart,
                         @Param("lineEnd") int lineEnd, @Param("now") Instant now);
    void markScanned(@Param("owner") String owner, @Param("itemId") String itemId,
                     @Param("versionId") String versionId, @Param("referenceCount") int referenceCount,
                     @Param("unresolved") int unresolved, @Param("parserVersion") String parserVersion,
                     @Param("now") Instant now);
}
