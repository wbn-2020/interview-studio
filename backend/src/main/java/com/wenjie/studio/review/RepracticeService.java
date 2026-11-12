package com.wenjie.studio.review;

import com.wenjie.studio.ai.AiDtos.EvaluationCitation;
import com.wenjie.studio.ai.AiDtos.EvaluationInput;
import com.wenjie.studio.ai.TrainingEvaluations;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import com.wenjie.studio.review.TodayActionProgress.TargetRef;
import com.wenjie.studio.training.TrainingDtos;
import com.wenjie.studio.training.TrainingMapper;
import com.wenjie.studio.training.TrainingRows;
import com.wenjie.studio.training.TrainingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.wenjie.studio.review.RepracticeDtos.*;
import static com.wenjie.studio.review.RepracticeRows.*;
import static com.wenjie.studio.review.RepracticeRules.*;
import static com.wenjie.studio.review.ReviewRules.*;

@Service
public class RepracticeService {
    private static final JsonMapper PROGRESS_HASH_JSON = JsonMapper.builder().build();
    private final RepracticeMapper mapper;
    private final ReviewMapper actions;
    private final ReviewService review;
    private final TrainingMapper training;
    private final TrainingService sessions;
    private final TrainingEvaluations evaluations;
    private final RecollectionSources recollections;
    private final IdempotencyService commands;
    private final JsonMapper json;

    public RepracticeService(RepracticeMapper mapper, ReviewMapper actions, ReviewService review,
                              TrainingMapper training, TrainingService sessions, TrainingEvaluations evaluations,
                              IdempotencyService commands, JsonMapper json, RecollectionSources recollections) {
        this.mapper = mapper;
        this.actions = actions;
        this.review = review;
        this.training = training;
        this.sessions = sessions;
        this.evaluations = evaluations;
        this.recollections = recollections;
        this.commands = commands;
        this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page list(String owner, String cursor, int limit) {
        uuid(owner); page(limit, 20); if (cursor != null) uuid(cursor);
        List<RepracticeRow> rows = mapper.list(owner, cursor, limit + 1);
        List<Summary> items = rows.stream().limit(limit).map(row -> {
            validateBinding(owner, row);
            requireBaseline(owner, row);
            TrainingRows.SessionRow session = requireSession(owner, row.sessionId());
            return new Summary(row.id(), session.title(), baseline(row), row.sessionId(), row.questionId(),
                    QuestionMode.valueOf(row.questionMode()), TrainingDtos.Status.valueOf(session.status()),
                    snapshots(owner, row).size(), row.createdAt(), baselineKind(row), row.recollectionId(), row.recollectionVersionId());
        }).toList();
        return new Page(items, rows.size() > limit ? items.getLast().id() : null);
    }

    public Repractice create(String owner, String key, Create request) {
        RepracticeRules.create(request);
        return commands.execute(owner, key, "POST /api/v1/review/repractices", request, Repractice.class, () -> {
            endedAnswer(owner, request.baseline());
            List<SourceSnapshot> fixed = new ArrayList<>();
            Map<String, ReviewDtos.Action> selectedActions = new HashMap<>();
            for (Pick pick : request.sources()) {
                ReviewDtos.Action action = selectedActions.computeIfAbsent(pick.actionId(), id -> review.action(owner, id));
                expect(pick.expectedActionRevision(), action.revision());
                if (action.status() != ReviewDtos.ActionStatus.OPEN) throw sourceChanged();
                ReviewDtos.ActionSource source = action.sources().stream().filter(item -> item.id().equals(pick.sourceId()))
                        .findFirst().orElseThrow(RepracticeService::sourceChanged);
                if (source.tracking() != ReviewDtos.Tracking.TRACKING || !request.baseline().equals(source.answer())) throw sourceChanged();
                fixed.add(new SourceSnapshot(source.id(), action.id(), action.revision(), action.title(), source));
            }
            List<String> encodedSources = fixed.stream().map(source -> bounded(source, MAX_SOURCE_BYTES)).toList();
            bounded(fixed, MAX_SOURCES_BYTES);
            // owner 锁、空白训练、固定来源及回执共用一个命令事务，任一步失败均不留下孤立草稿。
            TrainingDtos.Session session = sessions.createRepracticeDraft(owner, request.title(), request.timeBudgetMinutes(),
                    request.baseline().sessionId(), request.baseline().questionId(), request.variant(), request.contextLinks());
            if (session.status() != TrainingDtos.Status.DRAFT || session.questions().size() != 1) throw broken();
            String id = newId();
            RepracticeRow row = new RepracticeRow(id, owner, request.baseline().sessionId(), request.baseline().questionId(),
                    request.baseline().answerId(), session.id(), session.questions().getFirst().id(), request.questionMode().name(), now(),
                    BaselineKind.ANSWER.name(), null, null, null);
            mapper.insertRepractice(row);
            for (int index = 0; index < fixed.size(); index++) {
                SourceSnapshot source = fixed.get(index);
                mapper.insertSource(new SourceRow(owner, id, source.sourceId(), source.actionId(), source.actionRevision(), index, encodedSources.get(index)));
            }
            return view(owner, row);
        });
    }

    public Repractice createRecollection(String owner, String id, String key, CreateRecollection request) {
        uuid(id); RepracticeRules.create(request);
        return commands.execute(owner, key, "POST /api/v1/review/recollections/" + id + "/repractices", request, Repractice.class, () -> {
            RecollectionDtos.Version version = recollections.requireCurrent(owner, id, request.versionId(), request.expectedRevision());
            BaselineKind kind = BaselineKind.valueOf(version.content().baselineKind().name());
            List<SourceSnapshot> fixed = new ArrayList<>();
            Map<String, ReviewDtos.Action> selectedActions = new HashMap<>();
            for (Pick pick : request.sources()) {
                ReviewDtos.Action action = selectedActions.computeIfAbsent(pick.actionId(), actionId -> review.action(owner, actionId));
                expect(pick.expectedActionRevision(), action.revision());
                if (action.status() != ReviewDtos.ActionStatus.OPEN) throw sourceChanged();
                ReviewDtos.ActionSource source = action.sources().stream().filter(item -> item.id().equals(pick.sourceId()))
                        .findFirst().orElseThrow(RepracticeService::sourceChanged);
                ReviewDtos.RecollectionSource origin = source.recollection();
                if (source.kind() != ReviewDtos.SourceKind.RECOLLECTION || source.tracking() != ReviewDtos.Tracking.TRACKING
                        || origin == null || !id.equals(origin.recollectionId()) || !request.versionId().equals(origin.versionId())
                        || origin.versionNumber() != version.number() || origin.baselineKind() != version.content().baselineKind()) throw sourceChanged();
                fixed.add(new SourceSnapshot(source.id(), action.id(), action.revision(), action.title(), source));
            }
            List<String> encodedSources = fixed.stream().map(source -> bounded(source, MAX_SOURCE_BYTES)).toList();
            bounded(fixed, MAX_SOURCES_BYTES);
            TrainingDtos.Session session = sessions.createRecollectionRepracticeDraft(owner, request.title(), request.timeBudgetMinutes(),
                    id, request.versionId(), request.expectedRevision(), request.variant(), request.contextLinks());
            if (session.status() != TrainingDtos.Status.DRAFT || session.questions().size() != 1) throw broken();
            RepracticeRow row = new RepracticeRow(newId(), owner, null, null, null, session.id(), session.questions().getFirst().id(),
                    request.questionMode().name(), now(), kind.name(), id, request.versionId(), request.expectedRevision());
            mapper.insertRepractice(row);
            for (int index = 0; index < fixed.size(); index++) {
                SourceSnapshot source = fixed.get(index);
                mapper.insertSource(new SourceRow(owner, row.id(), source.sourceId(), source.actionId(), source.actionRevision(), index, encodedSources.get(index)));
            }
            return view(owner, row);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Repractice detail(String owner, String id) { return view(owner, requireRepractice(owner, id)); }

    public ComparisonSummary compare(String owner, String id, String key, Compare request) {
        uuid(id); if (request == null) throw invalid("请选择本次复练的确切提交。"); uuid(request.answerId());
        return commands.execute(owner, key, "POST /api/v1/review/repractices/" + id + "/comparisons", request, ComparisonSummary.class, () -> {
            RepracticeRow repractice = requireRepractice(owner, id);
            requireBaseline(owner, repractice);
            TrainingRows.AnswerRow answer = endedAnswer(owner, current(repractice, request.answerId()));
            snapshots(owner, repractice);
            if (mapper.comparisonByAnswer(owner, id, answer.id()) != null) {
                throw new ApiException(409, "REVIEW_COMPARISON_EXISTS", "这次确切提交已有对照，请读取已有记录。");
            }
            if (mapper.comparisons(owner, id).size() >= 20) throw limit("这次复练已达到 20 份对照上限。");
            ComparisonRow row = new ComparisonRow(newId(), owner, id, repractice.sessionId(), repractice.questionId(),
                    answer.id(), answer.versionNumber(), now());
            mapper.insertComparison(row);
            return summary(row);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Comparison comparison(String owner, String id) {
        ComparisonRow row = requireComparison(owner, id);
        RepracticeRow repractice = requireRepractice(owner, row.repracticeId());
        validateComparison(owner, repractice, row);
        Repractice detail = view(owner, repractice);
        Side baseline = baselineKind(repractice) == BaselineKind.ANSWER ? side(owner, baseline(repractice)) : null;
        Side current = side(owner, current(repractice, row.answerId()));
        List<SourceProgress> progress = detail.sources().stream().map(source -> progress(owner, requireSource(owner, source.sourceId()))).toList();
        return new Comparison(summary(row), detail, baseline, current, baseline == null ? List.of() : differences(baseline, current), progress);
    }

    public Judgment judge(String owner, String id, String sourceId, String key, Judge request) {
        uuid(id); uuid(sourceId); RepracticeRules.judge(request);
        return commands.execute(owner, key, "POST /api/v1/review/comparisons/" + id + "/sources/" + sourceId + "/judgments",
                request, Judgment.class, () -> {
            ComparisonRow comparison = requireComparison(owner, id);
            RepracticeRow repractice = requireRepractice(owner, comparison.repracticeId());
            validateComparison(owner, repractice, comparison);
            requireBaseline(owner, repractice);
            SourceSnapshot fixed = snapshots(owner, repractice).stream().filter(source -> source.sourceId().equals(sourceId))
                    .findFirst().orElseThrow(ReviewRules::missing);
            ReviewRows.SourceRow source = requireSource(owner, sourceId);
            if (ReviewDtos.SourceKind.RECOLLECTION.name().equals(source.kind())
                    && recollections.state(owner, source.recollectionId(), source.recollectionVersionId()) != RecollectionDtos.SourceState.CURRENT) {
                throw sourceChanged();
            }
            ReviewRows.ActionRow action = requireAction(owner, source.actionId());
            if (!request.currentActionId().equals(action.id())) throw sourceChanged();
            expect(request.expectedActionRevision(), action.revision());
            if (!ReviewDtos.ActionStatus.OPEN.name().equals(action.status()) || !ReviewDtos.Tracking.TRACKING.name().equals(source.tracking())
                    || !source.verification().equals(fixed.source().verification())) throw sourceChanged();
            JudgmentRow latest = mapper.latestJudgment(owner, sourceId);
            if (latest != null) {
                if (!sourceId.equals(latest.sourceId())) throw broken();
                judgment(owner, latest);
            }
            int revision = latest == null ? 0 : latest.revision();
            expect(request.expectedRevision(), revision);
            if (revision >= MAX_JUDGMENT_REVISION) throw limit("这条来源已达到 1000 次判断上限。");
            EvaluationInput input = evaluations.input(owner, comparison.sessionId(), comparison.questionId(), comparison.answerId());
            RepracticeRules.evidence(request, input);
            String citations = bounded(request.citations(), MAX_CITATION_BYTES);
            // 修订沿稳定来源全局递增；合并不重置判断，也不把动作容器状态改为完成。
            JudgmentRow row = new JudgmentRow(newId(), owner, sourceId, revision + 1, comparison.id(), comparison.answerId(),
                    fixed.source().verification(), request.outcome().name(), request.reason(), citations, now());
            mapper.insertJudgment(row);
            return judgment(owner, row);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public JudgmentPage judgments(String owner, String sourceId, Integer before, int limit) {
        requireSource(owner, sourceId); page(limit, 20);
        if (before != null && (before < 1 || before > MAX_JUDGMENT_REVISION + 1)) throw invalid("判断历史游标无效。");
        List<JudgmentRow> rows = mapper.judgments(owner, sourceId, before, limit + 1);
        List<Judgment> items = rows.stream().limit(limit).map(row -> {
            if (!sourceId.equals(row.sourceId())) throw broken();
            return judgment(owner, row);
        }).toList();
        return new JudgmentPage(items, rows.size() > limit ? items.getLast().revision() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProgressPage progress(String owner, String id) {
        requireAction(owner, id);
        return new ProgressPage(progress(owner, id, actions.sources(owner, id)));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<TodayActionProgress> todayProgress(String owner, List<String> actionIds) {
        uuid(owner);
        if (actionIds == null || actionIds.size() > 50) throw invalid("今日进度每批最多读取 50 个不同动作。");
        Set<String> identities = new HashSet<>();
        for (String id : actionIds) if (!identities.add(uuid(id))) throw invalid("今日进度的动作标识不能重复。");
        List<TodayActionProgress> values = new ArrayList<>();
        for (String id : actionIds) {
            ReviewRows.ActionRow action = requireAction(owner, id);
            List<ReviewRows.SourceRow> rows = actions.sources(owner, id);
            List<SourceProgress> progress = progress(owner, id, rows);
            int trackingCount = 0;
            int pendingCount = 0;
            for (SourceProgress source : progress) {
                if (source.tracking() != ReviewDtos.Tracking.TRACKING) continue;
                trackingCount++;
                // 只有仍适用的逐来源 HANDLED 才退出待处理；OPEN、放弃跟踪或旧判断不能代替完成。
                if (!source.applicable() || source.latest() == null || source.latest().outcome() != Outcome.HANDLED) pendingCount++;
            }
            ReviewDtos.ActionStatus status = ReviewDtos.ActionStatus.valueOf(action.status());
            if ((status == ReviewDtos.ActionStatus.OPEN) != (action.mergedInto() == null)) throw broken();
            values.add(new TodayActionProgress(action.id(), action.revision(), status, action.mergedInto(),
                    progress.size(), trackingCount, pendingCount, progressHash(action.revision(), progress),
                    targetRefs(owner, rows), action.updatedAt()));
        }
        return List.copyOf(values);
    }

    private List<SourceProgress> progress(String owner, String id, List<ReviewRows.SourceRow> rows) {
        if (rows.size() > 20) throw broken();
        return rows.stream().map(source -> {
            if (!id.equals(source.actionId())) throw broken();
            return progress(owner, source);
        }).toList();
    }

    private List<TargetRef> targetRefs(String owner, List<ReviewRows.SourceRow> sources) {
        Set<TargetRef> targets = new HashSet<>();
        for (ReviewRows.SourceRow source : sources) {
            if (ReviewDtos.SourceKind.RECOLLECTION.name().equals(source.kind())) {
                RecollectionDtos.InterviewSnapshot interview = recollections
                        .version(owner, source.recollectionId(), source.recollectionVersionId()).interview();
                if (interview != null && interview.targetId() != null) {
                    targets.add(new TargetRef(uuid(interview.targetId()), uuid(interview.targetVersionId())));
                }
            } else {
                TrainingRows.SessionRow session = requireSession(owner, source.sessionId());
                TrainingDtos.ContextSnapshot[] context = json.readValue(session.contextJson(), TrainingDtos.ContextSnapshot[].class);
                if (context == null) throw broken();
                for (TrainingDtos.ContextSnapshot item : context) {
                    if (item == null || item.kind() == null) throw broken();
                    if (item.kind() == TrainingDtos.ContextKind.TARGET) {
                        targets.add(new TargetRef(uuid(item.itemId()), uuid(item.versionId())));
                    }
                }
            }
        }
        return targets.stream().sorted(Comparator.comparing(TargetRef::itemId).thenComparing(TargetRef::versionId)).toList();
    }

    private static String progressHash(long revision, List<SourceProgress> progress) {
        List<List<Object>> sources = progress.stream().sorted(Comparator.comparing(SourceProgress::sourceId)).map(source -> {
            Judgment latest = source.latest();
            return Arrays.<Object>asList(source.sourceId(), source.actionId(), source.tracking().name(),
                    sha256(source.verification().getBytes(StandardCharsets.UTF_8)),
                    latest == null ? null : latest.id(), latest == null ? null : latest.revision(),
                    latest == null ? null : latest.outcome().name(), source.applicable(),
                    source.recollectionState() == null ? null : source.recollectionState().name());
        }).toList();
        // 固定数组顺序和显式 null，使判断变化及回忆当前性变化进入摘要，正文仅参与验证条件的单独哈希。
        return sha256(PROGRESS_HASH_JSON.writeValueAsBytes(List.of(revision, sources)));
    }

    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("当前运行时不支持进度摘要", exception); }
    }

    private Repractice view(String owner, RepracticeRow row) {
        validateBinding(owner, row);
        RecollectionBaseline recollection = requireBaseline(owner, row);
        List<SourceSnapshot> sources = snapshots(owner, row);
        List<ComparisonRow> comparisons = mapper.comparisons(owner, row.id());
        if (comparisons.size() > 20) throw broken();
        List<ComparisonSummary> summaries = comparisons.stream().map(comparison -> {
            validateComparison(owner, row, comparison); return summary(comparison);
        }).toList();
        TrainingRows.SessionRow session = requireSession(owner, row.sessionId());
        return new Repractice(row.id(), baseline(row), row.sessionId(), row.questionId(), QuestionMode.valueOf(row.questionMode()),
                sources, new TrainingDtos.SessionSummary(session.id(), session.title(), TrainingDtos.Mode.valueOf(session.mode()),
                TrainingDtos.Status.valueOf(session.status()), session.revision(), session.materialAccess(), session.questionCount(),
                session.createdAt(), session.updatedAt()), summaries, row.createdAt(), baselineKind(row), recollection);
    }

    private List<SourceSnapshot> snapshots(String owner, RepracticeRow repractice) {
        List<SourceRow> rows = mapper.sources(owner, repractice.id());
        if (rows.size() > 10) throw broken();
        List<SourceSnapshot> values = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        int totalBytes = 2;
        for (SourceRow row : rows) {
            int bytes = row.snapshotJson().getBytes(StandardCharsets.UTF_8).length;
            totalBytes += bytes + (values.isEmpty() ? 0 : 1);
            // 旧快照按原始 JSON 计量；新增可空字段不能改变旧回执或把合法历史挤出大小上限。
            if (bytes > MAX_SOURCE_BYTES || totalBytes > MAX_SOURCES_BYTES) throw broken();
            SourceSnapshot value = json.readValue(row.snapshotJson(), SourceSnapshot.class);
            if (!owner.equals(row.ownerId()) || !repractice.id().equals(row.repracticeId()) || row.position() != values.size()
                    || value == null || value.source() == null || !row.sourceId().equals(value.sourceId())
                    || !row.actionId().equals(value.actionId()) || row.actionRevision() != value.actionRevision()
                    || !row.sourceId().equals(value.source().id()) || !matchesBaseline(repractice, value.source())
                    || value.source().tracking() != ReviewDtos.Tracking.TRACKING || !identities.add(row.sourceId())) throw broken();
            ReviewRows.SourceRow current = requireSource(owner, row.sourceId());
            if (!sameSource(current, value.source(), json)) throw broken();
            values.add(value);
        }
        return List.copyOf(values);
    }

    private SourceProgress progress(String owner, ReviewRows.SourceRow source) {
        if (!owner.equals(source.ownerId())) throw broken();
        RecollectionDtos.SourceState recollectionState = null;
        if (ReviewDtos.SourceKind.RECOLLECTION.name().equals(source.kind())) {
            recollectionSource(owner, source);
            recollectionState = recollections.state(owner, source.recollectionId(), source.recollectionVersionId());
        } else requireSource(owner, source.id());
        ReviewRows.ActionRow action = requireAction(owner, source.actionId());
        JudgmentRow row = mapper.latestJudgment(owner, source.id());
        Judgment latest = row == null ? null : judgment(owner, row);
        if (latest != null && !source.id().equals(latest.sourceId())) throw broken();
        boolean applicable = RepracticeApplicability.applicable(latest != null,
                ReviewDtos.ActionStatus.valueOf(action.status()), ReviewDtos.Tracking.valueOf(source.tracking()),
                latest != null && source.verification().equals(latest.verification()), recollectionState);
        return new SourceProgress(source.id(), action.id(), action.revision(), source.verification(),
                ReviewDtos.Tracking.valueOf(source.tracking()), latest, applicable, recollectionState);
    }

    private Judgment judgment(String owner, JudgmentRow row) {
        if (!owner.equals(row.ownerId()) || row.revision() < 1 || row.revision() > MAX_JUDGMENT_REVISION) throw broken();
        ComparisonRow comparison = requireComparison(owner, row.comparisonId());
        RepracticeRow repractice = requireRepractice(owner, comparison.repracticeId());
        validateComparison(owner, repractice, comparison);
        requireBaseline(owner, repractice);
        SourceSnapshot source = snapshots(owner, repractice).stream().filter(value -> row.sourceId().equals(value.sourceId()))
                .findFirst().orElseThrow(RepracticeService::broken);
        if (!comparison.answerId().equals(row.answerId()) || !source.source().verification().equals(row.verification())) throw broken();
        List<EvaluationCitation> citations = List.copyOf(Arrays.asList(json.readValue(row.citationsJson(), EvaluationCitation[].class)));
        return new Judgment(row.id(), row.sourceId(), row.revision(), row.comparisonId(), row.answerId(), row.verification(),
                Outcome.valueOf(row.outcome()), row.reason(), citations, row.createdAt());
    }

    private Side side(String owner, ReviewDtos.AnswerLink link) {
        TrainingRows.AnswerRow answer = endedAnswer(owner, link);
        TrainingRows.SessionRow session = requireSession(owner, link.sessionId());
        TrainingRows.QuestionRow question = requireQuestion(owner, link.sessionId(), link.questionId());
        TrainingDtos.SessionConfig config = json.readValue(session.configJson(), TrainingDtos.SessionConfig.class);
        TrainingDtos.Answer submission = new TrainingDtos.Answer(answer.id(), answer.questionId(), answer.versionNumber(), answer.createdAt(),
                json.readValue(answer.contentJson(), TrainingDtos.AnswerContent.class), TrainingDtos.AnswerCondition.valueOf(answer.condition()),
                answer.assistanceEventSequence(), json.readValue(answer.exposureJson(), TrainingDtos.PriorExposure.class));
        // 计时只展示结束时已结算的整场记录，不能把它解释为该题的净答题时长。
        boolean budgetReached = config.timeBudgetMinutes() != null
                && session.observedMillis() + session.unobservedMillis() >= config.timeBudgetMinutes() * 60_000L;
        TrainingDtos.Timing timing = new TrainingDtos.Timing(session.observedMillis() / 1000, session.unobservedMillis() / 1000,
                session.pausedMillis() / 1000, 0, 0, budgetReached, session.endedAt());
        return new Side(link, json.readValue(question.snapshotJson(), TrainingDtos.QuestionSnapshot.class), submission,
                TrainingDtos.Mode.valueOf(session.mode()), config,
                List.copyOf(Arrays.asList(json.readValue(session.contextJson(), TrainingDtos.ContextSnapshot[].class))), timing,
                evaluations.input(owner, link.sessionId(), link.questionId(), link.answerId()).standard());
    }

    private List<Difference> differences(Side baseline, Side current) {
        boolean question = !baseline.question().content().equals(current.question().content())
                || !Objects.equals(baseline.question().sourceQuestionId(), current.question().sourceQuestionId())
                || !Objects.equals(baseline.question().sourceVersionId(), current.question().sourceVersionId());
        boolean reference = !Objects.equals(baseline.standard(), current.standard());
        boolean context = !baseline.context().equals(current.context());
        boolean mode = baseline.mode() != current.mode() || baseline.config().feedbackTiming() != current.config().feedbackTiming();
        boolean assistance = baseline.submission().condition() != current.submission().condition();
        boolean exposure = !Objects.equals(baseline.submission().priorExposure(), current.submission().priorExposure());
        boolean budget = !Objects.equals(baseline.config().timeBudgetMinutes(), current.config().timeBudgetMinutes());
        String referenceNote = baseline.standard() == null && current.standard() == null ? "两侧均没有题库参考；追问复练不借用原主问的参考。"
                : reference ? "两侧参考有差异；参考保留个人参考身份，不作为客观标准分。" : "两侧参考条件一致；个人参考仍保留未审身份。";
        return List.of(new Difference("QUESTION", question, question ? "题面或题库版本有变化，请结合两侧完整条件阅读。" : "两侧采用相同的确切题面与题库来源。"),
                new Difference("REFERENCE", reference, referenceNote),
                new Difference("CONTEXT", context, context ? "两次训练使用的背景快照不同。" : "两次训练使用相同背景快照。"),
                new Difference("MODE", mode, mode ? "训练模式或反馈时机不同。" : "训练模式与反馈时机一致。"),
                new Difference("ASSISTANCE", assistance, assistance ? "提交时的辅助条件不同，请分别阅读。" : "提交时的辅助条件一致。"),
                new Difference("EXPOSURE", exposure, "既往曝光按每次提交保留；复练已有原答曝光，不表示首次见题。"),
                new Difference("TIME_BUDGET", budget, budget ? "两次训练的时间预算不同。" : "两次训练的时间预算一致。"),
                new Difference("SESSION_TIME", baseline.timing().observedSeconds() != current.timing().observedSeconds()
                        || baseline.timing().unobservedSeconds() != current.timing().unobservedSeconds()
                        || baseline.timing().pausedSeconds() != current.timing().pausedSeconds(),
                        "时间为各自整场的观察、未观察和暂停记录，不代表该题净答题时长，也不用于自动判断提升。"));
    }

    private RepracticeRow requireRepractice(String owner, String id) {
        RepracticeRow row = mapper.repractice(uuid(owner), uuid(id));
        if (row == null) throw missing();
        if (!id.equals(row.id())) throw broken();
        validateBinding(owner, row);
        return row;
    }

    private void validateBinding(String owner, RepracticeRow row) {
        BaselineKind kind = baselineKind(row);
        if (!owner.equals(row.ownerId()) || (kind == BaselineKind.ANSWER && row.sessionId().equals(row.baselineSessionId()))
                || !Set.of("SAME", "VARIANT").contains(row.questionMode())) throw broken();
        TrainingRows.SessionRow session = requireSession(owner, row.sessionId());
        TrainingRows.QuestionRow question = requireQuestion(owner, row.sessionId(), row.questionId());
        if (session.questionCount() != 1 || !("SAME".equals(row.questionMode()) ? "REPLAY" : "MAIN").equals(question.kind())) throw broken();
    }

    private ComparisonRow requireComparison(String owner, String id) {
        ComparisonRow row = mapper.comparison(uuid(owner), uuid(id));
        if (row == null) throw missing();
        if (!owner.equals(row.ownerId()) || !id.equals(row.id())) throw broken();
        return row;
    }

    private void validateComparison(String owner, RepracticeRow repractice, ComparisonRow row) {
        if (!owner.equals(row.ownerId()) || !repractice.id().equals(row.repracticeId())
                || !repractice.sessionId().equals(row.sessionId()) || !repractice.questionId().equals(row.questionId())) throw broken();
        TrainingRows.AnswerRow answer = endedAnswer(owner, current(repractice, row.answerId()));
        if (answer.versionNumber() != row.answerNumber()) throw broken();
    }

    private TrainingRows.AnswerRow endedAnswer(String owner, ReviewDtos.AnswerLink link) {
        answer(link);
        TrainingRows.SessionRow session = requireSession(owner, link.sessionId());
        if (!TrainingDtos.Status.ENDED.name().equals(session.status())) {
            throw new ApiException(409, "REVIEW_REPRACTICE_NOT_ENDED", "原答与本次复练均须结束后才能建立或阅读对照。");
        }
        requireQuestion(owner, link.sessionId(), link.questionId());
        TrainingRows.AnswerRow answer = training.answer(owner, link.sessionId(), link.questionId(), link.answerId());
        if (answer == null) throw missing();
        if (!owner.equals(answer.ownerId()) || !link.sessionId().equals(answer.sessionId()) || !link.questionId().equals(answer.questionId())
                || !link.answerId().equals(answer.id())) throw broken();
        requireAccess(owner, link);
        return answer;
    }

    private TrainingRows.SessionRow requireSession(String owner, String id) {
        TrainingRows.SessionRow row = training.session(uuid(owner), uuid(id));
        if (row == null) throw missing();
        if (!owner.equals(row.ownerId()) || !id.equals(row.id())) throw broken();
        return row;
    }

    private TrainingRows.QuestionRow requireQuestion(String owner, String sessionId, String id) {
        TrainingRows.QuestionRow row = training.question(uuid(owner), uuid(sessionId), uuid(id));
        if (row == null) throw missing();
        if (!owner.equals(row.ownerId()) || !sessionId.equals(row.sessionId()) || !id.equals(row.id())) throw broken();
        return row;
    }

    private ReviewRows.SourceRow requireSource(String owner, String id) {
        ReviewRows.SourceRow row = mapper.currentSource(uuid(owner), uuid(id));
        if (row == null) throw missing();
        if (!owner.equals(row.ownerId()) || !id.equals(row.id())) throw broken();
        if (ReviewDtos.SourceKind.RECOLLECTION.name().equals(row.kind())) recollectionSource(owner, row);
        else {
            if (row.recollectionId() != null || row.recollectionVersionId() != null || row.recollectionJson() != null
                    || !(ReviewDtos.SourceKind.MANUAL.name().equals(row.kind()) || ReviewDtos.SourceKind.AI_EVALUATION.name().equals(row.kind()))) throw broken();
            answer(link(row)); requireAccess(owner, link(row));
        }
        return row;
    }

    private RecollectionBaseline requireBaseline(String owner, RepracticeRow row) {
        if (baselineKind(row) == BaselineKind.ANSWER) {
            endedAnswer(owner, baseline(row));
            return null;
        }
        RecollectionBaseline baseline = recollections.baseline(owner, row.recollectionId(), row.recollectionVersionId(), row.recollectionRevision());
        if (!row.baselineKind().equals(baseline.version().content().baselineKind().name())) throw broken();
        return baseline;
    }

    private ReviewDtos.RecollectionSource recollectionSource(String owner, ReviewRows.SourceRow row) {
        if (row.sessionId() != null || row.questionId() != null || row.answerId() != null || row.feedbackId() != null
                || row.feedbackRevision() != null || row.taskId() != null || row.observationId() != null || row.manualKind() != null
                || row.authorLabel() != null || row.observationJson() != null || row.decisionJson() != null || row.recollectionJson() == null) throw broken();
        ReviewDtos.RecollectionSource source = json.readValue(row.recollectionJson(), ReviewDtos.RecollectionSource.class);
        if (source == null || !Objects.equals(row.recollectionId(), source.recollectionId())
                || !Objects.equals(row.recollectionVersionId(), source.versionId())) throw broken();
        RecollectionDtos.Version version = recollections.version(owner, source.recollectionId(), source.versionId());
        if (version.number() != source.versionNumber() || version.content().baselineKind() != source.baselineKind()) throw broken();
        text(source.observation(), 5000, true);
        return source;
    }

    private ReviewRows.ActionRow requireAction(String owner, String id) {
        ReviewRows.ActionRow row = actions.action(uuid(owner), uuid(id));
        if (row == null) throw missing();
        if (!owner.equals(row.ownerId()) || !id.equals(row.id())) throw broken();
        return row;
    }

    private void requireAccess(String owner, ReviewDtos.AnswerLink link) {
        evaluations.requireAccess(owner, link.sessionId(), link.questionId(), link.answerId());
    }

    private String bounded(Object value, int maximum) {
        String encoded = json.writeValueAsString(value);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > maximum) throw limit("复练来源或判断引用超过保存上限，请缩减所选内容。");
        return encoded;
    }

    private static ReviewDtos.AnswerLink baseline(RepracticeRow row) {
        return baselineKind(row) == BaselineKind.ANSWER
                ? new ReviewDtos.AnswerLink(row.baselineSessionId(), row.baselineQuestionId(), row.baselineAnswerId()) : null;
    }
    private static ReviewDtos.AnswerLink current(RepracticeRow row, String answerId) { return new ReviewDtos.AnswerLink(row.sessionId(), row.questionId(), answerId); }
    private static ReviewDtos.AnswerLink link(ReviewRows.SourceRow row) { return new ReviewDtos.AnswerLink(row.sessionId(), row.questionId(), row.answerId()); }
    private static ComparisonSummary summary(ComparisonRow row) { return new ComparisonSummary(row.id(), row.repracticeId(), row.answerId(), row.answerNumber(), row.createdAt()); }
    private static String newId() { return UUID.randomUUID().toString(); }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static void expect(long expected, long actual) {
        if (expected != actual) throw new ApiException(409, "REVIEW_REVISION_CONFLICT", "动作或来源判断已变化，请保留输入并核对最新修订。", actual);
    }
    private static ApiException sourceChanged() { return new ApiException(409, "REVIEW_SOURCE_CHANGED", "来源归属、跟踪状态或验证条件已变化，请保留输入并核对。"); }
    private static IllegalStateException broken() { return new IllegalStateException("复练、来源或比较的确切身份绑定不完整"); }
}
