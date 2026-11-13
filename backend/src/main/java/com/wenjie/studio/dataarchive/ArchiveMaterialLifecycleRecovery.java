package com.wenjie.studio.dataarchive;

import com.wenjie.studio.dataprivacy.PrivacyLedger;
import com.wenjie.studio.material.MaterialLifecycleDtos;
import com.wenjie.studio.material.MaterialLifecycleMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static com.wenjie.studio.material.MaterialLifecycleDtos.*;

/** 摘录许可重放只改变用途，不恢复正文、归档状态或旧的模型范围授权。 */
final class ArchiveMaterialLifecycleRecovery {
    private static final long MAX_REVISION = 9_007_199_254_740_991L;

    private ArchiveMaterialLifecycleRecovery() { }

    static void replay(Connection target, MaterialLifecycleMapper mapper, String owner,
                       PrivacyLedger.Subject subject, PrivacyLedger.Entry entry) throws Exception {
        AiUse desired = desired(entry);
        var before = source(target, mapper, owner, subject.id());
        // 旧包可能早于摘录创建；仍由外层记录不可丢失的账本身份，不伪造正文和基线。
        if (before == null) return;
        if (before.aiUse().equals(desired.name()))
            throw new IOException("RESTORE_MATERIAL_PERMISSION_BASELINE_CONFLICT");
        if (before.revision() == MAX_REVISION)
            throw new IOException("RESTORE_MATERIAL_PERMISSION_REVISION_EXHAUSTED");
        Instant occurred = entry.mutation().occurredAt().truncatedTo(ChronoUnit.MICROS);
        Instant time = occurred.isBefore(before.updatedAt()) ? before.updatedAt() : occurred;
        if (!validTime(time)) throw new IOException("RESTORE_MATERIAL_PERMISSION_TIME_INVALID");
        // 独立账本不保存原理由正文；明确标记恢复事件并固定账本证明，不能伪称原用户事件。
        String reason = "离线恢复重放独立隐私账本，序号 " + entry.sequence() + "，事件 "
                + entry.mutation().eventId() + "，SHA-256 " + entry.hash();
        String id = UUID.nameUUIDFromBytes(("archive-material-ai-use:" + owner + ":" + subject.id() + ":"
                + entry.hash()).getBytes(StandardCharsets.UTF_8)).toString();
        var event = new MaterialLifecycleDtos.Event(id, subject.id(),
                desired == AiUse.REVOKED ? Action.REVOKE_AI : Action.ALLOW_AI,
                Status.valueOf(before.status()), AiUse.valueOf(before.aiUse()), before.revision(),
                Status.valueOf(before.status()), desired, before.revision() + 1, reason, time);
        if (mapper.change(owner, subject.id(), before.revision(), before.status(), desired.name(), time) != 1)
            throw new IOException("RESTORE_MATERIAL_PERMISSION_CONFLICT");
        mapper.insertEvent(owner, event);
        var after = source(target, mapper, owner, subject.id());
        if (after == null || after.revision() != event.revision() || !after.aiUse().equals(desired.name())
                || !after.status().equals(before.status()) || !after.updatedAt().equals(time))
            throw new IOException("RESTORE_MATERIAL_PERMISSION_NOT_APPLIED");
    }

    static void verify(Connection target, MaterialLifecycleMapper mapper, String owner,
                       PrivacyLedger.Subject subject, PrivacyLedger.Entry entry) throws Exception {
        AiUse expected = desired(entry);
        var row = source(target, mapper, owner, subject.id());
        if (row == null) return;
        if (!expected.name().equals(row.aiUse()))
            throw new IOException("ARCHIVE_MATERIAL_PERMISSION_NOT_APPLIED");
        verifyHistory(mapper, owner, row);
    }

    private static AiUse desired(PrivacyLedger.Entry entry) throws IOException {
        return switch (entry.mutation().action()) {
            case "REVOKE" -> AiUse.REVOKED;
            case "RESTORE" -> AiUse.ALLOWED;
            default -> throw new IOException("ARCHIVE_MATERIAL_PERMISSION_ACTION_INVALID");
        };
    }

    private static MaterialLifecycleMapper.Row source(Connection target, MaterialLifecycleMapper mapper,
                                                       String owner, String id) throws SQLException, IOException {
        var row = mapper.source(owner, id);
        if (row == null) {
            try (var query = target.prepareStatement("SELECT 1 FROM material_excerpt WHERE owner_id=? AND id=?")) {
                query.setQueryTimeout(5); query.setString(1, owner); query.setString(2, id);
                try (var result = query.executeQuery()) {
                    if (result.next()) throw new IOException("ARCHIVE_MATERIAL_PERMISSION_BASELINE_MISSING");
                }
            }
            return null;
        }
        if (!id.equals(row.excerptId()) || !id.equals(row.baselineExcerptId())
                || row.attachmentId() == null || !row.attachmentId().equals(row.baselineAttachmentId())
                || !("ACTIVE".equals(row.status()) || "ARCHIVED".equals(row.status()))
                || !("ALLOWED".equals(row.aiUse()) || "REVOKED".equals(row.aiUse()))
                || row.revision() == null || row.revision() < 0 || row.revision() > MAX_REVISION
                || !validTime(row.createdAt()) || !row.createdAt().equals(row.excerptCreatedAt())
                || !validTime(row.updatedAt()) || row.updatedAt().isBefore(row.createdAt())
                || row.revision() == 0 && (!"ACTIVE".equals(row.status()) || !"ALLOWED".equals(row.aiUse())
                    || !row.updatedAt().equals(row.createdAt())))
            throw new IOException("ARCHIVE_MATERIAL_PERMISSION_BASELINE_INVALID");
        return row;
    }

    private static void verifyHistory(MaterialLifecycleMapper mapper, String owner,
                                      MaterialLifecycleMapper.Row row) throws IOException {
        long revision = 0;
        Status status = Status.ACTIVE;
        AiUse aiUse = AiUse.ALLOWED;
        Instant time = row.createdAt();
        while (true) {
            var events = mapper.history(owner, row.excerptId(), revision, 256);
            if (events == null || events.size() > 256)
                throw new IOException("ARCHIVE_MATERIAL_PERMISSION_HISTORY_INVALID");
            if (events.isEmpty()) break;
            for (var event : events) {
                if (event == null || !uuid(event.id()) || !row.excerptId().equals(event.excerptId())
                        || event.action() == null || event.previousStatus() != status || event.previousAiUse() != aiUse
                        || event.status() == null || event.aiUse() == null || event.previousRevision() != revision
                        || event.revision() != revision + 1 || event.revision() > row.revision()
                        || !validTime(event.createdAt()) || event.createdAt().isBefore(time)
                        || event.createdAt().isAfter(row.updatedAt()) || !validReason(event.reason())
                        || !validTransition(event))
                    throw new IOException("ARCHIVE_MATERIAL_PERMISSION_HISTORY_INVALID");
                revision = event.revision(); status = event.status(); aiUse = event.aiUse(); time = event.createdAt();
            }
        }
        if (revision != row.revision() || !status.name().equals(row.status()) || !aiUse.name().equals(row.aiUse())
                || !time.equals(row.updatedAt()))
            throw new IOException("ARCHIVE_MATERIAL_PERMISSION_HISTORY_INCOMPLETE");
    }

    private static boolean validTransition(Event event) {
        return switch (event.action()) {
            case ARCHIVE -> event.previousStatus() == Status.ACTIVE && event.status() == Status.ARCHIVED
                    && event.aiUse() == event.previousAiUse();
            case RESTORE -> event.previousStatus() == Status.ARCHIVED && event.status() == Status.ACTIVE
                    && event.aiUse() == event.previousAiUse();
            case REVOKE_AI -> event.previousAiUse() == AiUse.ALLOWED && event.aiUse() == AiUse.REVOKED
                    && event.status() == event.previousStatus();
            case ALLOW_AI -> event.previousAiUse() == AiUse.REVOKED && event.aiUse() == AiUse.ALLOWED
                    && event.status() == event.previousStatus();
        };
    }

    private static boolean validReason(String value) {
        if (value == null || value.isEmpty() || value.length() > 1000 || !value.equals(value.strip())
                || value.indexOf('\0') >= 0) return false;
        for (int index = 0; index < value.length(); index++) {
            char item = value.charAt(index);
            if (Character.isHighSurrogate(item)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) return false;
            } else if (Character.isLowSurrogate(item)) return false;
        }
        return true;
    }

    private static boolean validTime(Instant value) {
        return value != null && value.getNano() % 1000 == 0
                && !value.isBefore(Instant.parse("1000-01-01T00:00:00Z"))
                && !value.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"));
    }

    private static boolean uuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException exception) { return false; }
    }
}
