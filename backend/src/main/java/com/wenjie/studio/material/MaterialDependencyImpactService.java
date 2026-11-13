package com.wenjie.studio.material;

import com.wenjie.studio.ai.AiDtos;
import com.wenjie.studio.ai.AiManifestDigest;
import com.wenjie.studio.ai.AiSources;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import com.wenjie.studio.lifecycle.KnowledgeRevalidationMapper;
import com.wenjie.studio.memory.MemoryInvalidation;
import com.wenjie.studio.review.ReviewedBasisInvalidation;
import com.wenjie.studio.review.ReviewedBasisMapper;
import com.wenjie.studio.review.ReviewedBasisRows;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.wenjie.studio.material.MaterialDependencyImpactDtos.*;
import static com.wenjie.studio.material.MaterialGuards.*;

@Service
public class MaterialDependencyImpactService {
    private static final int MAX_BINDINGS = 500;
    private static final int MAX_MANIFEST_BYTES = 131_072;
    private final MaterialDependencyImpactMapper mapper;
    private final MaterialCorrectionMapper corrections;
    private final MaterialMapper materials;
    private final MaterialProperties properties;
    private final IdempotencyService commands;
    private final KnowledgeRevalidationMapper frozen;
    private final ReviewedBasisMapper reviewed;
    private final AiManifestDigest digest;
    private final MaterialUsageTraversal usages;
    private final MemoryInvalidation memory;
    private final ReviewedBasisInvalidation reviewedInvalidation;
    private final JsonMapper json;

    public MaterialDependencyImpactService(MaterialDependencyImpactMapper mapper, MaterialCorrectionMapper corrections,
                                          MaterialMapper materials, MaterialProperties properties, IdempotencyService commands,
                                          KnowledgeRevalidationMapper frozen, ReviewedBasisMapper reviewed, AiManifestDigest digest,
                                          MaterialUsageTraversal usages, MemoryInvalidation memory, ReviewedBasisInvalidation reviewedInvalidation) {
        this.mapper = mapper; this.corrections = corrections; this.materials = materials; this.properties = properties;
        this.commands = commands; this.frozen = frozen; this.reviewed = reviewed; this.digest = digest;
        this.usages = usages; this.memory = memory; this.reviewedInvalidation = reviewedInvalidation;
        json = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                                .maxStringLength(MAX_MANIFEST_BYTES).maxNumberLength(32).build()).build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 20)
    public Page list(String owner, String excerptId, State state, String cursor, int limit) {
        enabled(); uuid(owner); uuid(excerptId);
        if (state == null || limit < 1 || limit > 20) throw invalid("须指定状态及 1–20 的每页数量。");
        if (materials.excerpt(owner, excerptId) == null) throw missing();
        Before before = cursor(cursor, owner, excerptId, state);
        var rows = mapper.impacts(owner, excerptId, state.name(), before == null ? null : before.at(),
                before == null ? null : before.id(), limit + 1);
        if (rows == null || rows.size() > limit + 1) throw integrity();
        var values = new ArrayList<Impact>();
        Instant previousTime = before == null ? null : before.at();
        String previousId = before == null ? null : before.id();
        var roots = new HashMap<String, MaterialCorrectionDtos.Statement>();
        for (var row : rows) {
            Impact value = view(owner, row, roots);
            if (!excerptId.equals(value.correction().excerptId()) || state == State.PENDING && value.resolution() != null
                    || previousTime != null && !(value.createdAt().isBefore(previousTime)
                    || value.createdAt().equals(previousTime) && value.id().compareTo(previousId) < 0)) throw integrity();
            values.add(value); previousTime = value.createdAt(); previousId = value.id();
        }
        List<Impact> items = List.copyOf(values.subList(0, Math.min(limit, values.size())));
        String next = values.size() <= limit ? null : owner + "~" + excerptId + "~" + state + "~"
                + items.getLast().createdAt() + "~" + items.getLast().id();
        return new Page(excerptId, state, items, next);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 20)
    public Impact detail(String owner, String impactId) {
        enabled(); uuid(owner); uuid(impactId);
        var row = mapper.impact(owner, impactId);
        if (row == null) throw notFound();
        return view(owner, row, new HashMap<>());
    }

    public Impact resolve(String owner, String impactId, String key, Resolve request) {
        enabled(); uuid(owner); uuid(impactId); uuid(key); request(request);
        return commands.execute(owner, key, "POST /api/v1/lifecycle/material-dependency-impacts/" + impactId + "/resolve",
                request, Impact.class, () -> {
                    var before = detail(owner, impactId);
                    if (before.revision() != request.expectedRevision() || before.resolution() != null) {
                        throw conflict("此次影响已处理，请保留原请求并读取最新状态。", before.revision());
                    }
                    var target = before.currentTarget();
                    if (!Objects.equals(request.expectedTargetVersionId(), target.versionId())
                            || !Objects.equals(request.expectedTargetRevision(), target.revision())) {
                        throw conflict("目标版本或修订已经变化，请重新核对目标。", target.revision());
                    }
                    choice(owner, before, request.choice());
                    Instant now = now();
                    Instant latest = frozen.latestFrozenAt(owner);
                    if (!validTime(now) || now.isBefore(before.createdAt()) || latest != null
                            && (!validTime(latest) || !now.isAfter(latest))) throw clock();
                    // 所有目标写命令共用 owner 锁；目标基线检查与影响 CAS、幂等回执在同一事务。
                    changed(mapper.resolve(owner, impactId, request, now));
                    Impact after = detail(owner, impactId);
                    if (after.revision() != 1 || after.resolution() == null || after.resolution().choice() != request.choice()
                            || !after.resolution().reason().equals(request.reason())
                            || !Objects.equals(after.resolution().targetVersionId(), target.versionId())
                            || !Objects.equals(after.resolution().targetRevision(), target.revision())
                            || !now.equals(after.resolution().decidedAt())) throw integrity();
                    return after;
                });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<MaterialDependencyImpactMapper.Dependency> collect(String owner, String excerptId) {
        writing(); uuid(owner); uuid(excerptId);
        var rows = mapper.dependencies(owner, excerptId, MAX_BINDINGS + 1);
        if (rows == null) throw integrity();
        if (rows.size() > MAX_BINDINGS) throw new ApiException(422, "MATERIAL_DEPENDENCY_LIMIT", "材料直接依赖超过完整处理上限，本次变更未提交。");
        var seen = new HashSet<String>();
        var tasks = new HashMap<String, AiDtos.Manifest>();
        for (var row : rows) {
            var saved = row(owner, id(), id(), excerptId, row, now());
            shape(saved);
            if (!seen.add(row.binding() + "~" + row.bindingId()) || !mapper.bindingExists(owner, excerptId, saved)) throw integrity();
            if (row.taskId() != null) proveTask(owner, excerptId, row, tasks);
        }
        return List.copyOf(rows);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void corrected(String owner, MaterialCorrectionDtos.Statement correction,
                          List<MaterialDependencyImpactMapper.Dependency> dependencies, String attachmentId,
                          List<MaterialUsageTraversal.Route> usageRoutes) {
        writing(); uuid(owner); uuid(attachmentId);
        if (correction == null || dependencies == null || dependencies.size() > MAX_BINDINGS) throw integrity();
        var actual = root(owner, correction.excerptId());
        if (!actual.equals(correction)) throw integrity();
        var seen = new HashSet<String>();
        var directTargets = new HashSet<String>();
        for (var dependency : dependencies) {
            var row = row(owner, id(), correction.id(), correction.excerptId(), dependency, correction.createdAt());
            shape(row);
            if (!seen.add(dependency.binding() + "~" + dependency.bindingId())
                    || !mapper.bindingExists(owner, correction.excerptId(), row)) throw integrity();
            changed(mapper.insert(row));
            directTargets.add(row.domain() + "~" + row.targetId() + "~" + row.targetVersionId() + "~" + row.targetRevision());
        }
        if (usageRoutes == null || usageRoutes.size() > 500) throw integrity();
        for (var route : usageRoutes) {
            var target = route.target();
            String targetKey = target.domain() + "~" + target.id() + "~" + target.versionId() + "~" + target.revision();
            if (directTargets.contains(targetKey) || usages.legacyKnowledge(owner, correction.id(), route)) continue;
            var dependency = new MaterialDependencyImpactMapper.Dependency(Binding.SOURCE_DEPENDENCY.name(),
                    usages.fingerprint(route), null, target.domain(), target.id(), target.versionId(), target.number(),
                    target.revision(), target.title());
            var row = row(owner, id(), correction.id(), correction.excerptId(), dependency, correction.createdAt());
            shape(row);
            changed(mapper.insert(row));
            usages.save(owner, row.id(), correction.excerptId(), route);
            if (!usages.prove(owner, row)) throw integrity();
            if (Domain.KNOWLEDGE.name().equals(row.domain())) {
                // 以新登记的确切影响身份进入既有卡片／审阅失效链，不能假冒正文发布事件。
                memory.knowledgeUpstreamChanged(owner, row.targetId(), row.targetVersionId(), row.id(), correction.createdAt());
                reviewedInvalidation.knowledgeUpstreamChanged(owner, row.targetId(), row.targetVersionId(), correction.createdAt());
            }
        }
        // 审阅以附件为真实直接来源；追加更正代次使原受审依据失效，不改原审阅、评分或快照。
        materialBasisChanged(owner, attachmentId, correction.createdAt());
    }

    public List<MaterialUsageTraversal.Route> collectUsage(String owner, String excerptId) {
        writing();
        return usages.collect(owner, excerptId);
    }

    public void checkClock(String owner, Instant now, List<MaterialUsageTraversal.Route> routes) {
        writing(); uuid(owner);
        if (routes == null || routes.size() > 500) throw integrity();
        Instant latest = frozen.latestFrozenAt(owner);
        if (!validTime(now) || latest != null && (!validTime(latest) || !now.isAfter(latest))) throw clock();
        // 原目标可能在任务创建后才采用；也须晚于整条固定路径，避免时钟回拨把影响藏到冻结点之前。
        for (var route : routes) {
            if (route == null || route.steps() == null || route.steps().isEmpty()) throw integrity();
            for (var step : route.steps()) {
                if (step == null || step.edge() == null || !validTime(step.edge().createdAt())) throw integrity();
                if (!now.isAfter(step.edge().createdAt())) throw clock();
            }
        }
    }

    public void localStatusChanged(String owner, String attachmentId, MaterialLifecycleDtos.Event event,
                                   List<MaterialUsageTraversal.Route> routes) {
        writing(); uuid(owner); uuid(attachmentId);
        for (var impact : usages.statusChanged(owner, event, routes)) {
            memory.knowledgeUpstreamChanged(owner, impact.itemId(), impact.versionId(), impact.id(), impact.createdAt());
            reviewedInvalidation.knowledgeUpstreamChanged(owner, impact.itemId(), impact.versionId(), impact.createdAt());
        }
        materialBasisChanged(owner, attachmentId, event.createdAt());
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true, noRollbackFor = RuntimeException.class)
    public boolean reviewRequired(String owner, Domain domain, String targetId, String versionId, Instant baseline) {
        uuid(owner); uuid(targetId);
        if (domain == null) throw invalid("依赖目标类型不能为空。");
        if (versioned(domain)) uuid(versionId);
        else if (versionId != null) throw invalid("此目标不具有版本标识。");
        if (baseline != null && !validTime(baseline)) throw integrity();
        return mapper.reviewRequired(owner, domain.name(), targetId, versionId, baseline)
                || mapper.lifecycleReviewRequired(owner, domain.name(), targetId, versionId, baseline);
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true, noRollbackFor = RuntimeException.class)
    public List<String> preparationExcerpts(String owner, String itemId, String versionId) {
        uuid(owner); uuid(itemId); uuid(versionId);
        var ids = mapper.preparationExcerpts(owner, itemId, versionId, 201);
        if (ids == null || ids.size() > 200 || new HashSet<>(ids).size() != ids.size()
                || ids.stream().anyMatch(value -> !storedUuid(value))) throw integrity();
        return List.copyOf(ids);
    }

    private Impact view(String owner, MaterialDependencyImpactMapper.Row row,
                        HashMap<String, MaterialCorrectionDtos.Statement> roots) {
        shape(row);
        if (!owner.equals(row.ownerId()) || !(Binding.SOURCE_DEPENDENCY.name().equals(row.binding())
                ? usages.prove(owner, row) : mapper.bindingExists(owner, row.excerptId(), row))) throw integrity();
        var correction = roots.computeIfAbsent(row.excerptId(), value -> root(owner, value));
        if (!row.correctionId().equals(correction.id()) || !row.createdAt().equals(correction.createdAt())) throw integrity();
        Domain domain = Domain.valueOf(row.domain());
        var target = new Target(domain, row.targetId(), row.targetVersionId(), row.targetVersionNumber(), row.targetRevision(), row.targetTitle());
        var current = current(owner, target);
        Resolution resolution = null;
        if (row.revision() == 0) {
            if (row.choice() != null || row.reason() != null || row.resolutionTargetVersionId() != null
                    || row.resolutionTargetRevision() != null || row.decidedAt() != null) throw integrity();
        } else {
            Choice choice;
            try { choice = Choice.valueOf(row.choice()); reason(row.reason()); }
            catch (IllegalArgumentException | NullPointerException | ApiException exception) { throw integrity(); }
            if (!validTime(row.decidedAt()) || row.decidedAt().isBefore(row.createdAt())
                    || versioned(domain)
                        && (!storedUuid(row.resolutionTargetVersionId()) || !safeRevision(row.resolutionTargetRevision())
                            || !List.of(Choice.KEEP_CURRENT, Choice.UPDATED, Choice.ARCHIVED).contains(choice))
                    || domain == Domain.RELATION && (row.resolutionTargetVersionId() != null || row.resolutionTargetRevision() != null
                        || !List.of(Choice.KEEP_CURRENT, Choice.REMOVED).contains(choice))
                    || domain == Domain.STANDARD_REVIEW && (row.resolutionTargetVersionId() != null
                        || !safeRevision(row.resolutionTargetRevision())
                        || !List.of(Choice.KEEP_CURRENT, Choice.UPDATED, Choice.WITHDRAWN).contains(choice))) throw integrity();
            resolution = new Resolution(choice, row.reason(), row.resolutionTargetVersionId(), row.resolutionTargetRevision(), row.decidedAt());
        }
        return new Impact(row.id(), row.revision(), correction, Binding.valueOf(row.binding()), row.bindingId(), row.taskId(),
                target, current, row.createdAt(), resolution);
    }

    private MaterialCorrectionDtos.Statement root(String owner, String excerptId) {
        var row = corrections.statement(owner, excerptId);
        if (row == null || !owner.equals(row.ownerId()) || !excerptId.equals(row.excerptId()) || !storedUuid(row.id())
                || !storedUuid(row.replacementExcerptId()) || excerptId.equals(row.replacementExcerptId())
                || !safeRevision(row.sourceRevision()) || !safeRevision(row.replacementRevision()) || !validTime(row.createdAt())) throw integrity();
        try { reason(row.reason()); } catch (ApiException exception) { throw integrity(); }
        return new MaterialCorrectionDtos.Statement(row.id(), excerptId, row.replacementExcerptId(), row.sourceRevision(),
                row.replacementRevision(), row.reason(), row.createdAt());
    }

    private CurrentTarget current(String owner, Target target) {
        var row = mapper.current(owner, target.domain().name(), target.id());
        if (row == null) {
            if (target.domain() == Domain.RELATION) {
                return new CurrentTarget(target.domain(), target.id(), null, null, null, "REMOVED", target.title());
            }
            throw integrity();
        }
        if (!target.domain().name().equals(row.domain()) || !target.id().equals(row.id()) || row.title() == null || row.title().isBlank()) throw integrity();
        if (versioned(target.domain())) {
            if (!storedUuid(row.versionId()) || row.versionNumber() == null || row.versionNumber() < target.versionNumber()
                    || !safeRevision(row.revision()) || !List.of("ACTIVE", "ARCHIVED").contains(row.status())) throw integrity();
        } else if (row.versionId() != null || row.versionNumber() != null
                || target.domain() == Domain.RELATION && (row.revision() != null || !"ACTIVE".equals(row.status()))
                || target.domain() == Domain.STANDARD_REVIEW && (!safeRevision(row.revision()) || row.revision() < target.revision()
                    || !List.of("ACTIVE", "WITHDRAWN").contains(row.status()))) throw integrity();
        return new CurrentTarget(target.domain(), row.id(), row.versionId(), row.versionNumber(), row.revision(), row.status(), row.title());
    }

    private void choice(String owner, Impact impact, Choice choice) {
        var target = impact.target(); var current = impact.currentTarget();
        boolean allowed = switch (target.domain()) {
            case PREPARATION, QUESTION, KNOWLEDGE -> switch (choice) {
                case KEEP_CURRENT -> "ACTIVE".equals(current.status());
                case ARCHIVED -> "ARCHIVED".equals(current.status());
                case UPDATED -> "ACTIVE".equals(current.status()) && mapper.bodyUpdated(owner, target.domain().name(),
                        target.id(), target.versionId(), current.versionId());
                default -> false;
            };
            case RELATION -> choice == Choice.KEEP_CURRENT && "ACTIVE".equals(current.status())
                    || choice == Choice.REMOVED && "REMOVED".equals(current.status());
            case STANDARD_REVIEW -> choice == Choice.KEEP_CURRENT && "ACTIVE".equals(current.status())
                    || choice == Choice.UPDATED && "ACTIVE".equals(current.status()) && current.revision() > target.revision()
                    || choice == Choice.WITHDRAWN && "WITHDRAWN".equals(current.status());
        };
        if (allowed && target.domain() == Domain.KNOWLEDGE && !mapper.knowledgeDraftClear(owner, target.id())) {
            throw new ApiException(409, "DRAFT_UNPUBLISHED", "知识存在未发布草稿，请先处理草稿后核对此次材料影响。", current.revision());
        }
        if (!allowed) throw conflict("处理方式与目标当前状态或真实更新不一致，请先处理目标后重新核对。", current.revision());
    }

    private void proveTask(String owner, String excerptId, MaterialDependencyImpactMapper.Dependency dependency,
                           HashMap<String, AiDtos.Manifest> cache) {
        var manifest = cache.get(dependency.taskId());
        if (manifest == null) {
            var task = mapper.task(owner, dependency.taskId());
            if (task == null || !owner.equals(task.ownerId()) || !dependency.taskId().equals(task.id())
                    || !"SUCCEEDED".equals(task.status()) || task.manifestJson() == null
                    || task.manifestJson().getBytes(StandardCharsets.UTF_8).length > MAX_MANIFEST_BYTES) throw integrity();
            try { manifest = json.readValue(task.manifestJson(), AiDtos.Manifest.class); }
            catch (RuntimeException exception) { throw integrity(); }
            if (manifest == null || manifest.purpose() == null || !manifest.purpose().name().equals(task.purpose())
                    || !Objects.equals(task.manifestHash(), digest.hash(manifest))) throw integrity();
            cache.put(dependency.taskId(), manifest);
        }
        List<MaterialDtos.AiExcerpt> snapshots;
        switch (Binding.valueOf(dependency.binding())) {
            case QUESTION_ADOPTION -> {
                if (manifest.purpose() != AiDtos.Purpose.QUESTION_DRAFT || !AiSources.validDraftInput(manifest)) throw integrity();
                snapshots = manifest.materialDraftSources();
            }
            case FACT_ADOPTION -> {
                if (manifest.purpose() != AiDtos.Purpose.FACT_DRAFT || !AiSources.validFactDraftInput(manifest)
                        || manifest.factDraft() == null) throw integrity();
                snapshots = manifest.factDraft().materials();
            }
            case RELATION_ADOPTION -> {
                if (manifest.purpose() != AiDtos.Purpose.RELATION_DRAFT || !AiSources.validRelationInput(manifest)
                        || manifest.relationEvidence() == null) throw integrity();
                snapshots = manifest.relationEvidence().materials();
            }
            default -> throw integrity();
        }
        var bindings = mapper.taskMaterials(owner, dependency.taskId(), dependency.binding(), 6);
        if (snapshots == null || snapshots.isEmpty() || snapshots.size() > 5 || bindings == null || snapshots.size() != bindings.size()) throw integrity();
        var seen = new HashSet<String>();
        for (int index = 0; index < bindings.size(); index++) {
            var binding = bindings.get(index); var snapshot = snapshots.get(index);
            if (snapshot == null || binding == null || binding.ordinal() == null || binding.ordinal() != index + 1
                    || !snapshot.excerptId().equals(binding.excerptId()) || !seen.add(binding.excerptId())
                    || !snapshot.equals(material(owner, binding.excerptId()))) throw integrity();
        }
        if (!seen.contains(excerptId)) throw integrity();
    }

    private MaterialDtos.AiExcerpt material(String owner, String excerptId) {
        var excerpt = materials.excerpt(owner, excerptId);
        if (excerpt == null || !owner.equals(excerpt.ownerId()) || !excerptId.equals(excerpt.id())) throw integrity();
        var attachment = materials.attachment(owner, excerpt.attachmentId());
        if (attachment == null || !owner.equals(attachment.ownerId()) || !excerpt.attachmentId().equals(attachment.id())
                || !"READY".equals(attachment.status())
                || !attachment.sha256().equals(excerpt.attachmentSha256())) throw integrity();
        if ("MANUAL".equals(excerpt.origin())) {
            if (excerpt.runId() != null || excerpt.segmentId() != null || excerpt.startOffset() != null || excerpt.endOffset() != null) throw integrity();
        } else if ("PARSED".equals(excerpt.origin())) {
            var run = materials.run(owner, excerpt.runId()); var segment = materials.segment(owner, excerpt.segmentId());
            if (run == null || segment == null || !owner.equals(run.ownerId()) || !excerpt.runId().equals(run.id())
                    || !excerpt.attachmentId().equals(run.attachmentId()) || !"SUCCEEDED".equals(run.status())
                    || !attachment.sha256().equals(run.attachmentSha256()) || !excerpt.segmentId().equals(segment.id())
                    || !excerpt.runId().equals(segment.runId()) || !excerpt.attachmentId().equals(segment.attachmentId())
                    || !excerpt.locator().equals(segment.locator()) || excerpt.startOffset() == null || excerpt.endOffset() == null
                    || excerpt.startOffset() < 0 || excerpt.endOffset() <= excerpt.startOffset()
                    || excerpt.endOffset() > segment.text().length()
                    || splitsSurrogate(segment.text(), excerpt.startOffset()) || splitsSurrogate(segment.text(), excerpt.endOffset())
                    || !excerpt.text().equals(segment.text().substring(excerpt.startOffset(), excerpt.endOffset()))) throw integrity();
        } else throw integrity();
        return new MaterialDtos.AiExcerpt(excerpt.id(), attachment.id(), attachment.fileName(), attachment.sha256(), excerpt.origin(),
                excerpt.runId(), excerpt.segmentId(), excerpt.locator(), excerpt.text(), excerpt.startOffset(), excerpt.endOffset());
    }

    private void materialBasisChanged(String owner, String attachmentId, Instant now) {
        var before = reviewed.lockGeneration(owner, "MATERIAL_ATTACHMENT", attachmentId);
        if (before != null && (!owner.equals(before.ownerId()) || !"MATERIAL_ATTACHMENT".equals(before.sourceKind())
                || !attachmentId.equals(before.sourceId()) || before.generation() < 1 || before.generation() > MAX_REVISION
                || !validTime(before.updatedAt()) || now.isBefore(before.updatedAt()))) throw integrity();
        long generation = before == null ? 0 : before.generation();
        if (generation == MAX_REVISION) throw conflict("材料受审依据代次已达到上限，本次更正未提交。", generation);
        var after = new ReviewedBasisRows.GenerationRow(owner, "MATERIAL_ATTACHMENT", attachmentId, generation + 1, now);
        changed(before == null ? reviewed.insertGeneration(after) : reviewed.updateGeneration(after, generation));
        changed(reviewed.insertChange(new ReviewedBasisRows.ChangeRow(owner, "MATERIAL_ATTACHMENT", attachmentId, generation + 1,
                "MATERIAL_BASIS_CHANGED", null, null, "READY", "READY", now, null)));
    }

    private static MaterialDependencyImpactMapper.Row row(String owner, String id, String correctionId, String excerptId,
            MaterialDependencyImpactMapper.Dependency dependency, Instant now) {
        if (dependency == null) throw integrity();
        return new MaterialDependencyImpactMapper.Row(owner, id, correctionId, excerptId, dependency.binding(), dependency.bindingId(),
                dependency.taskId(), dependency.domain(), dependency.targetId(), dependency.targetVersionId(),
                dependency.targetVersionNumber(), dependency.targetRevision(), dependency.targetTitle(), 0L, now, null, null, null, null, null);
    }
    private static void shape(MaterialDependencyImpactMapper.Row row) {
        if (row == null || !storedUuid(row.ownerId()) || !storedUuid(row.id()) || !storedUuid(row.correctionId())
                || !storedUuid(row.excerptId()) || !storedUuid(row.targetId()) || row.revision() == null || row.revision() < 0 || row.revision() > 1
                || !validTime(row.createdAt()) || row.targetTitle() == null || row.targetTitle().isBlank()
                || row.targetTitle().codePointCount(0, row.targetTitle().length()) > 200
                || row.bindingId() == null || row.bindingId().isEmpty() || row.bindingId().length() > 120) throw integrity();
        Domain domain; Binding binding;
        try { domain = Domain.valueOf(row.domain()); binding = Binding.valueOf(row.binding()); }
        catch (IllegalArgumentException | NullPointerException exception) { throw integrity(); }
        boolean task = List.of(Binding.QUESTION_ADOPTION, Binding.FACT_ADOPTION, Binding.RELATION_ADOPTION).contains(binding);
        if (task ? !storedUuid(row.taskId()) : row.taskId() != null) throw integrity();
        boolean target = switch (binding) {
            case PREPARATION_REFERENCE, FACT_ADOPTION -> domain == Domain.PREPARATION;
            case QUESTION_ADOPTION -> domain == Domain.QUESTION;
            case RELATION_ADOPTION, RELATION_EVIDENCE -> domain == Domain.RELATION;
            case REVIEW_EVIDENCE -> domain == Domain.STANDARD_REVIEW;
            case SOURCE_DEPENDENCY -> true;
        };
        if (!target) throw integrity();
        if (versioned(domain)) {
            if (!storedUuid(row.targetVersionId()) || row.targetVersionNumber() == null || row.targetVersionNumber() < 1
                    || row.targetRevision() != null) throw integrity();
        } else if (row.targetVersionId() != null || row.targetVersionNumber() != null
                || domain == Domain.RELATION && row.targetRevision() != null
                || domain == Domain.STANDARD_REVIEW && !safeRevision(row.targetRevision())) throw integrity();
    }
    private static void request(Resolve request) {
        if (request == null || request.choice() == null || !Boolean.TRUE.equals(request.confirmed())) throw invalid("须明确确认本次处理。");
        revision(request.expectedRevision());
        if (request.expectedTargetVersionId() != null) uuid(request.expectedTargetVersionId());
        if (request.expectedTargetRevision() != null) revision(request.expectedTargetRevision());
        reason(request.reason());
    }
    private static void reason(String value) {
        if (value == null || value.codePoints().allMatch(point -> Character.isWhitespace(point) || Character.isSpaceChar(point))
                || value.codePointCount(0, value.length()) > 1000 || value.indexOf('\0') >= 0
                || value.codePoints().anyMatch(point -> point >= 0xD800 && point <= 0xDFFF)) throw invalid("理由须为 1–1000 字且不能含无效字符。");
    }
    private static Before cursor(String value, String owner, String excerptId, State state) {
        if (value == null) return null;
        if (value.length() > 200) throw invalid("依赖目录游标无效。");
        String[] parts = value.split("~", -1);
        if (parts.length != 5 || !owner.equals(parts[0]) || !excerptId.equals(parts[1]) || !state.name().equals(parts[2])) throw invalid("游标不属于此目录。");
        Instant at = cursorTime(parts[3]); uuid(parts[4]);
        return new Before(at, parts[4]);
    }
    private static boolean storedUuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); } catch (IllegalArgumentException exception) { return false; }
    }
    private static boolean validTime(Instant value) {
        return value != null && value.getNano() % 1000 == 0 && !value.isBefore(Instant.parse("1000-01-01T00:00:00Z"))
                && !value.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"));
    }
    private static boolean safeRevision(Long value) { return value != null && value >= 0 && value <= MAX_REVISION; }
    private static boolean versioned(Domain domain) {
        return domain == Domain.PREPARATION || domain == Domain.QUESTION || domain == Domain.KNOWLEDGE;
    }
    private static boolean splitsSurrogate(String text, int index) {
        return index > 0 && index < text.length() && Character.isHighSurrogate(text.charAt(index - 1))
                && Character.isLowSurrogate(text.charAt(index));
    }
    private static void writing() {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("材料依赖写入须加入持有 owner 锁的命令事务");
        }
    }
    private void enabled() { if (!properties.enabled()) throw new ApiException(503, "MATERIALS_DISABLED", "服务器尚未启用附件资料。"); }
    private static void changed(int count) { if (count != 1) throw integrity(); }
    private static ApiException notFound() { return new ApiException(404, "MATERIAL_DEPENDENCY_NOT_FOUND", "材料依赖影响不存在或不可访问。"); }
    private static ApiException conflict(String message, Long revision) { return new ApiException(409, "MATERIAL_DEPENDENCY_CONFLICT", message, revision); }
    private static ApiException clock() { return new ApiException(409, "SOURCE_CLOCK_CONFLICT", "服务器时间未晚于既有冻结证据或处理，本次命令未提交。"); }
    private static IllegalStateException integrity() { return new IllegalStateException("材料直接依赖的固定绑定、目标或处理状态不一致"); }
    private record Before(Instant at, String id) {}
}
