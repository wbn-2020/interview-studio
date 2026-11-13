package com.wenjie.studio.dataprivacy;

import com.wenjie.studio.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 账本目录必须由运维独立持久化，不能放进随业务库回滚的备份中。
 * 哈希链只能检测损坏与局部截断；完整替换为旧副本仍需恢复时的外部最新头证明。
 */
@Component
public final class PrivacyLedger {
    private static final String ZERO = "0".repeat(64);
    private static final long MAX_BYTES = 67_108_864;
    private static final int MAX_LINE_BYTES = 2_097_152;
    private static final int MAX_EVENTS = 200_000;
    private static final Set<String> ACTIONS = Set.of("GENESIS", "TRASH", "RESTORE",
            "PURGE", "REVOKE", "REDACT");
    private static final Set<String> KINDS = Set.of("KNOWLEDGE", "PREPARATION", "QUESTION",
            "MEMORY_CARD", "BOARD", "MATERIAL", "AI_TASK", "AI_AUTHORIZATION",
            "TRAINING", "REVIEW_FEEDBACK", "RECOLLECTION", "PREPARATION_TASK",
            "REVIEW_ACTION", "QUESTION_COLLECTION", "INTERVIEW_EVENT", "MATERIAL_EXCERPT", "EXPORT");
    private final Path directory;
    private final JsonMapper json;
    private final Object mutex = new Object();

    public PrivacyLedger(@Value("${STUDIO_PRIVACY_LEDGER_DIRECTORY:}") String configuredDirectory,
                         JsonMapper json) {
        if (configuredDirectory == null || configuredDirectory.isBlank()) {
            this.directory = null;
        } else {
            Path path = Path.of(configuredDirectory);
            if (!path.isAbsolute() || !path.normalize().equals(path)) {
                throw new IllegalStateException("隐私账本目录须为规范化的绝对路径");
            }
            this.directory = path;
        }
        this.json = json.rebuild().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    }

    public record Subject(String kind, String id) { }
    /** 仅保存无正文定位；恢复时不能依赖已经被清除的原文重新查找副本。 */
    public record Redaction(String table, Map<String, String> key, List<String> columns,
                            boolean wholeFields, List<String> referenceIdentities) { }
    public record Mutation(String eventId, String action, List<Subject> subjects,
                           List<Redaction> redactions, String scopeDigest, Instant occurredAt) { }
    public record Entry(int format, String ownerId, long sequence, String previousHash,
                        Mutation mutation, String hash) { }
    public record Head(long sequence, String hash) { }
    public record Snapshot(boolean configured, boolean initialized, Head head, List<Entry> entries) { }
    private record Unsigned(int format, String ownerId, long sequence, String previousHash,
                            Mutation mutation) { }

    public Snapshot snapshot(String owner) {
        uuid(owner);
        if (directory == null) return new Snapshot(false, false, new Head(0, ZERO), List.of());
        synchronized (mutex) {
            Path file = ledgerPath(owner);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                return new Snapshot(true, false, new Head(0, ZERO), List.of());
            }
            try (FileChannel channel = openExisting(file); FileLock ignored = channel.lock()) {
                return scan(owner, channel);
            } catch (IOException exception) {
                throw unavailable();
            }
        }
    }

    /**
     * 仅供空环境初始化命令使用；调用方须在数据库工作区锁内证明没有业务数据。
     * 丢失账本时不允许自动重建，否则恢复旧备份会重新开放已经撤回的正文。
     */
    public Snapshot initializeEmpty(String owner, String eventId) {
        uuid(owner);
        uuid(eventId);
        if (directory == null) throw unavailable();
        synchronized (mutex) {
            Path file = ledgerPath(owner);
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                Snapshot current = snapshot(owner);
                if (current.entries().size() == 1
                        && eventId.equals(current.entries().getFirst().mutation().eventId())) return current;
                throw conflict("隐私账本已存在，不得覆盖或重新初始化。");
            }
            Mutation genesis = new Mutation(eventId, "GENESIS", List.of(), List.of(), ZERO, Instant.now());
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 FileLock ignored = channel.lock()) {
                appendRaw(channel, sign(owner, 1, ZERO, genesis));
                return scan(owner, channel);
            } catch (IOException exception) {
                // 不删除部分文件；任何不完整头都保持封闭，交由显式恢复流程处理。
                throw unavailable();
            }
        }
    }

    /**
     * 先持久化意图，再修改业务库。数据库失败时意图仍有效，重试或恢复必须补齐，
     * 不能靠回滚／删除日志恢复被撤回的权限。
     */
    public Entry append(String owner, Head expectedHead, Mutation mutation) {
        uuid(owner);
        validate(mutation);
        if (directory == null || expectedHead == null) throw unavailable();
        synchronized (mutex) {
            Path file = ledgerPath(owner);
            try (FileChannel channel = openExisting(file); FileLock ignored = channel.lock()) {
                Snapshot current = scan(owner, channel);
                for (Entry entry : current.entries()) {
                    if (entry.mutation().eventId().equals(mutation.eventId())) {
                        if (!json.writeValueAsString(entry.mutation()).equals(json.writeValueAsString(mutation))) {
                            throw conflict("相同隐私操作标识绑定了不同内容。");
                        }
                        return entry;
                    }
                }
                if (!current.head().equals(expectedHead)) throw conflict("隐私账本已变化，请重新预览。");
                Entry entry = sign(owner, Math.addExact(current.head().sequence(), 1),
                        current.head().hash(), mutation);
                appendRaw(channel, entry);
                return entry;
            } catch (IOException exception) {
                throw unavailable();
            }
        }
    }

    /** 恢复者必须持有从业务备份之外取得的最新头，不能用包内头代替。 */
    public Snapshot requireLatest(String owner, Head externallyExpectedLatest) {
        Snapshot current = snapshot(owner);
        if (!current.initialized() || externallyExpectedLatest == null
                || !current.head().equals(externallyExpectedLatest)) {
            throw conflict("无法证明独立隐私账本为最新状态，恢复数据必须继续封闭。");
        }
        return current;
    }

    private Snapshot scan(String owner, FileChannel channel) throws IOException {
        long size = channel.size();
        if (size <= 0 || size > MAX_BYTES) throw corrupt();
        channel.position(0);
        List<Entry> entries = new ArrayList<>();
        Set<String> eventIds = new HashSet<>();
        ByteBuffer buffer = ByteBuffer.allocate(16_384);
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        String previousHash = ZERO;
        long expectedSequence = 1;
        while (channel.read(buffer) != -1) {
            buffer.flip();
            while (buffer.hasRemaining()) {
                int value = Byte.toUnsignedInt(buffer.get());
                if (value == '\n') {
                    if (line.size() == 0 || entries.size() >= MAX_EVENTS) throw corrupt();
                    Entry entry;
                    try {
                        entry = json.readValue(line.toString(StandardCharsets.UTF_8), Entry.class);
                    } catch (RuntimeException exception) {
                        throw corrupt();
                    }
                    line.reset();
                    if (entry == null || entry.format() != 1 || !owner.equals(entry.ownerId())
                            || entry.sequence() != expectedSequence || !previousHash.equals(entry.previousHash())) {
                        throw corrupt();
                    }
                    try {
                        validate(entry.mutation());
                    } catch (RuntimeException exception) {
                        throw corrupt();
                    }
                    if (expectedSequence == 1 && (!"GENESIS".equals(entry.mutation().action())
                            || !entry.mutation().subjects().isEmpty() || !entry.mutation().redactions().isEmpty())) {
                        throw corrupt();
                    }
                    if (expectedSequence > 1 && "GENESIS".equals(entry.mutation().action())) throw corrupt();
                    if (!eventIds.add(entry.mutation().eventId())
                            || !sign(owner, expectedSequence, previousHash, entry.mutation()).hash().equals(entry.hash())) {
                        throw corrupt();
                    }
                    entries.add(entry);
                    previousHash = entry.hash();
                    expectedSequence++;
                } else {
                    if (line.size() >= MAX_LINE_BYTES || value == '\r') throw corrupt();
                    line.write(value);
                }
            }
            buffer.clear();
        }
        if (line.size() != 0 || entries.isEmpty()) throw corrupt();
        return new Snapshot(true, true, new Head(expectedSequence - 1, previousHash), List.copyOf(entries));
    }

    private void appendRaw(FileChannel channel, Entry entry) throws IOException {
        byte[] bytes = (json.writeValueAsString(entry) + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_LINE_BYTES || channel.size() + bytes.length > MAX_BYTES
                || entry.sequence() > MAX_EVENTS) throw conflict("隐私账本达到安全容量，请先安排账本迁移。");
        channel.position(channel.size());
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
    }

    private Entry sign(String owner, long sequence, String previousHash, Mutation mutation) {
        Unsigned unsigned = new Unsigned(1, owner, sequence, previousHash, mutation);
        return new Entry(1, owner, sequence, previousHash, mutation, digest(json.writeValueAsString(unsigned)));
    }

    private FileChannel openExisting(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()) throw corrupt();
        return FileChannel.open(file, Set.<OpenOption>of(StandardOpenOption.READ,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS));
    }

    private Path ledgerPath(String owner) {
        if (directory == null) throw unavailable();
        try {
            Path part = directory.getRoot();
            if (part == null) throw unavailable();
            for (Path name : directory) {
                part = part.resolve(name);
                BasicFileAttributes attributes = Files.readAttributes(part,
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) throw unavailable();
            }
            if (!directory.toRealPath().equals(directory)) throw unavailable();
            Path file = directory.resolve(owner + ".ledger.jsonl").normalize();
            if (!file.getParent().equals(directory)) throw unavailable();
            return file;
        } catch (IOException exception) {
            throw unavailable();
        }
    }

    static void validate(Mutation mutation) {
        if (mutation == null || !ACTIONS.contains(mutation.action()) || mutation.occurredAt() == null
                || mutation.subjects() == null || mutation.subjects().size() > 500
                || mutation.redactions() == null || mutation.redactions().size() > 2_000
                || mutation.scopeDigest() == null || !mutation.scopeDigest().matches("[0-9a-f]{64}")) {
            throw invalid("隐私账本操作格式无效。");
        }
        uuid(mutation.eventId());
        Set<String> subjects = new HashSet<>();
        for (Subject subject : mutation.subjects()) {
            if (subject == null || !KINDS.contains(subject.kind())) throw invalid("隐私对象类型无效。");
            uuid(subject.id());
            if (!subjects.add(subject.kind() + ":" + subject.id())) throw invalid("隐私对象重复。");
        }
        if (!"GENESIS".equals(mutation.action()) && mutation.subjects().isEmpty()) {
            throw invalid("隐私操作必须指定对象。");
        }
        for (Redaction redaction : mutation.redactions()) {
            if (redaction == null || !identifier(redaction.table()) || redaction.key() == null
                    || redaction.key().isEmpty() || redaction.key().size() > 8
                    || redaction.columns() == null || redaction.columns().isEmpty()
                    || redaction.columns().size() > 32 || redaction.referenceIdentities() == null
                    || redaction.referenceIdentities().size() > 1_000) throw invalid("隐私清除定位无效。");
            for (Map.Entry<String, String> key : redaction.key().entrySet()) {
                if (!identifier(key.getKey()) || key.getValue() == null
                        || !key.getValue().matches("(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9]{1,19}|[A-Z_]{1,40}|sha256:[0-9a-f]{64})")) {
                    throw invalid("账本仅允许保存无正文的对象定位。");
                }
            }
            if (new HashSet<>(redaction.columns()).size() != redaction.columns().size()
                    || redaction.columns().stream().anyMatch(column -> !identifier(column))) {
                throw invalid("隐私清除字段无效。");
            }
            for (String identity : redaction.referenceIdentities()) uuid(identity);
            if (!redaction.wholeFields() && redaction.referenceIdentities().isEmpty()) {
                throw invalid("局部引用清除必须保存无正文的来源身份。");
            }
        }
    }

    private static boolean identifier(String value) {
        return value != null && value.matches("[a-z][a-z0-9_]{0,63}");
    }

    public static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行时不支持隐私账本摘要", exception);
        }
    }

    static String uuid(String value) {
        try {
            if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException exception) {
            throw invalid("隐私操作对象必须是标准小写 UUID。");
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(400, "INVALID_PRIVACY_REQUEST", message);
    }
    private static ApiException conflict(String message) {
        return new ApiException(409, "PRIVACY_LEDGER_CONFLICT", message);
    }
    private static ApiException corrupt() {
        return new ApiException(503, "PRIVACY_LEDGER_INCOMPLETE", "独立隐私账本不完整，相关数据保持封闭，请由运维核对最新账本。");
    }
    private static ApiException unavailable() {
        return new ApiException(503, "PRIVACY_LEDGER_UNAVAILABLE", "独立隐私账本尚未配置或不可访问，不能执行删除、撤权或恢复。");
    }
}
