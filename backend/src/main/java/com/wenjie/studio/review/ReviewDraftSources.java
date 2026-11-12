package com.wenjie.studio.review;

import com.wenjie.studio.ai.AiDtos;
import com.wenjie.studio.bank.QuestionDtos;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.training.TrainingDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.wenjie.studio.ai.ReviewDraftDtos.*;

/** 复盘来源只固定用户选区与必要限定；不依赖 ReviewService，避免 AI 服务循环依赖。 */
@Service
@Transactional(propagation = Propagation.MANDATORY, readOnly = true, noRollbackFor = RuntimeException.class)
public class ReviewDraftSources {
    private static final Set<Field> FEEDBACK_FIELDS = Set.of(Field.OBSERVATION, Field.CONDITION, Field.BASIS, Field.LIMITATION, Field.SUGGESTION);
    private static final Set<Field> RECOLLECTION_FIELDS = Set.of(Field.QUESTION, Field.CONDITIONS, Field.ANSWER_FRAGMENT, Field.FEEDBACK, Field.UNCERTAINTY, Field.GAP);
    private static final Set<String> SOURCE_ERRORS = Set.of("AI_REVIEW_SOURCE_NOT_FOUND", "AI_SOURCE_CHANGED",
            "AI_EVALUATION_ACCESS_REQUIRED", "AI_EARLY_FEEDBACK_CONFIRMATION_REQUIRED");
    private final ReviewDraftSourceMapper mapper;
    private final JsonMapper json;

    public ReviewDraftSources(ReviewDraftSourceMapper mapper, JsonMapper json) {
        this.mapper = mapper;
        this.json = json.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    public FeedbackText readFeedbackSource(String owner, String feedbackId, int revision) {
        requireOwner(owner);
        if (!uuid(feedbackId) || !range(revision, 1, 100)) throw invalid();
        var row = feedback(owner, feedbackId, revision);
        var context = access(owner, answer(row));
        // 选定旧修订不能回落到当前正文；片段选择前仍须核对评价时机与查看授权。
        if (row.revision() != row.currentRevision()) throw changed();
        int decisionRevision = row.decisionRevision() == null ? 0 : row.decisionRevision();
        AiDtos.FeedbackChoice choice = row.decisionChoice() == null ? null : enumeration(row.decisionChoice(), AiDtos.FeedbackChoice.class);
        return new FeedbackText(enumeration(row.kind(), Kind.class), row.id(), row.revision(), answer(row),
                context.answerNumber(), context.answerCondition(), enumeration(row.dimension(), QuestionDtos.Dimension.class),
                enumeration(row.coverage(), AiDtos.EvaluationCoverage.class), decisionRevision, choice,
                row.observation(), row.condition(), row.basis(), row.limitation(), row.suggestion());
    }

    public void requireAnswerAccess(String owner, ReviewDtos.AnswerLink answer) {
        requireOwner(owner);
        if (!validAnswer(answer)) throw invalid();
        // 列表与历史回执也要检查本次反馈查看条件，不为权限校验加载反馈正文。
        access(owner, answer);
    }

    public Input input(String owner, SourceSelection selection) {
        requireOwner(owner); selection(selection);
        Input result;
        if (selection.kind() == Kind.RECOLLECTION) {
            RecollectionSelection selected = selection.recollection();
            LoadedRecollection loaded = recollection(owner, selected.recollectionId(), selected.versionId());
            var row = loaded.row();
            if (!"ACTIVE".equals(row.status()) || !row.currentVersionId().equals(row.versionId())
                    || row.sourceRevision() != selected.expectedRevision()) throw changed();
            result = recollectionInput(loaded, selected.fragments());
        } else {
            FeedbackSelection selected = selection.feedback();
            var row = feedback(owner, selected.feedbackId(), selected.feedbackRevision());
            var access = access(owner, answer(row));
            if (row.revision() != row.currentRevision() || !selection.kind().name().equals(row.kind())) throw changed();
            result = feedbackInput(selection.kind(), row, access, selected.fragments());
        }
        if (!valid(result)) throw broken();
        return result;
    }

    public boolean current(String owner, Input input) {
        requireOwner(owner);
        if (!valid(input)) return false;
        try {
            Input rebuilt = input(owner, selection(input));
            if (input.feedback() != null && rebuilt.feedback().decisionRevision() < input.feedback().decisionRevision()) throw broken();
            return input.equals(rebuilt);
        }
        catch (ApiException exception) { if (SOURCE_ERRORS.contains(exception.code())) return false; throw exception; }
    }

    public void requireAccess(String owner, SourceSelection selection) {
        requireOwner(owner); selection(selection);
        if (selection.kind() == Kind.RECOLLECTION) {
            recollection(owner, selection.recollection().recollectionId(), selection.recollection().versionId());
        } else {
            var row = feedback(owner, selection.feedback().feedbackId(), selection.feedback().feedbackRevision());
            if (!selection.kind().name().equals(row.kind())) throw changed();
            access(owner, answer(row));
        }
    }

    public void requireAccess(String owner, Input input) {
        requireOwner(owner);
        if (!valid(input)) throw broken();
        if (input.kind() == Kind.RECOLLECTION) {
            recollection(owner, input.recollection().recollectionId(), input.recollection().versionId());
        } else {
            var fixed = input.feedback();
            var row = feedback(owner, fixed.feedbackId(), fixed.feedbackRevision());
            if (!input.kind().name().equals(row.kind()) || !fixed.answer().equals(answer(row))) throw broken();
            var access = access(owner, fixed.answer());
            if (fixed.answerNumber() != access.answerNumber() || !fixed.answerCondition().equals(access.answerCondition())) throw broken();
        }
    }

    public AiDtos.OriginState state(String owner, Input input) {
        requireOwner(owner);
        if (!valid(input)) throw broken();
        try {
            if (input.kind() == Kind.RECOLLECTION) {
                RecollectionInput fixed = input.recollection();
                LoadedRecollection loaded = recollection(owner, fixed.recollectionId(), fixed.versionId());
                var row = loaded.row();
                if (row.sourceRevision() < fixed.sourceRevision()) throw broken();
                if (!"ACTIVE".equals(row.status())) return AiDtos.OriginState.ARCHIVED;
                if (!row.versionId().equals(row.currentVersionId())) return AiDtos.OriginState.SUPERSEDED;
                if (row.sourceRevision() != fixed.sourceRevision()) return AiDtos.OriginState.REVIEW_REQUIRED;
                return input.equals(recollectionInput(loaded, fixed.fragments())) ? AiDtos.OriginState.CURRENT : AiDtos.OriginState.REVIEW_REQUIRED;
            }
            FeedbackInput fixed = input.feedback();
            var row = feedback(owner, fixed.feedbackId(), fixed.feedbackRevision());
            var access = access(owner, answer(row));
            if (!input.kind().name().equals(row.kind()) || !fixed.answer().equals(answer(row))) throw broken();
            if ((row.decisionRevision() == null ? 0 : row.decisionRevision()) < fixed.decisionRevision()) throw broken();
            if (row.currentRevision() != fixed.feedbackRevision()) return AiDtos.OriginState.SUPERSEDED;
            return input.equals(feedbackInput(input.kind(), row, access, fixed.fragments()))
                    ? AiDtos.OriginState.CURRENT : AiDtos.OriginState.REVIEW_REQUIRED;
        } catch (ApiException exception) {
            // 来源状态不能掩盖原答权限错误；仅确切来源缺失或正常变化可显示历史状态。
            if ("AI_REVIEW_SOURCE_NOT_FOUND".equals(exception.code())) return AiDtos.OriginState.UNAVAILABLE;
            if ("AI_SOURCE_CHANGED".equals(exception.code())) return AiDtos.OriginState.REVIEW_REQUIRED;
            throw exception;
        }
    }

    public Origin origin(String owner, Input input) {
        if (!valid(input)) throw broken();
        AiDtos.OriginState state = state(owner, input);
        if (input.kind() == Kind.RECOLLECTION) {
            var fixed = input.recollection();
            return new Origin(input.kind(), null, new RecollectionOrigin(fixed.recollectionId(), fixed.versionId(), fixed.versionNumber(),
                    fixed.sourceRevision(), fixed.title(), fixed.baselineKind()), state);
        }
        var fixed = input.feedback();
        return new Origin(input.kind(), new FeedbackOrigin(fixed.feedbackId(), fixed.feedbackRevision(), fixed.answer(), fixed.answerNumber(),
                fixed.decisionRevision(), fixed.decisionChoice()), null, state);
    }

    private Input feedbackInput(Kind kind, ReviewDraftSourceRows.Feedback row, ReviewDraftSourceRows.Access access, List<Fragment> fragments) {
        match(fragments, field -> switch (field) {
            case OBSERVATION -> row.observation(); case CONDITION -> row.condition(); case BASIS -> row.basis();
            case LIMITATION -> row.limitation(); case SUGGESTION -> row.suggestion(); default -> null;
        });
        int decisionRevision = row.decisionRevision() == null ? 0 : row.decisionRevision();
        AiDtos.FeedbackChoice choice = row.decisionChoice() == null ? null : enumeration(row.decisionChoice(), AiDtos.FeedbackChoice.class);
        return new Input(kind, new FeedbackInput(row.id(), row.revision(), answer(row), access.answerNumber(), access.answerCondition(),
                enumeration(row.dimension(), QuestionDtos.Dimension.class), enumeration(row.coverage(), AiDtos.EvaluationCoverage.class),
                decisionRevision, choice, row.condition(), row.basis(), row.limitation(), List.copyOf(fragments)), null);
    }

    private Input recollectionInput(LoadedRecollection loaded, List<Fragment> fragments) {
        var row = loaded.row(); var content = loaded.content();
        if (content.baselineKind() == RecollectionDtos.BaselineKind.NONE && fragments.stream().anyMatch(item -> item.field() == Field.ANSWER_FRAGMENT)) throw changed();
        match(fragments, field -> switch (field) {
            case QUESTION -> content.question(); case CONDITIONS -> content.conditions(); case ANSWER_FRAGMENT -> content.answerFragment();
            case FEEDBACK -> content.feedback(); case UNCERTAINTY -> content.uncertainty(); case GAP -> content.gap(); default -> null;
        });
        return new Input(Kind.RECOLLECTION, null, new RecollectionInput(row.id(), row.versionId(), row.versionNumber(), row.sourceRevision(),
                content.title(), content.type(), content.baselineKind(), content.uncertainty(), List.copyOf(fragments)));
    }

    private ReviewDraftSourceRows.Feedback feedback(String owner, String id, int revision) {
        var row = mapper.feedback(owner, id, revision);
        if (row == null) throw missing();
        if (!owner.equals(row.ownerId()) || !id.equals(row.id()) || revision != row.revision() || !Boolean.TRUE.equals(row.metadataValid())
                || !range(row.currentRevision(), revision, 100) || !Objects.equals(row.actualHeadRevision(), row.currentRevision())
                || !validAnswer(answer(row))
                || !Set.of("SELF_CHECK", "EXTERNAL_FEEDBACK").contains(row.kind())
                || !codePoints(row.observation(), 2000, true) || !codePoints(row.condition(), 1000, false)
                || !codePoints(row.basis(), 1000, true) || !codePoints(row.limitation(), 1000, true)
                || !codePoints(row.suggestion(), 1000, true)) throw broken();
        enumeration(row.dimension(), QuestionDtos.Dimension.class); enumeration(row.coverage(), AiDtos.EvaluationCoverage.class);
        if (row.decisionRevision() == null) {
            if (row.decisionId() != null || row.decisionChoice() != null) throw broken();
        } else {
            if (!uuid(row.decisionId()) || !range(row.decisionRevision(), 1, 20)) throw broken();
            enumeration(row.decisionChoice(), AiDtos.FeedbackChoice.class);
        }
        return row;
    }

    private LoadedRecollection recollection(String owner, String id, String versionId) {
        var row = mapper.recollection(owner, id, versionId);
        if (row == null) throw missing();
        if (!owner.equals(row.ownerId()) || !id.equals(row.id()) || !versionId.equals(row.versionId())
                || !range(row.sourceRevision(), 0, 1000) || !range(row.versionNumber(), 1, 1000)
                || !range(row.headVersionNumber(), row.versionNumber(), 1000)
                || row.headVersionNumber() > row.sourceRevision() + 1 || !uuid(row.currentVersionId())
                || !Objects.equals(row.actualHeadVersionNumber(), row.headVersionNumber())
                || row.currentVersionId().equals(row.versionId()) != (row.versionNumber() == row.headVersionNumber())
                || !Set.of("ACTIVE", "ARCHIVED").contains(row.status()) || row.contentJson() == null
                || row.contentJson().getBytes(StandardCharsets.UTF_8).length > RecollectionRules.MAX_CONTENT_BYTES) throw broken();
        RecollectionDtos.Content content;
        try {
            content = json.readValue(row.contentJson(), RecollectionDtos.Content.class);
            RecollectionRules.content(content);
            if (!content.title().equals(row.title()) || !content.baselineKind().name().equals(row.baselineKind())) throw broken();
        } catch (RuntimeException exception) { throw new IllegalStateException("已保存回忆正文或确切版本元数据无效", exception); }
        return new LoadedRecollection(row, content);
    }

    private ReviewDraftSourceRows.Access access(String owner, ReviewDtos.AnswerLink answer) {
        var row = mapper.access(owner, answer.sessionId(), answer.questionId(), answer.answerId());
        if (row == null) throw missing();
        if (!owner.equals(row.ownerId()) || !answer.sessionId().equals(row.sessionId()) || !answer.questionId().equals(row.questionId())
                || !answer.answerId().equals(row.answerId()) || !range(row.answerNumber(), 1, 100) || !Boolean.TRUE.equals(row.configValid())) throw broken();
        var mode = enumeration(row.mode(), TrainingDtos.Mode.class);
        var condition = enumeration(row.answerCondition(), TrainingDtos.AnswerCondition.class);
        var timing = enumeration(row.feedbackTiming(), TrainingDtos.FeedbackTiming.class);
        if ((mode == TrainingDtos.Mode.LEARNING) != (condition == TrainingDtos.AnswerCondition.LEARNING)) throw broken();
        if (!Set.of("RUNNING", "PAUSED", "ENDED").contains(row.sessionStatus())) throw changed();
        if (row.grantedAt() == null) {
            if (row.earlyFeedback() != null) throw broken();
            throw new ApiException(409, "AI_EVALUATION_ACCESS_REQUIRED", "请先从确切提交确认打开评价区，核对反馈时机与辅助条件。");
        }
        if (row.earlyFeedback() == null) throw broken();
        try { StandardReviewRules.time(row.grantedAt()); }
        catch (ApiException exception) { throw broken(); }
        // 历史回执也可能带出反馈正文，当前反馈时机必须在回执恢复前重新检查。
        if (!"ENDED".equals(row.sessionStatus()) && timing == TrainingDtos.FeedbackTiming.AFTER_SESSION && !row.earlyFeedback()) {
            throw new ApiException(409, "AI_EARLY_FEEDBACK_CONFIRMATION_REQUIRED", "本场约定结束后反馈，请先在原评价入口确认提前查看。");
        }
        return row;
    }

    public static boolean valid(Input input) {
        if (input == null || input.kind() == null) return false;
        if (input.kind() == Kind.RECOLLECTION) {
            var value = input.recollection();
            return input.feedback() == null && value != null && uuid(value.recollectionId()) && uuid(value.versionId())
                    && range(value.versionNumber(), 1, 1000) && range(value.sourceRevision(), 0, 1000)
                    && value.versionNumber() <= value.sourceRevision() + 1 && codePoints(value.title(), 200, true)
                    && value.type() != null && value.baselineKind() != null && codePoints(value.uncertainty(), 2000, true)
                    && fragments(value.fragments(), RECOLLECTION_FIELDS)
                    && value.fragments().stream().filter(item -> item.field() == Field.UNCERTAINTY)
                        .allMatch(item -> matches(value.uncertainty(), item.start(), item.end(), item.quote()))
                    && (value.baselineKind() != RecollectionDtos.BaselineKind.NONE
                        || value.fragments().stream().noneMatch(item -> item.field() == Field.ANSWER_FRAGMENT));
        }
        var value = input.feedback();
        return input.recollection() == null && value != null && uuid(value.feedbackId()) && range(value.feedbackRevision(), 1, 100)
                && validAnswer(value.answer()) && range(value.answerNumber(), 1, 100)
                && value.answerCondition() != null && Set.of("LEARNING", "BEFORE_ASSISTANCE", "ASSISTED").contains(value.answerCondition())
                && value.dimension() != null && value.coverage() != null && range(value.decisionRevision(), 0, 20)
                && (value.decisionRevision() == 0) == (value.decisionChoice() == null)
                && codePoints(value.condition(), 1000, false) && codePoints(value.basis(), 1000, true)
                && codePoints(value.limitation(), 1000, true) && fragments(value.fragments(), FEEDBACK_FIELDS)
                && value.fragments().stream().allMatch(item -> switch (item.field()) {
                    case CONDITION -> matches(value.condition(), item.start(), item.end(), item.quote());
                    case BASIS -> matches(value.basis(), item.start(), item.end(), item.quote());
                    case LIMITATION -> matches(value.limitation(), item.start(), item.end(), item.quote());
                    default -> true;
                });
    }

    public static boolean validCitation(Input input, Citation citation) {
        if (!valid(input) || citation == null || citation.field() == null || !text(citation.quote(), 1000, true)
                || citation.start() < 0 || citation.end() <= citation.start()) return false;
        List<Fragment> selected = input.kind() == Kind.RECOLLECTION ? input.recollection().fragments() : input.feedback().fragments();
        for (Fragment fragment : selected) {
            if (fragment.field() == citation.field() && citation.start() >= fragment.start() && citation.end() <= fragment.end()
                    && matches(fragment.quote(), citation.start() - fragment.start(), citation.end() - fragment.start(), citation.quote())) return true;
        }
        return false;
    }

    private static void selection(SourceSelection selection) {
        if (selection == null || selection.kind() == null) throw invalid();
        if (selection.kind() == Kind.RECOLLECTION) {
            var value = selection.recollection();
            if (selection.feedback() != null || value == null || !uuid(value.recollectionId()) || !uuid(value.versionId())
                    || value.expectedRevision() == null || !range(value.expectedRevision(), 0, 1000)
                    || !fragments(value.fragments(), RECOLLECTION_FIELDS)) throw invalid();
        } else {
            var value = selection.feedback();
            if (selection.recollection() != null || value == null || !uuid(value.feedbackId()) || value.feedbackRevision() == null
                    || !range(value.feedbackRevision(), 1, 100) || !fragments(value.fragments(), FEEDBACK_FIELDS)) throw invalid();
        }
    }

    private static SourceSelection selection(Input input) {
        if (input.kind() == Kind.RECOLLECTION) {
            var fixed = input.recollection();
            return new SourceSelection(input.kind(), null, new RecollectionSelection(fixed.recollectionId(), fixed.versionId(), fixed.sourceRevision(), fixed.fragments()));
        }
        var fixed = input.feedback();
        return new SourceSelection(input.kind(), new FeedbackSelection(fixed.feedbackId(), fixed.feedbackRevision(), fixed.fragments()), null);
    }

    private static boolean fragments(List<Fragment> fragments, Set<Field> allowed) {
        if (fragments == null || fragments.isEmpty() || fragments.size() > 8) return false;
        for (int i = 0; i < fragments.size(); i++) {
            Fragment value = fragments.get(i);
            if (value == null || value.field() == null || !allowed.contains(value.field()) || value.start() == null || value.end() == null
                    || value.start() < 0 || value.end() <= value.start() || !text(value.quote(), 8000, true)
                    || (long) value.end() - value.start() != value.quote().length()) return false;
            for (int j = 0; j < i; j++) {
                Fragment previous = fragments.get(j);
                if (previous.field() == value.field() && previous.start() < value.end() && value.start() < previous.end()) return false;
            }
        }
        return true;
    }

    private static void match(List<Fragment> fragments, java.util.function.Function<Field, String> source) {
        for (Fragment fragment : fragments) if (!matches(source.apply(fragment.field()), fragment.start(), fragment.end(), fragment.quote())) throw changed();
    }
    private static boolean matches(String source, int start, int end, String quote) {
        return source != null && start >= 0 && end > start && end <= source.length()
                && !split(source, start) && !split(source, end) && quote.equals(source.substring(start, end));
    }
    private static boolean split(String source, int position) {
        return position > 0 && position < source.length() && Character.isHighSurrogate(source.charAt(position - 1)) && Character.isLowSurrogate(source.charAt(position));
    }
    private static ReviewDtos.AnswerLink answer(ReviewDraftSourceRows.Feedback row) { return new ReviewDtos.AnswerLink(row.sessionId(), row.questionId(), row.answerId()); }
    private static boolean validAnswer(ReviewDtos.AnswerLink value) { return value != null && uuid(value.sessionId()) && uuid(value.questionId()) && uuid(value.answerId()); }
    private static boolean range(long value, long minimum, long maximum) { return value >= minimum && value <= maximum; }
    private static boolean text(String value, int max, boolean required) { return unicode(value) && value.length() <= max && (!required || !value.isBlank()); }
    private static boolean codePoints(String value, int max, boolean required) { return unicode(value) && value.codePointCount(0, value.length()) <= max && (!required || !value.isBlank()); }
    private static boolean unicode(String value) { return value != null && value.indexOf('\0') < 0 && value.codePoints().noneMatch(point -> point >= 0xD800 && point <= 0xDFFF); }
    private static boolean uuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException exception) { return false; }
    }
    private static void requireOwner(String owner) { if (!uuid(owner)) throw invalid(); }
    private static <E extends Enum<E>> E enumeration(String value, Class<E> type) {
        try { return Enum.valueOf(type, value); }
        catch (IllegalArgumentException | NullPointerException exception) { throw broken(); }
    }
    private static ApiException invalid() { return new ApiException(400, "INVALID_REQUEST", "复盘来源身份、种类或所选片段无效。"); }
    private static ApiException missing() { return new ApiException(404, "AI_REVIEW_SOURCE_NOT_FOUND", "确切反馈、回忆版本或原答不存在或不可访问。"); }
    private static ApiException changed() { return new ApiException(409, "AI_SOURCE_CHANGED", "复盘来源版本、决定、状态或所选片段已变化，请重新预览。"); }
    private static IllegalStateException broken() { return new IllegalStateException("已保存复盘来源或访问元数据不完整"); }
    private record LoadedRecollection(ReviewDraftSourceRows.Recollection row, RecollectionDtos.Content content) {}
}
