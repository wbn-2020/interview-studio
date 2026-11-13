package com.wenjie.studio.knowledge;

import java.time.Instant;
import java.util.List;

public final class KnowledgeTextImportBatchDtos {
    private KnowledgeTextImportBatchDtos() {}

    public record FileEntry(String fileName, String sourceSha256, Integer sourceBytes, Integer sourceCharacters) {}
    public record CreateBatch(List<FileEntry> files) {}
    public record ImportEntry(Long revision, String source, String title, String body,
                              List<String> topicIds, Boolean createCopy) {}
    public record SkipEntry(Long revision) {}
    public record PublishLinks(String expectedVersionId, Long expectedDraftRevision, String body) {}
    public record Entry(String id, int ordinal, String fileName, String sourceSha256, int sourceBytes,
                        int sourceCharacters, String state, boolean hasRegisteredSource,
                        KnowledgeTextImportDtos.Entry record) {}
    public record Detail(String id, long revision, String state, Instant createdAt, List<Entry> entries) {}
    public record Summary(String id, long revision, String state, int total, int imported, int skipped,
                          Instant createdAt) {}
    public record Page(List<Summary> items, String nextCursor) {}

    public record DirectoryFile(String fileName, String sourceSha256, Integer sourceBytes,
                                Integer sourceCharacters, String sourcePath) {}
    public record CreateDirectoryBatch(List<DirectoryFile> files) {}
    public record EntryPath(String entryId, String sourcePath) {}
    public record DirectoryDetail(Detail batch, List<EntryPath> paths) {}
}
