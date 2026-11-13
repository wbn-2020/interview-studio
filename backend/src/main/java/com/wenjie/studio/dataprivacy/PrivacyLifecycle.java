package com.wenjie.studio.dataprivacy;

import com.wenjie.studio.ai.AiAuthorizationDtos;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyMapper;
import com.wenjie.studio.material.MaterialLifecycleDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;

/** 业务事务只推进账本检查点，不回滚独立账本中已经持久化的隐私意图。 */
@Service
public class PrivacyLifecycle {
    private final PrivacyLedger ledger;
    private final PrivacyMapper mapper;
    private final IdempotencyMapper workspaces;
    private final ThreadLocal<PendingPermit> pendingPermit = new ThreadLocal<>();
    private record PendingPermit(String owner, long sequence, String hash) { }

    public PrivacyLifecycle(PrivacyLedger ledger, PrivacyMapper mapper, IdempotencyMapper workspaces) {
        this.ledger = ledger;
        this.mapper = mapper;
        this.workspaces = workspaces;
    }

    public record Status(boolean configured, boolean initialized, boolean synchronizedWithDatabase,
                         PrivacyLedger.Head latestHead, Long appliedSequence, String appliedHash,
                         String state) { }

    @Transactional(readOnly = true, timeout = 15)
    public Status status(String owner) {
        PrivacyLedger.uuid(owner);
        var snapshot = ledger.snapshot(owner);
        var checkpoint = mapper.checkpoint(owner);
        boolean synchronizedWithDatabase = checkpoint != null && snapshot.initialized()
                && snapshot.head().sequence() == checkpoint.ledgerSequence()
                && snapshot.head().hash().equals(checkpoint.ledgerHash());
        String state = !snapshot.configured() ? "NOT_CONFIGURED"
                : !snapshot.initialized() ? "NOT_INITIALIZED"
                : synchronizedWithDatabase ? "READY" : "RECONCILIATION_REQUIRED";
        return new Status(snapshot.configured(), snapshot.initialized(), synchronizedWithDatabase,
                snapshot.head(), checkpoint == null ? null : checkpoint.ledgerSequence(),
                checkpoint == null ? null : checkpoint.ledgerHash(), state);
    }

    @Transactional(timeout = 30)
    public Status initialize(String owner, String eventId, boolean acknowledgeCurrentBaseline) {
        PrivacyLedger.uuid(owner);
        PrivacyLedger.uuid(eventId);
        workspaces.ensureWorkspace(owner);
        if (workspaces.lockWorkspace(owner) == null) throw new IllegalStateException("未能锁定个人工作区");
        if (mapper.checkpoint(owner) != null) return status(owner);
        if (mapper.businessObjectCount(owner) != 0 && !acknowledgeCurrentBaseline) {
            throw new ApiException(409, "PRIVACY_BASELINE_CONFIRMATION_REQUIRED",
                    "工作区已有内容。须明确确认从当前状态开始建立独立账本；此前没有账本的备份不能直接开放恢复。");
        }
        // 初始化命令的同标识重试可接续文件已落盘而数据库事务尚未提交的窗口。
        var snapshot = ledger.initializeEmpty(owner, eventId);
        if (mapper.insertCheckpoint(owner, snapshot.head().sequence(), snapshot.head().hash(), now()) != 1) {
            throw new IllegalStateException("未能建立隐私账本检查点");
        }
        return status(owner);
    }

    @Transactional(readOnly = true, timeout = 15)
    public void requireSynchronized(String owner) {
        var snapshot = ledger.snapshot(owner);
        var checkpoint = mapper.checkpoint(owner);
        // 尚未启用隐私功能的原工作区仍可正常使用；一旦存在检查点，不允许丢账本降级。
        if (checkpoint == null && !snapshot.initialized()) return;
        requireCheckpoint(snapshot, checkpoint);
        if (checkpoint.ledgerSequence() != snapshot.head().sequence()) {
            var permit = pendingPermit.get();
            if (permit == null || !owner.equals(permit.owner()) || permit.sequence() != snapshot.head().sequence()
                    || !permit.hash().equals(snapshot.head().hash())) throw reconciliation();
        }
    }

    /** HTTP 只放行确切原路由和操作键；owner 锁内仍须以完整的类型化请求核对意图摘要。 */
    @Transactional(readOnly = true, timeout = 15)
    public void requireRequestSynchronized(String owner, String eventId, String method, String path) {
        var snapshot = ledger.snapshot(owner);
        var checkpoint = mapper.checkpoint(owner);
        if (checkpoint == null && !snapshot.initialized()) return;
        requireCheckpoint(snapshot, checkpoint);
        if (checkpoint.ledgerSequence() == snapshot.head().sequence()) return;
        var pending = pendingMutation(owner, eventId, snapshot, checkpoint);
        if (!"POST".equals(method) || !matchesPermissionRoute(pending, "POST " + path)) throw reconciliation();
    }

    /** 非隐私命令的恢复必须匹配原路由、键、对象、动作、版本及请求正文摘要。 */
    @Transactional(readOnly = true, timeout = 15)
    public void requireCommandSynchronized(String owner, String eventId, String operation, Object request) {
        var snapshot = ledger.snapshot(owner);
        var checkpoint = mapper.checkpoint(owner);
        if (checkpoint == null && !snapshot.initialized()) return;
        requireCheckpoint(snapshot, checkpoint);
        if (checkpoint.ledgerSequence() == snapshot.head().sequence()) return;
        var permit = pendingPermit.get();
        if (permit != null && owner.equals(permit.owner()) && permit.sequence() == snapshot.head().sequence()
                && permit.hash().equals(snapshot.head().hash())) return;
        var pending = pendingMutation(owner, eventId, snapshot, checkpoint);
        if (!matchesPermissionCommand(pending, operation, request)) throw reconciliation();
    }

    /** 仅真正未决且完整匹配的原许可命令可跳过新独立训练的资料挑战。 */
    @Transactional(readOnly = true, timeout = 15)
    public boolean isMatchingPendingPermissionCommand(String owner, String eventId, String operation, Object request) {
        var snapshot = ledger.snapshot(owner);
        var checkpoint = mapper.checkpoint(owner);
        if (checkpoint == null && !snapshot.initialized()) return false;
        requireCheckpoint(snapshot, checkpoint);
        if (checkpoint.ledgerSequence() == snapshot.head().sequence()) return false;
        if (snapshot.head().sequence() != checkpoint.ledgerSequence() + 1) throw reconciliation();
        var pending = snapshot.entries().getLast().mutation();
        return eventId != null && eventId.equals(pending.eventId()) && pending.subjects().size() == 1
                && pending.redactions().isEmpty() && matchesPermissionCommand(pending, operation, request);
    }

    /** 原隐私操作只返回无正文结果，可在另一标签进入独立训练后接续，不能借此预览资料。 */
    @Transactional(readOnly = true, timeout = 15)
    public boolean isMatchingPendingPrivacyCommand(String owner, String eventId, String operation, Object request) {
        if (!(request instanceof DataPrivacyDtos.Command command) || eventId == null) return false;
        var snapshot = ledger.snapshot(owner);
        var checkpoint = mapper.checkpoint(owner);
        if (checkpoint == null && !snapshot.initialized()) return false;
        requireCheckpoint(snapshot, checkpoint);
        if (checkpoint.ledgerSequence() == snapshot.head().sequence()) return false;
        if (snapshot.head().sequence() != checkpoint.ledgerSequence() + 1) throw reconciliation();
        var pending = snapshot.entries().getLast().mutation();
        if (!List.of("TRASH", "RESTORE", "PURGE").contains(pending.action())
                || !eventId.equals(pending.eventId())
                || !("POST /api/v1/data-privacy/" + pending.action().toLowerCase(java.util.Locale.ROOT)).equals(operation)
                || !pending.scopeDigest().equals(command.previewDigest())
                || pending.subjects().stream().noneMatch(subject -> subject.kind().equals(command.objectKind())
                    && subject.id().equals(command.objectId()))) return false;
        return !"PURGE".equals(pending.action()) || Boolean.TRUE.equals(command.confirmWholeMixedDocuments());
    }

    /** 仅供 owner 命令锁内的嵌套原生归档使用，许可由已核对的原隐私意图建立。 */
    public boolean isVerifiedPendingContinuation(String owner) {
        var permit = pendingPermit.get();
        if (permit == null || !owner.equals(permit.owner())) return false;
        var snapshot = ledger.snapshot(owner);
        return snapshot.head().sequence() == permit.sequence() && snapshot.head().hash().equals(permit.hash());
    }

    /** 内层业务归档在同一 owner 锁内接续已核验的原隐私命令，不能借此跨越其他未决操作。 */
    @Transactional(propagation = Propagation.MANDATORY, timeout = 30)
    public void continueMatchingMutation(String owner, String eventId, String action,
                                         List<PrivacyLedger.Subject> subjects,
                                         List<PrivacyLedger.Redaction> redactions, String digest, Runnable work) {
        var snapshot = ledger.snapshot(owner);
        var checkpoint = mapper.checkpoint(owner);
        requireCheckpoint(snapshot, checkpoint);
        PendingPermit previous = pendingPermit.get();
        if (checkpoint.ledgerSequence() != snapshot.head().sequence()) {
            var pending = snapshot.entries().getLast().mutation();
            if (snapshot.head().sequence() != checkpoint.ledgerSequence() + 1
                    || !eventId.equals(pending.eventId()) || !action.equals(pending.action())
                    || !subjects.equals(pending.subjects()) || !redactions.equals(pending.redactions())
                    || !digest.equals(pending.scopeDigest())) throw reconciliation();
            pendingPermit.set(new PendingPermit(owner, snapshot.head().sequence(), snapshot.head().hash()));
        }
        try { work.run(); }
        finally {
            if (previous == null) pendingPermit.remove();
            else pendingPermit.set(previous);
        }
    }

    /** 调用方先完成当前授权版本校验／撤权，再在同一业务事务内写入独立不可回滚意图。 */
    @Transactional(propagation = Propagation.MANDATORY, timeout = 30)
    public void recordAuthorizationRevocation(String owner, String eventId, String authorizationId, long expectedRevision) {
        if (!recordingEnabled(owner)) return;
        var subject = new PrivacyLedger.Subject("AI_AUTHORIZATION", authorizationId);
        record(owner, eventId, "REVOKE", List.of(subject), List.of(), authorizationDigest(authorizationId, expectedRevision),
                entry -> setState(owner, subject.kind(), subject.id(), "REVOKED", "ACTIVE", entry));
    }

    /** 摘录的 AI 许可与本地归档分开记账，不能用恢复回收站代替用户重新允许。 */
    @Transactional(propagation = Propagation.MANDATORY, timeout = 30)
    public void recordMaterialAiUse(String owner, String eventId, String excerptId, MaterialLifecycleDtos.Change request) {
        if (request == null || request.action() == null) return;
        // 旧元数据回执仍可读取；新命令不能在已回收／清除的附件上重新改变许可或添加新理由正文。
        if (mapper.unavailableIdentities(owner).contains(excerptId))
            throw new ApiException(410, "PRIVACY_OBJECT_UNAVAILABLE", "摘录所属内容已移入回收站或清除，不能提交新的生命周期变更。");
        if (!List.of(MaterialLifecycleDtos.Action.REVOKE_AI, MaterialLifecycleDtos.Action.ALLOW_AI).contains(request.action())) return;
        if (!Boolean.TRUE.equals(request.confirmed()) || !validRevision(request.expectedRevision()) || request.reason() == null)
            throw new IllegalArgumentException("材料许可账本请求未经过原生命周期校验");
        if (!recordingEnabled(owner)) return;
        boolean revoke = request.action() == MaterialLifecycleDtos.Action.REVOKE_AI;
        var subject = new PrivacyLedger.Subject("MATERIAL_EXCERPT", excerptId);
        record(owner, eventId, revoke ? "REVOKE" : "RESTORE", List.of(subject), List.of(),
                materialDigest(excerptId, request), entry -> setState(owner, subject.kind(), subject.id(),
                        revoke ? "REVOKED" : "AVAILABLE", revoke ? "ALLOWED" : "REVOKED", entry));
    }

    @Transactional(readOnly = true, timeout = 15)
    public void requireAvailable(String owner, String kind, String id) {
        PrivacyLedger.uuid(owner);
        PrivacyLedger.uuid(id);
        var state = mapper.objectState(owner, kind, id);
        if (state == null || "AVAILABLE".equals(state.state())) return;
        String message = switch (state.state()) {
            case "TRASHED" -> "该内容已移入回收站，请在数据管理中恢复后访问。";
            case "PURGED" -> "该内容已按隐私请求清除，只保留无正文身份记录。";
            default -> "该内容的用途许可已撤回。";
        };
        throw new ApiException(410, "PRIVACY_OBJECT_UNAVAILABLE", message);
    }

    /** 必须在 IdempotencyService 的同一 owner 锁和业务事务内调用。 */
    @Transactional(propagation = Propagation.MANDATORY, timeout = 30)
    public PrivacyLedger.Entry record(String owner, String eventId, String action,
                                      List<PrivacyLedger.Subject> subjects,
                                      List<PrivacyLedger.Redaction> redactions, String digest,
                                      Consumer<PrivacyLedger.Entry> apply) {
        PrivacyLedger.uuid(owner);
        PrivacyLedger.uuid(eventId);
        var snapshot = ledger.snapshot(owner);
        var checkpoint = mapper.checkpoint(owner);
        requireCheckpoint(snapshot, checkpoint);
        PrivacyLedger.Entry entry;
        if (checkpoint.ledgerSequence() == snapshot.head().sequence()) {
            entry = ledger.append(owner, snapshot.head(), new PrivacyLedger.Mutation(eventId, action,
                    List.copyOf(subjects), List.copyOf(redactions), digest, Instant.now()));
        } else {
            // 只允许原命令接续自己的一个悬而未决意图；不能跨过另一条未应用操作。
            if (snapshot.head().sequence() != checkpoint.ledgerSequence() + 1) throw reconciliation();
            entry = snapshot.entries().getLast();
            var pending = entry.mutation();
            if (!eventId.equals(pending.eventId()) || !action.equals(pending.action())
                    || !subjects.equals(pending.subjects()) || !redactions.equals(pending.redactions())
                    || !digest.equals(pending.scopeDigest())) throw reconciliation();
        }
        apply.accept(entry);
        if (mapper.advanceCheckpoint(owner, checkpoint.ledgerSequence(), checkpoint.ledgerHash(),
                entry.sequence(), entry.hash(), now()) != 1) throw reconciliation();
        return entry;
    }

    @Transactional(propagation = Propagation.MANDATORY, timeout = 30)
    public void setState(String owner, String kind, String id, String state, String previousStatus,
                         PrivacyLedger.Entry entry) {
        var current = mapper.objectState(owner, kind, id);
        if (current != null && "PURGED".equals(current.state()) && !"PURGED".equals(state)) {
            throw new ApiException(409, "PRIVACY_PURGE_IRREVERSIBLE", "已清除的内容不能恢复，旧备份也不能重新开放。");
        }
        LocalDateTime time = now();
        LocalDateTime trashed = "TRASHED".equals(state) ? time : null;
        if (current == null) {
            mapper.insertState(owner, kind, id, state, entry.mutation().eventId(), entry.sequence(),
                    previousStatus, trashed, time);
        } else if (mapper.changeState(owner, kind, id, current.revision(), state,
                entry.mutation().eventId(), entry.sequence(), previousStatus, trashed, time) != 1) {
            throw new ApiException(409, "PRIVACY_STATE_CONFLICT", "隐私状态已变化，请刷新后重试。");
        }
    }

    /** 完整恢复的调用方逐条重放后使用；不接受跳号或伪造的包内账本条目。 */
    @Transactional(propagation = Propagation.MANDATORY, timeout = 30)
    public void applyRecoveryEntry(String owner, PrivacyLedger.Head latestExternalHead,
                                   PrivacyLedger.Entry entry, Consumer<PrivacyLedger.Entry> replay) {
        var snapshot = ledger.requireLatest(owner, latestExternalHead);
        var checkpoint = mapper.checkpoint(owner);
        requireCheckpoint(snapshot, checkpoint);
        if (entry == null || entry.sequence() != checkpoint.ledgerSequence() + 1
                || entry.sequence() > snapshot.entries().size()
                || !entry.equals(snapshot.entries().get(Math.toIntExact(entry.sequence() - 1)))) {
            throw reconciliation();
        }
        replay.accept(entry);
        if (mapper.advanceCheckpoint(owner, checkpoint.ledgerSequence(), checkpoint.ledgerHash(),
                entry.sequence(), entry.hash(), now()) != 1) throw reconciliation();
    }

    private static void requireCheckpoint(PrivacyLedger.Snapshot snapshot, PrivacyMapper.Checkpoint checkpoint) {
        if (!snapshot.initialized() || checkpoint == null || checkpoint.ledgerSequence() < 1
                || checkpoint.ledgerSequence() > snapshot.head().sequence()) throw reconciliation();
        var recorded = snapshot.entries().get(Math.toIntExact(checkpoint.ledgerSequence() - 1));
        if (!recorded.hash().equals(checkpoint.ledgerHash())) throw reconciliation();
    }

    private boolean recordingEnabled(String owner) {
        var snapshot = ledger.snapshot(owner);
        var checkpoint = mapper.checkpoint(owner);
        // 启用前的许可沿既有业务记录；完整导出本身要求初始化，旧备份不能跨越初始化基线。
        if (checkpoint == null && !snapshot.initialized()) return false;
        requireCheckpoint(snapshot, checkpoint);
        return true;
    }

    private static PrivacyLedger.Mutation pendingMutation(String owner, String eventId, PrivacyLedger.Snapshot snapshot,
                                                          PrivacyMapper.Checkpoint checkpoint) {
        PrivacyLedger.uuid(owner);
        if (snapshot.head().sequence() != checkpoint.ledgerSequence() + 1) throw reconciliation();
        var pending = snapshot.entries().getLast().mutation();
        if (eventId == null || !eventId.equals(pending.eventId()) || pending.subjects().size() != 1
                || !pending.redactions().isEmpty()) throw reconciliation();
        return pending;
    }

    private static boolean matchesPermissionRoute(PrivacyLedger.Mutation pending, String operation) {
        var subject = pending.subjects().getFirst();
        return "AI_AUTHORIZATION".equals(subject.kind()) && "REVOKE".equals(pending.action())
                && ("POST /api/v1/ai/authorizations/" + subject.id() + "/revoke").equals(operation)
                || "MATERIAL_EXCERPT".equals(subject.kind())
                && List.of("REVOKE", "RESTORE").contains(pending.action())
                && ("POST /api/v1/materials/excerpts/" + subject.id() + "/lifecycle/changes").equals(operation);
    }

    private static boolean matchesMaterialAction(String action, MaterialLifecycleDtos.Action requested) {
        return "REVOKE".equals(action) && requested == MaterialLifecycleDtos.Action.REVOKE_AI
                || "RESTORE".equals(action) && requested == MaterialLifecycleDtos.Action.ALLOW_AI;
    }

    private static boolean matchesPermissionCommand(PrivacyLedger.Mutation pending, String operation, Object request) {
        if (!matchesPermissionRoute(pending, operation)) return false;
        var subject = pending.subjects().getFirst();
        String expected;
        if ("AI_AUTHORIZATION".equals(subject.kind()) && request instanceof AiAuthorizationDtos.Revoke revoke
                && Boolean.TRUE.equals(revoke.confirmed()) && validRevision(revoke.expectedRevision())) {
            expected = authorizationDigest(subject.id(), revoke.expectedRevision());
        } else if ("MATERIAL_EXCERPT".equals(subject.kind()) && request instanceof MaterialLifecycleDtos.Change change
                && Boolean.TRUE.equals(change.confirmed()) && validRevision(change.expectedRevision())
                && change.reason() != null && matchesMaterialAction(pending.action(), change.action())) {
            expected = materialDigest(subject.id(), change);
        } else return false;
        return expected.equals(pending.scopeDigest());
    }

    private static String authorizationDigest(String id, long revision) {
        return PrivacyLedger.digest("REVOKE\n" + id + "\n" + revision);
    }

    private static String materialDigest(String id, MaterialLifecycleDtos.Change request) {
        return PrivacyLedger.digest("MATERIAL_AI_USE\n" + id + "\n" + request.expectedRevision() + "\n"
                + request.action().name() + "\n" + PrivacyLedger.digest(request.reason()));
    }

    private static boolean validRevision(Long revision) {
        return revision != null && revision >= 0 && revision <= 9_007_199_254_740_991L;
    }

    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    private static ApiException reconciliation() {
        return new ApiException(503, "PRIVACY_RECONCILIATION_REQUIRED",
                "独立隐私账本与业务检查点尚未对齐。请接续原隐私操作或在封闭恢复环境重放最新账本。");
    }
}
