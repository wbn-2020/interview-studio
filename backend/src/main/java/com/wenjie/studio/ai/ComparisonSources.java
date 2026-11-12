package com.wenjie.studio.ai;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.review.RepracticeDtos;
import com.wenjie.studio.review.RepracticeMapper;
import com.wenjie.studio.review.RepracticeRows;
import com.wenjie.studio.review.RepracticeRules;
import com.wenjie.studio.review.ReviewDtos;
import com.wenjie.studio.review.ReviewMapper;
import com.wenjie.studio.review.ReviewRows;
import com.wenjie.studio.training.TrainingDtos;
import com.wenjie.studio.training.TrainingMapper;
import com.wenjie.studio.training.TrainingRows;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.wenjie.studio.ai.ComparisonDtos.*;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ComparisonSources {
    private static final long MAX_SAFE = 9_007_199_254_740_991L;
    private static final Set<String> SOURCE_ERRORS = Set.of("AI_COMPARISON_NOT_FOUND", "AI_SOURCE_CHANGED",
            "AI_EVALUATION_ACCESS_REQUIRED", "AI_EVALUATION_NOT_FOUND");
    private final RepracticeMapper repractices;
    private final ReviewMapper actions;
    private final TrainingMapper training;
    private final TrainingEvaluations evaluations;
    private final ComparisonMapper mapper;
    private final JsonMapper json;

    public ComparisonSources(RepracticeMapper repractices, ReviewMapper actions, TrainingMapper training,
                              TrainingEvaluations evaluations, ComparisonMapper mapper, JsonMapper json) {
        this.repractices = repractices;
        this.actions = actions;
        this.training = training;
        this.evaluations = evaluations;
        this.mapper = mapper;
        this.json = json;
    }

    public record Selection(Input input, Checks checks) {}

    public Selection selection(String owner, String id, List<String> sourceIds) {
        requireUuid(owner); requireUuid(id);
        if (sourceIds == null || sourceIds.isEmpty() || sourceIds.size() > 10) throw invalid();
        Set<String> distinct = new HashSet<>();
        for (String sourceId : sourceIds) {
            requireUuid(sourceId);
            if (!distinct.add(sourceId)) throw invalid();
        }
        Pair pair = pair(owner, id);
        Map<String, RepracticeDtos.SourceSnapshot> fixed = fixedSources(owner, pair);
        List<Source> sources = new ArrayList<>();
        List<SourceCheck> checks = new ArrayList<>();
        for (String sourceId : sourceIds.stream().sorted().toList()) {
            RepracticeDtos.SourceSnapshot snapshot = fixed.get(sourceId);
            if (snapshot == null) throw missing();
            ReviewRows.SourceRow source = repractices.currentSource(owner, sourceId);
            if (source == null) throw missing();
            if (!sameSource(owner, source, snapshot)) throw broken();
            ReviewRows.ActionRow action = actions.action(owner, source.actionId());
            if (action == null) throw missing();
            if (!owner.equals(action.ownerId()) || !source.actionId().equals(action.id())) throw broken();
            if (!ReviewDtos.ActionStatus.OPEN.name().equals(action.status())
                    || !ReviewDtos.Tracking.TRACKING.name().equals(source.tracking())
                    || !snapshot.source().verification().equals(source.verification())) throw changed();
            sources.add(new Source(sourceId, snapshot.source().verification()));
            checks.add(new SourceCheck(sourceId, action.id(), action.revision()));
        }
        Input input = new Input(id, pair.baseline().value(), pair.current().value(), List.copyOf(sources), differences(pair));
        Checks condition = new Checks(id, pair.baseline().session().generation(), pair.current().session().generation(), List.copyOf(checks));
        if (!valid(input, condition)) throw changed();
        return new Selection(input, condition);
    }

    public boolean current(String owner, Input input, Checks checks) {
        if (!valid(input, checks)) return false;
        try {
            Selection selected = selection(owner, input.comparisonId(), input.sources().stream().map(Source::sourceId).toList());
            return input.equals(selected.input()) && checks.equals(selected.checks());
        } catch (ApiException exception) {
            // 仅把可识别的来源失效变成 false；数据库和坏记录不能冒充普通来源变化。
            if (SOURCE_ERRORS.contains(exception.code())) return false;
            throw exception;
        }
    }

    public void requireAvailable(String owner, String id, String excludedTaskId) {
        pair(owner, id);
        if (excludedTaskId != null) {
            requireUuid(excludedTaskId);
            ComparisonRows.BindingRow binding = mapper.binding(owner, excludedTaskId);
            if (binding == null || !owner.equals(binding.ownerId()) || !id.equals(binding.comparisonId())
                    || !excludedTaskId.equals(binding.taskId())) throw missing();
        }
        if ((excludedTaskId == null && mapper.taskCount(owner, id) >= 3)
                || mapper.pendingCount(owner, id, excludedTaskId) > 0) {
            throw new ApiException(409, "AI_COMPARISON_LIMIT", "这份对照已有在途比较任务或已达到 3 个任务上限，请查看已有记录。");
        }
    }

    public void requireReadable(String owner, String id) { pair(owner, id); }

    public Link binding(String owner, String taskId, Input input) {
        requireUuid(owner); requireUuid(taskId);
        ComparisonRows.BindingRow binding = mapper.binding(owner, taskId);
        if (binding == null || !validInput(input) || !owner.equals(binding.ownerId()) || !taskId.equals(binding.taskId())
                || !input.comparisonId().equals(binding.comparisonId())) throw missing();
        Pair pair = pair(owner, input.comparisonId());
        if (!pair.baseline().value().equals(input.baseline()) || !pair.current().value().equals(input.current())
                || !differences(pair).equals(input.differences())) throw broken();
        Map<String, RepracticeDtos.SourceSnapshot> fixed = fixedSources(owner, pair);
        for (Source source : input.sources()) {
            RepracticeDtos.SourceSnapshot saved = fixed.get(source.sourceId());
            if (saved == null || !source.verification().equals(saved.source().verification())) throw broken();
        }
        // 历史任务只核对原绑定、原提交与固定条件；动作后来的合并或停止跟踪不会隐藏旧候选。
        return new Link(input.comparisonId(), input.sources().stream().map(Source::sourceId).toList());
    }

    private Pair pair(String owner, String id) {
        RepracticeRows.ComparisonRow comparison = requireComparison(owner, id);
        RepracticeRows.RepracticeRow repractice = repractices.repractice(owner, comparison.repracticeId());
        if (repractice == null) throw missing();
        if (RepracticeRules.baselineKind(repractice) != RepracticeDtos.BaselineKind.ANSWER) {
            throw new ApiException(409, "AI_SOURCE_CHANGED", "AI 双答案比较只支持正式回答基线；个人回忆和无旧答补练请使用人工对照或新答单独评价。");
        }
        if (!owner.equals(repractice.ownerId()) || !comparison.repracticeId().equals(repractice.id())
                || !comparison.sessionId().equals(repractice.sessionId()) || !comparison.questionId().equals(repractice.questionId())
                || repractice.sessionId().equals(repractice.baselineSessionId())
                || !Set.of("SAME", "VARIANT").contains(repractice.questionMode())) throw broken();
        SideData baseline = side(owner, new ReviewDtos.AnswerLink(repractice.baselineSessionId(),
                repractice.baselineQuestionId(), repractice.baselineAnswerId()));
        SideData current = side(owner, new ReviewDtos.AnswerLink(comparison.sessionId(), comparison.questionId(), comparison.answerId()));
        TrainingDtos.SessionConfig config = json.readValue(current.session().configJson(), TrainingDtos.SessionConfig.class);
        TrainingRows.QuestionRow question = current.question();
        if (comparison.answerNumber() != current.value().answerNumber() || current.session().questionCount() != 1
                || current.value().mode() != TrainingDtos.Mode.FOCUSED || current.value().feedbackTiming() != TrainingDtos.FeedbackTiming.AFTER_SESSION
                || config.followUpLimit() != 0 || !List.of("独立复练").equals(config.stages())
                || config.collectionId() != null || config.collectionVersionId() != null
                || (config.timeBudgetMinutes() != null && config.timeBudgetMinutes() > 180)
                || !("SAME".equals(repractice.questionMode()) ? "REPLAY" : "MAIN").equals(question.kind())
                || question.rootQuestionId() != null || question.parentQuestionId() != null
                || question.parentAnswerId() != null || question.aiTaskId() != null) throw broken();
        if ("SAME".equals(repractice.questionMode()) && (!Objects.equals(baseline.snapshot().sourceQuestionId(), current.snapshot().sourceQuestionId())
                || !Objects.equals(baseline.snapshot().sourceVersionId(), current.snapshot().sourceVersionId())
                || !baseline.snapshot().content().equals(current.snapshot().content()))) throw broken();
        return new Pair(repractice, baseline, current);
    }

    private SideData side(String owner, ReviewDtos.AnswerLink identity) {
        if (!validIdentity(identity)) throw broken();
        TrainingRows.SessionRow session = training.session(owner, identity.sessionId());
        TrainingRows.QuestionRow question = training.question(owner, identity.sessionId(), identity.questionId());
        TrainingRows.AnswerRow answer = training.answer(owner, identity.sessionId(), identity.questionId(), identity.answerId());
        if (session == null || question == null || answer == null) throw missing();
        if (!owner.equals(session.ownerId()) || !identity.sessionId().equals(session.id())
                || !owner.equals(question.ownerId()) || !identity.sessionId().equals(question.sessionId()) || !identity.questionId().equals(question.id())
                || !owner.equals(answer.ownerId()) || !identity.sessionId().equals(answer.sessionId())
                || !identity.questionId().equals(answer.questionId()) || !identity.answerId().equals(answer.id())) throw broken();
        if (!TrainingDtos.Status.ENDED.name().equals(session.status())) throw changed();
        evaluations.requireAccess(owner, identity.sessionId(), identity.questionId(), identity.answerId());
        AiDtos.EvaluationInput evaluation = evaluations.input(owner, identity.sessionId(), identity.questionId(), identity.answerId());
        TrainingDtos.QuestionSnapshot snapshot = json.readValue(question.snapshotJson(), TrainingDtos.QuestionSnapshot.class);
        TrainingDtos.SessionConfig config = json.readValue(session.configJson(), TrainingDtos.SessionConfig.class);
        if (snapshot == null || snapshot.content() == null || config == null || session.endedAt() == null
                || !Objects.equals(snapshot.sourceQuestionId(), question.sourceQuestionId())
                || !Objects.equals(snapshot.sourceVersionId(), question.sourceVersionId())
                || !Objects.equals(snapshot.title(), question.title()) || !Objects.equals(snapshot.content().title(), question.title())) throw broken();
        // 结束时刻固定计时快照；读取时间不能进入输入摘要而使同一历史提交反复失效。
        boolean budgetReached = config.timeBudgetMinutes() != null
                && session.observedMillis() + session.unobservedMillis() >= config.timeBudgetMinutes() * 60_000L;
        TrainingDtos.Timing timing = new TrainingDtos.Timing(session.observedMillis() / 1000, session.unobservedMillis() / 1000,
                session.pausedMillis() / 1000, 0, 0, budgetReached, session.endedAt());
        Side value = new Side(identity, answer.versionNumber(), TrainingDtos.Mode.valueOf(session.mode()), config.feedbackTiming(),
                TrainingDtos.AnswerCondition.valueOf(answer.condition()), json.readValue(answer.exposureJson(), TrainingDtos.PriorExposure.class),
                config.timeBudgetMinutes(), timing, evaluation.question(), evaluation.answer());
        List<TrainingDtos.ContextSnapshot> context = List.copyOf(Arrays.asList(json.readValue(session.contextJson(), TrainingDtos.ContextSnapshot[].class)));
        return new SideData(session, question, snapshot, context, evaluation.standard(), value);
    }

    private Map<String, RepracticeDtos.SourceSnapshot> fixedSources(String owner, Pair pair) {
        List<RepracticeRows.SourceRow> rows = repractices.sources(owner, pair.repractice().id());
        if (rows.size() > 10) throw broken();
        Map<String, RepracticeDtos.SourceSnapshot> fixed = new HashMap<>();
        for (int index = 0; index < rows.size(); index++) {
            RepracticeRows.SourceRow row = rows.get(index);
            RepracticeDtos.SourceSnapshot snapshot = json.readValue(row.snapshotJson(), RepracticeDtos.SourceSnapshot.class);
            if (!owner.equals(row.ownerId()) || !pair.repractice().id().equals(row.repracticeId()) || row.position() != index
                    || snapshot == null || snapshot.source() == null || !uuid(row.sourceId()) || !uuid(row.actionId())
                    || row.actionRevision() < 0 || row.actionRevision() > 1000 || !row.sourceId().equals(snapshot.sourceId())
                    || !row.actionId().equals(snapshot.actionId()) || row.actionRevision() != snapshot.actionRevision()
                    || !row.sourceId().equals(snapshot.source().id()) || !RepracticeRules.matchesBaseline(pair.repractice(), snapshot.source())
                    || snapshot.source().tracking() != ReviewDtos.Tracking.TRACKING
                    || !text(snapshot.source().verification(), 2000, true)
                    || fixed.put(row.sourceId(), snapshot) != null) throw broken();
        }
        return fixed;
    }

    private boolean sameSource(String owner, ReviewRows.SourceRow source, RepracticeDtos.SourceSnapshot snapshot) {
        return owner.equals(source.ownerId()) && source.id().equals(snapshot.sourceId())
                && RepracticeRules.sameSource(source, snapshot.source(), json);
    }

    private RepracticeRows.ComparisonRow requireComparison(String owner, String id) {
        requireUuid(owner); requireUuid(id);
        RepracticeRows.ComparisonRow row = repractices.comparison(owner, id);
        if (row == null) throw missing();
        if (!owner.equals(row.ownerId()) || !id.equals(row.id())) throw broken();
        return row;
    }

    private static Differences differences(Pair pair) {
        SideData baseline = pair.baseline(), current = pair.current();
        Side left = baseline.value(), right = current.value();
        boolean question = !baseline.snapshot().content().equals(current.snapshot().content())
                || !Objects.equals(baseline.snapshot().sourceQuestionId(), current.snapshot().sourceQuestionId())
                || !Objects.equals(baseline.snapshot().sourceVersionId(), current.snapshot().sourceVersionId());
        return new Differences(question, !Objects.equals(baseline.standard(), current.standard()),
                !baseline.context().equals(current.context()), left.mode() != right.mode() || left.feedbackTiming() != right.feedbackTiming(),
                left.answerCondition() != right.answerCondition(), !left.priorExposure().equals(right.priorExposure()),
                !Objects.equals(left.timeBudgetMinutes(), right.timeBudgetMinutes()), sessionTimeChanged(left, right));
    }

    public static boolean valid(Input input, Checks checks) {
        if (!validInput(input) || checks == null || !input.comparisonId().equals(checks.comparisonId())
                || !safe(checks.baselineGeneration()) || !safe(checks.currentGeneration())
                || checks.sources() == null || checks.sources().size() != input.sources().size()) return false;
        Map<String, Long> revisions = new HashMap<>();
        for (int index = 0; index < checks.sources().size(); index++) {
            SourceCheck check = checks.sources().get(index);
            if (check == null || !input.sources().get(index).sourceId().equals(check.sourceId()) || !uuid(check.actionId())
                    || check.actionRevision() < 0 || check.actionRevision() > 1000) return false;
            Long previous = revisions.putIfAbsent(check.actionId(), check.actionRevision());
            if (previous != null && previous != check.actionRevision()) return false;
        }
        return true;
    }

    private static boolean validInput(Input input) {
        if (input == null || !uuid(input.comparisonId()) || !validSide(input.baseline()) || !validSide(input.current())
                || input.baseline().identity().sessionId().equals(input.current().identity().sessionId())
                || input.baseline().identity().questionId().equals(input.current().identity().questionId())
                || input.baseline().identity().answerId().equals(input.current().identity().answerId())
                || input.current().mode() != TrainingDtos.Mode.FOCUSED
                || input.current().feedbackTiming() != TrainingDtos.FeedbackTiming.AFTER_SESSION
                || (input.current().timeBudgetMinutes() != null && input.current().timeBudgetMinutes() > 180)
                || input.sources() == null || input.sources().isEmpty() || input.sources().size() > 10 || input.differences() == null) return false;
        String previous = null;
        for (Source source : input.sources()) {
            if (source == null || !uuid(source.sourceId()) || !text(source.verification(), 2000, true)
                    || (previous != null && previous.compareTo(source.sourceId()) >= 0)) return false;
            previous = source.sourceId();
        }
        Side left = input.baseline(), right = input.current();
        Differences differences = input.differences();
        return (differences.questionChanged() || left.question().equals(right.question()))
                && differences.modeChanged() == (left.mode() != right.mode() || left.feedbackTiming() != right.feedbackTiming())
                && differences.assistanceChanged() == (left.answerCondition() != right.answerCondition())
                && differences.exposureChanged() == !left.priorExposure().equals(right.priorExposure())
                && differences.timeBudgetChanged() == !Objects.equals(left.timeBudgetMinutes(), right.timeBudgetMinutes())
                && differences.sessionTimeChanged() == sessionTimeChanged(left, right);
    }

    private static boolean validSide(Side side) {
        if (side == null || !validIdentity(side.identity()) || side.answerNumber() < 1 || side.answerNumber() > 100
                || side.mode() == null || side.feedbackTiming() == null || side.answerCondition() == null
                || ((side.mode() == TrainingDtos.Mode.LEARNING) != (side.answerCondition() == TrainingDtos.AnswerCondition.LEARNING))
                || (side.timeBudgetMinutes() != null && (side.timeBudgetMinutes() < 1 || side.timeBudgetMinutes() > 240))) return false;
        TrainingDtos.PriorExposure exposure = side.priorExposure();
        TrainingDtos.Timing timing = side.timing();
        if (exposure == null || !safe(exposure.authoredCount()) || !safe(exposure.referenceViewCount())
                || exposure.evaluationAccessCount() == null || !safe(exposure.evaluationAccessCount()) || timing == null
                || !safe(timing.observedSeconds()) || !safe(timing.unobservedSeconds()) || !safe(timing.pausedSeconds())
                || timing.pendingUnknownSeconds() != 0 || timing.pendingPausedSeconds() != 0 || timing.asOf() == null
                || (side.timeBudgetMinutes() == null && timing.budgetReached())) return false;
        AiDtos.FollowUpQuestion question = side.question();
        if (question == null || question.type() == null || !text(question.title(), 200, true)
                || !text(question.prompt(), 10000, true) || !text(question.conditions(), 5000, false)
                || !text(question.technology(), 2000, false) || !text(question.scenario(), 2000, false)) return false;
        AiDtos.FollowUpAnswer answer = side.answer();
        if (answer == null || !text(answer.text(), 20000, true) || answer.nodes() == null || answer.nodes().size() > 80
                || answer.edges() == null || answer.edges().size() > 160) return false;
        Set<String> ids = new HashSet<>(), nodes = new HashSet<>();
        for (AiDtos.FollowUpNode node : answer.nodes()) {
            if (node == null || !uuid(node.id()) || !ids.add(node.id()) || !text(node.label(), 200, true)
                    || !text(node.description(), 5000, false)) return false;
            nodes.add(node.id());
        }
        for (AiDtos.FollowUpEdge edge : answer.edges()) {
            if (edge == null || !uuid(edge.id()) || !ids.add(edge.id()) || !nodes.contains(edge.source())
                    || !nodes.contains(edge.target()) || !text(edge.label(), 200, false)) return false;
        }
        return true;
    }

    private static boolean sessionTimeChanged(Side left, Side right) {
        return left.timing().observedSeconds() != right.timing().observedSeconds()
                || left.timing().unobservedSeconds() != right.timing().unobservedSeconds()
                || left.timing().pausedSeconds() != right.timing().pausedSeconds();
    }
    private static boolean validIdentity(ReviewDtos.AnswerLink value) {
        return value != null && uuid(value.sessionId()) && uuid(value.questionId()) && uuid(value.answerId());
    }
    private static boolean safe(long value) { return value >= 0 && value <= MAX_SAFE; }
    private static boolean text(String value, int maximum, boolean nonblank) {
        return value != null && value.codePointCount(0, value.length()) <= maximum && (!nonblank || !value.isBlank())
                && value.indexOf('\0') < 0 && value.codePoints().noneMatch(point -> point >= 0xD800 && point <= 0xDFFF);
    }
    private static boolean uuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException exception) { return false; }
    }
    private static void requireUuid(String value) { if (!uuid(value)) throw invalid(); }
    private static ApiException invalid() { return new ApiException(400, "INVALID_REQUEST", "请选择 1 至 10 个不同的比较来源，标识须为标准小写 UUID。"); }
    private static ApiException missing() { return new ApiException(404, "AI_COMPARISON_NOT_FOUND", "比较、确切提交或所选来源不存在或不可访问。"); }
    private static ApiException changed() { return new ApiException(409, "AI_SOURCE_CHANGED", "比较条件或所选动作来源已变化，请重新核对并预览。"); }
    private static IllegalStateException broken() { return new IllegalStateException("AI 比较的确切提交或固定来源绑定不一致"); }

    private record Pair(RepracticeRows.RepracticeRow repractice, SideData baseline, SideData current) {}
    private record SideData(TrainingRows.SessionRow session, TrainingRows.QuestionRow question,
                            TrainingDtos.QuestionSnapshot snapshot, List<TrainingDtos.ContextSnapshot> context,
                            AiDtos.EvaluationStandard standard, Side value) {}
}
