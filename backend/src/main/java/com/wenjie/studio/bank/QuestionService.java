package com.wenjie.studio.bank;

import com.wenjie.studio.bank.QuestionDtos.*;
import com.wenjie.studio.bank.QuestionRows.*;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import com.wenjie.studio.knowledge.KnowledgeDtos;
import com.wenjie.studio.knowledge.KnowledgeService;
import com.wenjie.studio.lifecycle.KnowledgeRevalidationQuery;
import com.wenjie.studio.review.ReviewedBasisInvalidation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.wenjie.studio.bank.QuestionGuards.*;

@Service
public class QuestionService {
    private static final StandardStatus STANDARD = StandardStatus.PERSONAL_UNREVIEWED;
    private final QuestionMapper mapper;
    private final IdempotencyService commands;
    private final KnowledgeService knowledge;
    private final KnowledgeRevalidationQuery revalidation;
    private final ReviewedBasisInvalidation reviewedBasis;
    private final JsonMapper json;

    public QuestionService(QuestionMapper mapper, IdempotencyService commands, KnowledgeService knowledge,
                           ReviewedBasisInvalidation reviewedBasis, KnowledgeRevalidationQuery revalidation, JsonMapper json) {
        this.mapper = mapper;
        this.commands = commands;
        this.knowledge = knowledge;
        this.revalidation = revalidation;
        this.reviewedBasis = reviewedBasis;
        this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public QuestionPage list(String owner, Status status, String query, Type type, Integer difficulty,
                             Dimension dimension, String topicId, String knowledgeId, String scene,
                             Boolean favorite, String cursor, int limit) {
        uuid(owner); required(status); pageLimit(limit); difficulty(difficulty);
        if (cursor != null) uuid(cursor);
        if (topicId != null) uuid(topicId);
        if (knowledgeId != null) uuid(knowledgeId);
        List<SummaryRow> rows = mapper.questions(owner, status.name(), pattern(query), type == null ? null : type.name(),
                difficulty, dimension == null ? null : dimension.name(), topicId, knowledgeId,
                pattern(scene), favorite, cursor, limit + 1);
        boolean more = rows.size() > limit;
        List<QuestionSummary> page = rows.stream().limit(limit).map(row -> new QuestionSummary(row.id(), row.title(),
                Type.valueOf(row.type()), row.difficulty(), Status.valueOf(row.status()), row.revision(), row.favorite(),
                row.versionId(), row.versionNumber(), StandardStatus.valueOf(row.standardStatus()),
                mapper.topics(owner, row.versionId()), row.updatedAt())).toList();
        return new QuestionPage(page, more ? page.getLast().id() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Question detail(String owner, String id) {
        QuestionRow row = requireQuestion(owner, id);
        QuestionVersion version = readVersion(owner, id, row.currentVersionId());
        List<KnowledgeReference> references = knowledgeReferences(owner, version);
        return new Question(row.id(), Status.valueOf(row.status()), row.revision(), row.favorite(),
                row.createdAt(), row.updatedAt(), version, references);
    }

    public Question create(String owner, String key, QuestionInput request) {
        required(request);
        return commands.execute(owner, key, "POST /api/v1/bank/questions", request, Question.class,
                () -> createWithinCommand(owner, request));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Question createWithinCommand(String owner, QuestionInput request) {
        // 题目、首版与曝光必须加入已持有 owner 锁的外层命令，跨模块采用失败时一起回滚。
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("题目创建必须处于命令事务中");
        }
        uuid(owner); required(request);
        QuestionInput content = checked(owner, request, Set.of());
        String id = UUID.randomUUID().toString();
        String versionId = UUID.randomUUID().toString();
        Instant now = now();
        mapper.insertQuestion(new QuestionRow(id, owner, Status.ACTIVE.name(), 0, false, versionId, 1, now, now));
        insertVersion(owner, id, versionId, 1, content, now);
        authored(owner, id, versionId, now);
        return detail(owner, id);
    }

    public Question save(String owner, String id, String key, SaveQuestion request) {
        uuid(id); required(request); revision(request.expectedRevision()); required(request.content());
        return commands.execute(owner, key, "PUT /api/v1/bank/questions/" + id, request, Question.class, () -> {
            QuestionRow row = requireQuestion(owner, id);
            expected(row, request.expectedRevision());
            if (!Status.ACTIVE.name().equals(row.status())) {
                throw new ApiException(409, "ITEM_ARCHIVED", "请先恢复题目，再修改题干与参考。");
            }
            if (row.versionNumber() == Integer.MAX_VALUE) {
                throw new ApiException(409, "REVISION_LIMIT", "题目版本已到上限，不能继续生成版本。");
            }
            // 允许保留的旧引用只取服务端上一版；客户端不能借此新增任意历史或停用来源。
            Set<KnowledgeLink> previousLinks = new HashSet<>(mapper.knowledgeLinks(owner, row.currentVersionId()));
            QuestionInput content = checked(owner, request.content(), previousLinks);
            String versionId = UUID.randomUUID().toString();
            int number = row.versionNumber() + 1;
            Instant now = now();
            insertVersion(owner, id, versionId, number, content, now);
            changed(mapper.saveContent(owner, id, row.revision(), versionId, number, now));
            reviewedBasis.questionVersionChanged(owner, id, row.currentVersionId(), versionId, now);
            authored(owner, id, versionId, now);
            return detail(owner, id);
        });
    }

    public Question status(String owner, String id, String key, ChangeStatus request, Status status) {
        uuid(id); required(request); revision(request.expectedRevision()); required(status);
        String operation = "POST /api/v1/bank/questions/" + id + (status == Status.ARCHIVED ? "/archive" : "/restore");
        return commands.execute(owner, key, operation, request, Question.class, () -> {
            QuestionRow row = requireQuestion(owner, id);
            expected(row, request.expectedRevision());
            Instant now = now();
            changed(mapper.changeStatus(owner, id, row.revision(), status.name(), now));
            reviewedBasis.questionStatusChanged(owner, id, row.currentVersionId(), Status.valueOf(row.status()), status, now);
            return detail(owner, id);
        });
    }

    public Question favorite(String owner, String id, String key, SetFavorite request) {
        uuid(id); required(request); revision(request.expectedRevision()); required(request.favorite());
        return commands.execute(owner, key, "PUT /api/v1/bank/questions/" + id + "/favorite", request, Question.class, () -> {
            QuestionRow row = requireQuestion(owner, id);
            expected(row, request.expectedRevision());
            changed(mapper.setFavorite(owner, id, row.revision(), request.favorite(), now()));
            return detail(owner, id);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public VersionPage versions(String owner, String id, Integer before, int limit) {
        requireQuestion(owner, id); pageLimit(limit);
        if (before != null && before <= 0) throw invalid("历史版本游标必须为正整数。");
        List<VersionSummary> rows = mapper.versions(owner, id, before, limit + 1);
        boolean more = rows.size() > limit;
        List<VersionSummary> page = rows.stream().limit(limit).toList();
        return new VersionPage(page, more ? page.getLast().number() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public QuestionVersion version(String owner, String id, String versionId) {
        requireQuestion(owner, id); uuid(versionId);
        return readVersion(owner, id, versionId);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public KnowledgeReferencePage knowledgeReferences(String owner, String id, String versionId) {
        requireQuestion(owner, id); uuid(versionId);
        QuestionVersion version = readVersion(owner, id, versionId);
        return new KnowledgeReferencePage(id, versionId, knowledgeReferences(owner, version));
    }

    public Reference reference(String owner, String id, String versionId, String key, EmptyRequest request) {
        uuid(id); uuid(versionId); required(request);
        String operation = "POST /api/v1/bank/questions/" + id + "/versions/" + versionId + "/reference";
        return commands.execute(owner, key, operation, request, Reference.class, () -> {
            requireQuestion(owner, id);
            ReferenceRow row = mapper.reference(owner, id, versionId);
            if (row == null) throw missing();
            String exposureId = UUID.randomUUID().toString();
            Instant recordedAt = now();
            // 返回参考之前记录确切版本曝光，与回执原子提交；重放回执不会产生第二条曝光。
            mapper.insertExposure(owner, exposureId, id, versionId, ExposureKind.REFERENCE_VIEW.name(), recordedAt);
            List<Checkpoint> checkpoints = List.copyOf(Arrays.asList(json.readValue(row.checkpointsJson(), Checkpoint[].class)));
            return new Reference(id, versionId, STANDARD, row.referenceAnswer(), checkpoints, exposureId, recordedAt);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ExposurePage exposures(String owner, String id, String before, int limit) {
        requireQuestion(owner, id); pageLimit(limit);
        Instant beforeTime = null;
        String beforeId = null;
        if (before != null) {
            String[] parts = before.split("~", -1);
            if (parts.length != 2) throw invalid("曝光历史游标无效。");
            beforeId = uuid(parts[1]);
            try {
                beforeTime = Instant.parse(parts[0]);
                int year = beforeTime.atOffset(ZoneOffset.UTC).getYear();
                if (year < 1000 || year > 9999 || !beforeTime.equals(beforeTime.truncatedTo(ChronoUnit.MICROS))) {
                    throw invalid("曝光历史时间游标无效。");
                }
            } catch (java.time.DateTimeException exception) { throw invalid("曝光历史时间游标无效。"); }
        }
        List<Exposure> rows = mapper.exposures(owner, id, beforeTime, beforeId, limit + 1);
        boolean more = rows.size() > limit;
        List<Exposure> page = rows.stream().limit(limit).toList();
        String next = more ? page.getLast().createdAt() + "~" + page.getLast().id() : null;
        return new ExposurePage(page, next);
    }

    private QuestionInput checked(String owner, QuestionInput input, Set<KnowledgeLink> previousLinks) {
        String title = text(input.title(), 200, true);
        String prompt = text(input.prompt(), 10000, true);
        String conditions = text(input.conditions(), 5000, false);
        String technology = text(input.technology(), 2000, false);
        String scenario = text(input.scenario(), 2000, false);
        Type type = required(input.type());
        difficulty(input.difficulty());
        List<Dimension> dimensions = QuestionGuards.list(input.dimensions(), 6);
        if (dimensions.isEmpty()) throw invalid("请至少指定一个考察维度。");
        distinct(dimensions);
        List<String> topics = QuestionGuards.list(input.topicIds(), 20);
        topics.forEach(QuestionGuards::uuid);
        distinct(topics);
        if (!topics.isEmpty()) {
            Set<String> available = knowledge.topics(owner).items().stream().map(KnowledgeDtos.Topic::id).collect(Collectors.toSet());
            if (!available.containsAll(topics)) throw new ApiException(422, "SOURCE_UNAVAILABLE", "所选知识主题不存在或不可访问。");
        }
        List<KnowledgeLink> links = QuestionGuards.list(input.knowledgeLinks(), 20);
        distinct(links);
        for (KnowledgeLink link : links) {
            uuid(link.itemId()); uuid(link.versionId());
            KnowledgeReference reference = resolveKnowledge(owner, link, null);
            if (!previousLinks.contains(link) && reference.state() != ReferenceState.CURRENT) {
                throw new ApiException(409, "SOURCE_CHANGED", "新增知识引用须为当前有效且无待复核影响的版本，请核对来源后重新选择。");
            }
        }
        List<String> tags = QuestionGuards.list(input.tags(), 20);
        tags.forEach(tag -> text(tag, 40, true));
        distinct(tags);
        Source source = input.source() == null ? new Source("", "", "") : input.source();
        source = new Source(text(source.title(), 200, false), text(source.url(), 2000, false), text(source.note(), 5000, false));
        url(source.url());
        String referenceAnswer = text(input.referenceAnswer(), 20000, false);
        List<Checkpoint> checkpoints = QuestionGuards.list(input.checkpoints(), 30);
        Set<String> checkpointIds = new HashSet<>();
        for (Checkpoint checkpoint : checkpoints) {
            uuid(checkpoint.id()); text(checkpoint.text(), 500, true);
            if (!checkpointIds.add(checkpoint.id())) throw invalid("检查点标识不能重复。");
        }
        return new QuestionInput(title, prompt, conditions, technology, scenario, type, input.difficulty(),
                dimensions, topics, links, tags, source, referenceAnswer, checkpoints);
    }

    private List<KnowledgeReference> knowledgeReferences(String owner, QuestionVersion version) {
        List<KnowledgeLink> links = mapper.knowledgeLinks(owner, version.id());
        if (version.createdAt() == null || version.content() == null || links.size() > 20
                || new HashSet<>(links).size() != links.size() || !links.equals(version.content().knowledgeLinks())) {
            throw new IllegalStateException("题目固定知识引用与版本题面不一致");
        }
        return links.stream().map(link -> resolveKnowledge(owner, link, version.createdAt())).toList();
    }

    private KnowledgeReference resolveKnowledge(String owner, KnowledgeLink link, Instant frozenAt) {
        try {
            KnowledgeDtos.ItemDetail item = knowledge.detail(owner, link.itemId());
            KnowledgeDtos.Version version = knowledge.version(owner, link.itemId(), link.versionId());
            boolean reviewRequired = frozenAt == null ? revalidation.pending(owner, link.itemId(), link.versionId())
                    : revalidation.reviewRequired(owner, link.itemId(), link.versionId(), frozenAt);
            ReferenceState state = item.status() == KnowledgeDtos.Status.ARCHIVED ? ReferenceState.ARCHIVED
                    : reviewRequired ? ReferenceState.REVIEW_REQUIRED
                    : version.id().equals(item.currentVersionId()) ? ReferenceState.CURRENT : ReferenceState.SUPERSEDED;
            return new KnowledgeReference(item.id(), version.id(), version.title(), version.number(), state);
        } catch (ApiException exception) {
            if (exception.status() == 404) throw new ApiException(422, "SOURCE_UNAVAILABLE", "知识引用不存在或不可访问。");
            throw exception;
        }
    }

    private void insertVersion(String owner, String id, String versionId, int number, QuestionInput content, Instant now) {
        QuestionPrompt prompt = new QuestionPrompt(content.title(), content.prompt(), content.conditions(), content.technology(),
                content.scenario(), content.type(), content.difficulty(), content.dimensions(), content.topicIds(),
                content.knowledgeLinks(), content.tags(), content.source());
        boolean hasReference = !content.referenceAnswer().isEmpty() || !content.checkpoints().isEmpty();
        mapper.insertVersion(new VersionRow(versionId, id, owner, number, content.title(), json.writeValueAsString(prompt),
                STANDARD.name(), hasReference, now), content.type().name(), content.difficulty(), content.scenario(),
                content.referenceAnswer(), json.writeValueAsString(content.checkpoints()));
        for (int i = 0; i < content.topicIds().size(); i++) mapper.insertTopic(owner, id, versionId, content.topicIds().get(i), i);
        for (Dimension dimension : content.dimensions()) mapper.insertDimension(owner, id, versionId, dimension.name());
        for (int i = 0; i < content.knowledgeLinks().size(); i++) {
            KnowledgeLink link = content.knowledgeLinks().get(i);
            mapper.insertKnowledgeLink(owner, id, versionId, link.itemId(), link.versionId(), i);
        }
    }

    private void authored(String owner, String id, String versionId, Instant now) {
        // 自建和编辑只记为 AUTHORED，不冒充系统观察到的参考阅读。
        mapper.insertExposure(owner, UUID.randomUUID().toString(), id, versionId, ExposureKind.AUTHORED.name(), now);
    }

    private QuestionRow requireQuestion(String owner, String id) {
        uuid(owner); uuid(id);
        QuestionRow row = mapper.question(owner, id);
        if (row == null) throw missing();
        return row;
    }

    private QuestionVersion readVersion(String owner, String id, String versionId) {
        VersionRow row = mapper.version(owner, id, versionId);
        if (row == null) throw missing();
        return new QuestionVersion(row.id(), row.versionNumber(), row.createdAt(),
                json.readValue(row.promptJson(), QuestionPrompt.class), StandardStatus.valueOf(row.standardStatus()), row.hasReference());
    }

    private void expected(QuestionRow row, long expected) {
        if (row.revision() != expected) {
            throw new ApiException(409, "REVISION_CONFLICT", "题目已发生变化，请保留当前输入并重新读取。", row.revision());
        }
        if (row.revision() == MAX_REVISION) throw new ApiException(409, "REVISION_LIMIT", "题目修订号已到上限。");
    }

    private void changed(int count) {
        if (count != 1) throw new ApiException(409, "REVISION_CONFLICT", "题目已发生变化，请保留当前输入并重新读取。");
    }

    private ApiException missing() { return new ApiException(404, "QUESTION_NOT_FOUND", "题目或题目版本不存在或不可访问。"); }
}
