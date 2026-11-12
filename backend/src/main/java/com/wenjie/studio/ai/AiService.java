package com.wenjie.studio.ai;

import com.wenjie.studio.ai.AiDtos.*;
import com.wenjie.studio.ai.AiRows.*;
import com.wenjie.studio.bank.QuestionService;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyMapper;
import com.wenjie.studio.common.IdempotencyService;
import com.wenjie.studio.knowledge.KnowledgeDtos.CreateItem;
import com.wenjie.studio.knowledge.KnowledgeDtos.CreateRelation;
import com.wenjie.studio.knowledge.KnowledgeDtos.Relation;
import com.wenjie.studio.knowledge.KnowledgeDtos.RelationType;
import com.wenjie.studio.knowledge.RelationAiFeedbackEvidenceDtos;
import com.wenjie.studio.knowledge.KnowledgeService;
import com.wenjie.studio.knowledge.RelationSupplementDtos;
import com.wenjie.studio.knowledge.RelationSupplementService;
import com.wenjie.studio.material.MaterialSources;
import com.wenjie.studio.memory.MemoryDtos;
import com.wenjie.studio.memory.MemoryService;
import com.wenjie.studio.planning.PlanningDtos;
import com.wenjie.studio.planning.PlanningService;
import com.wenjie.studio.prepare.PreparationDtos;
import com.wenjie.studio.prepare.PreparationService;
import com.wenjie.studio.prepare.PreparationSources;
import com.wenjie.studio.review.ReviewDraftSources;
import com.wenjie.studio.training.TrainingAccessGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.Function;

@Service
public class AiService {
    private static final long MAX_SAFE = 9_007_199_254_740_991L;
    private static final Set<String> SENT = Set.of("RUNNING", "VALIDATING", "CANCEL_REQUESTED");
    private static final Set<String> BEFORE_SEND_FAILURES = Set.of("AI_CONFIG_CHANGED_BEFORE_SEND", "AI_INPUT_LIMIT_BEFORE_SEND",
            "AI_DISABLED_BEFORE_SEND", "AI_NOT_CONFIGURED_BEFORE_SEND", "AI_REQUEST_INVALID_BEFORE_SEND", "AI_CANCELED_BEFORE_SEND");
    private static final Set<String> UNKNOWN_OUTCOMES = Set.of("AI_TIMEOUT_UNKNOWN", "AI_CANCELED_UNKNOWN", "AI_NETWORK_UNKNOWN");
    private static final JsonMapper JD_DECISIONS = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(8192).maxNumberLength(16).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final String FACT_SOURCE_TITLE = "AI 事实候选";
    private final AiMapper mapper;
    private final AiProperties properties;
    private final AiSources sources;
    private final AiAuthorizations authorizations;
    private final TrainingAccessGuard trainingAccess;
    private final TrainingFollowUps training;
    private final TrainingEvaluations evaluations;
    private final EvaluationMapper evaluationMapper;
    private final RewriteSources rewrites;
    private final RewriteMapper rewriteMapper;
    private final ComparisonSources comparisonSources;
    private final ComparisonMapper comparisonMapper;
    private final ModelGateway gateway;
    private final KnowledgeService knowledge;
    private final RelationSupplementService relationSupplements;
    private final QuestionService questions;
    private final PreparationSources preparation;
    private final PreparationService preparationService;
    private final PlanningService planning;
    private final MaterialSources materials;
    private final ReviewDraftSources reviewDrafts;
    private final ReviewDraftBindingMapper reviewDraftBindings;
    private final CardDraftMapper cardDrafts;
    private final MemoryService memory;
    private final JdRequirementsMapper jdRequirements;
    private final FactDraftMapper factDrafts;
    private final RelationEvidenceSources relationEvidence;
    private final RelationAiFeedbackDraftSources relationAiFeedback;
    private final SourceQaSources sourceQa;
    private final IdempotencyService commands;
    private final IdempotencyMapper workspaces;
    private final JsonMapper json;
    private final TransactionTemplate transaction;

    public AiService(AiMapper mapper, AiProperties properties, AiSources sources, IdempotencyService commands,
                      IdempotencyMapper workspaces, JsonMapper json, PlatformTransactionManager manager,
                      KnowledgeService knowledge, QuestionService questions, TrainingFollowUps training,
                      TrainingEvaluations evaluations, EvaluationMapper evaluationMapper, PreparationSources preparation,
                      MaterialSources materials, PreparationService preparationService, PlanningService planning,
                      ComparisonSources comparisonSources, ComparisonMapper comparisonMapper, ModelGateway gateway,
                      ReviewDraftSources reviewDrafts, ReviewDraftBindingMapper reviewDraftBindings,
                      CardDraftMapper cardDrafts, MemoryService memory, JdRequirementsMapper jdRequirements,
                      FactDraftMapper factDrafts, RewriteSources rewrites, RewriteMapper rewriteMapper,
                      RelationSupplementService relationSupplements, RelationEvidenceSources relationEvidence,
                      RelationAiFeedbackDraftSources relationAiFeedback, AiAuthorizations authorizations,
                      TrainingAccessGuard trainingAccess, SourceQaSources sourceQa) {
        this.mapper = mapper;
        this.properties = properties;
        this.sources = sources;
        this.authorizations = authorizations;
        this.trainingAccess = trainingAccess;
        this.training = training;
        this.evaluations = evaluations;
        this.evaluationMapper = evaluationMapper;
        this.rewrites = rewrites;
        this.rewriteMapper = rewriteMapper;
        this.comparisonSources = comparisonSources;
        this.comparisonMapper = comparisonMapper;
        this.gateway = gateway;
        this.knowledge = knowledge;
        this.relationSupplements = relationSupplements;
        this.questions = questions;
        this.preparation = preparation;
        this.preparationService = preparationService;
        this.planning = planning;
        this.materials = materials;
        this.reviewDrafts = reviewDrafts;
        this.reviewDraftBindings = reviewDraftBindings;
        this.cardDrafts = cardDrafts;
        this.memory = memory;
        this.jdRequirements = jdRequirements;
        this.factDrafts = factDrafts;
        this.relationEvidence = relationEvidence;
        this.relationAiFeedback = relationAiFeedback;
        this.sourceQa = sourceQa;
        this.commands = commands;
        this.workspaces = workspaces;
        this.json = json;
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(15);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Settings settings(String owner) {
        uuid(owner);
        Configuration configuration = properties.configuration();
        return new Settings(configuration, budget(owner, configuration, today()));
    }

    public AiAuthorizationDtos.Entry createAuthorization(String owner, String key, AiAuthorizationDtos.Create request) {
        uuid(owner); required(request); uuid(request.taskId()); consent(request.confirmed());
        if (request.expectedManifestHash() == null || !request.expectedManifestHash().matches("[0-9a-f]{64}")
                || request.validDays() == null || request.validDays() < 1 || request.validDays() > 90) throw invalid("持续授权范围或期限无效。");
        return commands.execute(owner, key, "POST /api/v1/ai/authorizations", request, AiAuthorizationDtos.Entry.class, () -> {
            TaskRow task = requireTask(owner, request.taskId());
            Manifest manifest = manifest(task);
            // 许可只能来自服务端保存的确切任务；原键恢复仅返回元数据，不重复授予或发送。
            requireReviewDraftAccess(owner, task, manifest);
            requireReviewDraftBinding(owner, task, manifest);
            if (manifest.purpose() == null || !task.purpose().equals(manifest.purpose().name())
                    || !request.expectedManifestHash().equals(task.manifestHash())
                    || !task.manifestHash().equals(sources.hash(manifest))) {
                throw new ApiException(409, "AI_MANIFEST_CHANGED", "原任务清单已变化，请重新核对完整外发范围。");
            }
            properties.requireReady();
            if (!configurationMatches(task) || !properties.configuration().equals(manifest.configuration())) throw configurationChanged();
            sources.requireCurrent(owner, manifest, task.createdAt());
            grantSourceBinding(owner, task, manifest);
            return authorizations.create(owner, task, manifest, request.validDays());
        });
    }

    private void grantSourceBinding(String owner, TaskRow task, Manifest manifest) {
        // 来源仍存在不代表这份历史任务确实绑定了它；授予新许可须同时核对首次保存的输入台账。
        requireSourceQaBinding(owner, task, manifest);
        switch (manifest.purpose()) {
            case FOLLOW_UP -> requireFollowUp(owner, manifest.training().sessionId(), task.id());
            case ANSWER_EVALUATION -> evaluations.binding(owner, task.id(), manifest.evaluation());
            case ANSWER_COMPARISON -> comparisonSources.binding(owner, task.id(), manifest.comparisonInput());
            case RELATION_DRAFT -> relationKnowledgeSources(owner, task, manifest);
            default -> { }
        }
        var expectedPreparation = new ArrayList<PreparationOriginSourceRow>();
        for (int index = 0; index < manifest.sources().size(); index++) {
            Source source = manifest.sources().get(index);
            if (!AiSources.knowledgeKind(source.kind())) {
                expectedPreparation.add(new PreparationOriginSourceRow(task.id(), source.itemId(), source.versionId(),
                        source.number(), source.title(), source.kind(), index + 1));
            }
        }
        var expectedMaterials = new ArrayList<MaterialDraftOriginRow>();
        if (manifest.materialDraftSources() != null) {
            for (int index = 0; index < manifest.materialDraftSources().size(); index++) {
                expectedMaterials.add(new MaterialDraftOriginRow(task.id(), manifest.materialDraftSources().get(index).excerptId(),
                        manifest.sources().size() + index + 1));
            }
        }
        var expectedQa = new ArrayList<MaterialDraftOriginRow>();
        if (manifest.materialQa() != null) {
            for (int index = 0; index < manifest.materialQa().excerpts().size(); index++) {
                expectedQa.add(new MaterialDraftOriginRow(task.id(), manifest.materialQa().excerpts().get(index).excerptId(), index + 1));
            }
        }
        var expectedTraining = new ArrayList<TrainingDraftSourceRow>();
        if (manifest.trainingDraft() != null) {
            TrainingDraftInput input = manifest.trainingDraft();
            expectedTraining.add(new TrainingDraftSourceRow(owner, task.id(), input.sessionId(), input.questionId(),
                    input.answerId(), input.feedbackTaskId()));
        }
        if (!expectedPreparation.equals(mapper.authorizationPreparationSources(owner, task.id()))
                || !expectedMaterials.equals(mapper.authorizationMaterialDraftSources(owner, task.id()))
                || !expectedQa.equals(mapper.authorizationMaterialQaSources(owner, task.id()))
                || !expectedTraining.equals(mapper.trainingDraftOriginSources(owner, List.of(task.id())))) {
            throw new ApiException(409, "AI_SOURCE_CHANGED", "原任务与首次保存的来源绑定不一致，不能据此授予持续授权。");
        }
    }

    public AiAuthorizationDtos.Revocation revokeAuthorization(String owner, String id, String key,
                                                               AiAuthorizationDtos.Revoke request) {
        uuid(owner); uuid(id); required(request); revision(request.expectedRevision()); consent(request.confirmed());
        return commands.execute(owner, key, "POST /api/v1/ai/authorizations/" + id + "/revoke", request,
                AiAuthorizationDtos.Revocation.class, () -> {
            // 撤回只需授权及尝试元数据；资料已归档、失效或不可读都不能阻止用户停止许可。
            var authorization = authorizations.revoke(owner, id, request.expectedRevision());
            int queuedStopped = 0, runningStopRequested = 0;
            // 此窄投影仅推进待处理状态；其候选本应为空，不能用于详情、来源检查或成功任务更新。
            for (TaskRow task : mapper.pendingAuthorizationTasks(owner, id)) {
                AttemptRow attempt = requireAttempt(owner, task.id(), task.currentAttemptId());
                if (!id.equals(attempt.scopeAuthorizationId())) continue;
                if (Status.QUEUED.name().equals(task.status())) {
                    failKnownUnsent(task, attempt, "AI_SCOPE_AUTHORIZATION_REVOKED", true);
                    queuedStopped++;
                } else if (Status.RUNNING.name().equals(task.status()) || Status.VALIDATING.name().equals(task.status())) {
                    requestAuthorizationStop(task, attempt, "AI_SCOPE_AUTHORIZATION_REVOKED");
                    runningStopRequested++;
                }
            }
            return new AiAuthorizationDtos.Revocation(authorization, queuedStopped, runningStopRequested);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AiAuthorizationDtos.UsagePage authorizationUses(String owner, String id) {
        uuid(owner); uuid(id);
        if (!mapper.taskExists(owner, id)) throw missing();
        return authorizations.uses(owner, mapper.attempts(owner, id));
    }

    public AiDraftAccess readDraftAccess(String owner, String taskId, String attemptId) {
        uuid(owner); uuid(taskId); uuid(attemptId);
        return locked(owner, () -> {
            // 暂态正文属于资料出口，追问的元数据白名单不能放宽它；检查与来源读取共用短 owner 锁。
            trainingAccess.checkCommand(owner, "GET /api/v1/ai/tasks/" + taskId + "/attempts/" + attemptId + "/draft-events");
            TaskRow task = requireTask(owner, taskId);
            AttemptRow attempt = requireAttempt(owner, taskId, attemptId);
            if (!owner.equals(task.ownerId()) || !taskId.equals(task.id()) || !owner.equals(attempt.ownerId())
                    || !taskId.equals(attempt.taskId()) || !attemptId.equals(attempt.id())) throw missing();
            if (!attemptId.equals(task.currentAttemptId())) {
                throw new ApiException(409, "AI_DRAFT_ATTEMPT_CHANGED", "执行尝试已变化，请重新读取任务后查看生成过程。");
            }
            Status status = Status.valueOf(task.status());
            if (!task.status().equals(attempt.status()) || task.attemptCount() != attempt.attemptNumber()) {
                throw new ApiException(409, "AI_DRAFT_UNAVAILABLE", "当前执行记录不一致，暂态正文不可读取。");
            }
            if (!Set.of(Status.QUEUED, Status.RUNNING, Status.VALIDATING).contains(status)) {
                return new AiDraftAccess(status.name(), false);
            }
            Manifest manifest = manifest(task);
            if (manifest == null || manifest.purpose() == null || manifest.configuration() == null
                    || !task.purpose().equals(manifest.purpose().name()) || !task.manifestHash().equals(sources.hash(manifest))) {
                throw new ApiException(409, "AI_DRAFT_UNAVAILABLE", "当前任务与固定外发清单不一致，暂态正文不可读取。");
            }
            requireReviewDraftAccess(owner, task, manifest);
            if (!configurationMatches(task) || !properties.configuration().equals(manifest.configuration())) throw configurationChanged();
            if (!properties.runtimeEpoch().equals(task.authorizationEpoch()) || !properties.runtimeEpoch().equals(attempt.runtimeEpoch())) {
                throw new ApiException(409, "AI_AUTHORIZATION_EXPIRED", "此执行所属进程已失效，请重新读取任务状态。");
            }
            String authorizationError = authorizations.refusal(owner, attempt, manifest);
            if (authorizationError != null) throw new ApiException(409, authorizationError, "本次外发许可已不可用，暂态正文已停止提供。");
            sources.requireCurrent(owner, manifest, task.createdAt());
            grantSourceBinding(owner, task, manifest);
            if (manifest.purpose() == Purpose.ANSWER_REWRITE) rewrites.binding(owner, task.id(), manifest.evaluation());
            if (manifest.purpose() == Purpose.FOLLOW_UP) {
                String error = training.availability(owner, manifest.training(), task.id());
                if (error != null) throw new ApiException(409, error, "原训练已变化，暂态追问正文已停止提供。");
            }
            if (status != Status.QUEUED && (attempt.claimToken() == null || attempt.sentAt() == null
                    || attempt.leaseUntil() == null || !attempt.leaseUntil().isAfter(now()))) {
                throw new ApiException(409, "AI_LEASE_EXPIRED", "本次执行租约已失效，请重新读取任务状态。");
            }
            // 这里只读校验；不能借 maySend 或恢复流程释放已发生的费用，也不推进持久任务状态。
            return new AiDraftAccess(status.name(), Boolean.TRUE.equals(manifest.configuration().streaming()));
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview preview(String owner, PreviewRequest request) {
        uuid(owner); required(request); properties.requireReady();
        Manifest manifest = sources.manifest(owner, request);
        return new Preview(manifest, sources.hash(manifest), budget(owner, manifest.configuration(), today()));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview previewRelationDraft(String owner, RelationDraftPreviewRequest request) {
        uuid(owner); required(request);
        Manifest manifest = sources.relationDraftManifest(owner, request);
        return new Preview(manifest, sources.hash(manifest), budget(owner, manifest.configuration(), today()));
    }

    public Task createRelationDraft(String owner, String key, CreateRelationDraft request) {
        required(request); consent(request.consentConfirmed());
        if (request.expectedManifestHash() == null || !request.expectedManifestHash().matches("[0-9a-f]{64}")) {
            throw invalid("请先预览本次关系候选的完整外发清单。");
        }
        return commands.execute(owner, key, "POST /api/v1/ai/relation-drafts/tasks", request, Task.class, () -> {
            recoverWithinTransaction(owner);
            Manifest manifest = sources.relationDraftManifest(owner,
                    new RelationDraftPreviewRequest(request.instruction(), request.sources()));
            String hash = sources.hash(manifest);
            if (!hash.equals(request.expectedManifestHash())) {
                throw new ApiException(409, "AI_MANIFEST_CHANGED", "所选知识版本、整理要求或配置已变化，请重新预览并授权。");
            }
            capacity(owner);
            reserve(owner, manifest.configuration(), manifest.reservedMicros());
            Instant now = now();
            String id = newId(), attemptId = newId();
            TaskRow task = new TaskRow(id, owner, Purpose.RELATION_DRAFT.name(), Status.QUEUED.name(), 0,
                    manifest.sources().getFirst().title(), manifest.configuration().provider(), manifest.configuration().model(),
                    manifest.configuration().currency(), json.writeValueAsString(manifest), hash, properties.fingerprint(),
                    properties.runtimeEpoch(), attemptId, 1, null, null, now, now);
            insertTask(task);
            mapper.insertAttempt(newAttempt(task, attemptId, 1, now, request.authorization()));
            // 所选笔记也属于授权证据；任务创建时固定全部来源，逐项决定和正式边删除均不改写它们。
            for (int index = 0; index < manifest.sources().size(); index++) {
                mapper.insertRelationSource(owner, id, manifest.sources().get(index), index + 1);
            }
            return view(owner, task);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview previewRelationEvidenceDraft(String owner, RelationEvidencePreviewRequest request) {
        uuid(owner); required(request);
        Manifest manifest = sources.relationEvidenceManifest(owner, request);
        return new Preview(manifest, sources.hash(manifest), budget(owner, manifest.configuration(), today()));
    }

    public Task createRelationEvidenceDraft(String owner, String key, CreateRelationEvidenceDraft request) {
        required(request); consent(request.consentConfirmed());
        if (request.expectedManifestHash() == null || !request.expectedManifestHash().matches("[0-9a-f]{64}")) {
            throw invalid("请先预览包含所选原文的完整关系候选外发清单。");
        }
        return commands.execute(owner, key, "POST /api/v1/ai/relation-evidence-drafts/tasks", request, Task.class,
                () -> requireRelationEvidenceCreateReceipt(owner, key), () -> {
            recoverWithinTransaction(owner);
            Manifest manifest = sources.relationEvidenceManifest(owner,
                    new RelationEvidencePreviewRequest(request.instruction(), request.sources(), request.source()));
            String hash = sources.hash(manifest);
            if (!hash.equals(request.expectedManifestHash())) {
                throw new ApiException(409, "AI_MANIFEST_CHANGED", "所选知识版本、原文证据、整理要求或配置已变化，请重新预览并授权。");
            }
            capacity(owner);
            reserve(owner, manifest.configuration(), manifest.reservedMicros());
            Instant now = now();
            String id = newId(), attemptId = newId();
            TaskRow task = new TaskRow(id, owner, Purpose.RELATION_DRAFT.name(), Status.QUEUED.name(), 0,
                    manifest.sources().getFirst().title(), manifest.configuration().provider(), manifest.configuration().model(),
                    manifest.configuration().currency(), json.writeValueAsString(manifest), hash, properties.fingerprint(),
                    properties.runtimeEpoch(), attemptId, 1, null, null, now, now);
            insertTask(task);
            mapper.insertAttempt(newAttempt(task, attemptId, 1, now, request.authorization()));
            for (int index = 0; index < manifest.sources().size(); index++) {
                mapper.insertRelationSource(owner, id, manifest.sources().get(index), index + 1);
            }
            // 两类来源与任务、费用和回执同时提交；原文证据不伪装成知识版本或正式关系。
            relationEvidence.insert(owner, id, manifest.relationEvidence());
            return view(owner, task);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview previewRelationReviewDraft(String owner, RelationReviewPreviewRequest request) {
        uuid(owner); required(request);
        Manifest manifest = sources.relationReviewManifest(owner, request);
        return new Preview(manifest, sources.hash(manifest), budget(owner, manifest.configuration(), today()));
    }

    public Task createRelationReviewDraft(String owner, String key, CreateRelationReviewDraft request) {
        required(request); consent(request.consentConfirmed());
        if (request.expectedManifestHash() == null || !request.expectedManifestHash().matches("[0-9a-f]{64}")) {
            throw invalid("请先预览包含所选反馈或回忆的完整关系候选外发清单。");
        }
        return commands.execute(owner, key, "POST /api/v1/ai/relation-review-drafts/tasks", request, Task.class,
                () -> requireRelationReviewCreateReceipt(owner, key, request.source()), () -> {
            recoverWithinTransaction(owner);
            Manifest manifest = sources.relationReviewManifest(owner,
                    new RelationReviewPreviewRequest(request.instruction(), request.sources(), request.source()));
            if (!AiSources.validRelationReviewInput(manifest)) throw reviewBindingBroken();
            String hash = sources.hash(manifest);
            if (!hash.equals(request.expectedManifestHash())) {
                throw new ApiException(409, "AI_MANIFEST_CHANGED", "所选知识版本、复盘来源、决定、片段或配置已变化，请重新预览并授权。");
            }
            capacity(owner);
            reserve(owner, manifest.configuration(), manifest.reservedMicros());
            Instant now = now();
            String id = newId(), attemptId = newId();
            TaskRow task = new TaskRow(id, owner, Purpose.RELATION_DRAFT.name(), Status.QUEUED.name(), 0,
                    manifest.sources().getFirst().title(), manifest.configuration().provider(), manifest.configuration().model(),
                    manifest.configuration().currency(), json.writeValueAsString(manifest), hash, properties.fingerprint(),
                    properties.runtimeEpoch(), attemptId, 1, null, null, now, now);
            insertTask(task);
            mapper.insertAttempt(newAttempt(task, attemptId, 1, now, request.authorization()));
            for (int index = 0; index < manifest.sources().size(); index++) {
                mapper.insertRelationSource(owner, id, manifest.sources().get(index), index + 1);
            }
            // 知识与复盘身份共同绑定本次授权；反馈修订和回忆版本不转成新的知识端点。
            if (reviewDraftBindings.insert(reviewBinding(owner, id, manifest.reviewDraft())) != 1) throw reviewBindingBroken();
            return view(owner, task);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview previewRelationAiFeedbackDraft(String owner, RelationAiFeedbackPreviewRequest request) {
        uuid(owner); required(request);
        Manifest manifest = sources.relationAiFeedbackManifest(owner, request);
        return new Preview(manifest, sources.hash(manifest), budget(owner, manifest.configuration(), today()));
    }

    public Task createRelationAiFeedbackDraft(String owner, String key, CreateRelationAiFeedbackDraft request) {
        required(request); consent(request.consentConfirmed());
        if (request.expectedManifestHash() == null || !request.expectedManifestHash().matches("[0-9a-f]{64}")) {
            throw invalid("请先预览包含所选 AI 反馈的完整关系候选外发清单。");
        }
        return commands.execute(owner, key, "POST /api/v1/ai/relation-ai-feedback-drafts/tasks", request, Task.class,
                () -> requireRelationAiFeedbackCreateReceipt(owner, key, request.source()), () -> {
            recoverWithinTransaction(owner);
            Manifest manifest = sources.relationAiFeedbackManifest(owner,
                    new RelationAiFeedbackPreviewRequest(request.instruction(), request.sources(), request.source()));
            if (!AiSources.validRelationAiFeedbackInput(manifest)) throw relationAiFeedbackBindingBroken();
            String hash = sources.hash(manifest);
            if (!hash.equals(request.expectedManifestHash())) {
                throw new ApiException(409, "AI_MANIFEST_CHANGED", "所选知识版本、AI 反馈、决定、片段或配置已变化，请重新预览并授权。");
            }
            capacity(owner);
            reserve(owner, manifest.configuration(), manifest.reservedMicros());
            Instant now = now();
            String id = newId(), attemptId = newId();
            TaskRow task = new TaskRow(id, owner, Purpose.RELATION_DRAFT.name(), Status.QUEUED.name(), 0,
                    manifest.sources().getFirst().title(), manifest.configuration().provider(), manifest.configuration().model(),
                    manifest.configuration().currency(), json.writeValueAsString(manifest), hash, properties.fingerprint(),
                    properties.runtimeEpoch(), attemptId, 1, null, null, now, now);
            insertTask(task);
            mapper.insertAttempt(newAttempt(task, attemptId, 1, now, request.authorization()));
            for (int index = 0; index < manifest.sources().size(); index++) {
                mapper.insertRelationSource(owner, id, manifest.sources().get(index), index + 1);
            }
            // 成功尝试、报告摘要和所选片段与任务、知识台账及费用一同提交，AI 意见不转成已确认事实。
            relationAiFeedback.bind(owner, id, manifest.relationAiFeedback());
            return view(owner, task);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview previewPreparationDraft(String owner, PreparationDraftPreviewRequest request) {
        uuid(owner); required(request);
        Manifest manifest = sources.preparationDraftManifest(owner, request);
        return new Preview(manifest, sources.hash(manifest), budget(owner, manifest.configuration(), today()));
    }

    public Task createPreparationDraft(String owner, String key, CreatePreparationDraft request) {
        required(request); consent(request.consentConfirmed());
        if (request.expectedManifestHash() == null || !request.expectedManifestHash().matches("[0-9a-f]{64}")) {
            throw invalid("请先预览本次准备版本生成候选的完整外发清单。");
        }
        return commands.execute(owner, key, "POST /api/v1/ai/preparation-drafts/tasks", request, Task.class, () -> {
            recoverWithinTransaction(owner);
            Manifest manifest = sources.preparationDraftManifest(owner,
                    new PreparationDraftPreviewRequest(request.purpose(), request.instruction(), request.sources(), request.preparationSources()));
            String hash = sources.hash(manifest);
            if (!hash.equals(request.expectedManifestHash())) {
                throw new ApiException(409, "AI_MANIFEST_CHANGED", "来源内容、可用性代次或配置已变化，请重新预览并授权。");
            }
            capacity(owner);
            reserve(owner, manifest.configuration(), manifest.reservedMicros());
            Instant now = now();
            String id = newId(), attemptId = newId();
            TaskRow task = new TaskRow(id, owner, manifest.purpose().name(), Status.QUEUED.name(), 0,
                    manifest.sources().getFirst().title(), manifest.configuration().provider(), manifest.configuration().model(),
                    manifest.configuration().currency(), json.writeValueAsString(manifest), hash, properties.fingerprint(),
                    properties.runtimeEpoch(), attemptId, 1, null, null, now, now);
            insertTask(task);
            mapper.insertAttempt(newAttempt(task, attemptId, 1, now, request.authorization()));
            // 准备关系含表达的确切版本；表达依据只进本地检查快照，不另建外发来源或知识版本关系。
            for (int index = 0; index < manifest.sources().size(); index++) {
                Source source = manifest.sources().get(index);
                if (!AiSources.knowledgeKind(source.kind())) mapper.insertPreparationSource(owner, id, source, index + 1);
            }
            return view(owner, task);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview previewMaterialDraft(String owner, MaterialDraftPreviewRequest request) {
        uuid(owner); required(request);
        Manifest manifest = sources.materialDraftManifest(owner, request);
        return new Preview(manifest, sources.hash(manifest), budget(owner, manifest.configuration(), today()));
    }

    public Task createMaterialDraft(String owner, String key, CreateMaterialDraft request) {
        required(request); consent(request.consentConfirmed());
        if (request.expectedManifestHash() == null || !request.expectedManifestHash().matches("[0-9a-f]{64}")) {
            throw invalid("请先预览本次资料摘录生成候选的完整外发清单。");
        }
        return commands.execute(owner, key, "POST /api/v1/ai/material-drafts/tasks", request, Task.class, () -> {
            recoverWithinTransaction(owner);
            Manifest manifest = sources.materialDraftManifest(owner,
                    new MaterialDraftPreviewRequest(request.purpose(), request.instruction(), request.sources(),
                            request.preparationSources(), request.excerptIds()));
            String hash = sources.hash(manifest);
            if (!hash.equals(request.expectedManifestHash())) {
                throw new ApiException(409, "AI_MANIFEST_CHANGED", "来源内容、可用性代次或配置已变化，请重新预览并授权。");
            }
            capacity(owner);
            reserve(owner, manifest.configuration(), manifest.reservedMicros());
            Instant now = now();
            String id = newId(), attemptId = newId();
            String title = manifest.sources().isEmpty() ? manifest.materialDraftSources().getFirst().fileName()
                    : manifest.sources().getFirst().title();
            title = title.substring(0, title.offsetByCodePoints(0, Math.min(200, title.codePointCount(0, title.length()))));
            TaskRow task = new TaskRow(id, owner, manifest.purpose().name(), Status.QUEUED.name(), 0, title,
                    manifest.configuration().provider(), manifest.configuration().model(), manifest.configuration().currency(),
                    json.writeValueAsString(manifest), hash, properties.fingerprint(), properties.runtimeEpoch(),
                    attemptId, 1, null, null, now, now);
            insertTask(task);
            mapper.insertAttempt(newAttempt(task, attemptId, 1, now, request.authorization()));
            for (int index = 0; index < manifest.sources().size(); index++) {
                Source source = manifest.sources().get(index);
                if (!AiSources.knowledgeKind(source.kind())) mapper.insertPreparationSource(owner, id, source, index + 1);
            }
            // 资料使用自己的摘录外键；关系与任务、预留、通知和回执一起提交，不能冒充知识版本。
            var excerpts = manifest.materialDraftSources();
            for (int index = 0; index < excerpts.size(); index++) {
                mapper.insertMaterialDraftSource(owner, id, excerpts.get(index).excerptId(), manifest.sources().size() + index + 1);
            }
            return view(owner, task);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview previewTrainingDraft(String owner, TrainingDraftPreviewRequest request) {
        uuid(owner); required(request);
        Manifest manifest = sources.trainingDraftManifest(owner, request);
        return new Preview(manifest, sources.hash(manifest), budget(owner, manifest.configuration(), today()));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview previewReviewDraft(String owner, ReviewDraftDtos.PreviewRequest request) {
        uuid(owner); required(request);
        Manifest manifest = sources.reviewDraftManifest(owner, request);
        return new Preview(manifest, sources.hash(manifest), budget(owner, manifest.configuration(), today()));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview previewCardDraft(String owner, CardDraftDtos.PreviewRequest request) {
        uuid(owner); required(request);
        Manifest manifest = sources.cardDraftManifest(owner, request);
        return new Preview(manifest, sources.hash(manifest), budget(owner, manifest.configuration(), today()));
    }

    public Task createCardDraft(String owner, CardDraftDtos.CreateRequest request, String key) {
        required(request); consent(request.consentConfirmed());
        if (request.expectedManifestHash() == null || !request.expectedManifestHash().matches("[0-9a-f]{64}")) {
            throw invalid("请先预览本次记忆卡候选的完整外发清单。");
        }
        return commands.execute(owner, key, "POST /api/v1/ai/card-drafts/tasks", request, Task.class, () -> {
            recoverWithinTransaction(owner);
            Manifest manifest = sources.cardDraftManifest(owner,
                    new CardDraftDtos.PreviewRequest(request.kind(), request.instruction(), request.sources()));
            String hash = sources.hash(manifest);
            if (!hash.equals(request.expectedManifestHash())) {
                throw new ApiException(409, "AI_MANIFEST_CHANGED", "记忆卡类型、知识版本、整理要求或配置已变化，请重新预览并授权。");
            }
            if (!AiSources.validCardDraftInput(manifest)) throw cardBindingBroken();
            capacity(owner); reserve(owner, manifest.configuration(), manifest.reservedMicros());
            Instant now = now();
            String id = newId(), attemptId = newId();
            TaskRow task = new TaskRow(id, owner, Purpose.MEMORY_CARD_DRAFT.name(), Status.QUEUED.name(), 0,
                    manifest.sources().getFirst().title(), manifest.configuration().provider(), manifest.configuration().model(),
                    manifest.configuration().currency(), json.writeValueAsString(manifest), hash, properties.fingerprint(),
                    properties.runtimeEpoch(), attemptId, 1, null, null, now, now);
            insertTask(task);
            mapper.insertAttempt(newAttempt(task, attemptId, 1, now, request.authorization()));
            // 来源身份和顺序在创建时固定，采用时不能通过编辑卡片另换依据。
            for (int index = 0; index < manifest.sources().size(); index++) {
                if (cardDrafts.insertSource(cardSource(owner, id, manifest.sources().get(index), index + 1)) != 1) throw cardBindingBroken();
            }
            return view(owner, task);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview previewJdRequirements(String owner, JdRequirementsDtos.PreviewRequest request) {
        uuid(owner); required(request);
        Manifest manifest = sources.jdRequirementsManifest(owner, request);
        return new Preview(manifest, sources.hash(manifest), budget(owner, manifest.configuration(), today()));
    }

    public Task createJdRequirements(String owner, JdRequirementsDtos.CreateRequest request, String key) {
        required(request); consent(request.consentConfirmed());
        if (request.expectedManifestHash() == null || !request.expectedManifestHash().matches("[0-9a-f]{64}")) {
            throw invalid("请先预览本次岗位要求候选的完整外发清单。");
        }
        return commands.execute(owner, key, "POST /api/v1/ai/jd-requirements/tasks", request, Task.class, () -> {
            recoverWithinTransaction(owner);
            Manifest manifest = sources.jdRequirementsManifest(owner,
                    new JdRequirementsDtos.PreviewRequest(request.targetItemId(), request.targetVersionId(), request.instruction()));
            String hash = sources.hash(manifest);
            if (!hash.equals(request.expectedManifestHash())) {
                throw new ApiException(409, "AI_MANIFEST_CHANGED", "岗位版本、JD、整理要求或配置已变化，请重新预览并授权。");
            }
            if (!AiSources.validJdRequirementsInput(manifest)) throw jdBindingBroken();
            capacity(owner); reserve(owner, manifest.configuration(), manifest.reservedMicros());
            Instant now = now();
            String id = newId(), attemptId = newId();
            TaskRow task = new TaskRow(id, owner, Purpose.JD_REQUIREMENT_DRAFT.name(), Status.QUEUED.name(), 0,
                    manifest.sources().getFirst().title(), manifest.configuration().provider(), manifest.configuration().model(),
                    manifest.configuration().currency(), json.writeValueAsString(manifest), hash, properties.fingerprint(),
                    properties.runtimeEpoch(), attemptId, 1, null, null, now, now);
            insertTask(task);
            mapper.insertAttempt(newAttempt(task, attemptId, 1, now, request.authorization()));
            mapper.insertPreparationSource(owner, id, manifest.sources().getFirst(), 1);
            return view(owner, task);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview previewFactDraft(String owner, FactDraftDtos.PreviewRequest request) {
        uuid(owner); required(request);
        Manifest manifest = sources.factDraftManifest(owner, request);
        return new Preview(manifest, sources.hash(manifest), budget(owner, manifest.configuration(), today()));
    }

    public Task createFactDraft(String owner, FactDraftDtos.CreateRequest request, String key) {
        required(request); consent(request.consentConfirmed());
        if (request.expectedManifestHash() == null || !request.expectedManifestHash().matches("[0-9a-f]{64}")) {
            throw invalid("请先预览本次事实候选的完整外发清单。");
        }
        return commands.execute(owner, key, "POST /api/v1/ai/fact-drafts/tasks", request, Task.class,
                () -> requireFactCreateReceipt(owner, key), () -> {
            recoverWithinTransaction(owner);
            Manifest manifest = sources.factDraftManifest(owner, new FactDraftDtos.PreviewRequest(request.instruction(), request.source()));
            String hash = sources.hash(manifest);
            if (!hash.equals(request.expectedManifestHash())) {
                throw new ApiException(409, "AI_MANIFEST_CHANGED", "所选原文、整理要求或配置已变化，请重新预览并授权。");
            }
            if (!AiSources.validFactDraftInput(manifest)) throw factBindingBroken();
            capacity(owner); reserve(owner, manifest.configuration(), manifest.reservedMicros());
            Instant now = now();
            String id = newId(), attemptId = newId();
            var input = manifest.factDraft();
            String title = input.kind() == FactDraftDtos.Kind.MATERIAL ? "资料摘录整理事实" : "训练回答片段整理事实";
            TaskRow task = new TaskRow(id, owner, Purpose.FACT_DRAFT.name(), Status.QUEUED.name(), 0, title,
                    manifest.configuration().provider(), manifest.configuration().model(), manifest.configuration().currency(),
                    json.writeValueAsString(manifest), hash, properties.fingerprint(), properties.runtimeEpoch(),
                    attemptId, 1, null, null, now, now);
            insertTask(task);
            mapper.insertAttempt(newAttempt(task, attemptId, 1, now, request.authorization()));
            // 来源模式及确切身份先固定，训练提交不能进入材料关系。
            if (factDrafts.insertSource(factSource(owner, id, input)) != 1) throw factBindingBroken();
            for (int index = 0; index < input.materials().size(); index++) {
                if (factDrafts.insertMaterial(new FactDraftRows.MaterialRow(owner, id,
                        input.materials().get(index).excerptId(), index + 1)) != 1) throw factBindingBroken();
            }
            return view(owner, task);
        });
    }

    public Task createReviewDraft(String owner, ReviewDraftDtos.CreateRequest request, String key) {
        required(request); consent(request.consentConfirmed());
        if (request.expectedManifestHash() == null || !request.expectedManifestHash().matches("[0-9a-f]{64}")) {
            throw invalid("请先预览本次复盘片段整理的完整外发清单。");
        }
        return commands.execute(owner, key, "POST /api/v1/ai/review-drafts/tasks", request, Task.class,
                () -> reviewDrafts.requireAccess(owner, request.source()), () -> {
            recoverWithinTransaction(owner);
            Manifest manifest = sources.reviewDraftManifest(owner, new ReviewDraftDtos.PreviewRequest(request.instruction(), request.source()));
            String hash = sources.hash(manifest);
            if (!hash.equals(request.expectedManifestHash())) {
                throw new ApiException(409, "AI_MANIFEST_CHANGED", "复盘来源、决定、片段或配置已变化，请重新预览并授权。");
            }
            if (!AiSources.validReviewDraftInput(manifest)) throw reviewBindingBroken();
            capacity(owner);
            reserve(owner, manifest.configuration(), manifest.reservedMicros());
            Instant now = now();
            String id = newId(), attemptId = newId();
            ReviewDraftDtos.Input input = manifest.reviewDraft();
            String title = input.recollection() != null ? input.recollection().title()
                    : input.kind() == ReviewDraftDtos.Kind.SELF_CHECK ? "自检片段整理" : "外部反馈片段整理";
            TaskRow task = new TaskRow(id, owner, Purpose.KNOWLEDGE_DRAFT.name(), Status.QUEUED.name(), 0,
                    title, manifest.configuration().provider(), manifest.configuration().model(), manifest.configuration().currency(),
                    json.writeValueAsString(manifest), hash, properties.fingerprint(), properties.runtimeEpoch(),
                    attemptId, 1, null, null, now, now);
            insertTask(task);
            mapper.insertAttempt(newAttempt(task, attemptId, 1, now, request.authorization()));
            // 确切来源关系与任务、预算和回执一起提交，后台各阶段仍重新核对该关系。
            if (reviewDraftBindings.insert(reviewBinding(owner, id, input)) != 1) throw reviewBindingBroken();
            return view(owner, task);
        });
    }

    public Task createTrainingDraft(String owner, String key, CreateTrainingDraft request) {
        required(request); consent(request.consentConfirmed());
        if (request.expectedManifestHash() == null || !request.expectedManifestHash().matches("[0-9a-f]{64}")) {
            throw invalid("请先预览本次训练片段整理的完整外发清单。");
        }
        return commands.execute(owner, key, "POST /api/v1/ai/training-drafts/tasks", request, Task.class, () -> {
            recoverWithinTransaction(owner);
            Manifest manifest = sources.trainingDraftManifest(owner, new TrainingDraftPreviewRequest(
                    request.sessionId(), request.questionId(), request.answerId(), request.instruction(),
                    request.fragments(), request.feedback()));
            String hash = sources.hash(manifest);
            if (!hash.equals(request.expectedManifestHash())) {
                throw new ApiException(409, "AI_MANIFEST_CHANGED", "所选训练片段、反馈、整理要求或配置已变化，请重新预览并授权。");
            }
            TrainingDraftInput input = manifest.trainingDraft();
            if (input.feedbackTaskId() != null) {
                var binding = evaluationMapper.binding(owner, input.feedbackTaskId());
                if (binding == null || !input.sessionId().equals(binding.sessionId())
                        || !input.questionId().equals(binding.questionId()) || !input.answerId().equals(binding.answerId())) {
                    throw new ApiException(404, "AI_TRAINING_SOURCE_NOT_FOUND", "所选评价不属于本次确切训练提交。");
                }
            }
            capacity(owner);
            reserve(owner, manifest.configuration(), manifest.reservedMicros());
            Instant now = now();
            String id = newId(), attemptId = newId();
            TaskRow task = new TaskRow(id, owner, Purpose.KNOWLEDGE_DRAFT.name(), Status.QUEUED.name(), 0,
                    input.question().title(), manifest.configuration().provider(), manifest.configuration().model(),
                    manifest.configuration().currency(), json.writeValueAsString(manifest), hash, properties.fingerprint(),
                    properties.runtimeEpoch(), attemptId, 1, null, null, now, now);
            insertTask(task);
            mapper.insertAttempt(newAttempt(task, attemptId, 1, now, request.authorization()));
            // 训练来源固定确切提交；即使没有知识版本来源，也必须与任务、费用预留及回执同事务保留关系。
            mapper.insertTrainingDraftSource(new TrainingDraftSourceRow(owner, id, input.sessionId(), input.questionId(),
                    input.answerId(), input.feedbackTaskId()));
            return view(owner, task);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview previewExpressionDraft(String owner, ExpressionDraftPreviewRequest request) {
        uuid(owner); required(request);
        Manifest manifest = sources.expressionDraftManifest(owner, request);
        return new Preview(manifest, sources.hash(manifest), budget(owner, manifest.configuration(), today()));
    }

    public Task createExpressionDraft(String owner, String key, CreateExpressionDraft request) {
        required(request); consent(request.consentConfirmed());
        if (request.expectedManifestHash() == null || !request.expectedManifestHash().matches("[0-9a-f]{64}")) {
            throw invalid("请先预览本次事实生成表达的完整外发清单。");
        }
        return commands.execute(owner, key, "POST /api/v1/ai/expression-drafts/tasks", request, Task.class, () -> {
            recoverWithinTransaction(owner);
            Manifest manifest = sources.expressionDraftManifest(owner,
                    new ExpressionDraftPreviewRequest(request.type(), request.instruction(), request.factSources()));
            String hash = sources.hash(manifest);
            if (!hash.equals(request.expectedManifestHash())) {
                throw new ApiException(409, "AI_MANIFEST_CHANGED", "事实内容、来源代次或配置已变化，请重新预览并授权。");
            }
            capacity(owner);
            reserve(owner, manifest.configuration(), manifest.reservedMicros());
            Instant now = now();
            String id = newId(), attemptId = newId();
            TaskRow task = new TaskRow(id, owner, Purpose.EXPRESSION_DRAFT.name(), Status.QUEUED.name(), 0,
                    manifest.sources().getFirst().title(), manifest.configuration().provider(), manifest.configuration().model(),
                    manifest.configuration().currency(), json.writeValueAsString(manifest), hash, properties.fingerprint(),
                    properties.runtimeEpoch(), attemptId, 1, null, null, now, now);
            insertTask(task);
            mapper.insertAttempt(newAttempt(task, attemptId, 1, now, request.authorization()));
            for (int index = 0; index < manifest.sources().size(); index++) {
                mapper.insertPreparationSource(owner, id, manifest.sources().get(index), index + 1);
            }
            return view(owner, task);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview previewPreparationPlan(String owner, PreparationPlanPreviewRequest request) {
        uuid(owner); required(request);
        Manifest manifest = sources.preparationPlanManifest(owner, request);
        return new Preview(manifest, sources.hash(manifest), budget(owner, manifest.configuration(), today()));
    }

    public Task createPreparationPlan(String owner, String key, CreatePreparationPlan request) {
        required(request); consent(request.consentConfirmed());
        if (request.expectedManifestHash() == null || !request.expectedManifestHash().matches("[0-9a-f]{64}")) {
            throw invalid("请先预览本次准备安排的完整外发清单。");
        }
        return commands.execute(owner, key, "POST /api/v1/ai/preparation-plans/tasks", request, Task.class, () -> {
            recoverWithinTransaction(owner);
            Manifest manifest = sources.preparationPlanManifest(owner,
                    new PreparationPlanPreviewRequest(request.target(), request.plan(), request.instruction()));
            String hash = sources.hash(manifest);
            if (!hash.equals(request.expectedManifestHash())) {
                throw new ApiException(409, "AI_MANIFEST_CHANGED", "目标版本、安排条件、来源代次或配置已变化，请重新预览并授权。");
            }
            capacity(owner);
            reserve(owner, manifest.configuration(), manifest.reservedMicros());
            Instant now = now();
            String id = newId(), attemptId = newId();
            TaskRow task = new TaskRow(id, owner, Purpose.PREPARATION_PLAN.name(), Status.QUEUED.name(), 0,
                    manifest.sources().getFirst().title(), manifest.configuration().provider(), manifest.configuration().model(),
                    manifest.configuration().currency(), json.writeValueAsString(manifest), hash, properties.fingerprint(),
                    properties.runtimeEpoch(), attemptId, 1, null, null, now, now);
            insertTask(task);
            mapper.insertAttempt(newAttempt(task, attemptId, 1, now, request.authorization()));
            mapper.insertPreparationSource(owner, id, manifest.sources().getFirst(), 1);
            return view(owner, task);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview previewMaterialQa(String owner, MaterialQaPreviewRequest request) {
        uuid(owner); required(request);
        Manifest manifest = sources.materialQaManifest(owner, request);
        return new Preview(manifest, sources.hash(manifest), budget(owner, manifest.configuration(), today()));
    }

    public Task createMaterialQa(String owner, String key, CreateMaterialQa request) {
        required(request); consent(request.consentConfirmed());
        if (request.expectedManifestHash() == null || !request.expectedManifestHash().matches("[0-9a-f]{64}")) {
            throw invalid("请先预览本次问答的完整外发清单。");
        }
        return commands.execute(owner, key, "POST /api/v1/ai/material-qa/tasks", request, Task.class, () -> {
            recoverWithinTransaction(owner);
            Manifest manifest = sources.materialQaManifest(owner,
                    new MaterialQaPreviewRequest(request.mode(), request.question(), request.excerptIds()));
            String hash = sources.hash(manifest);
            if (!hash.equals(request.expectedManifestHash())) {
                throw new ApiException(409, "AI_MANIFEST_CHANGED", "问题、摘录或配置已变化，请重新预览并授权。");
            }
            capacity(owner);
            reserve(owner, manifest.configuration(), manifest.reservedMicros());
            Instant now = now();
            String id = newId(), attemptId = newId();
            String question = manifest.materialQa().question();
            String title = question.substring(0, question.offsetByCodePoints(0,
                    Math.min(200, question.codePointCount(0, question.length()))));
            TaskRow task = new TaskRow(id, owner, Purpose.MATERIAL_QA.name(), Status.QUEUED.name(), 0, title,
                    manifest.configuration().provider(), manifest.configuration().model(), manifest.configuration().currency(),
                    json.writeValueAsString(manifest), hash, properties.fingerprint(), properties.runtimeEpoch(),
                    attemptId, 1, null, null, now, now);
            insertTask(task);
            mapper.insertAttempt(newAttempt(task, attemptId, 1, now, request.authorization()));
            // 来源关系与任务、费用预留、通知游标和幂等回执一起提交，保留确切摘录身份。
            var excerpts = manifest.materialQa().excerpts();
            for (int index = 0; index < excerpts.size(); index++) {
                mapper.insertMaterialQaSource(owner, id, excerpts.get(index).excerptId(), index + 1);
            }
            return view(owner, task);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview previewSourceQa(String owner, SourceQaDtos.PreviewRequest request) {
        uuid(owner); required(request);
        Manifest manifest = sources.sourceQaManifest(owner, request);
        return new Preview(manifest, sources.hash(manifest), budget(owner, manifest.configuration(), today()));
    }

    public Task createSourceQa(String owner, String key, SourceQaDtos.CreateRequest request) {
        required(request); consent(request.consentConfirmed());
        if (request.expectedManifestHash() == null || !request.expectedManifestHash().matches("[0-9a-f]{64}")) {
            throw invalid("请先预览跨域问答的完整外发内容与限定。");
        }
        return commands.execute(owner, key, "POST /api/v1/ai/source-qa/tasks", request, Task.class,
                () -> requireSourceQaCreateReceipt(owner, key, request), () -> {
            recoverWithinTransaction(owner);
            Manifest manifest = sources.sourceQaManifest(owner, new SourceQaDtos.PreviewRequest(request.question(), request.selections()));
            String hash = sources.hash(manifest);
            if (!hash.equals(request.expectedManifestHash())) {
                throw new ApiException(409, "AI_MANIFEST_CHANGED", "问题、来源、选区、限定或配置已变化，请重新预览并授权。");
            }
            capacity(owner);
            reserve(owner, manifest.configuration(), manifest.reservedMicros());
            Instant now = now();
            String id = newId(), attemptId = newId();
            String question = manifest.sourceQa().question();
            String title = question.substring(0, question.offsetByCodePoints(0, Math.min(200, question.codePointCount(0, question.length()))));
            TaskRow task = new TaskRow(id, owner, Purpose.SOURCE_QA.name(), Status.QUEUED.name(), 0, title,
                    manifest.configuration().provider(), manifest.configuration().model(), manifest.configuration().currency(),
                    json.writeValueAsString(manifest), hash, properties.fingerprint(), properties.runtimeEpoch(),
                    attemptId, 1, null, null, now, now);
            insertTask(task);
            mapper.insertAttempt(newAttempt(task, attemptId, 1, now, request.authorization()));
            // 有序来源绑定与任务、费用预留、通知和原键回执在同一 owner 事务提交。
            sourceQa.save(owner, id, manifest.sourceQa());
            return view(owner, task);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TaskPage list(String owner, String cursor, int limit) {
        uuid(owner); pageLimit(limit);
        if (cursor != null) uuid(cursor);
        var rows = mapper.tasks(owner, cursor, limit + 1);
        var page = rows.stream().limit(limit).map(this::summary).toList();
        return new TaskPage(page, rows.size() > limit ? page.getLast().id() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Task detail(String owner, String id) { return view(owner, requireTask(owner, id)); }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TaskPage relationGraphTasks(String owner, String cursor, int limit) {
        uuid(owner);
        if (cursor != null) uuid(cursor);
        if (limit < 1 || limit > 20) throw invalid("候选星图任务每页数量必须为 1 至 20。");
        // 任务选择只读取摘要；用户明确选择一个任务后才读取其候选和固定来源。
        var rows = mapper.relationGraphTasks(owner, cursor, limit + 1);
        if (rows == null || rows.size() > limit + 1) throw relationGraphBroken();
        var items = new ArrayList<Summary>();
        String previous = cursor;
        for (SummaryRow row : rows) {
            if (row == null || !Purpose.RELATION_DRAFT.name().equals(row.purpose())
                    || !Status.SUCCEEDED.name().equals(row.status())) throw relationGraphBroken();
            Summary item = summary(row);
            requireRelationGraphSummary(item);
            if (previous != null && item.id().compareTo(previous) <= 0) throw relationGraphBroken();
            previous = item.id();
            if (items.size() < limit) items.add(item);
        }
        return new TaskPage(List.copyOf(items), rows.size() > limit ? items.getLast().id() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RelationGraphDtos.Projection relationGraph(String owner, String id) {
        TaskRow task = requireTask(owner, id);
        if (!owner.equals(task.ownerId()) || !id.equals(task.id())) throw relationGraphBroken();
        if (!Purpose.RELATION_DRAFT.name().equals(task.purpose()) || !Status.SUCCEEDED.name().equals(task.status())) {
            throw stateConflict();
        }
        Manifest manifest = manifest(task);
        if (!AiSources.validRelationInput(manifest) || manifest.configuration() == null
                || !sources.hash(manifest).equals(task.manifestHash())) throw relationGraphBroken();
        requireReviewDraftAccess(owner, task, manifest);
        Summary summary = summary(task);
        requireRelationGraphSummary(summary);
        if (!summary.title().equals(manifest.sources().getFirst().title())
                || !summary.provider().equals(manifest.configuration().provider())
                || !summary.model().equals(manifest.configuration().model())
                || !summary.currency().equals(manifest.configuration().currency())) throw relationGraphBroken();
        List<OriginSource> origin = relationOriginSources(owner, task, manifest);
        RelationCandidate candidate = relationCandidate(task, manifest);
        var decided = new HashSet<String>();
        var supplements = new HashSet<String>();
        var decisions = mapper.relationDecisions(owner, id);
        if (decisions == null) throw relationGraphBroken();
        for (RelationDecisionRow saved : decisions) {
            if (saved == null || !decided.add(saved.candidateId())) throw relationGraphBroken();
            RelationDecision decision = relationDecision(owner, task, candidate, saved);
            if (decision.supplementId() != null && !supplements.add(decision.supplementId())) throw relationGraphBroken();
        }
        // 决定过滤发生在全部候选和绑定核验之后，不能借已忽略状态掩盖损坏或重新编号原候选。
        List<RelationGraphDtos.Edge> edges = candidate.relations().stream()
                .filter(item -> !decided.contains(item.id()))
                .map(item -> new RelationGraphDtos.Edge(item.id(), item.sourceItemId(), item.targetItemId(),
                        item.type(), item.reason(), item.citations().size()
                                + (item.materialCitations() == null ? 0 : item.materialCitations().size())
                                + (item.trainingCitations() == null ? 0 : item.trainingCitations().size())
                                + (item.reviewCitations() == null ? 0 : item.reviewCitations().size()))).toList();
        return new RelationGraphDtos.Projection(summary, sources.current(owner, manifest, task.createdAt()), origin,
                candidate.summary(), candidate.limitations(), candidate.validationNote(), candidate.relations().size(),
                decided.size(), edges);
    }

    private static void requireRelationGraphSummary(Summary value) {
        try { uuid(value.id()); }
        catch (ApiException exception) { throw relationGraphBroken(); }
        if (value.purpose() != Purpose.RELATION_DRAFT || value.status() != Status.SUCCEEDED
                || value.revision() < 0 || value.revision() > MAX_SAFE || value.attemptCount() < 1 || value.attemptCount() > 3
                || value.title() == null || value.title().isBlank() || value.provider() == null || value.provider().isBlank()
                || value.model() == null || value.model().isBlank() || value.currency() == null || !value.currency().matches("[A-Z]{3}")
                || value.createdAt() == null || value.updatedAt() == null) throw relationGraphBroken();
    }

    private static IllegalStateException relationGraphBroken() {
        return new IllegalStateException("候选星图任务、固定来源或逐项决定不完整");
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Settings followUpSettings(String owner, String sessionId) {
        training.session(owner, sessionId);
        return settings(owner);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview previewFollowUp(String owner, String sessionId, String questionId, FollowUpPreviewRequest request) {
        required(request);
        Manifest manifest = sources.followUpManifest(owner, sessionId, questionId, request.answerId());
        return new Preview(manifest, sources.hash(manifest), budget(owner, manifest.configuration(), today()));
    }

    public FollowUpTask createFollowUp(String owner, String sessionId, String questionId, String key, CreateFollowUp request) {
        uuid(sessionId); uuid(questionId); required(request); consent(request.consentConfirmed());
        if (request.expectedManifestHash() == null || !request.expectedManifestHash().matches("[0-9a-f]{64}")) {
            throw invalid("请先预览本次追问的完整外发清单。");
        }
        return commands.execute(owner, key, "POST /api/v1/training/sessions/" + sessionId + "/questions/" + questionId + "/follow-ups",
                request, FollowUpTask.class, () -> {
                    recoverWithinTransaction(owner);
                    Manifest manifest = sources.followUpManifest(owner, sessionId, questionId, request.answerId());
                    String hash = sources.hash(manifest);
                    if (!hash.equals(request.expectedManifestHash())) {
                        throw new ApiException(409, "AI_MANIFEST_CHANGED", "训练代次、配置或外发清单已变化，请重新预览授权。");
                    }
                    capacity(owner);
                    reserve(owner, manifest.configuration(), manifest.reservedMicros());
                    Instant now = now();
                    String id = newId(), attemptId = newId();
                    TaskRow task = new TaskRow(id, owner, Purpose.FOLLOW_UP.name(), Status.QUEUED.name(), 0,
                            manifest.training().question().title(), manifest.configuration().provider(), manifest.configuration().model(),
                            manifest.configuration().currency(), json.writeValueAsString(manifest), hash, properties.fingerprint(),
                            properties.runtimeEpoch(), attemptId, 1, null, null, now, now);
                    insertTask(task);
                    mapper.insertAttempt(newAttempt(task, attemptId, 1, now, request.authorization()));
                    FollowUpInput input = manifest.training();
                    mapper.insertFollowUp(new FollowUpRow(owner, id, sessionId, questionId, input.answerId(),
                            input.rootQuestionId(), input.generation(), null, now));
                    return followUpView(owner, task);
                });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FollowUpPage followUps(String owner, String sessionId, String cursor, int limit) {
        training.session(owner, sessionId); pageLimit(limit);
        if (cursor != null) uuid(cursor);
        var rows = mapper.followUpTasks(owner, sessionId, cursor, limit + 1);
        var page = rows.stream().limit(limit).map(this::summary).toList();
        return new FollowUpPage(page, rows.size() > limit ? page.getLast().id() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FollowUpTask followUp(String owner, String sessionId, String id) {
        requireFollowUp(owner, sessionId, id);
        return followUpView(owner, requireTask(owner, id));
    }

    public FollowUpTask cancelFollowUp(String owner, String sessionId, String id, String key, ChangeTask request) {
        return cancelCommand(owner, id, key, request, sessionId, FollowUpTask.class, row -> followUpView(owner, row));
    }

    public FollowUpTask retryFollowUp(String owner, String sessionId, String id, String key, RetryTask request) {
        return retryCommand(owner, id, key, request, sessionId, FollowUpTask.class, row -> followUpView(owner, row));
    }

    public FollowUpTask reconcileFollowUp(String owner, String sessionId, String id, String attemptId, String key, Reconcile request) {
        return reconcileCommand(owner, id, attemptId, key, request, sessionId, FollowUpTask.class, row -> followUpView(owner, row));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview previewEvaluation(String owner, String sessionId, String questionId, EvaluationPreviewRequest request) {
        required(request);
        Manifest manifest = sources.evaluationManifest(owner, sessionId, questionId, request.answerId());
        return new Preview(manifest, sources.hash(manifest), budget(owner, manifest.configuration(), today()));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview previewRewrite(String owner, RewriteDtos.PreviewRequest request) {
        uuid(owner); required(request);
        uuid(request.sessionId()); uuid(request.questionId()); uuid(request.answerId());
        Manifest manifest = sources.rewriteManifest(owner, request.sessionId(), request.questionId(), request.answerId());
        return new Preview(manifest, sources.hash(manifest), budget(owner, manifest.configuration(), today()));
    }

    public Task createRewrite(String owner, String key, RewriteDtos.CreateRequest request) {
        uuid(owner); required(request); consent(request.consentConfirmed());
        uuid(request.sessionId()); uuid(request.questionId()); uuid(request.answerId());
        if (request.expectedManifestHash() == null || !request.expectedManifestHash().matches("[0-9a-f]{64}")) {
            throw invalid("请先预览这份确切提交的完整改写输入和费用。");
        }
        return commands.execute(owner, key, "POST /api/v1/ai/answer-rewrites/tasks", request, Task.class, () -> {
            evaluations.requireAccess(owner, request.sessionId(), request.questionId(), request.answerId());
            requireRewriteCreateReceipt(owner, key);
        }, () -> {
            recoverWithinTransaction(owner);
            Manifest manifest = sources.rewriteManifest(owner, request.sessionId(), request.questionId(), request.answerId());
            String hash = sources.hash(manifest);
            if (!hash.equals(request.expectedManifestHash())) {
                throw new ApiException(409, "AI_MANIFEST_CHANGED", "确切改写输入或配置已变化，请重新预览并授权。");
            }
            if (!AiSources.validRewriteInput(manifest)) throw rewriteBindingBroken();
            capacity(owner); reserve(owner, manifest.configuration(), manifest.reservedMicros());
            Instant now = now();
            String id = newId(), attemptId = newId();
            TaskRow task = new TaskRow(id, owner, Purpose.ANSWER_REWRITE.name(), Status.QUEUED.name(), 0,
                    manifest.evaluation().question().title(), manifest.configuration().provider(), manifest.configuration().model(),
                    manifest.configuration().currency(), json.writeValueAsString(manifest), hash, properties.fingerprint(),
                    properties.runtimeEpoch(), attemptId, 1, null, null, now, now);
            insertTask(task);
            mapper.insertAttempt(newAttempt(task, attemptId, 1, now, request.authorization()));
            // 改写有独立绑定和额度，不混入评价覆盖，也不产生正式回答版本。
            rewriteMapper.insertBinding(new RewriteRows.BindingRow(owner, id, request.sessionId(), request.questionId(), request.answerId(), now));
            return view(owner, task);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EvaluationPage rewrites(String owner, String sessionId, String questionId, String answerId, String cursor, int limit) {
        uuid(owner); evaluations.session(owner, sessionId); pageLimit(limit);
        if (questionId != null) uuid(questionId);
        if (answerId != null) {
            uuid(answerId);
            if (questionId == null) throw invalid("按回答筛选改写任务时须同时指定题目。");
            evaluations.requireAccess(owner, sessionId, questionId, answerId);
        }
        if (cursor != null) uuid(cursor);
        var rows = rewriteMapper.tasks(owner, sessionId, questionId, answerId, cursor, limit + 1);
        var items = rows.stream().limit(limit).map(row -> {
            if (!Purpose.ANSWER_REWRITE.name().equals(row.purpose())) throw rewriteBindingBroken();
            return new EvaluationTaskSummary(new Summary(row.id(), Purpose.ANSWER_REWRITE, Status.valueOf(row.status()), row.revision(),
                    row.title(), row.provider(), row.model(), row.currency(), row.attemptCount(), row.createdAt(), row.updatedAt(), row.errorCode()),
                    row.questionId(), row.answerId(), row.answerNumber());
        }).toList();
        return new EvaluationPage(items, rows.size() > limit ? items.getLast().task().id() : null);
    }

    public Task createEvaluation(String owner, String sessionId, String questionId, String key, CreateEvaluation request) {
        uuid(sessionId); uuid(questionId); required(request); consent(request.consentConfirmed());
        if (request.expectedManifestHash() == null || !request.expectedManifestHash().matches("[0-9a-f]{64}")) {
            throw invalid("请先预览此次评价的完整输入和费用。");
        }
        return commands.execute(owner, key, "POST /api/v1/training/sessions/" + sessionId + "/questions/" + questionId + "/evaluations",
                request, Task.class, () -> {
                    recoverWithinTransaction(owner);
                    Manifest manifest = sources.evaluationManifest(owner, sessionId, questionId, request.answerId());
                    String hash = sources.hash(manifest);
                    if (!hash.equals(request.expectedManifestHash())) {
                        throw new ApiException(409, "AI_MANIFEST_CHANGED", "确切评价输入或配置已变化，请重新预览并授权。");
                    }
                    capacity(owner);
                    reserve(owner, manifest.configuration(), manifest.reservedMicros());
                    Instant now = now();
                    String id = newId(), attemptId = newId();
                    TaskRow task = new TaskRow(id, owner, Purpose.ANSWER_EVALUATION.name(), Status.QUEUED.name(), 0,
                            manifest.evaluation().question().title(), manifest.configuration().provider(), manifest.configuration().model(),
                            manifest.configuration().currency(), json.writeValueAsString(manifest), hash, properties.fingerprint(),
                            properties.runtimeEpoch(), attemptId, 1, null, null, now, now);
                    insertTask(task);
                    mapper.insertAttempt(newAttempt(task, attemptId, 1, now, request.authorization()));
                    evaluationMapper.insertBinding(new EvaluationRows.BindingRow(owner, id, sessionId, questionId, request.answerId(), now));
                    return view(owner, task);
                });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EvaluationPage evaluations(String owner, String sessionId, String questionId, String cursor, int limit) {
        evaluations.session(owner, sessionId); pageLimit(limit);
        if (questionId != null) uuid(questionId);
        if (cursor != null) uuid(cursor);
        var rows = evaluationMapper.tasks(owner, sessionId, questionId, cursor, limit + 1);
        var items = rows.stream().limit(limit).map(row -> new EvaluationTaskSummary(
                new Summary(row.id(), Purpose.valueOf(row.purpose()), Status.valueOf(row.status()), row.revision(), row.title(),
                        row.provider(), row.model(), row.currency(), row.attemptCount(), row.createdAt(), row.updatedAt(), row.errorCode()),
                row.questionId(), row.answerId(), row.answerNumber())).toList();
        return new EvaluationPage(items, rows.size() > limit ? items.getLast().task().id() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview previewComparison(String owner, String id, ComparisonDtos.PreviewRequest request) {
        uuid(owner); uuid(id); required(request);
        Manifest manifest = sources.comparisonManifest(owner, id, request.sourceIds());
        return new Preview(manifest, sources.hash(manifest), budget(owner, manifest.configuration(), today()));
    }

    public Task createComparison(String owner, String id, String key, ComparisonDtos.Create request) {
        uuid(id); required(request); consent(request.consentConfirmed());
        if (request.expectedManifestHash() == null || !request.expectedManifestHash().matches("[0-9a-f]{64}")) {
            throw invalid("请先预览本次双答案比较的完整外发清单和费用。");
        }
        return commands.execute(owner, key, "POST /api/v1/review/comparisons/" + id + "/ai/tasks", request, Task.class, () -> {
            recoverWithinTransaction(owner);
            Manifest manifest = sources.comparisonManifest(owner, id, request.sourceIds());
            String hash = sources.hash(manifest);
            if (!hash.equals(request.expectedManifestHash())) {
                throw new ApiException(409, "AI_MANIFEST_CHANGED", "确切答案、所选来源、核对代次或配置已变化，请重新预览并授权。");
            }
            capacity(owner);
            reserve(owner, manifest.configuration(), manifest.reservedMicros());
            Instant now = now();
            String taskId = newId(), attemptId = newId();
            TaskRow task = new TaskRow(taskId, owner, Purpose.ANSWER_COMPARISON.name(), Status.QUEUED.name(), 0,
                    manifest.comparisonInput().current().question().title(), manifest.configuration().provider(), manifest.configuration().model(),
                    manifest.configuration().currency(), json.writeValueAsString(manifest), hash, properties.fingerprint(),
                    properties.runtimeEpoch(), attemptId, 1, null, null, now, now);
            insertTask(task);
            mapper.insertAttempt(newAttempt(task, attemptId, 1, now, request.authorization()));
            // 比较绑定、任务、费用和回执同事务提交；候选不写入人工判断或动作状态。
            comparisonMapper.insertBinding(new ComparisonRows.BindingRow(owner, taskId, id, now));
            return view(owner, task);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TaskPage comparisons(String owner, String id, String cursor, int limit) {
        uuid(owner); uuid(id);
        if (limit < 1 || limit > 20) throw invalid("每页比较任务数量必须为 1 至 20。");
        if (cursor != null) uuid(cursor);
        comparisonSources.requireReadable(owner, id);
        List<TaskRow> rows = comparisonMapper.tasks(owner, id, cursor, limit + 1);
        List<Summary> items = rows.stream().limit(limit).map(row -> {
            if (!owner.equals(row.ownerId()) || !Purpose.ANSWER_COMPARISON.name().equals(row.purpose())) {
                throw new IllegalStateException("比较任务列表的归属或用途不一致");
            }
            Manifest manifest = manifest(row);
            if (!AiSources.validComparisonInput(manifest)) throw new IllegalStateException("比较任务列表的清单不一致");
            ComparisonDtos.Link binding = comparisonSources.binding(owner, row.id(), manifest.comparisonInput());
            if (!id.equals(binding.comparisonId())) throw new IllegalStateException("比较任务列表的绑定不一致");
            return summary(row);
        }).toList();
        return new TaskPage(items, rows.size() > limit ? items.getLast().id() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EvaluationSessionCoverage evaluationCoverage(String owner, String sessionId) {
        evaluations.session(owner, sessionId);
        return new EvaluationSessionCoverage(evaluationMapper.coverage(owner, sessionId));
    }

    public Task decideFeedback(String owner, String id, String feedbackId, String key, DecideFeedback request) {
        uuid(id); required(request);
        if (feedbackId == null || !feedbackId.matches("f([1-9]|1[0-2])")) throw invalid("反馈标识无效。");
        return commands.execute(owner, key, operation(id, "feedback/" + feedbackId + "/decisions"), request, Task.class,
                () -> requireReviewDraftAccess(owner, id), () -> {
            TaskRow task = requireTask(owner, id);
            if (!Purpose.ANSWER_EVALUATION.name().equals(task.purpose()) || !Status.SUCCEEDED.name().equals(task.status())
                    || task.candidateJson() == null) throw stateConflict();
            evaluations.binding(owner, id, manifest(task).evaluation());
            evaluations.decide(owner, id, json.readValue(task.candidateJson(), EvaluationReport.class), feedbackId, request);
            return decisionResult(owner, task, now());
        });
    }

    public Task adopt(String owner, String id, String key, AdoptCandidate request) {
        uuid(id); required(request); revision(request.expectedRevision());
        if (!Boolean.TRUE.equals(request.confirmed()) || request.mode() == null) {
            throw invalid("请明确选择采用方式并确认最终内容。");
        }
        if (request.title() == null || request.title().isBlank() || request.title().strip().length() > 200
                || request.body() == null || request.body().isBlank() || request.body().length() > 100_000) {
            throw invalid("标题须为 1 至 200 个字符，正文不能为空且不能超过 100000 个字符。");
        }
        if (request.mode() == AdoptionMode.NEW) {
            if (request.kind() == null || request.targetItemId() != null || request.expectedTargetVersionId() != null
                    || request.expectedTargetDraftRevision() != null) {
                throw invalid("新建知识时必须选择类型，并将现有目标及其基线设为空值。");
            }
        } else {
            if (request.kind() != null) throw invalid("采用到已有知识时不能变更知识类型。");
            uuid(request.targetItemId()); uuid(request.expectedTargetVersionId());
            revision(request.expectedTargetDraftRevision());
        }
        return commands.execute(owner, key, operation(id, "adopt"), request, Task.class,
                () -> requireReviewDraftAccess(owner, id), () -> {
            TaskRow task = undecidedCandidate(owner, id, request.expectedRevision(), Purpose.KNOWLEDGE_DRAFT);
            Manifest manifest = manifest(task);
            // 来源校验先于目标写入，允许目标本身也是来源；采用只消费已授权的本地候选。
            requireReviewDraftBinding(owner, task, manifest);
            if (manifest.reviewDraft() != null) gateway.readReviewDraftCandidate(task.candidateJson(), manifest);
            sources.requireCurrent(owner, manifest, task.createdAt());
            var target = request.mode() == AdoptionMode.NEW
                    ? knowledge.createWithinCommand(owner, new CreateItem(request.kind(), request.title(), request.body(), List.of()))
                    : knowledge.publishWithinCommand(owner, request.targetItemId(), request.expectedTargetVersionId(),
                            request.expectedTargetDraftRevision(), request.title(), request.body());
            Instant now = now();
            mapper.insertDecision(new DecisionRow(owner, id, DecisionStatus.ADOPTED.name(), request.mode().name(),
                    target.id(), target.currentVersionId(), now));
            for (int index = 0; index < manifest.sources().size(); index++) {
                Source source = manifest.sources().get(index);
                if (AiSources.knowledgeKind(source.kind())) mapper.insertKnowledgeSource(owner, id, source, index + 1);
            }
            return decisionResult(owner, task, now);
        });
    }

    public Task adoptFact(String owner, String id, String key, FactDraftDtos.Adopt request) {
        uuid(id); required(request); revision(request.expectedRevision()); revision(request.expectedProjectRevision());
        uuid(request.projectId()); uuid(request.expectedProjectVersionId());
        if (!Boolean.TRUE.equals(request.confirmed()) || !FactDraftSources.validContent(request.content())) {
            throw invalid("请核对事实内容并明确确认保存为待核实事实。");
        }
        return commands.execute(owner, key, operation(id, "adopt-fact"), request, Task.class,
                () -> requireReviewDraftAccess(owner, id), () -> {
            TaskRow task = undecidedCandidate(owner, id, request.expectedRevision(), Purpose.FACT_DRAFT);
            Manifest manifest = manifest(task);
            requireFactDraftBinding(owner, task, manifest);
            sources.requireCurrent(owner, manifest, task.createdAt());
            var content = request.content();
            var fact = new PreparationDtos.FactContent(content.title(), content.scenario(), content.problem(), content.action(),
                    content.result(), content.technology(), PreparationDtos.Responsibility.UNVERIFIED,
                    factPreparationSource(manifest.factDraft()), content.tags());
            // 项目 CAS、新事实首版、材料关系和一次决定共用 owner 命令事务，不自动核实本人责任。
            var target = preparationService.createFactWithinCommand(owner, request.projectId(), request.expectedProjectVersionId(),
                    request.expectedProjectRevision(), fact);
            Instant now = now();
            if (factDrafts.insertDecision(new FactDraftRows.DecisionRow(owner, id, DecisionStatus.ADOPTED.name(),
                    request.projectId(), target.id(), target.currentVersion().id(), now)) != 1) throw factBindingBroken();
            return decisionResult(owner, task, now);
        });
    }

    public Task adoptRequirements(String owner, String id, String key, JdRequirementsDtos.Adopt request) {
        uuid(id); required(request); revision(request.expectedRevision()); revision(request.expectedTargetRevision());
        if (!Boolean.TRUE.equals(request.confirmed()) || request.requirements() == null
                || request.requirements().isEmpty() || request.requirements().size() > 20) {
            throw invalid("请明确确认采用，并选择 1 至 20 条岗位要求。");
        }
        return commands.execute(owner, key, operation(id, "adopt-requirements"), request, Task.class,
                () -> requireReviewDraftAccess(owner, id), () -> {
            TaskRow task = undecidedCandidate(owner, id, request.expectedRevision(), Purpose.JD_REQUIREMENT_DRAFT);
            Manifest manifest = manifest(task);
            requireJdRequirementsBinding(owner, task, manifest);
            JdRequirementsDtos.Candidate candidate = gateway.readJdRequirementsCandidate(task.candidateJson(), manifest);
            sources.requireCurrent(owner, manifest, task.createdAt());
            Set<Integer> available = new HashSet<>(); candidate.requirements().forEach(value -> available.add(value.number()));
            Set<Integer> selected = new HashSet<>();
            var requirements = new ArrayList<PreparationDtos.Requirement>();
            var links = new ArrayList<JdRequirementsDtos.RequirementLink>();
            for (var selection : request.requirements()) {
                if (selection == null || selection.candidateNumber() == null || !available.contains(selection.candidateNumber())
                        || !selected.add(selection.candidateNumber())) throw invalid("采用要求须来自本次候选，且不能重复选择。");
                String requirementId = newId();
                requirements.add(new PreparationDtos.Requirement(requirementId, selection.text(), selection.dimensions(), selection.priority()));
                links.add(new JdRequirementsDtos.RequirementLink(selection.candidateNumber(), requirementId));
            }
            Source source = manifest.sources().getFirst();
            // 目标服务只追加新身份，保留旧要求；采用后的来源改版不撤销已提交决定。
            var target = preparationService.adoptRequirements(owner, source.itemId(), source.versionId(),
                    request.expectedTargetRevision(), List.copyOf(requirements));
            Instant now = now();
            if (jdRequirements.insertDecision(new JdRequirementsRows.DecisionRow(owner, id, DecisionStatus.ADOPTED.name(),
                    target.id(), target.currentVersion().id(), json.writeValueAsString(links), now)) != 1) throw jdBindingBroken();
            return decisionResult(owner, task, now);
        });
    }

    public Task adoptCard(String owner, String id, String key, CardDraftDtos.Adopt request) {
        uuid(id); required(request); revision(request.expectedRevision()); required(request.content());
        if (!Boolean.TRUE.equals(request.confirmed())) throw invalid("请明确确认采用审阅后的记忆卡内容。");
        return commands.execute(owner, key, operation(id, "adopt-card"), request, Task.class,
                () -> requireReviewDraftAccess(owner, id), () -> {
            TaskRow task = undecidedCandidate(owner, id, request.expectedRevision(), Purpose.MEMORY_CARD_DRAFT);
            Manifest manifest = manifest(task);
            requireCardDraftBinding(owner, task, manifest);
            gateway.readCardDraftCandidate(task.candidateJson(), manifest);
            sources.requireCurrent(owner, manifest, task.createdAt());
            MemoryDtos.Content content = request.content();
            List<MemoryDtos.SourceLink> fixedSources = manifest.sources().stream()
                    .map(source -> new MemoryDtos.SourceLink(source.itemId(), source.versionId())).toList();
            if (content.kind() != manifest.cardDraftType() || !fixedSources.equals(content.sources())) {
                throw invalid("采用后的卡片类型和完整来源顺序须与授权清单一致。");
            }
            // 新卡、观察基线、采用决定和任务回执共用 owner 事务；此处不启用排程或生成复习。
            MemoryDtos.Card card = memory.createWithinCommand(owner, new MemoryDtos.Create(content));
            Instant now = now();
            if (cardDrafts.insertDecision(new CardDraftRows.DecisionRow(owner, id, DecisionStatus.ADOPTED.name(),
                    card.id(), card.currentVersion().id(), now)) != 1) throw cardBindingBroken();
            return decisionResult(owner, task, now);
        });
    }

    public Task adoptQuestion(String owner, String id, String key, AdoptQuestion request) {
        uuid(id); required(request); revision(request.expectedRevision()); required(request.content());
        if (!Boolean.TRUE.equals(request.confirmed())) throw invalid("请明确确认采用审阅后的题目内容。");
        return commands.execute(owner, key, operation(id, "adopt-question"), request, Task.class,
                () -> requireReviewDraftAccess(owner, id), () -> {
            TaskRow task = undecidedCandidate(owner, id, request.expectedRevision(), Purpose.QUESTION_DRAFT);
            Manifest manifest = manifest(task);
            sources.requireCurrent(owner, manifest, task.createdAt());
            // 最终内容来自用户本次提交；模型引用只记录来源，不能自动变成题目的知识关联。
            var target = questions.createWithinCommand(owner, request.content());
            Instant now = now();
            mapper.insertQuestionDecision(new QuestionDecisionRow(owner, id, DecisionStatus.ADOPTED.name(),
                    target.id(), target.currentVersion().id(), now));
            for (int index = 0; index < manifest.sources().size(); index++) {
                Source source = manifest.sources().get(index);
                if (AiSources.knowledgeKind(source.kind())) mapper.insertQuestionSource(owner, id, source, index + 1);
            }
            return decisionResult(owner, task, now);
        });
    }

    public Task adoptExpression(String owner, String id, String key, AdoptExpression request) {
        uuid(id); required(request); revision(request.expectedRevision()); required(request.content());
        if (!Boolean.TRUE.equals(request.confirmed()) || request.mode() == null) {
            throw invalid("请明确选择表达采用方式并确认最终内容。");
        }
        if (request.mode() == AdoptionMode.NEW) {
            if (request.targetItemId() != null || request.expectedTargetVersionId() != null || request.expectedTargetRevision() != null) {
                throw invalid("新建表达时目标对象及基线必须为空值。");
            }
        } else {
            uuid(request.targetItemId()); uuid(request.expectedTargetVersionId()); revision(request.expectedTargetRevision());
        }
        return commands.execute(owner, key, operation(id, "adopt-expression"), request, Task.class,
                () -> requireReviewDraftAccess(owner, id), () -> {
            TaskRow task = undecidedCandidate(owner, id, request.expectedRevision(), Purpose.EXPRESSION_DRAFT);
            Manifest manifest = manifest(task);
            // 删减最终依据不缩小原任务的授权边界，采用前仍核对全部原事实及其代次。
            sources.requireCurrent(owner, manifest, task.createdAt());
            var content = expressionContent(manifest, request.content());
            var target = preparationService.adoptExpression(owner, request.targetItemId(), request.expectedTargetVersionId(),
                    request.expectedTargetRevision(), content);
            Instant now = now();
            mapper.insertExpressionDecision(new ExpressionDecisionRow(owner, id, DecisionStatus.ADOPTED.name(), request.mode().name(),
                    target.id(), target.currentVersion().id(), now));
            return decisionResult(owner, task, now);
        });
    }

    public Task adoptPreparationPlan(String owner, String id, String key, AdoptPreparationPlan request) {
        uuid(id); required(request); revision(request.expectedRevision()); required(request.content());
        if (!Boolean.TRUE.equals(request.confirmed())) throw invalid("请明确确认采用审阅后的准备安排。");
        return commands.execute(owner, key, operation(id, "adopt-preparation-plan"), request, Task.class,
                () -> requireReviewDraftAccess(owner, id), () -> {
            TaskRow task = undecidedCandidate(owner, id, request.expectedRevision(), Purpose.PREPARATION_PLAN);
            Manifest manifest = manifest(task);
            sources.requireCurrent(owner, manifest, task.createdAt());
            PreparationPlanRules.content(manifest.preparationPlan(), request.content());
            Source target = manifest.sources().getFirst();
            var contents = request.content().steps().stream().map(step -> new PlanningDtos.Content(
                    step.title(), step.detail(), step.minutes(), step.date(), manifest.preparationPlan().zoneId())).toList();
            // 采用只能在原目标、日期与工作量内新建任务；计划决定和任务初始历史在同一命令事务提交。
            var created = planning.adopt(owner, new PlanningDtos.TargetLink(target.itemId(), target.versionId()), id, contents);
            if (created.size() != contents.size()) throw new IllegalStateException("准备安排采用返回的任务数量不一致");
            var links = new ArrayList<PreparationPlanTaskLink>(created.size());
            for (int index = 0; index < created.size(); index++) {
                var item = created.get(index);
                if (!id.equals(item.sourceAiTaskId()) || !Integer.valueOf(index + 1).equals(item.sourceStep())) {
                    throw new IllegalStateException("准备安排采用返回的任务来源或顺序不一致");
                }
                links.add(new PreparationPlanTaskLink(index + 1, item.id()));
            }
            Instant now = now();
            mapper.insertPreparationPlanDecision(new PreparationPlanDecisionRow(owner, id, DecisionStatus.ADOPTED.name(),
                    json.writeValueAsString(request.content()), json.writeValueAsString(links), now));
            return decisionResult(owner, task, now);
        });
    }

    public Task adoptRelation(String owner, String id, String candidateId, String key, AdoptRelation request) {
        uuid(id); relationCandidateId(candidateId); required(request); revision(request.expectedRevision()); required(request.content());
        if (!Boolean.TRUE.equals(request.confirmed())) throw invalid("请明确确认采用审阅后的知识关系。");
        return commands.execute(owner, key, operation(id, "relations/" + candidateId + "/adopt"), request, Task.class,
                () -> requireReviewDraftAccess(owner, id), () -> {
            TaskRow task = undecidedRelationCandidate(owner, id, candidateId, request.expectedRevision());
            Manifest manifest = manifest(task);
            RelationSuggestion candidate = relationSuggestion(relationCandidate(task, manifest), candidateId);
            RelationContent content = relationContent(request.content(), candidate);
            // 改写理由或方向不缩小授权边界；采用前仍核对全部原来源与持久化来源关系。
            relationOriginSources(owner, task, manifest);
            sources.requireCurrent(owner, manifest, task.createdAt());
            var relation = knowledge.createNewRelationWithinCommand(owner,
                    new CreateRelation(content.sourceItemId(), content.targetItemId(), content.type()));
            if (!content.sourceItemId().equals(relation.sourceId()) || !content.targetItemId().equals(relation.targetId())
                    || content.type() != relation.type()) throw new IllegalStateException("新建知识关系与采用内容不一致");
            Instant now = now();
            mapper.insertRelationDecision(new RelationDecisionRow(owner, id, candidateId, DecisionStatus.ADOPTED.name(),
                    relation.id(), json.writeValueAsString(content), now, null));
            return decisionResult(owner, task, now);
        });
    }

    public Task supplementRelation(String owner, String id, String candidateId, String key, SupplementRelation request) {
        uuid(id); relationCandidateId(candidateId); required(request); revision(request.expectedRevision());
        uuid(request.relationId()); required(request.content());
        if (!Boolean.TRUE.equals(request.confirmed())) throw invalid("请明确确认把本次审阅理由与全部来源补充到所选已有关系。");
        return commands.execute(owner, key, operation(id, "relations/" + candidateId + "/supplement"), request, Task.class,
                () -> requireReviewDraftAccess(owner, id), () -> {
            TaskRow task = undecidedRelationCandidate(owner, id, candidateId, request.expectedRevision());
            Manifest manifest = manifest(task);
            RelationSuggestion candidate = relationSuggestion(relationCandidate(task, manifest), candidateId);
            RelationContent content = relationContent(request.content(), candidate);
            relationOriginSources(owner, task, manifest);
            sources.requireCurrent(owner, manifest, task.createdAt());
            // 用户指定原边身份；内容归一后仍须与该边一致，不能在并发删除后自动创建或匹配新边。
            Relation relation = new Relation(request.relationId(), content.sourceItemId(), content.targetItemId(), content.type());
            var supplement = relationSupplements.supplementAiWithinCommand(owner, relation, content.reason(),
                    manifest.sources().stream().map(source -> new RelationSupplementDtos.SourceLink(source.itemId(), source.versionId())).toList(),
                    id, candidateId);
            if (supplement == null || supplement.id() == null || !relation.id().equals(supplement.relationId())
                    || !relation.sourceId().equals(supplement.sourceId()) || !relation.targetId().equals(supplement.targetId())
                    || relation.type() != supplement.type() || !content.reason().equals(supplement.reason())
                    || supplement.origin() != RelationSupplementDtos.Origin.AI || !id.equals(supplement.taskId())
                    || !candidateId.equals(supplement.candidateId())) {
                throw new IllegalStateException("关系补充记录与指定正式关系不一致");
            }
            Instant now = now();
            mapper.insertRelationDecision(new RelationDecisionRow(owner, id, candidateId, DecisionStatus.ADOPTED.name(),
                    relation.id(), json.writeValueAsString(content), now, supplement.id()));
            return decisionResult(owner, task, now);
        });
    }

    public Task ignoreRelation(String owner, String id, String candidateId, String key, ChangeTask request) {
        uuid(id); relationCandidateId(candidateId); required(request); revision(request.expectedRevision());
        return commands.execute(owner, key, operation(id, "relations/" + candidateId + "/ignore"), request, Task.class,
                () -> requireReviewDraftAccess(owner, id), () -> {
            TaskRow task = undecidedRelationCandidate(owner, id, candidateId, request.expectedRevision());
            Instant now = now();
            mapper.insertRelationDecision(new RelationDecisionRow(owner, id, candidateId, DecisionStatus.IGNORED.name(), null, null, now, null));
            return decisionResult(owner, task, now);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RelationOrigin relationOrigin(String owner, String relationId) {
        uuid(owner); uuid(relationId);
        RelationDecisionRow saved = mapper.relationOrigin(owner, relationId);
        if (saved == null) return null;
        TaskRow task = requireTask(owner, saved.taskId());
        Manifest manifest = manifest(task);
        requireReviewDraftAccess(owner, task, manifest);
        RelationCandidate result = relationCandidate(task, manifest);
        RelationDecision decision = relationDecision(owner, task, result, saved);
        if (decision.status() != DecisionStatus.ADOPTED || !relationId.equals(decision.relationId()) || decision.supplementId() != null) {
            throw new IllegalStateException("知识关系来源与采用决定不一致");
        }
        return new RelationOrigin(task.id(), relationSuggestion(result, saved.candidateId()), decision,
                relationOriginSources(owner, task, manifest), mapper.relationExists(owner, relationId),
                manifest.relationEvidence(), manifest.relationEvidence() == null ? null
                        : relationEvidence.state(owner, manifest.relationEvidence()),
                manifest.reviewDraft(), manifest.reviewDraft() == null ? null : reviewDrafts.state(owner, manifest.reviewDraft()),
                manifest.relationAiFeedback(), manifest.relationAiFeedback() == null ? null
                        : relationAiFeedback.state(owner, manifest.relationAiFeedback()));
    }

    public Task ignore(String owner, String id, String key, ChangeTask request) {
        uuid(id); required(request); revision(request.expectedRevision());
        return commands.execute(owner, key, operation(id, "ignore"), request, Task.class,
                () -> requireReviewDraftAccess(owner, id), () -> {
            TaskRow task = undecidedCandidate(owner, id, request.expectedRevision(), null);
            Instant now = now();
            if (Purpose.FACT_DRAFT.name().equals(task.purpose())) {
                if (factDrafts.insertDecision(new FactDraftRows.DecisionRow(owner, id, DecisionStatus.IGNORED.name(),
                        null, null, null, now)) != 1) throw factBindingBroken();
            } else if (Purpose.JD_REQUIREMENT_DRAFT.name().equals(task.purpose())) {
                if (jdRequirements.insertDecision(new JdRequirementsRows.DecisionRow(owner, id, DecisionStatus.IGNORED.name(),
                        null, null, "[]", now)) != 1) throw jdBindingBroken();
            } else if (Purpose.MEMORY_CARD_DRAFT.name().equals(task.purpose())) {
                if (cardDrafts.insertDecision(new CardDraftRows.DecisionRow(owner, id, DecisionStatus.IGNORED.name(),
                        null, null, now)) != 1) throw cardBindingBroken();
            } else if (Purpose.QUESTION_DRAFT.name().equals(task.purpose())) {
                mapper.insertQuestionDecision(new QuestionDecisionRow(owner, id, DecisionStatus.IGNORED.name(), null, null, now));
            } else if (Purpose.EXPRESSION_DRAFT.name().equals(task.purpose())) {
                mapper.insertExpressionDecision(new ExpressionDecisionRow(owner, id, DecisionStatus.IGNORED.name(), null, null, null, now));
            } else if (Purpose.PREPARATION_PLAN.name().equals(task.purpose())) {
                mapper.insertPreparationPlanDecision(new PreparationPlanDecisionRow(owner, id, DecisionStatus.IGNORED.name(), null, "[]", now));
            } else {
                mapper.insertDecision(new DecisionRow(owner, id, DecisionStatus.IGNORED.name(), null, null, null, now));
            }
            return decisionResult(owner, task, now);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OriginPage origins(String owner, String itemId, String versionId, Integer before, int limit) {
        uuid(owner); uuid(itemId); uuid(versionId); pageLimit(limit);
        if (before != null && before < 1) throw invalid("来源游标必须为正整数版本号。");
        var version = knowledge.version(owner, itemId, versionId);
        var rows = mapper.origins(owner, itemId, version.number(), before, limit + 1);
        return originPage(owner, versionId, rows, limit, false);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FactDraftDtos.OriginPage factOrigins(String owner, String factId, String versionId, Integer before, int limit) {
        uuid(owner); uuid(factId); uuid(versionId); pageLimit(limit);
        if (before != null && before < 1) throw invalid("来源游标必须为正整数版本号。");
        var version = factDrafts.targetVersion(owner, factId, versionId);
        if (version == null || !"FACT".equals(version.kind())) throw new ApiException(404, "PREPARATION_NOT_FOUND", "事实或确切版本不存在或不可访问。");
        if (!owner.equals(version.ownerId()) || !factId.equals(version.itemId()) || !versionId.equals(version.id())
                || version.number() < 1 || !"PROJECT".equals(version.projectKind())) throw factBindingBroken();
        var rows = factDrafts.origins(owner, factId, version.number(), before, limit + 1);
        var page = rows.subList(0, Math.min(rows.size(), limit));
        var items = new ArrayList<FactDraftDtos.Origin>();
        long previous = before == null ? (long) version.number() + 1 : Math.min((long) version.number() + 1, before);
        for (var row : page) {
            Manifest manifest = json.readValue(row.manifestJson(), Manifest.class);
            if (!Purpose.FACT_DRAFT.name().equals(row.purpose()) || !Status.SUCCEEDED.name().equals(row.taskStatus())
                    || !AiSources.validFactDraftInput(manifest) || !Objects.equals(row.manifestHash(), sources.hash(manifest))
                    || !row.candidatePresent() || row.targetVersionNumber() != 1 || row.targetVersionNumber() >= previous
                    || !factId.equals(row.targetItemId()) || !Objects.equals(version.projectId(), row.targetProjectId())
                    || row.adoptedAt() == null || row.taskCreatedAt() == null || row.taskUpdatedAt() == null
                    || row.adoptedAt().isBefore(row.taskCreatedAt()) || row.adoptedAt().isAfter(row.taskUpdatedAt())) throw factBindingBroken();
            requireFactSource(owner, row.taskId(), manifest.factDraft());
            validateFactTarget(owner, row.targetProjectId(), factId, row.targetVersionId(), manifest.factDraft());
            var materialSources = new ArrayList<MaterialOriginSource>();
            for (var excerpt : manifest.factDraft().materials()) {
                OriginState state = materials.current(owner, List.of(excerpt)) ? OriginState.CURRENT : OriginState.UNAVAILABLE;
                materialSources.add(new MaterialOriginSource(excerpt.excerptId(), excerpt.attachmentId(), excerpt.fileName(), excerpt.sha256(),
                        excerpt.origin(), excerpt.runId(), excerpt.segmentId(), excerpt.locator(), excerpt.startOffset(), excerpt.endOffset(), state));
            }
            var trainingInput = manifest.factDraft().training();
            FactDraftDtos.TrainingOrigin trainingSource = trainingInput == null ? null : new FactDraftDtos.TrainingOrigin(
                    trainingInput.sessionId(), trainingInput.questionId(), trainingInput.answerId(), trainingInput.answerNumber(),
                    trainingInput.answerCondition(), sources.current(owner, manifest, row.taskCreatedAt()) ? OriginState.CURRENT : OriginState.UNAVAILABLE);
            items.add(new FactDraftDtos.Origin(row.taskId(), row.targetProjectId(), factId, row.targetVersionId(), row.targetVersionNumber(),
                    row.adoptedAt(), row.targetVersionId().equals(versionId), manifest.factDraft().kind(), List.copyOf(materialSources), trainingSource));
            previous = row.targetVersionNumber();
        }
        return new FactDraftDtos.OriginPage(List.copyOf(items), rows.size() > limit ? items.getLast().targetVersionNumber() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public JdRequirementsDtos.OriginPage requirementOrigins(String owner, String targetId, String versionId, Integer before, int limit) {
        uuid(owner); uuid(targetId); uuid(versionId); pageLimit(limit);
        if (before != null && before < 1) throw invalid("来源游标必须为正整数版本号。");
        var version = jdRequirements.targetVersion(owner, targetId, versionId);
        if (version == null || !"TARGET".equals(version.kind())) throw new ApiException(404, "PREPARATION_NOT_FOUND", "岗位目标或确切版本不存在或不可访问。");
        if (!owner.equals(version.ownerId()) || !targetId.equals(version.itemId()) || !versionId.equals(version.id()) || version.number() < 1) throw jdBindingBroken();
        var rows = jdRequirements.origins(owner, targetId, version.number(), before, limit + 1);
        var page = rows.subList(0, Math.min(rows.size(), limit));
        var items = new ArrayList<JdRequirementsDtos.Origin>();
        long previous = before == null ? (long) version.number() + 1 : Math.min((long) version.number() + 1, before);
        for (var row : page) {
            Manifest manifest = json.readValue(row.manifestJson(), Manifest.class);
            if (!Purpose.JD_REQUIREMENT_DRAFT.name().equals(row.purpose()) || !Status.SUCCEEDED.name().equals(row.taskStatus())
                    || !AiSources.validJdRequirementsInput(manifest) || !row.manifestHash().equals(sources.hash(manifest))
                    || row.targetVersionNumber() < 1 || row.targetVersionNumber() >= previous || row.adoptedAt() == null) throw jdBindingBroken();
            requireJdSource(owner, row.taskId(), manifest);
            var links = requirementLinks(row.requirementsJson());
            Set<Integer> numbers = jdCandidateNumbers(row.candidateNumbersJson());
            if (links.isEmpty() || links.stream().anyMatch(link -> !numbers.contains(link.candidateNumber()))) throw jdBindingBroken();
            validateRequirementTarget(owner, manifest.sources().getFirst(), targetId, row.targetVersionId(), links);
            Source source = manifest.sources().getFirst();
            var state = preparation.check(owner, PreparationDtos.Kind.TARGET, source.itemId(), source.versionId());
            OriginState originState = OriginState.valueOf(state.state().name());
            if (!Set.of(OriginState.CURRENT, OriginState.SUPERSEDED, OriginState.ARCHIVED, OriginState.UNAVAILABLE).contains(originState)) throw jdBindingBroken();
            items.add(new JdRequirementsDtos.Origin(row.taskId(), row.targetVersionId(), row.targetVersionNumber(), row.adoptedAt(),
                    row.targetVersionId().equals(versionId), new OriginSource(source.itemId(), source.versionId(), source.number(),
                    source.title(), originState, source.kind()), links));
            previous = row.targetVersionNumber();
        }
        return new JdRequirementsDtos.OriginPage(List.copyOf(items), rows.size() > limit ? items.getLast().targetVersionNumber() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OriginPage cardOrigins(String owner, String cardId, String versionId, Integer before, int limit) {
        uuid(owner); uuid(cardId); uuid(versionId); pageLimit(limit);
        if (before != null && before < 1) throw invalid("来源游标必须为正整数版本号。");
        int through = memory.version(owner, cardId, versionId).version().number();
        List<CardDraftRows.OriginRow> rows = cardDrafts.origins(owner, cardId, through, before, limit + 1);
        var page = rows.subList(0, Math.min(rows.size(), limit));
        var manifests = new HashMap<String, Manifest>();
        var sourceMap = new HashMap<String, List<OriginSource>>();
        int previous = before == null ? through + 1 : Math.min(through + 1, before);
        for (var row : page) {
            Manifest manifest = json.readValue(row.manifestJson(), Manifest.class);
            if (!Purpose.MEMORY_CARD_DRAFT.name().equals(row.purpose()) || !AiSources.validCardDraftInput(manifest)
                    || !row.manifestHash().equals(sources.hash(manifest)) || row.targetVersionNumber() < 1
                    || row.targetVersionNumber() >= previous || manifests.put(row.taskId(), manifest) != null) throw cardBindingBroken();
            previous = row.targetVersionNumber();
        }
        if (!page.isEmpty()) for (var source : cardDrafts.originSources(owner, page.stream().map(CardDraftRows.OriginRow::taskId).toList())) {
            Manifest manifest = manifests.get(source.taskId());
            var values = sourceMap.computeIfAbsent(source.taskId(), ignored -> new ArrayList<>());
            int index = source.ordinal() - 1;
            if (manifest == null || index < 0 || index >= manifest.sources().size() || index != values.size()) throw cardBindingBroken();
            Source fixed = manifest.sources().get(index);
            if (!fixed.itemId().equals(source.itemId()) || !fixed.versionId().equals(source.versionId())
                    || fixed.number() != source.number() || !fixed.title().equals(source.title()) || !fixed.kind().equals(source.kind())) throw cardBindingBroken();
            OriginState state = OriginState.valueOf(source.state());
            if (!Set.of(OriginState.CURRENT, OriginState.SUPERSEDED, OriginState.ARCHIVED, OriginState.UNAVAILABLE).contains(state)) throw cardBindingBroken();
            values.add(new OriginSource(fixed.itemId(), fixed.versionId(), fixed.number(), fixed.title(), state, fixed.kind()));
        }
        for (var entry : manifests.entrySet()) {
            if (sourceMap.getOrDefault(entry.getKey(), List.of()).size() != entry.getValue().sources().size()) throw cardBindingBroken();
        }
        var items = page.stream().map(row -> new Origin(row.taskId(), row.targetVersionId(), row.targetVersionNumber(), row.adoptedAt(),
                row.targetVersionId().equals(versionId), List.copyOf(sourceMap.get(row.taskId())), List.of(), null, null)).toList();
        return new OriginPage(items, rows.size() > limit ? items.getLast().targetVersionNumber() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OriginPage questionOrigins(String owner, String questionId, String versionId, Integer before, int limit) {
        uuid(owner); uuid(questionId); uuid(versionId); pageLimit(limit);
        if (before != null && before < 1) throw invalid("来源游标必须为正整数版本号。");
        var version = questions.version(owner, questionId, versionId);
        var rows = mapper.questionOrigins(owner, questionId, version.number(), before, limit + 1);
        return originPage(owner, versionId, rows, limit, true);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OriginPage expressionOrigins(String owner, String expressionId, String versionId, Integer before, int limit) {
        uuid(owner); uuid(expressionId); uuid(versionId); pageLimit(limit);
        if (before != null && before < 1) throw invalid("来源游标必须为正整数版本号。");
        if (preparationService.detail(owner, expressionId).kind() != PreparationDtos.Kind.EXPRESSION) {
            throw invalid("表达采用来源只适用于表达素材。");
        }
        var version = preparationService.version(owner, expressionId, versionId);
        var rows = mapper.expressionOrigins(owner, expressionId, version.number(), before, limit + 1);
        var page = rows.subList(0, Math.min(rows.size(), limit));
        var sourceMap = new HashMap<String, List<OriginSource>>();
        if (!page.isEmpty()) {
            var manifests = new HashMap<String, Manifest>();
            for (OriginRow row : page) {
                Manifest manifest = json.readValue(row.manifestJson(), Manifest.class);
                if (!AiSources.validExpressionInput(manifest) || manifest.reviewDraft() != null
                        || reviewDraftBindings.binding(owner, row.taskId()) != null) throw new IllegalStateException("表达采用历史的固定清单无效");
                manifests.put(row.taskId(), manifest);
            }
            var taskIds = page.stream().map(OriginRow::taskId).toList();
            for (var source : mapper.expressionOriginSources(owner, taskIds)) {
                Manifest manifest = manifests.get(source.taskId());
                var recorded = sourceMap.computeIfAbsent(source.taskId(), ignored -> new ArrayList<>());
                int index = source.ordinal() - 1;
                if (manifest == null || index < 0 || index >= manifest.sources().size() || index != recorded.size()) {
                    throw new IllegalStateException("表达来源关系的数量或顺序与固定清单不一致");
                }
                Source fixed = manifest.sources().get(index);
                if (!fixed.itemId().equals(source.itemId()) || !fixed.versionId().equals(source.versionId())
                        || fixed.number() != source.number() || !fixed.title().equals(source.title()) || !fixed.kind().equals(source.kind())) {
                    throw new IllegalStateException("表达来源关系的确切身份与固定清单不一致");
                }
                if (!"FACT".equals(fixed.kind()) || manifest.preparationChecks() == null
                        || index >= manifest.preparationChecks().size()) {
                    throw new IllegalStateException("表达采用历史缺少固定事实来源核对");
                }
                var captured = manifest.preparationChecks().get(index);
                var checked = preparation.check(owner, PreparationDtos.Kind.FACT, fixed.itemId(), fixed.versionId());
                recorded.add(new OriginSource(fixed.itemId(), fixed.versionId(), fixed.number(), fixed.title(),
                        preparationOriginState(fixed, captured, checked), fixed.kind()));
            }
            for (var entry : manifests.entrySet()) {
                if (sourceMap.getOrDefault(entry.getKey(), List.of()).size() != entry.getValue().sources().size()) {
                    throw new IllegalStateException("表达采用历史缺少确切事实来源");
                }
            }
        }
        var items = page.stream().map(row -> new Origin(row.taskId(), row.targetVersionId(), row.targetVersionNumber(),
                row.adoptedAt(), row.targetVersionId().equals(versionId),
                List.copyOf(sourceMap.getOrDefault(row.taskId(), List.of())), null, null, null)).toList();
        return new OriginPage(items, rows.size() > limit ? items.getLast().targetVersionNumber() : null);
    }

    private OriginPage originPage(String owner, String versionId, List<OriginRow> rows, int limit, boolean question) {
        var page = rows.subList(0, Math.min(rows.size(), limit));
        var sourceMap = new HashMap<String, List<OriginSource>>();
        var materialMap = new HashMap<String, List<MaterialOriginSource>>();
        var trainingMap = new HashMap<String, TrainingDraftOrigin>();
        var reviewMap = new HashMap<String, ReviewDraftDtos.Origin>();
        if (!page.isEmpty()) {
            var taskIds = page.stream().map(OriginRow::taskId).toList();
            var sourceRows = question ? mapper.questionOriginSources(owner, taskIds) : mapper.originSources(owner, taskIds);
            for (var source : sourceRows) {
                sourceMap.computeIfAbsent(source.taskId(), ignored -> new ArrayList<>()).add(
                        new OriginSource(source.itemId(), source.versionId(), source.number(), source.title(),
                                OriginState.valueOf(source.state()), source.kind()));
            }
            preparationOriginSources(owner, page, taskIds, question).forEach((taskId, values) ->
                    sourceMap.computeIfAbsent(taskId, ignored -> new ArrayList<>()).addAll(values));
            materialMap.putAll(materialOriginSources(owner, page, taskIds, question));
            trainingMap.putAll(trainingDraftOriginSources(owner, page, taskIds, question));
            reviewMap.putAll(reviewDraftOriginSources(owner, page, taskIds, question));
        }
        var items = page.stream().map(row -> new Origin(row.taskId(), row.targetVersionId(), row.targetVersionNumber(),
                row.adoptedAt(), row.targetVersionId().equals(versionId),
                List.copyOf(sourceMap.getOrDefault(row.taskId(), List.of())), materialMap.get(row.taskId()),
                trainingMap.get(row.taskId()), reviewMap.get(row.taskId()))).toList();
        return new OriginPage(items, rows.size() > limit ? items.getLast().targetVersionNumber() : null);
    }

    private HashMap<String, List<OriginSource>> preparationOriginSources(String owner, List<OriginRow> rows,
                                                                        List<String> taskIds, boolean question) {
        var relations = new HashMap<String, List<PreparationOriginSourceRow>>();
        for (var source : mapper.preparationOriginSources(owner, taskIds)) {
            if (!taskIds.contains(source.taskId())) throw new IllegalStateException("准备来源关系不属于本页采用历史");
            relations.computeIfAbsent(source.taskId(), ignored -> new ArrayList<>()).add(source);
        }
        var result = new HashMap<String, List<OriginSource>>();
        for (OriginRow row : rows) {
            Manifest manifest = json.readValue(row.manifestJson(), Manifest.class);
            if (manifest.sources() == null || manifest.sources().stream().anyMatch(Objects::isNull)) {
                throw new IllegalStateException("采用历史的固定来源清单无效");
            }
            boolean hasPreparation = manifest.sources().stream().anyMatch(source -> !AiSources.knowledgeKind(source.kind()));
            if ((hasPreparation || manifest.expressionChecks() != null)
                    && (!AiSources.validDraftInput(manifest)
                    || manifest.purpose() != (question ? Purpose.QUESTION_DRAFT : Purpose.KNOWLEDGE_DRAFT))) {
                throw new IllegalStateException("准备来源采用历史的固定清单无效");
            }
            var recorded = relations.getOrDefault(row.taskId(), List.of());
            var values = new ArrayList<OriginSource>();
            int preparationIndex = 0, expressionIndex = 0;
            for (int index = 0; index < manifest.sources().size(); index++) {
                Source fixed = manifest.sources().get(index);
                if (AiSources.knowledgeKind(fixed.kind())) continue;
                if (preparationIndex >= recorded.size()) throw new IllegalStateException("采用历史缺少确切准备来源关系");
                var source = recorded.get(preparationIndex++);
                if (source.ordinal() != index + 1 || !fixed.itemId().equals(source.itemId())
                        || !fixed.versionId().equals(source.versionId()) || fixed.number() != source.number()
                        || !fixed.title().equals(source.title()) || !fixed.kind().equals(source.kind())) {
                    throw new IllegalStateException("准备来源关系的身份、顺序或当时元数据与固定清单不一致");
                }
                OriginState state;
                if ("EXPRESSION".equals(fixed.kind())) {
                    var captured = manifest.expressionChecks().get(expressionIndex++);
                    var current = preparation.checkExpression(owner, fixed.itemId(), fixed.versionId());
                    // 仅比较本条表达及其全部依据；同任务另一独立来源失效不能使该表达误标待复核。
                    state = current.expression().state() == PreparationDtos.SourceState.UNVERIFIED
                            ? OriginState.REVIEW_REQUIRED
                            : current.expression().state() != PreparationDtos.SourceState.CURRENT
                            ? OriginState.valueOf(current.expression().state().name())
                            : captured.equals(current) ? OriginState.CURRENT : OriginState.REVIEW_REQUIRED;
                } else {
                    var checked = preparation.check(owner, PreparationDtos.Kind.valueOf(fixed.kind()), fixed.itemId(), fixed.versionId());
                    if (manifest.preparationChecks() == null || preparationIndex - 1 >= manifest.preparationChecks().size()) {
                        throw new IllegalStateException("采用历史缺少固定准备来源核对");
                    }
                    var captured = manifest.preparationChecks().get(preparationIndex - 1);
                    state = preparationOriginState(fixed, captured, checked);
                }
                values.add(new OriginSource(fixed.itemId(), fixed.versionId(), fixed.number(), fixed.title(), state, fixed.kind()));
            }
            if (preparationIndex != recorded.size()) throw new IllegalStateException("采用历史存在清单之外的准备来源关系");
            if (!values.isEmpty()) result.put(row.taskId(), List.copyOf(values));
        }
        return result;
    }

    private static OriginState preparationOriginState(Source fixed, PreparationDtos.SourceCheck captured,
                                                     PreparationDtos.SourceCheck current) {
        if (fixed == null || captured == null || current == null || captured.kind() == null
                || !fixed.kind().equals(captured.kind().name()) || !fixed.itemId().equals(captured.itemId())
                || !fixed.versionId().equals(captured.versionId()) || current.kind() != captured.kind()
                || !current.itemId().equals(captured.itemId()) || !current.versionId().equals(captured.versionId())) {
            throw new IllegalStateException("固定准备来源核对身份不一致");
        }
        // 当前仍可读但来源代次不同，说明历史采用依据已变更；非 CURRENT 状态保留其原始状态语义。
        if (current.state() == PreparationDtos.SourceState.CURRENT && !captured.equals(current)) {
            return OriginState.REVIEW_REQUIRED;
        }
        return OriginState.valueOf(current.state().name());
    }

    private HashMap<String, List<MaterialOriginSource>> materialOriginSources(String owner, List<OriginRow> rows,
                                                                             List<String> taskIds, boolean question) {
        var manifests = new HashMap<String, Manifest>();
        for (OriginRow row : rows) {
            Manifest manifest = json.readValue(row.manifestJson(), Manifest.class);
            if (manifest.materialDraftSources() == null) continue;
            if (!AiSources.validDraftInput(manifest)
                    || manifest.purpose() != (question ? Purpose.QUESTION_DRAFT : Purpose.KNOWLEDGE_DRAFT)) {
                throw new IllegalStateException("资料候选采用历史的固定清单无效");
            }
            manifests.put(row.taskId(), manifest);
        }
        var result = new HashMap<String, List<MaterialOriginSource>>();
        for (var source : mapper.materialDraftOriginSources(owner, taskIds)) {
            Manifest manifest = manifests.get(source.taskId());
            int index = manifest == null ? -1 : source.ordinal() - manifest.sources().size() - 1;
            if (manifest == null || index < 0 || index >= manifest.materialDraftSources().size()) {
                throw new IllegalStateException("资料候选来源关系与固定清单不一致");
            }
            var excerpt = manifest.materialDraftSources().get(index);
            if (!source.excerptId().equals(excerpt.excerptId())) {
                throw new IllegalStateException("资料候选来源关系与固定摘录不一致");
            }
            // 当时元数据只取原授权清单；当前不可用不抹掉历史，真实数据库故障继续传播。
            OriginState state = materials.current(owner, List.of(excerpt)) ? OriginState.CURRENT : OriginState.UNAVAILABLE;
            result.computeIfAbsent(source.taskId(), ignored -> new ArrayList<>()).add(new MaterialOriginSource(
                    excerpt.excerptId(), excerpt.attachmentId(), excerpt.fileName(), excerpt.sha256(), excerpt.origin(),
                    excerpt.runId(), excerpt.segmentId(), excerpt.locator(), excerpt.startOffset(), excerpt.endOffset(), state));
        }
        for (var entry : manifests.entrySet()) {
            if (result.getOrDefault(entry.getKey(), List.of()).size() != entry.getValue().materialDraftSources().size()) {
                throw new IllegalStateException("资料候选采用历史缺少确切来源关系");
            }
        }
        result.replaceAll((taskId, values) -> List.copyOf(values));
        return result;
    }

    private HashMap<String, TrainingDraftOrigin> trainingDraftOriginSources(String owner, List<OriginRow> rows,
                                                                            List<String> taskIds, boolean question) {
        var manifests = new HashMap<String, Manifest>();
        for (OriginRow row : rows) {
            Manifest manifest = json.readValue(row.manifestJson(), Manifest.class);
            if (manifest.trainingDraft() == null) continue;
            if (question || !AiSources.validDraftInput(manifest) || manifest.purpose() != Purpose.KNOWLEDGE_DRAFT) {
                throw new IllegalStateException("训练片段采用历史的固定清单无效");
            }
            manifests.put(row.taskId(), manifest);
        }
        var result = new HashMap<String, TrainingDraftOrigin>();
        for (TrainingDraftSourceRow source : mapper.trainingDraftOriginSources(owner, taskIds)) {
            Manifest manifest = manifests.get(source.taskId());
            if (manifest == null || !owner.equals(source.ownerId()) || result.containsKey(source.taskId())) {
                throw new IllegalStateException("训练片段来源关系与采用清单不一致");
            }
            TrainingDraftInput input = manifest.trainingDraft();
            if (!input.sessionId().equals(source.sessionId()) || !input.questionId().equals(source.questionId())
                    || !input.answerId().equals(source.answerId()) || !Objects.equals(input.feedbackTaskId(), source.evaluationTaskId())) {
                throw new IllegalStateException("训练片段来源关系与确切提交或评价身份不一致");
            }
            // 原题与提交身份只取历史清单；当前不可用只改变提示，数据库故障不得伪装成来源失效。
            OriginState state = sources.current(owner, manifest) ? OriginState.CURRENT : OriginState.UNAVAILABLE;
            result.put(source.taskId(), new TrainingDraftOrigin(input.sessionId(), input.questionId(), input.answerId(),
                    input.answerNumber(), input.question().title(), input.feedbackTaskId(),
                    input.feedback().stream().map(EvaluationObservation::id).toList(), state));
        }
        if (!result.keySet().equals(manifests.keySet())) {
            throw new IllegalStateException("训练片段采用历史缺少确切来源关系");
        }
        return result;
    }

    private HashMap<String, ReviewDraftDtos.Origin> reviewDraftOriginSources(String owner, List<OriginRow> rows,
                                                                           List<String> taskIds, boolean question) {
        var bindings = new HashMap<String, ReviewDraftBindingRows.Binding>();
        for (var binding : reviewDraftBindings.bindings(owner, taskIds)) {
            if (!owner.equals(binding.ownerId()) || !taskIds.contains(binding.taskId())
                    || bindings.put(binding.taskId(), binding) != null) throw reviewBindingBroken();
        }
        var result = new HashMap<String, ReviewDraftDtos.Origin>();
        for (OriginRow row : rows) {
            Manifest manifest = json.readValue(row.manifestJson(), Manifest.class);
            var binding = bindings.get(row.taskId());
            if (manifest.reviewDraft() == null) {
                if (binding != null) throw reviewBindingBroken();
                continue;
            }
            if (question || !AiSources.validReviewDraftInput(manifest)
                    || !reviewBinding(owner, row.taskId(), manifest.reviewDraft()).equals(binding)) throw reviewBindingBroken();
            // 采用来源只输出当时确切身份；读取仍核原答权限，后续修订／归档仅改变历史状态。
            reviewDrafts.requireAccess(owner, manifest.reviewDraft());
            result.put(row.taskId(), reviewDrafts.origin(owner, manifest.reviewDraft()));
        }
        return result;
    }

    public Task create(String owner, String key, CreateTask request) {
        required(request);
        consent(request.consentConfirmed());
        if (request.expectedManifestHash() == null || !request.expectedManifestHash().matches("[0-9a-f]{64}")) {
            throw invalid("请先预览本次实际外发清单。");
        }
        return commands.execute(owner, key, "POST /api/v1/ai/tasks", request, Task.class, () -> {
            recoverWithinTransaction(owner);
            properties.requireReady();
            Manifest manifest = sources.manifest(owner, new PreviewRequest(request.purpose(), request.instruction(), request.sources()));
            String hash = sources.hash(manifest);
            if (!hash.equals(request.expectedManifestHash())) {
                throw new ApiException(409, "AI_MANIFEST_CHANGED", "来源、配置或价格已变化，请重新预览外发清单。");
            }
            capacity(owner);
            reserve(owner, manifest.configuration(), manifest.reservedMicros());
            Instant now = now();
            String id = newId(), attemptId = newId();
            String title = manifest.sources().getFirst().title();
            TaskRow task = new TaskRow(id, owner, manifest.purpose().name(), Status.QUEUED.name(), 0, title,
                    manifest.configuration().provider(), manifest.configuration().model(), manifest.configuration().currency(),
                    json.writeValueAsString(manifest), hash, properties.fingerprint(), properties.runtimeEpoch(),
                    attemptId, 1, null, null, now, now);
            insertTask(task);
            mapper.insertAttempt(newAttempt(task, attemptId, 1, now, request.authorization()));
            return view(owner, task);
        });
    }

    public Task cancel(String owner, String id, String key, ChangeTask request) {
        return cancelCommand(owner, id, key, request, null, Task.class, row -> view(owner, row));
    }

    private <T> T cancelCommand(String owner, String id, String key, ChangeTask request, String sessionId,
                                Class<T> type, Function<TaskRow, T> response) {
        uuid(id); required(request); revision(request.expectedRevision());
        if (sessionId != null) uuid(sessionId);
        String commandOperation = taskOperation(sessionId, id, "cancel");
        return commands.execute(owner, key, commandOperation, request, type,
                () -> requireCommandReceiptAccess(owner, id, key, commandOperation), () -> {
            if (sessionId != null) requireFollowUp(owner, sessionId, id);
            recoverWithinTransaction(owner);
            TaskRow task = changeable(owner, id, request.expectedRevision());
            AttemptRow attempt = requireAttempt(owner, id, task.currentAttemptId());
            Instant now = now();
            if (Status.QUEUED.name().equals(task.status())) {
                replaceAttempt(attempt, Status.CANCELED.name(), FeeStatus.RELEASED.name(), null, null, null,
                        null, "AI_CANCELED_BEFORE_SEND", attempt.reconciliationNote(), attempt.budgetDate(),
                        attempt.claimToken(), attempt.runtimeEpoch(), null, attempt.sentAt(), now);
                replaceTask(task, Status.CANCELED.name(), null, "AI_CANCELED_BEFORE_SEND", task.currentAttemptId(),
                        task.attemptCount(), task.authorizationEpoch(), now);
            } else if (SENT.contains(task.status())) {
                if (Status.CANCEL_REQUESTED.name().equals(task.status())) throw stateConflict();
                replaceAttempt(attempt, Status.CANCEL_REQUESTED.name(), attempt.feeStatus(), attempt.accountedMicros(),
                        attempt.promptTokens(), attempt.completionTokens(), attempt.providerRequestId(), "AI_CANCEL_REQUESTED",
                        attempt.reconciliationNote(), attempt.budgetDate(), attempt.claimToken(), attempt.runtimeEpoch(),
                        attempt.leaseUntil(), attempt.sentAt(), attempt.finishedAt());
                replaceTask(task, Status.CANCEL_REQUESTED.name(), null, "AI_CANCEL_REQUESTED", task.currentAttemptId(),
                        task.attemptCount(), task.authorizationEpoch(), now);
            } else if (Status.UNKNOWN.name().equals(task.status())) {
                replaceAttempt(attempt, Status.CANCELED.name(), attempt.feeStatus(), attempt.accountedMicros(),
                        attempt.promptTokens(), attempt.completionTokens(), attempt.providerRequestId(), "AI_CANCELED_UNKNOWN_COST",
                        attempt.reconciliationNote(), attempt.budgetDate(), attempt.claimToken(), attempt.runtimeEpoch(),
                        null, attempt.sentAt(), now);
                replaceTask(task, Status.CANCELED.name(), null, "AI_CANCELED_UNKNOWN_COST", task.currentAttemptId(),
                        task.attemptCount(), task.authorizationEpoch(), now);
            } else throw stateConflict();
            return response.apply(requireTask(owner, id));
        });
    }

    public Task retry(String owner, String id, String key, RetryTask request) {
        return retryCommand(owner, id, key, request, null, Task.class, row -> view(owner, row));
    }

    private <T> T retryCommand(String owner, String id, String key, RetryTask request, String sessionId,
                               Class<T> type, Function<TaskRow, T> response) {
        uuid(id); required(request); revision(request.expectedRevision()); consent(request.consentConfirmed());
        if (sessionId != null) uuid(sessionId);
        String commandOperation = taskOperation(sessionId, id, "retry");
        return commands.execute(owner, key, commandOperation, request, type,
                () -> requireCommandReceiptAccess(owner, id, key, commandOperation), () -> {
            if (sessionId != null) requireFollowUp(owner, sessionId, id);
            recoverWithinTransaction(owner);
            TaskRow task = changeable(owner, id, request.expectedRevision());
            if (Purpose.FOLLOW_UP.name().equals(task.purpose()) && sessionId == null) {
                throw new ApiException(409, "AI_FOLLOW_UP_ROUTE_REQUIRED", "请回到该训练的追问入口核对原答和授权。");
            }
            if (Purpose.RELATION_DRAFT.name().equals(task.purpose()) && !mapper.relationDecisions(owner, id).isEmpty()) {
                throw new ApiException(409, "AI_CANDIDATE_DECIDED", "此任务已有关系候选决定，不能重新生成覆盖原候选。", task.revision());
            }
            if (!Set.of("FAILED", "UNKNOWN", "CANCELED").contains(task.status())) throw stateConflict();
            properties.requireReady();
            if (!properties.fingerprint().equals(task.configurationFingerprint())) throw configurationChanged();
            Manifest manifest = manifest(task);
            requireReviewDraftBinding(owner, task, manifest);
            if (!comparisonBindingMatches(owner, task, manifest)) {
                throw new ApiException(409, "AI_SOURCE_CHANGED", "比较任务与确切来源绑定不一致，请重新核对。");
            }
            if (manifest.purpose() == Purpose.FOLLOW_UP) training.requireAvailable(owner, manifest.training(), id);
            if (manifest.purpose() == Purpose.ANSWER_EVALUATION) evaluations.requireAvailable(owner, manifest.evaluation(), id);
            if (manifest.purpose() == Purpose.ANSWER_REWRITE) rewrites.requireAvailable(owner, manifest.evaluation(), id);
            if (manifest.purpose() == Purpose.ANSWER_COMPARISON) {
                ComparisonDtos.Link binding = comparisonSources.binding(owner, id, manifest.comparisonInput());
                comparisonSources.requireAvailable(owner, binding.comparisonId(), id);
            }
            sources.requireCurrent(owner, manifest, task.createdAt());
            if (task.attemptCount() >= 3 || task.attemptCount() >= manifest.configuration().maxAttempts()) {
                throw limit("此任务已达到执行尝试上限，请审阅现有结果与费用。");
            }
            boolean unknownCost = mapper.attempts(owner, id).stream().anyMatch(attempt -> FeeStatus.UNKNOWN.name().equals(attempt.feeStatus()));
            if (unknownCost && !Boolean.TRUE.equals(request.acknowledgeUnknownCost())) {
                throw new ApiException(409, "AI_UNKNOWN_COST_CONFIRMATION_REQUIRED", "旧尝试费用尚未知，请明确确认继续占用该预留并新增一次尝试。");
            }
            capacity(owner);
            reserve(owner, manifest.configuration(), manifest.reservedMicros());
            Instant now = now();
            String attemptId = newId();
            TaskRow next = replaceTask(task, Status.QUEUED.name(), null, null, attemptId, task.attemptCount() + 1,
                    properties.runtimeEpoch(), now);
            mapper.insertAttempt(newAttempt(next, attemptId, next.attemptCount(), now, request.authorization()));
            return response.apply(next);
        });
    }

    public Task reconcile(String owner, String id, String attemptId, String key, Reconcile request) {
        return reconcileCommand(owner, id, attemptId, key, request, null, Task.class, row -> view(owner, row));
    }

    private <T> T reconcileCommand(String owner, String id, String attemptId, String key, Reconcile request,
                                   String sessionId, Class<T> type, Function<TaskRow, T> response) {
        uuid(id); uuid(attemptId); required(request); revision(request.expectedRevision());
        if (sessionId != null) uuid(sessionId);
        if (request.amountMicros() == null || request.amountMicros() < 0 || request.amountMicros() > MAX_SAFE) {
            throw invalid("核对金额必须是有效的非负整数微单位。");
        }
        String note = text(request.note(), 1000, true);
        String commandOperation = taskOperation(sessionId, id, "attempts/" + attemptId + "/reconcile");
        return commands.execute(owner, key, commandOperation,
                request, type, () -> requireCommandReceiptAccess(owner, id, key, commandOperation), () -> {
                    if (sessionId != null) requireFollowUp(owner, sessionId, id);
                    recoverWithinTransaction(owner);
                    TaskRow task = changeable(owner, id, request.expectedRevision());
                    AttemptRow attempt = requireAttempt(owner, id, attemptId);
                    if (!FeeStatus.UNKNOWN.name().equals(attempt.feeStatus())) throw stateConflict();
                    replaceAttempt(attempt, attempt.status(), FeeStatus.RECONCILED.name(), request.amountMicros(),
                            attempt.promptTokens(), attempt.completionTokens(), attempt.providerRequestId(), attempt.errorCode(), note,
                            attempt.budgetDate(), attempt.claimToken(), attempt.runtimeEpoch(), attempt.leaseUntil(), attempt.sentAt(), attempt.finishedAt());
                    replaceTask(task, task.status(), task.candidateJson(), task.errorCode(), task.currentAttemptId(),
                            task.attemptCount(), task.authorizationEpoch(), now());
                    return response.apply(requireTask(owner, id));
                });
    }

    /** 在短事务中记录可能发送的边界；返回后才允许执行网络调用。 */
    public Claim claim(String owner) {
        return locked(owner, () -> {
            recoverWithinTransaction(owner);
            Configuration configuration = properties.configuration();
            if (!configuration.enabled() || !configuration.configured() || mapper.runningCount(owner) != 0) return null;
            TaskRow task = mapper.queued(owner);
            if (task == null) return null;
            AttemptRow attempt = requireAttempt(owner, task.id(), task.currentAttemptId());
            Manifest manifest = manifest(task);
            String refusal = beforeSendError(owner, task, attempt, manifest);
            if (refusal != null) {
                failUnsent(task, attempt, refusal);
                return null;
            }
            Instant now = now();
            String token = newId();
            Instant lease = now.plusSeconds(manifest.configuration().timeoutSeconds() + 60L);
            replaceAttempt(attempt, Status.RUNNING.name(), FeeStatus.RESERVED.name(), null, null, null, null, null, null,
                    utcDate(now), token, properties.runtimeEpoch(), lease, now, null);
            replaceTask(task, Status.RUNNING.name(), null, null, task.currentAttemptId(), task.attemptCount(), task.authorizationEpoch(), now);
            return new Claim(owner, task.id(), attempt.id(), token, properties.runtimeEpoch(), manifest);
        });
    }

    /** 网络调用前最后检查取消与租约；发送边界已经持久化，崩溃一律保守视为未知。 */
    public boolean maySend(Claim claim) {
        return locked(claim.owner(), () -> {
            recoverWithinTransaction(claim.owner());
            TaskRow task = requireTask(claim.owner(), claim.taskId());
            AttemptRow attempt = requireAttempt(claim.owner(), claim.taskId(), claim.attemptId());
            if (!validClaim(claim, attempt, now()) || !task.currentAttemptId().equals(attempt.id())) return false;
            if (Status.CANCEL_REQUESTED.name().equals(task.status())) {
                String authorizationError = authorizations.refusal(claim.owner(), attempt, claim.manifest());
                failKnownUnsent(task, attempt, authorizationError == null ? "AI_CANCELED_BEFORE_SEND" : authorizationError, true);
                return false;
            }
            if (!Status.RUNNING.name().equals(task.status())) return false;
            String refusal = beforeSendError(claim.owner(), task, attempt, claim.manifest());
            if (refusal != null) {
                failKnownUnsent(task, attempt, refusal, false);
                return false;
            }
            return true;
        });
    }

    public void finish(Claim claim, ModelGateway.Result result) {
        locked(claim.owner(), () -> {
            recoverWithinTransaction(claim.owner());
            TaskRow task = requireTask(claim.owner(), claim.taskId());
            AttemptRow attempt = requireAttempt(claim.owner(), claim.taskId(), claim.attemptId());
            if (!claim.token().equals(attempt.claimToken()) || !claim.epoch().equals(attempt.runtimeEpoch())) return null;
            requireReviewDraftBinding(claim.owner(), task, claim.manifest());
            Instant now = now();
            boolean current = task.currentAttemptId().equals(attempt.id());
            boolean canceled = Status.CANCEL_REQUESTED.name().equals(attempt.status())
                    || (current && Status.CANCEL_REQUESTED.name().equals(task.status()));
            boolean live = current && validClaim(claim, attempt, now) && Status.RUNNING.name().equals(task.status());
            Accounting fee = accounting(claim.manifest(), attempt, result);
            String providerId = result == null ? attempt.providerRequestId() : safeProviderId(result.providerRequestId());
            if (providerId == null) providerId = attempt.providerRequestId();
            if (!SENT.contains(attempt.status())) {
                // 终态结果不可再发布或改写；迟到用量只补费用，不抹掉既有候选或用户核对金额。
                if (FeeStatus.RELEASED.name().equals(attempt.feeStatus()) || sameAccounting(attempt, fee, providerId)) return null;
                replaceAttempt(attempt, attempt.status(), fee.status(), fee.amount(), fee.promptTokens(), fee.completionTokens(),
                        providerId, attempt.errorCode(), attempt.reconciliationNote(), attempt.budgetDate(), attempt.claimToken(),
                        attempt.runtimeEpoch(), null, attempt.sentAt(), attempt.finishedAt());
                replaceTask(task, task.status(), task.candidateJson(), task.errorCode(), task.currentAttemptId(),
                        task.attemptCount(), task.authorizationEpoch(), now);
                return null;
            }
            String error = result == null ? "AI_OUTCOME_UNKNOWN" : safeError(result.errorCode());
            String trainingError = claim.manifest().purpose() == Purpose.FOLLOW_UP
                    ? training.availability(claim.owner(), claim.manifest().training(), task.id()) : null;
            String authorizationError = authorizations.refusal(claim.owner(), attempt, claim.manifest());
            String status;
            String candidateJson = null;
            if (canceled) {
                status = Status.CANCELED.name();
                error = authorizationError == null ? "AI_CANCELED_AFTER_SEND" : authorizationError;
            } else if (!live) {
                status = Status.UNKNOWN.name();
                error = "AI_RESULT_NOT_ACCEPTED";
            } else if (!configurationMatches(task) || !properties.runtimeEpoch().equals(task.authorizationEpoch())) {
                status = Status.FAILED.name();
                error = "AI_CONFIG_CHANGED";
            } else if (authorizationError != null) {
                // 已发生用量仍结算；许可失效只能阻止候选发布，不能把本次调用改成免费。
                status = Status.FAILED.name();
                error = authorizationError;
            } else if (trainingError != null) {
                status = Status.FAILED.name();
                error = trainingError;
            } else if (!comparisonBindingMatches(claim.owner(), task, claim.manifest())
                    || !sources.current(claim.owner(), claim.manifest(), task.createdAt())) {
                status = Status.FAILED.name();
                error = "AI_SOURCE_CHANGED";
            } else if (result == null || UNKNOWN_OUTCOMES.contains(error == null ? "" : error)) status = Status.UNKNOWN.name();
            else if (!matchesCandidate(claim.manifest().purpose(), result) || error != null
                    || (claim.manifest().purpose() == Purpose.FACT_DRAFT
                        && !FactDraftSources.validCandidate(claim.manifest().factDraft(), result.factCandidate()))
                    || (claim.manifest().purpose() == Purpose.RELATION_DRAFT
                        && (claim.manifest().relationEvidence() != null || claim.manifest().reviewDraft() != null
                            || claim.manifest().relationAiFeedback() != null)
                        && !validRelationResult(claim.manifest(), result))
                    || (claim.manifest().purpose() == Purpose.ANSWER_REWRITE && !validRewriteResult(claim.manifest(), result))
                    || (claim.manifest().purpose() == Purpose.SOURCE_QA && !validSourceQaResult(claim.manifest(), result))) {
                status = Status.FAILED.name();
                if (error == null) error = "AI_RESULT_INVALID";
            } else {
                status = Status.SUCCEEDED.name();
                Object candidate = switch (claim.manifest().purpose()) {
                    case KNOWLEDGE_DRAFT -> result.candidate();
                    case QUESTION_DRAFT -> result.questionCandidate();
                    case FOLLOW_UP -> result.followUpCandidate();
                    case ANSWER_EVALUATION -> result.evaluationReport();
                    case ANSWER_COMPARISON -> result.comparisonCandidate();
                    case MATERIAL_QA -> result.materialAnswer();
                    case SOURCE_QA -> result.sourceQaAnswer();
                    case EXPRESSION_DRAFT -> result.expressionCandidate();
                    case PREPARATION_PLAN -> result.preparationPlanCandidate();
                    case RELATION_DRAFT -> result.relationCandidate();
                    case MEMORY_CARD_DRAFT -> result.cardCandidate();
                    case JD_REQUIREMENT_DRAFT -> result.jdRequirementsCandidate();
                    case FACT_DRAFT -> result.factCandidate();
                    case ANSWER_REWRITE -> result.rewriteCandidate();
                };
                if (claim.manifest().purpose() == Purpose.FOLLOW_UP) {
                    // 来源、生命周期和容量已在同一 owner 锁内核对；失效先记失败，避免回滚已产生费用。
                    var binding = requireFollowUp(claim.owner(), claim.manifest().training().sessionId(), task.id());
                    training.append(claim.owner(), binding, claim.manifest().training(), result.followUpCandidate(), now);
                }
                candidateJson = json.writeValueAsString(candidate);
                if (claim.manifest().purpose() == Purpose.FACT_DRAFT) gateway.readFactCandidate(candidateJson, claim.manifest());
            }
            // 迟到响应只能补充费用，不能绕过令牌、取消或租约重新发布候选；人工核对金额也不被覆盖。
            replaceAttempt(attempt, status, fee.status(), fee.amount(), fee.promptTokens(), fee.completionTokens(),
                    providerId, error,
                    attempt.reconciliationNote(), attempt.budgetDate(), attempt.claimToken(), attempt.runtimeEpoch(),
                    null, attempt.sentAt(), now);
            if (current) replaceTask(task, status, candidateJson, error, task.currentAttemptId(), task.attemptCount(), task.authorizationEpoch(), now);
            else replaceTask(task, task.status(), task.candidateJson(), task.errorCode(), task.currentAttemptId(),
                    task.attemptCount(), task.authorizationEpoch(), now);
            return null;
        });
    }

    private String beforeSendError(String owner, TaskRow task, AttemptRow attempt, Manifest manifest) {
        requireReviewDraftBinding(owner, task, manifest);
        if (!configurationMatches(task)) return "AI_CONFIG_CHANGED";
        if (!properties.runtimeEpoch().equals(task.authorizationEpoch())) return "AI_AUTHORIZATION_EXPIRED";
        String authorizationError = authorizations.refusal(owner, attempt, manifest);
        if (authorizationError != null) return authorizationError;
        if (manifest.purpose() == Purpose.FOLLOW_UP) {
            String error = training.availability(owner, manifest.training(), task.id());
            if (error != null) return error;
        }
        if (!comparisonBindingMatches(owner, task, manifest) || !sources.current(owner, manifest, task.createdAt())) return "AI_SOURCE_CHANGED";
        BudgetRow totals = mapper.budget(owner, manifest.configuration().currency(), today());
        if (totals.unaccountableCount() > 0) return "AI_CURRENCY_PENDING";
        if (total(totals).compareTo(BigDecimal.valueOf(manifest.configuration().dailyBudgetMicros())) > 0) {
            return "AI_BUDGET_EXCEEDED";
        }
        return null;
    }

    private boolean comparisonBindingMatches(String owner, TaskRow task, Manifest manifest) {
        if (!Purpose.ANSWER_COMPARISON.name().equals(task.purpose())) return manifest.purpose() != Purpose.ANSWER_COMPARISON;
        if (!AiSources.validComparisonInput(manifest)) return false;
        try {
            ComparisonDtos.Link binding = comparisonSources.binding(owner, task.id(), manifest.comparisonInput());
            return binding != null && manifest.comparisonInput().comparisonId().equals(binding.comparisonId());
        } catch (ApiException exception) {
            // 来源失效在任务内落成失败；不能回滚已经发生的调用与费用。
            return false;
        }
    }

    private static ReviewDraftBindingRows.Binding reviewBinding(String owner, String taskId, ReviewDraftDtos.Input input) {
        if (!ReviewDraftSources.valid(input)) throw reviewBindingBroken();
        if (input.kind() == ReviewDraftDtos.Kind.RECOLLECTION) {
            var fixed = input.recollection();
            return new ReviewDraftBindingRows.Binding(owner, taskId, input.kind().name(), null, null, null, null, null,
                    null, null, fixed.recollectionId(), fixed.versionId(), fixed.sourceRevision());
        }
        var fixed = input.feedback();
        return new ReviewDraftBindingRows.Binding(owner, taskId, input.kind().name(), fixed.feedbackId(), fixed.feedbackRevision(),
                fixed.answer().sessionId(), fixed.answer().questionId(), fixed.answer().answerId(),
                fixed.decisionRevision() == 0 ? null : fixed.decisionRevision(),
                fixed.decisionChoice() == null ? null : fixed.decisionChoice().name(), null, null, null);
    }

    private void requireReviewDraftBinding(String owner, TaskRow task, Manifest manifest) {
        requireSourceQaBinding(owner, task, manifest);
        requireRelationAiFeedbackBinding(owner, task, manifest);
        requireRelationEvidenceBinding(owner, task, manifest);
        requireRewriteBinding(owner, task, manifest);
        requireCardDraftBinding(owner, task, manifest);
        requireJdRequirementsBinding(owner, task, manifest);
        requireFactDraftBinding(owner, task, manifest);
        var binding = reviewDraftBindings.binding(owner, task.id());
        if (manifest.reviewDraft() == null) {
            if (binding != null) throw reviewBindingBroken();
            return;
        }
        // 共享来源台账不共享用途权限；原知识整理仍要求空知识列表，关系模式走独立的封闭清单。
        boolean relationReview = Purpose.RELATION_DRAFT.name().equals(task.purpose());
        boolean validInput = relationReview ? AiSources.validRelationReviewInput(manifest)
                : Purpose.KNOWLEDGE_DRAFT.name().equals(task.purpose()) && AiSources.validReviewDraftInput(manifest);
        if (!owner.equals(task.ownerId()) || !validInput || !Objects.equals(task.manifestHash(), sources.hash(manifest))
                || !reviewBinding(owner, task.id(), manifest.reviewDraft()).equals(binding)) throw reviewBindingBroken();
        if (relationReview) {
            // 复盘与知识台账共同固定授权；缺行或身份漂移不能等同正常改版，也不能留到采用时才发现。
            relationKnowledgeSources(owner, task, manifest);
            // 固定候选损坏不属于来源过期，后台不能据此释放预算或重新接纳其他结果。
            if (Status.SUCCEEDED.name().equals(task.status()) != (task.candidateJson() != null)) throw reviewBindingBroken();
            if (task.candidateJson() != null) gateway.readRelationCandidate(task.candidateJson(), manifest);
        }
    }

    private void requireReviewDraftAccess(String owner, String taskId) {
        TaskRow task = requireTask(owner, taskId);
        requireReviewDraftAccess(owner, task, manifest(task));
    }

    private void requireReviewDraftAccess(String owner, TaskRow task, Manifest manifest) {
        requireReviewDraftBinding(owner, task, manifest);
        // 权限与当前版本是两件事；历史候选可查看／忽略，但原答访问不能从回执旁路。
        if (manifest.reviewDraft() != null) reviewDrafts.requireAccess(owner, manifest.reviewDraft());
        if (manifest.relationAiFeedback() != null) relationAiFeedback.requireAccess(owner, manifest.relationAiFeedback());
        if (manifest.sourceQa() != null) sourceQa.requireTaskAccess(owner, task.id());
    }

    private void requireSourceQaCreateReceipt(String owner, String key, SourceQaDtos.CreateRequest request) {
        var receipt = workspaces.findReceipt(owner, key);
        if (receipt == null) {
            sourceQa.requireSelectionAccess(owner, new SourceQaDtos.PreviewRequest(request.question(), request.selections()));
            return;
        }
        if (!"POST /api/v1/ai/source-qa/tasks".equals(receipt.operation())) return;
        Task issued = json.readValue(receipt.responseJson(), Task.class);
        if (issued == null || issued.task() == null || issued.manifest() == null
                || issued.task().purpose() != Purpose.SOURCE_QA || !AiSources.validSourceQaInput(issued.manifest())) throw sourceQaBindingBroken();
        try { uuid(issued.task().id()); }
        catch (ApiException exception) { throw sourceQaBindingBroken(); }
        TaskRow current = mapper.task(owner, issued.task().id());
        if (current == null) throw sourceQaBindingBroken();
        requireSourceQaReceiptAccess(owner, current, issued);
    }

    private void requireCommandReceiptAccess(String owner, String taskId, String key, String operation) {
        TaskRow current = requireTask(owner, taskId);
        if (Purpose.SOURCE_QA.name().equals(current.purpose())) {
            var receipt = workspaces.findReceipt(owner, key);
            if (receipt != null && operation.equals(receipt.operation())) {
                requireSourceQaReceiptAccess(owner, current, json.readValue(receipt.responseJson(), Task.class));
                return;
            }
        }
        requireReviewDraftAccess(owner, current, manifest(current));
    }

    private void requireSourceQaReceiptAccess(String owner, TaskRow current, Task issued) {
        if (issued == null || issued.task() == null || issued.manifest() == null
                || issued.task().purpose() != Purpose.SOURCE_QA || !current.id().equals(issued.task().id())
                || !AiSources.validSourceQaInput(issued.manifest())) throw sourceQaBindingBroken();
        Manifest fixed = manifest(current);
        if (!owner.equals(current.ownerId()) || !Purpose.SOURCE_QA.name().equals(current.purpose())
                || !issued.manifest().equals(fixed) || !Objects.equals(issued.sourceQaManifestHash(), current.manifestHash())
                || issued.task().revision() < 0 || issued.task().revision() > current.revision()) throw sourceQaBindingBroken();
        requireReviewDraftBinding(owner, current, fixed);
        // 已签发操作不能因暂时失权丢失原键；恢复只核查看许可，不重算新发资格。
        try { sourceQa.requireTaskAccess(owner, current.id()); }
        catch (ApiException exception) {
            if (!Set.of("AI_EVALUATION_ACCESS_REQUIRED", "AI_EARLY_FEEDBACK_CONFIRMATION_REQUIRED",
                    "AI_REVIEW_SOURCE_NOT_FOUND", "AI_SOURCE_CHANGED", "SEARCH_SOURCE_NOT_FOUND",
                    "RELATION_AI_FEEDBACK_SOURCE_NOT_FOUND", "RELATION_AI_FEEDBACK_SOURCE_CHANGED").contains(exception.code())) throw exception;
            throw new ApiException(503, "AI_SOURCE_QA_RECEIPT_UNAVAILABLE",
                    "任务操作已经保存，但来源读取条件尚无法核实。请恢复权限后使用原请求重试以取回回执。");
        }
    }

    private void requireSourceQaBinding(String owner, TaskRow task, Manifest manifest) {
        if (manifest == null) throw sourceQaBindingBroken();
        if (manifest.sourceQa() == null && !Purpose.SOURCE_QA.name().equals(task.purpose())) {
            sourceQa.requireBinding(owner, task.id(), null);
            return;
        }
        if (!owner.equals(task.ownerId()) || !Purpose.SOURCE_QA.name().equals(task.purpose())
                || !AiSources.validSourceQaInput(manifest) || !Objects.equals(task.manifestHash(), sources.hash(manifest))) throw sourceQaBindingBroken();
        sourceQa.requireBinding(owner, task.id(), manifest.sourceQa());
        if (Status.SUCCEEDED.name().equals(task.status()) != (task.candidateJson() != null)) throw sourceQaBindingBroken();
        if (task.candidateJson() != null) gateway.readSourceQaAnswer(task.candidateJson(), manifest);
    }

    private boolean validSourceQaResult(Manifest manifest, ModelGateway.Result result) {
        if (result == null || result.sourceQaAnswer() == null) return false;
        try {
            return result.sourceQaAnswer().equals(gateway.readSourceQaAnswer(json.writeValueAsString(result.sourceQaAnswer()), manifest));
        } catch (RuntimeException exception) { return false; }
    }

    private static IllegalStateException sourceQaBindingBroken() {
        return new IllegalStateException("跨域问答任务与固定清单、来源绑定或保存结果不一致");
    }

    private void requireRelationAiFeedbackCreateReceipt(String owner, String key, RelationAiFeedbackEvidenceDtos.Selection selected) {
        var receipt = workspaces.findReceipt(owner, key);
        if (receipt == null) {
            relationAiFeedback.requireAccess(owner, selected);
            return;
        }
        if (!"POST /api/v1/ai/relation-ai-feedback-drafts/tasks".equals(receipt.operation())) return;
        Task issued = json.readValue(receipt.responseJson(), Task.class);
        if (issued == null || issued.task() == null || issued.manifest() == null
                || issued.task().purpose() != Purpose.RELATION_DRAFT || !AiSources.validRelationAiFeedbackInput(issued.manifest())) {
            throw relationAiFeedbackBindingBroken();
        }
        try { uuid(issued.task().id()); }
        catch (ApiException exception) { throw relationAiFeedbackBindingBroken(); }
        TaskRow current = mapper.task(owner, issued.task().id());
        if (current == null) throw relationAiFeedbackBindingBroken();
        Manifest fixed = manifest(current);
        // 原键恢复只依赖已签发清单、完整绑定和查看权限；来源后续决定不抹去原回执。
        requireReviewDraftAccess(owner, current, fixed);
        if (!issued.manifest().equals(fixed) || issued.task().revision() < 0
                || issued.task().revision() > current.revision()) throw relationAiFeedbackBindingBroken();
    }

    private void requireRelationAiFeedbackBinding(String owner, TaskRow task, Manifest manifest) {
        if (manifest.relationAiFeedback() == null) {
            relationAiFeedback.requireBinding(owner, task.id(), null);
            return;
        }
        if (!owner.equals(task.ownerId()) || !Purpose.RELATION_DRAFT.name().equals(task.purpose())
                || !AiSources.validRelationAiFeedbackInput(manifest)
                || !Objects.equals(task.manifestHash(), sources.hash(manifest))) throw relationAiFeedbackBindingBroken();
        relationAiFeedback.requireBinding(owner, task.id(), manifest.relationAiFeedback());
        // 两种台账都须保留完整身份，不能把缺行、改写或坏候选降为正常来源过期。
        relationKnowledgeSources(owner, task, manifest);
        if (Status.SUCCEEDED.name().equals(task.status()) != (task.candidateJson() != null)) throw relationAiFeedbackBindingBroken();
        if (task.candidateJson() != null) gateway.readRelationCandidate(task.candidateJson(), manifest);
    }

    private static IllegalStateException relationAiFeedbackBindingBroken() {
        return new IllegalStateException("关系候选任务与固定 AI 反馈来源不一致");
    }

    private void requireRelationReviewCreateReceipt(String owner, String key, ReviewDraftDtos.SourceSelection selected) {
        var receipt = workspaces.findReceipt(owner, key);
        if (receipt == null) {
            reviewDrafts.requireAccess(owner, selected);
            return;
        }
        if (!"POST /api/v1/ai/relation-review-drafts/tasks".equals(receipt.operation())) return;
        Task issued = json.readValue(receipt.responseJson(), Task.class);
        if (issued == null || issued.task() == null || issued.manifest() == null
                || issued.task().purpose() != Purpose.RELATION_DRAFT || !AiSources.validRelationReviewInput(issued.manifest())) {
            throw reviewBindingBroken();
        }
        try { uuid(issued.task().id()); }
        catch (ApiException exception) { throw reviewBindingBroken(); }
        TaskRow current = mapper.task(owner, issued.task().id());
        if (current == null) throw reviewBindingBroken();
        Manifest fixed = manifest(current);
        // 先核固定任务和原答查看权限，再恢复回执；后续修订、决定或归档只影响新发送与采用。
        requireReviewDraftAccess(owner, current, fixed);
        if (!issued.manifest().equals(fixed) || issued.task().revision() < 0
                || issued.task().revision() > current.revision()) throw reviewBindingBroken();
    }

    private void requireRelationEvidenceCreateReceipt(String owner, String key) {
        var receipt = workspaces.findReceipt(owner, key);
        if (receipt == null || !"POST /api/v1/ai/relation-evidence-drafts/tasks".equals(receipt.operation())) return;
        Task issued = json.readValue(receipt.responseJson(), Task.class);
        if (issued == null || issued.task() == null || issued.manifest() == null
                || issued.task().purpose() != Purpose.RELATION_DRAFT || issued.manifest().relationEvidence() == null) {
            throw relationEvidenceBindingBroken();
        }
        try { uuid(issued.task().id()); }
        catch (ApiException exception) { throw relationEvidenceBindingBroken(); }
        TaskRow current = mapper.task(owner, issued.task().id());
        if (current == null) throw relationEvidenceBindingBroken();
        Manifest fixed = manifest(current);
        requireReviewDraftAccess(owner, current, fixed);
        // 原键恢复已签发快照；来源后来失效不抹掉原回执，但不能绕过任务与原文绑定核验。
        if (!issued.manifest().equals(fixed) || issued.task().revision() < 0
                || issued.task().revision() > current.revision()) throw relationEvidenceBindingBroken();
    }

    private void requireRelationEvidenceBinding(String owner, TaskRow task, Manifest manifest) {
        if (manifest.relationEvidence() == null) {
            relationEvidence.requireBinding(owner, task.id(), null);
            return;
        }
        if (!owner.equals(task.ownerId()) || !Purpose.RELATION_DRAFT.name().equals(task.purpose())
                || !AiSources.validRelationInput(manifest) || !Objects.equals(task.manifestHash(), sources.hash(manifest))) {
            throw relationEvidenceBindingBroken();
        }
        relationEvidence.requireBinding(owner, task.id(), manifest.relationEvidence());
        // 固定内容损坏与来源过期分开处理，不能把坏绑定降为可重试的普通失效。
        if (Status.SUCCEEDED.name().equals(task.status()) != (task.candidateJson() != null)) throw relationEvidenceBindingBroken();
        if (task.candidateJson() != null) gateway.readRelationCandidate(task.candidateJson(), manifest);
    }

    private boolean validRelationResult(Manifest manifest, ModelGateway.Result result) {
        try {
            return result != null && result.relationCandidate() != null && result.relationCandidate().equals(
                    gateway.readRelationCandidate(json.writeValueAsString(result.relationCandidate()), manifest));
        } catch (RuntimeException exception) {
            // 只校验模型返回的内存结果；非法候选仍须结算本次已发生的费用。
            return false;
        }
    }

    private static IllegalStateException relationEvidenceBindingBroken() {
        return new IllegalStateException("关系候选任务与固定原文证据不一致");
    }

    private void requireRewriteBinding(String owner, TaskRow task, Manifest manifest) {
        if (Purpose.ANSWER_REWRITE.name().equals(task.purpose()) || manifest.purpose() == Purpose.ANSWER_REWRITE) {
            if (!owner.equals(task.ownerId()) || !Purpose.ANSWER_REWRITE.name().equals(task.purpose())
                    || !AiSources.validRewriteInput(manifest) || !task.manifestHash().equals(sources.hash(manifest))) throw rewriteBindingBroken();
        }
        rewrites.assertBinding(owner, task.id(), manifest);
    }

    private void requireRewriteCreateReceipt(String owner, String key) {
        var receipt = workspaces.findReceipt(owner, key);
        if (receipt == null || !"POST /api/v1/ai/answer-rewrites/tasks".equals(receipt.operation())) return;
        Task issued = json.readValue(receipt.responseJson(), Task.class);
        if (issued == null || issued.task() == null || issued.manifest() == null
                || issued.task().purpose() != Purpose.ANSWER_REWRITE) throw rewriteBindingBroken();
        try { uuid(issued.task().id()); }
        catch (ApiException exception) { throw rewriteBindingBroken(); }
        TaskRow current = mapper.task(owner, issued.task().id());
        if (current == null) throw rewriteBindingBroken();
        Manifest fixed = manifest(current);
        requireReviewDraftAccess(owner, current, fixed);
        // 回执保留发出时快照；当前绑定及访问仍须核对，不能借历史回执跳过资料边界。
        if (!issued.manifest().equals(fixed) || issued.task().revision() > current.revision()) throw rewriteBindingBroken();
    }

    private boolean validRewriteResult(Manifest manifest, ModelGateway.Result result) {
        try {
            return result != null && result.rewriteCandidate() != null
                    && result.rewriteCandidate().equals(gateway.readRewriteCandidate(json.writeValueAsString(result.rewriteCandidate()), manifest));
        } catch (RuntimeException exception) {
            // 此处仅校验纯内存结果；非法候选记失败，同时保留已经发生的模型费用。
            return false;
        }
    }

    private static IllegalStateException rewriteBindingBroken() {
        return new IllegalStateException("改写任务与确切提交或固定清单不一致");
    }

    private static IllegalStateException reviewBindingBroken() {
        return new IllegalStateException("复盘整理任务与确切来源绑定不一致");
    }

    private static CardDraftRows.SourceRow cardSource(String owner, String taskId, Source source, int ordinal) {
        return new CardDraftRows.SourceRow(owner, taskId, source.itemId(), source.versionId(), source.number(),
                source.title(), source.kind(), ordinal);
    }

    private void requireCardDraftBinding(String owner, TaskRow task, Manifest manifest) {
        List<CardDraftRows.SourceRow> recorded = cardDrafts.sources(owner, task.id());
        CardDraftRows.DecisionRow decision = cardDrafts.decision(owner, task.id());
        if (!Purpose.MEMORY_CARD_DRAFT.name().equals(task.purpose()) && manifest.purpose() != Purpose.MEMORY_CARD_DRAFT
                && manifest.cardDraftType() == null) {
            if (!recorded.isEmpty() || decision != null) throw cardBindingBroken();
            return;
        }
        if (!owner.equals(task.ownerId()) || !Purpose.MEMORY_CARD_DRAFT.name().equals(task.purpose())
                || !AiSources.validCardDraftInput(manifest) || !task.manifestHash().equals(sources.hash(manifest))
                || recorded.size() != manifest.sources().size()) throw cardBindingBroken();
        for (int index = 0; index < recorded.size(); index++) {
            if (!cardSource(owner, task.id(), manifest.sources().get(index), index + 1).equals(recorded.get(index))) throw cardBindingBroken();
        }
        if (decision != null) cardDecision(owner, task, decision);
    }

    private CardDraftDtos.Decision cardDecision(String owner, TaskRow task, CardDraftRows.DecisionRow row) {
        if (!owner.equals(row.ownerId()) || !task.id().equals(row.taskId()) || !Status.SUCCEEDED.name().equals(task.status())
                || task.candidateJson() == null
                || row.createdAt() == null || row.createdAt().isBefore(task.createdAt()) || row.createdAt().isAfter(task.updatedAt())) throw cardBindingBroken();
        if (DecisionStatus.ADOPTED.name().equals(row.status())) {
            try { uuid(row.targetCardId()); uuid(row.targetVersionId()); }
            catch (ApiException exception) { throw cardBindingBroken(); }
        } else if (!DecisionStatus.IGNORED.name().equals(row.status()) || row.targetCardId() != null || row.targetVersionId() != null) throw cardBindingBroken();
        return new CardDraftDtos.Decision(DecisionStatus.valueOf(row.status()), row.targetCardId(), row.targetVersionId(), row.createdAt());
    }

    private static IllegalStateException cardBindingBroken() {
        return new IllegalStateException("记忆卡候选任务与固定来源或采用决定不一致");
    }

    private void requireJdRequirementsBinding(String owner, TaskRow task, Manifest manifest) {
        var decision = jdRequirements.decision(owner, task.id());
        if (!Purpose.JD_REQUIREMENT_DRAFT.name().equals(task.purpose()) && manifest.purpose() != Purpose.JD_REQUIREMENT_DRAFT) {
            if (decision != null) throw jdBindingBroken();
            return;
        }
        if (!owner.equals(task.ownerId()) || !Purpose.JD_REQUIREMENT_DRAFT.name().equals(task.purpose())
                || !AiSources.validJdRequirementsInput(manifest) || !task.manifestHash().equals(sources.hash(manifest))) throw jdBindingBroken();
        requireJdSource(owner, task.id(), manifest);
        if (decision != null) jdDecision(owner, task, manifest, decision);
    }

    private void requireJdSource(String owner, String taskId, Manifest manifest) {
        var recorded = jdRequirements.sources(owner, taskId);
        if (recorded.size() != 1) throw jdBindingBroken();
        Source source = manifest.sources().getFirst();
        var expected = new JdRequirementsRows.SourceRow(owner, taskId, source.itemId(), source.versionId(),
                source.number(), source.title(), "TARGET", 1);
        if (!expected.equals(recorded.getFirst())) throw jdBindingBroken();
    }

    private JdRequirementsDtos.Decision jdDecision(String owner, TaskRow task, Manifest manifest, JdRequirementsRows.DecisionRow row) {
        if (!owner.equals(row.ownerId()) || !task.id().equals(row.taskId()) || !Status.SUCCEEDED.name().equals(task.status())
                || task.candidateJson() == null || row.createdAt() == null || row.createdAt().isBefore(task.createdAt())
                || row.createdAt().isAfter(task.updatedAt())) throw jdBindingBroken();
        var links = requirementLinks(row.requirementsJson());
        if (DecisionStatus.ADOPTED.name().equals(row.status())) {
            if (links.isEmpty()) throw jdBindingBroken();
            var candidate = gateway.readJdRequirementsCandidate(task.candidateJson(), manifest);
            Set<Integer> available = new HashSet<>(); candidate.requirements().forEach(value -> available.add(value.number()));
            if (links.stream().anyMatch(value -> !available.contains(value.candidateNumber()))) throw jdBindingBroken();
            validateRequirementTarget(owner, manifest.sources().getFirst(), row.targetItemId(), row.targetVersionId(), links);
        } else if (!DecisionStatus.IGNORED.name().equals(row.status()) || row.targetItemId() != null
                || row.targetVersionId() != null || !links.isEmpty()) throw jdBindingBroken();
        return new JdRequirementsDtos.Decision(DecisionStatus.valueOf(row.status()), row.targetItemId(), row.targetVersionId(), links, row.createdAt());
    }

    private void validateRequirementTarget(String owner, Source source, String targetId, String versionId,
                                           List<JdRequirementsDtos.RequirementLink> links) {
        try { uuid(targetId); uuid(versionId); }
        catch (ApiException exception) { throw jdBindingBroken(); }
        var version = jdRequirements.targetVersion(owner, targetId, versionId);
        if (!source.itemId().equals(targetId) || source.versionId().equals(versionId) || version == null
                || !owner.equals(version.ownerId()) || !targetId.equals(version.itemId()) || !versionId.equals(version.id())
                || !"TARGET".equals(version.kind()) || (long) version.number() != (long) source.number() + 1) throw jdBindingBroken();
        List<String> before = jdRequirements.requirementIds(owner, source.itemId(), source.versionId());
        List<String> after = jdRequirements.requirementIds(owner, targetId, versionId);
        var expected = new ArrayList<>(before);
        links.forEach(link -> expected.add(link.requirementId()));
        // 来源旧要求必须完整保留在前，新要求身份按采用顺序追加；只核 ID，不读取目标正文。
        if (before.size() > 50 || expected.size() > 50 || new HashSet<>(expected).size() != expected.size()
                || !expected.equals(after)) throw jdBindingBroken();
    }

    private static List<JdRequirementsDtos.RequirementLink> requirementLinks(String raw) {
        JsonNode values = jdDecisionJson(raw);
        if (!values.isArray() || values.size() > 20) throw jdBindingBroken();
        var links = new ArrayList<JdRequirementsDtos.RequirementLink>();
        var numbers = new HashSet<Integer>(); var ids = new HashSet<String>();
        for (JsonNode value : values) {
            if (!value.isObject() || value.size() != 2 || !value.has("candidateNumber") || !value.has("requirementId")) throw jdBindingBroken();
            JsonNode number = value.get("candidateNumber"), id = value.get("requirementId");
            if (!number.isIntegralNumber() || !number.canConvertToInt() || number.intValue() < 1 || number.intValue() > 20
                    || !id.isTextual() || !numbers.add(number.intValue()) || !ids.add(id.stringValue())) throw jdBindingBroken();
            try { uuid(id.stringValue()); } catch (ApiException exception) { throw jdBindingBroken(); }
            links.add(new JdRequirementsDtos.RequirementLink(number.intValue(), id.stringValue()));
        }
        return List.copyOf(links);
    }

    private static Set<Integer> jdCandidateNumbers(String raw) {
        JsonNode values = jdDecisionJson(raw);
        if (!values.isArray() || values.isEmpty() || values.size() > 20) throw jdBindingBroken();
        var numbers = new HashSet<Integer>();
        int position = 0;
        for (JsonNode value : values) {
            if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() != ++position
                    || !numbers.add(value.intValue())) throw jdBindingBroken();
        }
        return Set.copyOf(numbers);
    }

    private static JsonNode jdDecisionJson(String raw) {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > 8192) throw jdBindingBroken();
        try {
            JsonNode value = JD_DECISIONS.readTree(raw);
            if (value == null) throw jdBindingBroken();
            return value;
        } catch (RuntimeException exception) { throw new IllegalStateException("岗位要求采用决定的固定 JSON 无效", exception); }
    }

    private static IllegalStateException jdBindingBroken() {
        return new IllegalStateException("岗位要求候选与固定来源或采用决定不一致");
    }

    private void requireFactCreateReceipt(String owner, String key) {
        var receipt = workspaces.findReceipt(owner, key);
        if (receipt == null || !"POST /api/v1/ai/fact-drafts/tasks".equals(receipt.operation())) return;
        Task issued = json.readValue(receipt.responseJson(), Task.class);
        if (issued == null || issued.task() == null || issued.manifest() == null
                || issued.task().purpose() != Purpose.FACT_DRAFT) throw factBindingBroken();
        try { uuid(issued.task().id()); }
        catch (ApiException exception) { throw factBindingBroken(); }
        TaskRow current = mapper.task(owner, issued.task().id());
        if (current == null) throw factBindingBroken();
        Manifest fixed = manifest(current);
        requireReviewDraftAccess(owner, current, fixed);
        // 原键返回已签发快照；先核对固定任务绑定，后续来源变化不会改写该回执。
        if (!issued.manifest().equals(fixed) || issued.task().revision() > current.revision()) throw factBindingBroken();
    }

    private static FactDraftRows.SourceRow factSource(String owner, String taskId, FactDraftDtos.Input input) {
        var selected = input.training();
        return new FactDraftRows.SourceRow(owner, taskId, input.kind().name(), selected == null ? null : selected.sessionId(),
                selected == null ? null : selected.questionId(), selected == null ? null : selected.answerId());
    }

    private static PreparationDtos.Source factPreparationSource(FactDraftDtos.Input input) {
        return new PreparationDtos.Source(FACT_SOURCE_TITLE, "", FactDraftSources.VALIDATION_NOTE,
                input.materials().stream().map(value -> value.excerptId()).toList());
    }

    private void requireFactSource(String owner, String taskId, FactDraftDtos.Input input) {
        if (!FactDraftSources.valid(input) || !factSource(owner, taskId, input).equals(factDrafts.source(owner, taskId))) throw factBindingBroken();
        var recorded = factDrafts.materials(owner, taskId);
        if (recorded.size() != input.materials().size()) throw factBindingBroken();
        for (int index = 0; index < recorded.size(); index++) {
            if (!new FactDraftRows.MaterialRow(owner, taskId, input.materials().get(index).excerptId(), index + 1)
                    .equals(recorded.get(index))) throw factBindingBroken();
        }
    }

    private void requireFactDraftBinding(String owner, TaskRow task, Manifest manifest) {
        var decision = factDrafts.decision(owner, task.id());
        if (!Purpose.FACT_DRAFT.name().equals(task.purpose()) && manifest.purpose() != Purpose.FACT_DRAFT && manifest.factDraft() == null) {
            if (decision != null || factDrafts.source(owner, task.id()) != null || !factDrafts.materials(owner, task.id()).isEmpty()) throw factBindingBroken();
            return;
        }
        // 固定身份或候选损坏必须作为完整性错误；当前可用性由 sources.current 单独判断。
        if (!owner.equals(task.ownerId()) || !Purpose.FACT_DRAFT.name().equals(task.purpose())
                || !AiSources.validFactDraftInput(manifest) || !Objects.equals(task.manifestHash(), sources.hash(manifest))) throw factBindingBroken();
        requireFactSource(owner, task.id(), manifest.factDraft());
        if (Status.SUCCEEDED.name().equals(task.status()) != (task.candidateJson() != null)) throw factBindingBroken();
        if (task.candidateJson() != null) gateway.readFactCandidate(task.candidateJson(), manifest);
        if (decision != null) factDecision(owner, task, manifest, decision);
    }

    private FactDraftDtos.Decision factDecision(String owner, TaskRow task, Manifest manifest, FactDraftRows.DecisionRow row) {
        if (!owner.equals(row.ownerId()) || !task.id().equals(row.taskId()) || !Status.SUCCEEDED.name().equals(task.status())
                || task.candidateJson() == null || row.createdAt() == null || row.createdAt().isBefore(task.createdAt())
                || row.createdAt().isAfter(task.updatedAt())) throw factBindingBroken();
        if (DecisionStatus.ADOPTED.name().equals(row.status())) {
            validateFactTarget(owner, row.targetProjectId(), row.targetItemId(), row.targetVersionId(), manifest.factDraft());
        } else if (!DecisionStatus.IGNORED.name().equals(row.status()) || row.targetProjectId() != null
                || row.targetItemId() != null || row.targetVersionId() != null) throw factBindingBroken();
        return new FactDraftDtos.Decision(DecisionStatus.valueOf(row.status()), row.targetProjectId(), row.targetItemId(), row.targetVersionId(), row.createdAt());
    }

    private void validateFactTarget(String owner, String projectId, String itemId, String versionId, FactDraftDtos.Input input) {
        try { uuid(projectId); uuid(itemId); uuid(versionId); }
        catch (ApiException exception) { throw factBindingBroken(); }
        var target = factDrafts.targetVersion(owner, itemId, versionId);
        if (target == null || !owner.equals(target.ownerId()) || !itemId.equals(target.itemId()) || !versionId.equals(target.id())
                || !"FACT".equals(target.kind()) || !projectId.equals(target.projectId()) || !"PROJECT".equals(target.projectKind())
                || target.number() != 1) throw factBindingBroken();
        var original = factDrafts.targetSource(owner, itemId, versionId);
        if (original == null || !PreparationDtos.Responsibility.UNVERIFIED.name().equals(original.responsibility())
                || original.sourceJson() == null || original.sourceJson().getBytes(StandardCharsets.UTF_8).length > 8192) throw factBindingBroken();
        JsonNode source;
        try { source = JD_DECISIONS.readTree(original.sourceJson()); }
        catch (RuntimeException exception) { throw new IllegalStateException("事实首版的固定来源无效", exception); }
        var expected = factPreparationSource(input);
        if (!json.valueToTree(expected).equals(source)) throw factBindingBroken();
        // 只核采用首版的固定来源和材料关系；后续改稿、归档及人工核实不抹掉历史。
        List<String> recorded = factDrafts.targetMaterials(owner, itemId, versionId);
        if (!expected.excerptIds().stream().sorted().toList().equals(recorded)) throw factBindingBroken();
    }

    private static IllegalStateException factBindingBroken() {
        return new IllegalStateException("事实候选与固定来源或采用决定不一致");
    }

    private void recoverWithinTransaction(String owner) {
        Instant now = now();
        for (TaskRow task : mapper.pending(owner)) {
            AttemptRow attempt = requireAttempt(owner, task.id(), task.currentAttemptId());
            requireReviewDraftBinding(owner, task, manifest(task));
            String authorizationError = authorizations.refusal(owner, attempt, manifest(task));
            if (Status.QUEUED.name().equals(task.status())) {
                if (!properties.runtimeEpoch().equals(task.authorizationEpoch())) failUnsent(task, attempt, "AI_AUTHORIZATION_EXPIRED");
                else if (!configurationMatches(task)) failUnsent(task, attempt, "AI_CONFIG_CHANGED");
                else if (authorizationError != null) {
                    failUnsent(task, attempt, authorizationError);
                }
                else if (Purpose.FOLLOW_UP.name().equals(task.purpose())) {
                    Manifest manifest = manifest(task);
                    if (!training.lifecycleCurrent(owner, manifest.training())) {
                        failUnsent(task, attempt, "AI_FOLLOW_UP_STATE_CHANGED");
                    } else if (!sources.current(owner, manifest, task.createdAt())) {
                        failUnsent(task, attempt, "AI_SOURCE_CHANGED");
                    }
                } else if (Purpose.ANSWER_COMPARISON.name().equals(task.purpose())) {
                    Manifest manifest = manifest(task);
                    if (!comparisonBindingMatches(owner, task, manifest) || !sources.current(owner, manifest, task.createdAt())) {
                        failUnsent(task, attempt, "AI_SOURCE_CHANGED");
                    }
                } else if ((Purpose.MATERIAL_QA.name().equals(task.purpose())
                        || Purpose.SOURCE_QA.name().equals(task.purpose())
                        || Purpose.KNOWLEDGE_DRAFT.name().equals(task.purpose()) || Purpose.QUESTION_DRAFT.name().equals(task.purpose())
                        || Purpose.EXPRESSION_DRAFT.name().equals(task.purpose()) || Purpose.PREPARATION_PLAN.name().equals(task.purpose())
                        || Purpose.RELATION_DRAFT.name().equals(task.purpose()) || Purpose.MEMORY_CARD_DRAFT.name().equals(task.purpose())
                        || Purpose.JD_REQUIREMENT_DRAFT.name().equals(task.purpose()) || Purpose.FACT_DRAFT.name().equals(task.purpose())
                        || Purpose.ANSWER_REWRITE.name().equals(task.purpose()))
                        && !sources.current(owner, manifest(task), task.createdAt())) {
                    failUnsent(task, attempt, "AI_SOURCE_CHANGED");
                }
                continue;
            }
            if (!SENT.contains(task.status())) continue;
            if (properties.runtimeEpoch().equals(attempt.runtimeEpoch()) && attempt.leaseUntil() != null && attempt.leaseUntil().isAfter(now)) {
                if (authorizationError != null && !Status.CANCEL_REQUESTED.name().equals(task.status())) {
                    requestAuthorizationStop(task, attempt, authorizationError);
                }
                continue;
            }
            boolean canceled = Status.CANCEL_REQUESTED.name().equals(task.status());
            String status = canceled ? Status.CANCELED.name() : Status.UNKNOWN.name();
            String error = properties.runtimeEpoch().equals(attempt.runtimeEpoch()) ? "AI_LEASE_EXPIRED" : "AI_PROCESS_INTERRUPTED";
            replaceAttempt(attempt, status, FeeStatus.UNKNOWN.name(), null, attempt.promptTokens(), attempt.completionTokens(),
                    attempt.providerRequestId(), error, attempt.reconciliationNote(), attempt.budgetDate(), attempt.claimToken(),
                    attempt.runtimeEpoch(), null, attempt.sentAt(), now);
            replaceTask(task, status, null, error, task.currentAttemptId(), task.attemptCount(), task.authorizationEpoch(), now);
        }
    }

    private void failUnsent(TaskRow task, AttemptRow attempt, String error) { failKnownUnsent(task, attempt, error, false); }

    private void requestAuthorizationStop(TaskRow task, AttemptRow attempt, String error) {
        replaceAttempt(attempt, Status.CANCEL_REQUESTED.name(), attempt.feeStatus(), attempt.accountedMicros(),
                attempt.promptTokens(), attempt.completionTokens(), attempt.providerRequestId(), error,
                attempt.reconciliationNote(), attempt.budgetDate(), attempt.claimToken(), attempt.runtimeEpoch(),
                attempt.leaseUntil(), attempt.sentAt(), attempt.finishedAt());
        replaceTask(task, Status.CANCEL_REQUESTED.name(), null, error, task.currentAttemptId(),
                task.attemptCount(), task.authorizationEpoch(), now());
    }

    private void failKnownUnsent(TaskRow task, AttemptRow attempt, String error, boolean canceled) {
        Instant now = now();
        String status = canceled ? Status.CANCELED.name() : Status.FAILED.name();
        replaceAttempt(attempt, status, FeeStatus.RELEASED.name(), null, null, null, null, error, null,
                attempt.budgetDate(), attempt.claimToken(), attempt.runtimeEpoch(), null, attempt.sentAt(), now);
        replaceTask(task, status, null, error, task.currentAttemptId(), task.attemptCount(), task.authorizationEpoch(), now);
    }

    private boolean configurationMatches(TaskRow task) {
        Configuration current = properties.configuration();
        return current.enabled() && current.configured() && properties.fingerprint().equals(task.configurationFingerprint());
    }

    private boolean validClaim(Claim claim, AttemptRow attempt, Instant now) {
        return claim.token().equals(attempt.claimToken()) && claim.epoch().equals(attempt.runtimeEpoch())
                && claim.epoch().equals(properties.runtimeEpoch()) && SENT.contains(attempt.status())
                && attempt.leaseUntil() != null && attempt.leaseUntil().isAfter(now);
    }

    private Accounting accounting(Manifest manifest, AttemptRow attempt, ModelGateway.Result result) {
        Integer prompt = result == null ? attempt.promptTokens() : validTokens(result.promptTokens());
        Integer completion = result == null ? attempt.completionTokens() : validTokens(result.completionTokens());
        if (prompt == null) prompt = attempt.promptTokens();
        if (completion == null) completion = attempt.completionTokens();
        if (FeeStatus.RECONCILED.name().equals(attempt.feeStatus()) || FeeStatus.REPORTED.name().equals(attempt.feeStatus())) {
            return new Accounting(attempt.feeStatus(), attempt.accountedMicros(), prompt, completion);
        }
        // 只有网关明确在构造／校验请求阶段拒绝且未携带远端信息，才可证明本次没有发送。
        if (knownUnsent(result)) return new Accounting(FeeStatus.RELEASED.name(), null, null, null);
        if (result == null || prompt == null || completion == null) return new Accounting(FeeStatus.UNKNOWN.name(), null, prompt, completion);
        Configuration config = manifest.configuration();
        BigInteger amount = tokenPrice(prompt, config.inputPriceMicrosPerMillion())
                .add(tokenPrice(completion, config.outputPriceMicrosPerMillion()));
        if (amount.signum() < 0 || amount.compareTo(BigInteger.valueOf(MAX_SAFE)) > 0) return new Accounting(FeeStatus.UNKNOWN.name(), null, prompt, completion);
        return new Accounting(FeeStatus.REPORTED.name(), amount.longValueExact(), prompt, completion);
    }

    private static BigInteger tokenPrice(int tokens, long microsPerMillion) {
        return BigInteger.valueOf(tokens).multiply(BigInteger.valueOf(microsPerMillion))
                .add(BigInteger.valueOf(999_999)).divide(BigInteger.valueOf(1_000_000));
    }

    private static boolean knownUnsent(ModelGateway.Result result) {
        return result != null && result.candidate() == null && result.questionCandidate() == null && result.followUpCandidate() == null
                && result.evaluationReport() == null && result.materialAnswer() == null && result.expressionCandidate() == null
                && result.preparationPlanCandidate() == null && result.relationCandidate() == null && result.comparisonCandidate() == null
                && result.cardCandidate() == null
                && result.jdRequirementsCandidate() == null
                && result.factCandidate() == null
                && result.rewriteCandidate() == null
                && result.promptTokens() == null && result.completionTokens() == null
                && result.providerRequestId() == null && BEFORE_SEND_FAILURES.contains(result.errorCode() == null ? "" : result.errorCode());
    }

    private static boolean matchesCandidate(Purpose purpose, ModelGateway.Result result) {
        if (result == null) return false;
        if (purpose != Purpose.SOURCE_QA && result.sourceQaAnswer() != null) return false;
        if (purpose != Purpose.ANSWER_COMPARISON && result.comparisonCandidate() != null) return false;
        if (purpose != Purpose.MEMORY_CARD_DRAFT && result.cardCandidate() != null) return false;
        if (purpose != Purpose.JD_REQUIREMENT_DRAFT && result.jdRequirementsCandidate() != null) return false;
        if (purpose != Purpose.FACT_DRAFT && result.factCandidate() != null) return false;
        if (purpose != Purpose.ANSWER_REWRITE && result.rewriteCandidate() != null) return false;
        return switch (purpose) {
            case SOURCE_QA -> result.sourceQaAnswer() != null && result.candidate() == null && result.questionCandidate() == null
                    && result.followUpCandidate() == null && result.evaluationReport() == null && result.materialAnswer() == null
                    && result.expressionCandidate() == null && result.preparationPlanCandidate() == null && result.relationCandidate() == null;
            case KNOWLEDGE_DRAFT -> result.candidate() != null && result.questionCandidate() == null && result.followUpCandidate() == null
                    && result.evaluationReport() == null && result.materialAnswer() == null && result.expressionCandidate() == null
                    && result.preparationPlanCandidate() == null && result.relationCandidate() == null;
            case QUESTION_DRAFT -> result.questionCandidate() != null && result.candidate() == null && result.followUpCandidate() == null
                    && result.evaluationReport() == null && result.materialAnswer() == null && result.expressionCandidate() == null
                    && result.preparationPlanCandidate() == null && result.relationCandidate() == null;
            case FOLLOW_UP -> result.followUpCandidate() != null && result.candidate() == null && result.questionCandidate() == null
                    && result.evaluationReport() == null && result.materialAnswer() == null && result.expressionCandidate() == null
                    && result.preparationPlanCandidate() == null && result.relationCandidate() == null;
            case ANSWER_EVALUATION -> result.evaluationReport() != null && result.candidate() == null && result.questionCandidate() == null
                    && result.followUpCandidate() == null && result.materialAnswer() == null && result.expressionCandidate() == null
                    && result.preparationPlanCandidate() == null && result.relationCandidate() == null;
            case ANSWER_COMPARISON -> result.comparisonCandidate() != null && result.candidate() == null && result.questionCandidate() == null
                    && result.followUpCandidate() == null && result.evaluationReport() == null && result.materialAnswer() == null
                    && result.expressionCandidate() == null && result.preparationPlanCandidate() == null && result.relationCandidate() == null;
            case MATERIAL_QA -> result.materialAnswer() != null && result.candidate() == null && result.questionCandidate() == null
                    && result.followUpCandidate() == null && result.evaluationReport() == null && result.expressionCandidate() == null
                    && result.preparationPlanCandidate() == null && result.relationCandidate() == null;
            case EXPRESSION_DRAFT -> result.expressionCandidate() != null && result.candidate() == null && result.questionCandidate() == null
                    && result.followUpCandidate() == null && result.evaluationReport() == null && result.materialAnswer() == null
                    && result.preparationPlanCandidate() == null && result.relationCandidate() == null;
            case PREPARATION_PLAN -> result.preparationPlanCandidate() != null && result.candidate() == null && result.questionCandidate() == null
                    && result.followUpCandidate() == null && result.evaluationReport() == null && result.materialAnswer() == null
                    && result.expressionCandidate() == null && result.relationCandidate() == null;
            case RELATION_DRAFT -> result.relationCandidate() != null && result.candidate() == null && result.questionCandidate() == null
                    && result.followUpCandidate() == null && result.evaluationReport() == null && result.materialAnswer() == null
                    && result.expressionCandidate() == null && result.preparationPlanCandidate() == null;
            case MEMORY_CARD_DRAFT -> result.cardCandidate() != null && result.candidate() == null && result.questionCandidate() == null
                    && result.followUpCandidate() == null && result.evaluationReport() == null && result.materialAnswer() == null
                    && result.expressionCandidate() == null && result.preparationPlanCandidate() == null && result.relationCandidate() == null;
            case JD_REQUIREMENT_DRAFT -> result.jdRequirementsCandidate() != null && result.candidate() == null && result.questionCandidate() == null
                    && result.followUpCandidate() == null && result.evaluationReport() == null && result.materialAnswer() == null
                    && result.expressionCandidate() == null && result.preparationPlanCandidate() == null && result.relationCandidate() == null;
            case FACT_DRAFT -> result.factCandidate() != null && result.candidate() == null && result.questionCandidate() == null
                    && result.followUpCandidate() == null && result.evaluationReport() == null && result.materialAnswer() == null
                    && result.expressionCandidate() == null && result.preparationPlanCandidate() == null && result.relationCandidate() == null;
            case ANSWER_REWRITE -> result.rewriteCandidate() != null && result.candidate() == null && result.questionCandidate() == null
                    && result.followUpCandidate() == null && result.evaluationReport() == null && result.materialAnswer() == null
                    && result.expressionCandidate() == null && result.preparationPlanCandidate() == null && result.relationCandidate() == null;
        };
    }

    private static boolean sameAccounting(AttemptRow attempt, Accounting fee, String providerId) {
        return attempt.feeStatus().equals(fee.status()) && Objects.equals(attempt.accountedMicros(), fee.amount())
                && Objects.equals(attempt.promptTokens(), fee.promptTokens()) && Objects.equals(attempt.completionTokens(), fee.completionTokens())
                && Objects.equals(attempt.providerRequestId(), providerId);
    }

    private AttemptRow newAttempt(TaskRow task, String attemptId, int number, Instant now, AiAuthorizationDtos.Use requestedAuthorization) {
        Manifest manifest = manifest(task);
        AiAuthorizationDtos.Use authorization = authorizations.requireUse(task.ownerId(), manifest, requestedAuthorization);
        return new AttemptRow(attemptId, task.ownerId(), task.id(), number, Status.QUEUED.name(), FeeStatus.RESERVED.name(),
                task.currency(), manifest.reservedMicros(), null, null, null, null, null, null, utcDate(now),
                null, properties.runtimeEpoch(), null, now, null, null,
                authorization == null ? null : authorization.id(), authorization == null ? null : authorization.expectedRevision());
    }

    private void insertTask(TaskRow task) {
        mapper.insertTask(task);
        insertDraftKnowledgeInputs(task);
        mapper.advanceEventCursor(task.ownerId(), newId());
    }

    private void insertDraftKnowledgeInputs(TaskRow task) {
        if (!Purpose.KNOWLEDGE_DRAFT.name().equals(task.purpose())
                && !Purpose.QUESTION_DRAFT.name().equals(task.purpose())) return;
        Manifest manifest = manifest(task);
        if (manifest == null || manifest.purpose() == null || !task.purpose().equals(manifest.purpose().name())
                || manifest.sources() == null || manifest.sources().size() > 5) {
            throw new IllegalStateException("草稿知识输入与固定外发清单不一致");
        }
        var seen = new HashSet<String>();
        // 混选准备来源也必须校验身份，不能把未知类型或重复对象静默当成非知识略过。
        for (Source source : manifest.sources()) {
            if (source == null || (!AiSources.knowledgeKind(source.kind())
                    && !"TARGET".equals(source.kind()) && !"PROJECT".equals(source.kind())
                    && !"FACT".equals(source.kind()) && !"EXPRESSION".equals(source.kind()))) {
                throw new IllegalStateException("草稿来源类型无效");
            }
            uuid(source.itemId());
            uuid(source.versionId());
            if (!seen.add(source.itemId())) throw new IllegalStateException("草稿来源对象重复");
        }
        // 登记保留混选清单中的原序号；失败须回滚任务、预算和回执，不等待候选采用才留痕。
        for (int index = 0; index < manifest.sources().size(); index++) {
            Source source = manifest.sources().get(index);
            if (AiSources.knowledgeKind(source.kind())
                    && mapper.insertDraftKnowledgeInput(task.ownerId(), task.id(), source, index + 1) != 1) {
                throw new IllegalStateException("草稿知识输入登记失败");
            }
        }
    }

    private TaskRow replaceTask(TaskRow before, String status, String candidate, String error, String currentAttemptId,
                                 int attemptCount, String epoch, Instant now) {
        if (before.revision() >= MAX_SAFE) throw limit("AI 任务修订号已达到上限。");
        TaskRow next = new TaskRow(before.id(), before.ownerId(), before.purpose(), status, before.revision() + 1,
                before.title(), before.provider(), before.model(), before.currency(), before.manifestJson(), before.manifestHash(),
                before.configurationFingerprint(), epoch, currentAttemptId, attemptCount, candidate, error, before.createdAt(), now);
        changed(mapper.updateTask(next, before.revision()));
        // 游标跟随业务事务提交；回滚和幂等回放不会让浏览器观察到未提交的状态。
        mapper.advanceEventCursor(before.ownerId(), newId());
        return next;
    }

    private void replaceAttempt(AttemptRow before, String status, String feeStatus, Long amount, Integer prompt, Integer completion,
                                  String providerRequestId, String error, String note, LocalDate date, String token,
                                  String epoch, Instant lease, Instant sentAt, Instant finishedAt) {
        AttemptRow next = new AttemptRow(before.id(), before.ownerId(), before.taskId(), before.attemptNumber(), status,
                feeStatus, before.currency(), before.reservedMicros(), amount, prompt, completion, providerRequestId,
                error, note, date, token, epoch, lease, before.createdAt(), sentAt, finishedAt,
                before.scopeAuthorizationId(), before.scopeAuthorizationRevision());
        changed(mapper.updateAttempt(next, before.status(), before.claimToken()));
    }

    private Task view(String owner, TaskRow row) {
        Manifest manifest = manifest(row);
        requireReviewDraftAccess(owner, row, manifest);
        if (manifest.purpose() == null || !row.purpose().equals(manifest.purpose().name())
                || (manifest.purpose() == Purpose.ANSWER_COMPARISON && !AiSources.validComparisonInput(manifest))
                || (manifest.purpose() != Purpose.ANSWER_COMPARISON
                    && (manifest.comparisonInput() != null || manifest.comparisonChecks() != null))) {
            throw new IllegalStateException("AI 任务用途与比较清单不一致");
        }
        List<Attempt> attempts = mapper.attempts(owner, row.id()).stream().map(attempt -> new Attempt(attempt.id(), attempt.attemptNumber(),
                Status.valueOf(attempt.status()), FeeStatus.valueOf(attempt.feeStatus()), attempt.reservedMicros(), attempt.accountedMicros(),
                attempt.promptTokens(), attempt.completionTokens(), attempt.providerRequestId(), attempt.errorCode(), attempt.reconciliationNote(),
                attempt.createdAt(), attempt.sentAt(), attempt.finishedAt())).toList();
        Candidate candidate = null;
        CandidateDecision decision = null;
        QuestionCandidate questionCandidate = null;
        QuestionDecision questionDecision = null;
        FollowUpLink followUp = null;
        EvaluationLink evaluation = null;
        EvaluationReport evaluationReport = null;
        List<FeedbackDecision> evaluationDecisions = List.of();
        MaterialAnswer materialAnswer = null;
        ExpressionCandidate expressionCandidate = null;
        ExpressionDecision expressionDecision = null;
        PreparationPlanCandidate preparationPlanCandidate = null;
        PreparationPlanDecision preparationPlanDecision = null;
        RelationCandidate relationCandidate = null;
        List<RelationDecision> relationDecisions = null;
        ComparisonDtos.Link comparison = null;
        ComparisonDtos.Candidate comparisonCandidate = null;
        CardDraftDtos.Candidate cardCandidate = null;
        CardDraftDtos.Decision cardDecision = null;
        JdRequirementsDtos.Candidate jdRequirementsCandidate = null;
        JdRequirementsDtos.Decision jdRequirementsDecision = null;
        FactDraftDtos.Candidate factCandidate = null;
        FactDraftDtos.Decision factDecision = null;
        EvaluationLink rewrite = null;
        RewriteDtos.Candidate rewriteCandidate = null;
        SourceQaDtos.Answer sourceQaAnswer = null;
        String sourceQaManifestHash = null;
        if (Purpose.SOURCE_QA.name().equals(row.purpose())) {
            evaluationDecisions = null;
            sourceQaManifestHash = row.manifestHash();
            if (row.candidateJson() != null) sourceQaAnswer = gateway.readSourceQaAnswer(row.candidateJson(), manifest);
        } else if (Purpose.ANSWER_REWRITE.name().equals(row.purpose())) {
            evaluationDecisions = null;
            rewrite = rewrites.binding(owner, row.id(), manifest.evaluation());
            if (Status.SUCCEEDED.name().equals(row.status()) != (row.candidateJson() != null)) throw rewriteBindingBroken();
            if (row.candidateJson() != null) rewriteCandidate = gateway.readRewriteCandidate(row.candidateJson(), manifest);
        } else if (Purpose.FACT_DRAFT.name().equals(row.purpose())) {
            evaluationDecisions = null;
            if (row.candidateJson() != null) factCandidate = gateway.readFactCandidate(row.candidateJson(), manifest);
            var saved = factDrafts.decision(owner, row.id());
            if (saved != null) factDecision = factDecision(owner, row, manifest, saved);
        } else if (Purpose.JD_REQUIREMENT_DRAFT.name().equals(row.purpose())) {
            evaluationDecisions = null;
            if (row.candidateJson() != null) {
                if (!Status.SUCCEEDED.name().equals(row.status())) throw jdBindingBroken();
                jdRequirementsCandidate = gateway.readJdRequirementsCandidate(row.candidateJson(), manifest);
            }
            var saved = jdRequirements.decision(owner, row.id());
            if (saved != null) jdRequirementsDecision = jdDecision(owner, row, manifest, saved);
        } else if (Purpose.MEMORY_CARD_DRAFT.name().equals(row.purpose())) {
            evaluationDecisions = null;
            if (row.candidateJson() != null) {
                if (!Status.SUCCEEDED.name().equals(row.status())) throw cardBindingBroken();
                cardCandidate = gateway.readCardDraftCandidate(row.candidateJson(), manifest);
            }
            var saved = cardDrafts.decision(owner, row.id());
            if (saved != null) cardDecision = cardDecision(owner, row, saved);
        } else if (Purpose.ANSWER_COMPARISON.name().equals(row.purpose())) {
            evaluationDecisions = null;
            comparison = comparisonSources.binding(owner, row.id(), manifest.comparisonInput());
            if (row.candidateJson() != null) comparisonCandidate = comparisonCandidate(row, manifest);
        } else if (Purpose.RELATION_DRAFT.name().equals(row.purpose())) {
            evaluationDecisions = null;
            if (row.candidateJson() != null) relationCandidate = relationCandidate(row, manifest);
            var decisions = new ArrayList<RelationDecision>();
            var candidateIds = new HashSet<String>();
            for (RelationDecisionRow saved : mapper.relationDecisions(owner, row.id())) {
                if (!candidateIds.add(saved.candidateId())) throw new IllegalStateException("同一关系候选存在重复决定");
                decisions.add(relationDecision(owner, row, relationCandidate, saved));
            }
            relationDecisions = List.copyOf(decisions);
        } else if (Purpose.PREPARATION_PLAN.name().equals(row.purpose())) {
            evaluationDecisions = null;
            if (row.candidateJson() != null) preparationPlanCandidate = json.readValue(row.candidateJson(), PreparationPlanCandidate.class);
            PreparationPlanDecisionRow saved = mapper.preparationPlanDecision(owner, row.id());
            if (saved != null) {
                PreparationPlanTaskLink[] links = json.readValue(saved.tasksJson(), PreparationPlanTaskLink[].class);
                preparationPlanDecision = new PreparationPlanDecision(DecisionStatus.valueOf(saved.status()),
                        saved.contentJson() == null ? null : json.readValue(saved.contentJson(), PreparationPlanContent.class),
                        List.of(links), saved.createdAt());
            }
        } else if (Purpose.EXPRESSION_DRAFT.name().equals(row.purpose())) {
            evaluationDecisions = null;
            if (row.candidateJson() != null) expressionCandidate = json.readValue(row.candidateJson(), ExpressionCandidate.class);
            ExpressionDecisionRow saved = mapper.expressionDecision(owner, row.id());
            if (saved != null) expressionDecision = new ExpressionDecision(DecisionStatus.valueOf(saved.status()),
                    saved.mode() == null ? null : AdoptionMode.valueOf(saved.mode()), saved.targetItemId(),
                    saved.targetVersionId(), saved.createdAt());
        } else if (Purpose.MATERIAL_QA.name().equals(row.purpose())) {
            if (row.candidateJson() != null) materialAnswer = json.readValue(row.candidateJson(), MaterialAnswer.class);
        } else if (Purpose.ANSWER_EVALUATION.name().equals(row.purpose())) {
            evaluation = evaluations.binding(owner, row.id(), manifest.evaluation());
            if (row.candidateJson() != null) evaluationReport = json.readValue(row.candidateJson(), EvaluationReport.class);
            evaluationDecisions = evaluations.decisions(owner, row.id());
        } else if (Purpose.FOLLOW_UP.name().equals(row.purpose())) {
            var binding = mapper.followUp(owner, row.id());
            if (binding == null) throw new IllegalStateException("追问任务缺少训练绑定");
            followUp = new FollowUpLink(binding.sessionId(), binding.questionId(), binding.answerId(),
                    binding.rootQuestionId(), binding.generatedQuestionId());
        } else if (Purpose.QUESTION_DRAFT.name().equals(row.purpose())) {
            if (row.candidateJson() != null) questionCandidate = json.readValue(row.candidateJson(), QuestionCandidate.class);
            QuestionDecisionRow saved = mapper.questionDecision(owner, row.id());
            if (saved != null) questionDecision = new QuestionDecision(DecisionStatus.valueOf(saved.status()),
                    saved.targetQuestionId(), saved.targetVersionId(), saved.createdAt());
        } else if (Purpose.KNOWLEDGE_DRAFT.name().equals(row.purpose())) {
            if (manifest.trainingDraft() != null || manifest.reviewDraft() != null) evaluationDecisions = null;
            if (row.candidateJson() != null) candidate = manifest.reviewDraft() == null
                    ? json.readValue(row.candidateJson(), Candidate.class) : gateway.readReviewDraftCandidate(row.candidateJson(), manifest);
            DecisionRow saved = mapper.decision(owner, row.id());
            if (saved != null) decision = new CandidateDecision(DecisionStatus.valueOf(saved.status()),
                    saved.mode() == null ? null : AdoptionMode.valueOf(saved.mode()), saved.targetItemId(),
                    saved.targetVersionId(), saved.createdAt());
        } else throw new IllegalStateException("AI 任务用途不受支持，不能将结果作为知识候选读取");
        return new Task(summary(row), manifest, attempts, candidate, sources.current(owner, manifest, row.createdAt()), decision,
                questionCandidate, questionDecision, followUp, evaluation, evaluationReport, evaluationDecisions, materialAnswer,
                expressionCandidate, expressionDecision, preparationPlanCandidate, preparationPlanDecision, relationCandidate, relationDecisions,
                comparison, comparisonCandidate, cardCandidate, cardDecision, jdRequirementsCandidate, jdRequirementsDecision, factCandidate, factDecision,
                rewrite, rewriteCandidate, sourceQaAnswer, sourceQaManifestHash);
    }

    private ComparisonDtos.Candidate comparisonCandidate(TaskRow task, Manifest manifest) {
        if (!Status.SUCCEEDED.name().equals(task.status())) throw new IllegalStateException("比较候选与任务状态不一致");
        // 以保存时的原始 JSON 和固定清单复核结构与引文；后续来源变化不抹掉历史候选。
        return gateway.readComparisonCandidate(task.candidateJson(), manifest);
    }

    private FollowUpTask followUpView(String owner, TaskRow row) {
        Task task = view(owner, row);
        if (task.followUp() == null || task.manifest().purpose() != Purpose.FOLLOW_UP) throw followUpMissing();
        return new FollowUpTask(task.task(), task.manifest(), task.attempts(),
                task.followUp().generatedQuestionId() == null && task.sourcesCurrent()
                        && training.availability(owner, task.manifest().training(), row.id()) == null,
                task.followUp().generatedQuestionId());
    }

    private FollowUpRow requireFollowUp(String owner, String sessionId, String id) {
        uuid(owner); uuid(sessionId); uuid(id);
        FollowUpRow binding = mapper.followUp(owner, id);
        TaskRow task = mapper.task(owner, id);
        if (binding == null || !sessionId.equals(binding.sessionId()) || task == null
                || !Purpose.FOLLOW_UP.name().equals(task.purpose())) throw followUpMissing();
        Manifest manifest = manifest(task);
        FollowUpInput input = manifest.training();
        if (input == null || !sessionId.equals(input.sessionId()) || !binding.questionId().equals(input.questionId())
                || !binding.answerId().equals(input.answerId()) || !binding.rootQuestionId().equals(input.rootQuestionId())
                || binding.generation() != input.generation()) throw followUpMissing();
        return binding;
    }
    private Task decisionResult(String owner, TaskRow task, Instant now) {
        return view(owner, replaceTask(task, task.status(), task.candidateJson(), task.errorCode(), task.currentAttemptId(),
                task.attemptCount(), task.authorizationEpoch(), now));
    }

    private TaskRow undecidedRelationCandidate(String owner, String id, String candidateId, long expected) {
        TaskRow task = requireTask(owner, id);
        if (!Purpose.RELATION_DRAFT.name().equals(task.purpose()) || !Status.SUCCEEDED.name().equals(task.status())
                || task.candidateJson() == null) throw stateConflict();
        RelationCandidate candidate = relationCandidate(task, manifest(task));
        if (relationSuggestion(candidate, candidateId) == null) {
            throw new ApiException(404, "AI_RELATION_CANDIDATE_NOT_FOUND", "本次任务中不存在该关系候选。");
        }
        // 原键在命令入口恢复；新键必须先检查单项终态，不能凭旧回执再次创建已删除的正式边。
        if (mapper.relationDecision(owner, id, candidateId) != null) {
            throw new ApiException(409, "AI_CANDIDATE_DECIDED", "此关系候选已采用或忽略，请查看原决定。", task.revision());
        }
        if (task.revision() != expected) {
            throw new ApiException(409, "REVISION_CONFLICT", "AI 任务已变化，请重新读取后保留原输入再操作。", task.revision());
        }
        if (task.revision() >= MAX_SAFE) throw limit("AI 任务修订号已达到上限。");
        return task;
    }

    private RelationCandidate relationCandidate(TaskRow task, Manifest manifest) {
        if (!Purpose.RELATION_DRAFT.name().equals(task.purpose()) || !Status.SUCCEEDED.name().equals(task.status())
                || task.candidateJson() == null || !AiSources.validRelationInput(manifest)) {
            throw new IllegalStateException("知识关系候选的任务或固定清单无效");
        }
        try { return gateway.readRelationCandidate(task.candidateJson(), manifest); }
        catch (RuntimeException exception) { throw new IllegalStateException("知识关系候选的固定结果或引用原文无效", exception); }
    }

    private static RelationSuggestion relationSuggestion(RelationCandidate result, String candidateId) {
        return result == null ? null : result.relations().stream().filter(item -> item.id().equals(candidateId)).findFirst().orElse(null);
    }

    private RelationDecision relationDecision(String owner, TaskRow task, RelationCandidate result, RelationDecisionRow saved) {
        RelationSuggestion candidate = relationSuggestion(result, saved.candidateId());
        if (!owner.equals(saved.ownerId()) || !task.id().equals(saved.taskId()) || candidate == null || saved.createdAt() == null) {
            throw new IllegalStateException("知识关系决定不属于原任务候选");
        }
        DecisionStatus status = DecisionStatus.valueOf(saved.status());
        RelationContent content = null;
        if (status == DecisionStatus.ADOPTED) {
            if (saved.relationId() == null || saved.contentJson() == null) throw new IllegalStateException("采用决定缺少正式关系或最终内容");
            content = json.readValue(saved.contentJson(), RelationContent.class);
            try {
                uuid(saved.relationId());
                if (content == null || !content.equals(relationContent(content, candidate))) {
                    throw new IllegalStateException("知识关系采用决定未按关系方向归一");
                }
            } catch (ApiException invalidContent) {
                throw new IllegalStateException("知识关系采用决定与原候选不一致", invalidContent);
            }
            if (saved.supplementId() != null) {
                Manifest manifest = manifest(task);
                relationOriginSources(owner, task, manifest);
                // 补充决定引用不可变台账；后续归档、改版或删边不能让历史决定失去可读性。
                relationSupplements.verifyAiSupplement(owner, saved.supplementId(),
                        new Relation(saved.relationId(), content.sourceItemId(), content.targetItemId(), content.type()),
                        content.reason(), manifest.sources().stream()
                                .map(source -> new RelationSupplementDtos.SourceLink(source.itemId(), source.versionId())).toList(),
                        task.id(), saved.candidateId());
            }
        } else if (saved.relationId() != null || saved.contentJson() != null || saved.supplementId() != null) {
            throw new IllegalStateException("忽略关系候选不能携带正式关系或最终内容");
        }
        return new RelationDecision(saved.candidateId(), status, saved.relationId(), content, saved.createdAt(), saved.supplementId());
    }

    private List<OriginSource> relationOriginSources(String owner, TaskRow task, Manifest manifest) {
        requireReviewDraftBinding(owner, task, manifest);
        return relationKnowledgeSources(owner, task, manifest);
    }

    private List<OriginSource> relationKnowledgeSources(String owner, TaskRow task, Manifest manifest) {
        // 只核知识固定台账并返回当次状态，不回调任务绑定或逐项决定，避免来源与补充校验互相递归。
        if (!owner.equals(task.ownerId()) || !Purpose.RELATION_DRAFT.name().equals(task.purpose())
                || !AiSources.validRelationInput(manifest)) {
            throw new IllegalStateException("知识关系来源的固定清单无效");
        }
        List<RelationSourceRow> recorded = mapper.relationSources(owner, task.id());
        if (recorded == null || recorded.size() != manifest.sources().size()) throw new IllegalStateException("知识关系来源数量与固定清单不一致");
        var result = new ArrayList<OriginSource>(recorded.size());
        for (int index = 0; index < recorded.size(); index++) {
            RelationSourceRow source = recorded.get(index);
            Source fixed = manifest.sources().get(index);
            if (source == null || !task.id().equals(source.taskId()) || source.ordinal() != index + 1
                    || !fixed.itemId().equals(source.itemId()) || !fixed.versionId().equals(source.versionId())
                    || fixed.number() != source.number() || !fixed.title().equals(source.title()) || !fixed.kind().equals(source.kind())) {
                throw new IllegalStateException("知识关系来源的身份、顺序或当时元数据与固定清单不一致");
            }
            OriginState state;
            try { state = OriginState.valueOf(source.state()); }
            catch (IllegalArgumentException | NullPointerException exception) {
                throw new IllegalStateException("知识关系来源状态无效", exception);
            }
            if (!Set.of(OriginState.CURRENT, OriginState.SUPERSEDED, OriginState.ARCHIVED, OriginState.UNAVAILABLE).contains(state)) {
                throw new IllegalStateException("知识关系来源状态无效");
            }
            result.add(new OriginSource(fixed.itemId(), fixed.versionId(), fixed.number(), fixed.title(), state, fixed.kind()));
        }
        return List.copyOf(result);
    }

    private static RelationContent relationContent(RelationContent content, RelationSuggestion candidate) {
        required(content);
        uuid(content.sourceItemId()); uuid(content.targetItemId());
        if (content.type() == null || !validRelationText(content.reason(), 1000)) {
            throw invalid("关系类型不能为空，理由须为 1 至 1000 个 UTF-16 码元且不能含 NUL 或不完整字符。");
        }
        boolean original = content.sourceItemId().equals(candidate.sourceItemId()) && content.targetItemId().equals(candidate.targetItemId());
        boolean reversed = content.sourceItemId().equals(candidate.targetItemId()) && content.targetItemId().equals(candidate.sourceItemId());
        if (!original && !reversed) throw invalid("采用时只能保留或交换原候选的两个概念端点。");
        boolean swap = (content.type() == RelationType.RELATED || content.type() == RelationType.CONTRASTS)
                && content.sourceItemId().compareTo(content.targetItemId()) > 0;
        return swap ? new RelationContent(content.targetItemId(), content.sourceItemId(), content.type(), content.reason()) : content;
    }

    private static boolean validRelationText(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum && value.indexOf('\0') < 0
                && value.codePoints().noneMatch(codePoint -> codePoint >= 0xD800 && codePoint <= 0xDFFF);
    }

    private static void relationCandidateId(String value) {
        if (value == null || !value.matches("r([1-9]|10)")) throw invalid("关系候选标识无效。");
    }

    private TaskRow undecidedCandidate(String owner, String id, long expected, Purpose expectedPurpose) {
        TaskRow task = requireTask(owner, id);
        if (expectedPurpose != null && !expectedPurpose.name().equals(task.purpose())) throw stateConflict();
        // 原幂等键已在外层恢复回执；新键必须读取决定表，不能把旧回执中的空决定当作可再次采用。
        if (mapper.decision(owner, id) != null || mapper.questionDecision(owner, id) != null
                || mapper.expressionDecision(owner, id) != null || mapper.preparationPlanDecision(owner, id) != null
                 || cardDrafts.decision(owner, id) != null || jdRequirements.decision(owner, id) != null || factDrafts.decision(owner, id) != null) {
            throw new ApiException(409, "AI_CANDIDATE_DECIDED", "此候选已采用或忽略，请查看原决定。", task.revision());
        }
        if (task.revision() != expected) {
            throw new ApiException(409, "REVISION_CONFLICT", "AI 任务已变化，请重新读取后保留原输入再操作。", task.revision());
        }
        if (task.revision() >= MAX_SAFE) throw limit("AI 任务修订号已达到上限。");
        if (!Set.of(Purpose.KNOWLEDGE_DRAFT.name(), Purpose.QUESTION_DRAFT.name(), Purpose.EXPRESSION_DRAFT.name(),
                    Purpose.PREPARATION_PLAN.name(), Purpose.MEMORY_CARD_DRAFT.name(), Purpose.JD_REQUIREMENT_DRAFT.name(), Purpose.FACT_DRAFT.name()).contains(task.purpose())
                || !Status.SUCCEEDED.name().equals(task.status())
                || task.candidateJson() == null) throw stateConflict();
        return task;
    }
    private Summary summary(TaskRow row) { return new Summary(row.id(), Purpose.valueOf(row.purpose()), Status.valueOf(row.status()),
            row.revision(), row.title(), row.provider(), row.model(), row.currency(), row.attemptCount(), row.createdAt(), row.updatedAt(), row.errorCode()); }
    private Summary summary(SummaryRow row) { return new Summary(row.id(), Purpose.valueOf(row.purpose()), Status.valueOf(row.status()),
            row.revision(), row.title(), row.provider(), row.model(), row.currency(), row.attemptCount(), row.createdAt(), row.updatedAt(), row.errorCode()); }
    private Manifest manifest(TaskRow row) { return json.readValue(row.manifestJson(), Manifest.class); }

    private Budget budget(String owner, Configuration config, LocalDate date) {
        BudgetRow totals = mapper.budget(owner, config.currency(), date);
        BigDecimal remaining = BigDecimal.valueOf(config.dailyBudgetMicros()).subtract(total(totals)).max(BigDecimal.ZERO);
        return new Budget(date.toString(), config.currency(), config.dailyBudgetMicros(), safeAmount(totals.reportedMicros()),
                safeAmount(totals.reservedMicros()), safeAmount(totals.unknownMicros()), totals.unaccountableCount() > 0 ? 0 : safeAmount(remaining));
    }
    private void reserve(String owner, Configuration config, long requested) {
        if (requested < 0 || requested > MAX_SAFE) throw limit("本次费用预留超出金额范围。");
        BudgetRow totals = mapper.budget(owner, config.currency(), today());
        if (totals.unaccountableCount() > 0) {
            throw new ApiException(409, "AI_CURRENCY_PENDING", "工作区仍有其他币种的未结费用或当日支出，不能直接换币种继续发送；未知费用可在原任务中核对。");
        }
        if (total(totals).add(BigDecimal.valueOf(requested))
                .compareTo(BigDecimal.valueOf(config.dailyBudgetMicros())) > 0) {
            throw new ApiException(409, "AI_BUDGET_EXCEEDED", "今日预算不足；未知费用预留不会跨日自动释放。");
        }
    }
    private static BigDecimal total(BudgetRow totals) { return totals.reportedMicros().add(totals.reservedMicros()).add(totals.unknownMicros()); }
    private static long safeAmount(BigDecimal value) { return value.max(BigDecimal.ZERO).min(BigDecimal.valueOf(MAX_SAFE)).longValueExact(); }
    private void capacity(String owner) { if (mapper.pendingCount(owner) >= 20) throw limit("AI 工作区最多保留 20 个待执行或执行中的任务。"); }
    private TaskRow requireTask(String owner, String id) { TaskRow row = mapper.task(uuid(owner), uuid(id)); if (row == null) throw missing(); return row; }
    private AttemptRow requireAttempt(String owner, String task, String id) { AttemptRow row = mapper.attempt(owner, task, id); if (row == null) throw missing(); return row; }
    private TaskRow changeable(String owner, String id, long expected) {
        TaskRow row = requireTask(owner, id);
        if (row.revision() != expected) throw new ApiException(409, "REVISION_CONFLICT", "AI 任务已变化，请重新读取后保留原输入再操作。", row.revision());
        if (row.revision() >= MAX_SAFE) throw limit("AI 任务修订号已达到上限。");
        return row;
    }
    private <T> T locked(String owner, Supplier<T> action) {
        uuid(owner);
        return transaction.execute(status -> {
            workspaces.ensureWorkspace(owner);
            if (workspaces.lockWorkspace(owner) == null) throw new IllegalStateException("无法取得 AI 工作区锁");
            return action.get();
        });
    }
    private static Integer validTokens(Integer value) { return value != null && value >= 0 ? value : null; }
    private static String safeProviderId(String value) { return value != null && value.matches("[A-Za-z0-9_.:-]{1,128}") ? value : null; }
    private static String safeError(String value) { return value == null ? null : value.matches("[A-Z0-9_]{1,64}") ? value : "AI_PROVIDER_FAILED"; }
    private static void consent(Boolean value) { if (!Boolean.TRUE.equals(value)) throw invalid("必须明确确认本次外发授权。"); }
    private static void revision(Long value) { if (value == null || value < 0 || value > MAX_SAFE) throw invalid("预期修订号无效。"); }
    private static void pageLimit(int value) { if (value < 1 || value > 50) throw invalid("每页数量必须为 1 至 50。"); }
    private static <T> T required(T value) { if (value == null) throw invalid("必填内容不能为空。"); return value; }
    private static PreparationDtos.ExpressionContent expressionContent(Manifest manifest, PreparationDtos.ExpressionContent content) {
        if (content.type() == null || content.type() != manifest.expressionType()) {
            throw invalid("采用的表达类型必须与本次授权一致。");
        }
        String title = expressionText(content.title(), 200, "表达标题");
        String body = expressionText(content.body(), 12_000, "表达正文");
        if (content.factVersionIds() == null || content.factVersionIds().isEmpty() || content.factVersionIds().size() > 5) {
            throw invalid("请明确选择 1–5 个本次任务已使用的事实版本作为最终依据。");
        }
        var allowed = new HashSet<String>();
        for (Source source : manifest.sources()) allowed.add(source.versionId());
        var selected = new HashSet<String>();
        for (String versionId : content.factVersionIds()) {
            uuid(versionId);
            if (!allowed.contains(versionId) || !selected.add(versionId)) {
                throw invalid("表达依据须为本次授权事实的非空去重子集，不能增加未参与生成的版本。");
            }
        }
        return new PreparationDtos.ExpressionContent(title, content.type(), body, List.copyOf(content.factVersionIds()), content.tags());
    }
    private static String expressionText(String value, int maximum, String field) {
        if (value == null || value.indexOf('\0') >= 0
                || value.codePoints().anyMatch(codePoint -> codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            throw invalid(field + "不能为空或包含 NUL、不完整字符。");
        }
        String trimmed = value.strip();
        if (trimmed.isBlank() || trimmed.length() > maximum) throw invalid(field + "为空或超过 UTF-16 长度上限。");
        return trimmed;
    }
    private static String text(String value, int max, boolean required) { String text = value == null ? "" : value;
        if ((required && text.isBlank()) || text.codePointCount(0, text.length()) > max) throw invalid("说明为空或超过长度限制。"); return text; }
    private static String uuid(String value) { try { if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException exception) { throw invalid("对象标识必须为标准小写 UUID。"); } return value; }
    private static void changed(int count) { if (count != 1) throw new ApiException(409, "REVISION_CONFLICT", "AI 任务已变化，本次修改未提交。"); }
    private static String operation(String id, String action) { return "POST /api/v1/ai/tasks/" + id + "/" + action; }
    private static String taskOperation(String sessionId, String id, String action) {
        return sessionId == null ? operation(id, action)
                : "POST /api/v1/training/sessions/" + sessionId + "/follow-ups/" + id + "/" + action;
    }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static LocalDate today() { return LocalDate.now(ZoneOffset.UTC); }
    private static LocalDate utcDate(Instant time) { return time.atOffset(ZoneOffset.UTC).toLocalDate(); }
    private static String newId() { return UUID.randomUUID().toString(); }
    private static ApiException invalid(String message) { return new ApiException(400, "INVALID_REQUEST", message); }
    private static ApiException limit(String message) { return new ApiException(422, "AI_LIMIT", message); }
    private static ApiException missing() { return new ApiException(404, "AI_TASK_NOT_FOUND", "AI 任务或尝试不存在或不可访问。"); }
    private static ApiException stateConflict() { return new ApiException(409, "AI_STATE_CONFLICT", "当前 AI 任务或费用状态不允许此操作。"); }
    private static ApiException followUpMissing() { return new ApiException(404, "AI_FOLLOW_UP_NOT_FOUND", "本场训练的追问任务不存在或不可访问。"); }
    private static ApiException configurationChanged() { return new ApiException(409, "AI_CONFIG_CHANGED", "AI 配置或价格已变化，请重新预览并创建新任务。"); }

    public record AiDraftAccess(String status, boolean streaming) {}
    public record Claim(String owner, String taskId, String attemptId, String token, String epoch, Manifest manifest) {}
    private record Accounting(String status, Long amount, Integer promptTokens, Integer completionTokens) {}
}
