package com.wenjie.studio.knowledge;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

import static com.wenjie.studio.knowledge.KnowledgeTextImportBatchDtos.Summary;

@Mapper
public interface KnowledgeTextImportBatchMapper {
    record BatchRow(String id, long revision, int total, Instant createdAt) {}
    record EntryRow(String id, int ordinal, String fileName, String sourceSha256, int sourceBytes,
                    int sourceCharacters, String state, boolean hasRegisteredSource, String importRecordId,
                    String sourcePath) {}

    BatchRow find(@Param("owner") String owner, @Param("id") String id);
    List<EntryRow> entries(@Param("owner") String owner, @Param("batchId") String batchId);
    List<Summary> list(@Param("owner") String owner, @Param("cursorId") String cursorId,
                       @Param("cursorTime") Instant cursorTime, @Param("limit") int limit);
    void insertBatch(@Param("owner") String owner, @Param("batch") BatchRow batch);
    void insertEntry(@Param("owner") String owner, @Param("batchId") String batchId, @Param("entry") EntryRow entry);
    int resolve(@Param("owner") String owner, @Param("batchId") String batchId, @Param("entryId") String entryId,
                @Param("state") String state, @Param("recordId") String recordId);
    int advance(@Param("owner") String owner, @Param("id") String id, @Param("revision") long revision);
}
