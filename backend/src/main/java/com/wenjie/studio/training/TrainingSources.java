package com.wenjie.studio.training;

import com.wenjie.studio.ai.QuestionKnowledgeOrigins;
import com.wenjie.studio.bank.CollectionDtos;
import com.wenjie.studio.bank.CollectionService;
import com.wenjie.studio.bank.QuestionDtos;
import com.wenjie.studio.bank.QuestionService;
import com.wenjie.studio.board.BoardDtos;
import com.wenjie.studio.board.BoardService;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.knowledge.KnowledgeDtos;
import com.wenjie.studio.knowledge.KnowledgeService;
import com.wenjie.studio.lifecycle.KnowledgeMaterialLineage;
import com.wenjie.studio.lifecycle.KnowledgeRevalidationQuery;
import com.wenjie.studio.material.MaterialDependencyImpactService;
import com.wenjie.studio.material.MaterialDependencyImpactDtos;
import com.wenjie.studio.prepare.PreparationDtos;
import com.wenjie.studio.prepare.PreparationService;
import com.wenjie.studio.prepare.PreparationSources;
import com.wenjie.studio.review.RecollectionDtos;
import com.wenjie.studio.review.RecollectionSources;
import com.wenjie.studio.training.TrainingDtos.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.wenjie.studio.training.TrainingInput.*;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class TrainingSources {
    private static final String REPRACTICE_STAGE = "独立复练";
    private static final String REPLAY_EXPOSURE_WARNING = "同题独立复练：此题已有历史提交，曝光计数不代表首次见题。";
    private static final String LEGACY_REPLAY_WARNING = "沿用历史 AI 追问题面；本题没有题库参考，题意与技术质量仍需人工判断。";
    private final QuestionService questions;
    private final QuestionKnowledgeOrigins questionOrigins;
    private final KnowledgeMaterialLineage questionLineage;
    private final KnowledgeRevalidationQuery knowledgeRevalidation;
    private final MaterialDependencyImpactService materialDependencies;
    private final CollectionService collections;
    private final PreparationService preparation;
    private final PreparationSources preparationSources;
    private final KnowledgeService knowledge;
    private final BoardService boards;
    private final TrainingMapper training;
    private final RecollectionSources recollections;
    private final JsonMapper json;

    public TrainingSources(QuestionService questions, CollectionService collections, PreparationService preparation,
                           KnowledgeService knowledge, BoardService boards, TrainingMapper training, JsonMapper json,
                           PreparationSources preparationSources, RecollectionSources recollections,
                           QuestionKnowledgeOrigins questionOrigins, KnowledgeMaterialLineage questionLineage,
                           KnowledgeRevalidationQuery knowledgeRevalidation, MaterialDependencyImpactService materialDependencies) {
        this.questions = questions;
        this.questionOrigins = questionOrigins;
        this.questionLineage = questionLineage;
        this.knowledgeRevalidation = knowledgeRevalidation;
        this.materialDependencies = materialDependencies;
        this.collections = collections;
        this.preparation = preparation;
        this.preparationSources = preparationSources;
        this.knowledge = knowledge;
        this.boards = boards;
        this.training = training;
        this.recollections = recollections;
        this.json = json;
    }

    public SourceSnapshot snapshot(String owner, CreateSession request) {
        writeTransaction(); uuid(owner);
        CreateSession checked = TrainingInput.create(request);
        List<ContextSnapshot> context = checked.contextLinks().stream().map(link -> context(owner, link)).toList();
        bounded(context, MAX_CONTEXT_BYTES, "所选上下文超过 128 KiB，请减少选中的材料。");
        return new SourceSnapshot(context, questionSnapshots(owner, checked.questions(), checked.collectionId(), checked.collectionVersionId()));
    }

    public SourceSnapshot repracticeSnapshot(String owner, String baselineSessionId, String baselineQuestionId,
                                             QuestionSelection variant, List<ContextLink> contextLinks) {
        writeTransaction(); uuid(owner);
        QuestionSnapshot baseline = baselineQuestion(owner, baselineSessionId, baselineQuestionId);
        List<ContextSnapshot> context = repracticeContexts(owner, contextLinks);
        QuestionSnapshot question = variant == null ? replayQuestion(owner, baselineSessionId, baselineQuestionId, baseline)
                : variantQuestion(owner, variant);
        return new SourceSnapshot(context, List.of(question));
    }

    public void revalidateRepractice(String owner, SessionConfig config, List<ContextSnapshot> context,
                                    QuestionSnapshot snapshot, String baselineSessionId, String baselineQuestionId,
                                    boolean sameQuestion) {
        writeTransaction(); uuid(owner); required(config); required(context); required(snapshot);
        QuestionSnapshot baseline = baselineQuestion(owner, baselineSessionId, baselineQuestionId);
        revalidateContext(owner, context);
        QuestionSnapshot current = sameQuestion ? replayQuestion(owner, baselineSessionId, baselineQuestionId, baseline)
                : variantQuestion(owner, new QuestionSelection(snapshot.sourceQuestionId(), snapshot.sourceVersionId(), snapshot.stage()));
        if (sameQuestion && snapshot.sourceQuestionId() == null
                && List.of(REPLAY_EXPOSURE_WARNING, LEGACY_REPLAY_WARNING).equals(snapshot.warnings())) {
            // 兼容旧草稿的来源提示措辞；题面、版本和其余字段仍须逐项一致。
            current = new QuestionSnapshot(current.sourceQuestionId(), current.sourceVersionId(), current.title(), current.number(),
                    current.stage(), current.content(), snapshot.warnings());
        }
        if (!snapshot.equals(current)) throw changed("复练题面或确切来源已变化，请重新创建复练。");
    }

    public SourceSnapshot recollectionRepracticeSnapshot(String owner, String id, String versionId, long revision,
                                                         QuestionSelection variant, List<ContextLink> contextLinks) {
        writeTransaction(); uuid(owner);
        RecollectionDtos.Version version = recollections.requireCurrent(owner, id, versionId, revision);
        QuestionSnapshot question = variant == null ? recollectionQuestion(version) : recollectionVariant(owner, variant);
        return new SourceSnapshot(repracticeContexts(owner, contextLinks), List.of(question));
    }

    public RecollectionDtos.Version revalidateRecollectionRepractice(String owner, List<ContextSnapshot> context, QuestionSnapshot snapshot,
                                                                      String id, String versionId, long revision, boolean sameQuestion) {
        writeTransaction(); uuid(owner); required(context); required(snapshot);
        RecollectionDtos.Version version = recollections.requireCurrent(owner, id, versionId, revision);
        revalidateContext(owner, context);
        QuestionSnapshot current = sameQuestion ? recollectionQuestion(version)
                : recollectionVariant(owner, new QuestionSelection(snapshot.sourceQuestionId(), snapshot.sourceVersionId(), snapshot.stage()));
        if (!snapshot.equals(current)) throw changed("回忆复练的题面或来源已变化，请重新创建复练。");
        return version;
    }

    private QuestionSnapshot recollectionQuestion(RecollectionDtos.Version version) {
        RecollectionDtos.Content content = version.content();
        // 训练安全查询只保存题面投影；回忆片段、反馈和知识缺口不得混入题干。
        QuestionDtos.QuestionPrompt prompt = new QuestionDtos.QuestionPrompt(content.title(), content.question(), content.conditions(),
                "", "", content.type(), null, List.of(QuestionDtos.Dimension.OTHER), List.of(), List.of(), List.of(), new QuestionDtos.Source("", "", ""));
        QuestionSnapshot snapshot = new QuestionSnapshot(null, null, content.title(), 0, REPRACTICE_STAGE, prompt,
                List.of("同题补练：题面来自个人回忆，已接触过题面；题库曝光计数不能表示首次见题。",
                        "题面可能存在回忆偏差；本次独立作答，原回忆片段与反馈在训练结束后查看。"));
        bounded(snapshot, MAX_ANSWER_BYTES, "回忆复练题面超过 192 KiB。");
        return snapshot;
    }

    private QuestionSnapshot recollectionVariant(String owner, QuestionSelection variant) {
        QuestionSnapshot question = variantQuestion(owner, variant);
        return new QuestionSnapshot(question.sourceQuestionId(), question.sourceVersionId(), question.title(), question.number(),
                question.stage(), question.content(), List.of("由个人回忆发起的变式补练；曝光计数只统计所选题库题目的既往记录。"));
    }

    public List<ContextCheck> contextChecks(String owner, List<ContextSnapshot> context) {
        uuid(owner); required(context);
        if (context.size() > 12) throw invalid("训练固定背景最多 12 项。");
        return context.stream().map(source -> {
            required(source); required(source.kind());
            var checked = preparationSources.check(owner, PreparationDtos.Kind.valueOf(source.kind().name()),
                    source.itemId(), source.versionId());
            // 标题与版本号仍来自原快照；动态状态不能把当前正文冒充为当时训练条件。
            return new ContextCheck(source.kind(), source.itemId(), source.versionId(), source.title(), source.number(),
                    checked.state(), checked.sourceGeneration(), checked.projectGeneration());
        }).toList();
    }

    public void revalidate(String owner, SessionConfig config, List<ContextSnapshot> context, List<QuestionSnapshot> snapshots) {
        writeTransaction(); uuid(owner); required(config); required(context); required(snapshots);
        revalidateContext(owner, context);
        List<QuestionSelection> selected = snapshots.stream().map(question ->
                new QuestionSelection(question.sourceQuestionId(), question.sourceVersionId(), question.stage())).toList();
        List<QuestionSnapshot> current = questionSnapshots(owner, selected, config.collectionId(), config.collectionVersionId());
        // 旧题提示包含预览时的当前版本号，开始时再改版也必须重新确认。
        if (!snapshots.equals(current)) throw changed("题目来源或版本提示已变化，请重新创建预览。");
    }

    private void revalidateContext(String owner, List<ContextSnapshot> context) {
        for (ContextSnapshot existing : context) {
            ContextSnapshot current = context(owner, new ContextLink(existing.kind(), existing.itemId(), existing.versionId()));
            if (!existing.equals(current)) throw changed("训练上下文已变化，请重新创建预览。");
        }
    }

    private List<ContextSnapshot> repracticeContexts(String owner, List<ContextLink> links) {
        required(links);
        if (links.size() > 12) throw invalid("复练背景最多 12 项。");
        Set<String> ids = new HashSet<>();
        int targets = 0, projects = 0, facts = 0;
        for (ContextLink link : links) {
            required(link); required(link.kind()); uuid(link.itemId()); uuid(link.versionId());
            if (!ids.add(link.itemId())) throw invalid("复练背景不能重复选择同一对象。");
            switch (link.kind()) {
                case TARGET -> targets++;
                case PROJECT -> projects++;
                case FACT -> facts++;
            }
        }
        if (targets > 1 || projects > 1 || facts > 10) throw invalid("复练背景最多一个目标、一个项目和十条已确认事实。");
        List<ContextSnapshot> context = links.stream().map(link -> context(owner, link)).toList();
        bounded(context, MAX_CONTEXT_BYTES, "复练背景超过 128 KiB，请减少选中的材料。");
        return context;
    }

    private QuestionSnapshot baselineQuestion(String owner, String sessionId, String questionId) {
        uuid(sessionId); uuid(questionId);
        var session = training.session(owner, sessionId);
        var question = training.question(owner, sessionId, questionId);
        if (session == null || question == null) throw unavailable("原训练题目不存在或不可访问。");
        if (!Status.ENDED.name().equals(session.status())) throw changed("请先结束原训练，再从确切提交创建复练。");
        if (question.answerCount() < 1) throw unavailable("原训练题目尚无正式提交，不能作为复练基线。");
        QuestionSnapshot snapshot = json.readValue(question.snapshotJson(), QuestionSnapshot.class);
        boolean hasBankSource = question.sourceQuestionId() != null;
        if (!Set.of("MAIN", "FOLLOW_UP", "REPLAY").contains(question.kind())
                || ("MAIN".equals(question.kind()) && !hasBankSource)
                || ("FOLLOW_UP".equals(question.kind()) && hasBankSource)
                || (hasBankSource != (question.sourceVersionId() != null))
                || snapshot == null || snapshot.content() == null
                || !Objects.equals(snapshot.sourceQuestionId(), question.sourceQuestionId())
                || !Objects.equals(snapshot.sourceVersionId(), question.sourceVersionId())
                || !Objects.equals(snapshot.title(), question.title())
                || !Objects.equals(snapshot.content().title(), question.title())) {
            throw unavailable("原训练题面与固定来源不一致。");
        }
        return snapshot;
    }

    private QuestionSnapshot replayQuestion(String owner, String sessionId, String questionId, QuestionSnapshot baseline) {
        boolean bankSource = baseline.sourceQuestionId() != null;
        if (bankSource) {
            uuid(baseline.sourceQuestionId()); uuid(baseline.sourceVersionId());
            try {
                var version = questions.version(owner, baseline.sourceQuestionId(), baseline.sourceVersionId());
                // 同题复练核对原版本而非当前版本；归档或改版不能偷换当时的题面及个人参考。
                if (version.number() != baseline.number() || !version.content().equals(baseline.content())) {
                    throw changed("原训练题面与确切题库版本不一致。");
                }
                requireQuestionKnowledgeCurrent(owner, baseline.sourceQuestionId(), baseline.sourceVersionId(), version.number());
            } catch (ApiException exception) { throw sourceFailure(exception); }
        } else {
            var lineage = questionLineage.questionSources(owner, sessionId, questionId);
            for (var source : lineage.bankSources()) {
                try {
                    var version = questions.version(owner, source.itemId(), source.versionId());
                    // 追问和跨会话复制不能借空题库标识跳过原确切题库版的知识资格。
                    requireQuestionKnowledgeCurrent(owner, source.itemId(), source.versionId(), version.number());
                } catch (ApiException exception) { throw sourceFailure(exception); }
            }
            for (var reference : lineage.answerKnowledgeSources()) {
                var source = reference.source();
                try {
                    var item = knowledge.detail(owner, source.itemId());
                    // 原提交后的上游更正或处理仍使旧图引用失效；新复练时间不能清洗基线。
                    if (item.status() != KnowledgeDtos.Status.ACTIVE || !source.versionId().equals(item.currentVersionId())
                            || knowledgeRevalidation.reviewRequired(owner, source.itemId(), source.versionId(), reference.frozenAt())) {
                        throw changed("原题面生成所用答案图的知识来源已变化，请选择来源适用的题目或另行生成。");
                    }
                } catch (ApiException exception) { throw sourceFailure(exception); }
            }
            for (var reference : lineage.adoptedKnowledgeSources()) {
                var source = reference.source();
                try {
                    var item = knowledge.detail(owner, source.itemId());
                    // 采用目标的后来版本不能刷新原生成任务的知识基线；每个任务分别检查。
                    if (item.status() != KnowledgeDtos.Status.ACTIVE || !source.versionId().equals(item.currentVersionId())
                            || knowledgeRevalidation.reviewRequired(owner, source.itemId(), source.versionId(), reference.frozenAt())) {
                        throw changed("原题面来源的实际采用任务所用知识已变化，请选择来源适用的题目或另行生成。");
                    }
                } catch (ApiException exception) { throw sourceFailure(exception); }
            }
            for (var reference : lineage.adoptedPreparationSources()) {
                var check = reference.check();
                // 只核原版本状态与代次；表达还须核完整依据，恢复为可用也不能清洗旧任务基线。
                boolean current = check.kind() == PreparationDtos.Kind.EXPRESSION
                        ? reference.expressionCheck().equals(preparationSources.checkExpression(owner, check.itemId(), check.versionId()))
                        : check.equals(preparationSources.check(owner, check.kind(), check.itemId(), check.versionId()));
                if (!current) throw changed("原题面来源的实际采用任务所用准备资料已变化，请选择来源适用的题目或另行生成。");
            }
            for (var reference : lineage.followUpPreparationSources()) {
                var check = reference.check();
                // 题面继承原追问真正使用的背景代次，不能借跨会话复制恢复已失效的依据。
                if (!check.equals(preparationSources.check(owner, check.kind(), check.itemId(), check.versionId()))) {
                    throw changed("原追问题面实际使用的准备背景已变化，请选择来源适用的题目或另行生成。");
                }
            }
        }
        List<String> warnings = List.of(REPLAY_EXPOSURE_WARNING,
                bankSource ? "沿用原训练的确切题库版本及未审个人参考；题库改版或停用不替换本次题面。"
                        : "沿用历史正式提交的固定题面；本题没有题库参考，题面来源与技术质量仍需人工判断。");
        QuestionSnapshot snapshot = new QuestionSnapshot(baseline.sourceQuestionId(), baseline.sourceVersionId(),
                baseline.title(), baseline.number(), REPRACTICE_STAGE, baseline.content(), warnings);
        bounded(snapshot, MAX_ANSWER_BYTES, "复练题面超过 192 KiB。");
        return snapshot;
    }

    private QuestionSnapshot variantQuestion(String owner, QuestionSelection variant) {
        required(variant); uuid(variant.sourceQuestionId()); uuid(variant.sourceVersionId());
        if (!REPRACTICE_STAGE.equals(variant.stage())) throw invalid("变式题阶段必须为独立复练。");
        QuestionSnapshot current = questionSnapshots(owner, List.of(variant), null, null).getFirst();
        return new QuestionSnapshot(current.sourceQuestionId(), current.sourceVersionId(), current.title(), current.number(),
                current.stage(), current.content(), List.of("由已有原答发起的变式复练；曝光计数只统计所选题库题目的既往记录。"));
    }

    public AnswerContent answer(String owner, String sessionId, AnswerInput request, boolean materialAccess,
                                List<AnswerContent> preserved) {
        writeTransaction(); uuid(owner); uuid(sessionId);
        AnswerInput checked = TrainingInput.answer(request, false);
        bounded(checked, MAX_ANSWER_BYTES, "单题图文超过 192 KiB，请缩减内容。");
        Map<DiagramLink, DiagramReference> savedReferences = new HashMap<>();
        Set<BoardSource> savedBoards = new HashSet<>();
        // 这些内容必须由调用方读取同题草稿或提交，绝不能来自请求中的“已保存”声明。
        for (AnswerContent previous : preserved == null ? List.<AnswerContent>of() : preserved) {
            if (previous == null || previous.diagram() == null) continue;
            if (previous.diagram().boardSource() != null) savedBoards.add(previous.diagram().boardSource());
            for (DiagramNode node : previous.diagram().nodes()) {
                if (node.reference() == null) continue;
                DiagramReference reference = node.reference();
                savedReferences.putIfAbsent(new DiagramLink(reference.kind(), reference.itemId(), reference.versionId()), reference);
            }
        }
        BoardSource source = checked.diagram().boardSource();
        if (source != null && !savedBoards.contains(source)) {
            materials(materialAccess);
            // 来源身份必须对应本次训练实际导入的记录；图内容可继续编辑，不以原图逐字相等限制编辑。
            var imported = training.boardImport(owner, sessionId, source.importId());
            if (imported == null || !source.boardId().equals(imported.boardId())
                    || source.revision() != imported.boardRevision()) {
                throw unavailable("白板导入记录不存在或与来源修订不一致，请在本次训练中重新导入。");
            }
        }
        List<DiagramNode> nodes = new ArrayList<>();
        for (DiagramNodeInput node : checked.diagram().nodes()) {
            DiagramReference reference = null;
            if (node.link() != null) {
                reference = savedReferences.get(node.link());
                if (reference == null) {
                    materials(materialAccess);
                    reference = diagramReference(owner, node.link());
                }
            }
            nodes.add(new DiagramNode(node.id(), node.label(), node.description(), node.x(), node.y(), reference));
        }
        AnswerContent result = new AnswerContent(checked.text(), new Diagram(List.copyOf(nodes), checked.diagram().edges(), source));
        bounded(result, MAX_ANSWER_BYTES, "单题图文和引用快照超过 192 KiB，请缩减内容。");
        return result;
    }

    public BoardImportCandidate importBoard(String owner, String boardId, long expectedRevision) {
        writeTransaction(); uuid(owner); uuid(boardId); revision(expectedRevision);
        BoardDtos.HistoryPage history;
        try { history = boards.history(owner, boardId, null, 1); }
        catch (ApiException exception) { throw sourceFailure(exception); }
        if (history.items().isEmpty()) throw unavailable("白板尚无可用的固定修订。");
        if (history.items().getFirst().revision() != expectedRevision) throw changed("白板已在其他页面修改，请重新读取后导入。");
        BoardDtos.Detail snapshot = boardSnapshot(owner, boardId, expectedRevision);
        // 训练图解目前只接受知识引用，不能把题目或附件的标识当成知识标识继续解析。
        if (snapshot.nodes().stream().anyMatch(node -> node.type() == BoardDtos.NodeType.REFERENCE
                && node.referenceKind() != BoardDtos.ReferenceKind.KNOWLEDGE)
                || snapshot.references().stream().anyMatch(reference ->
                reference.referenceKind() != BoardDtos.ReferenceKind.KNOWLEDGE)) {
            throw new ApiException(409, "BOARD_TRAINING_REFERENCE_UNSUPPORTED",
                    "当前训练图解仅支持知识引用，请先移除白板中的题目或附件引用后再导入。");
        }
        Map<String, BoardDtos.Reference> references = new HashMap<>();
        for (BoardDtos.Reference reference : snapshot.references()) references.put(reference.itemId(), reference);
        List<DiagramNodeInput> nodes = new ArrayList<>();
        for (BoardDtos.Node node : snapshot.nodes()) {
            if (node.type() == BoardDtos.NodeType.TEXT) {
                nodes.add(new DiagramNodeInput(node.id(), textLabel(node.text()), node.text(), node.x(), node.y(), null));
            } else {
                BoardDtos.Reference reference = references.get(node.itemId());
                if (reference == null) throw unavailable("白板历史知识引用不完整，无法导入。");
                DiagramLink link = new DiagramLink(LinkKind.KNOWLEDGE, reference.itemId(), reference.versionId());
                DiagramReference fixed = diagramReference(owner, link);
                nodes.add(new DiagramNodeInput(node.id(), fixed.title(), "", node.x(), node.y(), link));
            }
        }
        List<DiagramEdge> edges = new ArrayList<>();
        Set<String> directedPairs = new HashSet<>();
        for (BoardDtos.Edge edge : snapshot.edges()) {
            edges.add(new DiagramEdge(edge.id(), edge.source(), edge.target(), edge.label()));
            directedPairs.add(edge.source() + ":" + edge.target());
        }
        // 脑图父子关系以 parentId 保存；导入答案时转为固定连线，避免丢失原有层级。
        for (BoardDtos.Node node : snapshot.nodes()) {
            if (node.parentId() != null && directedPairs.add(node.parentId() + ":" + node.id())) {
                String seed = boardId + ":" + expectedRevision + ":" + node.parentId() + ":" + node.id();
                edges.add(new DiagramEdge(UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString(),
                        node.parentId(), node.id(), ""));
            }
        }
        BoardSource source = new BoardSource(boardId, expectedRevision, UUID.randomUUID().toString());
        DiagramInput diagram = TrainingInput.diagram(new DiagramInput(List.copyOf(nodes), List.copyOf(edges), source));
        BoardImportCandidate candidate = new BoardImportCandidate(boardId, expectedRevision, diagram);
        bounded(candidate, MAX_ANSWER_BYTES, "白板候选图超过 192 KiB，请先减少节点或文字。");
        return candidate;
    }

    private List<QuestionSnapshot> questionSnapshots(String owner, List<QuestionSelection> selected,
                                                      String collectionId, String collectionVersionId) {
        Set<String> collectionReferences = new HashSet<>();
        if (collectionId != null) {
            try {
                CollectionDtos.Collection collection = collections.detail(owner, collectionId);
                if (collection.status() != CollectionDtos.Status.ACTIVE || !collection.currentVersion().id().equals(collectionVersionId)) {
                    throw changed("题单或路线已改版或停用，请重新选择。");
                }
                CollectionDtos.CollectionVersion version = collections.version(owner, collectionId, collectionVersionId);
                for (CollectionDtos.Step step : version.content().steps()) collectionReferences.add(step.questionId() + ":" + step.questionVersionId());
            } catch (ApiException exception) { throw sourceFailure(exception); }
        }
        List<QuestionSnapshot> result = new ArrayList<>();
        for (QuestionSelection selection : selected) {
            if (collectionId != null && !collectionReferences.contains(selection.sourceQuestionId() + ":" + selection.sourceVersionId())) {
                throw unavailable("所选确切题目版本不属于此次题单或路线。");
            }
            try {
                QuestionDtos.Question question = questions.detail(owner, selection.sourceQuestionId());
                QuestionDtos.QuestionVersion version = questions.version(owner, selection.sourceQuestionId(), selection.sourceVersionId());
                if (question.status() != QuestionDtos.Status.ACTIVE) throw changed("所选题目已停用，请重新选择。");
                boolean historical = !question.currentVersion().id().equals(version.id());
                if (historical && collectionId == null) throw changed("题目已改版，请重新选择当前版本。");
                requireQuestionKnowledgeCurrent(owner, question.id(), version.id(), version.number());
                List<String> warnings = historical ? List.of("题单固定引用 v" + version.number()
                        + "，当前题目已发布至 v" + question.currentVersion().number() + "；本次仍使用题单中的确切旧版本。") : List.of();
                QuestionSnapshot snapshot = new QuestionSnapshot(question.id(), version.id(), version.content().title(),
                        version.number(), selection.stage(), version.content(), warnings);
                bounded(snapshot, MAX_ANSWER_BYTES, "单题题干快照超过 192 KiB，请缩减题目内容。");
                result.add(snapshot);
            } catch (ApiException exception) { throw sourceFailure(exception); }
        }
        return List.copyOf(result);
    }

    private void requireQuestionKnowledgeCurrent(String owner, String questionId, String versionId, int number) {
        // 创建及开始新训练时核使用资格；历史题面和输入读取仍保留原固定内容。
        // 完整证明实际材料祖先，但本地准入以目标复核结果为准；不把旧摘录的 AI 撤权当成本地禁用。
        questionLineage.bankExcerptIds(owner, questionId, versionId);
        if (materialDependencies.reviewRequired(owner, MaterialDependencyImpactDtos.Domain.QUESTION, questionId, versionId, null)) {
            throw changed("题目实际采用的材料已更正，请先复核材料影响再创建训练或复练。");
        }
        var references = questions.knowledgeReferences(owner, questionId, versionId).items();
        if (references.stream().anyMatch(reference -> reference.state() != QuestionDtos.ReferenceState.CURRENT)) {
            throw changed("请先复核关联知识并保存适用的题目版本，再创建训练或复练。");
        }
        questionOrigins.requireCurrent(owner, questionId, number);
    }

    private ContextSnapshot context(String owner, ContextLink link) {
        try {
            PreparationDtos.Item item = preparation.detail(owner, link.itemId());
            PreparationDtos.Version version = preparation.version(owner, link.itemId(), link.versionId());
            if (!item.kind().name().equals(link.kind().name())) throw unavailable("训练上下文类型与来源对象不一致。");
            if (item.status() != PreparationDtos.Status.ACTIVE || !item.currentVersion().id().equals(version.id())) {
                throw changed("训练材料已改版或归档，请重新选择当前版本。");
            }
            var checked = preparationSources.check(owner, PreparationDtos.Kind.valueOf(link.kind().name()), item.id(), version.id());
            if (item.reviewRequired() || checked.state() != PreparationDtos.SourceState.CURRENT) {
                throw changed("训练准备资料的事实或材料依据待复核，请先处理来源后重新选择。");
            }
            questionLineage.preparationExcerptIds(owner, checked);
            if (link.kind() == ContextKind.FACT) {
                if (item.verification() != PreparationDtos.Verification.CONFIRMED) throw changed("训练事实尚未确认或已撤回，请重新核实来源。");
                PreparationDtos.Item project = preparation.detail(owner, item.projectId());
                if (project.kind() != PreparationDtos.Kind.PROJECT || project.status() != PreparationDtos.Status.ACTIVE) {
                    throw changed("事实所属项目已不可用，请重新选择。");
                }
            }
            // 只固定显式选中版本的正文，不扩展其组织链接、附件或表达稿。
            return new ContextSnapshot(link.kind(), item.id(), version.id(), version.content().path("title").stringValue(),
                    version.number(), version.content().deepCopy());
        } catch (ApiException exception) { throw sourceFailure(exception); }
    }

    private DiagramReference diagramReference(String owner, DiagramLink link) {
        try {
            if (link.kind() == LinkKind.KNOWLEDGE) {
                var version = knowledge.version(owner, link.itemId(), link.versionId());
                return new DiagramReference(link.kind(), link.itemId(), version.id(), version.title(), version.number());
            }
            PreparationDtos.Item item = preparation.detail(owner, link.itemId());
            if (!item.kind().name().equals(link.kind().name())) throw unavailable("图节点引用类型与来源对象不一致。");
            PreparationDtos.Version version = preparation.version(owner, link.itemId(), link.versionId());
            return new DiagramReference(link.kind(), item.id(), version.id(), version.content().path("title").stringValue(), version.number());
        } catch (ApiException exception) { throw sourceFailure(exception); }
    }

    private BoardDtos.Detail boardSnapshot(String owner, String id, long revision) {
        try { return boards.snapshot(owner, id, revision); }
        catch (ApiException exception) { throw sourceFailure(exception); }
    }

    private String textLabel(String text) {
        String firstLine = text.lines().findFirst().orElse("").strip();
        if (firstLine.isBlank()) return "文本节点";
        return firstLine.codePointCount(0, firstLine.length()) <= 200 ? firstLine : firstLine.substring(0, firstLine.offsetByCodePoints(0, 200));
    }

    private void bounded(Object value, int maximum, String message) {
        if (json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8).length > maximum) {
            throw new ApiException(422, "TRAINING_LIMIT", message);
        }
    }

    private void writeTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("训练来源必须在调用方持有 owner 锁的写事务中校验");
        }
    }

    private void materials(boolean allowed) {
        if (!allowed) throw new ApiException(409, "ASSISTANCE_CONFIRMATION_REQUIRED", "添加资料引用前请先确认辅助条件。");
    }

    private ApiException sourceFailure(ApiException exception) {
        return exception.status() == 404 ? unavailable("来源版本不存在或不可访问。") : exception;
    }

    private ApiException unavailable(String message) { return new ApiException(422, "SOURCE_UNAVAILABLE", message); }
    private ApiException changed(String message) { return new ApiException(409, "SOURCE_CHANGED", message); }

}
