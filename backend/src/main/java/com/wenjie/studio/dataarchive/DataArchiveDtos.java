package com.wenjie.studio.dataarchive;

import java.time.Instant;
import java.util.List;

public final class DataArchiveDtos {
    private DataArchiveDtos() {}

    public enum State { QUEUED, RUNNING, READY, FAILED, EXPIRED }
    public record Create(boolean confirmedSensitiveData) {}
    public record Status(boolean enabled, boolean lifecycleReady, int formatVersion, int expiryHours,
                         long maximumBytes, long maximumRows, List<String> exclusions) {}
    public record Job(String id, State state, int formatVersion, Instant createdAt, Instant startedAt,
                      Instant completedAt, Instant expiresAt, Long byteSize, String sha256,
                      Long rowCount, Integer fileCount, String errorCode, boolean downloadable) {}
    public record JobPage(List<Job> items) {}

    public record Column(String name, int jdbcType, String typeName, int size, int scale,
                         boolean nullable, boolean generated, String defaultValue, String expression) {}
    public record ForeignKey(String name, String parentTable, int ordinal, String column, String parentColumn) {}
    public record Table(String name, List<Column> columns, List<String> primaryKey, List<ForeignKey> foreignKeys,
                        String definitionSha256) {}
    public record Schema(List<Table> tables, String sha256) {}
    public record Entry(String path, String kind, String table, String attachmentId, String recordId,
                        long rows, long byteSize, String sha256) {}
    public record FileOmission(String path, String kind, String attachmentId, String recordId, String reason) {}
    public record Manifest(String format, int formatVersion, String archiveId, String ownerId,
                           Instant createdAt, Instant expiresAt, Schema schema,
                           ArchiveLifecyclePolicy.Checkpoint lifecycleCheckpoint,
                           long rowCount, long payloadBytes, List<Entry> entries, List<FileOmission> omittedFiles,
                           List<String> exclusions, List<String> transformations) {}
    public record Preview(String archiveId, String ownerId, int formatVersion, String archiveSha256,
                          String schemaSha256, long rows, int files, long bytes, String targetDatabase,
                          boolean targetEmpty, boolean targetSealed,
                          ArchiveLifecyclePolicy.Checkpoint latestCheckpoint,
                          List<String> exclusions, List<String> transformations) {}
    public record RestoreResult(String archiveId, String ownerId, String state,
                                ArchiveLifecyclePolicy.Checkpoint appliedCheckpoint,
                                long restoredRows, int restoredFiles) {}
}
