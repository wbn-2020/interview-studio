package com.wenjie.studio.dataarchive;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyMapper;
import com.wenjie.studio.dataprivacy.PrivacyContentMapper;
import com.wenjie.studio.dataprivacy.PrivacyLedger;
import com.wenjie.studio.dataprivacy.PrivacyLifecycle;
import com.wenjie.studio.dataprivacy.PrivacyMapper;
import com.wenjie.studio.dataprivacy.PrivacyScope;
import com.wenjie.studio.material.MaterialLifecycleMapper;
import com.wenjie.studio.material.MaterialProperties;
import com.wenjie.studio.search.SearchArchiveRecovery;
import com.wenjie.studio.search.SearchMapper;
import com.wenjie.studio.search.SearchProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 在线导出与离线恢复共用同一条独立账本，不把业务包内的旧授权当作许可。 */
@Component
public final class LedgerArchiveLifecyclePolicy implements ArchiveLifecyclePolicy {
    private final PrivacyLedger ledger;
    private final JsonMapper json;
    private final String materialDirectory;
    private final String searchDirectory;
    private final String ledgerDirectory;

    /** ServiceLoader 路径只读取明确命名的目录配置，不启动 Spring 或后台任务。 */
    public LedgerArchiveLifecyclePolicy() {
        this(new PrivacyLedger(System.getenv("STUDIO_PRIVACY_LEDGER_DIRECTORY"), new JsonMapper()),
                new JsonMapper(), System.getenv("STUDIO_MATERIAL_DIRECTORY"),
                System.getenv("STUDIO_SEARCH_DIRECTORY"), System.getenv("STUDIO_PRIVACY_LEDGER_DIRECTORY"));
    }

    @Autowired
    public LedgerArchiveLifecyclePolicy(PrivacyLedger ledger, JsonMapper json, MaterialProperties materials,
                                        SearchProperties search,
                                        @Value("${STUDIO_PRIVACY_LEDGER_DIRECTORY:}") String ledgerDirectory) {
        this(ledger, json, materials.directory(), search.directory(), ledgerDirectory);
    }

    private LedgerArchiveLifecyclePolicy(PrivacyLedger ledger, JsonMapper json, String materials,
                                         String search, String ledgerDirectory) {
        this.ledger = ledger; this.json = json; this.materialDirectory = materials;
        this.searchDirectory = search; this.ledgerDirectory = ledgerDirectory;
    }

    @Override public Checkpoint current(String owner) {
        var snapshot = ledger.snapshot(owner);
        if (!snapshot.configured() || !snapshot.initialized()) {
            throw new ApiException(503, "ARCHIVE_LEDGER_NOT_INITIALIZED",
                    "请先配置并初始化独立隐私账本；完整导出和恢复必须能够证明最新删除／撤权边界。");
        }
        return checkpoint(snapshot.head());
    }

    @Override public void verifyExportCheckpoint(Connection snapshot, String owner, Checkpoint latest) throws Exception {
        var chain = requireLatest(owner, latest);
        try (var session = ArchiveJdbcSession.open(snapshot)) {
            var recorded = session.getMapper(PrivacyMapper.class).checkpoint(owner);
            requirePrefix(chain, recorded);
            if (recorded.ledgerSequence() != latest.sequence() || !recorded.ledgerHash().equals(latest.sha256()))
                throw new IOException("ARCHIVE_PRIVACY_RECONCILIATION_REQUIRED");
            var effective = finalStates(chain);
            verifyStates(session.getMapper(PrivacyMapper.class), owner, effective);
            verifyMaterialPermissions(snapshot, session.getMapper(MaterialLifecycleMapper.class), owner, effective);
        }
    }

    @Override public void verifyArchiveCheckpoint(String owner, Checkpoint archived, Checkpoint externallyExpectedLatest) {
        var chain = requireLatest(owner, externallyExpectedLatest);
        if (archived == null || archived.sequence() < 1 || archived.sequence() > chain.head().sequence()
                || !chain.entries().get(Math.toIntExact(archived.sequence() - 1)).hash().equals(archived.sha256()))
            throw new IllegalStateException("导出包的隐私检查点不属于最新独立账本");
        finalStates(chain);
    }

    @Override public void applyLatest(Connection target, String owner, Checkpoint expected, Path materialRoot) throws Exception {
        requireSealed(target, owner);
        requireConfiguredRoot(materialRoot, materialDirectory, "RESTORE_MATERIAL_ROOT_MISMATCH");
        var chain = requireLatest(owner, expected);
        try (var session = ArchiveJdbcSession.open(target)) {
            var states = session.getMapper(PrivacyMapper.class);
            var recorded = states.checkpoint(owner);
            requirePrefix(chain, recorded);
            var scope = new PrivacyScope(session.getMapper(PrivacyContentMapper.class), json);
            var lifecycle = new PrivacyLifecycle(ledger, states, session.getMapper(IdempotencyMapper.class));
            var materials = session.getMapper(MaterialLifecycleMapper.class);
            long sequence = recorded.ledgerSequence();
            String hash = recorded.ledgerHash();
            for (var entry : chain.entries()) {
                if (entry.sequence() <= recorded.ledgerSequence()) continue;
                if (entry.sequence() != sequence + 1 || !entry.previousHash().equals(hash))
                    throw new IOException("RESTORE_PRIVACY_REPLAY_ORDER_MISMATCH");
                if (!entry.mutation().redactions().isEmpty()) scope.replayRedactions(owner, entry);
                for (var subject : entry.mutation().subjects()) {
                    String state = stateFor(entry.mutation().action());
                    if ("MATERIAL_EXCERPT".equals(subject.kind()))
                        ArchiveMaterialLifecycleRecovery.replay(target, materials, owner, subject, entry);
                    String previous = "MATERIAL_EXCERPT".equals(subject.kind())
                            ? ("REVOKE".equals(entry.mutation().action()) ? "ALLOWED" : "REVOKED") : null;
                    if (state != null) lifecycle.setState(owner, subject.kind(), subject.id(), state, previous, entry);
                }
                // 完整链在事务首尾核验；逐条 CAS 保证不跳号，避免每一条都重新扫描整份账本。
                if (states.advanceCheckpoint(owner, sequence, hash, entry.sequence(), entry.hash(),
                        LocalDateTime.now(ZoneOffset.UTC)) != 1)
                    throw new IOException("RESTORE_PRIVACY_REPLAY_CHECKPOINT_CONFLICT");
                sequence = entry.sequence(); hash = entry.hash();
            }
            // 旧包也不能伪造“已清除”的正文；所有不可逆字段按独立定位再次约束。
            for (var entry : chain.entries())
                if (!entry.mutation().redactions().isEmpty()) scope.replayRedactions(owner, entry);
            var effective = finalStates(chain);
            for (var event : effective.values()) {
                var subject = event.subject();
                var state = states.objectState(owner, subject.kind(), subject.id());
                if (!matches(state, event))
                    lifecycle.setState(owner, subject.kind(), subject.id(), event.state(), null, event.entry());
                if (!"AVAILABLE".equals(event.state()) || event.entry().sequence() > recorded.ledgerSequence())
                    archiveUnavailable(target, owner, event);
                if ("MATERIAL".equals(subject.kind()) && "PURGED".equals(event.state()))
                    attachmentFiles(target, owner, subject.id(), materialRoot, true);
            }
            revokeOldAuthorizations(target, owner);
            pauseRestoredSchedules(target, owner);
            verifyStates(states, owner, effective);
            verifyMaterialPermissions(target, materials, owner, effective);
            var applied = states.checkpoint(owner);
            if (applied == null || applied.ledgerSequence() != expected.sequence() || !applied.ledgerHash().equals(expected.sha256()))
                throw new IOException("RESTORE_PRIVACY_CHECKPOINT_NOT_APPLIED");
        }
        requireLatest(owner, expected);
    }

    @Override public boolean includeAttachment(Connection snapshot, String owner, String attachmentId) throws Exception {
        ArchivePaths.uuid(owner); ArchivePaths.uuid(attachmentId);
        var chain = requireLatest(owner, current(owner));
        var effective = finalStates(chain).get("MATERIAL:" + attachmentId);
        boolean purged = effective != null && "PURGED".equals(effective.state());
        try (var query = snapshot.prepareStatement("SELECT state FROM privacy_object_state WHERE owner_id=? AND object_kind='MATERIAL' AND object_id=?")) {
            query.setQueryTimeout(5); query.setString(1, owner); query.setString(2, attachmentId);
            try (var result = query.executeQuery()) {
                if (result.next() && "PURGED".equals(result.getString(1)) && !purged)
                    throw new IOException("ARCHIVE_ATTACHMENT_PURGE_NOT_IN_LEDGER");
            }
        }
        return !purged;
    }

    @Override public void rebuildIndexes(Connection target, String owner, Path newIndexDirectory) throws Exception {
        requireSealed(target, owner);
        requireConfiguredRoot(newIndexDirectory, searchDirectory, "RESTORE_SEARCH_ROOT_MISMATCH");
        try (var session = ArchiveJdbcSession.open(target)) {
            SearchArchiveRecovery.rebuild(target, session.getMapper(SearchMapper.class), owner, newIndexDirectory, json);
        }
    }

    @Override public void verifyBeforeOpen(Connection target, String owner, Checkpoint expected, Path indexRoot) throws Exception {
        requireSealed(target, owner);
        requireConfiguredRoot(indexRoot, searchDirectory, "RESTORE_SEARCH_ROOT_MISMATCH");
        verifyExportCheckpoint(target, owner, expected);
        try (var session = ArchiveJdbcSession.open(target)) {
            SearchArchiveRecovery.verify(target, session.getMapper(SearchMapper.class), owner, indexRoot);
        }
        for (String sql : List.of(
                "SELECT 1 FROM ai_scope_authorization WHERE owner_id=? AND state='ACTIVE' LIMIT 1",
                "SELECT 1 FROM ai_task WHERE owner_id=? AND status IN ('QUEUED','RUNNING','VALIDATING','CANCEL_REQUESTED') LIMIT 1",
                "SELECT 1 FROM ai_attempt WHERE owner_id=? AND status IN ('QUEUED','RUNNING','VALIDATING','CANCEL_REQUESTED') LIMIT 1",
                "SELECT 1 FROM memory_schedule WHERE owner_id=? AND status='ACTIVE' LIMIT 1")) {
            try (var query = target.prepareStatement(sql)) {
                query.setQueryTimeout(5); query.setString(1, owner);
                try (var result = query.executeQuery()) { if (result.next()) throw new IOException("RESTORE_AUTOMATION_STILL_ACTIVE"); }
            }
        }
        var chain = requireLatest(owner, expected);
        Path materialRoot = ArchivePaths.root(materialDirectory);
        for (var event : finalStates(chain).values())
            if ("MATERIAL".equals(event.subject().kind()) && "PURGED".equals(event.state()))
                attachmentFiles(target, owner, event.subject().id(), materialRoot, false);
    }

    private record StateEvent(PrivacyLedger.Subject subject, String state, PrivacyLedger.Entry entry) { }

    private static Map<String, StateEvent> finalStates(PrivacyLedger.Snapshot chain) {
        Map<String, StateEvent> result = new LinkedHashMap<>();
        for (var entry : chain.entries()) {
            String state = stateFor(entry.mutation().action());
            if (state == null) continue;
            for (var subject : entry.mutation().subjects()) {
                String identity = subject.kind() + ":" + subject.id();
                var previous = result.get(identity);
                if (previous != null && "PURGED".equals(previous.state()) && !"PURGED".equals(state))
                    throw new IllegalStateException("独立账本包含清除后恢复的冲突状态");
                result.put(identity, new StateEvent(subject, state, entry));
            }
        }
        return Map.copyOf(result);
    }

    private static String stateFor(String action) {
        return switch (action) {
            case "TRASH" -> "TRASHED";
            case "RESTORE" -> "AVAILABLE";
            case "PURGE" -> "PURGED";
            case "REVOKE" -> "REVOKED";
            default -> null;
        };
    }

    private static void verifyStates(PrivacyMapper mapper, String owner, Map<String, StateEvent> effective) throws IOException {
        for (var event : effective.values())
            if (!matches(mapper.objectState(owner, event.subject().kind(), event.subject().id()), event))
                throw new IOException("ARCHIVE_PRIVACY_STATE_NOT_APPLIED");
    }

    private static void verifyMaterialPermissions(Connection target, MaterialLifecycleMapper mapper, String owner,
                                                  Map<String, StateEvent> effective) throws Exception {
        for (var event : effective.values())
            if ("MATERIAL_EXCERPT".equals(event.subject().kind()))
                ArchiveMaterialLifecycleRecovery.verify(target, mapper, owner, event.subject(), event.entry());
    }

    private static boolean matches(PrivacyMapper.ObjectState state, StateEvent event) {
        return state != null && state.state().equals(event.state())
                && state.ledgerSequence() == event.entry().sequence()
                && state.latestEventId().equals(event.entry().mutation().eventId());
    }

    private static void requirePrefix(PrivacyLedger.Snapshot chain, PrivacyMapper.Checkpoint recorded) throws IOException {
        if (recorded == null || recorded.ledgerSequence() < 1 || recorded.ledgerSequence() > chain.head().sequence()
                || !chain.entries().get(Math.toIntExact(recorded.ledgerSequence() - 1)).hash().equals(recorded.ledgerHash()))
            throw new IOException("RESTORE_PRIVACY_CHECKPOINT_NOT_IN_CHAIN");
    }

    private PrivacyLedger.Snapshot requireLatest(String owner, Checkpoint expected) {
        return ledger.requireLatest(owner, new PrivacyLedger.Head(expected.sequence(), expected.sha256()));
    }

    private static Checkpoint checkpoint(PrivacyLedger.Head head) { return new Checkpoint(head.sequence(), head.hash()); }

    private static void requireSealed(Connection target, String owner) throws SQLException, IOException {
        try (var query = target.prepareStatement("SELECT 1 FROM data_restore_gate WHERE singleton_id=1 AND owner_id=? AND state IN ('RESTORING','VERIFYING','FAILED') AND ai_blocked=TRUE")) {
            query.setQueryTimeout(5); query.setString(1, owner);
            try (var result = query.executeQuery()) { if (!result.next()) throw new IOException("RESTORE_POLICY_REQUIRES_SEALED_TARGET"); }
        }
    }

    private void requireConfiguredRoot(Path actual, String configured, String code) throws IOException {
        Path expected = ArchivePaths.root(configured);
        ArchivePaths.directories(actual);
        Path independent = ArchivePaths.root(ledgerDirectory);
        if (!expected.equals(actual) || actual.startsWith(independent) || independent.startsWith(actual))
            throw new IOException(code);
    }

    private static void archiveUnavailable(Connection target, String owner, StateEvent event) throws SQLException, IOException {
        // 回收站恢复也只恢复访问身份，不能重新启用旧归档内容或自动复习。
        if (!List.of("KNOWLEDGE", "PREPARATION", "QUESTION", "MEMORY_CARD", "RECOLLECTION", "QUESTION_COLLECTION")
                .contains(event.subject().kind())) return;
        String table = switch (event.subject().kind()) {
            case "KNOWLEDGE" -> "knowledge_item";
            case "PREPARATION" -> "preparation_item";
            case "QUESTION" -> "question";
            case "RECOLLECTION" -> "review_recollection";
            case "QUESTION_COLLECTION" -> "question_collection";
            default -> "memory_card";
        };
        String revision = table.equals("knowledge_item") ? "metadata_revision" : "revision";
        long maximum = List.of("memory_card", "review_recollection").contains(table) ? 1000 : 9_007_199_254_740_991L;
        try (var update = target.prepareStatement("UPDATE " + table + " SET status='ARCHIVED'," + revision + "=" + revision
                + "+1,updated_at=UTC_TIMESTAMP(6) WHERE owner_id=? AND id=? AND status<>'ARCHIVED' AND " + revision + "<?")) {
            update.setQueryTimeout(5); update.setString(1, owner); update.setString(2, event.subject().id());
            update.setLong(3, maximum); update.executeUpdate();
        }
        try (var query = target.prepareStatement("SELECT 1 FROM " + table + " WHERE owner_id=? AND id=? AND status<>'ARCHIVED'")) {
            query.setQueryTimeout(5); query.setString(1, owner); query.setString(2, event.subject().id());
            try (var result = query.executeQuery()) { if (result.next()) throw new IOException("RESTORE_ARCHIVE_REVISION_EXHAUSTED"); }
        }
    }

    private static void revokeOldAuthorizations(Connection target, String owner) throws SQLException {
        try (var update = target.prepareStatement("UPDATE ai_scope_authorization SET state='REVOKED',revision=1,"
                + "revoked_at=GREATEST(created_at,UTC_TIMESTAMP(6)) WHERE owner_id=? AND state='ACTIVE'")) {
            update.setQueryTimeout(10); update.setString(1, owner); update.executeUpdate();
        }
    }

    private static void pauseRestoredSchedules(Connection target, String owner) throws SQLException, IOException {
        try (var update = target.prepareStatement("UPDATE memory_schedule SET status='PAUSED',pause_reason='MANUAL',"
                + "revision=revision+1,updated_at=UTC_TIMESTAMP(6) WHERE owner_id=? AND status='ACTIVE' AND revision<9007199254740991")) {
            update.setQueryTimeout(10); update.setString(1, owner); update.executeUpdate();
        }
        try (var query = target.prepareStatement("SELECT 1 FROM memory_schedule WHERE owner_id=? AND status='ACTIVE' LIMIT 1")) {
            query.setQueryTimeout(5); query.setString(1, owner);
            try (var result = query.executeQuery()) { if (result.next()) throw new IOException("RESTORE_SCHEDULE_REVISION_EXHAUSTED"); }
        }
    }

    private static void attachmentFiles(Connection target, String owner, String attachment, Path root, boolean clear) throws Exception {
        ArchivePaths.uuid(owner); ArchivePaths.uuid(attachment);
        for (String table : List.of("material_upload", "material_parse_run")) {
            try (var query = target.prepareStatement("SELECT id FROM " + table + " WHERE owner_id=? AND attachment_id=? ORDER BY id LIMIT 100001")) {
                query.setQueryTimeout(10); query.setString(1, owner); query.setString(2, attachment);
                int count = 0;
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        if (++count > 100_000) throw new IOException("RESTORE_ATTACHMENT_CAPACITY");
                        String id = ArchivePaths.uuid(rows.getString(1));
                        Path base = root.resolve(owner).resolve(attachment);
                        if ("material_upload".equals(table)) checkFile(base.resolve("uploads").resolve(id + ".bin"), clear);
                        else {
                            checkFile(base.resolve("runs").resolve(id).resolve("result.json"), clear);
                            checkFile(base.resolve("runs").resolve(id).resolve("preview.png"), clear);
                        }
                    }
                }
            }
        }
    }

    private static void checkFile(Path path, boolean clear) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        ArchivePaths.regular(path, 20_971_520, true);
        if (clear) {
            try (var channel = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                channel.truncate(0); channel.force(true);
            }
        }
        if (Files.size(path) != 0) throw new IOException("RESTORE_PURGED_ATTACHMENT_PRESENT");
    }
}
