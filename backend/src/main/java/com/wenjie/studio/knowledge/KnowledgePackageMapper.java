package com.wenjie.studio.knowledge;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface KnowledgePackageMapper {
    record PackageRow(String id, long revision, String state, String packageKey, String planSha256,
                      boolean createCopy, int importCount, int skipCount, Instant createdAt, Instant committedAt) {}
    record EntryRow(int ordinal, String sourceId, String sourceSha256, int sourceBytes, int sourceCharacters,
                    String kind, String decision, String title, String tagsJson, String aliasesJson,
                    String topicIdsJson, String itemId, String versionId) {}
    record AttachmentRow(int ordinal, String sourceId, String attachmentId, String sourceSha256) {}

    PackageRow find(@Param("owner") String owner, @Param("id") String id);
    PackageRow original(@Param("owner") String owner, @Param("packageKey") String packageKey);
    List<EntryRow> entries(@Param("owner") String owner, @Param("id") String id);
    List<AttachmentRow> attachments(@Param("owner") String owner, @Param("id") String id);
    List<PackageRow> list(@Param("owner") String owner, @Param("cursorId") String cursorId,
                          @Param("cursorTime") Instant cursorTime, @Param("limit") int limit);
    void insert(@Param("owner") String owner, @Param("row") PackageRow row);
    void insertEntry(@Param("owner") String owner, @Param("id") String id, @Param("row") EntryRow row);
    void insertAttachment(@Param("owner") String owner, @Param("id") String id,
                          @Param("row") AttachmentRow row);
    int commit(@Param("owner") String owner, @Param("id") String id, @Param("revision") long revision,
               @Param("now") Instant now);
}
