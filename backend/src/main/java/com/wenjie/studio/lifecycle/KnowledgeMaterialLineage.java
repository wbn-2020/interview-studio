package com.wenjie.studio.lifecycle;

import com.wenjie.studio.ai.AiDtos;
import com.wenjie.studio.ai.AiManifestDigest;
import com.wenjie.studio.ai.AiSources;
import com.wenjie.studio.ai.FactDraftDtos;
import com.wenjie.studio.ai.FactDraftSources;
import com.wenjie.studio.bank.QuestionDtos;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.material.MaterialDtos;
import com.wenjie.studio.material.MaterialLifecycleDtos;
import com.wenjie.studio.prepare.PreparationDtos;
import com.wenjie.studio.training.TrainingDtos;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static com.wenjie.studio.lifecycle.KnowledgeMaterialLineageRows.*;

@Component
public class KnowledgeMaterialLineage {
    private static final int MAX_VERSIONS = 200;
    private static final int MAX_ADOPTIONS = 500;
    private static final int MAX_BINDINGS = 2500;
    private static final int MAX_EXCERPTS = 200;
    private static final int MAX_MANIFEST_BYTES = 131072;
    private static final int MAX_CONTEXT_BYTES = 131072;
    private static final int MAX_PREPARATION_BYTES = 1_048_576;
    private static final int MAX_ANSWER_BYTES = 196608;
    private static final int MAX_REPORT_BYTES = 524288;
    private static final String EVALUATION_VALIDATION_NOTE = "AI 未审评价，结构与确切输入的原文定位已核对；仅表示结构和定位合法，不证明语义、技术正确性或用户经历真实。个人参考与检查点仍为未审标准，内容需人工审阅。";
    private static final String FOLLOW_UP_VALIDATION_NOTE = "AI 追问，结构和确切已提交答案的原文定位已核对；不证明问题的技术正确性，也不能保证题干完全没有暗示。";
    private static final long MAX_REVISION = 9_007_199_254_740_991L;
    private final KnowledgeMaterialLineageMapper mapper;
    private final AiManifestDigest digest;
    private final JsonMapper json;

    public KnowledgeMaterialLineage(KnowledgeMaterialLineageMapper mapper, AiManifestDigest digest) {
        this.mapper = mapper;
        this.digest = digest;
        this.json = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                                .maxStringLength(MAX_PREPARATION_BYTES).maxNumberLength(32).build()).build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @Transactional(readOnly = true, propagation = Propagation.MANDATORY, noRollbackFor = RuntimeException.class)
    public List<String> excerptIds(String owner, List<AiDtos.SourceLink> roots) {
        if (roots == null) throw integrity();
        // 只从知识根沿实际采用与固定生成输入展开；未记录的父项目历史及其他隐式背景不属于本闭包。
        if (roots.isEmpty()) return List.of();
        uuid(owner);
        var traversal = new Traversal(owner);
        for (var root : roots) traversal.version(root);
        return traversal.collect();
    }

    private final class Traversal {
        private final String owner;
        private final Map<AiDtos.SourceLink, VersionRow> versions = new HashMap<>();
        private final Map<AiDtos.SourceLink, PreparedVersion> preparationVersions = new HashMap<>();
        private final Map<String, SavedAnswer> answers = new HashMap<>();
        private final Map<String, SavedQuestion> trainingQuestions = new HashMap<>();
        private final Map<AiDtos.SourceLink, SavedBankVersion> bankVersions = new HashMap<>();
        private final Map<AiDtos.SourceLink, AiDtos.EvaluationStandard> bankStandards = new HashMap<>();
        private final Map<String, SavedEvaluation> evaluations = new HashMap<>();
        private final Map<String, String> followUpQuestions = new HashMap<>();
        private final ArrayDeque<AiDtos.SourceLink> pending = new ArrayDeque<>();
        private final ArrayDeque<AiDtos.SourceLink> preparationPending = new ArrayDeque<>();
        private final ArrayDeque<String> answerPending = new ArrayDeque<>();
        private final ArrayDeque<String> questionPending = new ArrayDeque<>();
        private final ArrayDeque<AiDtos.SourceLink> bankPending = new ArrayDeque<>();
        private final Map<AiDtos.Purpose, TreeSet<String>> taskIds = new EnumMap<>(AiDtos.Purpose.class);
        private final Map<String, AiDtos.Purpose> taskPurposes = new HashMap<>();
        private final TreeSet<String> excerptIds = new TreeSet<>();
        private int bindingCount;

        private Traversal(String owner) { this.owner = owner; }

        private VersionRow version(AiDtos.SourceLink link) {
            if (link == null) throw integrity();
            uuid(link.itemId()); uuid(link.versionId());
            var known = versions.get(link);
            if (known != null) return known;
            int remaining = remainingVersions();
            if (remaining == 0) throw limit();
            var rows = mapper.version(owner, link.itemId(), link.versionId(), remaining + 1);
            if (rows == null || rows.size() != 1) throw integrity();
            var value = rows.getFirst();
            if (value == null || !owner.equals(value.ownerId()) || !owner.equals(value.itemOwnerId())
                    || !link.itemId().equals(value.itemId()) || !link.versionId().equals(value.versionId())
                    || value.versionNumber() == null || value.versionNumber() < 1
                    || !AiSources.knowledgeKind(value.itemKind()) || !knowledgeStatus(value.itemStatus())
                    || value.title() == null || value.title().isBlank() || value.body() == null) throw integrity();
            versions.put(link, value);
            pending.addLast(link);
            return value;
        }

        private List<String> collect() {
            while (!pending.isEmpty() || !preparationPending.isEmpty() || !answerPending.isEmpty()
                    || !questionPending.isEmpty() || !bankPending.isEmpty()) {
                if (!pending.isEmpty()) {
                    var link = pending.removeFirst();
                    adoptions(link, versions.get(link).versionNumber(), AiDtos.Purpose.KNOWLEDGE_DRAFT);
                } else if (!preparationPending.isEmpty()) {
                    var link = preparationPending.removeFirst();
                    var version = preparationVersions.get(link).row();
                    var purpose = "EXPRESSION".equals(version.itemKind())
                            ? AiDtos.Purpose.EXPRESSION_DRAFT : AiDtos.Purpose.FACT_DRAFT;
                    adoptions(link, version.versionNumber(), purpose);
                } else if (!answerPending.isEmpty()) {
                    answerReferences(answers.get(answerPending.removeFirst()));
                } else if (!questionPending.isEmpty()) {
                    questionReferences(trainingQuestions.get(questionPending.removeFirst()));
                } else {
                    var link = bankPending.removeFirst();
                    var saved = bankVersions.get(link);
                    bankKnowledgeBindings(saved);
                    adoptions(link, saved.row().versionNumber(), AiDtos.Purpose.QUESTION_DRAFT);
                }
            }
            return List.copyOf(excerptIds);
        }

        private void adoptions(AiDtos.SourceLink link, int through, AiDtos.Purpose purpose) {
            var knownTasks = taskIds.computeIfAbsent(purpose, ignored -> new TreeSet<>());
            int remaining = MAX_ADOPTIONS - taskPurposes.size();
            // 分用途排除已核任务，跨用途仍会查出并报错；所有实际采用共用同一额度。
            var rows = switch (purpose) {
                case KNOWLEDGE_DRAFT -> mapper.adoptions(owner, link.itemId(), through, List.copyOf(knownTasks), remaining + 1);
                case EXPRESSION_DRAFT -> mapper.expressionAdoptions(owner, link.itemId(), through, List.copyOf(knownTasks), remaining + 1);
                case FACT_DRAFT -> mapper.factAdoptions(owner, link.itemId(), through, List.copyOf(knownTasks), remaining + 1);
                case QUESTION_DRAFT -> mapper.questionAdoptions(owner, link.itemId(), through, List.copyOf(knownTasks), remaining + 1);
                default -> throw integrity();
            };
            if (rows == null) throw integrity();
            if (rows.size() > remaining) throw limit();
            for (var row : rows) {
                adoption(row, link, through, purpose);
                if (taskPurposes.putIfAbsent(row.taskId(), purpose) != null || !knownTasks.add(row.taskId())) throw integrity();
                var manifest = manifest(row, purpose);
                var target = new AiDtos.SourceLink(row.targetItemId(), row.targetVersionId());
                if (purpose == AiDtos.Purpose.KNOWLEDGE_DRAFT) {
                    if (!Objects.equals(version(target).versionNumber(), row.targetVersionNumber())) throw integrity();
                } else if (purpose == AiDtos.Purpose.QUESTION_DRAFT) {
                    if (!Objects.equals(bankVersion(target).row().versionNumber(), row.targetVersionNumber())) throw integrity();
                } else if (purpose == AiDtos.Purpose.EXPRESSION_DRAFT) {
                    var adopted = preparationVersion(target, "EXPRESSION");
                    var allowed = new HashSet<AiDtos.SourceLink>();
                    for (var source : manifest.sources()) allowed.add(new AiDtos.SourceLink(source.itemId(), source.versionId()));
                    // 只约束该次实际采用版；后续手工版可以改依据，但仍继承原任务的全部来源。
                    if (!Objects.equals(adopted.row().versionNumber(), row.targetVersionNumber())
                            || adopted.content().expressionType() != manifest.expressionType()
                            || adopted.facts().isEmpty() || !allowed.containsAll(adopted.facts())) throw integrity();
                } else {
                    var adopted = adoptedFactVersion(target);
                    var input = manifest.factDraft();
                    var expected = new PreparationDtos.Source("AI 事实候选", "", FactDraftSources.VALIDATION_NOTE,
                            input.materials().stream().map(MaterialDtos.AiExcerpt::excerptId).toList());
                    if (!Objects.equals(adopted.row().versionNumber(), row.targetVersionNumber())
                            || !row.targetProjectId().equals(adopted.row().projectId())
                            || !expected.equals(adopted.content().fact().source())) throw integrity();
                    factBindings(row.taskId(), input);
                }
                trainingBindings(row.taskId(), manifest.trainingDraft());
                knowledgeBindings(row.taskId(), manifest);
                preparationBindings(row.taskId(), manifest);
                materialBindings(row.taskId(), manifest);
            }
        }

        private void adoption(AdoptionRow row, AiDtos.SourceLink link, int through, AiDtos.Purpose purpose) {
            if (row == null || !owner.equals(row.ownerId()) || !owner.equals(row.taskOwnerId())
                    || !owner.equals(row.itemOwnerId()) || !owner.equals(row.versionOwnerId())
                    || !AiDtos.DecisionStatus.ADOPTED.name().equals(row.status())
                    || !purpose.name().equals(row.purpose())
                    || !AiDtos.Status.SUCCEEDED.name().equals(row.taskStatus())
                    || !link.itemId().equals(row.targetItemId()) || !link.itemId().equals(row.actualItemId())
                    || !link.itemId().equals(row.versionItemId()) || !Objects.equals(row.taskId(), row.actualTaskId())
                    || !Objects.equals(row.targetVersionId(), row.actualVersionId())
                    || !knowledgeStatus(row.itemStatus())
                    || row.targetVersionNumber() == null || row.targetVersionNumber() < 1
                    || row.targetVersionNumber() > through) throw integrity();
            uuid(row.taskId()); uuid(row.targetVersionId());
            if (purpose == AiDtos.Purpose.QUESTION_DRAFT) {
                if (!"QUESTION".equals(row.itemKind()) || row.mode() != null || row.targetVersionNumber() != 1
                        || row.targetProjectId() != null || row.actualProjectId() != null) throw integrity();
                return;
            }
            if (purpose == AiDtos.Purpose.FACT_DRAFT) {
                uuid(row.targetProjectId());
                if (!"FACT".equals(row.itemKind()) || row.mode() != null || row.targetVersionNumber() != 1
                        || !row.targetProjectId().equals(row.actualProjectId())) throw integrity();
                return;
            }
            if ((purpose == AiDtos.Purpose.KNOWLEDGE_DRAFT ? !AiSources.knowledgeKind(row.itemKind())
                    : !"EXPRESSION".equals(row.itemKind()))
                    || row.targetProjectId() != null || row.actualProjectId() != null) throw integrity();
            if (AiDtos.AdoptionMode.NEW.name().equals(row.mode())) {
                if (row.targetVersionNumber() != 1) throw integrity();
            } else if (AiDtos.AdoptionMode.EXISTING.name().equals(row.mode())) {
                if (row.targetVersionNumber() <= 1) throw integrity();
            } else throw integrity();
        }

        private void knowledgeBindings(String taskId, AiDtos.Manifest manifest) {
            int remaining = MAX_BINDINGS - bindingCount;
            var rows = manifest.purpose() == AiDtos.Purpose.QUESTION_DRAFT
                    ? mapper.questionKnowledgeBindings(owner, taskId, remaining + 1)
                    : mapper.knowledgeBindings(owner, taskId, remaining + 1);
            if (rows == null) throw integrity();
            if (rows.size() > remaining) throw limit();
            bindingCount += rows.size();
            int bindingIndex = 0;
            for (int index = 0; index < manifest.sources().size(); index++) {
                var fixed = manifest.sources().get(index);
                if (!AiSources.knowledgeKind(fixed.kind())) continue;
                if (bindingIndex >= rows.size()) throw integrity();
                var row = rows.get(bindingIndex++);
                if (row == null || !owner.equals(row.ownerId()) || !taskId.equals(row.taskId())
                        || !fixed.itemId().equals(row.itemId()) || !fixed.versionId().equals(row.versionId())
                        || row.versionNumber() == null || fixed.number() != row.versionNumber()
                        || !fixed.title().equals(row.title()) || row.ordinal() == null || row.ordinal() != index + 1) {
                    throw integrity();
                }
                var actual = version(new AiDtos.SourceLink(row.itemId(), row.versionId()));
                if (fixed.number() != actual.versionNumber() || !fixed.kind().equals(actual.itemKind())
                        || !fixed.title().equals(actual.title()) || !fixed.body().equals(actual.body())) throw integrity();
            }
            if (bindingIndex != rows.size()) throw integrity();
        }

        private void preparationBindings(String taskId, AiDtos.Manifest manifest) {
            int remaining = MAX_BINDINGS - bindingCount;
            var rows = mapper.preparationBindings(owner, taskId, remaining + 1);
            countBindings(rows);
            int bindingIndex = 0, expressionIndex = 0;
            for (int index = 0; index < manifest.sources().size(); index++) {
                var fixed = manifest.sources().get(index);
                if (AiSources.knowledgeKind(fixed.kind())) continue;
                if (bindingIndex >= rows.size()) throw integrity();
                var row = rows.get(bindingIndex++);
                if (row == null || !owner.equals(row.ownerId()) || !taskId.equals(row.taskId())
                        || !fixed.itemId().equals(row.itemId()) || !fixed.versionId().equals(row.versionId())
                        || !fixed.kind().equals(row.kind()) || row.versionNumber() == null
                        || fixed.number() != row.versionNumber() || !fixed.title().equals(row.title())
                        || row.ordinal() == null || row.ordinal() != index + 1) throw integrity();
                var actual = preparationVersion(new AiDtos.SourceLink(row.itemId(), row.versionId()), row.kind());
                if (fixed.number() != actual.row().versionNumber() || !fixed.title().equals(actual.row().title())
                        || !fixed.body().equals(actual.content().body())) throw integrity();
                if ("EXPRESSION".equals(fixed.kind())) {
                    if (manifest.expressionChecks() == null || expressionIndex >= manifest.expressionChecks().size()) {
                        throw integrity();
                    }
                    var captured = manifest.expressionChecks().get(expressionIndex++).factChecks();
                    if (captured.size() != actual.facts().size()) throw integrity();
                    for (int factIndex = 0; factIndex < captured.size(); factIndex++) {
                        var check = captured.get(factIndex);
                        var fact = actual.facts().get(factIndex);
                        // 这里只核原快照的完整身份和顺序，不用今天的状态或代次改写祖先历史。
                        if (!fact.itemId().equals(check.itemId()) || !fact.versionId().equals(check.versionId())) {
                            throw integrity();
                        }
                    }
                }
            }
            if (bindingIndex != rows.size()
                    || (manifest.expressionChecks() == null ? expressionIndex != 0
                        : expressionIndex != manifest.expressionChecks().size())) throw integrity();
        }

        private PreparedVersion preparationVersion(AiDtos.SourceLink link, String kind) {
            var prepared = loadPreparationVersion(link, kind);
            // 历史事实首版可未核实，但作为原 AI 输入的事实必须已核实；缓存命中也不得绕过角色校验。
            if ("FACT".equals(kind) && prepared.content().fact().responsibility() == PreparationDtos.Responsibility.UNVERIFIED) {
                throw integrity();
            }
            return prepared;
        }

        private PreparedVersion adoptedFactVersion(AiDtos.SourceLink link) {
            var prepared = loadPreparationVersion(link, "FACT");
            if (prepared.content().fact().responsibility() != PreparationDtos.Responsibility.UNVERIFIED) throw integrity();
            return prepared;
        }

        private PreparedVersion loadPreparationVersion(AiDtos.SourceLink link, String kind) {
            uuid(link.itemId()); uuid(link.versionId());
            var known = preparationVersions.get(link);
            if (known != null) {
                if (!kind.equals(known.row().itemKind())) throw integrity();
                return known;
            }
            int remaining = remainingVersions();
            if (remaining == 0) throw limit();
            var rows = mapper.preparationVersion(owner, link.itemId(), link.versionId(), remaining + 1);
            if (rows == null || rows.size() != 1) throw integrity();
            var row = rows.getFirst();
            if (row == null || !owner.equals(row.ownerId()) || !owner.equals(row.itemOwnerId())
                    || !link.itemId().equals(row.itemId()) || !link.versionId().equals(row.versionId())
                    || !kind.equals(row.itemKind()) || !knowledgeStatus(row.itemStatus())
                    || row.versionNumber() == null || row.versionNumber() < 1
                    || row.title() == null || row.title().isBlank()) throw integrity();
            if ("FACT".equals(kind)) {
                uuid(row.projectId());
                if (!owner.equals(row.projectOwnerId()) || !row.projectId().equals(row.actualProjectId())
                        || !"PROJECT".equals(row.projectKind())) throw integrity();
            } else if (row.projectId() != null || row.projectOwnerId() != null
                    || row.actualProjectId() != null || row.projectKind() != null) throw integrity();
            var content = preparationContent(row);
            var facts = preparationReferences(row, content);
            var prepared = new PreparedVersion(row, content, facts);
            // 先占本节点，再展开其来源，避免共享节点额度少算一层。
            preparationVersions.put(link, prepared);
            if ("EXPRESSION".equals(kind) || "FACT".equals(kind)) preparationPending.addLast(link);
            preparationMaterials(row, content.excerptIds());
            for (var fact : facts) preparationVersion(fact, "FACT");
            return prepared;
        }

        private void preparationMaterials(PreparationVersionRow version, List<String> fixedIds) {
            var rows = mapper.preparationMaterials(owner, version.versionId(), MAX_BINDINGS - bindingCount + 1);
            countBindings(rows);
            if (rows.size() != fixedIds.size()) throw integrity();
            var actualIds = new ArrayList<String>();
            for (var row : rows) {
                if (row == null || !owner.equals(row.ownerId()) || !version.itemId().equals(row.itemId())
                        || !version.versionId().equals(row.versionId()) || !owner.equals(row.excerptOwnerId())
                        || !owner.equals(row.attachmentOwnerId()) || !Objects.equals(row.excerptId(), row.actualExcerptId())
                        || !Objects.equals(row.attachmentId(), row.actualAttachmentId())
                        || row.attachmentSha256() == null || !row.attachmentSha256().matches("[0-9a-f]{64}")
                        || !row.attachmentSha256().equals(row.actualSha256())) throw integrity();
                uuid(row.excerptId()); uuid(row.attachmentId());
                actualIds.add(row.excerptId());
            }
            if (!fixedIds.stream().sorted().toList().equals(actualIds)) throw integrity();
            excerptIds.addAll(actualIds);
            if (excerptIds.size() > MAX_EXCERPTS) throw limit();
        }

        private List<AiDtos.SourceLink> preparationReferences(PreparationVersionRow version, PreparedContent content) {
            var rows = mapper.preparationReferences(owner, version.versionId(), MAX_BINDINGS - bindingCount + 1);
            countBindings(rows);
            var byVersion = new HashMap<String, AiDtos.SourceLink>();
            var organization = new HashSet<PreparationDtos.VersionLink>();
            for (var row : rows) {
                if (row == null || !owner.equals(row.ownerId()) || !version.itemId().equals(row.targetItemId())
                        || !version.versionId().equals(row.targetVersionId())) throw integrity();
                if ("EXPRESSION".equals(version.itemKind())) {
                    if (!"FACT".equals(row.sourceKind()) || !"FACT_BASIS".equals(row.useType())
                            || row.knowledgeItemId() != null || row.knowledgeVersionId() != null) throw integrity();
                    uuid(row.preparationItemId()); uuid(row.preparationVersionId());
                    if (byVersion.put(row.preparationVersionId(),
                            new AiDtos.SourceLink(row.preparationItemId(), row.preparationVersionId())) != null) throw integrity();
                } else if ("TARGET".equals(version.itemKind())) {
                    if (!"ORGANIZATION".equals(row.useType())) throw integrity();
                    PreparationDtos.LinkKind kind;
                    try { kind = PreparationDtos.LinkKind.valueOf(row.sourceKind()); }
                    catch (RuntimeException exception) { throw integrity(); }
                    String itemId, versionId;
                    if (kind == PreparationDtos.LinkKind.KNOWLEDGE) {
                        if (row.preparationItemId() != null || row.preparationVersionId() != null) throw integrity();
                        itemId = row.knowledgeItemId(); versionId = row.knowledgeVersionId();
                    } else {
                        if (row.knowledgeItemId() != null || row.knowledgeVersionId() != null) throw integrity();
                        itemId = row.preparationItemId(); versionId = row.preparationVersionId();
                    }
                    uuid(itemId); uuid(versionId);
                    if (!organization.add(new PreparationDtos.VersionLink(kind, itemId, versionId))) throw integrity();
                } else throw integrity();
            }
            if ("TARGET".equals(version.itemKind())) {
                // 组织关系只核原 JSON 与登记一致，不将其扩成内容或材料祖先。
                if (!organization.equals(new HashSet<>(content.links()))) throw integrity();
            }
            if (!"EXPRESSION".equals(version.itemKind())) return List.of();
            if (byVersion.size() != content.factVersionIds().size()) throw integrity();
            var facts = new ArrayList<AiDtos.SourceLink>();
            for (String factVersionId : content.factVersionIds()) {
                var fact = byVersion.get(factVersionId);
                if (fact == null) throw integrity();
                facts.add(fact);
            }
            return List.copyOf(facts);
        }

        private void countBindings(List<?> rows) {
            if (rows == null) throw integrity();
            if (rows.size() > MAX_BINDINGS - bindingCount) throw limit();
            bindingCount += rows.size();
        }

        private int remainingVersions() {
            return MAX_VERSIONS - versions.size() - preparationVersions.size() - answers.size()
                    - trainingQuestions.size() - bankVersions.size() - evaluations.size() - followUpQuestions.size();
        }

        private void factBindings(String taskId, FactDraftDtos.Input input) {
            var heads = mapper.factSources(owner, taskId, MAX_BINDINGS - bindingCount + 1);
            countBindings(heads);
            if (heads.size() != 1) throw integrity();
            var head = heads.getFirst();
            if (head == null || !owner.equals(head.ownerId()) || !taskId.equals(head.taskId())
                    || !input.kind().name().equals(head.kind())) throw integrity();
            if (input.kind() == FactDraftDtos.Kind.MATERIAL) {
                if (head.sessionId() != null || head.questionId() != null || head.answerId() != null) throw integrity();
            } else {
                var fixed = input.training();
                if (fixed == null || !fixed.sessionId().equals(head.sessionId())
                        || !fixed.questionId().equals(head.questionId()) || !fixed.answerId().equals(head.answerId())) {
                    throw integrity();
                }
                trainingAnswer(new AnswerSelection(fixed.sessionId(), fixed.questionId(), fixed.answerId(),
                        fixed.answerNumber(), fixed.answerCondition(), fixed.fragments()));
            }
            var rows = mapper.factMaterials(owner, taskId, MAX_BINDINGS - bindingCount + 1);
            countBindings(rows);
            if (rows.size() != input.materials().size()) throw integrity();
            for (int index = 0; index < rows.size(); index++) {
                materialBinding(taskId, rows.get(index), input.materials().get(index), index + 1, "MATERIAL");
            }
        }

        private void trainingBindings(String taskId, AiDtos.TrainingDraftInput fixed) {
            var heads = mapper.trainingSources(owner, taskId, MAX_BINDINGS - bindingCount + 1);
            countBindings(heads);
            if (fixed == null) {
                if (!heads.isEmpty()) throw integrity();
                return;
            }
            if (heads.size() != 1) throw integrity();
            var head = heads.getFirst();
            // 来源头逐任务核对；同一答案缓存不能掩盖另一任务错绑的评价或选区。
            if (head == null || !owner.equals(head.ownerId()) || !taskId.equals(head.taskId())
                    || !fixed.sessionId().equals(head.sessionId()) || !fixed.questionId().equals(head.questionId())
                    || !fixed.answerId().equals(head.answerId())
                    || !Objects.equals(fixed.feedbackTaskId(), head.evaluationTaskId())) throw integrity();
            trainingAnswer(new AnswerSelection(fixed.sessionId(), fixed.questionId(), fixed.answerId(),
                    fixed.answerNumber(), fixed.answerCondition(), fixed.fragments()));
            trainingQuestion(fixed);
            if (fixed.feedbackTaskId() != null) trainingEvaluation(fixed);
        }

        private void trainingEvaluation(AiDtos.TrainingDraftInput fixed) {
            String taskId = fixed.feedbackTaskId();
            uuid(taskId);
            var saved = evaluations.get(taskId);
            if (saved == null) {
                int remaining = remainingVersions();
                if (remaining == 0) throw limit();
                var rows = mapper.evaluationSource(owner, taskId, remaining + 1);
                countBindings(rows);
                if (rows.size() != 1) throw integrity();
                var row = rows.getFirst();
                if (row == null || !owner.equals(row.ownerId()) || !taskId.equals(row.taskId())
                        || !AiDtos.Purpose.ANSWER_EVALUATION.name().equals(row.purpose())
                        || !AiDtos.Status.SUCCEEDED.name().equals(row.taskStatus())
                        || !owner.equals(row.bindingOwnerId()) || !taskId.equals(row.bindingTaskId())
                        || !fixed.sessionId().equals(row.sessionId()) || !fixed.questionId().equals(row.questionId())
                        || !fixed.answerId().equals(row.answerId()) || row.attemptCount() == null
                        || row.attemptCount() < 1 || row.attemptCount() > 3
                        || !owner.equals(row.attemptOwnerId()) || !taskId.equals(row.attemptTaskId())
                        || !Objects.equals(row.currentAttemptId(), row.attemptId()) || row.attemptNumber() == null
                        || !row.attemptNumber().equals(row.attemptCount())
                        || !AiDtos.Status.SUCCEEDED.name().equals(row.attemptStatus())
                        || !owner.equals(row.sessionOwnerId()) || !row.sessionId().equals(row.actualSessionId())) throw integrity();
                uuid(row.currentAttemptId());
                var manifest = evaluationManifest(row);
                var input = originalEvaluationInput(fixed, row);
                if (!input.equals(manifest.evaluation())) throw integrity();
                var report = evaluationReport(row.candidateJson(), input);
                // 整份报告都已参与来源核验，未选观察的引用也计入共享原始绑定预算。
                for (var observation : report.observations()) countBindings(observation.citations());
                // 确切个人参考可能刚加载了题库节点，写入评价缓存前再次核剩余额度。
                if (remainingVersions() == 0) throw limit();
                saved = new SavedEvaluation(input, report);
                evaluations.put(taskId, saved);
            }
            var input = saved.input();
            var report = saved.report();
            // 同报告可被不同整理任务选用；每次仍核其完整选择，且保持用户选择顺序。
            if (!fixed.sessionId().equals(input.sessionId()) || !fixed.questionId().equals(input.questionId())
                    || !fixed.answerId().equals(input.answerId()) || fixed.answerNumber() != input.answerNumber()
                    || !fixed.answerCondition().equals(input.answerCondition()) || !fixed.question().equals(input.question())
                    || fixed.feedbackLevel() != report.level() || !report.limitations().equals(fixed.feedbackLimitations())
                    || fixed.feedback() == null || fixed.feedback().isEmpty() || fixed.feedback().size() > 5) throw integrity();
            var selected = new HashSet<String>();
            for (var observation : fixed.feedback()) {
                if (observation == null || !selected.add(observation.id())
                        || !report.observations().contains(observation)) throw integrity();
            }
        }

        private AiDtos.EvaluationInput originalEvaluationInput(AiDtos.TrainingDraftInput fixed, EvaluationSourceRow row) {
            String feedbackTiming;
            try {
                TrainingDtos.Mode.valueOf(row.sessionMode());
                if (row.sessionConfigJson() == null) throw integrity();
                var config = json.readValue(row.sessionConfigJson(), TrainingDtos.SessionConfig.class);
                if (config == null || config.feedbackTiming() == null) throw integrity();
                feedbackTiming = config.feedbackTiming().name();
            } catch (RuntimeException exception) {
                throw new IllegalStateException("知识整理原评价的固定会话条件不一致", exception);
            }
            var savedAnswer = answers.get(fixed.answerId());
            var savedQuestion = trainingQuestions.get(fixed.questionId());
            if (savedAnswer == null || savedQuestion == null) throw integrity();
            var answer = savedAnswer.content();
            var question = savedQuestion.snapshot().content();
            var originalAnswer = new AiDtos.FollowUpAnswer(answer.text(), answer.diagram().nodes().stream()
                    .map(node -> new AiDtos.FollowUpNode(node.id(), node.label(), node.description())).toList(),
                    answer.diagram().edges().stream()
                            .map(edge -> new AiDtos.FollowUpEdge(edge.id(), edge.source(), edge.target(), edge.label())).toList());
            var originalQuestion = new AiDtos.FollowUpQuestion(question.title(), question.prompt(), question.conditions(),
                    question.technology(), question.scenario(), question.type());
            var source = savedQuestion.row();
            var standard = source.sourceQuestionId() == null ? null
                    : bankStandard(new AiDtos.SourceLink(source.sourceQuestionId(), source.sourceVersionId()));
            return new AiDtos.EvaluationInput(row.sessionId(), row.questionId(), row.answerId(),
                    savedAnswer.row().answerNumber(), row.sessionMode(), feedbackTiming, savedAnswer.row().answerCondition(),
                    originalQuestion, originalAnswer, standard);
        }

        private AiDtos.EvaluationStandard bankStandard(AiDtos.SourceLink link) {
            var known = bankStandards.get(link);
            if (known != null) return known;
            bankVersion(link);
            // 参考属于已计数的确切题库版本；只有显式选了评价才读取，不借根主问或当前版。
            var rows = mapper.bankStandard(owner, link.itemId(), link.versionId(), remainingVersions() + 1);
            if (rows == null || rows.size() != 1) throw integrity();
            var row = rows.getFirst();
            if (row == null || !owner.equals(row.ownerId()) || !owner.equals(row.itemOwnerId())
                    || !link.itemId().equals(row.questionId()) || !link.itemId().equals(row.actualQuestionId())
                    || !link.versionId().equals(row.versionId())
                    || !QuestionDtos.StandardStatus.PERSONAL_UNREVIEWED.name().equals(row.standardStatus())
                    || row.referenceAnswer() == null) throw integrity();
            List<QuestionDtos.Checkpoint> checkpoints;
            try {
                if (row.checkpointsJson() == null
                        || row.checkpointsJson().getBytes(StandardCharsets.UTF_8).length > MAX_PREPARATION_BYTES) throw integrity();
                var tree = json.readTree(row.checkpointsJson());
                if (tree == null || !tree.isArray() || tree.size() > 30) throw integrity();
                var parsed = new ArrayList<QuestionDtos.Checkpoint>();
                var identifiers = new HashSet<String>();
                for (var item : tree) {
                    fields(item, Set.of("id", "text"));
                    if (!item.get("id").isTextual() || !item.get("text").isTextual()) throw integrity();
                    var checkpoint = json.treeToValue(item, QuestionDtos.Checkpoint.class);
                    uuid(checkpoint.id());
                    if (!identifiers.add(checkpoint.id())) throw integrity();
                    parsed.add(checkpoint);
                }
                checkpoints = List.copyOf(parsed);
            } catch (RuntimeException exception) {
                throw new IllegalStateException("知识整理原评价的确切个人检查点结构不一致", exception);
            }
            var standard = new AiDtos.EvaluationStandard(link.itemId(), link.versionId(), row.standardStatus(),
                    row.referenceAnswer(), checkpoints);
            bankStandards.put(link, standard);
            return standard;
        }

        private void trainingQuestion(AiDtos.TrainingDraftInput fixed) {
            var saved = loadTrainingQuestion(fixed.questionId());
            var row = saved.row();
            var content = saved.snapshot().content();
            var projected = new AiDtos.FollowUpQuestion(content.title(), content.prompt(), content.conditions(),
                    content.technology(), content.scenario(), content.type());
            // 每个任务都核其真正外发的题面投影；同题缓存只复用固定原文，不替另一任务背书。
            if (!fixed.sessionId().equals(row.sessionId()) || !fixed.questionId().equals(row.questionId())
                    || !Objects.equals(fixed.sourceQuestionId(), row.sourceQuestionId())
                    || !Objects.equals(fixed.sourceVersionId(), row.sourceVersionId())
                    || !projected.equals(fixed.question())) throw integrity();
        }

        private SavedQuestion loadTrainingQuestion(String questionId) {
            uuid(questionId);
            var saved = trainingQuestions.get(questionId);
            if (saved == null) {
                int remaining = remainingVersions();
                if (remaining == 0) throw limit();
                var rows = mapper.trainingQuestion(owner, questionId, remaining + 1);
                if (rows == null || rows.size() != 1) throw integrity();
                var row = rows.getFirst();
                if (row == null || !owner.equals(row.ownerId()) || !questionId.equals(row.questionId())
                        || !owner.equals(row.sessionOwnerId()) || !Objects.equals(row.sessionId(), row.actualSessionId())
                        || row.title() == null || row.stage() == null || row.questionKind() == null
                        || row.positionIndex() == null || row.positionIndex() < 0 || row.positionIndex() > 59) throw integrity();
                uuid(row.sessionId());
                boolean hasBank = row.sourceQuestionId() != null;
                if (hasBank) {
                    uuid(row.sourceQuestionId()); uuid(row.sourceVersionId());
                } else if (row.sourceVersionId() != null) throw integrity();
                boolean hasParent = row.rootQuestionId() != null || row.parentQuestionId() != null
                        || row.parentAnswerId() != null || row.aiTaskId() != null;
                switch (row.questionKind()) {
                    case "MAIN" -> { if (!hasBank || hasParent) throw integrity(); }
                    case "REPLAY" -> { if (hasParent) throw integrity(); }
                    case "FOLLOW_UP" -> {
                        if (hasBank) throw integrity();
                        uuid(row.rootQuestionId()); uuid(row.parentQuestionId()); uuid(row.parentAnswerId()); uuid(row.aiTaskId());
                        if (row.questionId().equals(row.rootQuestionId()) || row.questionId().equals(row.parentQuestionId())) {
                            throw integrity();
                        }
                    }
                    default -> throw integrity();
                }
                saved = new SavedQuestion(row, questionSnapshot(row));
                trainingQuestions.put(questionId, saved);
                questionPending.addLast(questionId);
            }
            return saved;
        }

        private TrainingDtos.QuestionSnapshot questionSnapshot(TrainingQuestionRow row) {
            try {
                if (row.snapshotJson() == null
                        || row.snapshotJson().getBytes(StandardCharsets.UTF_8).length > MAX_ANSWER_BYTES) throw integrity();
                var tree = json.readTree(row.snapshotJson());
                var snapshot = json.treeToValue(tree, TrainingDtos.QuestionSnapshot.class);
                if (snapshot == null || !tree.has("sourceQuestionId") || !tree.has("sourceVersionId")
                        || !tree.has("number") || !tree.get("number").isIntegralNumber()
                        || !tree.get("number").canConvertToInt() || snapshot.number() < 0
                        || snapshot.warnings() == null || snapshot.warnings().stream().anyMatch(Objects::isNull)
                        || !Objects.equals(row.sourceQuestionId(), snapshot.sourceQuestionId())
                        || !Objects.equals(row.sourceVersionId(), snapshot.sourceVersionId())
                        || !row.title().equals(snapshot.title()) || !row.stage().equals(snapshot.stage())) throw integrity();
                questionPrompt(snapshot.content());
                if (!row.title().equals(snapshot.content().title())) throw integrity();
                if (row.sourceQuestionId() == null
                        ? snapshot.number() != 0 || !snapshot.content().knowledgeLinks().isEmpty()
                        : snapshot.number() < 1) throw integrity();
                return snapshot;
            } catch (RuntimeException exception) {
                throw new IllegalStateException("知识整理原训练的固定题面结构不一致", exception);
            }
        }

        private void questionReferences(SavedQuestion saved) {
            var row = saved.row();
            var expected = new HashSet<AiDtos.SourceLink>();
            for (var link : saved.snapshot().content().knowledgeLinks()) {
                expected.add(new AiDtos.SourceLink(link.itemId(), link.versionId()));
            }
            var rows = mapper.trainingQuestionKnowledgeBindings(owner, row.questionId(), MAX_BINDINGS - bindingCount + 1);
            countBindings(rows);
            var registered = new HashSet<AiDtos.SourceLink>();
            for (var binding : rows) {
                if (binding == null || !owner.equals(binding.ownerId()) || !row.sessionId().equals(binding.sessionId())
                        || !row.questionId().equals(binding.questionId())) throw integrity();
                uuid(binding.itemId()); uuid(binding.versionId());
                if (!registered.add(new AiDtos.SourceLink(binding.itemId(), binding.versionId()))) throw integrity();
            }
            if (!expected.equals(registered)) throw integrity();
            if (row.sourceQuestionId() == null) {
                if ("FOLLOW_UP".equals(row.questionKind())) followUpReferences(saved);
                // 个人回忆或历史题面 REPLAY 的生成来源另行核验，不据空登记推断为空。
                return;
            }
            var actual = bankVersion(new AiDtos.SourceLink(row.sourceQuestionId(), row.sourceVersionId()));
            if (saved.snapshot().number() != actual.row().versionNumber()
                    || !row.title().equals(actual.row().title())
                    || !saved.snapshot().content().equals(actual.content())) throw integrity();
        }

        private void followUpReferences(SavedQuestion saved) {
            var child = saved.row();
            var known = followUpQuestions.get(child.aiTaskId());
            if (known != null) {
                if (!child.questionId().equals(known)) throw integrity();
                return;
            }
            int remaining = remainingVersions();
            if (remaining == 0) throw limit();
            var rows = mapper.followUpSource(owner, child.aiTaskId(), remaining + 1);
            countBindings(rows);
            if (rows.size() != 1) throw integrity();
            var row = rows.getFirst();
            if (row == null || !owner.equals(row.ownerId()) || !child.aiTaskId().equals(row.taskId())
                    || !AiDtos.Purpose.FOLLOW_UP.name().equals(row.purpose())
                    || !AiDtos.Status.SUCCEEDED.name().equals(row.taskStatus())
                    || !owner.equals(row.bindingOwnerId()) || !row.taskId().equals(row.bindingTaskId())
                    || !child.sessionId().equals(row.sessionId()) || !child.questionId().equals(row.generatedQuestionId())
                    || !child.rootQuestionId().equals(row.rootQuestionId())
                    || !child.parentQuestionId().equals(row.parentQuestionId())
                    || !child.parentAnswerId().equals(row.parentAnswerId())
                    || row.generation() == null || !historicalGeneration(row.generation())
                    || row.attemptCount() == null || row.attemptCount() < 1 || row.attemptCount() > 3
                    || !owner.equals(row.attemptOwnerId()) || !row.taskId().equals(row.attemptTaskId())
                    || !Objects.equals(row.currentAttemptId(), row.attemptId()) || row.attemptNumber() == null
                    || !row.attemptNumber().equals(row.attemptCount())
                    || !AiDtos.Status.SUCCEEDED.name().equals(row.attemptStatus())
                    || !owner.equals(row.sessionOwnerId()) || !row.sessionId().equals(row.actualSessionId())) throw integrity();
            uuid(row.currentAttemptId());
            var manifest = followUpManifest(row);
            var input = manifest.training();
            var candidate = followUpCandidate(row.candidateJson(), input);
            // 先占原成功任务节点，父题与答案只排队；循环不能借同步递归绕过共享额度。
            followUpQuestions.put(row.taskId(), child.questionId());
            countBindings(manifest.contextChecks());
            countBindings(input.context());
            countBindings(candidate.citations());
            var root = loadTrainingQuestion(child.rootQuestionId());
            var parent = loadTrainingQuestion(child.parentQuestionId());
            var answer = loadTrainingAnswer(child.parentAnswerId());
            if (!child.sessionId().equals(root.row().sessionId()) || !"MAIN".equals(root.row().questionKind())
                    || !child.sessionId().equals(parent.row().sessionId())
                    || parent.row().positionIndex() >= child.positionIndex()
                    || root.row().positionIndex() > parent.row().positionIndex()
                    || !child.sessionId().equals(answer.row().sessionId())
                    || !parent.row().questionId().equals(answer.row().questionId())) throw integrity();
            if ("MAIN".equals(parent.row().questionKind())) {
                if (!root.row().questionId().equals(parent.row().questionId())) throw integrity();
            } else if ("FOLLOW_UP".equals(parent.row().questionKind())) {
                if (!root.row().questionId().equals(parent.row().rootQuestionId())) throw integrity();
            } else throw integrity();
            // 每条追问边的位置严格递增，结合共享题目队列拒绝环；不以今天的会话代次判旧结果。
            var original = parent.snapshot().content();
            var submitted = answer.content();
            var expectedInput = new AiDtos.FollowUpInput(row.sessionId(), row.parentQuestionId(), row.parentAnswerId(),
                    row.rootQuestionId(), row.generation(), answer.row().answerNumber(), answer.row().answerCondition(),
                    new AiDtos.FollowUpQuestion(original.title(), original.prompt(), original.conditions(),
                            original.technology(), original.scenario(), original.type()),
                    new AiDtos.FollowUpAnswer(submitted.text(), submitted.diagram().nodes().stream()
                            .map(node -> new AiDtos.FollowUpNode(node.id(), node.label(), node.description())).toList(),
                            submitted.diagram().edges().stream()
                                    .map(edge -> new AiDtos.FollowUpEdge(edge.id(), edge.source(), edge.target(), edge.label())).toList()),
                    input.context());
            if (!expectedInput.equals(input)) throw integrity();
            var prompt = new QuestionDtos.QuestionPrompt(candidate.title(), candidate.prompt(), candidate.conditions(),
                    original.technology(), original.scenario(), original.type(), original.difficulty(), original.dimensions(),
                    List.of(), List.of(), List.of(), new QuestionDtos.Source("", "", ""));
            var expectedSnapshot = new TrainingDtos.QuestionSnapshot(null, null, candidate.title(), 0, parent.row().stage(), prompt,
                    List.of("AI 追问；结构与原答引用位置已核对，题意与技术质量仍需人工判断。"));
            if (!expectedSnapshot.equals(saved.snapshot())) throw integrity();
            followUpBackground(row, manifest);
        }

        private void followUpBackground(FollowUpSourceRow row, AiDtos.Manifest manifest) {
            var snapshots = followUpContexts(row.sessionContextJson());
            var checks = manifest.contextChecks();
            if (checks.size() != snapshots.size()) throw integrity();
            var expected = new ArrayList<AiDtos.FollowUpContext>();
            var used = new ArrayList<SavedContext>();
            for (int index = 0; index < snapshots.size(); index++) {
                var stored = snapshots.get(index);
                var snapshot = stored.projected();
                var check = checks.get(index);
                if (check == null || check.kind() == null || !snapshot.kind().equals(check.kind().name())
                        || !snapshot.itemId().equals(check.itemId()) || !snapshot.versionId().equals(check.versionId())
                        || !snapshot.title().equals(check.title()) || snapshot.number() != check.number() || check.state() == null
                        || !historicalGeneration(check.sourceGeneration()) || !historicalGeneration(check.projectGeneration())
                        || check.kind() != TrainingDtos.ContextKind.FACT && check.projectGeneration() != null
                        || check.state() != PreparationDtos.SourceState.UNAVAILABLE && check.sourceGeneration() == null) {
                    throw integrity();
                }
                if (check.kind() != TrainingDtos.ContextKind.FACT
                        && check.state() != PreparationDtos.SourceState.CURRENT
                        && check.state() != PreparationDtos.SourceState.SUPERSEDED
                        && check.state() != PreparationDtos.SourceState.ARCHIVED
                        && check.state() != PreparationDtos.SourceState.UNAVAILABLE) throw integrity();
                if (check.state() == PreparationDtos.SourceState.UNAVAILABLE
                        && (check.projectGeneration() != null
                        || check.kind() != TrainingDtos.ContextKind.FACT && check.sourceGeneration() != null)) throw integrity();
                // 这三种历史状态只会在父项目存在时产生；撤回与归档可先于缺父判断。
                if (check.kind() == TrainingDtos.ContextKind.FACT && check.projectGeneration() == null
                        && (check.state() == PreparationDtos.SourceState.SUPERSEDED
                        || check.state() == PreparationDtos.SourceState.PROJECT_ARCHIVED
                        || check.state() == PreparationDtos.SourceState.UNVERIFIED)) throw integrity();
                if (check.state() == PreparationDtos.SourceState.CURRENT) {
                    if (check.sourceGeneration() == null
                            || check.kind() == TrainingDtos.ContextKind.FACT && check.projectGeneration() == null) throw integrity();
                    expected.add(snapshot);
                    used.add(stored);
                }
            }
            if (!expected.equals(manifest.training().context())) throw integrity();
            // 仅原清单真正外发的背景进入祖先；排除项的保存镜像不扩大材料集合。
            for (var stored : used) {
                var context = stored.projected();
                var prepared = preparationVersion(new AiDtos.SourceLink(context.itemId(), context.versionId()), context.kind());
                if (context.number() != prepared.row().versionNumber() || !context.title().equals(prepared.row().title())
                        || !context.body().equals(prepared.content().body())
                        || !stored.snapshot().content().equals(json.readTree(prepared.row().contentJson()))) throw integrity();
            }
        }

        private SavedBankVersion bankVersion(AiDtos.SourceLink link) {
            uuid(link.itemId()); uuid(link.versionId());
            var known = bankVersions.get(link);
            if (known != null) return known;
            int remaining = remainingVersions();
            if (remaining == 0) throw limit();
            var rows = mapper.bankVersion(owner, link.itemId(), link.versionId(), remaining + 1);
            if (rows == null || rows.size() != 1) throw integrity();
            var row = rows.getFirst();
            if (row == null || !owner.equals(row.ownerId()) || !owner.equals(row.itemOwnerId())
                    || !link.itemId().equals(row.questionId()) || !link.itemId().equals(row.actualQuestionId())
                    || !link.versionId().equals(row.versionId()) || !knowledgeStatus(row.itemStatus())
                    || row.versionNumber() == null || row.versionNumber() < 1 || row.title() == null) throw integrity();
            var saved = new SavedBankVersion(row, bankPrompt(row));
            bankVersions.put(link, saved);
            bankPending.addLast(link);
            return saved;
        }

        private QuestionDtos.QuestionPrompt bankPrompt(BankVersionRow row) {
            try {
                if (row.promptJson() == null
                        || row.promptJson().getBytes(StandardCharsets.UTF_8).length > MAX_ANSWER_BYTES) throw integrity();
                var content = json.readValue(row.promptJson(), QuestionDtos.QuestionPrompt.class);
                questionPrompt(content);
                if (!row.title().equals(content.title())) throw integrity();
                return content;
            } catch (RuntimeException exception) {
                throw new IllegalStateException("知识整理原题库确切版本的题面结构不一致", exception);
            }
        }

        private void bankKnowledgeBindings(SavedBankVersion saved) {
            var version = saved.row();
            var fixed = saved.content().knowledgeLinks();
            var rows = mapper.bankKnowledgeBindings(owner, version.versionId(), MAX_BINDINGS - bindingCount + 1);
            countBindings(rows);
            if (rows.size() != fixed.size()) throw integrity();
            for (int index = 0; index < fixed.size(); index++) {
                var row = rows.get(index);
                var link = fixed.get(index);
                if (row == null || !owner.equals(row.ownerId()) || !version.questionId().equals(row.questionId())
                        || !version.versionId().equals(row.versionId()) || row.positionIndex() == null
                        || row.positionIndex() != index || !link.itemId().equals(row.knowledgeItemId())
                        || !link.versionId().equals(row.knowledgeVersionId())) throw integrity();
                version(new AiDtos.SourceLink(link.itemId(), link.versionId()));
            }
        }

        private void trainingAnswer(AnswerSelection fixed) {
            uuid(fixed.sessionId()); uuid(fixed.questionId()); uuid(fixed.answerId());
            var saved = loadTrainingAnswer(fixed.answerId());
            var row = saved.row();
            if (!fixed.sessionId().equals(row.sessionId()) || !fixed.questionId().equals(row.questionId())
                    || fixed.answerNumber() != row.answerNumber() || !fixed.answerCondition().equals(row.answerCondition())) {
                throw integrity();
            }
            for (var fragment : fixed.fragments()) {
                var original = answerText(saved.content(), fragment);
                if (original == null || fragment.start() < 0 || fragment.end() > original.length()
                        || splitSurrogate(original, fragment.start()) || splitSurrogate(original, fragment.end())
                        || !fragment.quote().equals(original.substring(fragment.start(), fragment.end()))) throw integrity();
            }
        }

        private SavedAnswer loadTrainingAnswer(String answerId) {
            uuid(answerId);
            var saved = answers.get(answerId);
            if (saved == null) {
                int remaining = remainingVersions();
                if (remaining == 0) throw limit();
                var rows = mapper.trainingAnswer(owner, answerId, remaining + 1);
                if (rows == null || rows.size() != 1) throw integrity();
                var row = rows.getFirst();
                if (row == null || !owner.equals(row.ownerId()) || !answerId.equals(row.answerId())
                        || !owner.equals(row.questionOwnerId()) || !Objects.equals(row.questionId(), row.actualQuestionId())
                        || !Objects.equals(row.sessionId(), row.questionSessionId()) || !owner.equals(row.sessionOwnerId())
                        || !Objects.equals(row.sessionId(), row.actualSessionId()) || row.answerNumber() == null
                        || row.answerNumber() < 1 || row.answerNumber() > 100
                        || !List.of("LEARNING", "BEFORE_ASSISTANCE", "ASSISTED").contains(row.answerCondition())) throw integrity();
                uuid(row.sessionId()); uuid(row.questionId());
                saved = new SavedAnswer(row, answerContent(row.answerContentJson()));
                // 先占确切答案节点，再排队展开整图；循环引用和多任务重复答案只展开一次。
                answers.put(answerId, saved);
                answerPending.addLast(answerId);
            }
            return saved;
        }

        private TrainingDtos.AnswerContent answerContent(String contentJson) {
            try {
                if (contentJson == null || contentJson.getBytes(StandardCharsets.UTF_8).length > MAX_ANSWER_BYTES) throw integrity();
                var tree = json.readTree(contentJson);
                var submitted = json.treeToValue(tree, TrainingDtos.AnswerContent.class);
                if (submitted == null || submitted.text() == null || submitted.diagram() == null
                        || submitted.diagram().nodes() == null || submitted.diagram().edges() == null
                        || submitted.diagram().nodes().size() > 80 || submitted.diagram().edges().size() > 160
                        || submitted.diagram().nodes().stream().anyMatch(Objects::isNull)
                        || submitted.diagram().edges().stream().anyMatch(Objects::isNull)) throw integrity();
                var identifiers = new HashSet<String>();
                var nodeIds = new HashSet<String>();
                for (int index = 0; index < submitted.diagram().nodes().size(); index++) {
                    var node = submitted.diagram().nodes().get(index);
                    var raw = tree.get("diagram").get("nodes").get(index);
                    uuid(node.id());
                    if (!identifiers.add(node.id()) || node.label() == null || node.description() == null
                            || !raw.has("x") || !raw.get("x").isNumber() || !raw.has("y") || !raw.get("y").isNumber()
                            || !Double.isFinite(node.x()) || !Double.isFinite(node.y())
                            || Math.abs(node.x()) > 1_000_000 || Math.abs(node.y()) > 1_000_000
                            || !raw.has("reference")) throw integrity();
                    nodeIds.add(node.id());
                    var reference = node.reference();
                    if (reference != null) {
                        if (reference.kind() == null || reference.title() == null || reference.number() < 1) throw integrity();
                        uuid(reference.itemId()); uuid(reference.versionId());
                    }
                }
                for (var edge : submitted.diagram().edges()) {
                    uuid(edge.id()); uuid(edge.source()); uuid(edge.target());
                    if (!identifiers.add(edge.id()) || !nodeIds.contains(edge.source()) || !nodeIds.contains(edge.target())
                            || edge.label() == null) throw integrity();
                }
                var board = submitted.diagram().boardSource();
                if (board != null) {
                    uuid(board.boardId()); uuid(board.importId());
                    if (board.revision() == null || board.revision() < 0 || board.revision() > MAX_REVISION) throw integrity();
                }
                return submitted;
            } catch (RuntimeException exception) {
                throw new IllegalStateException("知识采用中固定答案的原文或图引用结构不一致", exception);
            }
        }

        private void answerReferences(SavedAnswer saved) {
            var references = saved.content().diagram().nodes().stream().map(TrainingDtos.DiagramNode::reference)
                    .filter(Objects::nonNull).toList();
            // 采用了答案片段的任务保守继承整图引用，重复目标仍按原图的每个引用节点计绑定。
            countBindings(references);
            var expectedKnowledge = new HashSet<AiDtos.SourceLink>();
            for (var reference : references) {
                var link = new AiDtos.SourceLink(reference.itemId(), reference.versionId());
                if (reference.kind() == TrainingDtos.LinkKind.KNOWLEDGE) {
                    var actual = version(link);
                    if (reference.number() != actual.versionNumber() || !reference.title().equals(actual.title())) throw integrity();
                    expectedKnowledge.add(link);
                } else {
                    // 答案历史引用是独立角色；可引用未核实或已归档事实，不套原 AI FACT 输入门禁。
                    var actual = loadPreparationVersion(link, reference.kind().name()).row();
                    if (reference.number() != actual.versionNumber() || !reference.title().equals(actual.title())) throw integrity();
                }
            }
            var answer = saved.row();
            var rows = mapper.answerKnowledgeBindings(owner, answer.answerId(), MAX_BINDINGS - bindingCount + 1);
            countBindings(rows);
            var registered = new HashSet<AiDtos.SourceLink>();
            for (var row : rows) {
                if (row == null || !owner.equals(row.ownerId()) || !answer.sessionId().equals(row.sessionId())
                        || !answer.questionId().equals(row.questionId()) || !answer.answerId().equals(row.answerId())) throw integrity();
                uuid(row.itemId()); uuid(row.versionId());
                if (!registered.add(new AiDtos.SourceLink(row.itemId(), row.versionId()))) throw integrity();
            }
            if (!expectedKnowledge.equals(registered)) throw integrity();
        }

        private void materialBindings(String taskId, AiDtos.Manifest manifest) {
            var rows = mapper.materialBindings(owner, taskId, MAX_BINDINGS - bindingCount + 1);
            countBindings(rows);
            var fixedSources = manifest.materialDraftSources() == null ? List.<MaterialDtos.AiExcerpt>of()
                    : manifest.materialDraftSources();
            if (rows.size() != fixedSources.size()) throw integrity();
            for (int index = 0; index < rows.size(); index++) {
                materialBinding(taskId, rows.get(index), fixedSources.get(index), manifest.sources().size() + index + 1, null);
            }
        }

        private void materialBinding(String taskId, MaterialBinding row, MaterialDtos.AiExcerpt fixed,
                                     int ordinal, String kind) {
            if (row == null || !owner.equals(row.ownerId()) || !taskId.equals(row.taskId())
                    || !Objects.equals(kind, row.kind())
                    || !owner.equals(row.excerptOwnerId()) || !owner.equals(row.attachmentOwnerId())
                    || !fixed.excerptId().equals(row.excerptId()) || !fixed.excerptId().equals(row.actualExcerptId())
                    || !fixed.attachmentId().equals(row.attachmentId()) || !fixed.attachmentId().equals(row.actualAttachmentId())
                    || !fixed.sha256().equals(row.attachmentSha256()) || row.ordinal() == null
                    || row.ordinal() != ordinal) throw integrity();
            var actual = new MaterialDtos.AiExcerpt(row.actualExcerptId(), row.actualAttachmentId(), row.fileName(),
                    row.actualSha256(), row.origin(), row.runId(), row.segmentId(), row.locator(), row.text(),
                    row.startOffset(), row.endOffset());
            if (!fixed.equals(actual)) throw integrity();
            excerptIds.add(fixed.excerptId());
            if (excerptIds.size() > MAX_EXCERPTS) throw limit();
        }
    }

    private static String answerText(TrainingDtos.AnswerContent content, AiDtos.TrainingDraftFragment fragment) {
        return switch (fragment.field()) {
            case ANSWER_TEXT -> content.text();
            case ANSWER_NODE_LABEL, ANSWER_NODE_DESCRIPTION -> {
                var found = content.diagram().nodes().stream()
                        .filter(node -> fragment.elementId().equals(node.id())).toList();
                if (found.size() != 1) throw integrity();
                var node = found.getFirst();
                yield fragment.field() == AiDtos.TrainingDraftField.ANSWER_NODE_LABEL ? node.label() : node.description();
            }
            case ANSWER_EDGE_LABEL -> {
                var found = content.diagram().edges().stream()
                        .filter(edge -> fragment.elementId().equals(edge.id())).toList();
                if (found.size() != 1) throw integrity();
                yield found.getFirst().label();
            }
            default -> throw integrity();
        };
    }

    private static boolean splitSurrogate(String value, int index) {
        return index > 0 && index < value.length() && Character.isHighSurrogate(value.charAt(index - 1))
                && Character.isLowSurrogate(value.charAt(index));
    }

    private static void questionPrompt(QuestionDtos.QuestionPrompt content) {
        if (content == null || content.title() == null || content.prompt() == null || content.conditions() == null
                || content.technology() == null || content.scenario() == null || content.type() == null
                || content.difficulty() != null && (content.difficulty() < 1 || content.difficulty() > 5)
                || content.dimensions() == null || content.dimensions().stream().anyMatch(Objects::isNull)
                || new HashSet<>(content.dimensions()).size() != content.dimensions().size()
                || content.topicIds() == null || content.tags() == null || content.tags().stream().anyMatch(Objects::isNull)
                || content.knowledgeLinks() == null || content.knowledgeLinks().size() > 20 || content.source() == null
                || content.source().title() == null || content.source().url() == null || content.source().note() == null) throw integrity();
        ids(content.topicIds(), 0, 20);
        var references = new HashSet<QuestionDtos.KnowledgeLink>();
        for (var link : content.knowledgeLinks()) {
            if (link == null || !references.add(link)) throw integrity();
            uuid(link.itemId()); uuid(link.versionId());
        }
    }

    private PreparedContent preparationContent(PreparationVersionRow row) {
        return preparationContent(row.itemKind(), row.title(), row.contentJson());
    }

    private PreparedContent preparationContent(String kind, String expectedTitle, String contentJson) {
        try {
            if (contentJson == null
                    || contentJson.getBytes(StandardCharsets.UTF_8).length > MAX_PREPARATION_BYTES) throw integrity();
            var projected = json.createObjectNode();
            List<String> excerpts = List.of(), facts = List.of();
            List<PreparationDtos.VersionLink> links = List.of();
            PreparationDtos.FactContent factContent = null;
            PreparationDtos.ExpressionType expressionType = null;
            String title;
            switch (PreparationDtos.Kind.valueOf(kind)) {
                case PROJECT -> {
                    var value = json.readValue(contentJson, PreparationDtos.ProjectContent.class);
                    title = value.title();
                    projected.put("overview", text(value.overview(), 20000, true));
                    projected.put("technology", text(value.technology(), 2000, true));
                    excerpts = preparationExcerptIds(value.source());
                }
                case FACT -> {
                    var value = json.readValue(contentJson, PreparationDtos.FactContent.class);
                    factContent = value;
                    title = value.title();
                    projected.put("scenario", text(value.scenario(), 10000, true));
                    projected.put("problem", text(value.problem(), 10000, true));
                    projected.put("action", text(value.action(), 10000, true));
                    projected.put("result", text(value.result(), 10000, true));
                    projected.put("technology", text(value.technology(), 2000, true));
                    if (value.responsibility() == null) throw integrity();
                    projected.put("responsibility", value.responsibility().name());
                    excerpts = preparationExcerptIds(value.source());
                    tags(value.tags());
                }
                case EXPRESSION -> {
                    var value = json.readValue(contentJson, PreparationDtos.ExpressionContent.class);
                    title = value.title();
                    if (value.type() == null) throw integrity();
                    expressionType = value.type();
                    projected.put("type", value.type().name());
                    projected.put("body", text(value.body(), 50000, false));
                    facts = ids(value.factVersionIds(), 1, 50);
                    tags(value.tags());
                }
                case TARGET -> {
                    var value = json.readValue(contentJson, StoredTargetContent.class);
                    title = value.title();
                    projected.put("company", text(value.company(), 200, true));
                    projected.put("jd", text(value.jd(), 50000, true));
                    text(value.sourceUrl(), 2000, true);
                    if (value.capturedOn() != null) LocalDate.parse(value.capturedOn());
                    if (value.weeklyMinutes() != null && (value.weeklyMinutes() < 0 || value.weeklyMinutes() > 10080)) {
                        throw integrity();
                    }
                    if (value.requirements() == null || value.requirements().size() > 50) throw integrity();
                    var requirementIds = new HashSet<String>();
                    var requirements = projected.putArray("requirements");
                    for (var requirement : value.requirements()) {
                        if (requirement == null) throw integrity();
                        uuid(requirement.id());
                        if (!requirementIds.add(requirement.id()) || requirement.priority() == null
                                || requirement.priority() < 1 || requirement.priority() > 5
                                || requirement.dimensions() == null || requirement.dimensions().isEmpty()
                                || requirement.dimensions().size() > 6
                                || new HashSet<>(requirement.dimensions()).size() != requirement.dimensions().size()) {
                            throw integrity();
                        }
                        var item = requirements.addObject();
                        item.put("text", text(requirement.text(), 2000, false));
                        var dimensions = item.putArray("dimensions");
                        for (String dimension : requirement.dimensions()) dimensions.add(text(dimension, 40, false));
                        item.put("priority", requirement.priority());
                    }
                    if (value.links() == null || value.links().size() > 100
                            || new HashSet<>(value.links()).size() != value.links().size()) throw integrity();
                    for (var link : value.links()) {
                        if (link == null || link.kind() == null) throw integrity();
                        uuid(link.itemId()); uuid(link.versionId());
                    }
                    links = List.copyOf(value.links());
                }
                default -> throw integrity();
            }
            if (expectedTitle == null || !expectedTitle.equals(text(title, 200, false))) throw integrity();
            return new PreparedContent(json.writeValueAsString(projected), excerpts, facts, links, factContent, expressionType);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("知识采用中准备版本的固定正文或依据结构不一致", exception);
        }
    }

    private static List<String> preparationExcerptIds(PreparationDtos.Source source) {
        if (source == null) throw integrity();
        text(source.title(), 200, true); text(source.url(), 2000, true); text(source.note(), 5000, true);
        return ids(source.excerptIds(), 0, 20);
    }

    private static List<String> ids(List<String> values, int minimum, int maximum) {
        if (values == null || values.size() < minimum || values.size() > maximum
                || new HashSet<>(values).size() != values.size()) throw integrity();
        for (String value : values) uuid(value);
        return List.copyOf(values);
    }

    private static void tags(List<String> values) {
        if (values == null || values.size() > 20) throw integrity();
        var seen = new HashSet<String>();
        for (String value : values) if (!seen.add(text(value, 40, false).strip())) throw integrity();
    }

    private static String text(String value, int maximum, boolean emptyAllowed) {
        if (value == null || value.length() > maximum || (!emptyAllowed && value.isBlank())) throw integrity();
        return value;
    }

    private record PreparedContent(String body, List<String> excerptIds, List<String> factVersionIds,
                                   List<PreparationDtos.VersionLink> links, PreparationDtos.FactContent fact,
                                   PreparationDtos.ExpressionType expressionType) {}
    private record PreparedVersion(PreparationVersionRow row, PreparedContent content, List<AiDtos.SourceLink> facts) {}
    private record SavedAnswer(TrainingAnswerRow row, TrainingDtos.AnswerContent content) {}
    private record SavedQuestion(TrainingQuestionRow row, TrainingDtos.QuestionSnapshot snapshot) {}
    private record SavedBankVersion(BankVersionRow row, QuestionDtos.QuestionPrompt content) {}
    private record SavedEvaluation(AiDtos.EvaluationInput input, AiDtos.EvaluationReport report) {}
    private record SavedContext(TrainingDtos.ContextSnapshot snapshot, AiDtos.FollowUpContext projected) {}
    private record AnswerSelection(String sessionId, String questionId, String answerId, int answerNumber,
                                   String answerCondition, List<AiDtos.TrainingDraftFragment> fragments) {}
    private record StoredTargetContent(String title, String company, String jd, String sourceUrl,
                                       String capturedOn, Integer weeklyMinutes,
                                       List<PreparationDtos.Requirement> requirements,
                                       List<PreparationDtos.VersionLink> links) {}

    private AiDtos.Manifest followUpManifest(FollowUpSourceRow row) {
        try {
            if (row.manifestJson() == null || row.manifestHash() == null
                    || row.manifestJson().getBytes(StandardCharsets.UTF_8).length > MAX_MANIFEST_BYTES
                    || !row.manifestHash().matches("[0-9a-f]{64}")) throw integrity();
            var tree = json.readTree(row.manifestJson());
            var training = tree.get("training");
            fields(training, Set.of("sessionId", "questionId", "answerId", "rootQuestionId", "generation",
                    "answerNumber", "answerCondition", "question", "answer", "context"));
            if (!training.get("generation").isIntegralNumber() || !training.get("generation").canConvertToLong()
                    || !training.get("answerNumber").isIntegralNumber() || !training.get("answerNumber").canConvertToInt()) {
                throw integrity();
            }
            var checks = tree.get("contextChecks");
            var contexts = training.get("context");
            if (checks == null || !checks.isArray() || checks.size() > 12
                    || !contexts.isArray() || contexts.size() > 12) throw integrity();
            for (var check : checks) {
                fields(check, Set.of("kind", "itemId", "versionId", "title", "number", "state",
                        "sourceGeneration", "projectGeneration"));
                for (String name : List.of("kind", "itemId", "versionId", "title", "state")) {
                    if (!check.get(name).isTextual()) throw integrity();
                }
                if (!check.get("number").isIntegralNumber() || !check.get("number").canConvertToInt()) throw integrity();
                for (String name : List.of("sourceGeneration", "projectGeneration")) {
                    var value = check.get(name);
                    if (!value.isNull() && (!value.isIntegralNumber() || !value.canConvertToLong())) throw integrity();
                }
            }
            for (var context : contexts) {
                fields(context, Set.of("kind", "itemId", "versionId", "number", "title", "body"));
                for (String name : List.of("kind", "itemId", "versionId", "title", "body")) {
                    if (!context.get(name).isTextual()) throw integrity();
                }
                if (!context.get("number").isIntegralNumber() || !context.get("number").canConvertToInt()) throw integrity();
            }
            var manifest = json.treeToValue(tree, AiDtos.Manifest.class);
            // v2 明确保存全部检查及实际外发子序列；没有原字节见证的旧提示词不能推定同一生成依据。
            if (manifest == null || manifest.purpose() != AiDtos.Purpose.FOLLOW_UP
                    || !"".equals(manifest.instruction()) || manifest.sources() == null || !manifest.sources().isEmpty()
                    || !"training-follow-up-2".equals(manifest.promptVersion()) || manifest.configuration() == null
                    || manifest.training() == null || manifest.contextChecks() == null || manifest.evaluation() != null
                    || manifest.materialQa() != null || manifest.preparationChecks() != null
                    || manifest.materialDraftSources() != null || manifest.expressionType() != null
                    || manifest.preparationPlan() != null || manifest.trainingDraft() != null
                    || manifest.expressionChecks() != null || manifest.comparisonInput() != null
                    || manifest.comparisonChecks() != null || manifest.reviewDraft() != null
                    || manifest.cardDraftType() != null || manifest.factDraft() != null
                    || manifest.relationEvidence() != null || manifest.relationAiFeedback() != null
                    || manifest.sourceQa() != null || manifest.materialChecks() != null
                    || manifest.knowledgeMaterialChecks() != null
                    || manifest.training().answer() == null || manifest.training().answer().text() == null
                    || manifest.training().context().stream().anyMatch(Objects::isNull)
                    || manifest.contextChecks().stream().anyMatch(Objects::isNull)
                    || !row.manifestHash().equals(digest.hash(manifest))) throw integrity();
            return manifest;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("知识整理原追问的固定清单或摘要不一致", exception);
        }
    }

    private AiDtos.FollowUpCandidate followUpCandidate(String value, AiDtos.FollowUpInput input) {
        try {
            if (value == null || value.getBytes(StandardCharsets.UTF_8).length > MAX_REPORT_BYTES) throw integrity();
            var tree = json.readTree(value);
            fields(tree, Set.of("title", "prompt", "conditions", "citations", "validationNote"));
            for (String name : List.of("title", "prompt", "conditions", "validationNote")) {
                if (!tree.get(name).isTextual()) throw integrity();
            }
            var citations = tree.get("citations");
            if (!citations.isArray() || citations.isEmpty() || citations.size() > 3) throw integrity();
            for (var citation : citations) {
                fields(citation, Set.of("answerId", "start", "end", "quote"));
                if (!citation.get("answerId").isTextual() || !citation.get("quote").isTextual()
                        || !citation.get("start").isIntegralNumber() || !citation.get("start").canConvertToInt()
                        || !citation.get("end").isIntegralNumber() || !citation.get("end").canConvertToInt()) throw integrity();
            }
            var candidate = json.treeToValue(tree, AiDtos.FollowUpCandidate.class);
            if (!FOLLOW_UP_VALIDATION_NOTE.equals(candidate.validationNote())) throw integrity();
            reportText(candidate.title(), 200, true);
            reportText(candidate.prompt(), 4000, true);
            reportText(candidate.conditions(), 2000, false);
            var positions = new HashSet<String>();
            String answer = input.answer().text();
            for (var citation : candidate.citations()) {
                reportText(citation.quote(), 500, true);
                if (!input.answerId().equals(citation.answerId()) || citation.start() < 0
                        || citation.end() <= citation.start() || citation.end() > answer.length()
                        || splitSurrogate(answer, citation.start()) || splitSurrogate(answer, citation.end())
                        || !citation.quote().equals(answer.substring(citation.start(), citation.end()))
                        || !positions.add(citation.start() + ":" + citation.end())) throw integrity();
            }
            return candidate;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("知识整理原追问的完整候选或原答定位不一致", exception);
        }
    }

    private List<SavedContext> followUpContexts(String value) {
        try {
            if (value == null || value.getBytes(StandardCharsets.UTF_8).length > MAX_CONTEXT_BYTES) throw integrity();
            var tree = json.readTree(value);
            if (!tree.isArray() || tree.size() > 12) throw integrity();
            var snapshots = new ArrayList<SavedContext>();
            var items = new HashSet<String>();
            int targets = 0, projects = 0, facts = 0;
            for (var item : tree) {
                fields(item, Set.of("kind", "itemId", "versionId", "title", "number", "content"));
                for (String name : List.of("kind", "itemId", "versionId", "title")) {
                    if (!item.get(name).isTextual()) throw integrity();
                }
                if (!item.get("number").isIntegralNumber() || !item.get("number").canConvertToInt()
                        || !item.get("content").isObject()) throw integrity();
                var snapshot = json.treeToValue(item, TrainingDtos.ContextSnapshot.class);
                if (snapshot.kind() == null || snapshot.number() < 1 || !items.add(snapshot.itemId())) throw integrity();
                uuid(snapshot.itemId()); uuid(snapshot.versionId());
                switch (snapshot.kind()) {
                    case TARGET -> targets++;
                    case PROJECT -> projects++;
                    case FACT -> facts++;
                }
                var content = preparationContent(snapshot.kind().name(), snapshot.title(),
                        json.writeValueAsString(snapshot.content()));
                snapshots.add(new SavedContext(snapshot, new AiDtos.FollowUpContext(snapshot.kind().name(),
                        snapshot.itemId(), snapshot.versionId(), snapshot.number(), snapshot.title(), content.body())));
            }
            if (targets > 1 || projects > 1 || facts > 10) throw integrity();
            return List.copyOf(snapshots);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("知识整理原追问的训练背景快照不一致", exception);
        }
    }

    private static boolean historicalGeneration(Long value) {
        return value == null || value >= 0 && value <= MAX_REVISION;
    }

    private AiDtos.Manifest evaluationManifest(EvaluationSourceRow row) {
        try {
            if (row.manifestJson() == null || row.manifestHash() == null
                    || row.manifestJson().getBytes(StandardCharsets.UTF_8).length > MAX_MANIFEST_BYTES
                    || !row.manifestHash().matches("[0-9a-f]{64}")) throw integrity();
            var manifest = json.readValue(row.manifestJson(), AiDtos.Manifest.class);
            if (manifest == null || manifest.purpose() != AiDtos.Purpose.ANSWER_EVALUATION
                    || !"".equals(manifest.instruction()) || manifest.sources() == null || !manifest.sources().isEmpty()
                    || !"training-evaluation-1".equals(manifest.promptVersion()) || manifest.configuration() == null
                    || manifest.evaluation() == null || manifest.training() != null || manifest.contextChecks() != null
                    || manifest.materialQa() != null || manifest.preparationChecks() != null
                    || manifest.materialDraftSources() != null || manifest.expressionType() != null
                    || manifest.preparationPlan() != null || manifest.trainingDraft() != null
                    || manifest.expressionChecks() != null || manifest.comparisonInput() != null
                    || manifest.comparisonChecks() != null || manifest.reviewDraft() != null
                    || manifest.cardDraftType() != null || manifest.factDraft() != null
                    || manifest.relationEvidence() != null || manifest.relationAiFeedback() != null
                    || manifest.sourceQa() != null || manifest.materialChecks() != null
                    || manifest.knowledgeMaterialChecks() != null
                    || !row.manifestHash().equals(digest.hash(manifest))) throw integrity();
            return manifest;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("知识整理原评价的固定清单或摘要不一致", exception);
        }
    }

    private AiDtos.EvaluationReport evaluationReport(String value, AiDtos.EvaluationInput input) {
        try {
            if (value == null || value.getBytes(StandardCharsets.UTF_8).length > MAX_REPORT_BYTES) throw integrity();
            var tree = json.readTree(value);
            fields(tree, Set.of("level", "summary", "limitations", "observations", "validationNote"));
            for (String name : List.of("level", "summary", "limitations", "validationNote")) {
                if (!tree.get(name).isTextual()) throw integrity();
            }
            var observations = tree.get("observations");
            if (!observations.isArray() || observations.isEmpty() || observations.size() > 12) throw integrity();
            for (var item : observations) {
                fields(item, Set.of("id", "dimension", "coverage", "observation", "condition", "basis", "limitation", "suggestion", "citations"));
                for (String name : List.of("id", "dimension", "coverage", "observation", "condition", "basis", "limitation", "suggestion")) {
                    if (!item.get(name).isTextual()) throw integrity();
                }
                var citations = item.get("citations");
                if (!citations.isArray() || citations.size() > 6) throw integrity();
                for (var citation : citations) {
                    fields(citation, Set.of("kind", "elementId", "start", "end", "quote"));
                    if (!citation.get("kind").isTextual() || !citation.get("quote").isTextual()
                            || !(citation.get("elementId").isNull() || citation.get("elementId").isTextual())
                            || !citation.get("start").isIntegralNumber() || !citation.get("start").canConvertToInt()
                            || !citation.get("end").isIntegralNumber() || !citation.get("end").canConvertToInt()) throw integrity();
                }
            }
            var report = json.treeToValue(tree, AiDtos.EvaluationReport.class);
            if (report.level() != AiDtos.EvaluationLevel.AI_UNREVIEWED
                    || !EVALUATION_VALIDATION_NOTE.equals(report.validationNote())) throw integrity();
            int total = reportText(report.summary(), 2000, true) + reportText(report.limitations(), 2000, true);
            for (int index = 0; index < report.observations().size(); index++) {
                var item = report.observations().get(index);
                if (!("f" + (index + 1)).equals(item.id()) || item.dimension() == null || item.coverage() == null) throw integrity();
                total += reportText(item.observation(), 2000, true) + reportText(item.condition(), 1000, false)
                        + reportText(item.basis(), 1000, true) + reportText(item.limitation(), 1000, true)
                        + reportText(item.suggestion(), 1000, true);
                var positions = new HashSet<String>();
                boolean answerEvidence = false;
                for (var citation : item.citations()) {
                    if (citation.kind() == null || citation.start() < 0 || citation.end() <= citation.start()) throw integrity();
                    boolean requiresElement = switch (citation.kind()) {
                        case ANSWER_NODE_LABEL, ANSWER_NODE_DESCRIPTION, ANSWER_EDGE_LABEL, CHECKPOINT -> true;
                        case ANSWER_TEXT, QUESTION_PROMPT, QUESTION_CONDITIONS, REFERENCE -> false;
                    };
                    if (requiresElement) uuid(citation.elementId());
                    else if (citation.elementId() != null) throw integrity();
                    total += reportText(citation.quote(), 500, true);
                    String original = evaluationText(input, citation);
                    if (original == null || citation.end() > original.length()
                            || splitSurrogate(original, citation.start()) || splitSurrogate(original, citation.end())
                            || !citation.quote().equals(original.substring(citation.start(), citation.end()))
                            || !positions.add(citation.kind() + ":" + citation.elementId() + ":" + citation.start() + ":" + citation.end())) {
                        throw integrity();
                    }
                    answerEvidence |= switch (citation.kind()) {
                        case ANSWER_TEXT, ANSWER_NODE_LABEL, ANSWER_NODE_DESCRIPTION, ANSWER_EDGE_LABEL -> true;
                        case QUESTION_PROMPT, QUESTION_CONDITIONS, REFERENCE, CHECKPOINT -> false;
                    };
                }
                if (item.coverage() == AiDtos.EvaluationCoverage.MENTIONED && !answerEvidence
                        || item.coverage() == AiDtos.EvaluationCoverage.NOT_MENTIONED
                        && (answerEvidence || item.citations().isEmpty()) || total > 24000) throw integrity();
            }
            return report;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("知识整理原评价的完整报告或原文定位不一致", exception);
        }
    }

    private static String evaluationText(AiDtos.EvaluationInput input, AiDtos.EvaluationCitation citation) {
        return switch (citation.kind()) {
            case ANSWER_TEXT -> input.answer().text();
            case QUESTION_PROMPT -> input.question().prompt();
            case QUESTION_CONDITIONS -> input.question().conditions();
            case REFERENCE -> input.standard() == null ? null : input.standard().referenceAnswer();
            case ANSWER_NODE_LABEL, ANSWER_NODE_DESCRIPTION -> input.answer().nodes().stream()
                    .filter(node -> citation.elementId().equals(node.id()))
                    .map(node -> citation.kind() == AiDtos.EvaluationEvidenceKind.ANSWER_NODE_LABEL ? node.label() : node.description())
                    .findFirst().orElse(null);
            case ANSWER_EDGE_LABEL -> input.answer().edges().stream()
                    .filter(edge -> citation.elementId().equals(edge.id())).map(AiDtos.FollowUpEdge::label).findFirst().orElse(null);
            case CHECKPOINT -> input.standard() == null ? null : input.standard().checkpoints().stream()
                    .filter(checkpoint -> citation.elementId().equals(checkpoint.id())).map(QuestionDtos.Checkpoint::text).findFirst().orElse(null);
        };
    }

    private static int reportText(String value, int maximum, boolean required) {
        // 沿用报告生成时的码点规则，历史 NUL 不清洗，代理对仍须完整。
        if (value == null || required && value.isBlank()
                || value.codePoints().anyMatch(point -> point >= 0xD800 && point <= 0xDFFF)) throw integrity();
        int count = value.codePointCount(0, value.length());
        if (count > maximum) throw integrity();
        return count;
    }

    private static void fields(JsonNode value, Set<String> names) {
        if (value == null || !value.isObject() || value.size() != names.size()
                || names.stream().anyMatch(name -> !value.has(name))) throw integrity();
    }

    private AiDtos.Manifest manifest(AdoptionRow row, AiDtos.Purpose purpose) {
        try {
            if (row.manifestJson() == null || row.manifestHash() == null
                    || row.manifestJson().getBytes(StandardCharsets.UTF_8).length > MAX_MANIFEST_BYTES
                    || !row.manifestHash().matches("[0-9a-f]{64}")) throw integrity();
            var manifest = json.readValue(row.manifestJson(), AiDtos.Manifest.class);
            boolean valid = switch (purpose) {
                case KNOWLEDGE_DRAFT, QUESTION_DRAFT -> AiSources.validDraftInput(manifest);
                case EXPRESSION_DRAFT -> AiSources.validExpressionInput(manifest);
                case FACT_DRAFT -> AiSources.validFactDraftInput(manifest);
                default -> false;
            };
            if (manifest == null || manifest.purpose() != purpose || !valid || !row.manifestHash().equals(digest.hash(manifest))) {
                throw integrity();
            }
            checks(manifest.materialChecks(), 1, 5);
            if (manifest.materialChecks() != null) {
                var directSources = purpose == AiDtos.Purpose.FACT_DRAFT ? manifest.factDraft().materials()
                        : manifest.materialDraftSources();
                var directIds = directSources == null ? List.<String>of()
                        : directSources.stream().map(MaterialDtos.AiExcerpt::excerptId).sorted().toList();
                if (!manifest.materialChecks().stream().map(MaterialLifecycleDtos.Check::excerptId).toList().equals(directIds)) {
                    throw integrity();
                }
            }
            checks(manifest.knowledgeMaterialChecks(), 0, MAX_EXCERPTS);
            if (manifest.knowledgeMaterialChecks() != null
                    && manifest.sources().stream().noneMatch(source -> AiSources.knowledgeKind(source.kind()))) throw integrity();
            return manifest;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("知识采用材料用途祖先的固定清单或摘要不一致", exception);
        }
    }

    private static void checks(List<MaterialLifecycleDtos.Check> checks, int minimum, int maximum) {
        if (checks == null) return;
        if (checks.size() < minimum || checks.size() > maximum) throw integrity();
        String previous = null;
        for (var check : checks) {
            if (check == null || check.revision() == null || check.revision() < 0 || check.revision() > MAX_REVISION) {
                throw integrity();
            }
            uuid(check.excerptId());
            if (previous != null && previous.compareTo(check.excerptId()) >= 0) throw integrity();
            previous = check.excerptId();
        }
    }

    private static boolean knowledgeStatus(String status) {
        return "ACTIVE".equals(status) || "ARCHIVED".equals(status);
    }

    private static void uuid(String value) {
        try {
            if (value == null || !UUID.fromString(value).toString().equals(value)) throw integrity();
        } catch (IllegalArgumentException exception) {
            throw integrity();
        }
    }

    private static IllegalStateException integrity() {
        return new IllegalStateException("知识采用材料用途祖先的固定身份或绑定不一致");
    }

    private static ApiException limit() {
        return new ApiException(409, "KNOWLEDGE_MATERIAL_LINEAGE_LIMIT", "知识采用材料用途祖先超过本次核对上限，请缩小知识来源范围后重试。");
    }
}
