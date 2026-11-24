package com.wenjie.studio.dataprivacy;

import com.wenjie.studio.bank.QuestionDtos;
import com.wenjie.studio.bank.QuestionService;
import com.wenjie.studio.bank.CollectionDtos;
import com.wenjie.studio.bank.CollectionService;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import com.wenjie.studio.knowledge.KnowledgeService;
import com.wenjie.studio.memory.MemoryDtos;
import com.wenjie.studio.memory.MemoryService;
import com.wenjie.studio.prepare.PreparationDtos;
import com.wenjie.studio.prepare.PreparationService;
import com.wenjie.studio.review.RecollectionDtos;
import com.wenjie.studio.review.RecollectionService;
import com.wenjie.studio.training.TrainingAccessGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.wenjie.studio.dataprivacy.DataPrivacyDtos.*;

@Service
public class DataPrivacyService {
    private final PrivacyScope scope;
    private final PrivacyLifecycle lifecycle;
    private final PrivacyMapper mapper;
    private final PrivacyObjectQueryService objectQuery;
    private final PrivacyStorageEffects effects;
    private final IdempotencyService commands;
    private final TrainingAccessGuard training;
    private final KnowledgeService knowledge;
    private final PreparationService preparation;
    private final QuestionService questions;
    private final MemoryService memory;
    private final CollectionService collections;
    private final RecollectionService recollections;

    public DataPrivacyService(PrivacyScope scope, PrivacyLifecycle lifecycle, PrivacyMapper mapper,
                              PrivacyObjectQueryService objectQuery, PrivacyStorageEffects effects, IdempotencyService commands,
                              TrainingAccessGuard training, KnowledgeService knowledge,
                              PreparationService preparation, QuestionService questions, MemoryService memory,
                              CollectionService collections, RecollectionService recollections) {
        this.scope = scope; this.lifecycle = lifecycle; this.mapper = mapper; this.objectQuery = objectQuery;
        this.effects = effects;
        this.commands = commands; this.training = training; this.knowledge = knowledge;
        this.preparation = preparation; this.questions = questions; this.memory = memory;
        this.collections = collections; this.recollections = recollections;
    }

    public PrivacyLifecycle.Status status(String owner) { return lifecycle.status(owner); }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public PrivacyObjectPage objects(String owner, PrivacyScope.PrivacyActionableKind kind,
                                     String query, String cursor, int limit) {
        lifecycle.requireSynchronized(owner);
        return objectQuery.list(owner, kind, query, cursor, limit);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public PrivacyObjectPage objectsByIds(String owner, java.util.List<String> ids) {
        lifecycle.requireSynchronized(owner);
        return objectQuery.listByIds(owner, ids);
    }

    public PrivacyLifecycle.Status initialize(String owner, String key, InitializeLedger request) {
        if (request == null || request.acknowledgeCurrentBaseline() == null) throw invalid();
        return lifecycle.initialize(owner, key, request.acknowledgeCurrentBaseline());
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, timeout = 20)
    public TrashPage trash(String owner, String cursor, int limit) {
        lifecycle.requireSynchronized(owner);
        training.checkHttp(owner, "GET", "/api/v1/lifecycle/privacy", null);
        if (limit < 1 || limit > 50) throw invalid();
        String afterKind = null, afterId = null;
        if (cursor != null) {
            String[] parts = cursor.split("~", -1);
            if (parts.length != 2 || !parts[0].matches("[A-Z_]{1,32}")) throw invalid();
            afterKind = parts[0]; afterId = PrivacyLedger.uuid(parts[1]);
        }
        var rows = mapper.states(owner, "TRASHED", afterKind, afterId, limit + 1);
        List<TrashEntry> entries = rows.stream().limit(limit).map(row -> new TrashEntry(row.objectKind(),
                row.objectId(), row.state(), row.revision(), row.previousStatus(), row.trashedAt(), row.updatedAt())).toList();
        String next = rows.size() > limit ? entries.getLast().objectKind() + "~" + entries.getLast().objectId() : null;
        return new TrashPage(entries, next);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, timeout = 120)
    public Preview preview(String owner, String action, SelectObject request) {
        selection(request);
        lifecycle.requireSynchronized(owner);
        training.checkHttp(owner, "GET", "/api/v1/lifecycle/privacy", null);
        return previewWithin(owner, action, request.objectKind(), request.objectId());
    }

    public Result command(String owner, String key, String action, Command request) {
        if (request == null || request.previewDigest() == null || !request.previewDigest().matches("[0-9a-f]{64}")) throw invalid();
        selection(new SelectObject(request.objectKind(), request.objectId()));
        PrivacyLedger.uuid(key);
        if (!List.of("TRASH", "RESTORE", "PURGE").contains(action)) throw invalid();
        return effects.withoutWorkers(() -> commands.execute(owner, key,
                "POST /api/v1/data-privacy/" + action.toLowerCase(java.util.Locale.ROOT), request,
                Result.class, () -> {
                    if (!lifecycle.isMatchingPendingPrivacyCommand(owner, key,
                            "POST /api/v1/data-privacy/" + action.toLowerCase(java.util.Locale.ROOT), request))
                        training.checkHttp(owner, "POST", "/api/v1/lifecycle/privacy", null);
                    effects.requireQuiescent(owner);
                    if ("PURGE".equals(action)) return purge(owner, key, request);
                    Preview preview = previewWithin(owner, action, request.objectKind(), request.objectId());
                    requireDigest(preview, request.previewDigest());
                    var object = preview.objects().getFirst();
                    var subject = new PrivacyLedger.Subject(request.objectKind(), request.objectId());
                    // 可预见的草稿／版本／来源失败须在写入不可回滚意图之前发生。
                    // 归档仅修改本事务内的业务记录，随后账本失败仍会整体回滚。
                    if ("TRASH".equals(action)) lifecycle.continueMatchingMutation(owner, key, action,
                            List.of(subject), List.of(), preview.digest(), () -> archive(owner, key, object));
                    var entry = lifecycle.record(owner, key, action, List.of(subject), List.of(), preview.digest(), event -> {
                        // 恢复只恢复可访问身份，保留归档与暂停。重新安排复习和授权须另行确认。
                        lifecycle.setState(owner, subject.kind(), subject.id(),
                                "TRASH".equals(action) ? "TRASHED" : "AVAILABLE", object.status(), event);
                    });
                    return new Result(action, List.of(subject), entry.sequence(), entry.hash(),
                            "TRASH".equals(action) ? "TRASHED" : "RESTORED_WITHOUT_REACTIVATION");
                }));
    }

    private Result purge(String owner, String key, Command request) {
        var current = mapper.objectState(owner, request.objectKind(), request.objectId());
        if (current == null || !"TRASHED".equals(current.state())) {
            throw new ApiException(409, "PRIVACY_TRASH_REQUIRED", "请先将根内容移入回收站，再预览彻底清除范围。");
        }
        PrivacyScope.Plan plan = scope.purgePreview(owner, request.objectKind(), request.objectId());
        Preview preview = withExports(owner, plan.preview());
        requireDigest(preview, request.previewDigest());
        if (!Boolean.TRUE.equals(request.confirmWholeMixedDocuments())) {
            throw new ApiException(400, "PRIVACY_WHOLE_SCOPE_CONFIRMATION_REQUIRED",
                    "彻底清除需明确确认清单中的全部版本、混合派生正文、任务输入、索引与历史完整导出副本。");
        }
        effects.requireUnsharedMaterials(owner, plan.subjects());
        var entry = lifecycle.record(owner, key, "PURGE", plan.subjects(), plan.redactions(), preview.digest(), event -> {
            scope.apply(owner, plan);
            for (var subject : plan.subjects()) {
                lifecycle.setState(owner, subject.kind(), subject.id(), "PURGED", null, event);
            }
            effects.purge(owner, plan.subjects());
        });
        return new Result("PURGE", plan.subjects(), entry.sequence(), entry.hash(), "PURGED_BODYLESS_TOMBSTONES");
    }

    private Preview previewWithin(String owner, String action, String kind, String id) {
        if (!List.of("TRASH", "RESTORE", "PURGE").contains(action)) throw invalid();
        if ("AI_AUTHORIZATION".equals(kind)) {
            throw new ApiException(409, "PRIVACY_AUTHORIZATION_REVOKE_REQUIRED",
                    "用途授权必须在 AI 设置中撤权，不能通过回收站恢复；其历史正文副本随来源清除处理。");
        }
        var state = mapper.objectState(owner, kind, id);
        if (state != null && "PURGED".equals(state.state())) {
            throw new ApiException(410, "PRIVACY_PURGE_IRREVERSIBLE", "该内容已彻底清除，不能从回收站恢复。");
        }
        if ("RESTORE".equals(action) && (state == null || !"TRASHED".equals(state.state()))) {
            throw new ApiException(409, "PRIVACY_NOT_TRASHED", "该内容不在回收站。");
        }
        if ("TRASH".equals(action) && state != null && "TRASHED".equals(state.state())) {
            throw new ApiException(409, "PRIVACY_ALREADY_TRASHED", "该内容已在回收站。");
        }
        if ("TRASH".equals(action) && "TRAINING".equals(kind)) {
            var object = scope.object(owner, kind, id);
            if (List.of("RUNNING", "PAUSED").contains(object.status())) {
                throw new ApiException(409, "PRIVACY_TRAINING_ACTIVE", "请先结束当前训练，再将整份训练记录移入回收站。");
            }
        }
        if ("PURGE".equals(action)) {
            if (state == null || !"TRASHED".equals(state.state())) {
                throw new ApiException(409, "PRIVACY_TRASH_REQUIRED", "请先移入回收站，再预览彻底清除范围。");
            }
            effects.requireQuiescent(owner);
            var plan = scope.purgePreview(owner, kind, id);
            effects.requireUnsharedMaterials(owner, plan.subjects());
            return withExports(owner, plan.preview());
        }
        return scope.simplePreview(owner, action, kind, id);
    }

    private Preview withExports(String owner, Preview original) {
        List<String> exports = effects.exportIds(owner);
        List<RowImpact> storage = new ArrayList<>(original.storage());
        if (!exports.isEmpty()) storage.add(new RowImpact("REGISTERED_FULL_EXPORT_FILES", exports.size(),
                List.of("private_archive_zip"), "INVALIDATE_AND_CLEAR_WHOLE_MIXED_EXPORT"));
        String digest = PrivacyLedger.digest(original.digest() + "\n" + String.join("\n", exports));
        return new Preview(original.action(), original.objectKind(), original.objectId(), digest,
                original.objects(), List.copyOf(storage), original.limitations(), original.ledgerRedactions());
    }

    private void archive(String owner, String commandKey, ObjectInfo object) {
        if ("ARCHIVED".equals(object.status())) return;
        String key = UUID.nameUUIDFromBytes(("privacy-archive:" + commandKey).getBytes(StandardCharsets.UTF_8)).toString();
        switch (object.objectKind()) {
            case "KNOWLEDGE" -> knowledge.archiveForPrivacyWithinCommand(owner, object.objectId(), object.revision());
            case "PREPARATION" -> preparation.status(owner, object.objectId(), key,
                    new PreparationDtos.ChangeStatus(object.revision()), PreparationDtos.Status.ARCHIVED);
            case "QUESTION" -> questions.status(owner, object.objectId(), key,
                    new QuestionDtos.ChangeStatus(object.revision()), QuestionDtos.Status.ARCHIVED);
            case "MEMORY_CARD" -> memory.status(owner, object.objectId(), key,
                    new MemoryDtos.ChangeStatus(object.revision()), MemoryDtos.Status.ARCHIVED);
            case "QUESTION_COLLECTION" -> collections.status(owner, object.objectId(), key,
                    new CollectionDtos.ChangeStatus(object.revision()), CollectionDtos.Status.ARCHIVED);
            case "RECOLLECTION" -> recollections.status(owner, object.objectId(), key,
                    new RecollectionDtos.ChangeStatus(object.revision()), RecollectionDtos.Status.ARCHIVED);
            default -> { }
        }
    }

    private static void selection(SelectObject request) {
        if (request == null || request.objectKind() == null) throw invalid();
        PrivacyLedger.uuid(request.objectId());
    }
    private static void requireDigest(Preview preview, String expected) {
        if (!preview.digest().equals(expected)) {
            throw new ApiException(409, "PRIVACY_PREVIEW_STALE", "对象、版本或依赖已变化，必须重新生成清除影响清单。");
        }
    }
    private static ApiException invalid() {
        return new ApiException(400, "INVALID_PRIVACY_REQUEST", "隐私请求格式无效。");
    }
}
