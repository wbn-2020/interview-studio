package com.wenjie.studio.ai;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.knowledge.KnowledgeMapper;
import com.wenjie.studio.knowledge.RelationAiFeedbackEvidenceDtos;
import com.wenjie.studio.lifecycle.KnowledgeMaterialLineage;
import com.wenjie.studio.lifecycle.KnowledgeRevalidationQuery;
import com.wenjie.studio.material.MaterialDtos.AiExcerpt;
import com.wenjie.studio.material.MaterialLifecycleDtos;
import com.wenjie.studio.material.MaterialLifecycleService;
import com.wenjie.studio.material.MaterialSources;
import com.wenjie.studio.memory.MemoryDtos;
import com.wenjie.studio.prepare.PreparationDtos;
import com.wenjie.studio.prepare.PreparationSources;
import com.wenjie.studio.review.ReviewDraftSources;
import com.wenjie.studio.training.TrainingDtos;
import org.springframework.stereotype.Service;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static com.wenjie.studio.ai.AiDtos.*;

@Service
public class AiSources {
    public static final String PROMPT_VERSION = "knowledge-draft-1";
    private static final String TRAINING_DRAFT_PROMPT_VERSION = "training-knowledge-draft-1";
    private static final String REVIEW_DRAFT_PROMPT_VERSION = "review-knowledge-draft-1";
    private static final String CARD_DRAFT_PROMPT_VERSION = "memory-card-draft-1";
    private static final String JD_REQUIREMENTS_PROMPT_VERSION = "jd-requirements-1";
    private static final String FACT_DRAFT_PROMPT_VERSION = "fact-draft-1";
    private static final String REWRITE_PROMPT_VERSION = "answer-rewrite-1";
    private static final String RELATION_EVIDENCE_PROMPT_VERSION = "relation-evidence-draft-1";
    private static final String RELATION_REVIEW_PROMPT_VERSION = "relation-review-draft-1";
    private static final String RELATION_AI_FEEDBACK_PROMPT_VERSION = "relation-ai-feedback-draft-1";
    private final KnowledgeMapper knowledge;
    private final KnowledgeRevalidationQuery revalidation;
    private final AiProperties properties;
    private final TrainingFollowUps training;
    private final TrainingEvaluations evaluations;
    private final RewriteSources rewrites;
    private final TrainingDraftSources trainingDrafts;
    private final ReviewDraftSources reviewDrafts;
    private final FactDraftSources factDrafts;
    private final RelationEvidenceSources relationEvidence;
    private final RelationAiFeedbackDraftSources relationAiFeedbackDrafts;
    private final SourceQaSources sourceQa;
    private final ComparisonSources comparisons;
    private final MaterialSources materials;
    private final MaterialLifecycleService materialLifecycle;
    private final KnowledgeMaterialLineage knowledgeMaterialLineage;
    private final AiManifestDigest manifestDigest;
    private final PreparationSources preparation;
    private final JsonMapper canonical;

    public AiSources(KnowledgeMapper knowledge, AiProperties properties, JsonMapper json, TrainingFollowUps training,
                     TrainingEvaluations evaluations, MaterialSources materials, PreparationSources preparation,
                     TrainingDraftSources trainingDrafts, ComparisonSources comparisons, ReviewDraftSources reviewDrafts,
                     FactDraftSources factDrafts, RewriteSources rewrites, RelationEvidenceSources relationEvidence,
                     RelationAiFeedbackDraftSources relationAiFeedbackDrafts, SourceQaSources sourceQa,
                     KnowledgeRevalidationQuery revalidation, MaterialLifecycleService materialLifecycle,
                     KnowledgeMaterialLineage knowledgeMaterialLineage, AiManifestDigest manifestDigest) {
        this.knowledge = knowledge;
        this.revalidation = revalidation;
        this.properties = properties;
        this.training = training;
        this.evaluations = evaluations;
        this.rewrites = rewrites;
        this.trainingDrafts = trainingDrafts;
        this.reviewDrafts = reviewDrafts;
        this.factDrafts = factDrafts;
        this.relationEvidence = relationEvidence;
        this.relationAiFeedbackDrafts = relationAiFeedbackDrafts;
        this.sourceQa = sourceQa;
        this.comparisons = comparisons;
        this.materials = materials;
        this.materialLifecycle = materialLifecycle;
        this.knowledgeMaterialLineage = knowledgeMaterialLineage;
        this.manifestDigest = manifestDigest;
        this.preparation = preparation;
        canonical = json.rebuild().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    }

    public Manifest manifest(String owner, PreviewRequest request) {
        properties.requireReady();
        if (request == null || request.purpose() == null
                || !List.of(Purpose.KNOWLEDGE_DRAFT, Purpose.QUESTION_DRAFT).contains(request.purpose())) {
            throw new ApiException(400, "AI_PURPOSE_INVALID", "请选择当前支持的知识整理或出题用途。");
        }
        String instruction = request.instruction() == null ? "" : request.instruction().strip();
        if (instruction.length() > 1000) throw new ApiException(422, "AI_LIMIT", "任务要求最多 1000 个字符。");
        if (request.sources() == null || request.sources().isEmpty() || request.sources().size() > 5) {
            throw new ApiException(422, "AI_LIMIT", "每次任务请选择 1–5 个已发布知识对象。");
        }
        var sources = knowledgeSources(owner, request.sources(), new HashSet<>());
        int sourceBytes = canonical.writeValueAsBytes(new SourceContent(instruction, sources)).length;
        return withKnowledgeMaterialChecks(owner, manifest(request.purpose(), instruction, List.copyOf(sources),
                null, null, null, null, null, null, null, null, null, null, sourceBytes));
    }

    public Manifest relationDraftManifest(String owner, RelationDraftPreviewRequest request) {
        properties.requireReady();
        if (request == null || !validFixedInstruction(request.instruction())) {
            throw new ApiException(400, "INVALID_REQUEST", "请明确提供最多 1000 个 UTF-16 码元的关系整理要求，且不能包含 NUL 或不完整字符。");
        }
        if (request.sources() == null || request.sources().size() < 2 || request.sources().size() > 5) {
            throw new ApiException(422, "AI_LIMIT", "关系整理请选择 2–5 个当前已发布知识版本，其中至少两个为概念。");
        }
        String instruction = request.instruction().strip();
        var sources = List.copyOf(knowledgeSources(owner, request.sources(), new HashSet<>()));
        if (!validRelationSources(sources)) {
            throw new ApiException(422, "AI_SOURCE_UNAVAILABLE", "关系来源须包含至少两个概念，标题与正文须在知识存储范围内且不能含空白正文、NUL 或不完整字符。");
        }
        // 笔记只作显式依据；预算覆盖所选完整知识投影，不展开现有关系或其他关联内容。
        int sourceBytes = canonical.writeValueAsBytes(new RelationDraftContent(Purpose.RELATION_DRAFT, instruction, sources)).length;
        return withKnowledgeMaterialChecks(owner, manifest(Purpose.RELATION_DRAFT, instruction, sources, null, null, null, null,
                null, null, null, null, null, null, sourceBytes));
    }

    public Manifest relationEvidenceManifest(String owner, RelationEvidencePreviewRequest request) {
        properties.requireReady();
        if (request == null || !validFixedInstruction(request.instruction())) {
            throw new ApiException(400, "INVALID_REQUEST", "请明确提供最多 1000 个 UTF-16 码元的关系整理要求，且不能包含 NUL 或不完整字符。");
        }
        if (request.sources() == null || request.sources().size() < 2 || request.sources().size() > 5) {
            throw new ApiException(422, "AI_LIMIT", "关系整理请选择 2–5 个当前已发布知识版本，其中至少两个为概念。");
        }
        String instruction = request.instruction();
        var sources = List.copyOf(knowledgeSources(owner, request.sources(), new HashSet<>()));
        if (!validRelationSources(sources)) {
            throw new ApiException(422, "AI_SOURCE_UNAVAILABLE", "关系来源须包含至少两个概念，标题与正文须在知识存储范围内且不能含空白正文、NUL 或不完整字符。");
        }
        FactDraftDtos.Input evidence = relationEvidence.input(owner, request.source());
        // 知识端点与直接依据分别保留身份；预览、预算和网关使用同一完整外发投影。
        int sourceBytes = canonical.writeValueAsBytes(relationEvidenceContent(instruction, sources, evidence)).length;
        return withMaterialChecks(owner, manifest(Purpose.RELATION_DRAFT, instruction, sources, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, evidence, sourceBytes));
    }

    public Manifest relationReviewManifest(String owner, RelationReviewPreviewRequest request) {
        properties.requireReady();
        if (request == null || !validFixedInstruction(request.instruction())) {
            throw new ApiException(400, "INVALID_REQUEST", "请明确提供最多 1000 个 UTF-16 码元的关系整理要求，且不能包含 NUL 或不完整字符。");
        }
        if (request.sources() == null || request.sources().size() < 2 || request.sources().size() > 5) {
            throw new ApiException(422, "AI_LIMIT", "关系整理请选择 2–5 个当前已发布知识版本，其中至少两个为概念。");
        }
        var sources = List.copyOf(knowledgeSources(owner, request.sources(), new HashSet<>()));
        if (!validRelationSources(sources)) {
            throw new ApiException(422, "AI_SOURCE_UNAVAILABLE", "关系来源须包含至少两个概念，标题与正文须在知识存储范围内且不能含空白正文、NUL 或不完整字符。");
        }
        ReviewDraftDtos.Input review = reviewDrafts.input(owner, request.source());
        // 片段的修订、决定和限定同知识一起明示外发，不能把复盘依据改装为知识正文。
        int sourceBytes = canonical.writeValueAsBytes(relationReviewContent(request.instruction(), sources, review)).length;
        return withKnowledgeMaterialChecks(owner, manifest(Purpose.RELATION_DRAFT, request.instruction(), sources, null, null, null, null,
                null, null, null, null, null, null, null, null, review, sourceBytes));
    }

    public Manifest relationAiFeedbackManifest(String owner, RelationAiFeedbackPreviewRequest request) {
        properties.requireReady();
        if (request == null || !validFixedInstruction(request.instruction())) {
            throw new ApiException(400, "INVALID_REQUEST", "请明确提供最多 1000 个 UTF-16 码元的关系整理要求，且不能包含 NUL 或不完整字符。");
        }
        if (request.sources() == null || request.sources().size() < 2 || request.sources().size() > 5) {
            throw new ApiException(422, "AI_LIMIT", "关系整理请选择 2–5 个当前已发布知识版本，其中至少两个为概念。");
        }
        var sources = List.copyOf(knowledgeSources(owner, request.sources(), new HashSet<>()));
        if (!validRelationSources(sources)) {
            throw new ApiException(422, "AI_SOURCE_UNAVAILABLE", "关系来源须包含至少两个概念，标题与正文须在知识存储范围内且不能含空白正文、NUL 或不完整字符。");
        }
        var feedback = relationAiFeedbackDrafts.input(owner, request.source());
        // AI 反馈保留固定报告身份及完整限定；不能清洗历史背景或将选区扩展成整份评价报告。
        int sourceBytes = canonical.writeValueAsBytes(relationAiFeedbackContent(request.instruction(), sources, feedback)).length;
        return withKnowledgeMaterialChecks(owner, manifest(Purpose.RELATION_DRAFT, request.instruction(), sources, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, feedback, sourceBytes));
    }

    public Manifest preparationDraftManifest(String owner, PreparationDraftPreviewRequest request) {
        properties.requireReady();
        if (request == null || !draftPurpose(request.purpose())) {
            throw new ApiException(400, "AI_PURPOSE_INVALID", "请选择知识整理或出题用途。");
        }
        String instruction = request.instruction() == null ? "" : request.instruction().strip();
        if (instruction.length() > 1000 || instruction.indexOf('\0') >= 0) {
            throw new ApiException(422, "AI_LIMIT", "任务要求最多 1000 个 UTF-16 码元，且不能包含 NUL 字符。");
        }
        if (request.sources() == null || request.preparationSources() == null) {
            throw new ApiException(400, "AI_SOURCE_INVALID", "请明确提供知识和准备来源列表。");
        }
        if (request.preparationSources().isEmpty() || request.preparationSources().size() > 5
                || request.sources().size() > 5 - request.preparationSources().size()) {
            throw new ApiException(422, "AI_LIMIT", "请选择至少 1 个准备版本，知识和准备来源合计最多 5 个。");
        }
        var seen = new HashSet<String>();
        var sources = knowledgeSources(owner, request.sources(), seen);
        var expressionChecks = new ArrayList<PreparationDtos.ExpressionCheck>();
        var checks = appendPreparationSources(owner, request.preparationSources(), seen, sources, expressionChecks);
        if (!expressionChecks.isEmpty() && !validUnicode(instruction)) {
            throw new ApiException(422, "AI_LIMIT", "表达来源的任务要求不能包含不完整字符。");
        }
        // 代次只用于本地授权守卫；费用预留按明确发送的来源投影计算。
        int sourceBytes = expressionChecks.isEmpty()
                ? canonical.writeValueAsBytes(new SourceContent(instruction, sources)).length
                : canonical.writeValueAsBytes(new ExpressionSourceContent(request.purpose(), instruction, sources)).length;
        return withKnowledgeMaterialChecks(owner, manifest(request.purpose(), instruction, List.copyOf(sources), null, null, null, null,
                checks, null, null, null, null, expressionChecks.isEmpty() ? null : List.copyOf(expressionChecks), sourceBytes));
    }

    public Manifest materialDraftManifest(String owner, MaterialDraftPreviewRequest request) {
        properties.requireReady();
        if (request == null || !draftPurpose(request.purpose())) {
            throw new ApiException(400, "AI_PURPOSE_INVALID", "请选择知识整理或出题用途。");
        }
        String instruction = request.instruction() == null ? "" : request.instruction().strip();
        if (instruction.length() > 1000 || instruction.indexOf('\0') >= 0) {
            throw new ApiException(422, "AI_LIMIT", "任务要求最多 1000 个 UTF-16 码元，且不能包含 NUL 字符。");
        }
        if (request.sources() == null || request.preparationSources() == null || request.excerptIds() == null) {
            throw new ApiException(400, "AI_SOURCE_INVALID", "请明确提供知识、准备和资料摘录列表。");
        }
        int excerptCount = request.excerptIds().size();
        if (excerptCount < 1 || excerptCount > 5 || request.preparationSources().size() > 5 - excerptCount
                || request.sources().size() > 5 - excerptCount - request.preparationSources().size()) {
            throw new ApiException(422, "AI_LIMIT", "请选择至少 1 条资料摘录，知识、准备和摘录合计最多 5 项。");
        }
        var excerptIds = new HashSet<String>();
        for (String id : request.excerptIds()) {
            if (!uuid(id) || !excerptIds.add(id)) {
                throw new ApiException(400, "AI_SOURCE_INVALID", "摘录标识无效或存在重复项。");
            }
        }
        var seen = new HashSet<String>();
        var sources = knowledgeSources(owner, request.sources(), seen);
        var expressionChecks = new ArrayList<PreparationDtos.ExpressionCheck>();
        var checks = appendPreparationSources(owner, request.preparationSources(), seen, sources, expressionChecks);
        if (!expressionChecks.isEmpty() && !validUnicode(instruction)) {
            throw new ApiException(422, "AI_LIMIT", "表达来源的任务要求不能包含不完整字符。");
        }
        var excerpts = materials.read(owner, request.excerptIds());
        // 资料保持独立身份；字节额度覆盖本次全部外发内容，不包含仅用于本地守卫的准备代次。
        int sourceBytes = canonical.writeValueAsBytes(new MaterialDraftContent(request.purpose(), instruction, sources, excerpts)).length;
        return withMaterialChecks(owner, manifest(request.purpose(), instruction, List.copyOf(sources), null, null, null, null,
                checks.isEmpty() ? null : checks, excerpts, null, null, null,
                expressionChecks.isEmpty() ? null : List.copyOf(expressionChecks), sourceBytes));
    }

    public Manifest expressionDraftManifest(String owner, ExpressionDraftPreviewRequest request) {
        properties.requireReady();
        if (request == null || request.type() == null || request.instruction() == null) {
            throw new ApiException(400, "INVALID_REQUEST", "请明确提供表达类型、要求和事实来源。");
        }
        String instruction = request.instruction().strip();
        if (instruction.length() > 1000 || instruction.indexOf('\0') >= 0 || !validUnicode(instruction)) {
            throw new ApiException(422, "AI_LIMIT", "任务要求最多 1000 个 UTF-16 码元，且不能包含 NUL 或不完整字符。");
        }
        if (request.factSources() == null || request.factSources().isEmpty() || request.factSources().size() > 5) {
            throw new ApiException(422, "AI_LIMIT", "表达任务请选择 1–5 个已确认事实的当前版本。");
        }
        var links = new ArrayList<PreparationLink>();
        for (SourceLink link : request.factSources()) {
            if (link == null) throw new ApiException(400, "AI_SOURCE_INVALID", "事实来源不能为空。");
            links.add(new PreparationLink(PreparationDtos.Kind.FACT, link.itemId(), link.versionId()));
        }
        var sources = new ArrayList<Source>();
        var checks = appendPreparationSources(owner, links, new HashSet<>(), sources, null);
        // 表达只读取显式事实；父项目代次参与授权，不展开项目概况、附件或旧表达正文。
        int sourceBytes = canonical.writeValueAsBytes(new ExpressionDraftContent(
                Purpose.EXPRESSION_DRAFT, instruction, request.type(), sources)).length;
        return withMaterialChecks(owner, manifest(Purpose.EXPRESSION_DRAFT, instruction, List.copyOf(sources), null, null, null, null,
                checks, null, request.type(), null, null, null, sourceBytes));
    }

    public Manifest preparationPlanManifest(String owner, PreparationPlanPreviewRequest request) {
        properties.requireReady();
        if (request == null || request.target() == null || !validFixedInstruction(request.instruction())) {
            throw new ApiException(400, "INVALID_REQUEST", "请明确提供目标和最多 1000 个 UTF-16 码元的要求，且不能包含 NUL 或不完整字符。");
        }
        PreparationPlanRules.input(request.plan());
        var sources = new ArrayList<Source>();
        var checks = appendPreparationSources(owner, List.of(new PreparationLink(PreparationDtos.Kind.TARGET,
                request.target().itemId(), request.target().versionId())), new HashSet<>(), sources, null);
        // 仅发送所选目标和本次时间、缺口；来源代次参与本地摘要，不展开日历或其他准备材料。
        int sourceBytes = canonical.writeValueAsBytes(new PreparationPlanContentProjection(
                Purpose.PREPARATION_PLAN, request.instruction(), sources, request.plan())).length;
        return withMaterialChecks(owner, manifest(Purpose.PREPARATION_PLAN, request.instruction(), List.copyOf(sources), null, null, null, null,
                checks, null, null, request.plan(), null, null, sourceBytes));
    }

    public Manifest trainingDraftManifest(String owner, TrainingDraftPreviewRequest request) {
        properties.requireReady();
        if (request == null || !validFixedInstruction(request.instruction())) {
            throw new ApiException(400, "INVALID_REQUEST", "请明确提供最多 1000 个 UTF-16 码元的整理要求，且不能包含 NUL 或不完整字符。");
        }
        TrainingDraftInput input = trainingDrafts.input(owner, request);
        // 费用仅按确切题面、所选片段和反馈投影计算，不扩展为完整原答或其他训练资料。
        int sourceBytes = canonical.writeValueAsBytes(new TrainingDraftContentProjection(
                Purpose.KNOWLEDGE_DRAFT, request.instruction(), input)).length;
        return withMaterialChecks(owner, manifest(Purpose.KNOWLEDGE_DRAFT, request.instruction(), List.of(), null, null, null, null,
                null, null, null, null, input, null, sourceBytes));
    }

    public Manifest reviewDraftManifest(String owner, ReviewDraftDtos.PreviewRequest request) {
        properties.requireReady();
        if (request == null || !validFixedInstruction(request.instruction())) {
            throw new ApiException(400, "INVALID_REQUEST", "请明确提供最多 1000 个 UTF-16 码元的整理要求，且不能包含 NUL 或不完整字符。");
        }
        ReviewDraftDtos.Input input = reviewDrafts.input(owner, request.source());
        // 额度和网关共用明确外发投影；反馈原引文、决定原因与其他未选资料不会随来源展开。
        int sourceBytes = canonical.writeValueAsBytes(reviewDraftContent(request.instruction(), input)).length;
        return withMaterialChecks(owner, manifest(Purpose.KNOWLEDGE_DRAFT, request.instruction(), List.of(), null, null, null, null,
                null, null, null, null, null, null, null, null, input, sourceBytes));
    }

    public Manifest cardDraftManifest(String owner, CardDraftDtos.PreviewRequest request) {
        properties.requireReady();
        if (request == null || !cardDraftKind(request.kind()) || !validFixedInstruction(request.instruction())) {
            throw new ApiException(400, "INVALID_REQUEST", "请明确选择问答或挖空卡片，并提供最多 1000 个 UTF-16 码元的要求，且不能包含 NUL 或不完整字符。");
        }
        if (request.sources() == null || request.sources().isEmpty() || request.sources().size() > 5) {
            throw new ApiException(422, "AI_LIMIT", "制卡请选择 1–5 个当前已发布知识版本。");
        }
        var sources = List.copyOf(knowledgeSources(owner, request.sources(), new HashSet<>()));
        if (!validCardDraftSources(sources)) {
            throw new ApiException(422, "AI_SOURCE_UNAVAILABLE", "制卡来源须为笔记或概念的完整发布正文，且不能含空白正文、NUL 或不完整字符。");
        }
        // 保留整理要求原文；额度与网关共用显式发布版本投影，不读取未发布草稿或关联材料。
        int sourceBytes = canonical.writeValueAsBytes(cardDraftContent(request.instruction(), request.kind(), sources)).length;
        return withKnowledgeMaterialChecks(owner, manifest(Purpose.MEMORY_CARD_DRAFT, request.instruction(), sources, null, null, null, null,
                null, null, null, null, null, null, null, null, null, request.kind(), sourceBytes));
    }

    public Manifest jdRequirementsManifest(String owner, JdRequirementsDtos.PreviewRequest request) {
        properties.requireReady();
        if (request == null || !uuid(request.targetItemId()) || !uuid(request.targetVersionId())
                || !validFixedInstruction(request.instruction())) {
            throw new ApiException(400, "INVALID_REQUEST", "请选择确切目标版本，并提供最多 1000 个 UTF-16 码元的要求，且不能包含 NUL 或不完整字符。");
        }
        var version = preparation.readJd(owner, request.targetItemId(), request.targetVersionId());
        if (version == null) {
            throw new ApiException(409, "AI_SOURCE_CHANGED", "目标版本已变化或不可用，请重新选择当前目标。");
        }
        Source source = preparationSource(version);
        if (!validJdRequirementsSource(source, version.check())) {
            throw new ApiException(422, "AI_SOURCE_UNAVAILABLE", "所选目标须有非空白且不超过 50000 个 UTF-16 码元的完整 JD，标题与正文不能含 NUL 或不完整字符。");
        }
        var sources = List.of(source);
        // 原始 JD 是唯一外发正文；公司、已有要求和关联资料不由通用 TARGET 投影展开。
        int sourceBytes = canonical.writeValueAsBytes(jdRequirementsContent(request.instruction(), sources)).length;
        return withMaterialChecks(owner, manifest(Purpose.JD_REQUIREMENT_DRAFT, request.instruction(), sources, null, null, null, null,
                List.of(version.check()), null, null, null, null, null, sourceBytes));
    }

    public Manifest factDraftManifest(String owner, FactDraftDtos.PreviewRequest request) {
        properties.requireReady();
        if (request == null || !validFixedInstruction(request.instruction())) {
            throw new ApiException(400, "INVALID_REQUEST", "请提供最多 1000 个 UTF-16 码元的事实整理要求，且不能包含 NUL 或不完整字符。");
        }
        FactDraftDtos.Input input = factDrafts.input(owner, request.source());
        // 项目在采用时另行选择；预览、预算和网关只使用本次明确选定的材料或答案窄投影。
        int sourceBytes = canonical.writeValueAsBytes(factDraftContent(request.instruction(), input)).length;
        return withMaterialChecks(owner, manifest(Purpose.FACT_DRAFT, request.instruction(), List.of(), null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, input, sourceBytes));
    }

    private List<PreparationDtos.SourceCheck> appendPreparationSources(String owner, List<PreparationLink> links,
                                                                      Set<String> seen, List<Source> sources,
                                                                      List<PreparationDtos.ExpressionCheck> expressionChecks) {
        var checks = new ArrayList<PreparationDtos.SourceCheck>();
        for (PreparationLink link : links) {
            if (link == null || link.kind() == null || (link.kind() == PreparationDtos.Kind.EXPRESSION && expressionChecks == null)
                    || !uuid(link.itemId()) || !uuid(link.versionId()) || !seen.add(link.itemId())) {
                throw new ApiException(400, "AI_SOURCE_INVALID", "准备来源类型或标识无效，或与已选来源重复。");
            }
            PreparationDtos.AiVersion version;
            if (link.kind() == PreparationDtos.Kind.EXPRESSION) {
                var expression = preparation.readExpression(owner, link.itemId(), link.versionId());
                version = expression == null ? null : expression.version();
                if (expression != null) expressionChecks.add(expression.check());
            } else {
                version = preparation.read(owner, link.kind(), link.itemId(), link.versionId());
            }
            if (version == null) {
                throw new ApiException(409, "AI_SOURCE_CHANGED", "准备版本已变化或不可用，请重新选择当前可用版本。");
            }
            sources.add(preparationSource(version));
            checks.add(version.check());
        }
        return List.copyOf(checks);
    }

    private ArrayList<Source> knowledgeSources(String owner, List<SourceLink> links, Set<String> seen) {
        var sources = new ArrayList<Source>();
        for (SourceLink link : links) {
            if (link == null || !uuid(link.itemId()) || !uuid(link.versionId()) || !seen.add(link.itemId())) {
                throw new ApiException(400, "AI_SOURCE_INVALID", "知识来源标识无效或存在重复项。");
            }
            var item = knowledge.findItem(owner, link.itemId());
            if (item == null || !"ACTIVE".equals(item.status()) || !link.versionId().equals(item.currentVersionId())) {
                throw new ApiException(409, "AI_SOURCE_CHANGED", "所选知识已变化或不可用，请重新选择已发布版本。");
            }
            // 详情页包含尚未发布的草稿，外发仅读取用户选中的确切发布版本。
            var version = knowledge.findVersion(owner, link.itemId(), link.versionId());
            if (version == null || version.body() == null || version.body().isBlank()) {
                throw new ApiException(422, "AI_SOURCE_UNAVAILABLE", "所选知识没有可用的已发布正文。");
            }
            revalidation.requireClear(owner, link.itemId(), link.versionId());
            sources.add(new Source(item.id(), version.id(), version.number(), item.kind(), version.title(), version.body()));
        }
        return sources;
    }

    public Manifest followUpManifest(String owner, String sessionId, String questionId, String answerId) {
        properties.requireReady();
        var checked = training.input(owner, sessionId, questionId, answerId);
        FollowUpInput input = checked.input();
        training.requireAvailable(owner, input, null);
        if (!trainingMaterialsAllowed(owner, input)) throw materialUseChanged();
        // 核对元数据参与本地授权摘要，费用和外发大小仅按实际发送投影计算。
        return withMaterialChecks(owner, manifest(Purpose.FOLLOW_UP, "", List.of(), input, null, checked.contextChecks(), null, null, null, null, null, null, null,
                canonical.writeValueAsBytes(input).length));
    }

    public Manifest evaluationManifest(String owner, String sessionId, String questionId, String answerId) {
        properties.requireReady();
        EvaluationInput input = evaluations.input(owner, sessionId, questionId, answerId);
        evaluations.requireAvailable(owner, input, null);
        return withMaterialChecks(owner, manifest(Purpose.ANSWER_EVALUATION, "", List.of(), null, input, null, null,
                null, null, null, null, null, null, canonical.writeValueAsBytes(input).length));
    }

    public Manifest rewriteManifest(String owner, String sessionId, String questionId, String answerId) {
        properties.requireReady();
        EvaluationInput input = rewrites.input(owner, sessionId, questionId, answerId);
        rewrites.requireAvailable(owner, input, null);
        // 费用和网关使用同一封闭投影，个人参考与原答必须全部在预览内，不展开关联背景。
        return withMaterialChecks(owner, manifest(Purpose.ANSWER_REWRITE, "", List.of(), null, input, null, null,
                null, null, null, null, null, null, canonical.writeValueAsBytes(rewriteContent(input)).length));
    }

    public Manifest comparisonManifest(String owner, String id, List<String> sourceIds) {
        properties.requireReady();
        var selection = comparisons.selection(owner, id, sourceIds);
        comparisons.requireAvailable(owner, id, null);
        // 训练代次与动作修订只参与本地授权摘要，费用额度仅覆盖外发白名单投影。
        return withMaterialChecks(owner, manifest(Purpose.ANSWER_COMPARISON, "", List.of(), null, null, null, null,
                null, null, null, null, null, null, selection.input(), selection.checks(),
                canonical.writeValueAsBytes(selection.input()).length));
    }

    public Manifest materialQaManifest(String owner, MaterialQaPreviewRequest request) {
        properties.requireReady();
        if (request == null || request.mode() == null || request.excerptIds() == null) {
            throw new ApiException(400, "INVALID_REQUEST", "请选择问答模式，并明确提供摘录列表。");
        }
        String question = request.question() == null ? "" : request.question().strip();
        if (!validMaterialQuestion(question)) {
            throw new ApiException(422, "AI_LIMIT", "问题须为 1–2000 个 UTF-16 码元，且不能包含 NUL 字符。");
        }
        if (request.mode() == MaterialQaMode.GENERAL && !request.excerptIds().isEmpty()) {
            throw new ApiException(400, "AI_SOURCE_INVALID", "通用解释的摘录列表必须明确为空。");
        }
        if (request.mode() == MaterialQaMode.MATERIAL) {
            if (request.excerptIds().isEmpty() || request.excerptIds().size() > 5) {
                throw new ApiException(422, "AI_LIMIT", "资料问答每次请选择 1–5 条确切摘录。");
            }
            var seen = new HashSet<String>();
            for (String id : request.excerptIds()) {
                if (!uuid(id) || !seen.add(id)) {
                    throw new ApiException(400, "AI_SOURCE_INVALID", "摘录标识无效或存在重复项。");
                }
            }
        }
        // 通用解释不读取资料域；明确选择的摘录是唯一允许加入清单的资料。
        List<AiExcerpt> excerpts = request.mode() == MaterialQaMode.MATERIAL
                ? materials.read(owner, request.excerptIds()) : List.of();
        MaterialQaInput input = new MaterialQaInput(request.mode(), question, excerpts);
        return withMaterialChecks(owner, manifest(Purpose.MATERIAL_QA, "", List.of(), null, null, null, input,
                null, null, null, null, null, null, canonical.writeValueAsBytes(input).length));
    }

    public Manifest sourceQaManifest(String owner, SourceQaDtos.PreviewRequest request) {
        properties.requireReady();
        SourceQaDtos.Input input = sourceQa.capture(owner, request);
        // 费用与网关共用完整显式投影；本地 checks 参与授权摘要但不外发。
        int bytes = canonical.writeValueAsBytes(SourceQaSources.projection(input)).length;
        Manifest base = manifest(Purpose.SOURCE_QA, "", List.of(), null, null, null, null,
                null, null, null, null, null, null, bytes);
        return withMaterialChecks(owner, new Manifest(base.purpose(), base.instruction(), base.sources(), base.configuration(),
                base.inputTokenAllowance(), base.reservedMicros(), base.promptVersion(), null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, input,
                base.materialChecks(), base.knowledgeMaterialChecks()));
    }

    private Manifest withMaterialChecks(String owner, Manifest manifest) {
        return withKnowledgeMaterialChecks(owner, manifest);
    }

    private Manifest withKnowledgeMaterialChecks(String owner, Manifest manifest) {
        List<String> directIds = directAndPreparationMaterialIds(owner, manifest);
        if (directIds == null) throw new IllegalStateException("AI 清单的材料固定身份无效");
        var directChecks = directIds.isEmpty() ? null : materialLifecycle.captureAiChecks(owner, directIds);
        List<String> ids = knowledgeMaterialLineage.manifestExcerptIds(owner, manifest);
        // 尾字段沿用旧名称，但覆盖实际外发的知识、准备、题面及答案来源；不把祖先正文追加给模型。
        // 完整历史证明只确定材料身份；新外发固定当前代次，不复用原任务当时的许可。
        List<MaterialLifecycleDtos.Check> checks = ids.isEmpty() ? List.of() : materialLifecycle.captureAiChecks(owner, ids);
        return new Manifest(manifest.purpose(), manifest.instruction(), manifest.sources(), manifest.configuration(),
                manifest.inputTokenAllowance(), manifest.reservedMicros(), manifest.promptVersion(), manifest.training(),
                manifest.evaluation(), manifest.contextChecks(), manifest.materialQa(), manifest.preparationChecks(),
                manifest.materialDraftSources(), manifest.expressionType(), manifest.preparationPlan(), manifest.trainingDraft(),
                manifest.expressionChecks(), manifest.comparisonInput(), manifest.comparisonChecks(), manifest.reviewDraft(),
                manifest.cardDraftType(), manifest.factDraft(), manifest.relationEvidence(), manifest.relationAiFeedback(),
                manifest.sourceQa(), directChecks, checks);
    }

    private boolean currentKnowledgeMaterialChecks(String owner, Manifest manifest) {
        List<String> ids;
        try {
            ids = knowledgeMaterialLineage.manifestExcerptIds(owner, manifest);
        } catch (ApiException exception) {
            // 缺历史见证只使新动作不可用；普通任务历史仍可读，数据库与完整性错误不能伪装成失效。
            if ("KNOWLEDGE_MATERIAL_LINEAGE_HISTORY_UNAVAILABLE".equals(exception.code())) return false;
            throw exception;
        }
        // 新清单即使没有材料祖先也显式保存 []；旧 null 仅兼容当前仍为原始 0 代次的完整已证明集合。
        if (ids.isEmpty()) return manifest.knowledgeMaterialChecks() == null || manifest.knowledgeMaterialChecks().isEmpty();
        return materialLifecycle.currentAiChecks(owner, ids, manifest.knowledgeMaterialChecks());
    }

    private static List<String> directMaterialIds(Manifest manifest) {
        var ids = new TreeSet<String>();
        var excerpts = new ArrayList<AiExcerpt>();
        if (manifest.materialDraftSources() != null) excerpts.addAll(manifest.materialDraftSources());
        if (manifest.materialQa() != null && manifest.materialQa().excerpts() != null) {
            excerpts.addAll(manifest.materialQa().excerpts());
        }
        if (manifest.factDraft() != null && manifest.factDraft().kind() == FactDraftDtos.Kind.MATERIAL
                && manifest.factDraft().materials() != null) excerpts.addAll(manifest.factDraft().materials());
        if (manifest.relationEvidence() != null && manifest.relationEvidence().kind() == FactDraftDtos.Kind.MATERIAL
                && manifest.relationEvidence().materials() != null) excerpts.addAll(manifest.relationEvidence().materials());
        for (AiExcerpt excerpt : excerpts) {
            if (excerpt == null || !uuid(excerpt.excerptId())) return null;
            ids.add(excerpt.excerptId());
        }
        if (manifest.sourceQa() != null && manifest.sourceQa().sources() != null) {
            for (var source : manifest.sourceQa().sources()) {
                if (source == null || source.ref() == null) return null;
                if (source.ref().domain() == com.wenjie.studio.search.UnifiedSearchDtos.Domain.MATERIAL_EXCERPT) {
                    if (!uuid(source.ref().id())) return null;
                    ids.add(source.ref().id());
                }
            }
        }
        return List.copyOf(ids);
    }

    private List<String> directAndPreparationMaterialIds(String owner, Manifest manifest) {
        List<String> direct = directMaterialIds(manifest);
        if (direct == null || manifest.sources() == null) return null;
        var ids = new TreeSet<>(direct);
        for (var source : manifest.sources()) {
            if (source == null) return null;
            if (preparationKind(source.kind())) {
                ids.addAll(preparation.materialIds(owner, PreparationDtos.Kind.valueOf(source.kind()), source.itemId(), source.versionId()));
            }
        }
        if (manifest.training() != null) {
            if (manifest.training().context() == null) return null;
            for (var context : manifest.training().context()) {
                if (context == null || !List.of("TARGET", "PROJECT", "FACT").contains(context.kind())) return null;
                ids.addAll(preparation.materialIds(owner, PreparationDtos.Kind.valueOf(context.kind()), context.itemId(), context.versionId()));
            }
        }
        if (manifest.sourceQa() != null) {
            if (manifest.sourceQa().sources() == null) return null;
            for (var source : manifest.sourceQa().sources()) {
                if (source == null || source.ref() == null) return null;
                if (source.ref().domain() == com.wenjie.studio.search.UnifiedSearchDtos.Domain.PREPARATION) {
                    if (!preparationKind(source.kind())) return null;
                    ids.addAll(preparation.materialIds(owner, PreparationDtos.Kind.valueOf(source.kind()), source.ref().id(), source.ref().versionId()));
                }
            }
        }
        if (ids.size() > 200) throw new ApiException(422, "AI_MATERIAL_SOURCE_LIMIT", "材料许可依赖超过本次完整核对上限，请减少所选来源。");
        return List.copyOf(ids);
    }

    private boolean trainingMaterialsAllowed(String owner, FollowUpInput input) {
        if (input == null || input.context() == null) return false;
        // 追问实际外发的固定背景也须核材料许可；普通训练状态和历史来源检查保持独立。
        for (FollowUpContext context : input.context()) {
            if (context == null || !List.of("TARGET", "PROJECT", "FACT").contains(context.kind())
                    || !preparation.aiAllowed(owner, PreparationDtos.Kind.valueOf(context.kind()),
                        context.itemId(), context.versionId())) return false;
        }
        return true;
    }

    private static ApiException materialUseChanged() {
        return new ApiException(409, "AI_SOURCE_CHANGED", "来源材料已归档或撤回 AI 用途，请重新核对本次输入。");
    }

    private Manifest manifest(Purpose purpose, String instruction, List<Source> sources, FollowUpInput input,
                              EvaluationInput evaluation, List<TrainingDtos.ContextCheck> contextChecks,
                              MaterialQaInput materialQa, List<PreparationDtos.SourceCheck> preparationChecks,
                              List<AiExcerpt> materialDraftSources, PreparationDtos.ExpressionType expressionType,
                              PreparationPlanInput preparationPlan, TrainingDraftInput trainingDraft,
                              List<PreparationDtos.ExpressionCheck> expressionChecks, int sourceBytes) {
        return manifest(purpose, instruction, sources, input, evaluation, contextChecks, materialQa, preparationChecks,
                materialDraftSources, expressionType, preparationPlan, trainingDraft, expressionChecks, null, null, sourceBytes);
    }

    private Manifest manifest(Purpose purpose, String instruction, List<Source> sources, FollowUpInput input,
                              EvaluationInput evaluation, List<TrainingDtos.ContextCheck> contextChecks,
                              MaterialQaInput materialQa, List<PreparationDtos.SourceCheck> preparationChecks,
                              List<AiExcerpt> materialDraftSources, PreparationDtos.ExpressionType expressionType,
                              PreparationPlanInput preparationPlan, TrainingDraftInput trainingDraft,
                              List<PreparationDtos.ExpressionCheck> expressionChecks, ComparisonDtos.Input comparisonInput,
                              ComparisonDtos.Checks comparisonChecks, int sourceBytes) {
        return manifest(purpose, instruction, sources, input, evaluation, contextChecks, materialQa, preparationChecks,
                materialDraftSources, expressionType, preparationPlan, trainingDraft, expressionChecks,
                comparisonInput, comparisonChecks, null, sourceBytes);
    }

    private Manifest manifest(Purpose purpose, String instruction, List<Source> sources, FollowUpInput input,
                              EvaluationInput evaluation, List<TrainingDtos.ContextCheck> contextChecks,
                              MaterialQaInput materialQa, List<PreparationDtos.SourceCheck> preparationChecks,
                              List<AiExcerpt> materialDraftSources, PreparationDtos.ExpressionType expressionType,
                              PreparationPlanInput preparationPlan, TrainingDraftInput trainingDraft,
                              List<PreparationDtos.ExpressionCheck> expressionChecks, ComparisonDtos.Input comparisonInput,
                              ComparisonDtos.Checks comparisonChecks, ReviewDraftDtos.Input reviewDraft, int sourceBytes) {
        return manifest(purpose, instruction, sources, input, evaluation, contextChecks, materialQa, preparationChecks,
                materialDraftSources, expressionType, preparationPlan, trainingDraft, expressionChecks,
                comparisonInput, comparisonChecks, reviewDraft, null, sourceBytes);
    }

    private Manifest manifest(Purpose purpose, String instruction, List<Source> sources, FollowUpInput input,
                              EvaluationInput evaluation, List<TrainingDtos.ContextCheck> contextChecks,
                              MaterialQaInput materialQa, List<PreparationDtos.SourceCheck> preparationChecks,
                              List<AiExcerpt> materialDraftSources, PreparationDtos.ExpressionType expressionType,
                              PreparationPlanInput preparationPlan, TrainingDraftInput trainingDraft,
                              List<PreparationDtos.ExpressionCheck> expressionChecks, ComparisonDtos.Input comparisonInput,
                              ComparisonDtos.Checks comparisonChecks, ReviewDraftDtos.Input reviewDraft,
                              MemoryDtos.Kind cardDraftType, int sourceBytes) {
        return manifest(purpose, instruction, sources, input, evaluation, contextChecks, materialQa, preparationChecks,
                materialDraftSources, expressionType, preparationPlan, trainingDraft, expressionChecks,
                comparisonInput, comparisonChecks, reviewDraft, cardDraftType, null, sourceBytes);
    }

    private Manifest manifest(Purpose purpose, String instruction, List<Source> sources, FollowUpInput input,
                              EvaluationInput evaluation, List<TrainingDtos.ContextCheck> contextChecks,
                              MaterialQaInput materialQa, List<PreparationDtos.SourceCheck> preparationChecks,
                              List<AiExcerpt> materialDraftSources, PreparationDtos.ExpressionType expressionType,
                              PreparationPlanInput preparationPlan, TrainingDraftInput trainingDraft,
                              List<PreparationDtos.ExpressionCheck> expressionChecks, ComparisonDtos.Input comparisonInput,
                              ComparisonDtos.Checks comparisonChecks, ReviewDraftDtos.Input reviewDraft,
                              MemoryDtos.Kind cardDraftType, FactDraftDtos.Input factDraft, int sourceBytes) {
        return manifest(purpose, instruction, sources, input, evaluation, contextChecks, materialQa, preparationChecks,
                materialDraftSources, expressionType, preparationPlan, trainingDraft, expressionChecks,
                comparisonInput, comparisonChecks, reviewDraft, cardDraftType, factDraft, null, sourceBytes);
    }

    private Manifest manifest(Purpose purpose, String instruction, List<Source> sources, FollowUpInput input,
                              EvaluationInput evaluation, List<TrainingDtos.ContextCheck> contextChecks,
                              MaterialQaInput materialQa, List<PreparationDtos.SourceCheck> preparationChecks,
                              List<AiExcerpt> materialDraftSources, PreparationDtos.ExpressionType expressionType,
                              PreparationPlanInput preparationPlan, TrainingDraftInput trainingDraft,
                              List<PreparationDtos.ExpressionCheck> expressionChecks, ComparisonDtos.Input comparisonInput,
                              ComparisonDtos.Checks comparisonChecks, ReviewDraftDtos.Input reviewDraft,
                              MemoryDtos.Kind cardDraftType, FactDraftDtos.Input factDraft,
                              FactDraftDtos.Input relationEvidence, int sourceBytes) {
        return manifest(purpose, instruction, sources, input, evaluation, contextChecks, materialQa, preparationChecks,
                materialDraftSources, expressionType, preparationPlan, trainingDraft, expressionChecks,
                comparisonInput, comparisonChecks, reviewDraft, cardDraftType, factDraft, relationEvidence, null, sourceBytes);
    }

    private Manifest manifest(Purpose purpose, String instruction, List<Source> sources, FollowUpInput input,
                              EvaluationInput evaluation, List<TrainingDtos.ContextCheck> contextChecks,
                              MaterialQaInput materialQa, List<PreparationDtos.SourceCheck> preparationChecks,
                              List<AiExcerpt> materialDraftSources, PreparationDtos.ExpressionType expressionType,
                              PreparationPlanInput preparationPlan, TrainingDraftInput trainingDraft,
                              List<PreparationDtos.ExpressionCheck> expressionChecks, ComparisonDtos.Input comparisonInput,
                              ComparisonDtos.Checks comparisonChecks, ReviewDraftDtos.Input reviewDraft,
                              MemoryDtos.Kind cardDraftType, FactDraftDtos.Input factDraft,
                              FactDraftDtos.Input relationEvidence, RelationAiFeedbackEvidenceDtos.Snapshot relationAiFeedback,
                              int sourceBytes) {
        var configuration = properties.configuration();
        if (sourceBytes > configuration.maxInputBytes()) {
            throw new ApiException(422, "AI_LIMIT", "本次固定输入超过 32 KiB；请缩减来源或提交较短的新修订，系统不会截断原文。");
        }
        // 按字节加协议余量作保守预算；不是特定 tokenizer 或供应商收费承诺。
        int inputAllowance = Math.addExact(sourceBytes, 8192);
        long reservation;
        try {
            reservation = Math.addExact(price(inputAllowance, configuration.inputPriceMicrosPerMillion()),
                    price(configuration.maxOutputTokens(), configuration.outputPriceMicrosPerMillion()));
        } catch (ArithmeticException exception) {
            throw new ApiException(422, "AI_LIMIT", "费用预留超出允许范围，请核对服务器价格配置。");
        }
        return new Manifest(purpose, instruction, sources, configuration,
                inputAllowance, reservation, relationAiFeedback != null ? RELATION_AI_FEEDBACK_PROMPT_VERSION
                : relationEvidence != null ? RELATION_EVIDENCE_PROMPT_VERSION
                : factDraft != null ? FACT_DRAFT_PROMPT_VERSION
                : cardDraftType != null ? CARD_DRAFT_PROMPT_VERSION
                : reviewDraft != null ? (purpose == Purpose.RELATION_DRAFT ? RELATION_REVIEW_PROMPT_VERSION : REVIEW_DRAFT_PROMPT_VERSION)
                : trainingDraft != null ? TRAINING_DRAFT_PROMPT_VERSION
                : purpose == Purpose.EXPRESSION_DRAFT || purpose == Purpose.PREPARATION_PLAN || purpose == Purpose.RELATION_DRAFT
                        || purpose == Purpose.JD_REQUIREMENT_DRAFT ? promptVersion(purpose)
                : expressionChecks != null ? expressionSourcePromptVersion(purpose, materialDraftSources != null)
                : materialDraftSources != null ? materialDraftPromptVersion(purpose)
                : preparationChecks == null ? promptVersion(purpose) : preparationPromptVersion(purpose),
                input, evaluation, contextChecks, materialQa, preparationChecks, materialDraftSources, expressionType,
                preparationPlan, trainingDraft, expressionChecks, comparisonInput, comparisonChecks, reviewDraft, cardDraftType,
                factDraft, relationEvidence, relationAiFeedback);
    }

    public static String promptVersion(Purpose purpose) {
        if (purpose == null) return null;
        return switch (purpose) {
            case KNOWLEDGE_DRAFT -> PROMPT_VERSION;
            case QUESTION_DRAFT -> "question-draft-1";
            case FOLLOW_UP -> "training-follow-up-2";
            case ANSWER_EVALUATION -> "training-evaluation-1";
            case MATERIAL_QA -> "material-qa-1";
            case SOURCE_QA -> "source-qa-1";
            case EXPRESSION_DRAFT -> "expression-draft-1";
            case PREPARATION_PLAN -> "preparation-plan-1";
            case RELATION_DRAFT -> "relation-draft-1";
            case ANSWER_COMPARISON -> "answer-comparison-1";
            case MEMORY_CARD_DRAFT -> CARD_DRAFT_PROMPT_VERSION;
            case JD_REQUIREMENT_DRAFT -> JD_REQUIREMENTS_PROMPT_VERSION;
            case FACT_DRAFT -> FACT_DRAFT_PROMPT_VERSION;
            case ANSWER_REWRITE -> REWRITE_PROMPT_VERSION;
        };
    }

    public static String promptVersion(Manifest manifest) {
        if (manifest == null || manifest.purpose() == null) return null;
        if (manifest.sourceQa() != null || manifest.purpose() == Purpose.SOURCE_QA) {
            return validSourceQaInput(manifest) ? promptVersion(Purpose.SOURCE_QA) : null;
        }
        if (manifest.relationAiFeedback() != null) {
            return validRelationAiFeedbackInput(manifest) ? RELATION_AI_FEEDBACK_PROMPT_VERSION : null;
        }
        if (manifest.relationEvidence() != null) {
            return validRelationInput(manifest) ? RELATION_EVIDENCE_PROMPT_VERSION : null;
        }
        if (manifest.reviewDraft() != null) {
            return validRelationReviewInput(manifest) ? RELATION_REVIEW_PROMPT_VERSION
                    : validReviewDraftInput(manifest) ? REVIEW_DRAFT_PROMPT_VERSION : null;
        }
        if (manifest.purpose() == Purpose.ANSWER_REWRITE) {
            return validRewriteInput(manifest) ? REWRITE_PROMPT_VERSION : null;
        }
        if (manifest.factDraft() != null || manifest.purpose() == Purpose.FACT_DRAFT) {
            return validFactDraftInput(manifest) ? FACT_DRAFT_PROMPT_VERSION : null;
        }
        if (manifest.purpose() == Purpose.JD_REQUIREMENT_DRAFT) {
            return validJdRequirementsInput(manifest) ? JD_REQUIREMENTS_PROMPT_VERSION : null;
        }
        if (manifest.cardDraftType() != null || manifest.purpose() == Purpose.MEMORY_CARD_DRAFT) {
            return validCardDraftInput(manifest) ? CARD_DRAFT_PROMPT_VERSION : null;
        }
        if (manifest.trainingDraft() != null && manifest.purpose() == Purpose.KNOWLEDGE_DRAFT) {
            return TRAINING_DRAFT_PROMPT_VERSION;
        }
        if (manifest.expressionChecks() != null && draftPurpose(manifest.purpose())) {
            return expressionSourcePromptVersion(manifest.purpose(), manifest.materialDraftSources() != null);
        }
        if (manifest.materialDraftSources() != null && draftPurpose(manifest.purpose())) {
            return materialDraftPromptVersion(manifest.purpose());
        }
        return manifest.preparationChecks() != null && draftPurpose(manifest.purpose())
                ? preparationPromptVersion(manifest.purpose()) : promptVersion(manifest.purpose());
    }

    private static String materialDraftPromptVersion(Purpose purpose) {
        return switch (purpose) {
            case KNOWLEDGE_DRAFT -> "material-knowledge-draft-1";
            case QUESTION_DRAFT -> "material-question-draft-1";
            default -> null;
        };
    }

    private static String preparationPromptVersion(Purpose purpose) {
        return switch (purpose) {
            case KNOWLEDGE_DRAFT -> "preparation-knowledge-draft-1";
            case QUESTION_DRAFT -> "preparation-question-draft-1";
            default -> null;
        };
    }

    private static String expressionSourcePromptVersion(Purpose purpose, boolean material) {
        return switch (purpose) {
            case KNOWLEDGE_DRAFT -> material ? "expression-source-material-knowledge-draft-1" : "expression-source-knowledge-draft-1";
            case QUESTION_DRAFT -> material ? "expression-source-material-question-draft-1" : "expression-source-question-draft-1";
            default -> null;
        };
    }

    public static boolean validSourceQaInput(Manifest manifest) {
        return manifest != null && manifest.purpose() == Purpose.SOURCE_QA
                && "".equals(manifest.instruction()) && manifest.sources() != null && manifest.sources().isEmpty()
                && promptVersion(Purpose.SOURCE_QA).equals(manifest.promptVersion())
                && manifest.training() == null && manifest.evaluation() == null && manifest.contextChecks() == null
                && manifest.materialQa() == null && manifest.preparationChecks() == null && manifest.materialDraftSources() == null
                && manifest.expressionType() == null && manifest.preparationPlan() == null && manifest.trainingDraft() == null
                && manifest.expressionChecks() == null && manifest.comparisonInput() == null && manifest.comparisonChecks() == null
                && manifest.reviewDraft() == null && manifest.cardDraftType() == null && manifest.factDraft() == null
                && manifest.relationEvidence() == null && manifest.relationAiFeedback() == null && SourceQaSources.valid(manifest.sourceQa());
    }

    public static boolean validComparisonInput(Manifest manifest) {
        if (manifest != null && manifest.sourceQa() != null) return false;
        return manifest != null && manifest.relationAiFeedback() == null && manifest.relationEvidence() == null && manifest.factDraft() == null && manifest.purpose() == Purpose.ANSWER_COMPARISON
                && "".equals(manifest.instruction()) && manifest.sources() != null && manifest.sources().isEmpty()
                && promptVersion(Purpose.ANSWER_COMPARISON).equals(manifest.promptVersion())
                && manifest.training() == null && manifest.evaluation() == null && manifest.contextChecks() == null
                && manifest.materialQa() == null && manifest.preparationChecks() == null && manifest.materialDraftSources() == null
                && manifest.expressionType() == null && manifest.preparationPlan() == null && manifest.trainingDraft() == null
                && manifest.expressionChecks() == null && manifest.reviewDraft() == null && manifest.cardDraftType() == null
                && ComparisonSources.valid(manifest.comparisonInput(), manifest.comparisonChecks());
    }

    public static boolean validRelationInput(Manifest manifest) {
        if (manifest != null && manifest.sourceQa() != null) return false;
        if (manifest != null && manifest.relationAiFeedback() != null) return validRelationAiFeedbackInput(manifest);
        if (manifest != null && manifest.reviewDraft() != null) return validRelationReviewInput(manifest);
        return manifest != null && manifest.relationAiFeedback() == null && manifest.factDraft() == null && manifest.purpose() == Purpose.RELATION_DRAFT
                && validFixedInstruction(manifest.instruction())
                && (manifest.relationEvidence() != null || manifest.instruction().equals(manifest.instruction().strip()))
                && (manifest.relationEvidence() == null ? "relation-draft-1".equals(manifest.promptVersion())
                    : RELATION_EVIDENCE_PROMPT_VERSION.equals(manifest.promptVersion()) && FactDraftSources.valid(manifest.relationEvidence()))
                && manifest.training() == null && manifest.evaluation() == null && manifest.contextChecks() == null
                && manifest.materialQa() == null && manifest.preparationChecks() == null && manifest.materialDraftSources() == null
                && manifest.expressionType() == null && manifest.preparationPlan() == null && manifest.trainingDraft() == null
                && manifest.expressionChecks() == null && manifest.comparisonInput() == null && manifest.comparisonChecks() == null
                && manifest.reviewDraft() == null && manifest.cardDraftType() == null
                && validRelationSources(manifest.sources());
    }

    public static boolean validRelationReviewInput(Manifest manifest) {
        if (manifest != null && manifest.sourceQa() != null) return false;
        return manifest != null && manifest.relationAiFeedback() == null && manifest.purpose() == Purpose.RELATION_DRAFT
                && manifest.relationEvidence() == null && manifest.factDraft() == null
                && validFixedInstruction(manifest.instruction()) && RELATION_REVIEW_PROMPT_VERSION.equals(manifest.promptVersion())
                && manifest.training() == null && manifest.evaluation() == null && manifest.contextChecks() == null
                && manifest.materialQa() == null && manifest.preparationChecks() == null && manifest.materialDraftSources() == null
                && manifest.expressionType() == null && manifest.preparationPlan() == null && manifest.trainingDraft() == null
                && manifest.expressionChecks() == null && manifest.comparisonInput() == null && manifest.comparisonChecks() == null
                && manifest.cardDraftType() == null && validRelationSources(manifest.sources())
                && ReviewDraftSources.valid(manifest.reviewDraft());
    }

    public static boolean validRelationAiFeedbackInput(Manifest manifest) {
        if (manifest != null && manifest.sourceQa() != null) return false;
        return manifest != null && manifest.purpose() == Purpose.RELATION_DRAFT
                && manifest.relationEvidence() == null && manifest.factDraft() == null && manifest.reviewDraft() == null
                && validFixedInstruction(manifest.instruction()) && RELATION_AI_FEEDBACK_PROMPT_VERSION.equals(manifest.promptVersion())
                && manifest.training() == null && manifest.evaluation() == null && manifest.contextChecks() == null
                && manifest.materialQa() == null && manifest.preparationChecks() == null && manifest.materialDraftSources() == null
                && manifest.expressionType() == null && manifest.preparationPlan() == null && manifest.trainingDraft() == null
                && manifest.expressionChecks() == null && manifest.comparisonInput() == null && manifest.comparisonChecks() == null
                && manifest.cardDraftType() == null && validRelationSources(manifest.sources())
                && RelationAiFeedbackSources.validSnapshot(manifest.relationAiFeedback());
    }

    private static boolean validRelationSources(List<Source> sources) {
        if (sources == null || sources.size() < 2 || sources.size() > 5) return false;
        var itemIds = new HashSet<String>();
        var versionIds = new HashSet<String>();
        int concepts = 0;
        for (Source source : sources) {
            if (source == null || !knowledgeKind(source.kind()) || !uuid(source.itemId()) || !uuid(source.versionId())
                    || !itemIds.add(source.itemId()) || !versionIds.add(source.versionId()) || source.number() < 1
                    || source.title() == null || source.title().isBlank() || source.title().length() > 200
                    || source.title().indexOf('\0') >= 0 || !validUnicode(source.title())
                    || source.body() == null || source.body().isBlank() || source.body().length() > 100_000
                    || source.body().indexOf('\0') >= 0 || !validUnicode(source.body())) return false;
            if ("CONCEPT".equals(source.kind())) concepts++;
        }
        return concepts >= 2;
    }

    public static boolean validDraftInput(Manifest manifest) {
        if (manifest != null && manifest.sourceQa() != null) return false;
        if (manifest == null || manifest.relationAiFeedback() != null || manifest.relationEvidence() != null || manifest.factDraft() != null || manifest.comparisonInput() != null || manifest.comparisonChecks() != null
                || manifest.cardDraftType() != null) return false;
        if (manifest.reviewDraft() != null) return validReviewDraftInput(manifest);
        if (manifest.trainingDraft() != null) {
            return manifest.purpose() == Purpose.KNOWLEDGE_DRAFT && validFixedInstruction(manifest.instruction())
                    && manifest.sources() != null && manifest.sources().isEmpty()
                    && manifest.training() == null && manifest.evaluation() == null && manifest.contextChecks() == null
                    && manifest.materialQa() == null && manifest.preparationChecks() == null && manifest.materialDraftSources() == null
                    && manifest.expressionType() == null && manifest.preparationPlan() == null && manifest.expressionChecks() == null
                    && TRAINING_DRAFT_PROMPT_VERSION.equals(manifest.promptVersion())
                    && TrainingDraftSources.valid(manifest.trainingDraft());
        }
        if (!draftPurpose(manifest.purpose()) || manifest.instruction() == null
                || manifest.instruction().length() > 1000 || !manifest.instruction().equals(manifest.instruction().strip())
                || manifest.sources() == null || manifest.sources().size() > 5
                || manifest.training() != null || manifest.evaluation() != null || manifest.contextChecks() != null
                || manifest.materialQa() != null || manifest.expressionType() != null || manifest.preparationPlan() != null
                || !promptVersion(manifest).equals(manifest.promptVersion())) return false;
        if (manifest.materialDraftSources() == null) {
            if (manifest.sources().isEmpty()) return false;
        } else if (!validMaterialDraftSources(manifest.materialDraftSources())
                || manifest.sources().size() > 5 - manifest.materialDraftSources().size()
                || manifest.instruction().indexOf('\0') >= 0) return false;
        if (manifest.expressionChecks() != null && (manifest.expressionChecks().isEmpty()
                || manifest.expressionChecks().size() > 5 || !validFixedInstruction(manifest.instruction()))) return false;
        var ids = new HashSet<String>();
        int preparationIndex = 0, expressionIndex = 0;
        for (Source source : manifest.sources()) {
            if (source == null || !uuid(source.itemId()) || !uuid(source.versionId()) || !ids.add(source.itemId())
                    || source.number() < 1 || source.title() == null || source.title().isBlank()
                    || source.body() == null || source.body().isBlank()) return false;
            if (knowledgeKind(source.kind())) {
                if (preparationIndex != 0) return false;
                continue;
            }
            if (!preparationKind(source.kind()) || manifest.preparationChecks() == null
                    || preparationIndex >= manifest.preparationChecks().size()) return false;
            var check = manifest.preparationChecks().get(preparationIndex++);
            if (check == null || check.kind() == null || !source.kind().equals(check.kind().name())
                    || !source.itemId().equals(check.itemId()) || !source.versionId().equals(check.versionId())
                    || check.state() != PreparationDtos.SourceState.CURRENT || !validGeneration(check.sourceGeneration())
                    || ("FACT".equals(source.kind()) ? !validGeneration(check.projectGeneration()) : check.projectGeneration() != null)) {
                return false;
            }
            if ("EXPRESSION".equals(source.kind())) {
                if (manifest.expressionChecks() == null || expressionIndex >= manifest.expressionChecks().size()
                        || !validExpressionSourceCheck(check, manifest.expressionChecks().get(expressionIndex++))) return false;
            }
        }
        if (expressionIndex == 0 ? manifest.expressionChecks() != null
                : expressionIndex != manifest.expressionChecks().size()) return false;
        return preparationIndex == 0 ? manifest.preparationChecks() == null
                : preparationIndex == manifest.preparationChecks().size() && manifest.instruction().indexOf('\0') < 0;
    }

    public static boolean validReviewDraftInput(Manifest manifest) {
        if (manifest != null && manifest.sourceQa() != null) return false;
        return manifest != null && manifest.relationAiFeedback() == null && manifest.relationEvidence() == null && manifest.factDraft() == null && manifest.purpose() == Purpose.KNOWLEDGE_DRAFT
                && validFixedInstruction(manifest.instruction()) && manifest.sources() != null && manifest.sources().isEmpty()
                && manifest.training() == null && manifest.evaluation() == null && manifest.contextChecks() == null
                && manifest.materialQa() == null && manifest.preparationChecks() == null && manifest.materialDraftSources() == null
                && manifest.expressionType() == null && manifest.preparationPlan() == null && manifest.trainingDraft() == null
                && manifest.expressionChecks() == null && manifest.comparisonInput() == null && manifest.comparisonChecks() == null
                && manifest.cardDraftType() == null
                && REVIEW_DRAFT_PROMPT_VERSION.equals(manifest.promptVersion())
                && ReviewDraftSources.valid(manifest.reviewDraft());
    }

    public static boolean validCardDraftInput(Manifest manifest) {
        if (manifest != null && manifest.sourceQa() != null) return false;
        return manifest != null && manifest.relationAiFeedback() == null && manifest.relationEvidence() == null && manifest.factDraft() == null && manifest.purpose() == Purpose.MEMORY_CARD_DRAFT
                && cardDraftKind(manifest.cardDraftType()) && validFixedInstruction(manifest.instruction())
                && CARD_DRAFT_PROMPT_VERSION.equals(manifest.promptVersion())
                && manifest.training() == null && manifest.evaluation() == null && manifest.contextChecks() == null
                && manifest.materialQa() == null && manifest.preparationChecks() == null && manifest.materialDraftSources() == null
                && manifest.expressionType() == null && manifest.preparationPlan() == null && manifest.trainingDraft() == null
                && manifest.expressionChecks() == null && manifest.comparisonInput() == null && manifest.comparisonChecks() == null
                && manifest.reviewDraft() == null && validCardDraftSources(manifest.sources());
    }

    private static boolean validCardDraftSources(List<Source> sources) {
        if (sources == null || sources.isEmpty() || sources.size() > 5) return false;
        var itemIds = new HashSet<String>();
        var versionIds = new HashSet<String>();
        for (Source source : sources) {
            if (source == null || !knowledgeKind(source.kind()) || !uuid(source.itemId()) || !uuid(source.versionId())
                    || !itemIds.add(source.itemId()) || !versionIds.add(source.versionId()) || source.number() < 1
                    || source.title() == null || source.title().isBlank() || source.title().length() > 200
                    || source.title().indexOf('\0') >= 0 || !validUnicode(source.title())
                    || source.body() == null || source.body().isBlank() || source.body().length() > 100_000
                    || source.body().indexOf('\0') >= 0 || !validUnicode(source.body())) return false;
        }
        return true;
    }

    private static boolean cardDraftKind(MemoryDtos.Kind kind) {
        return kind == MemoryDtos.Kind.QA || kind == MemoryDtos.Kind.CLOZE;
    }

    public static boolean validJdRequirementsInput(Manifest manifest) {
        if (manifest != null && manifest.sourceQa() != null) return false;
        return manifest != null && manifest.relationAiFeedback() == null && manifest.relationEvidence() == null && manifest.factDraft() == null && manifest.purpose() == Purpose.JD_REQUIREMENT_DRAFT
                && validFixedInstruction(manifest.instruction())
                && JD_REQUIREMENTS_PROMPT_VERSION.equals(manifest.promptVersion())
                && manifest.sources() != null && manifest.sources().size() == 1
                && manifest.preparationChecks() != null && manifest.preparationChecks().size() == 1
                && manifest.training() == null && manifest.evaluation() == null && manifest.contextChecks() == null
                && manifest.materialQa() == null && manifest.materialDraftSources() == null && manifest.expressionType() == null
                && manifest.preparationPlan() == null && manifest.trainingDraft() == null && manifest.expressionChecks() == null
                && manifest.comparisonInput() == null && manifest.comparisonChecks() == null
                && manifest.reviewDraft() == null && manifest.cardDraftType() == null
                && validJdRequirementsSource(manifest.sources().getFirst(), manifest.preparationChecks().getFirst());
    }

    private static boolean validJdRequirementsSource(Source source, PreparationDtos.SourceCheck check) {
        return source != null && "TARGET".equals(source.kind()) && uuid(source.itemId()) && uuid(source.versionId())
                && source.number() >= 1 && source.title() != null && !source.title().isBlank() && source.title().length() <= 200
                && source.title().indexOf('\0') < 0 && validUnicode(source.title())
                && source.body() != null && source.body().length() <= 50_000
                && source.body().codePoints().anyMatch(point -> !Character.isWhitespace(point) && !Character.isSpaceChar(point))
                && source.body().indexOf('\0') < 0 && validUnicode(source.body())
                && check != null && check.kind() == PreparationDtos.Kind.TARGET
                && source.itemId().equals(check.itemId()) && source.versionId().equals(check.versionId())
                && check.state() == PreparationDtos.SourceState.CURRENT && validGeneration(check.sourceGeneration())
                && check.projectGeneration() == null;
    }

    public static boolean validRewriteInput(Manifest manifest) {
        if (manifest != null && manifest.sourceQa() != null) return false;
        return manifest != null && manifest.relationAiFeedback() == null && manifest.relationEvidence() == null && manifest.purpose() == Purpose.ANSWER_REWRITE
                && "".equals(manifest.instruction()) && REWRITE_PROMPT_VERSION.equals(manifest.promptVersion())
                && manifest.sources() != null && manifest.sources().isEmpty()
                && manifest.training() == null && manifest.contextChecks() == null && manifest.materialQa() == null
                && manifest.preparationChecks() == null && manifest.materialDraftSources() == null
                && manifest.expressionType() == null && manifest.preparationPlan() == null && manifest.trainingDraft() == null
                && manifest.expressionChecks() == null && manifest.comparisonInput() == null && manifest.comparisonChecks() == null
                && manifest.reviewDraft() == null && manifest.cardDraftType() == null && manifest.factDraft() == null
                && RewriteSources.valid(manifest.evaluation());
    }

    public static boolean validFactDraftInput(Manifest manifest) {
        if (manifest != null && manifest.sourceQa() != null) return false;
        return manifest != null && manifest.relationAiFeedback() == null && manifest.relationEvidence() == null && manifest.purpose() == Purpose.FACT_DRAFT
                && validFixedInstruction(manifest.instruction()) && FACT_DRAFT_PROMPT_VERSION.equals(manifest.promptVersion())
                && manifest.sources() != null && manifest.sources().isEmpty()
                && manifest.training() == null && manifest.evaluation() == null && manifest.contextChecks() == null
                && manifest.materialQa() == null && manifest.preparationChecks() == null && manifest.materialDraftSources() == null
                && manifest.expressionType() == null && manifest.preparationPlan() == null && manifest.trainingDraft() == null
                && manifest.expressionChecks() == null && manifest.comparisonInput() == null && manifest.comparisonChecks() == null
                && manifest.reviewDraft() == null && manifest.cardDraftType() == null && FactDraftSources.valid(manifest.factDraft());
    }

    private static boolean validExpressionSourceCheck(PreparationDtos.SourceCheck source,
                                                      PreparationDtos.ExpressionCheck check) {
        if (check == null || !source.equals(check.expression()) || check.factChecks() == null
                || check.factChecks().isEmpty() || check.factChecks().size() > 50) return false;
        var itemIds = new HashSet<String>();
        var versionIds = new HashSet<String>();
        for (var fact : check.factChecks()) {
            if (fact == null || fact.kind() != PreparationDtos.Kind.FACT || !uuid(fact.itemId()) || !uuid(fact.versionId())
                    || !itemIds.add(fact.itemId()) || !versionIds.add(fact.versionId())
                    || fact.state() != PreparationDtos.SourceState.CURRENT
                    || !validGeneration(fact.sourceGeneration()) || !validGeneration(fact.projectGeneration())) return false;
        }
        return true;
    }

    public static boolean validExpressionInput(Manifest manifest) {
        if (manifest != null && manifest.sourceQa() != null) return false;
        if (manifest == null || manifest.relationAiFeedback() != null || manifest.relationEvidence() != null || manifest.factDraft() != null || manifest.purpose() != Purpose.EXPRESSION_DRAFT || manifest.expressionType() == null
                || manifest.instruction() == null || manifest.instruction().length() > 1000
                || manifest.instruction().indexOf('\0') >= 0 || !validUnicode(manifest.instruction())
                || !manifest.instruction().equals(manifest.instruction().strip())
                || manifest.sources() == null || manifest.sources().isEmpty() || manifest.sources().size() > 5
                || manifest.preparationChecks() == null || manifest.preparationChecks().size() != manifest.sources().size()
                || manifest.training() != null || manifest.evaluation() != null || manifest.contextChecks() != null
                || manifest.materialQa() != null || manifest.materialDraftSources() != null || manifest.preparationPlan() != null
                || manifest.trainingDraft() != null || manifest.expressionChecks() != null
                || manifest.comparisonInput() != null || manifest.comparisonChecks() != null || manifest.reviewDraft() != null
                || manifest.cardDraftType() != null
                || !promptVersion(Purpose.EXPRESSION_DRAFT).equals(manifest.promptVersion())) return false;
        var ids = new HashSet<String>();
        for (int index = 0; index < manifest.sources().size(); index++) {
            Source source = manifest.sources().get(index);
            var check = manifest.preparationChecks().get(index);
            if (source == null || !"FACT".equals(source.kind()) || !uuid(source.itemId()) || !uuid(source.versionId())
                    || !ids.add(source.itemId()) || source.number() < 1 || source.title() == null || source.title().isBlank()
                    || source.body() == null || source.body().isBlank() || check == null || check.kind() != PreparationDtos.Kind.FACT
                    || !source.itemId().equals(check.itemId()) || !source.versionId().equals(check.versionId())
                    || check.state() != PreparationDtos.SourceState.CURRENT || !validGeneration(check.sourceGeneration())
                    || !validGeneration(check.projectGeneration())) return false;
        }
        return true;
    }

    public static boolean validPreparationPlanInput(Manifest manifest) {
        if (manifest != null && manifest.sourceQa() != null) return false;
        if (manifest == null || manifest.relationAiFeedback() != null || manifest.relationEvidence() != null || manifest.factDraft() != null || manifest.purpose() != Purpose.PREPARATION_PLAN
                || !validFixedInstruction(manifest.instruction()) || manifest.preparationPlan() == null
                || manifest.sources() == null || manifest.sources().size() != 1
                || manifest.preparationChecks() == null || manifest.preparationChecks().size() != 1
                || manifest.training() != null || manifest.evaluation() != null || manifest.contextChecks() != null
                || manifest.materialQa() != null || manifest.materialDraftSources() != null || manifest.expressionType() != null
                || manifest.trainingDraft() != null || manifest.expressionChecks() != null
                || manifest.comparisonInput() != null || manifest.comparisonChecks() != null || manifest.reviewDraft() != null
                || manifest.cardDraftType() != null
                || !promptVersion(Purpose.PREPARATION_PLAN).equals(manifest.promptVersion())) return false;
        Source source = manifest.sources().getFirst();
        var check = manifest.preparationChecks().getFirst();
        if (source == null || !"TARGET".equals(source.kind()) || !uuid(source.itemId()) || !uuid(source.versionId())
                || source.number() < 1 || source.title() == null || source.title().isBlank()
                || source.body() == null || source.body().isBlank() || check == null || check.kind() != PreparationDtos.Kind.TARGET
                || !source.itemId().equals(check.itemId()) || !source.versionId().equals(check.versionId())
                || check.state() != PreparationDtos.SourceState.CURRENT || !validGeneration(check.sourceGeneration())
                || check.projectGeneration() != null) return false;
        try {
            PreparationPlanRules.input(manifest.preparationPlan());
            return true;
        } catch (ApiException exception) {
            return false;
        }
    }

    private static boolean validMaterialDraftSources(List<AiExcerpt> excerpts) {
        if (excerpts == null || excerpts.isEmpty() || excerpts.size() > 5) return false;
        var ids = new HashSet<String>();
        for (AiExcerpt excerpt : excerpts) {
            if (excerpt == null || !uuid(excerpt.excerptId()) || !uuid(excerpt.attachmentId()) || !ids.add(excerpt.excerptId())
                    || excerpt.fileName() == null || excerpt.fileName().isBlank()
                    || excerpt.sha256() == null || !excerpt.sha256().matches("[0-9a-f]{64}")
                    || excerpt.locator() == null || excerpt.locator().isBlank()
                    || excerpt.text() == null || excerpt.text().isBlank() || excerpt.text().indexOf('\0') >= 0) return false;
            if ("MANUAL".equals(excerpt.origin())) {
                if (excerpt.runId() != null || excerpt.segmentId() != null
                        || excerpt.startOffset() != null || excerpt.endOffset() != null) return false;
            } else if (!"PARSED".equals(excerpt.origin()) || !uuid(excerpt.runId()) || !uuid(excerpt.segmentId())
                    || excerpt.startOffset() == null || excerpt.endOffset() == null || excerpt.startOffset() < 0
                    || excerpt.endOffset() <= excerpt.startOffset()
                    || excerpt.endOffset() - excerpt.startOffset() != excerpt.text().length()) return false;
        }
        return true;
    }

    public String hash(Manifest manifest) {
        return manifestDigest.hash(manifest);
    }

    public boolean current(String owner, Manifest manifest) {
        return current(owner, manifest, null);
    }

    public boolean current(String owner, Manifest manifest, Instant frozenAt) {
        if (manifest == null || manifest.purpose() == null) return false;
        List<String> materialIds = directAndPreparationMaterialIds(owner, manifest);
        // 所有专用分支共用完整材料代次守卫，避免早返回放过旧许可或多余 checks。
        if (materialIds == null || !materialLifecycle.currentAiChecks(owner, materialIds, manifest.materialChecks())) return false;
        if (!currentKnowledgeMaterialChecks(owner, manifest)) return false;
        if (manifest.purpose() == Purpose.FOLLOW_UP && !trainingMaterialsAllowed(owner, manifest.training())) return false;
        if (manifest.sourceQa() != null || manifest.purpose() == Purpose.SOURCE_QA) {
            return validSourceQaInput(manifest) && sourceQa.current(owner, manifest.sourceQa(), frozenAt);
        }
        // 新依据只能进入关系专用分支，旧用途不能借非空尾字段绕过原来源边界。
        if (manifest.relationAiFeedback() != null && manifest.purpose() != Purpose.RELATION_DRAFT) return false;
        if (manifest.relationEvidence() != null && manifest.purpose() != Purpose.RELATION_DRAFT) return false;
        if (manifest.purpose() == Purpose.ANSWER_REWRITE) {
            return validRewriteInput(manifest) && rewrites.current(owner, manifest.evaluation());
        }
        if (manifest.factDraft() != null || manifest.purpose() == Purpose.FACT_DRAFT) {
            // 最先守住模式互斥，防止新窄投影混入旧用途而跳过原有来源核对。
            return validFactDraftInput(manifest) && factDrafts.current(owner, manifest.factDraft());
        }
        if (manifest.purpose() == Purpose.JD_REQUIREMENT_DRAFT) {
            if (!validJdRequirementsInput(manifest)) return false;
            Source source = manifest.sources().getFirst();
            var version = preparation.readJd(owner, source.itemId(), source.versionId());
            // JD 正文与目标代次分别核对；归档后恢复或内容改后改回不能恢复旧授权。
            return version != null && source.equals(preparationSource(version))
                    && version.check().equals(manifest.preparationChecks().getFirst());
        }
        if (manifest.cardDraftType() != null || manifest.purpose() == Purpose.MEMORY_CARD_DRAFT) {
            if (!validCardDraftInput(manifest)) return false;
            for (Source source : manifest.sources()) {
                var item = knowledge.findItem(owner, source.itemId());
                if (item == null || !"ACTIVE".equals(item.status()) || !source.kind().equals(item.kind())
                        || !source.versionId().equals(item.currentVersionId())) return false;
                var version = knowledge.findVersion(owner, source.itemId(), source.versionId());
                if (version == null || !source.equals(new Source(item.id(), version.id(), version.number(), item.kind(),
                        version.title(), version.body())) || !knowledgeReady(owner, source, frozenAt)) return false;
            }
            return true;
        }
        if (manifest.reviewDraft() != null && manifest.purpose() != Purpose.RELATION_DRAFT) {
            // 知识整理沿原复盘规则；关系模式留到下方先核知识，不能绕过概念端点的当前性。
            return validReviewDraftInput(manifest) && reviewDrafts.current(owner, manifest.reviewDraft());
        }
        if (manifest.purpose() == Purpose.ANSWER_COMPARISON) {
            return validComparisonInput(manifest)
                    && comparisons.current(owner, manifest.comparisonInput(), manifest.comparisonChecks());
        }
        if (manifest.comparisonInput() != null || manifest.comparisonChecks() != null) return false;
        if (manifest.purpose() == Purpose.RELATION_DRAFT) {
            if (!validRelationInput(manifest)) return false;
            for (Source source : manifest.sources()) {
                var item = knowledge.findItem(owner, source.itemId());
                if (item == null || !"ACTIVE".equals(item.status()) || !source.kind().equals(item.kind())
                        || !source.versionId().equals(item.currentVersionId())) return false;
                var version = knowledge.findVersion(owner, source.itemId(), source.versionId());
                if (version == null || !source.equals(new Source(item.id(), version.id(), version.number(), item.kind(),
                        version.title(), version.body())) || !knowledgeReady(owner, source, frozenAt)) return false;
            }
            if (manifest.relationAiFeedback() != null) return relationAiFeedbackDrafts.current(owner, manifest.relationAiFeedback());
            if (manifest.reviewDraft() != null) {
                try {
                    // 新关系须区分正常过期与来源代次回退；结构或绑定损坏不能伪装成普通过期。
                    return reviewDrafts.state(owner, manifest.reviewDraft()) == OriginState.CURRENT;
                } catch (ApiException exception) {
                    // 后台权限阻断只使来源失效，以保留已发生费用；前台独立的访问守卫仍须拒绝读取。
                    if ("AI_EVALUATION_ACCESS_REQUIRED".equals(exception.code())
                            || "AI_EARLY_FEEDBACK_CONFIRMATION_REQUIRED".equals(exception.code())) return false;
                    throw exception;
                }
            }
            return manifest.relationEvidence() == null || relationEvidence.current(owner, manifest.relationEvidence());
        }
        if (!draftPurpose(manifest.purpose()) && manifest.expressionChecks() != null) return false;
        if (manifest.trainingDraft() != null) {
            // 已提交片段的可用性由其确切来源判断，不借用会话代次或最新答案指针。
            return validDraftInput(manifest) && trainingDrafts.current(owner, manifest.trainingDraft());
        }
        if (manifest.purpose() == Purpose.PREPARATION_PLAN) {
            if (!validPreparationPlanInput(manifest)) return false;
            Source source = manifest.sources().getFirst();
            var version = preparation.read(owner, PreparationDtos.Kind.TARGET, source.itemId(), source.versionId());
            return version != null && source.equals(preparationSource(version))
                    && version.check().equals(manifest.preparationChecks().getFirst());
        }
        if (manifest.preparationPlan() != null) return false;
        if (manifest.purpose() == Purpose.EXPRESSION_DRAFT) {
            if (!validExpressionInput(manifest)) return false;
            for (int index = 0; index < manifest.sources().size(); index++) {
                Source source = manifest.sources().get(index);
                var version = preparation.read(owner, PreparationDtos.Kind.FACT, source.itemId(), source.versionId());
                if (version == null || !source.equals(preparationSource(version))
                        || !version.check().equals(manifest.preparationChecks().get(index))) return false;
            }
            return true;
        }
        if (manifest.expressionType() != null) return false;
        if (draftPurpose(manifest.purpose())) {
            if (!validDraftInput(manifest)) return false;
            int preparationIndex = 0, expressionIndex = 0;
            for (Source source : manifest.sources()) {
                if (knowledgeKind(source.kind())) {
                    var item = knowledge.findItem(owner, source.itemId());
                    if (item == null || !"ACTIVE".equals(item.status()) || !source.kind().equals(item.kind())
                            || !source.versionId().equals(item.currentVersionId())
                            || knowledge.findVersion(owner, source.itemId(), source.versionId()) == null
                            || !knowledgeReady(owner, source, frozenAt)) return false;
                } else if ("EXPRESSION".equals(source.kind())) {
                    var expression = preparation.readExpression(owner, source.itemId(), source.versionId());
                    // 依据即使恢复到可用状态，代次也必须与本次授权一致。
                    if (expression == null || !source.equals(preparationSource(expression.version()))
                            || !expression.version().check().equals(manifest.preparationChecks().get(preparationIndex++))
                            || !expression.check().equals(manifest.expressionChecks().get(expressionIndex++))) return false;
                } else {
                    var version = preparation.read(owner, PreparationDtos.Kind.valueOf(source.kind()), source.itemId(), source.versionId());
                    if (version == null || !source.equals(preparationSource(version))
                            || !version.check().equals(manifest.preparationChecks().get(preparationIndex++))) return false;
                }
            }
            return manifest.materialDraftSources() == null || materials.current(owner, manifest.materialDraftSources());
        }
        if (manifest.preparationChecks() != null || manifest.materialDraftSources() != null) return false;
        if (manifest.purpose() == Purpose.MATERIAL_QA) {
            MaterialQaInput input = manifest.materialQa();
            if (!"".equals(manifest.instruction()) || manifest.sources() == null || !manifest.sources().isEmpty()
                    || manifest.training() != null || manifest.evaluation() != null || manifest.contextChecks() != null
                    || !promptVersion(Purpose.MATERIAL_QA).equals(manifest.promptVersion())
                    || input == null || input.mode() == null || !validMaterialQuestion(input.question()) || input.excerpts() == null) {
                return false;
            }
            if (input.mode() == MaterialQaMode.GENERAL) return input.excerpts().isEmpty();
            return !input.excerpts().isEmpty() && input.excerpts().size() <= 5 && materials.current(owner, input.excerpts());
        }
        if (manifest.materialQa() != null) return false;
        if (manifest.purpose() == Purpose.ANSWER_EVALUATION) {
            return manifest.sources() != null && manifest.sources().isEmpty() && manifest.training() == null
                    && evaluations.current(owner, manifest.evaluation());
        }
        if (manifest.purpose() == Purpose.FOLLOW_UP) {
            return manifest.sources() != null && manifest.sources().isEmpty()
                    && promptVersion(Purpose.FOLLOW_UP).equals(manifest.promptVersion())
                    && training.current(owner, manifest.training(), manifest.contextChecks());
        }
        return false;
    }

    public void requireCurrent(String owner, Manifest manifest) {
        requireCurrent(owner, manifest, null);
    }

    public void requireCurrent(String owner, Manifest manifest, Instant frozenAt) {
        if (!current(owner, manifest, frozenAt)) {
            throw new ApiException(409, "AI_SOURCE_CHANGED", "来源已改版或不可用，请重新预览当前资料后再授权。");
        }
    }

    private boolean knowledgeReady(String owner, Source source, Instant frozenAt) {
        // 旧任务保留原冻结点；影响创建或处理与其等时、晚于它，都不能恢复旧资格。
        return frozenAt == null
                ? !revalidation.pending(owner, source.itemId(), source.versionId())
                : !revalidation.reviewRequired(owner, source.itemId(), source.versionId(), frozenAt);
    }

    public static long price(long tokens, long microsPerMillion) {
        if (tokens < 0 || microsPerMillion < 0) throw new ArithmeticException("Negative usage or price");
        return BigInteger.valueOf(tokens).multiply(BigInteger.valueOf(microsPerMillion))
                .add(BigInteger.valueOf(999_999L)).divide(BigInteger.valueOf(1_000_000L)).longValueExact();
    }

    private static boolean uuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException exception) { return false; }
    }

    private static boolean validMaterialQuestion(String question) {
        return question != null && !question.isBlank() && question.length() <= 2000
                && question.indexOf('\0') < 0 && question.equals(question.strip());
    }

    public static boolean knowledgeKind(String kind) { return "NOTE".equals(kind) || "CONCEPT".equals(kind); }

    private static boolean preparationKind(String kind) {
        return "TARGET".equals(kind) || "PROJECT".equals(kind) || "FACT".equals(kind) || "EXPRESSION".equals(kind);
    }

    private static boolean draftPurpose(Purpose purpose) {
        return purpose == Purpose.KNOWLEDGE_DRAFT || purpose == Purpose.QUESTION_DRAFT;
    }

    private static boolean validGeneration(Long value) { return value != null && value >= 0 && value <= 9_007_199_254_740_991L; }

    private static boolean validUnicode(String value) {
        return value.codePoints().noneMatch(codePoint -> codePoint >= 0xD800 && codePoint <= 0xDFFF);
    }

    private static boolean validFixedInstruction(String value) {
        return value != null && value.length() <= 1000 && value.indexOf('\0') < 0 && validUnicode(value);
    }

    private static Source preparationSource(PreparationDtos.AiVersion version) {
        return new Source(version.itemId(), version.versionId(), version.number(), version.kind().name(), version.title(), version.body());
    }

    public static ReviewDraftContentProjection reviewDraftContent(String instruction, ReviewDraftDtos.Input input) {
        // 固定 Input 中的修订、决定及限制均明示外发；预览、费用与网关使用同一封闭投影。
        return new ReviewDraftContentProjection(Purpose.KNOWLEDGE_DRAFT, instruction, input);
    }

    public record ReviewDraftContentProjection(Purpose purpose, String instruction, ReviewDraftDtos.Input reviewDraft) {}

    public static CardDraftContentProjection cardDraftContent(String instruction, MemoryDtos.Kind kind, List<Source> sources) {
        return new CardDraftContentProjection(Purpose.MEMORY_CARD_DRAFT, instruction, kind, sources);
    }

    public record CardDraftContentProjection(Purpose purpose, String instruction, MemoryDtos.Kind cardDraftType,
                                              List<Source> sources) {}

    public static JdRequirementsContentProjection jdRequirementsContent(String instruction, List<Source> sources) {
        return new JdRequirementsContentProjection(Purpose.JD_REQUIREMENT_DRAFT, instruction, sources);
    }

    public record JdRequirementsContentProjection(Purpose purpose, String instruction, List<Source> sources) {}

    public static FactDraftContentProjection factDraftContent(String instruction, FactDraftDtos.Input input) {
        return new FactDraftContentProjection(Purpose.FACT_DRAFT, instruction, input);
    }

    public record FactDraftContentProjection(Purpose purpose, String instruction, FactDraftDtos.Input factDraft) {}

    public static RewriteContentProjection rewriteContent(EvaluationInput input) {
        return new RewriteContentProjection(Purpose.ANSWER_REWRITE, input);
    }

    public record RewriteContentProjection(Purpose purpose, EvaluationInput evaluation) {}

    public static RelationEvidenceContentProjection relationEvidenceContent(String instruction, List<Source> sources,
                                                                             FactDraftDtos.Input evidence) {
        return new RelationEvidenceContentProjection(Purpose.RELATION_DRAFT, instruction, sources, evidence);
    }

    public record RelationEvidenceContentProjection(Purpose purpose, String instruction, List<Source> sources,
                                                    FactDraftDtos.Input relationEvidence) {}

    public static RelationReviewContentProjection relationReviewContent(String instruction, List<Source> sources,
                                                                         ReviewDraftDtos.Input input) {
        return new RelationReviewContentProjection(Purpose.RELATION_DRAFT, instruction, sources, input);
    }

    public record RelationReviewContentProjection(Purpose purpose, String instruction, List<Source> sources,
                                                  ReviewDraftDtos.Input reviewDraft) {}

    public static RelationAiFeedbackContentProjection relationAiFeedbackContent(String instruction, List<Source> sources,
                                                                                RelationAiFeedbackEvidenceDtos.Snapshot input) {
        return new RelationAiFeedbackContentProjection(Purpose.RELATION_DRAFT, instruction, sources, input);
    }

    public record RelationAiFeedbackContentProjection(Purpose purpose, String instruction, List<Source> sources,
                                                       RelationAiFeedbackEvidenceDtos.Snapshot relationAiFeedback) {}

    private record SourceContent(String instruction, List<Source> sources) {}
    private record RelationDraftContent(Purpose purpose, String instruction, List<Source> sources) {}
    private record ExpressionSourceContent(Purpose purpose, String instruction, List<Source> sources) {}
    private record MaterialDraftContent(Purpose purpose, String instruction, List<Source> sources,
                                        List<AiExcerpt> materialDraftSources) {}
    private record ExpressionDraftContent(Purpose purpose, String instruction, PreparationDtos.ExpressionType expressionType,
                                          List<Source> sources) {}
    private record PreparationPlanContentProjection(Purpose purpose, String instruction, List<Source> sources,
                                                     PreparationPlanInput preparationPlan) {}
    private record TrainingDraftContentProjection(Purpose purpose, String instruction, TrainingDraftInput trainingDraft) {}
}
