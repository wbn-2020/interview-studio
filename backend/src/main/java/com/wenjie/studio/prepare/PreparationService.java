package com.wenjie.studio.prepare;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import com.wenjie.studio.knowledge.KnowledgeDtos;
import com.wenjie.studio.knowledge.KnowledgeService;
import com.wenjie.studio.material.MaterialService;
import com.wenjie.studio.lifecycle.PreparationKnowledgePropagation;
import com.wenjie.studio.lifecycle.PreparationKnowledgeDtos.ChangeKind;
import com.wenjie.studio.lifecycle.KnowledgeRevalidationQuery;
import com.wenjie.studio.prepare.PreparationDtos.*;
import com.wenjie.studio.prepare.PreparationRows.ItemRow;
import com.wenjie.studio.prepare.PreparationRows.VersionRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class PreparationService {
    private static final long MAX_REVISION = 9_007_199_254_740_991L;
    private static final Set<String> DIMENSIONS = Set.of("EXPLAIN", "DEBUG", "TRADEOFF", "OWNERSHIP", "COMMUNICATION", "OTHER");
    private final PreparationMapper mapper;
    private final IdempotencyService commands;
    private final KnowledgeService knowledge;
    private final MaterialService materials;
    private final ExpressionBasisReviewService expressionBasis;
    private final PreparationKnowledgePropagation knowledgePropagation;
    private final KnowledgeRevalidationQuery knowledgeRevalidation;
    private final PreparationSources sourceEligibility;
    private final JsonMapper json;

    public PreparationService(PreparationMapper mapper, IdempotencyService commands,
                              KnowledgeService knowledge, MaterialService materials, JsonMapper json,
                              ExpressionBasisReviewService expressionBasis, PreparationKnowledgePropagation knowledgePropagation,
                              KnowledgeRevalidationQuery knowledgeRevalidation, PreparationSources sourceEligibility) {
        this.mapper = mapper;
        this.commands = commands;
        this.knowledge = knowledge;
        this.materials = materials;
        this.expressionBasis = expressionBasis;
        this.knowledgePropagation = knowledgePropagation;
        this.knowledgeRevalidation = knowledgeRevalidation;
        this.sourceEligibility = sourceEligibility;
        this.json = json.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ItemPage list(String owner, Kind kind, Status status, String query, Verification verification,
                         Boolean favorite, String projectId, String cursor, int limit) {
        required(status);
        limit(limit);
        if (query != null && query.length() > 100) throw invalid("标题筛选最多 100 个字符。");
        if (verification != null && kind != Kind.FACT) throw invalid("核实筛选仅用于事实。");
        if (projectId != null) {
            uuid(projectId);
            ItemRow project = requireItem(owner, projectId);
            if (!project.kind().equals("PROJECT")) throw invalid("所属项目标识必须指向项目。");
        }
        if (cursor != null) uuid(cursor);
        String pattern = query == null || query.isBlank() ? null : "%" + query.strip().replace("!", "!!")
                .replace("%", "!%").replace("_", "!_") + "%";
        var rows = mapper.items(owner, kind == null ? null : kind.name(), status.name(), pattern,
                verification == null ? null : verification.name(), favorite, projectId, cursor, limit + 1);
        boolean more = rows.size() > limit;
        var page = rows.subList(0, Math.min(rows.size(), limit));
        return new ItemPage(page.stream().map(this::summary).toList(), more ? page.getLast().id() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Item detail(String owner, String id) { return current(owner, requireItem(owner, uuid(id))); }

    public Item create(String owner, String key, CreateItem request) {
        required(request);
        required(request.kind());
        return commands.execute(owner, key, "POST /api/v1/prepare/items", request, Item.class, () -> {
            if (request.kind() == Kind.FACT) {
                ItemRow project = requireItem(owner, uuid(request.projectId()));
                if (!project.kind().equals("PROJECT")) throw invalid("事实必须属于项目。");
                active(project);
            } else if (request.projectId() != null) throw invalid("只有事实可指定所属项目。");
            Checked content = checkContent(request.kind(), request.content());
            List<VersionLink> links = links(owner, request.kind(), content.value());
            validateLinks(owner, links, Set.of());
            String id = newId();
            String versionId = newId();
            Instant now = now();
            mapper.insertItem(new ItemRow(id, owner, request.kind().name(), request.projectId(), content.title(),
                    "ACTIVE", 0, request.kind() == Kind.FACT ? "UNVERIFIED" : null, false, versionId, 1, now, now));
            insertVersion(owner, id, versionId, 1, content, links, request.kind(), now);
            if (request.kind() == Kind.EXPRESSION) expressionBasis.capture(owner, id, versionId, now);
            return current(owner, requireItem(owner, id));
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Item createFactWithinCommand(String owner, String projectId, String expectedProjectVersionId,
                                        long expectedProjectRevision, FactContent content) {
        // 外层命令须持有同一 owner 锁，使事实首版、摘录关系、采用决定和回执原子提交。
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("事实采用必须处于命令事务中");
        }
        uuid(owner); uuid(projectId); uuid(expectedProjectVersionId); revision(expectedProjectRevision);
        required(content);
        ItemRow project = requireItem(owner, projectId);
        if (!project.kind().equals("PROJECT")) throw invalid("事实必须属于项目。");
        active(project);
        expect(expectedProjectRevision, project.revision());
        if (!expectedProjectVersionId.equals(project.currentVersionId())) {
            throw new ApiException(409, "VERSION_CONFLICT", "所属项目版本已变化，请保留事实后重新选择。", project.revision());
        }
        if (content.responsibility() != Responsibility.UNVERIFIED) {
            throw invalid("事实候选采用时的责任边界必须为待核实。");
        }
        Checked checked = checkContent(Kind.FACT, json.readTree(json.writeValueAsString(content)));
        List<VersionLink> links = links(owner, Kind.FACT, checked.value());
        validateLinks(owner, links, Set.of());
        String id = newId();
        String versionId = newId();
        Instant now = now();
        mapper.insertItem(new ItemRow(id, owner, Kind.FACT.name(), projectId, checked.title(),
                "ACTIVE", 0, Verification.UNVERIFIED.name(), false, versionId, 1, now, now));
        insertVersion(owner, id, versionId, 1, checked, links, Kind.FACT, now);
        return current(owner, requireItem(owner, id));
    }

    public Item save(String owner, String id, String key, SaveItem request) {
        uuid(id);
        required(request);
        revision(request.expectedRevision());
        return commands.execute(owner, key, "PUT /api/v1/prepare/items/" + id, request, Item.class, () -> {
            ItemRow before = changeable(owner, id, request.expectedRevision());
            active(before);
            if (before.versionNumber() == Integer.MAX_VALUE) throw revisionLimit();
            Kind kind = Kind.valueOf(before.kind());
            Checked content = checkContent(kind, request.content());
            List<VersionLink> links = links(owner, kind, content.value());
            Set<String> preserved = new HashSet<>();
            if (kind == Kind.TARGET) {
                for (var link : mapper.references(owner, before.currentVersionId())) {
                    preserved.add(link.sourceKind() + ":" + link.sourceItemId() + ":" + link.sourceVersionId());
                }
            }
            validateLinks(owner, links, preserved);
            var sourceBefore = knowledgePropagation.observe(owner, id);
            String versionId = newId();
            Instant now = now();
            insertVersion(owner, id, versionId, before.versionNumber() + 1, content, links, kind, now);
            // 新事实版本一律待核实；旧版本的确认决定与表达依据原封不动保留。
            changed(mapper.saveContent(owner, id, before.revision(), versionId,
                    before.versionNumber() + 1, content.title(), now));
            if (kind == Kind.EXPRESSION) expressionBasis.capture(owner, id, versionId, now);
            knowledgePropagation.changed(owner, sourceBefore, ChangeKind.CONTENT_SAVED, now);
            return current(owner, requireItem(owner, id));
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Item adoptRequirements(String owner, String targetId, String expectedVersionId,
                                  Long expectedRevision, List<Requirement> appended) {
        // 外层命令持有同一 owner 锁；新岗位版本、采用决定和回执必须一起提交。
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("岗位要求采用必须处于命令事务中");
        }
        uuid(owner); uuid(targetId); uuid(expectedVersionId); revision(expectedRevision);
        bounded(appended, 1, 20, "追加要求");
        ItemRow before = changeable(owner, targetId, expectedRevision);
        if (!before.kind().equals("TARGET")) throw invalid("采用目标必须为岗位目标。");
        active(before);
        if (!expectedVersionId.equals(before.currentVersionId())) {
            throw new ApiException(409, "VERSION_CONFLICT", "岗位目标版本已变化，请保留要求后重新对照。", before.revision());
        }
        if (before.versionNumber() == Integer.MAX_VALUE) throw revisionLimit();
        for (Requirement requirement : appended) requirementText(required(requirement).text());

        TargetContent previous = json.readValue(requireVersion(owner, before).contentJson(), TargetContent.class);
        var requirements = new ArrayList<>(previous.requirements());
        requirements.addAll(appended);
        // 只追加新要求；既有要求身份、原顺序及全部其他岗位字段均取当前固定版本。
        var content = new TargetContent(previous.title(), previous.company(), previous.jd(), previous.sourceUrl(),
                previous.capturedOn(), previous.weeklyMinutes(), requirements, previous.links());
        // 继承字段来自固定旧版本；只追加已严格校验的新要求，不把新输入规则追溯施加到历史原文。
        Checked checked = checkContentFields(Kind.TARGET, json.readTree(json.writeValueAsString(content)));
        List<VersionLink> links = links(owner, Kind.TARGET, checked.value());
        Set<String> preserved = new HashSet<>();
        for (var link : mapper.references(owner, before.currentVersionId())) {
            preserved.add(link.sourceKind() + ":" + link.sourceItemId() + ":" + link.sourceVersionId());
        }
        validateLinks(owner, links, preserved);
        var sourceBefore = knowledgePropagation.observe(owner, targetId);
        String versionId = newId();
        int number = before.versionNumber() + 1;
        Instant now = now();
        insertVersion(owner, targetId, versionId, number, checked, links, Kind.TARGET, now);
        changed(mapper.saveContent(owner, targetId, before.revision(), versionId, number, checked.title(), now));
        knowledgePropagation.changed(owner, sourceBefore, ChangeKind.CONTENT_SAVED, now);
        return current(owner, requireItem(owner, targetId));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Item adoptExpression(String owner, String targetId, String expectedVersionId,
                                Long expectedRevision, ExpressionContent content) {
        // 外层 AI 命令持有 owner 锁并复核授权与来源代次；这里加入同一事务，不生成第二份幂等回执。
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("表达采用必须处于命令事务中");
        }
        uuid(owner);
        required(content);
        ItemRow before = null;
        if (targetId == null) {
            if (expectedVersionId != null || expectedRevision != null) throw invalid("新建表达不能指定目标版本或修订号。");
        } else {
            uuid(targetId);
            uuid(expectedVersionId);
            revision(expectedRevision);
            before = changeable(owner, targetId, expectedRevision);
            if (!before.kind().equals("EXPRESSION")) throw invalid("采用目标必须为表达素材。");
            active(before);
            if (!expectedVersionId.equals(before.currentVersionId())) {
                throw new ApiException(409, "VERSION_CONFLICT", "目标表达版本已变化，请保留改写后重新对照。", before.revision());
            }
            if (before.versionNumber() == Integer.MAX_VALUE) throw revisionLimit();
        }

        var finalContent = new ExpressionContent(expressionText(content.title(), 200, "标题"), required(content.type()),
                expressionText(content.body(), 12000, "表达正文"),
                List.copyOf(bounded(content.factVersionIds(), 1, 5, "事实依据")), content.tags());
        Checked checked = checkContent(Kind.EXPRESSION, json.readTree(json.writeValueAsString(finalContent)));
        List<VersionLink> links = links(owner, Kind.EXPRESSION, checked.value());
        validateLinks(owner, links, Set.of());
        String id = before == null ? newId() : before.id();
        var sourceBefore = before == null ? null : knowledgePropagation.observe(owner, id);
        String versionId = newId();
        int number = before == null ? 1 : before.versionNumber() + 1;
        Instant now = now();
        if (before == null) {
            mapper.insertItem(new ItemRow(id, owner, Kind.EXPRESSION.name(), null, checked.title(),
                    "ACTIVE", 0, null, false, versionId, number, now, now));
        }
        // 采用保存审阅后的完整版本及确切事实依据，不将候选拼接到旧正文。
        insertVersion(owner, id, versionId, number, checked, links, Kind.EXPRESSION, now);
        if (before != null) {
            changed(mapper.saveContent(owner, id, before.revision(), versionId, number, checked.title(), now));
        }
        expressionBasis.capture(owner, id, versionId, now);
        if (sourceBefore != null) knowledgePropagation.changed(owner, sourceBefore, ChangeKind.CONTENT_SAVED, now);
        return current(owner, requireItem(owner, id));
    }

    public Item status(String owner, String id, String key, ChangeStatus request, Status status) {
        uuid(id);
        required(request);
        revision(request.expectedRevision());
        String action = status == Status.ARCHIVED ? "archive" : "restore";
        return commands.execute(owner, key, "POST /api/v1/prepare/items/" + id + "/" + action,
                request, Item.class, () -> {
                    ItemRow before = changeable(owner, id, request.expectedRevision());
                    var sourceBefore = knowledgePropagation.observe(owner, id);
                    if (status == Status.ARCHIVED && before.kind().equals("TARGET")) {
                        Focus focus = currentFocus(owner);
                        if (id.equals(focus.targetId())) {
                            if (focus.revision() == MAX_REVISION) throw revisionLimit();
                            // 归档目标和移出当前焦点必须一起提交，避免悬挂的默认训练上下文。
                            changed(mapper.setFocus(owner, focus.revision(), null));
                        }
                    }
                    Instant now = now();
                    changed(mapper.changeStatus(owner, id, before.revision(), status.name(), now));
                    knowledgePropagation.changed(owner, sourceBefore, ChangeKind.STATUS_SET, now);
                    return current(owner, requireItem(owner, id));
                });
    }

    public Item decide(String owner, String id, String key, DecideFact request, Verification decision) {
        uuid(id);
        required(request);
        revision(request.expectedRevision());
        String reason = text(request.reason(), 2000, decision != Verification.WITHDRAWN, "核实说明");
        String action = decision == Verification.CONFIRMED ? "confirm" : "withdraw";
        return commands.execute(owner, key, "POST /api/v1/prepare/items/" + id + "/" + action,
                request, Item.class, () -> {
                    ItemRow before = changeable(owner, id, request.expectedRevision());
                    if (!before.kind().equals("FACT")) throw invalid("只有事实可确认或撤回。");
                    if (decision == Verification.CONFIRMED) {
                        active(before);
                        active(requireItem(owner, before.projectId()));
                        FactContent fact = json.readValue(requireVersion(owner, before).contentJson(), FactContent.class);
                        if (fact.responsibility() == Responsibility.UNVERIFIED) {
                            throw new ApiException(409, "FACT_RESPONSIBILITY_UNVERIFIED", "请先明确本人负责、参与或仅了解的职责边界，再确认事实。");
                        }
                    }
                    var sourceBefore = knowledgePropagation.observe(owner, id);
                    Instant now = now();
                    // 决定固定确切版本；确认与撤回都不会修改历史正文，也不会产生模型授权。
                    mapper.insertDecision(owner, newId(), id, before.currentVersionId(), decision.name(), reason, now);
                    changed(mapper.setVerification(owner, id, before.revision(), decision.name(), now));
                    knowledgePropagation.changed(owner, sourceBefore, ChangeKind.FACT_DECIDED, now);
                    return current(owner, requireItem(owner, id));
                });
    }

    public Item favorite(String owner, String id, String key, SetFavorite request) {
        uuid(id);
        required(request);
        revision(request.expectedRevision());
        required(request.favorite());
        return commands.execute(owner, key, "PUT /api/v1/prepare/items/" + id + "/favorite", request, Item.class, () -> {
            ItemRow before = changeable(owner, id, request.expectedRevision());
            changed(mapper.setFavorite(owner, id, before.revision(), request.favorite(), now()));
            return current(owner, requireItem(owner, id));
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public VersionPage versions(String owner, String id, Integer before, int limit) {
        requireItem(owner, uuid(id));
        limit(limit);
        if (before != null && before < 1) throw invalid("历史游标必须是正版本号。");
        var rows = mapper.versions(owner, id, before, limit + 1);
        boolean more = rows.size() > limit;
        var page = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
        return new VersionPage(page, more ? page.getLast().number() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Version version(String owner, String id, String versionId) {
        requireItem(owner, uuid(id));
        VersionRow version = mapper.version(owner, id, uuid(versionId));
        if (version == null) throw notFound();
        return toVersion(version);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DecisionPage decisions(String owner, String id, String before, int limit) {
        ItemRow item = requireItem(owner, uuid(id));
        if (!item.kind().equals("FACT")) throw invalid("核实历史只适用于事实。");
        limit(limit);
        Instant beforeTime = null;
        String beforeId = null;
        if (before != null) {
            String[] parts = before.split("~", -1);
            if (before.length() > 100 || parts.length != 2) throw invalid("核实历史游标无效。");
            beforeId = uuid(parts[1]);
            try {
                beforeTime = Instant.parse(parts[0]);
                if (!beforeTime.toString().equals(parts[0]) || beforeTime.getNano() % 1000 != 0
                        || beforeTime.isBefore(Instant.parse("1000-01-01T00:00:00Z"))
                        || beforeTime.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"))) {
                    throw invalid("核实历史游标无效。");
                }
            } catch (DateTimeParseException exception) { throw invalid("核实历史游标无效。"); }
        }
        // 同一微秒也可能存在多个决定；时间和 ID 必须一起排序、一起作为游标。
        var rows = mapper.decisions(owner, id, beforeTime, beforeId, limit + 1);
        boolean more = rows.size() > limit;
        var page = rows.subList(0, Math.min(rows.size(), limit));
        return new DecisionPage(page.stream().map(row -> new Decision(row.id(), row.versionId(),
                Verification.valueOf(row.decision()), row.reason(), row.createdAt())).toList(),
                more ? page.getLast().createdAt() + "~" + page.getLast().id() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ImpactPage impacts(String owner, String id, String cursor, int limit) {
        requireItem(owner, uuid(id));
        limit(limit);
        if (cursor != null) uuid(cursor);
        var rows = mapper.impacts(owner, id, cursor, limit + 1);
        boolean more = rows.size() > limit;
        var page = rows.subList(0, Math.min(rows.size(), limit));
        return new ImpactPage(page.stream().map(row -> new Impact(row.id(), Kind.valueOf(row.kind()), row.title(),
                row.currentVersionId(), row.kind().equals("EXPRESSION") && mapper.hasInvalidFactBasis(owner, row.currentVersionId()))).toList(),
                more ? page.getLast().id() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Focus focus(String owner) { return currentFocus(owner); }

    public Focus focus(String owner, String key, SetFocus request) {
        required(request);
        revision(request.expectedRevision());
        if (request.targetId() != null) uuid(request.targetId());
        return commands.execute(owner, key, "PUT /api/v1/prepare/focus", request, Focus.class, () -> {
            Focus before = currentFocus(owner);
            expect(request.expectedRevision(), before.revision());
            if (before.revision() == MAX_REVISION) throw revisionLimit();
            if (request.targetId() != null) {
                ItemRow target = requireItem(owner, request.targetId());
                if (!target.kind().equals("TARGET")) throw invalid("当前关注目标必须指向岗位目标。");
                active(target);
            }
            mapper.ensureFocus(owner);
            changed(mapper.setFocus(owner, before.revision(), request.targetId()));
            return currentFocus(owner);
        });
    }

    private Item current(String owner, ItemRow row) {
        var references = mapper.references(owner, row.currentVersionId()).stream().map(link ->
                reference(owner, new VersionLink(LinkKind.valueOf(link.sourceKind()), link.sourceItemId(), link.sourceVersionId()))).toList();
        boolean review = row.kind().equals("EXPRESSION") && mapper.hasInvalidFactBasis(owner, row.currentVersionId());
        // 岗位中的既有组织关联可保留供对照，但正文未改版不代表其采用来源仍然有效。
        review |= references.stream().anyMatch(link -> link.kind() == LinkKind.KNOWLEDGE
                && knowledgeRevalidation.pending(owner, link.itemId(), link.versionId()));
        review |= !sourceEligibility.locallyUsable(owner, Kind.valueOf(row.kind()), row.id(), row.currentVersionId());
        return new Item(row.id(), Kind.valueOf(row.kind()), row.projectId(), Status.valueOf(row.status()), row.revision(),
                row.verification() == null ? null : Verification.valueOf(row.verification()), row.favorite(), row.createdAt(),
                row.updatedAt(), toVersion(requireVersion(owner, row)), references, review);
    }

    private Summary summary(ItemRow row) {
        return new Summary(row.id(), Kind.valueOf(row.kind()), row.projectId(), row.title(), Status.valueOf(row.status()),
                row.revision(), row.verification() == null ? null : Verification.valueOf(row.verification()), row.favorite(),
                row.currentVersionId(), row.versionNumber(), row.updatedAt());
    }

    private Version toVersion(VersionRow row) {
        return new Version(row.id(), row.versionNumber(), row.createdAt(), json.readTree(row.contentJson()));
    }

    private Focus currentFocus(String owner) {
        Focus focus = mapper.focus(owner);
        return focus == null ? new Focus(null, 0) : focus;
    }

    private void insertVersion(String owner, String id, String versionId, int number, Checked content,
                               List<VersionLink> links, Kind kind, Instant now) {
        mapper.insertVersion(new VersionRow(versionId, id, owner, number, content.title(), json.writeValueAsString(content.value()), now));
        Source source = switch (kind) {
            case PROJECT -> ((ProjectContent) content.value()).source();
            case FACT -> ((FactContent) content.value()).source();
            default -> null;
        };
        // 来源摘录绑定本次不可变版本；验证失败时正文、关系与命令回执一起回滚。
        if (source != null) materials.bindPreparationSources(owner, id, versionId, source.excerptIds());
        for (VersionLink link : links) {
            mapper.insertReference(owner, id, versionId, link.kind().name(), link.itemId(), link.versionId(),
                    kind == Kind.EXPRESSION ? "FACT_BASIS" : "ORGANIZATION");
        }
    }

    private List<VersionLink> links(String owner, Kind kind, Object content) {
        if (kind == Kind.TARGET) return ((TargetContent) content).links();
        if (kind != Kind.EXPRESSION) return List.of();
        var links = new ArrayList<VersionLink>();
        for (String versionId : ((ExpressionContent) content).factVersionIds()) {
            VersionRow source = mapper.versionById(owner, versionId);
            if (source == null) throw sourceUnavailable();
            links.add(new VersionLink(LinkKind.FACT, source.itemId(), versionId));
        }
        return List.copyOf(links);
    }

    private void validateLinks(String owner, List<VersionLink> links, Set<String> preserved) {
        for (VersionLink link : links) {
            Reference source = reference(owner, link);
            String key = link.kind() + ":" + link.itemId() + ":" + link.versionId();
            if (link.kind() == LinkKind.KNOWLEDGE && !preserved.contains(key)) {
                knowledgeRevalidation.requireClear(owner, link.itemId(), link.versionId());
            }
            // 目标保留既有旧版本属于组织关系；新增内容依据绝不能继承旧版本的有效身份。
            if (source.state() != ReferenceState.CURRENT && !preserved.contains(key)) {
                if (source.state() == ReferenceState.WITHDRAWN || source.state() == ReferenceState.UNVERIFIED) {
                    throw new ApiException(409, "FACT_NOT_CONFIRMED", "引用事实尚未确认或已经撤回，请重新选择依据。");
                }
                throw new ApiException(409, "SOURCE_CHANGED", "引用来源已改版或归档，请读取当前版本后重新选择。");
            }
        }
    }

    private Reference reference(String owner, VersionLink link) {
        if (link.kind() == LinkKind.COLLECTION) {
            // 题单仅用于目标整理，固定确切版本；不得按准备正文或知识来源展开题目与参考答案。
            Reference source = mapper.collectionReference(owner, link.itemId(), link.versionId());
            if (source == null) throw sourceUnavailable();
            return source;
        }
        if (link.kind() == LinkKind.KNOWLEDGE) {
            try {
                var source = knowledge.detail(owner, link.itemId());
                var version = knowledge.version(owner, link.itemId(), link.versionId());
                ReferenceState state = source.status() == KnowledgeDtos.Status.ARCHIVED ? ReferenceState.ARCHIVED
                        : !source.currentVersionId().equals(version.id()) ? ReferenceState.SUPERSEDED : ReferenceState.CURRENT;
                return new Reference(link.kind(), link.itemId(), version.id(), version.title(), version.number(), state);
            } catch (ApiException exception) {
                if (exception.status() == 404) throw sourceUnavailable();
                throw exception;
            }
        }
        ItemRow source = mapper.item(owner, link.itemId());
        VersionRow version = mapper.version(owner, link.itemId(), link.versionId());
        if (source == null || version == null || !source.kind().equals(link.kind().name())) throw sourceUnavailable();
        ReferenceState state;
        if ("WITHDRAWN".equals(source.verification())) state = ReferenceState.WITHDRAWN;
        else if (!source.status().equals("ACTIVE") || (source.kind().equals("FACT") && !projectActive(owner, source.projectId()))) state = ReferenceState.ARCHIVED;
        else if ("UNVERIFIED".equals(source.verification())) state = ReferenceState.UNVERIFIED;
        else if (!source.currentVersionId().equals(version.id())) state = ReferenceState.SUPERSEDED;
        else state = ReferenceState.CURRENT;
        return new Reference(link.kind(), source.id(), version.id(), version.title(), version.versionNumber(), state);
    }

    private boolean projectActive(String owner, String id) {
        ItemRow row = mapper.item(owner, id);
        return row != null && row.kind().equals("PROJECT") && row.status().equals("ACTIVE");
    }

    private Checked checkContent(Kind kind, JsonNode node) {
        Checked checked = checkContentFields(kind, node);
        checkNewText(node);
        if (checked.value() instanceof TargetContent target && target.capturedOn() != null
                && (target.capturedOn().getYear() < 1000 || target.capturedOn().getYear() > 9999)) {
            throw invalid("新提交的采集日期须在 1000 至 9999 年范围内。");
        }
        return checked;
    }

    private static void checkNewText(JsonNode node) {
        if (node.isTextual()) {
            String value = node.stringValue();
            if (value.indexOf('\0') >= 0 || value.codePoints().anyMatch(point -> point >= 0xD800 && point <= 0xDFFF)) {
                throw invalid("新提交的内容不能包含 NUL 或不完整的 Unicode 字符。");
            }
        } else if (node.isObject() || node.isArray()) {
            for (JsonNode child : node) checkNewText(child);
        }
    }

    private Checked checkContentFields(Kind kind, JsonNode node) {
        if (node == null || !node.isObject()) throw invalid("内容必须是对应类型的对象。");
        try {
            return switch (kind) {
                case TARGET -> {
                    var value = json.readValue(node.toString(), TargetContent.class);
                    String title = text(value.title(), 200, false, "标题").strip();
                    var requirements = bounded(value.requirements(), 0, 50, "要求");
                    var ids = new HashSet<String>();
                    var normalized = new ArrayList<Requirement>();
                    for (Requirement requirement : requirements) {
                        required(requirement);
                        if (!ids.add(uuid(requirement.id()))) throw invalid("要求标识不能重复。");
                        var dimensions = bounded(requirement.dimensions(), 1, 6, "考察项");
                        if (new HashSet<>(dimensions).size() != dimensions.size() || dimensions.stream().anyMatch(d -> !DIMENSIONS.contains(d))) throw invalid("考察项无效或重复。");
                        if (requirement.priority() == null || requirement.priority() < 1 || requirement.priority() > 5) throw invalid("要求优先级必须为 1 至 5。");
                        normalized.add(new Requirement(requirement.id(), text(requirement.text(), 2000, false, "要求文字"), List.copyOf(dimensions), requirement.priority()));
                    }
                    var links = bounded(value.links(), 0, 100, "目标引用");
                    var linkIds = new HashSet<String>();
                    for (VersionLink link : links) {
                        required(link);
                        required(link.kind());
                        uuid(link.itemId());
                        uuid(link.versionId());
                        if (!linkIds.add(link.kind() + ":" + link.itemId() + ":" + link.versionId())) throw invalid("目标引用不能重复。");
                    }
                    if (value.weeklyMinutes() != null && (value.weeklyMinutes() < 0 || value.weeklyMinutes() > 10080)) throw invalid("每周可用时间必须为 0 至 10080 分钟。");
                    yield new Checked(title, new TargetContent(title, text(value.company(), 200, true, "公司备注"),
                            text(value.jd(), 50000, true, "JD"), url(value.sourceUrl()), value.capturedOn(), value.weeklyMinutes(), List.copyOf(normalized), List.copyOf(links)));
                }
                case PROJECT -> {
                    var value = json.readValue(node.toString(), ProjectContent.class);
                    String title = text(value.title(), 200, false, "标题").strip();
                    yield new Checked(title, new ProjectContent(title, text(value.overview(), 20000, true, "项目概述"),
                            text(value.technology(), 2000, true, "技术版本"), source(value.source())));
                }
                case FACT -> {
                    var value = json.readValue(node.toString(), FactContent.class);
                    String title = text(value.title(), 200, false, "标题").strip();
                    required(value.responsibility());
                    yield new Checked(title, new FactContent(title, text(value.scenario(), 10000, true, "场景"),
                            text(value.problem(), 10000, true, "问题"), text(value.action(), 10000, true, "动作"),
                            text(value.result(), 10000, true, "结果"), text(value.technology(), 2000, true, "技术版本"),
                            value.responsibility(), source(value.source()), tags(value.tags())));
                }
                case EXPRESSION -> {
                    var value = json.readValue(node.toString(), ExpressionContent.class);
                    String title = text(value.title(), 200, false, "标题").strip();
                    required(value.type());
                    var facts = bounded(value.factVersionIds(), 1, 50, "事实依据");
                    for (String id : facts) uuid(id);
                    if (new HashSet<>(facts).size() != facts.size()) throw invalid("事实依据不能重复。");
                    yield new Checked(title, new ExpressionContent(title, value.type(), text(value.body(), 50000, true, "表达正文"), List.copyOf(facts), tags(value.tags())));
                }
            };
        } catch (ApiException exception) {
            throw exception;
        } catch (tools.jackson.core.JacksonException exception) {
            throw invalid("内容字段或类型不正确，请检查必填项和未知字段。");
        }
    }

    private static Source source(Source value) {
        required(value);
        var excerpts = value.excerptIds() == null ? List.<String>of() : bounded(value.excerptIds(), 0, 20, "来源摘录");
        for (String id : excerpts) uuid(id);
        if (new HashSet<>(excerpts).size() != excerpts.size()) throw invalid("来源摘录不能重复。");
        return new Source(text(value.title(), 200, true, "来源标题"), url(value.url()),
                text(value.note(), 5000, true, "来源说明"), List.copyOf(excerpts));
    }

    private static List<String> tags(List<String> values) {
        var result = bounded(values, 0, 20, "标签").stream().map(value -> text(value.strip(), 40, false, "标签")).toList();
        if (new HashSet<>(result).size() != result.size()) throw invalid("标签不能重复。");
        return result;
    }

    private static String url(String value) {
        String text = text(value, 2000, true, "来源网址").strip();
        if (text.isEmpty()) return text;
        try {
            URI uri = URI.create(text);
            if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) throw invalid("来源网址仅允许不含登录信息的 HTTP(S) 地址。");
            return text;
        } catch (IllegalArgumentException exception) { throw invalid("来源网址格式不正确。"); }
    }

    private static String text(String value, int max, boolean empty, String field) {
        if (value == null || value.length() > max || (!empty && value.isBlank())) throw invalid(field + "为空或超过长度上限。");
        return value;
    }

    private static String expressionText(String value, int max, String field) {
        String normalized = text(required(value).strip(), max, false, field);
        for (int i = 0; i < normalized.length(); i++) {
            char character = normalized.charAt(i);
            if (character == '\0') throw invalid(field + "不能包含 NUL 字符。");
            if (Character.isHighSurrogate(character)) {
                if (i + 1 >= normalized.length() || !Character.isLowSurrogate(normalized.charAt(i + 1))) {
                    throw invalid(field + "包含不完整的 Unicode 字符。");
                }
                i++;
            } else if (Character.isLowSurrogate(character)) {
                throw invalid(field + "包含不完整的 Unicode 字符。");
            }
        }
        return normalized;
    }

    private static void requirementText(String value) {
        if (value == null || value.length() > 2000
                || value.codePoints().allMatch(point -> Character.isWhitespace(point) || Character.isSpaceChar(point))
                || value.indexOf('\0') >= 0 || value.codePoints().anyMatch(point -> point >= 0xD800 && point <= 0xDFFF)) {
            throw invalid("要求文字须为 1 至 2000 个 UTF-16 码元的非空白文字，且不能包含 NUL 或不完整字符。");
        }
    }

    private static <T> List<T> bounded(List<T> values, int min, int max, String field) {
        if (values == null || values.size() < min || values.size() > max || values.stream().anyMatch(value -> value == null)) throw invalid(field + "数量无效。");
        return values;
    }

    private ItemRow requireItem(String owner, String id) {
        ItemRow row = mapper.item(owner, id);
        if (row == null) throw notFound();
        return row;
    }

    private VersionRow requireVersion(String owner, ItemRow item) {
        VersionRow row = mapper.version(owner, item.id(), item.currentVersionId());
        if (row == null) throw new IllegalStateException("准备内容当前版本不完整");
        return row;
    }

    private ItemRow changeable(String owner, String id, long expected) {
        ItemRow item = requireItem(owner, id);
        expect(expected, item.revision());
        if (item.revision() == MAX_REVISION) throw revisionLimit();
        return item;
    }

    private static void active(ItemRow row) {
        if (!row.status().equals("ACTIVE")) throw new ApiException(409, "ITEM_ARCHIVED", "内容已归档，请先恢复后再使用。");
    }

    private static void expect(long expected, long actual) {
        if (expected != actual) throw new ApiException(409, "REVISION_CONFLICT", "准备内容已被其他操作修改，请保留输入后重新读取。", actual);
    }

    private static void changed(int count) {
        if (count != 1) throw new ApiException(409, "REVISION_CONFLICT", "准备内容已变化，本次修改未提交。");
    }

    private static void revision(Long value) {
        if (value == null || value < 0 || value > MAX_REVISION) throw invalid("预期修订号无效。");
    }

    private static void limit(int value) { if (value < 1 || value > 50) throw invalid("每页数量必须为 1 至 50。"); }
    private static <T> T required(T value) { if (value == null) throw invalid("请求内容或必填字段不能为空。"); return value; }
    private static String uuid(String value) {
        try { if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException exception) { throw invalid("对象标识必须为标准小写 UUID。"); }
        return value;
    }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static String newId() { return UUID.randomUUID().toString(); }
    private static ApiException invalid(String detail) { return new ApiException(400, "INVALID_REQUEST", detail); }
    private static ApiException notFound() { return new ApiException(404, "PREPARATION_NOT_FOUND", "准备内容不存在或不可访问。"); }
    private static ApiException sourceUnavailable() { return new ApiException(404, "SOURCE_UNAVAILABLE", "引用来源不存在或不可访问。"); }
    private static ApiException revisionLimit() { return new ApiException(409, "REVISION_LIMIT", "准备内容修订号已达到上限。"); }
    private record Checked(String title, Object value) {}
}
