package com.wenjie.studio.material;

import com.wenjie.studio.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static com.wenjie.studio.material.MaterialReferencesDtos.*;

@Service
public class MaterialReferencesService {
    private static final long MAX = 9_007_199_254_740_991L;
    private static final Set<String> AI_STATUSES = Set.of("QUEUED", "RUNNING", "VALIDATING", "SUCCEEDED", "FAILED",
            "UNKNOWN", "CANCEL_REQUESTED", "CANCELED");
    private final MaterialReferencesMapper mapper;
    private final MaterialProperties properties;

    public MaterialReferencesService(MaterialReferencesMapper mapper, MaterialProperties properties) {
        this.mapper = mapper; this.properties = properties;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 20)
    public Page list(String owner, String excerptId, Kind kind, String cursor, int limit) {
        if (!properties.enabled()) throw new ApiException(503, "MATERIALS_DISABLED", "服务器尚未启用附件资料。");
        uuid(owner); uuid(excerptId);
        if (kind == null || limit < 1 || limit > 20) throw invalid("引用分类或每页数量无效。");
        After after = cursor(cursor, excerptId, kind);
        // 先在同一数据库快照核固定附件与摘要关联，不依赖磁盘或解析器，也不加载摘录原文。
        Source source = mapper.source(owner, excerptId);
        if (source == null) throw MaterialGuards.missing();
        source(source, excerptId);
        var rows = mapper.references(owner, excerptId, kind.name(), after == null ? null : after.id(),
                after == null ? 0L : after.revision(), limit + 1);
        if (rows.size() > limit + 1) throw integrity();
        String previousId = after == null ? null : after.id();
        long previousRevision = after == null ? 0 : after.revision();
        for (var row : rows) {
            entry(kind, row);
            if (previousId != null && (row.referenceId().compareTo(previousId) < 0
                    || row.referenceId().equals(previousId) && row.referenceRevision() <= previousRevision)) throw integrity();
            previousId = row.referenceId(); previousRevision = row.referenceRevision();
        }
        List<Entry> items = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
        String next = null;
        if (rows.size() > limit) {
            var last = items.getLast();
            next = excerptId + "~" + kind.name() + "~" + last.referenceId() + "~" + last.referenceRevision();
        }
        return new Page(kind, "REGISTERED_DIRECT_ONLY", source, items, next);
    }

    static Kind kind(String value) {
        try {
            if (value == null) throw new IllegalArgumentException();
            return Kind.valueOf(value);
        } catch (IllegalArgumentException exception) { throw invalid("引用分类无效。"); }
    }
    private static After cursor(String value, String excerptId, Kind kind) {
        if (value == null) return null;
        if (value.isEmpty() || value.length() > 160) throw invalid("引用目录游标无效。");
        String[] parts = value.split("~", -1);
        if (parts.length != 4 || !excerptId.equals(parts[0]) || !kind.name().equals(parts[1])) {
            throw invalid("游标与当前摘录或分类不一致。");
        }
        uuid(parts[2]);
        if (!parts[3].matches("(?:0|[1-9][0-9]{0,15})")) throw invalid("引用目录游标修订无效。");
        try {
            long revision = Long.parseLong(parts[3]);
            if (revision > MAX || revision != 0 && kind != Kind.STANDARD_REVIEW_EVIDENCE) {
                throw invalid("引用目录游标修订超出允许范围。");
            }
            return new After(parts[2], revision);
        } catch (NumberFormatException exception) { throw invalid("引用目录游标修订无效。"); }
    }
    private static void source(Source source, String excerptId) {
        if (!excerptId.equals(source.excerptId()) || !storedUuid(source.attachmentId()) || source.createdAt() == null
                || source.createdAt().getNano() % 1000 != 0
                || source.createdAt().isBefore(Instant.parse("1000-01-01T00:00:00Z"))
                || source.createdAt().isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"))) throw integrity();
        if ("MANUAL".equals(source.origin())) {
            if (source.runId() != null || source.segmentId() != null) throw integrity();
        } else if (!"PARSED".equals(source.origin()) || !storedUuid(source.runId()) || !storedUuid(source.segmentId())) throw integrity();
    }
    private static void entry(Kind kind, Entry value) {
        if (value == null || !storedUuid(value.referenceId()) || !storedUuid(value.targetId())
                || value.referenceRevision() < 0 || value.referenceRevision() > MAX || value.targetKind() == null
                || value.status() == null
                || value.referenceRevision() != 0 && kind != Kind.STANDARD_REVIEW_EVIDENCE) throw integrity();
        switch (kind) {
            case PREPARATION_VERSIONS -> {
                if (value.targetKind() != TargetKind.PREPARATION || !value.referenceId().equals(value.targetVersionId())
                        || value.targetVersionNumber() == null || value.targetVersionNumber() < 1
                        || value.targetRevision() != null || value.anchorItemId() != null || value.otherItemId() != null
                        || value.anchorVersionId() != null || !Set.of("ACTIVE", "ARCHIVED").contains(value.status())) throw integrity();
            }
            case RELATION_EVIDENCE -> {
                if (value.targetKind() != TargetKind.RELATION || value.targetVersionId() != null || value.targetVersionNumber() != null
                        || value.targetRevision() != null || !storedUuid(value.anchorItemId()) || !storedUuid(value.otherItemId())
                        || value.anchorItemId().equals(value.otherItemId()) || value.anchorVersionId() != null
                        || !(value.targetCurrent() ? "ACTIVE" : "REMOVED").equals(value.status())) throw integrity();
            }
            case STANDARD_REVIEW_EVIDENCE -> {
                if (value.targetKind() != TargetKind.STANDARD_REVIEW || !value.referenceId().equals(value.targetId())
                        || value.targetRevision() == null || value.targetRevision() != value.referenceRevision()
                        || value.targetVersionId() != null || value.targetVersionNumber() != null
                        || !storedUuid(value.anchorItemId()) || !storedUuid(value.anchorVersionId())
                        || value.otherItemId() != null || !Set.of("ACTIVE", "WITHDRAWN").contains(value.status())) throw integrity();
            }
            default -> {
                if (value.targetKind() != TargetKind.AI_TASK || !value.referenceId().equals(value.targetId())
                        || value.targetVersionId() != null || value.targetVersionNumber() != null || value.targetRevision() != null
                        || !value.targetCurrent() || value.anchorItemId() != null || value.otherItemId() != null
                        || value.anchorVersionId() != null || !AI_STATUSES.contains(value.status())) throw integrity();
            }
        }
    }
    private static void uuid(String value) {
        if (!storedUuid(value)) throw invalid("标识须为标准小写 UUID。");
    }
    private static boolean storedUuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException exception) { return false; }
    }
    static ApiException invalid(String message) {
        return MaterialGuards.invalid(message);
    }
    private static IllegalStateException integrity() { return new IllegalStateException("材料直接引用目录的固定身份或元数据不一致"); }
    private record After(String id, long revision) {}
}
