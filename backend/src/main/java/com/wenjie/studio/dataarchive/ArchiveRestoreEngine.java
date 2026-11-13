package com.wenjie.studio.dataarchive;

import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.wenjie.studio.dataarchive.DataArchiveDtos.*;

/** 仅用于明确指定的空恢复库；不关闭外键、不覆盖数据、不启动 Spring 应用。 */
public final class ArchiveRestoreEngine {
    private final JsonMapper json;
    private final ArchiveFormat format;
    private final ArchiveLifecyclePolicy policy;
    private final long maximumBytes;

    public ArchiveRestoreEngine(JsonMapper json, ArchiveLifecyclePolicy policy, long maximumBytes, long maximumRows) {
        this.json = json; this.policy = policy; this.maximumBytes = maximumBytes;
        this.format = new ArchiveFormat(json, maximumBytes, maximumRows);
    }

    public Preview preview(Connection target, String targetDatabase, String owner, Path archive,
                           ArchiveLifecyclePolicy.Checkpoint latest) throws Exception {
        identity(target, targetDatabase, owner);
        var inspected = format.inspect(archive);
        Manifest manifest = inspected.manifest();
        var schema = verifyManifest(target, owner, manifest, latest);
        boolean empty = targetEmpty(target, schema);
        boolean sealed = sealed(target, owner);
        return new Preview(manifest.archiveId(), owner, manifest.formatVersion(), inspected.archiveSha256(),
                schema.sha256(), manifest.rowCount(), (int) manifest.entries().stream().filter(entry -> !"TABLE".equals(entry.kind())).count(),
                manifest.payloadBytes(), targetDatabase, empty, sealed, latest, manifest.exclusions(), manifest.transformations());
    }

    public void prepare(Connection target, String targetDatabase, String owner) throws Exception {
        identity(target, targetDatabase, owner);
        var schema = ArchiveSchema.read(target, json);
        if (!targetEmpty(target, schema)) throw new IOException("RESTORE_TARGET_NOT_EMPTY");
        try (var update = target.prepareStatement("UPDATE data_restore_gate SET state='SEALED',owner_id=?,archive_id=NULL,archive_sha256=NULL,restored_material_directory=NULL,restored_index_directory=NULL,ledger_sequence=NULL,ledger_sha256=NULL,ai_blocked=TRUE,ack_ai_config_fingerprint=NULL,ack_ai_config_version=NULL,ack_ai_config_at=NULL,updated_at=UTC_TIMESTAMP(6) WHERE singleton_id=1")) {
            update.setQueryTimeout(5); update.setString(1, owner);
            if (update.executeUpdate() != 1) throw new IOException("RESTORE_GUARD_MISSING");
        }
        target.commit();
    }

    public RestoreResult restore(Connection target, String targetDatabase, String owner, Path archive, String expectedArchiveSha256,
                                 Path emptyMaterialDirectory, Path emptyIndexDirectory,
                                 ArchiveLifecyclePolicy.Checkpoint latest) throws Exception {
        Preview preview = preview(target, targetDatabase, owner, archive, latest);
        if (!preview.targetEmpty() || !preview.targetSealed() || !preview.archiveSha256().equals(expectedArchiveSha256))
            throw new IOException("RESTORE_CONFIRMATION_MISMATCH");
        ArchivePaths.empty(emptyMaterialDirectory); ArchivePaths.empty(emptyIndexDirectory);
        requireSeparate(archive, emptyMaterialDirectory, emptyIndexDirectory);
        var inspected = format.inspect(archive);
        if (!expectedArchiveSha256.equals(inspected.archiveSha256())
                || !preview.archiveSha256().equals(inspected.archiveSha256()))
            throw new IOException("RESTORE_CONFIRMED_ARCHIVE_CHANGED");
        var schema = verifyManifest(target, owner, inspected.manifest(), latest);
        if (!targetEmpty(target, schema) || !sealed(target, owner))
            throw new IOException("RESTORE_TARGET_CHANGED");
        state(target, owner, "RESTORING", inspected.manifest().archiveId(), inspected.archiveSha256(),
                emptyMaterialDirectory, emptyIndexDirectory, latest);
        target.commit();
        try {
            // 封锁状态单独提交；正文事务回滚或进程中断都不会重新开放目标环境。
            importRows(target, archive, inspected.manifest(), expectedArchiveSha256);
            ArchiveSchema.ownerOnly(target, inspected.manifest().schema(), owner);
            verifyImportedCheckpoint(target, owner, inspected.manifest().lifecycleCheckpoint());
            for (FileOmission omitted : inspected.manifest().omittedFiles()) {
                if (policy.includeAttachment(target, owner, omitted.attachmentId()))
                    throw new IOException("RESTORE_ATTACHMENT_OMISSION_NOT_AUTHORIZED");
            }
            restoreFiles(target, archive, inspected.manifest(), expectedArchiveSha256, emptyMaterialDirectory);
            requireLatest(owner, latest);
            policy.applyLatest(target, owner, latest, emptyMaterialDirectory);
            if (!inspected.archiveSha256().equals(ArchivePaths.sha256(archive, maximumBytes))) throw new IOException("ARCHIVE_CHANGED");
            state(target, owner, "VERIFYING", inspected.manifest().archiveId(), inspected.archiveSha256(),
                    emptyMaterialDirectory, emptyIndexDirectory, latest);
            target.commit();
            finish(target, targetDatabase, owner, emptyMaterialDirectory, emptyIndexDirectory, latest);
            return new RestoreResult(inspected.manifest().archiveId(), owner, "OPEN_AI_BLOCKED", latest,
                    inspected.manifest().rowCount(), preview.files());
        } catch (Exception exception) {
            target.rollback();
            fail(target, owner);
            throw exception;
        }
    }

    /** 已提交正文但索引阶段中断时可接续；不得再次把业务包导入已有对象。 */
    public void finish(Connection target, String targetDatabase, String owner, Path materialDirectory, Path emptyIndexDirectory,
                       ArchiveLifecyclePolicy.Checkpoint latest) throws Exception {
        identity(target, targetDatabase, owner);
        try (var query = target.prepareStatement("SELECT state,owner_id,archive_id,restored_material_directory FROM data_restore_gate WHERE singleton_id=1 FOR UPDATE")) {
            query.setQueryTimeout(5);
            try (var result = query.executeQuery()) {
                if (!result.next() || !List.of("VERIFYING", "FAILED").contains(result.getString(1))
                        || !owner.equals(result.getString(2)) || result.getString(3) == null
                        || !materialDirectory.toString().equals(result.getString(4)))
                    throw new IOException("RESTORE_FINISH_NOT_ALLOWED");
            }
        }
        requireLatest(owner, latest);
        ArchivePaths.directories(materialDirectory); ArchivePaths.empty(emptyIndexDirectory);
        if (materialDirectory.startsWith(emptyIndexDirectory) || emptyIndexDirectory.startsWith(materialDirectory))
            throw new IOException("RESTORE_DIRECTORIES_OVERLAP");
        try (var update = target.prepareStatement("UPDATE data_restore_gate SET restored_index_directory=? WHERE singleton_id=1 AND owner_id=? AND state IN ('VERIFYING','FAILED')")) {
            update.setQueryTimeout(5); update.setString(1, emptyIndexDirectory.toString()); update.setString(2, owner);
            if (update.executeUpdate() != 1) throw new IOException("RESTORE_INDEX_DIRECTORY_BINDING_FAILED");
        }
        policy.applyLatest(target, owner, latest, materialDirectory);
        try {
            policy.rebuildIndexes(target, owner, emptyIndexDirectory);
            policy.verifyBeforeOpen(target, owner, latest, emptyIndexDirectory);
            requireLatest(owner, latest);
            try (var update = target.prepareStatement("UPDATE data_restore_gate SET state='OPEN',ledger_sequence=?,ledger_sha256=?,ai_blocked=TRUE,updated_at=UTC_TIMESTAMP(6) WHERE singleton_id=1 AND owner_id=? AND state IN ('VERIFYING','FAILED')")) {
                update.setQueryTimeout(5); update.setLong(1, latest.sequence()); update.setString(2, latest.sha256()); update.setString(3, owner);
                if (update.executeUpdate() != 1) throw new IOException("RESTORE_COMPLETION_LOST");
            }
            target.commit();
        } catch (Exception exception) {
            target.rollback(); fail(target, owner); throw exception;
        }
    }

    private void importRows(Connection target, Path archive, Manifest manifest, String expectedArchiveSha256) throws Exception {
        Map<String, Table> tables = new HashMap<>();
        Map<String, BitSet> restored = new HashMap<>();
        Map<String, Long> counts = new HashMap<>();
        for (Table table : manifest.schema().tables()) { tables.put("tables/" + table.name() + ".ndjson", table); restored.put(table.name(), new BitSet()); }
        long remaining = manifest.rowCount(), started = System.nanoTime();
        for (int pass = 0; (pass == 0 || remaining > 0) && pass < 1000; pass++) {
            long[] inserted = {0};
            readConfirmedArchive(archive, manifest, expectedArchiveSha256, (entry, payload) -> {
                    if (!"TABLE".equals(entry.kind())) return;
                    Table table = tables.get(entry.path());
                    if (table == null) throw new IOException("RESTORE_TABLE_NOT_IN_SCHEMA");
                    List<Column> columns = ArchiveFormat.writable(table);
                    String fields = columns.stream().map(column -> ArchiveSchema.quote(column.name())).collect(java.util.stream.Collectors.joining(","));
                    String placeholders = String.join(",", java.util.Collections.nCopies(columns.size(), "?"));
                    BitSet done = restored.get(table.name());
                    int rowNumber = 0;
                    InputStream buffered = new BufferedInputStream(payload, 65_536);
                    try (PreparedStatement insert = target.prepareStatement("INSERT INTO " + ArchiveSchema.quote(table.name()) + "(" + fields + ") VALUES(" + placeholders + ")")) {
                        insert.setQueryTimeout(5);
                        byte[] line;
                        while ((line = ArchiveFormat.line(buffered)) != null) {
                            ArchiveFormat.time(started);
                            if (rowNumber == Integer.MAX_VALUE || rowNumber >= entry.rows())
                                throw new IOException("RESTORE_RECORD_COUNT_MISMATCH");
                            int index = rowNumber++;
                            if (done.get(index)) continue;
                            List<String> values = format.decodeRow(line, columns.size());
                            for (int column = 0; column < columns.size(); column++) {
                                String value = values.get(column);
                                if (columns.get(column).name().equals("owner_id") && !manifest.ownerId().equals(value))
                                    throw new IOException("ARCHIVE_OTHER_OWNER_PRESENT");
                                if (value == null) insert.setNull(column + 1, columns.get(column).jdbcType());
                                else insert.setString(column + 1, value);
                            }
                            try {
                                if (insert.executeUpdate() != 1) throw new SQLException("RESTORE_INSERT_FAILED");
                                done.set(index); inserted[0]++;
                            } catch (SQLException exception) {
                                // MySQL 1452 仅回滚该语句；其他错误不能误当依赖等待或静默忽略。
                                if (exception.getErrorCode() != 1452 || !"23000".equals(exception.getSQLState())) throw exception;
                            }
                        }
                    }
                    if (rowNumber != entry.rows()) throw new IOException("RESTORE_RECORD_COUNT_MISMATCH");
                    counts.put(table.name(), (long) rowNumber);
            });
            remaining -= inserted[0];
            if (remaining > 0 && inserted[0] == 0) throw new IOException("RESTORE_UNRESOLVED_REFERENCES");
        }
        if (remaining != 0) throw new IOException("RESTORE_DEPENDENCY_LIMIT");
        for (Entry entry : manifest.entries()) if ("TABLE".equals(entry.kind())
                && counts.getOrDefault(entry.table(), 0L) != entry.rows()) throw new IOException("RESTORE_RECORD_COUNT_MISMATCH");
    }

    private void restoreFiles(Connection target, Path archive, Manifest manifest, String expectedArchiveSha256,
                              Path materialRoot) throws Exception {
        readConfirmedArchive(archive, manifest, expectedArchiveSha256, (entry, payload) -> {
                if ("TABLE".equals(entry.kind())) return;
                verifyAttachmentRecord(target, manifest.ownerId(), entry);
                if (!policy.includeAttachment(target, manifest.ownerId(), entry.attachmentId())) return;
                String[] parts = entry.path().substring("attachments/".length()).split("/");
                Path parent = materialRoot;
                for (int index = 0; index < parts.length - 1; index++) parent = ArchivePaths.childDirectory(parent, parts[index], true);
                Path file = parent.resolve(parts[parts.length - 1]);
                long bytes = 0;
                try (OutputStream output = ArchivePaths.create(file)) {
                    byte[] buffer = new byte[65_536];
                    for (int count; (count = payload.read(buffer)) != -1;) {
                        bytes += count; if (bytes > entry.byteSize()) throw new IOException("RESTORE_ATTACHMENT_SIZE_MISMATCH");
                        output.write(buffer, 0, count);
                    }
                }
                if (bytes != entry.byteSize() || !entry.sha256().equals(ArchivePaths.sha256(file, 20_971_520)))
                    throw new IOException("RESTORE_ATTACHMENT_DIGEST_MISMATCH");
        });
    }

    @FunctionalInterface
    private interface EntryReader { void read(Entry entry, InputStream payload) throws Exception; }

    private void readConfirmedArchive(Path archive, Manifest manifest, String expectedArchiveSha256,
                                       EntryReader consumer) throws Exception {
        Map<String, Entry> expected = new HashMap<>();
        manifest.entries().forEach(entry -> expected.put(entry.path(), entry));
        var seen = new HashSet<String>();
        boolean manifestSeen = false;
        try (var file = ArchiveFormat.openChecked(archive, maximumBytes);
             var zip = new ZipInputStream(file, StandardCharsets.UTF_8)) {
            for (ZipEntry next; (next = zip.getNextEntry()) != null;) {
                if (next.isDirectory() || !seen.add(next.getName())) throw new IOException("RESTORE_ARCHIVE_ENTRY_CHANGED");
                if ("manifest.json".equals(next.getName())) {
                    byte[] bytes = zip.readNBytes(ArchiveFormat.MAX_MANIFEST_BYTES + 1);
                    if (bytes.length > ArchiveFormat.MAX_MANIFEST_BYTES) throw new IOException("ARCHIVE_MANIFEST_LIMIT");
                    try {
                        if (!manifest.equals(json.readValue(bytes, Manifest.class)))
                            throw new IOException("RESTORE_ARCHIVE_MANIFEST_CHANGED");
                    } catch (RuntimeException exception) { throw new IOException("RESTORE_ARCHIVE_MANIFEST_CHANGED"); }
                    manifestSeen = true;
                    continue;
                }
                Entry entry = expected.remove(next.getName());
                if (entry == null) throw new IOException("RESTORE_ARCHIVE_ENTRY_CHANGED");
                var payload = new ArchiveFormat.CheckedInput(zip, entry.byteSize(), false);
                consumer.read(entry, payload);
                // 包括已导入行、非本阶段载荷和已清除附件，每次实际读取都必须对应同一清单。
                payload.verify(entry);
            }
            if (!manifestSeen || !expected.isEmpty()) throw new IOException("RESTORE_ARCHIVE_ENTRY_MISSING");
            if (!expectedArchiveSha256.equals(file.finish())) throw new IOException("RESTORE_CONFIRMED_ARCHIVE_CHANGED");
        }
    }

    private Schema verifyManifest(Connection target, String owner, Manifest manifest,
                                  ArchiveLifecyclePolicy.Checkpoint latest) throws Exception {
        if (!owner.equals(manifest.ownerId())) throw new IOException("RESTORE_OWNER_CONFLICT");
        var schema = ArchiveSchema.read(target, json);
        if (!schema.equals(manifest.schema())) throw new IOException("RESTORE_SCHEMA_CONFLICT");
        requireLatest(owner, latest);
        policy.verifyArchiveCheckpoint(owner, manifest.lifecycleCheckpoint(), latest);
        return schema;
    }

    private static void verifyAttachmentRecord(Connection target, String owner, Entry entry) throws SQLException, IOException {
        String sql = "ATTACHMENT".equals(entry.kind())
                ? "SELECT 1 FROM material_attachment WHERE owner_id=? AND id=? AND ready_upload_id=? AND byte_size=? AND sha256=?"
                : "SELECT 1 FROM material_parse_run WHERE owner_id=? AND attachment_id=? AND id=? AND status='SUCCEEDED'";
        try (var query = target.prepareStatement(sql)) {
            query.setQueryTimeout(5); query.setString(1, owner); query.setString(2, entry.attachmentId()); query.setString(3, entry.recordId());
            if ("ATTACHMENT".equals(entry.kind())) { query.setLong(4, entry.byteSize()); query.setString(5, entry.sha256()); }
            try (var result = query.executeQuery()) { if (!result.next()) throw new IOException("RESTORE_ATTACHMENT_RECORD_CONFLICT"); }
        }
    }

    private static void verifyImportedCheckpoint(Connection target, String owner,
                                                  ArchiveLifecyclePolicy.Checkpoint archived) throws SQLException, IOException {
        try (var query = target.prepareStatement("SELECT ledger_sequence,ledger_hash FROM privacy_ledger_checkpoint WHERE owner_id=?")) {
            query.setQueryTimeout(5); query.setString(1, owner);
            try (var result = query.executeQuery()) {
                if (!result.next() || result.getLong(1) != archived.sequence()
                        || !result.getString(2).equals(archived.sha256()))
                    throw new IOException("RESTORE_IMPORTED_CHECKPOINT_MISMATCH");
            }
        }
    }

    private static void identity(Connection target, String database, String owner) throws SQLException, IOException {
        ArchivePaths.uuid(owner);
        if (database == null || !database.matches("[a-zA-Z0-9_]{1,64}") || !database.equals(target.getCatalog()))
            throw new IOException("RESTORE_DATABASE_CONFIRMATION_MISMATCH");
        if (target.getAutoCommit()) throw new IOException("RESTORE_TRANSACTION_REQUIRED");
    }

    private void requireLatest(String owner, ArchiveLifecyclePolicy.Checkpoint expected) throws IOException {
        if (expected == null || !expected.equals(policy.current(owner))) throw new IOException("RESTORE_LATEST_LEDGER_REQUIRED");
    }

    private static boolean targetEmpty(Connection target, Schema schema) throws SQLException {
        if (!ArchiveSchema.empty(target, schema)) return false;
        for (String name : List.of("data_archive_job", "search_projection", "search_generation_event")) {
            try (var query = target.createStatement()) {
                query.setQueryTimeout(5);
                try (var result = query.executeQuery("SELECT 1 FROM " + ArchiveSchema.quote(name) + " LIMIT 1")) { if (result.next()) return false; }
            }
        }
        return true;
    }

    private static boolean sealed(Connection target, String owner) throws SQLException {
        try (var query = target.prepareStatement("SELECT 1 FROM data_restore_gate WHERE singleton_id=1 AND state='SEALED' AND owner_id=? AND ai_blocked=TRUE")) {
            query.setQueryTimeout(5); query.setString(1, owner);
            try (var result = query.executeQuery()) { return result.next(); }
        }
    }

    private static void state(Connection target, String owner, String state, String archive, String digest,
                              Path materials, Path indexes, ArchiveLifecyclePolicy.Checkpoint latest) throws SQLException {
        try (var update = target.prepareStatement("UPDATE data_restore_gate SET state=?,owner_id=?,archive_id=?,archive_sha256=?,ledger_sequence=?,ledger_sha256=?,restored_material_directory=?,restored_index_directory=?,ai_blocked=TRUE,ack_ai_config_fingerprint=NULL,ack_ai_config_version=NULL,ack_ai_config_at=NULL,updated_at=UTC_TIMESTAMP(6) WHERE singleton_id=1")) {
            update.setQueryTimeout(5); update.setString(1, state); update.setString(2, owner); update.setString(3, archive); update.setString(4, digest);
            update.setLong(5, latest.sequence()); update.setString(6, latest.sha256());
            update.setString(7, materials.toString()); update.setString(8, indexes.toString());
            if (update.executeUpdate() != 1) throw new SQLException("RESTORE_GUARD_MISSING");
        }
    }

    private static void fail(Connection target, String owner) throws SQLException {
        try (var update = target.prepareStatement("UPDATE data_restore_gate SET state='FAILED',ai_blocked=TRUE,updated_at=UTC_TIMESTAMP(6) WHERE singleton_id=1 AND owner_id=? AND state<>'OPEN'")) {
            update.setQueryTimeout(5); update.setString(1, owner); update.executeUpdate();
        }
        target.commit();
    }

    private static void requireSeparate(Path archive, Path materials, Path indexes) throws IOException {
        if (archive.startsWith(materials) || archive.startsWith(indexes) || materials.startsWith(indexes) || indexes.startsWith(materials))
            throw new IOException("RESTORE_DIRECTORIES_OVERLAP");
    }
}
