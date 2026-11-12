package com.wenjie.studio.bank;

import com.wenjie.studio.bank.CollectionDtos.*;
import com.wenjie.studio.bank.CollectionRows.CollectionRow;
import com.wenjie.studio.bank.CollectionRows.VersionRow;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.wenjie.studio.bank.QuestionGuards.*;

@Service
public class CollectionService {
    private final CollectionMapper mapper;
    private final QuestionService questions;
    private final IdempotencyService commands;
    private final JsonMapper json;

    public CollectionService(CollectionMapper mapper, QuestionService questions,
                              IdempotencyService commands, JsonMapper json) {
        this.mapper = mapper;
        this.questions = questions;
        this.commands = commands;
        this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CollectionPage list(String owner, CollectionKind kind, Status status, String query, String cursor, int limit) {
        uuid(owner); required(status); pageLimit(limit);
        if (cursor != null) uuid(cursor);
        List<CollectionRow> rows = mapper.collections(owner, kind == null ? null : kind.name(), status.name(),
                pattern(query), cursor, limit + 1);
        List<CollectionSummary> page = rows.stream().limit(limit).map(this::summary).toList();
        return new CollectionPage(page, rows.size() > limit ? page.getLast().id() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Collection detail(String owner, String id) { return current(owner, requireCollection(owner, id)); }

    public Collection create(String owner, String key, CreateCollection request) {
        required(request); required(request.kind()); required(request.content());
        return commands.execute(owner, key, "POST /api/v1/bank/collections", request, Collection.class, () -> {
            CollectionInput content = checked(request.kind(), request.content());
            validateSources(owner, content.steps(), Set.of());
            String id = UUID.randomUUID().toString();
            String versionId = UUID.randomUUID().toString();
            Instant now = now();
            mapper.insertCollection(new CollectionRow(id, owner, request.kind().name(), content.title(),
                    Status.ACTIVE.name(), 0, versionId, 1, content.steps().size(), now, now));
            insertVersion(owner, id, versionId, 1, content, now);
            return current(owner, requireCollection(owner, id));
        });
    }

    public Collection save(String owner, String id, String key, SaveCollection request) {
        uuid(id); required(request); revision(request.expectedRevision()); required(request.content());
        return commands.execute(owner, key, "PUT /api/v1/bank/collections/" + id, request, Collection.class, () -> {
            CollectionRow before = changeable(owner, id, request.expectedRevision());
            if (!Status.ACTIVE.name().equals(before.status())) {
                throw new ApiException(409, "ITEM_ARCHIVED", "请先恢复题单或路线，再修改步骤。");
            }
            if (before.versionNumber() == Integer.MAX_VALUE) throw revisionLimit();
            CollectionInput content = checked(CollectionKind.valueOf(before.kind()), request.content());
            Set<QuestionLink> previous = new HashSet<>();
            // 旧引用资格来自服务端上一版，不能由客户端携带的版本内容自证。
            for (var reference : mapper.references(owner, id, before.currentVersionId())) {
                previous.add(new QuestionLink(reference.questionId(), reference.versionId()));
            }
            validateSources(owner, content.steps(), previous);
            String versionId = UUID.randomUUID().toString();
            int number = before.versionNumber() + 1;
            Instant now = now();
            insertVersion(owner, id, versionId, number, content, now);
            changed(mapper.saveContent(owner, id, before.revision(), versionId, number,
                    content.title(), content.steps().size(), now));
            return current(owner, requireCollection(owner, id));
        });
    }

    public Collection status(String owner, String id, String key, ChangeStatus request, Status status) {
        uuid(id); required(request); revision(request.expectedRevision()); required(status);
        String action = status == Status.ARCHIVED ? "/archive" : "/restore";
        return commands.execute(owner, key, "POST /api/v1/bank/collections/" + id + action,
                request, Collection.class, () -> {
                    CollectionRow before = changeable(owner, id, request.expectedRevision());
                    changed(mapper.changeStatus(owner, id, before.revision(), status.name(), now()));
                    return current(owner, requireCollection(owner, id));
                });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public VersionPage versions(String owner, String id, Integer before, int limit) {
        requireCollection(owner, id); pageLimit(limit);
        if (before != null && before < 1) throw invalid("历史版本游标必须为正整数。");
        List<VersionSummary> rows = mapper.versions(owner, id, before, limit + 1);
        List<VersionSummary> page = rows.stream().limit(limit).toList();
        return new VersionPage(page, rows.size() > limit ? page.getLast().number() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CollectionVersion version(String owner, String id, String versionId) {
        requireCollection(owner, id);
        VersionRow row = mapper.version(owner, id, uuid(versionId));
        if (row == null) throw notFound();
        return toVersion(row);
    }

    private CollectionInput checked(CollectionKind kind, CollectionInput input) {
        String title = text(input.title(), 200, true);
        String purpose = text(input.purpose(), 5000, false);
        List<Step> steps = new ArrayList<>();
        Set<String> questionIds = new HashSet<>();
        Map<String, Step> byId = new HashMap<>();
        for (Step step : QuestionGuards.list(input.steps(), 50)) {
            uuid(step.id()); uuid(step.questionId()); uuid(step.questionVersionId());
            List<String> dependencies = QuestionGuards.list(step.dependsOn(), 49);
            dependencies.forEach(QuestionGuards::uuid);
            distinct(dependencies);
            if (kind == CollectionKind.LIST && !dependencies.isEmpty()) {
                throw invalid("普通题单不能设置前置步骤。");
            }
            Step normalized = new Step(step.id(), step.questionId(), step.questionVersionId(),
                    text(step.purpose(), 1000, false), dependencies, text(step.checkpoint(), 1000, false));
            if (byId.putIfAbsent(step.id(), normalized) != null || !questionIds.add(step.questionId())) {
                throw invalid("步骤标识和题目在同一题单或路线中不能重复。");
            }
            steps.add(normalized);
        }
        for (Step step : steps) {
            for (String dependency : step.dependsOn()) {
                if (dependency.equals(step.id()) || !byId.containsKey(dependency)) {
                    throw invalid("前置步骤必须指向当前版本内的其他步骤。");
                }
            }
        }
        // 顺序只用于展示；依赖按显式边检查，因此前置步骤也可位于数组后方。
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (Step step : steps) visit(step.id(), byId, visiting, visited);
        return new CollectionInput(title, purpose, List.copyOf(steps));
    }

    private static void visit(String id, Map<String, Step> steps, Set<String> visiting, Set<String> visited) {
        if (visited.contains(id)) return;
        if (!visiting.add(id)) throw new ApiException(409, "ROUTE_CYCLE", "路线的前置步骤形成循环，请调整依赖。");
        for (String dependency : steps.get(id).dependsOn()) visit(dependency, steps, visiting, visited);
        visiting.remove(id);
        visited.add(id);
    }

    private void validateSources(String owner, List<Step> steps, Set<QuestionLink> previous) {
        for (Step step : steps) {
            QuestionLink link = new QuestionLink(step.questionId(), step.questionVersionId());
            if (previous.contains(link)) continue;
            try {
                // 调用方已取得工作区锁；来源校验和本次不可变版本保存处于同一事务。
                var version = questions.version(owner, step.questionId(), step.questionVersionId());
                var question = questions.detail(owner, step.questionId());
                if (question.status() != QuestionDtos.Status.ACTIVE
                        || !version.id().equals(question.currentVersion().id())) {
                    throw new ApiException(409, "SOURCE_CHANGED", "新增或更换的题目引用必须是当前可用版本，请重新选择。");
                }
            } catch (ApiException exception) {
                if (exception.status() == 404) {
                    throw new ApiException(422, "SOURCE_UNAVAILABLE", "所选题目版本不存在或不可访问。");
                }
                throw exception;
            }
        }
    }

    private void insertVersion(String owner, String id, String versionId, int number, CollectionInput content, Instant now) {
        mapper.insertVersion(new VersionRow(versionId, id, owner, number, content.title(), json.writeValueAsString(content), now));
        for (int index = 0; index < content.steps().size(); index++) {
            mapper.insertStep(owner, id, versionId, content.steps().get(index), index);
        }
        // 先插入所有步骤，再插入依赖，使同版本复合外键不受显示顺序影响。
        for (Step step : content.steps()) {
            for (String dependency : step.dependsOn()) {
                mapper.insertDependency(owner, id, versionId, step.id(), dependency);
            }
        }
    }

    private Collection current(String owner, CollectionRow row) {
        VersionRow version = mapper.version(owner, row.id(), row.currentVersionId());
        if (version == null) throw new IllegalStateException("题单当前版本不完整");
        var references = mapper.references(owner, row.id(), version.id()).stream().map(reference -> {
            ReferenceState state = Status.ARCHIVED.name().equals(reference.status()) ? ReferenceState.ARCHIVED
                    : reference.versionId().equals(reference.currentVersionId()) ? ReferenceState.CURRENT : ReferenceState.SUPERSEDED;
            return new QuestionReference(reference.stepId(), reference.questionId(), reference.versionId(),
                    reference.title(), reference.versionNumber(), state);
        }).toList();
        if (references.size() != row.stepCount()) throw new IllegalStateException("题单步骤引用不完整");
        return new Collection(row.id(), CollectionKind.valueOf(row.kind()), Status.valueOf(row.status()), row.revision(),
                row.createdAt(), row.updatedAt(), toVersion(version), references);
    }

    private CollectionSummary summary(CollectionRow row) {
        return new CollectionSummary(row.id(), CollectionKind.valueOf(row.kind()), row.title(), Status.valueOf(row.status()),
                row.revision(), row.currentVersionId(), row.versionNumber(), row.stepCount(), row.updatedAt());
    }

    private CollectionVersion toVersion(VersionRow row) {
        return new CollectionVersion(row.id(), row.versionNumber(), row.createdAt(), json.readValue(row.contentJson(), CollectionInput.class));
    }

    private CollectionRow requireCollection(String owner, String id) {
        CollectionRow row = mapper.collection(uuid(owner), uuid(id));
        if (row == null) throw notFound();
        return row;
    }

    private CollectionRow changeable(String owner, String id, long expected) {
        CollectionRow row = requireCollection(owner, id);
        if (row.revision() != expected) {
            throw new ApiException(409, "REVISION_CONFLICT", "题单或路线已被其他操作修改，请保留输入后重新读取。", row.revision());
        }
        if (row.revision() == MAX_REVISION) throw revisionLimit();
        return row;
    }

    private static void changed(int count) {
        if (count != 1) throw new ApiException(409, "REVISION_CONFLICT", "题单或路线已变化，本次修改未提交。");
    }
    private static ApiException revisionLimit() { return new ApiException(409, "REVISION_LIMIT", "题单或路线的修订号或版本号已达到上限。"); }
    private static ApiException notFound() { return new ApiException(404, "COLLECTION_NOT_FOUND", "题单或路线不存在或不可访问。"); }
    private record QuestionLink(String questionId, String versionId) {}
}
