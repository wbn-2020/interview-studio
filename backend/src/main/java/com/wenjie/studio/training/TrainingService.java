package com.wenjie.studio.training;

import com.wenjie.studio.bank.QuestionDtos;
import com.wenjie.studio.bank.QuestionMapper;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import com.wenjie.studio.review.RecollectionDtos;
import com.wenjie.studio.review.RepracticeDtos;
import com.wenjie.studio.review.RepracticeMapper;
import com.wenjie.studio.review.RepracticeRows;
import com.wenjie.studio.review.RepracticeRules;
import com.wenjie.studio.review.ReviewDtos;
import com.wenjie.studio.review.ReviewMapper;
import com.wenjie.studio.training.TrainingDtos.*;
import com.wenjie.studio.training.TrainingRows.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class TrainingService {
    private static final long MAX_REVISION = 9_007_199_254_740_991L;
    private final TrainingMapper mapper;
    private final TrainingSources sources;
    private final TrainingKnowledgeReferences knowledgeReferences;
    private final QuestionMapper questionBank;
    private final RepracticeMapper repractices;
    private final ReviewMapper reviewActions;
    private final IdempotencyService commands;
    private final JsonMapper json;

    public TrainingService(TrainingMapper mapper, TrainingSources sources,
                            QuestionMapper questionBank, RepracticeMapper repractices,
                            IdempotencyService commands, JsonMapper json, ReviewMapper reviewActions,
                            TrainingKnowledgeReferences knowledgeReferences) {
        this.mapper = mapper;
        this.sources = sources;
        this.knowledgeReferences = knowledgeReferences;
        this.questionBank = questionBank;
        this.repractices = repractices;
        this.reviewActions = reviewActions;
        this.commands = commands;
        this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SessionPage list(String owner, Status status, String cursor, int limit) {
        uuid(owner); limit(limit);
        if (cursor != null) uuid(cursor);
        var rows = mapper.sessions(owner, status == null ? null : status.name(), cursor, limit + 1);
        var page = rows.stream().limit(limit).map(this::summary).toList();
        return new SessionPage(page, rows.size() > limit ? page.getLast().id() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Active active(String owner) {
        SessionRow row = mapper.active(uuid(owner));
        return new Active(row == null ? null : summary(row));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Session detail(String owner, String id) { return current(owner, requireSession(owner, id)); }

    public Session create(String owner, String key, CreateSession request) {
        required(request);
        return commands.execute(owner, key, "POST /api/v1/training/sessions", request, Session.class, () -> {
            CreateSession checked = TrainingInput.create(request);
            SourceSnapshot snapshot = sources.snapshot(owner, checked);
            String contextJson = boundedJson(snapshot.context(), 131_072, "所选上下文超过 128 KiB，请减少选中材料。");
            SessionConfig config = new SessionConfig(checked.feedbackTiming(), checked.timeBudgetMinutes(),
                    checked.followUpLimit(), checked.stages(), checked.collectionId(), checked.collectionVersionId());
            String id = newId();
            Instant now = now();
            mapper.insertSession(new SessionRow(id, owner, checked.title(), checked.mode().name(), Status.DRAFT.name(),
                    0, false, 0, 0, json.writeValueAsString(config), contextJson, snapshot.questions().size(),
                    0, 0, 0, null, null, now, now, null, null, null, 0));
            for (int index = 0; index < snapshot.questions().size(); index++) {
                QuestionSnapshot question = snapshot.questions().get(index);
                PriorExposure exposure = mapper.exposure(owner, question.sourceQuestionId());
                String questionId = newId();
                mapper.insertQuestion(new QuestionRow(questionId, owner, id, question.sourceQuestionId(), question.sourceVersionId(),
                        question.stage(), question.title(), QuestionStatus.PENDING.name(), 0, json.writeValueAsString(question),
                        null, null, null, 0, json.writeValueAsString(exposure), index, QuestionKind.MAIN.name(),
                        null, null, null, null));
                knowledgeReferences.question(owner, id, questionId, question);
            }
            event(owner, id, EventKind.CREATED, null, "已保存训练预览。", null, now, false);
            return current(owner, requireSession(owner, id));
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Session createRepracticeDraft(String owner, String title, Integer timeBudgetMinutes,
                                         String baselineSessionId, String baselineQuestionId,
                                         QuestionSelection variant, List<ContextLink> contextLinks) {
        uuid(owner);
        String checkedTitle = text(title, 200, true);
        if (timeBudgetMinutes != null && (timeBudgetMinutes < 1 || timeBudgetMinutes > 180)) {
            throw invalid("复练时间预算必须为 1 至 180 分钟或留空。");
        }
        SourceSnapshot snapshot = sources.repracticeSnapshot(owner, baselineSessionId, baselineQuestionId, variant, contextLinks);
        QuestionSnapshot question = snapshot.questions().getFirst();
        PriorExposure exposure = question.sourceQuestionId() == null
                ? exposure(requireQuestion(owner, baselineSessionId, baselineQuestionId))
                : mapper.exposure(owner, question.sourceQuestionId());
        return insertRepracticeDraft(owner, checkedTitle, timeBudgetMinutes, snapshot, exposure, variant == null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Session createRecollectionRepracticeDraft(String owner, String title, Integer timeBudgetMinutes,
                                                     String recollectionId, String versionId, long revision,
                                                     QuestionSelection variant, List<ContextLink> contextLinks) {
        uuid(owner);
        String checkedTitle = text(title, 200, true);
        if (timeBudgetMinutes != null && (timeBudgetMinutes < 1 || timeBudgetMinutes > 180)) {
            throw invalid("复练时间预算必须为 1 至 180 分钟或留空。");
        }
        SourceSnapshot snapshot = sources.recollectionRepracticeSnapshot(owner, recollectionId, versionId, revision, variant, contextLinks);
        QuestionSnapshot question = snapshot.questions().getFirst();
        // 回忆没有题库曝光记录；用题面警告标明曾接触，不虚构计数或接触日期。
        PriorExposure exposure = question.sourceQuestionId() == null ? new PriorExposure(0, 0, null, 0L)
                : mapper.exposure(owner, question.sourceQuestionId());
        return insertRepracticeDraft(owner, checkedTitle, timeBudgetMinutes, snapshot, exposure, variant == null);
    }

    private Session insertRepracticeDraft(String owner, String title, Integer timeBudgetMinutes, SourceSnapshot snapshot,
                                           PriorExposure exposure, boolean sameQuestion) {
        QuestionSnapshot question = snapshot.questions().getFirst();
        String contextJson = boundedJson(snapshot.context(), 131_072, "复练背景超过 128 KiB，请减少选中的材料。");
        SessionConfig config = new SessionConfig(FeedbackTiming.AFTER_SESSION, timeBudgetMinutes,
                0, List.of("独立复练"), null, null);
        String id = newId();
        Instant now = now();
        mapper.insertSession(new SessionRow(id, owner, title, Mode.FOCUSED.name(), Status.DRAFT.name(),
                0, false, 0, 0, json.writeValueAsString(config), contextJson, 1,
                0, 0, 0, null, null, now, now, null, null, null, 0));
        // 外层命令固定基线并写入绑定；新题只继承题面与曝光，答案、草稿和父链保持空白。
        String questionId = newId();
        mapper.insertQuestion(new QuestionRow(questionId, owner, id, question.sourceQuestionId(), question.sourceVersionId(),
                question.stage(), question.title(), QuestionStatus.PENDING.name(), 0, json.writeValueAsString(question),
                null, null, null, 0, json.writeValueAsString(exposure), 0,
                sameQuestion ? QuestionKind.REPLAY.name() : QuestionKind.MAIN.name(), null, null, null, null));
        knowledgeReferences.question(owner, id, questionId, question);
        event(owner, id, EventKind.CREATED, null, "已保存空白独立复练预览。", null, now, false);
        return current(owner, requireSession(owner, id));
    }

    public Session start(String owner, String id, String key, ChangeState request) {
        stateRequest(id, request);
        return commands.execute(owner, key, operation(id, "start"), request, Session.class, () -> {
            SessionRow row = changeable(owner, id, request.expectedRevision());
            state(row, Status.DRAFT);
            if (mapper.active(owner) != null) throw new ApiException(409, "ACTIVE_SESSION_EXISTS", "请先结束当前运行或暂停的训练。");
            List<QuestionRow> questions = mapper.questions(owner, id);
            RepracticeRows.RepracticeRow repractice = repractices.bySession(owner, id);
            if (repractice == null) {
                if (questions.stream().anyMatch(question -> QuestionKind.REPLAY.name().equals(question.kind()))) {
                    throw new ApiException(409, "SOURCE_CHANGED", "复练缺少确切原答绑定，请重新创建。");
                }
                sources.revalidate(owner, config(row), contexts(row), questions.stream().map(this::snapshot).toList());
            } else revalidateRepractice(owner, row, questions, repractice);
            // 曝光包含源题各版本并在开始时固定；改版不能抹去既往接触，后续查看不反写旧答案。
            for (QuestionRow question : questions) {
                PriorExposure exposure = question.sourceQuestionId() == null ? exposure(question)
                        : mapper.exposure(owner, question.sourceQuestionId());
                changed(mapper.freezeExposure(owner, id, question.id(), json.writeValueAsString(exposure)));
            }
            Instant now = now();
            boolean access = Mode.LEARNING.name().equals(row.mode());
            changed(mapper.changeState(owner, id, row.revision(), Status.RUNNING.name(), access,
                    now, null, now, null, null, now));
            event(owner, id, EventKind.STARTED, null, access ? "按学习条件开始，可查阅站内资料。" : "开始先答训练。", null, now, false);
            return current(owner, requireSession(owner, id));
        });
    }

    public Session pause(String owner, String id, String key, ChangeState request) {
        stateRequest(id, request);
        return commands.execute(owner, key, operation(id, "pause"), request, Session.class, () -> {
            SessionRow row = changeable(owner, id, request.expectedRevision());
            state(row, Status.RUNNING);
            Instant now = now();
            settle(owner, row, now, false);
            changed(mapper.changeState(owner, id, row.revision(), Status.PAUSED.name(), row.materialAccess(),
                    null, now, row.startedAt(), null, null, now));
            event(owner, id, EventKind.PAUSED, null, "训练已暂停，仍可保存草稿。", null, now, false);
            return current(owner, requireSession(owner, id));
        });
    }

    public Session resume(String owner, String id, String key, ChangeState request) {
        stateRequest(id, request);
        return commands.execute(owner, key, operation(id, "resume"), request, Session.class, () -> {
            SessionRow row = changeable(owner, id, request.expectedRevision());
            state(row, Status.PAUSED);
            Instant now = now();
            settle(owner, row, now, false);
            changed(mapper.changeState(owner, id, row.revision(), Status.RUNNING.name(), row.materialAccess(),
                    now, null, row.startedAt(), null, null, now));
            event(owner, id, EventKind.RESUMED, null, "训练已恢复。", null, now, false);
            return current(owner, requireSession(owner, id));
        });
    }

    public Session end(String owner, String id, String key, EndSession request) {
        uuid(id); required(request); revision(request.expectedRevision());
        if (!Boolean.TRUE.equals(request.checklistConfirmed())) throw invalid("结束前请确认服务器清单，未保存的本地输入不会计入。");
        if (request.expectedChecklistToken() == null || !request.expectedChecklistToken().matches("[0-9a-f]{64}")) {
            throw invalid("请先读取并确认当前服务器结束清单。");
        }
        return commands.execute(owner, key, operation(id, "end"), request, Session.class, () -> {
            SessionRow row = changeable(owner, id, request.expectedRevision());
            if (Status.ENDED.name().equals(row.status())) throw stateConflict();
            List<QuestionRow> questions = mapper.questions(owner, id);
            // 题草稿与提交有独立 revision，结束必须核对清单令牌，不能仅凭会话 revision 认为清单未变。
            if (!request.expectedChecklistToken().equals(checklistToken(questions))) {
                throw new ApiException(409, "CHECKLIST_CHANGED", "题目或草稿已变化，请重新读取并确认服务器结束清单。");
            }
            Instant now = now();
            settle(owner, row, now, true);
            EndChecklist checklist = checklist(questions);
            changed(mapper.changeState(owner, id, row.revision(), Status.ENDED.name(), row.materialAccess(),
                    null, null, row.startedAt(), now, json.writeValueAsString(checklist), now));
            event(owner, id, EventKind.ENDED, null, "已结束训练，提交和未提交草稿均已保留。", null, now, true);
            return current(owner, requireSession(owner, id));
        });
    }

    public Timing pulse(String owner, String id, String key, EmptyRequest request) {
        uuid(id); required(request);
        return commands.execute(owner, key, operation(id, "pulse"), request, Timing.class, () -> {
            SessionRow row = requireSession(owner, id);
            state(row, Status.RUNNING);
            Instant now = now();
            // 心跳只移动接收点和结算计时，不消费会话或题实例 revision。
            settle(owner, row, now, false);
            return timing(requireSession(owner, id), now);
        });
    }

    public Session assistance(String owner, String id, String key, Assistance request) {
        uuid(id); required(request); revision(request.expectedRevision());
        String reason = text(request.reason(), 1000, true);
        return commands.execute(owner, key, operation(id, "assistance"), request, Session.class, () -> {
            SessionRow row = changeable(owner, id, request.expectedRevision());
            state(row, Status.RUNNING, Status.PAUSED);
            if (row.materialAccess()) throw stateConflict();
            Instant now = now();
            int sequence = event(owner, id, EventKind.MATERIAL_ACCESS, null, reason, null, now, false);
            changed(mapper.grantAssistance(owner, id, row.revision(), sequence, now));
            return current(owner, requireSession(owner, id));
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Question question(String owner, String sessionId, String questionId) {
        SessionRow session = requireSession(owner, sessionId);
        return currentQuestion(owner, session, requireQuestion(owner, sessionId, questionId));
    }

    public Question draft(String owner, String sessionId, String questionId, String key, SaveAnswer request) {
        answerRequest(sessionId, questionId, request);
        return commands.execute(owner, key, questionOperation(sessionId, questionId, "draft", "PUT"), request, Question.class, () -> {
            SessionRow session = expectSession(owner, sessionId, request.expectedSessionRevision());
            state(session, Status.RUNNING, Status.PAUSED);
            QuestionRow question = changeableQuestion(owner, sessionId, questionId, request.expectedRevision());
            AnswerContent content = checkedAnswer(owner, question, request.content(), session.materialAccess(), false);
            String contentJson = boundedJson(content, 196_608, "单题草稿超过 192 KiB，请缩减图文。");
            Instant now = now();
            changed(mapper.saveDraft(owner, sessionId, questionId, question.revision(), contentJson, now));
            knowledgeReferences.draft(owner, sessionId, questionId, content);
            event(owner, sessionId, EventKind.DRAFT_SAVED, questionId, "草稿已保存到服务器，尚未提交。", null, now, false);
            return currentQuestion(owner, requireSession(owner, sessionId), requireQuestion(owner, sessionId, questionId));
        });
    }

    public Question submit(String owner, String sessionId, String questionId, String key, SaveAnswer request) {
        answerRequest(sessionId, questionId, request);
        return commands.execute(owner, key, questionOperation(sessionId, questionId, "submit", "POST"), request, Question.class, () -> {
            SessionRow session = expectSession(owner, sessionId, request.expectedSessionRevision());
            state(session, Status.RUNNING);
            QuestionRow question = changeableQuestion(owner, sessionId, questionId, request.expectedRevision());
            if (question.answerCount() >= 100) throw capacity("本题已达到 100 次提交修订上限。");
            AnswerContent content = checkedAnswer(owner, question, request.content(), session.materialAccess(), true);
            String contentJson = boundedJson(content, 196_608, "单题提交超过 192 KiB，请缩减图文。");
            Instant now = now();
            String answerId = newId();
            AnswerCondition condition = Mode.LEARNING.name().equals(session.mode()) ? AnswerCondition.LEARNING
                    : session.materialAccess() ? AnswerCondition.ASSISTED : AnswerCondition.BEFORE_ASSISTANCE;
            mapper.insertAnswer(new AnswerRow(answerId, owner, sessionId, questionId, question.answerCount() + 1,
                    contentJson, condition.name(), session.assistanceEventSequence(), question.exposureJson(), now));
            knowledgeReferences.answer(owner, sessionId, questionId, answerId, content);
            // 消费服务器草稿与创建不可变答案同事务；答案只采用此次明确发送的图文。
            changed(mapper.submit(owner, sessionId, questionId, question.revision(), answerId, question.answerCount() + 1));
            knowledgeReferences.clearDraft(owner, sessionId, questionId);
            event(owner, sessionId, EventKind.ANSWER_SUBMITTED, questionId, "已保存不可变答案修订。", null, now, false);
            return currentQuestion(owner, requireSession(owner, sessionId), requireQuestion(owner, sessionId, questionId));
        });
    }

    public Question skip(String owner, String sessionId, String questionId, String key, QuestionCommand request, boolean skip) {
        uuid(sessionId); uuid(questionId); required(request); revision(request.expectedSessionRevision()); revision(request.expectedRevision());
        String action = skip ? "skip" : "unskip";
        return commands.execute(owner, key, questionOperation(sessionId, questionId, action, "POST"), request, Question.class, () -> {
            SessionRow session = expectSession(owner, sessionId, request.expectedSessionRevision());
            state(session, Status.RUNNING);
            QuestionRow question = changeableQuestion(owner, sessionId, questionId, request.expectedRevision());
            if (skip == QuestionStatus.SKIPPED.name().equals(question.status())) throw stateConflict();
            String status = skip ? QuestionStatus.SKIPPED.name()
                    : question.answerCount() > 0 ? QuestionStatus.ANSWERED.name() : QuestionStatus.PENDING.name();
            changed(mapper.changeQuestionStatus(owner, sessionId, questionId, question.revision(), status));
            event(owner, sessionId, skip ? EventKind.SKIPPED : EventKind.UNSKIPPED, questionId,
                    skip ? "已跳过题目，原草稿仍保留。" : "已恢复题目。", null, now(), false);
            return currentQuestion(owner, requireSession(owner, sessionId), requireQuestion(owner, sessionId, questionId));
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AnswerPage answers(String owner, String sessionId, String questionId, Integer before, int limit) {
        requireSession(owner, sessionId); requireQuestion(owner, sessionId, questionId); history(before, limit);
        var rows = mapper.answers(owner, sessionId, questionId, before, limit + 1);
        var page = rows.stream().limit(limit).toList();
        return new AnswerPage(page, rows.size() > limit ? page.getLast().number() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Answer answer(String owner, String sessionId, String questionId, String answerId) {
        requireSession(owner, sessionId); requireQuestion(owner, sessionId, questionId);
        AnswerRow row = mapper.answer(owner, sessionId, questionId, uuid(answerId));
        if (row == null) throw missing();
        return toAnswer(row);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EventPage events(String owner, String sessionId, Integer before, int limit) {
        requireSession(owner, sessionId); history(before, limit);
        var rows = mapper.events(owner, sessionId, before, limit + 1);
        var page = rows.stream().limit(limit).toList();
        return new EventPage(page, rows.size() > limit ? page.getLast().sequence() : null);
    }

    public QuestionDtos.Reference reference(String owner, String sessionId, String questionId, String key, EmptyRequest request) {
        uuid(sessionId); uuid(questionId); required(request);
        return commands.execute(owner, key, questionOperation(sessionId, questionId, "reference", "POST"),
                request, QuestionDtos.Reference.class, () -> {
                    SessionRow session = requireSession(owner, sessionId);
                    materials(session, true);
                    QuestionRow question = requireQuestion(owner, sessionId, questionId);
                    if (question.sourceQuestionId() == null) {
                        throw new ApiException(422, "FOLLOW_UP_REFERENCE_UNAVAILABLE", "此题没有题库参考答案。");
                    }
                    var reference = questionBank.reference(owner, question.sourceQuestionId(), question.sourceVersionId());
                    if (reference == null) throw new ApiException(422, "SOURCE_UNAVAILABLE", "确切题目参考不存在或不可访问。");
                    Instant now = now();
                    String exposureId = newId();
                    questionBank.insertExposure(owner, exposureId, question.sourceQuestionId(), question.sourceVersionId(),
                            QuestionDtos.ExposureKind.REFERENCE_VIEW.name(), now);
                    event(owner, sessionId, EventKind.REFERENCE_VIEW, questionId, "已请求此题确切版本的参考。", null, now, false);
                    var checkpoints = List.copyOf(Arrays.asList(json.readValue(reference.checkpointsJson(), QuestionDtos.Checkpoint[].class)));
                    return new QuestionDtos.Reference(question.sourceQuestionId(), question.sourceVersionId(),
                            QuestionDtos.StandardStatus.PERSONAL_UNREVIEWED, reference.referenceAnswer(), checkpoints, exposureId, now);
                });
    }

    public BoardImportCandidate importBoard(String owner, String sessionId, String key, ImportBoard request) {
        uuid(sessionId); required(request); uuid(request.boardId()); revision(request.expectedBoardRevision());
        return commands.execute(owner, key, operation(sessionId, "import-board"), request, BoardImportCandidate.class, () -> {
            SessionRow session = requireSession(owner, sessionId);
            state(session, Status.RUNNING, Status.PAUSED);
            materials(session, false);
            BoardImportCandidate candidate = sources.importBoard(owner, request.boardId(), request.expectedBoardRevision());
            BoardSource source = required(candidate.diagram().boardSource());
            uuid(source.importId());
            Instant now = now();
            // 来源凭证、导入事件和幂等回执同事务保存，答案不能只凭一个存在的白板版本伪称已导入。
            mapper.insertBoardImport(new BoardImportRow(source.importId(), owner, sessionId,
                    source.boardId(), source.revision(), now));
            event(owner, sessionId, EventKind.BOARD_IMPORT, null, "已固定白板候选图，保存或提交后成为答案内容。", null, now, false);
            return candidate;
        });
    }

    private Session current(String owner, SessionRow row) {
        List<QuestionRow> questions = mapper.questions(owner, row.id());
        var ordered = new ArrayList<QuestionRow>();
        for (QuestionRow question : questions) {
            if (!QuestionKind.MAIN.name().equals(question.kind()) && !QuestionKind.REPLAY.name().equals(question.kind())) continue;
            ordered.add(question);
            questions.stream().filter(child -> question.id().equals(child.rootQuestionId())).forEach(ordered::add);
        }
        List<QuestionSummary> summaries = ordered.stream().map(question -> new QuestionSummary(question.id(),
                question.sourceQuestionId(), question.sourceVersionId(), question.title(), question.stage(),
                QuestionStatus.valueOf(question.status()), question.revision(), question.draftJson() != null,
                question.answerCount(), snapshot(question).warnings(), exposure(question),
                QuestionKind.valueOf(question.kind()), followUp(question))).toList();
        EndChecklist checklist = row.endChecklistJson() == null ? checklist(questions)
                : json.readValue(row.endChecklistJson(), EndChecklist.class);
        List<ContextSnapshot> context = contexts(row);
        return new Session(row.id(), row.title(), Mode.valueOf(row.mode()), Status.valueOf(row.status()), row.revision(),
                row.materialAccess(), row.createdAt(), row.updatedAt(), row.startedAt(), row.endedAt(), config(row),
                context, summaries, timing(row, now()), checklist, checklistToken(questions), row.generation(),
                sources.contextChecks(owner, context));
    }

    private void revalidateRepractice(String owner, SessionRow session, List<QuestionRow> questions,
                                     RepracticeRows.RepracticeRow binding) {
        RepracticeDtos.BaselineKind baselineKind = RepracticeRules.baselineKind(binding);
        SessionConfig config = config(session);
        boolean sameQuestion = "SAME".equals(binding.questionMode());
        if (!owner.equals(binding.ownerId()) || !session.id().equals(binding.sessionId())
                || (!sameQuestion && !"VARIANT".equals(binding.questionMode()))
                || !Mode.FOCUSED.name().equals(session.mode()) || config.feedbackTiming() != FeedbackTiming.AFTER_SESSION
                || config.followUpLimit() != 0 || !List.of("独立复练").equals(config.stages())
                || config.collectionId() != null || config.collectionVersionId() != null
                || (config.timeBudgetMinutes() != null && (config.timeBudgetMinutes() < 1 || config.timeBudgetMinutes() > 180))
                || session.questionCount() != 1 || questions.size() != 1) {
            throw new ApiException(409, "SOURCE_CHANGED", "复练配置或原答绑定已变化，请重新创建。");
        }
        QuestionRow question = questions.getFirst();
        QuestionSnapshot questionSnapshot = snapshot(question);
        if (!question.id().equals(binding.questionId())
                || !(sameQuestion ? QuestionKind.REPLAY.name() : QuestionKind.MAIN.name()).equals(question.kind())
                || question.rootQuestionId() != null || question.parentQuestionId() != null
                || question.parentAnswerId() != null || question.aiTaskId() != null || questionSnapshot == null
                || !Objects.equals(question.sourceQuestionId(), questionSnapshot.sourceQuestionId())
                || !Objects.equals(question.sourceVersionId(), questionSnapshot.sourceVersionId())
                || !Objects.equals(question.title(), questionSnapshot.title())
                || !Objects.equals(question.stage(), questionSnapshot.stage())) {
            throw new ApiException(409, "SOURCE_CHANGED", "复练题目身份与原答绑定不一致，请重新创建。");
        }
        RecollectionDtos.Version recollection = null;
        if (baselineKind == RepracticeDtos.BaselineKind.ANSWER) {
            SessionRow baseline = requireSession(owner, binding.baselineSessionId());
            requireQuestion(owner, baseline.id(), binding.baselineQuestionId());
            if (!Status.ENDED.name().equals(baseline.status()) || baseline.id().equals(session.id())
                    || mapper.answer(owner, baseline.id(), binding.baselineQuestionId(), uuid(binding.baselineAnswerId())) == null) {
                throw new ApiException(409, "SOURCE_CHANGED", "复练基线必须是已结束训练的确切正式提交。");
            }
            sources.revalidateRepractice(owner, config, contexts(session), questionSnapshot, baseline.id(), binding.baselineQuestionId(), sameQuestion);
        } else {
            recollection = sources.revalidateRecollectionRepractice(owner, contexts(session), questionSnapshot,
                    binding.recollectionId(), binding.recollectionVersionId(), binding.recollectionRevision(), sameQuestion);
            if (!baselineKind.name().equals(recollection.content().baselineKind().name())) {
                throw new ApiException(409, "SOURCE_CHANGED", "回忆基线类型与固定版本不一致，请重新创建复练。");
            }
        }
        revalidateRepracticeSources(owner, binding, recollection);
    }

    private void revalidateRepracticeSources(String owner, RepracticeRows.RepracticeRow binding, RecollectionDtos.Version recollection) {
        List<RepracticeRows.SourceRow> saved = repractices.sources(owner, binding.id());
        if (saved.size() > 10) throw new IllegalStateException("复练固定来源数量异常");
        var seen = new HashSet<String>();
        for (int index = 0; index < saved.size(); index++) {
            RepracticeRows.SourceRow row = saved.get(index);
            RepracticeDtos.SourceSnapshot fixed = json.readValue(row.snapshotJson(), RepracticeDtos.SourceSnapshot.class);
            if (!owner.equals(row.ownerId()) || !binding.id().equals(row.repracticeId()) || row.position() != index
                    || fixed == null || !row.sourceId().equals(fixed.sourceId()) || !row.actionId().equals(fixed.actionId())
                    || row.actionRevision() != fixed.actionRevision() || !seen.add(row.sourceId())
                    || !RepracticeRules.matchesBaseline(binding, fixed.source()) || !row.sourceId().equals(fixed.source().id())
                    || fixed.source().tracking() != ReviewDtos.Tracking.TRACKING
                    || (recollection != null && fixed.source().recollection().versionNumber() != recollection.number())) {
                throw new IllegalStateException("复练固定来源与基线绑定不一致");
            }
            var source = repractices.currentSource(owner, row.sourceId());
            var action = reviewActions.action(owner, row.actionId());
            // 只在开始前验证当前归属与修订；已开始训练不再依赖可编辑的回忆或动作。
            if (source == null || action == null || !owner.equals(source.ownerId()) || !owner.equals(action.ownerId())
                    || !row.actionId().equals(source.actionId()) || !row.actionId().equals(action.id())
                    || action.revision() != row.actionRevision() || !ReviewDtos.ActionStatus.OPEN.name().equals(action.status())
                    || !ReviewDtos.Tracking.TRACKING.name().equals(source.tracking())
                    || !Objects.equals(source.verification(), fixed.source().verification())
                    || !RepracticeRules.sameSource(source, fixed.source(), json)) {
                throw new ApiException(409, "SOURCE_CHANGED", "复练来源的归属、修订、跟踪或验证条件已变化，请重新创建复练。");
            }
        }
    }

    private Question currentQuestion(String owner, SessionRow session, QuestionRow row) {
        Draft draft = row.draftJson() == null ? null : new Draft(json.readValue(row.draftJson(), AnswerContent.class), row.draftSavedAt());
        AnswerRow latest = row.latestAnswerId() == null ? null : mapper.answer(owner, session.id(), row.id(), row.latestAnswerId());
        if (row.latestAnswerId() != null && latest == null) throw new IllegalStateException("题目最新答案不完整");
        return new Question(row.id(), session.id(), session.revision(), row.revision(), QuestionStatus.valueOf(row.status()),
                snapshot(row), draft, latest == null ? null : toAnswer(latest), exposure(row),
                QuestionKind.valueOf(row.kind()), followUp(row));
    }

    private FollowUpOrigin followUp(QuestionRow row) {
        return QuestionKind.FOLLOW_UP.name().equals(row.kind())
                ? new FollowUpOrigin(row.rootQuestionId(), row.parentQuestionId(), row.parentAnswerId(), row.aiTaskId()) : null;
    }

    private Answer toAnswer(AnswerRow row) {
        return new Answer(row.id(), row.questionId(), row.versionNumber(), row.createdAt(),
                json.readValue(row.contentJson(), AnswerContent.class), AnswerCondition.valueOf(row.condition()),
                row.assistanceEventSequence(), json.readValue(row.exposureJson(), PriorExposure.class));
    }

    private AnswerContent checkedAnswer(String owner, QuestionRow question, AnswerInput request,
                                         boolean materialAccess, boolean submitting) {
        AnswerInput checked = TrainingInput.answer(request, submitting);
        if (submitting && !checked.diagram().nodes().isEmpty() && checked.text().isBlank()) {
            throw new ApiException(422, "INVALID_DIAGRAM", "提交图文答案时必须包含文字解释。");
        }
        List<AnswerContent> preserved = new ArrayList<>();
        if (question.draftJson() != null) preserved.add(json.readValue(question.draftJson(), AnswerContent.class));
        if (question.latestAnswerId() != null) {
            AnswerRow answer = mapper.answer(owner, question.sessionId(), question.id(), question.latestAnswerId());
            if (answer == null) throw new IllegalStateException("题目最新答案不完整");
            preserved.add(json.readValue(answer.contentJson(), AnswerContent.class));
        }
        return sources.answer(owner, question.sessionId(), checked, materialAccess, List.copyOf(preserved));
    }

    private SessionSummary summary(SessionRow row) {
        return new SessionSummary(row.id(), row.title(), Mode.valueOf(row.mode()), Status.valueOf(row.status()),
                row.revision(), row.materialAccess(), row.questionCount(), row.createdAt(), row.updatedAt());
    }

    private EndChecklist checklist(List<QuestionRow> questions) {
        int answered = 0, skipped = 0, unanswered = 0, drafts = 0;
        for (QuestionRow question : questions) {
            if (QuestionStatus.ANSWERED.name().equals(question.status())) answered++;
            else if (QuestionStatus.SKIPPED.name().equals(question.status())) skipped++;
            else unanswered++;
            if (question.draftJson() != null) drafts++;
        }
        return new EndChecklist(answered, skipped, unanswered, drafts);
    }

    private static String checklistToken(List<QuestionRow> questions) {
        StringBuilder content = new StringBuilder();
        questions.stream().sorted(Comparator.comparing(QuestionRow::id)).forEach(question -> content.append(question.id())
                .append(':').append(question.revision()).append(':').append(question.status()).append(':')
                .append(question.draftJson() != null).append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("当前运行时不支持清单摘要", exception); }
    }

    private Timing timing(SessionRow row, Instant now) {
        long unknown = Status.RUNNING.name().equals(row.status()) ? elapsed(row.observationAt(), now) : 0;
        long paused = Status.PAUSED.name().equals(row.status()) ? elapsed(row.pauseStartedAt(), now) : 0;
        Integer budget = config(row).timeBudgetMinutes();
        boolean reached = budget != null && row.observedMillis() + row.unobservedMillis() + unknown >= budget * 60_000L;
        return new Timing(row.observedMillis() / 1000, row.unobservedMillis() / 1000, row.pausedMillis() / 1000,
                unknown / 1000, paused / 1000, reached, now);
    }

    private void settle(String owner, SessionRow row, Instant now, boolean ending) {
        long observed = row.observedMillis(), unobserved = row.unobservedMillis(), paused = row.pausedMillis();
        Instant observationAt = row.observationAt(), pauseStartedAt = row.pauseStartedAt();
        if (Status.RUNNING.name().equals(row.status())) {
            long elapsed = elapsed(observationAt, now);
            if (observationAt == null || !now.isAfter(observationAt.plusSeconds(45))) observed += elapsed;
            else {
                unobserved += elapsed;
                // 为结束保留事件名额；极限情况下仍结算未知时间，不让审计容量阻止收尾。
                if (!ending || requireSession(owner, row.id()).eventCount() < 1999) {
                    event(owner, row.id(), EventKind.UNOBSERVED_INTERVAL, null, "接收间隔超过 45 秒，整段计为未观察时间。",
                            elapsed / 1000, now, false);
                }
            }
            observationAt = now;
        } else if (Status.PAUSED.name().equals(row.status())) {
            paused += elapsed(pauseStartedAt, now);
            pauseStartedAt = now;
        } else return;
        changed(mapper.updateTiming(owner, row.id(), observed, unobserved, paused, observationAt, pauseStartedAt));
    }

    private int event(String owner, String sessionId, EventKind kind, String questionId, String detail,
                       Long durationSeconds, Instant now, boolean ending) {
        SessionRow session = requireSession(owner, sessionId);
        int count = session.eventCount();
        if (ending && count >= 2000) return count;
        if (!ending && count >= 1999) throw capacity("本场训练事件已达到容量边界，请保存现有输入副本后结束训练。");
        int sequence = count + 1;
        mapper.insertEvent(owner, sessionId, new Event(newId(), sequence, kind, questionId, detail, durationSeconds, now));
        changed(mapper.incrementEventCount(owner, sessionId, count));
        return sequence;
    }

    private SessionConfig config(SessionRow row) { return json.readValue(row.configJson(), SessionConfig.class); }
    private List<ContextSnapshot> contexts(SessionRow row) { return List.copyOf(Arrays.asList(json.readValue(row.contextJson(), ContextSnapshot[].class))); }
    private QuestionSnapshot snapshot(QuestionRow row) { return json.readValue(row.snapshotJson(), QuestionSnapshot.class); }
    private PriorExposure exposure(QuestionRow row) { return json.readValue(row.exposureJson(), PriorExposure.class); }

    private SessionRow requireSession(String owner, String id) {
        SessionRow row = mapper.session(uuid(owner), uuid(id));
        if (row == null) throw missing();
        return row;
    }
    private QuestionRow requireQuestion(String owner, String sessionId, String id) {
        QuestionRow row = mapper.question(uuid(owner), uuid(sessionId), uuid(id));
        if (row == null) throw missing();
        return row;
    }
    private SessionRow expectSession(String owner, String id, long expected) {
        SessionRow row = requireSession(owner, id);
        expect(expected, row.revision());
        return row;
    }
    private SessionRow changeable(String owner, String id, long expected) {
        SessionRow row = expectSession(owner, id, expected);
        if (row.revision() == MAX_REVISION) throw capacity("会话修订号已达到上限。");
        return row;
    }
    private QuestionRow changeableQuestion(String owner, String sessionId, String id, long expected) {
        QuestionRow row = requireQuestion(owner, sessionId, id);
        expect(expected, row.revision());
        if (row.revision() == MAX_REVISION) throw capacity("题实例修订号已达到上限。");
        return row;
    }
    private static void materials(SessionRow row, boolean allowEnded) {
        if ((allowEnded && Status.ENDED.name().equals(row.status())) || row.materialAccess()) return;
        throw new ApiException(409, "ASSISTANCE_CONFIRMATION_REQUIRED", "请先明确确认本次训练允许查阅资料。");
    }
    private static void state(SessionRow row, Status... allowed) {
        if (Arrays.stream(allowed).noneMatch(status -> status.name().equals(row.status()))) throw stateConflict();
    }
    private static void stateRequest(String id, ChangeState request) { uuid(id); required(request); revision(request.expectedRevision()); }
    private static void answerRequest(String sessionId, String questionId, SaveAnswer request) {
        uuid(sessionId); uuid(questionId); required(request); required(request.content());
        revision(request.expectedSessionRevision()); revision(request.expectedRevision());
    }
    private String boundedJson(Object value, int bytes, String message) {
        String encoded = json.writeValueAsString(value);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > bytes) throw capacity(message);
        return encoded;
    }
    private static void expect(long expected, long actual) {
        if (expected != actual) throw new ApiException(409, "REVISION_CONFLICT", "训练已被其他操作修改，请保留输入后重新读取。", actual);
    }
    private static void changed(int count) { if (count != 1) throw new ApiException(409, "REVISION_CONFLICT", "训练已变化，本次操作未提交。"); }
    private static void revision(Long value) { if (value == null || value < 0 || value > MAX_REVISION) throw invalid("预期修订号无效。"); }
    private static void limit(int value) { if (value < 1 || value > 50) throw invalid("每页数量必须为 1 至 50。"); }
    private static void history(Integer before, int limit) { limit(limit); if (before != null && before < 1) throw invalid("历史游标必须为正整数。"); }
    private static <T> T required(T value) { if (value == null) throw invalid("必填内容不能为空。"); return value; }
    private static String uuid(String value) {
        try { if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException exception) { throw invalid("对象标识必须为标准小写 UUID。"); }
        return value;
    }
    private static String text(String value, int max, boolean required) {
        String content = value == null ? "" : value;
        if ((required && content.isBlank()) || content.codePointCount(0, content.length()) > max) throw invalid("文本为空或超出长度限制。");
        return content;
    }
    private static long elapsed(Instant from, Instant to) { return from == null ? 0 : Math.max(0, Duration.between(from, to).toMillis()); }
    private static String operation(String id, String action) { return "POST /api/v1/training/sessions/" + id + "/" + action; }
    private static String questionOperation(String session, String question, String action, String method) { return method + " /api/v1/training/sessions/" + session + "/questions/" + question + "/" + action; }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static String newId() { return UUID.randomUUID().toString(); }
    private static ApiException invalid(String message) { return new ApiException(400, "INVALID_REQUEST", message); }
    private static ApiException capacity(String message) { return new ApiException(422, "TRAINING_LIMIT", message); }
    private static ApiException missing() { return new ApiException(404, "TRAINING_NOT_FOUND", "训练、题目或答案不存在或不可访问。"); }
    private static ApiException stateConflict() { return new ApiException(409, "SESSION_STATE_CONFLICT", "当前训练或题目状态不允许此操作，请重新读取。"); }
}
