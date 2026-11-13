package com.wenjie.studio.dataarchive;

import com.wenjie.studio.material.MaterialStorage;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static com.wenjie.studio.dataarchive.DataArchiveDtos.*;

final class ArchiveFormat {
    static final String FORMAT = "wenjie-full-archive";
    static final int VERSION = 1;
    static final int MAX_ROW_BYTES = 4_194_304;
    static final int MAX_MANIFEST_BYTES = 4_194_304;
    static final int MAX_ENTRIES = 20_000;
    static final List<String> EXCLUSIONS = List.of(
            "凭据、HTTP会话与会话令牌、环境配置、独立生命周期账本原文件",
            "Lucene索引及重建检查点、导出作业与历史导出文件",
            "失败或未完成传输的临时文件、解析器启动器及临时进程文件");
    static final List<String> TRANSFORMATIONS = List.of(
            "保留业务ID、发布版本、关系和业务记录；生成列由目标数据库按定义计算",
            "原worker claim_token不导出；AI已发送终态记录使用固定不可执行占位标识保留sent_at事实",
            "恢复必须重放最新独立生命周期账本、撤销旧AI授权、重建新索引后才可解除封锁");
    private static final String SENTINEL = "00000000-0000-0000-0000-000000000000";
    private final JsonMapper json;
    private final long maximumBytes;
    private final long maximumRows;

    ArchiveFormat(JsonMapper json, long maximumBytes, long maximumRows) {
        this.json = json.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        this.maximumBytes = maximumBytes; this.maximumRows = maximumRows;
    }

    Manifest write(Connection source, Schema schema, String owner, String archiveId, Instant createdAt, Instant expiresAt,
                   ArchiveLifecyclePolicy policy, MaterialStorage materials, Path output) throws Exception {
        long started = System.nanoTime();
        var checkpoint = policy.current(owner);
        policy.verifyExportCheckpoint(source, owner, checkpoint);
        List<Entry> entries = new ArrayList<>();
        List<FileOmission> omitted = new ArrayList<>();
        long[] totals = {0, 0};
        try (OutputStream file = ArchivePaths.create(output);
             ZipOutputStream zip = new ZipOutputStream(new BoundedOutput(file, maximumBytes), StandardCharsets.UTF_8)) {
            for (Table table : schema.tables()) {
                time(started);
                String path = "tables/" + table.name() + ".ndjson";
                zip.putNextEntry(new ZipEntry(path));
                MessageDigest digest = ArchivePaths.digest();
                long bytes = 0, rows = 0;
                List<Column> columns = writable(table);
                String names = columns.stream().map(column -> ArchiveSchema.quote(column.name())).collect(java.util.stream.Collectors.joining(","));
                String order = table.primaryKey().stream().map(ArchiveSchema::quote).collect(java.util.stream.Collectors.joining(","));
                try (var query = source.createStatement()) {
                    query.setQueryTimeout(15); query.setFetchSize(200);
                    try (var result = query.executeQuery("SELECT " + names + " FROM " + ArchiveSchema.quote(table.name()) + " ORDER BY " + order)) {
                        while (result.next()) {
                            time(started);
                            List<String> values = new ArrayList<>(columns.size());
                            for (int index = 0; index < columns.size(); index++) {
                                Column column = columns.get(index);
                                String value = result.getString(index + 1);
                                if ((column.jdbcType() == java.sql.Types.BIT || column.jdbcType() == java.sql.Types.BOOLEAN) && value != null)
                                    value = result.getBoolean(index + 1) ? "1" : "0";
                                if (column.name().equals("claim_token"))
                                    value = table.name().equals("ai_attempt") && result.getString("sent_at") != null ? SENTINEL : null;
                                if (column.name().equals("runtime_epoch")) value = SENTINEL;
                                values.add(value);
                            }
                            byte[] line = json.writeValueAsBytes(values);
                            if (line.length > MAX_ROW_BYTES || ++totals[0] > maximumRows) throw new IOException("ARCHIVE_RECORD_LIMIT");
                            totals[1] += line.length + 1L; bytes += line.length + 1L; rows++;
                            if (totals[1] > maximumBytes) throw new IOException("ARCHIVE_PAYLOAD_LIMIT");
                            zip.write(line); zip.write('\n'); digest.update(line); digest.update((byte) '\n');
                        }
                    }
                }
                zip.closeEntry();
                entries.add(new Entry(path, "TABLE", table.name(), null, null, rows, bytes, HexFormat.of().formatHex(digest.digest())));
            }
            exportFiles(source, owner, policy, materials, zip, entries, omitted, totals, started);
            if (!checkpoint.equals(policy.current(owner))) throw new IOException("ARCHIVE_LIFECYCLE_CHANGED");
            Manifest manifest = new Manifest(FORMAT, VERSION, archiveId, owner, createdAt, expiresAt, schema, checkpoint,
                    totals[0], totals[1], List.copyOf(entries), List.copyOf(omitted), EXCLUSIONS, TRANSFORMATIONS);
            byte[] bytes = json.writeValueAsBytes(manifest);
            if (bytes.length > MAX_MANIFEST_BYTES) throw new IOException("ARCHIVE_MANIFEST_LIMIT");
            zip.putNextEntry(new ZipEntry("manifest.json")); zip.write(bytes); zip.closeEntry();
            return manifest;
        }
    }

    private void exportFiles(Connection source, String owner, ArchiveLifecyclePolicy policy, MaterialStorage materials,
                             ZipOutputStream zip, List<Entry> entries, List<FileOmission> omitted, long[] totals, long started) throws Exception {
        try (var query = source.prepareStatement("SELECT id,ready_upload_id,byte_size,sha256 FROM material_attachment WHERE owner_id=? AND status='READY' ORDER BY id")) {
            query.setQueryTimeout(5); query.setString(1, owner);
            try (var result = query.executeQuery()) {
                while (result.next()) {
                    String attachment = result.getString(1), upload = result.getString(2);
                    String path = "attachments/" + owner + "/" + attachment + "/uploads/" + upload + ".bin";
                    if (!policy.includeAttachment(source, owner, attachment)) {
                        omitted.add(new FileOmission(path, "ATTACHMENT", attachment, upload, "PRIVACY_CLEARED"));
                        continue;
                    }
                    Path file = materials.uploadPath(owner, attachment, upload, false);
                    Entry entry = addFile(file, path,
                            "ATTACHMENT", attachment, upload, zip, totals, started);
                    if (entry.byteSize() != result.getLong(3) || !entry.sha256().equals(result.getString(4)))
                        throw new IOException("ARCHIVE_ATTACHMENT_CHANGED");
                    entries.add(entry);
                }
            }
        }
        try (var query = source.prepareStatement("SELECT id,attachment_id,has_preview FROM material_parse_run WHERE owner_id=? AND status='SUCCEEDED' ORDER BY id")) {
            query.setQueryTimeout(5); query.setString(1, owner);
            try (var result = query.executeQuery()) {
                while (result.next()) {
                    String run = result.getString(1), attachment = result.getString(2);
                    String base = "attachments/" + owner + "/" + attachment + "/runs/" + run + "/";
                    if (!policy.includeAttachment(source, owner, attachment)) {
                        omitted.add(new FileOmission(base + "result.json", "PARSE_RESULT", attachment, run, "PRIVACY_CLEARED"));
                        if (result.getBoolean(3)) omitted.add(new FileOmission(base + "preview.png", "PREVIEW", attachment, run, "PRIVACY_CLEARED"));
                        continue;
                    }
                    Path directory = materials.runDirectory(owner, attachment, run, false);
                    entries.add(addFile(directory.resolve("result.json"), base + "result.json", "PARSE_RESULT", attachment, run, zip, totals, started));
                    if (result.getBoolean(3)) entries.add(addFile(directory.resolve("preview.png"), base + "preview.png", "PREVIEW", attachment, run, zip, totals, started));
                }
            }
        }
    }

    private Entry addFile(Path file, String path, String kind, String attachment, String record, ZipOutputStream zip,
                          long[] totals, long started) throws IOException {
        long size = ArchivePaths.regular(file, 20_971_520, false);
        MessageDigest digest = ArchivePaths.digest();
        long bytes = 0;
        zip.putNextEntry(new ZipEntry(path));
        try (InputStream input = ArchivePaths.open(file, 20_971_520)) {
            byte[] buffer = new byte[65_536];
            for (int count; (count = input.read(buffer)) != -1;) {
                time(started); bytes += count; totals[1] += count;
                if (bytes > size || totals[1] > maximumBytes) throw new IOException("ARCHIVE_PAYLOAD_LIMIT");
                zip.write(buffer, 0, count); digest.update(buffer, 0, count);
            }
        }
        if (bytes != size) throw new IOException("ARCHIVE_ATTACHMENT_CHANGED");
        zip.closeEntry();
        return new Entry(path, kind, null, attachment, record, 0, bytes, HexFormat.of().formatHex(digest.digest()));
    }

    record Inspected(Manifest manifest, String archiveSha256) {}

    Inspected inspect(Path archive) throws IOException {
        String archiveSha256;
        Map<String, Entry> actual = new HashMap<>();
        Manifest manifest = null;
        long total = 0, rows = 0;
        long started = System.nanoTime();
        try (CheckedInput file = openChecked(archive, maximumBytes); ZipInputStream zip = new ZipInputStream(file, StandardCharsets.UTF_8)) {
            for (ZipEntry next; (next = zip.getNextEntry()) != null;) {
                time(started);
                String path = next.getName();
                if (next.isDirectory() || actual.containsKey(path) || actual.size() >= MAX_ENTRIES
                        || path.contains("\\") || path.contains("..") || path.startsWith("/"))
                    throw new IOException("ARCHIVE_ENTRY_INVALID");
                if (path.equals("manifest.json")) {
                    if (manifest != null) throw new IOException("ARCHIVE_MANIFEST_DUPLICATE");
                    byte[] bytes = readLimited(zip, MAX_MANIFEST_BYTES);
                    try { manifest = json.readValue(bytes, Manifest.class); }
                    catch (RuntimeException exception) { throw new IOException("ARCHIVE_MANIFEST_INVALID"); }
                    continue;
                }
                if (!path.matches("tables/[a-z][a-z0-9_]{0,63}\\.ndjson")
                        && !path.matches("attachments/[a-f0-9-]{36}/[a-f0-9-]{36}/(?:uploads/[a-f0-9-]{36}\\.bin|runs/[a-f0-9-]{36}/(?:preview\\.png|result\\.json))"))
                    throw new IOException("ARCHIVE_ENTRY_UNSUPPORTED");
                MessageDigest digest = ArchivePaths.digest();
                long size = 0, lines = 0;
                int last = -1, lineBytes = 0;
                byte[] buffer = new byte[65_536];
                for (int count; (count = zip.read(buffer)) != -1;) {
                    time(started); total += count; size += count;
                    if (total > maximumBytes) throw new IOException("ARCHIVE_PAYLOAD_LIMIT");
                    digest.update(buffer, 0, count);
                    if (path.startsWith("tables/")) for (int index = 0; index < count; index++) {
                        if (buffer[index] == '\n') { lines++; lineBytes = 0; }
                        else if (++lineBytes > MAX_ROW_BYTES) throw new IOException("ARCHIVE_RECORD_LIMIT");
                    }
                    last = buffer[count - 1];
                }
                if (path.startsWith("tables/") && size > 0 && last != '\n') throw new IOException("ARCHIVE_ROW_TRUNCATED");
                rows += lines;
                if (rows > maximumRows) throw new IOException("ARCHIVE_RECORD_LIMIT");
                actual.put(path, new Entry(path, null, null, null, null, lines, size, HexFormat.of().formatHex(digest.digest())));
            }
            // 摘要与解析共享同一文件句柄及同一次实际字节流，不能用另一次路径读取替代。
            archiveSha256 = file.finish();
        }
        validateManifest(manifest, actual, rows, total);
        verifyFileInventory(archive, manifest, archiveSha256);
        if (!archiveSha256.equals(ArchivePaths.sha256(archive, maximumBytes))) throw new IOException("ARCHIVE_CHANGED");
        return new Inspected(manifest, archiveSha256);
    }

    private void validateManifest(Manifest manifest, Map<String, Entry> actual, long rows, long total) throws IOException {
        try {
            if (manifest == null || !FORMAT.equals(manifest.format()) || manifest.formatVersion() != VERSION
                    || manifest.createdAt() == null || manifest.expiresAt() == null || !manifest.expiresAt().isAfter(manifest.createdAt())
                    || manifest.schema() == null || manifest.schema().tables() == null || manifest.schema().tables().size() > 500
                    || manifest.lifecycleCheckpoint() == null || manifest.exclusions() == null || manifest.transformations() == null
                    || manifest.entries() == null || manifest.entries().size() != actual.size()
                    || manifest.omittedFiles() == null || manifest.omittedFiles().size() > MAX_ENTRIES
                    || manifest.rowCount() != rows || manifest.payloadBytes() != total) throw new IOException("ARCHIVE_MANIFEST_MISMATCH");
            ArchivePaths.uuid(manifest.ownerId()); ArchivePaths.uuid(manifest.archiveId());
            if (!ArchivePaths.sha256(json.writeValueAsBytes(manifest.schema().tables())).equals(manifest.schema().sha256()))
                throw new IOException("ARCHIVE_SCHEMA_DIGEST_MISMATCH");
            Set<String> names = new HashSet<>(), tables = new HashSet<>();
            for (Table table : manifest.schema().tables()) if (!tables.add(table.name())) throw new IOException("ARCHIVE_SCHEMA_DUPLICATE");
            for (Entry entry : manifest.entries()) {
                if (entry == null || !names.add(entry.path())) throw new IOException("ARCHIVE_ENTRY_DUPLICATE");
                Entry value = actual.get(entry.path());
                if (value == null || !value.sha256().equals(entry.sha256()) || value.byteSize() != entry.byteSize() || value.rows() != entry.rows())
                    throw new IOException("ARCHIVE_ENTRY_DIGEST_MISMATCH");
                if ("TABLE".equals(entry.kind())) {
                    if (!tables.remove(entry.table()) || !entry.path().equals("tables/" + entry.table() + ".ndjson"))
                        throw new IOException("ARCHIVE_TABLE_MISMATCH");
                } else {
                    ArchivePaths.uuid(entry.attachmentId()); ArchivePaths.uuid(entry.recordId());
                    String base = "attachments/" + manifest.ownerId() + "/" + entry.attachmentId();
                    String expected = switch (entry.kind()) {
                        case "ATTACHMENT" -> base + "/uploads/" + entry.recordId() + ".bin";
                        case "PARSE_RESULT" -> base + "/runs/" + entry.recordId() + "/result.json";
                        case "PREVIEW" -> base + "/runs/" + entry.recordId() + "/preview.png";
                        default -> throw new IOException("ARCHIVE_FILE_KIND_INVALID");
                    };
                    if (!expected.equals(entry.path()) || entry.rows() != 0 || entry.byteSize() < 1 || entry.byteSize() > 20_971_520)
                        throw new IOException("ARCHIVE_ATTACHMENT_MISMATCH");
                }
            }
            if (!tables.isEmpty()) throw new IOException("ARCHIVE_TABLE_MISSING");
            for (FileOmission omitted : manifest.omittedFiles()) {
                if (omitted == null || !names.add(omitted.path()) || !"PRIVACY_CLEARED".equals(omitted.reason()))
                    throw new IOException("ARCHIVE_OMISSION_INVALID");
                ArchivePaths.uuid(omitted.attachmentId()); ArchivePaths.uuid(omitted.recordId());
                if (!expectedFilePath(manifest.ownerId(), omitted.kind(), omitted.attachmentId(), omitted.recordId()).equals(omitted.path()))
                    throw new IOException("ARCHIVE_OMISSION_INVALID");
            }
        } catch (IllegalArgumentException | NullPointerException exception) { throw new IOException("ARCHIVE_MANIFEST_INVALID"); }
    }

    private void verifyFileInventory(Path archive, Manifest manifest, String expectedArchiveSha256) throws IOException {
        Map<String, Entry> files = new HashMap<>();
        Map<String, FileOmission> omitted = new HashMap<>();
        manifest.entries().stream().filter(entry -> !"TABLE".equals(entry.kind())).forEach(entry -> files.put(entry.path(), entry));
        manifest.omittedFiles().forEach(entry -> omitted.put(entry.path(), entry));
        Map<String, Table> relevant = new HashMap<>();
        manifest.schema().tables().stream().filter(table -> Set.of("material_attachment", "material_parse_run").contains(table.name()))
                .forEach(table -> relevant.put("tables/" + table.name() + ".ndjson", table));
        try (CheckedInput file = openChecked(archive, maximumBytes); ZipInputStream zip = new ZipInputStream(file, StandardCharsets.UTF_8)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                Table table = relevant.get(entry.getName());
                if (table == null) continue;
                List<Column> columns = writable(table);
                Map<String, Integer> indexes = new HashMap<>();
                for (int index = 0; index < columns.size(); index++) indexes.put(columns.get(index).name(), index);
                InputStream buffered = new java.io.BufferedInputStream(zip, 65_536);
                for (byte[] line; (line = line(buffered)) != null;) {
                    List<String> values = decodeRow(line, columns.size());
                    String status = values.get(indexes.get("status"));
                    if (table.name().equals("material_attachment") && status.equals("READY")) {
                        String id = values.get(indexes.get("id")), upload = values.get(indexes.get("ready_upload_id"));
                        String path = expectedFilePath(manifest.ownerId(), "ATTACHMENT", id, upload);
                        Entry actual = requireInventory(path, files, omitted);
                        if (actual != null && (actual.byteSize() != Long.parseLong(values.get(indexes.get("byte_size")))
                                || !actual.sha256().equals(values.get(indexes.get("sha256")))))
                            throw new IOException("ARCHIVE_ATTACHMENT_RECORD_MISMATCH");
                    } else if (table.name().equals("material_parse_run") && status.equals("SUCCEEDED")) {
                        String attachment = values.get(indexes.get("attachment_id")), run = values.get(indexes.get("id"));
                        requireInventory(expectedFilePath(manifest.ownerId(), "PARSE_RESULT", attachment, run), files, omitted);
                        String preview = values.get(indexes.get("has_preview"));
                        if ("1".equals(preview) || "true".equalsIgnoreCase(preview))
                            requireInventory(expectedFilePath(manifest.ownerId(), "PREVIEW", attachment, run), files, omitted);
                    }
                }
            }
            if (!expectedArchiveSha256.equals(file.finish())) throw new IOException("ARCHIVE_CHANGED");
        } catch (IllegalArgumentException | NullPointerException exception) { throw new IOException("ARCHIVE_ATTACHMENT_RECORD_INVALID"); }
        if (!files.isEmpty() || !omitted.isEmpty()) throw new IOException("ARCHIVE_ATTACHMENT_ORPHAN");
    }

    private static Entry requireInventory(String path, Map<String, Entry> files, Map<String, FileOmission> omissions) throws IOException {
        Entry file = files.remove(path);
        if (file == null && omissions.remove(path) == null) throw new IOException("ARCHIVE_ATTACHMENT_MISSING");
        return file;
    }

    private static String expectedFilePath(String owner, String kind, String attachment, String record) throws IOException {
        ArchivePaths.uuid(owner); ArchivePaths.uuid(attachment); ArchivePaths.uuid(record);
        String base = "attachments/" + owner + "/" + attachment;
        return switch (kind) {
            case "ATTACHMENT" -> base + "/uploads/" + record + ".bin";
            case "PARSE_RESULT" -> base + "/runs/" + record + "/result.json";
            case "PREVIEW" -> base + "/runs/" + record + "/preview.png";
            default -> throw new IOException("ARCHIVE_FILE_KIND_INVALID");
        };
    }

    static List<Column> writable(Table table) { return table.columns().stream().filter(column -> !column.generated()).toList(); }

    List<String> decodeRow(byte[] bytes, int columns) throws IOException {
        try {
            JsonNode row = json.readTree(bytes);
            if (!row.isArray() || row.size() != columns) throw new IOException("ARCHIVE_ROW_INVALID");
            List<String> values = new ArrayList<>(columns);
            for (JsonNode field : row) {
                if (!field.isNull() && !field.isString()) throw new IOException("ARCHIVE_ROW_INVALID");
                values.add(field.isNull() ? null : field.asString());
            }
            return values;
        } catch (RuntimeException exception) { throw new IOException("ARCHIVE_ROW_INVALID"); }
    }

    static byte[] line(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int next; (next = input.read()) != -1;) {
            if (next == '\n') return line.toByteArray();
            if (line.size() >= MAX_ROW_BYTES) throw new IOException("ARCHIVE_RECORD_LIMIT");
            line.write(next);
        }
        if (line.size() != 0) throw new IOException("ARCHIVE_ROW_TRUNCATED");
        return null;
    }

    private static byte[] readLimited(InputStream input, int maximum) throws IOException {
        byte[] bytes = input.readNBytes(maximum + 1);
        if (bytes.length > maximum) throw new IOException("ARCHIVE_MANIFEST_LIMIT");
        return bytes;
    }

    static void time(long started) throws IOException {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() - started > 300_000_000_000L)
            throw new IOException("ARCHIVE_TIME_LIMIT");
    }

    static CheckedInput openChecked(Path archive, long maximumBytes) throws IOException {
        return new CheckedInput(ArchivePaths.open(archive, maximumBytes), maximumBytes, true);
    }

    /** 压缩原字节与单个解压载荷均按实际读取计数、摘要和限时，关闭载荷不会关闭整个 ZIP。 */
    static final class CheckedInput extends InputStream {
        private final InputStream source;
        private final long maximum;
        private final boolean closeSource;
        private final MessageDigest digest = ArchivePaths.digest();
        private final long started = System.nanoTime();
        private long size;
        private boolean eof;
        private String sha256;

        CheckedInput(InputStream source, long maximum, boolean closeSource) {
            if (source == null || maximum < 0 || maximum > 4_294_967_296L)
                throw new IllegalArgumentException("归档读取边界无效");
            this.source = source; this.maximum = maximum; this.closeSource = closeSource;
        }

        @Override public int read() throws IOException {
            if (eof) return -1;
            time(started);
            int value = source.read();
            if (value == -1) { eof = true; return -1; }
            if (++size > maximum) throw new IOException("ARCHIVE_ENTRY_SIZE_LIMIT");
            digest.update((byte) value);
            return value;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) return 0;
            if (eof) return -1;
            time(started);
            int count = source.read(bytes, offset, (int) Math.min(length, maximum - size + 1));
            if (count == -1) { eof = true; return -1; }
            size += count;
            if (size > maximum) throw new IOException("ARCHIVE_ENTRY_SIZE_LIMIT");
            digest.update(bytes, offset, count);
            return count;
        }

        String finish() throws IOException {
            byte[] bytes = new byte[65_536];
            while (read(bytes) != -1) { }
            if (sha256 == null) sha256 = HexFormat.of().formatHex(digest.digest());
            return sha256;
        }

        void verify(Entry expected) throws IOException {
            if (!expected.sha256().equals(finish()) || size != expected.byteSize())
                throw new IOException("ARCHIVE_ENTRY_DIGEST_MISMATCH");
        }

        @Override public void close() throws IOException { if (closeSource) source.close(); }
    }

    private static final class BoundedOutput extends OutputStream {
        private final OutputStream target; private final long maximum; private long total;
        BoundedOutput(OutputStream target, long maximum) { this.target = target; this.maximum = maximum; }
        @Override public void write(int value) throws IOException { if (++total > maximum) throw new IOException("ARCHIVE_SIZE_LIMIT"); target.write(value); }
        @Override public void write(byte[] bytes, int offset, int count) throws IOException {
            total += count; if (total > maximum) throw new IOException("ARCHIVE_SIZE_LIMIT"); target.write(bytes, offset, count);
        }
        @Override public void flush() throws IOException { target.flush(); }
    }
}
