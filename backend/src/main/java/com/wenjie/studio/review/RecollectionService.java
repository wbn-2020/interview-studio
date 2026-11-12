package com.wenjie.studio.review;

import com.wenjie.studio.bank.QuestionDtos;
import com.wenjie.studio.bank.QuestionMapper;
import com.wenjie.studio.bank.QuestionService;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import com.wenjie.studio.schedule.ScheduleDtos;
import com.wenjie.studio.schedule.ScheduleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.wenjie.studio.review.RecollectionDtos.*;
import static com.wenjie.studio.review.RecollectionRows.*;
import static com.wenjie.studio.review.RecollectionRules.*;
import static com.wenjie.studio.review.ReviewRules.page;
import static com.wenjie.studio.review.ReviewRules.text;
import static com.wenjie.studio.review.ReviewRules.uuid;

@Service
public class RecollectionService {
    private final RecollectionMapper mapper;
    private final ScheduleMapper schedules;
    private final QuestionMapper questionBank;
    private final QuestionService questions;
    private final IdempotencyService commands;
    private final JsonMapper json;

    public RecollectionService(RecollectionMapper mapper, ScheduleMapper schedules, QuestionMapper questionBank,
                               QuestionService questions, IdempotencyService commands, JsonMapper json) {
        this.mapper = mapper;
        this.schedules = schedules;
        this.questionBank = questionBank;
        this.questions = questions;
        this.commands = commands;
        this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page list(String owner, Status status, String interviewId, String query, String cursor, int limit) {
        uuid(owner); page(limit, 50);
        if (status == null) throw invalid("请选择回忆状态。");
        if (interviewId != null) uuid(interviewId);
        if (cursor != null) uuid(cursor);
        List<SummaryRow> rows = mapper.list(owner, status.name(), interviewId, pattern(query), cursor, limit + 1);
        List<Summary> items = rows.stream().limit(limit).map(row -> new Summary(row.id(), row.title(),
                BaselineKind.valueOf(row.baselineKind()), Status.valueOf(row.status()), row.revision(),
                row.versionId(), row.versionNumber(), row.interviewId(), row.interviewTitle(), row.updatedAt())).toList();
        return new Page(items, rows.size() > limit ? items.getLast().id() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Recollection detail(String owner, String id) { return view(owner, requireRecollection(owner, id)); }

    public Recollection create(String owner, String key, Create request) {
        if (request == null) throw invalid("回忆请求不能为空。");
        content(request.content()); interview(request.interview());
        return commands.execute(owner, key, "POST /api/v1/review/recollections", request, Recollection.class, () -> {
            String id = newId();
            Instant now = now();
            VersionRow version = newVersion(owner, id, 1, request.content(), request.interview(), now);
            RecollectionRow item = new RecollectionRow(id, owner, Status.ACTIVE.name(), 0, version.id(), 1, now, now);
            // 身份、不可变版本和回执共用 owner 命令事务，当前指针不会先于版本单独提交。
            mapper.insertRecollection(item);
            mapper.insertVersion(version);
            return view(owner, item);
        });
    }

    public Recollection save(String owner, String id, String key, Save request) {
        uuid(id); if (request == null) throw invalid("回忆请求不能为空。");
        revision(request.expectedRevision()); content(request.content()); interview(request.interview());
        return commands.execute(owner, key, "PUT /api/v1/review/recollections/" + id, request, Recollection.class, () -> {
            RecollectionRow before = requireRecollection(owner, id);
            expect(before, request.expectedRevision()); active(before);
            if (before.revision() >= MAX_REVISION || before.versionNumber() >= MAX_VERSION) throw limit();
            Instant now = now();
            VersionRow version = newVersion(owner, id, before.versionNumber() + 1, request.content(), request.interview(), now);
            mapper.insertVersion(version);
            changed(mapper.saveContent(owner, id, before.revision(), version.id(), version.versionNumber(), now));
            return view(owner, requireRecollection(owner, id));
        });
    }

    public Recollection status(String owner, String id, String key, ChangeStatus request, Status status) {
        uuid(id); if (request == null || status == null) throw invalid("回忆状态请求不能为空。");
        revision(request.expectedRevision());
        String path = "POST /api/v1/review/recollections/" + id + (status == Status.ARCHIVED ? "/archive" : "/restore");
        return commands.execute(owner, key, path, request, Recollection.class, () -> {
            RecollectionRow before = requireRecollection(owner, id);
            expect(before, request.expectedRevision());
            // 同状态也先核对修订；幂等空操作不消耗修订，更不制造一份相同内容版本。
            if (status.name().equals(before.status())) return view(owner, before);
            if (before.revision() >= MAX_REVISION) throw limit();
            changed(mapper.changeStatus(owner, id, before.revision(), status.name(), now()));
            return view(owner, requireRecollection(owner, id));
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public VersionPage versions(String owner, String id, Integer before, int limit) {
        requireRecollection(owner, id); page(limit, 20);
        if (before != null && (before < 1 || before > MAX_VERSION + 1)) throw invalid("回忆版本游标须为 1 至 1001 的整数。");
        List<VersionSummary> rows = mapper.versions(owner, id, before, limit + 1);
        List<VersionSummary> items = rows.stream().limit(limit).toList();
        return new VersionPage(items, rows.size() > limit ? items.getLast().number() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Version version(String owner, String id, String versionId) {
        requireRecollection(owner, id);
        return versionView(requireVersion(owner, id, versionId));
    }

    public QuestionAdoption createQuestion(String owner, String id, String key, CreateQuestion request) {
        uuid(id); if (request == null || request.content() == null) throw invalid("请填写要整理成题目的内容。");
        revision(request.expectedRevision()); uuid(request.versionId());
        return commands.execute(owner, key, "POST /api/v1/review/recollections/" + id + "/question", request, QuestionAdoption.class, () -> {
            RecollectionRow item = requireRecollection(owner, id);
            requireVersion(owner, id, request.versionId());
            expect(item, request.expectedRevision()); active(item);
            if (!item.currentVersionId().equals(request.versionId())) {
                throw new ApiException(409, "REVISION_CONFLICT", "请选择当前回忆版本，保留输入并重新核对。", item.revision());
            }
            QuestionRow existing = mapper.questionByRecollection(owner, id, request.versionId());
            if (existing != null) {
                if (!id.equals(existing.recollectionId()) || !request.versionId().equals(existing.recollectionVersionId())) throw broken();
                requireQuestionLink(owner, existing);
                throw new ApiException(409, "RECOLLECTION_QUESTION_EXISTS", "此回忆版本已整理为题目，请打开该版本的已有题目入口。", item.revision());
            }
            // 只采用用户提交的完整题目；回忆回答、反馈和缺口不被自动填进参考答案或检查点。
            QuestionDtos.Question question = questions.createWithinCommand(owner, request.content());
            if (question == null || question.currentVersion() == null || question.currentVersion().number() != 1
                    || question.currentVersion().standardStatus() != QuestionDtos.StandardStatus.PERSONAL_UNREVIEWED) throw broken();
            QuestionRow link = new QuestionRow(owner, id, request.versionId(), question.id(), question.currentVersion().id(), now());
            mapper.insertQuestion(link);
            return new QuestionAdoption(question, questionLink(link));
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public QuestionLookup question(String owner, String id, String versionId) {
        requireRecollection(owner, id); requireVersion(owner, id, versionId);
        QuestionRow row = mapper.questionByRecollection(owner, id, versionId);
        if (row == null) return new QuestionLookup(null);
        if (!id.equals(row.recollectionId()) || !versionId.equals(row.recollectionVersionId())) throw broken();
        requireQuestionLink(owner, row);
        return new QuestionLookup(questionLink(row));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public QuestionOrigin questionSource(String owner, String questionId, String versionId) {
        requireQuestionVersion(owner, questionId, versionId);
        QuestionRow link = mapper.questionSource(owner, questionId, versionId);
        if (link == null) return new QuestionOrigin(null);
        if (!owner.equals(link.ownerId()) || !questionId.equals(link.questionId()) || !versionId.equals(link.questionVersionId())) throw broken();
        RecollectionRow item = requireRecollection(owner, link.recollectionId());
        Version version = versionView(requireVersion(owner, item.id(), link.recollectionVersionId()));
        // 只更新来源提示，仍返回采用时确切版本；归档或补记不覆盖旧题和旧回忆。
        SourceState state = Status.ARCHIVED.name().equals(item.status()) ? SourceState.ARCHIVED
                : item.currentVersionId().equals(version.id()) ? SourceState.CURRENT : SourceState.SUPERSEDED;
        return new QuestionOrigin(new QuestionSource(questionLink(link), version, state));
    }

    private RecollectionRow requireRecollection(String owner, String id) {
        RecollectionRow row = mapper.recollection(uuid(owner), uuid(id));
        if (row == null) throw missing();
        if (!owner.equals(row.ownerId()) || !id.equals(row.id()) || !Set.of("ACTIVE", "ARCHIVED").contains(row.status())
                || row.revision() < 0 || row.revision() > MAX_REVISION || row.versionNumber() < 1
                || row.versionNumber() > MAX_VERSION || row.versionNumber() > row.revision() + 1
                || row.currentVersionId() == null) throw broken();
        return row;
    }

    private VersionRow requireVersion(String owner, String id, String versionId) {
        VersionRow row = mapper.version(uuid(owner), uuid(id), uuid(versionId));
        if (row == null) throw missing();
        if (!owner.equals(row.ownerId()) || !id.equals(row.recollectionId()) || !versionId.equals(row.id())
                || row.versionNumber() < 1 || row.versionNumber() > MAX_VERSION) throw broken();
        return row;
    }

    private Recollection view(String owner, RecollectionRow row) {
        Version version = versionView(requireVersion(owner, row.id(), row.currentVersionId()));
        if (version.number() != row.versionNumber()) throw broken();
        return new Recollection(row.id(), Status.valueOf(row.status()), row.revision(), version, row.createdAt(), row.updatedAt());
    }

    private VersionRow newVersion(String owner, String id, int number, Content content, InterviewLink interview, Instant now) {
        InterviewSnapshot snapshot = resolveInterview(owner, interview);
        return new VersionRow(newId(), owner, id, number, content.title(), content.baselineKind().name(),
                bounded(content, MAX_CONTENT_BYTES, "回忆内容超过 192 KiB，请缩减后保存。"),
                snapshot == null ? null : snapshot.eventId(), snapshot == null ? null : snapshot.revision(),
                snapshot == null ? null : bounded(snapshot, MAX_INTERVIEW_BYTES, "面试安排摘要超过 8 KiB。"), now);
    }

    private InterviewSnapshot resolveInterview(String owner, InterviewLink link) {
        if (link == null) return null;
        String saved = schedules.snapshot(owner, link.eventId(), link.revision());
        if (saved == null) throw missing();
        ScheduleDtos.InterviewEvent event = json.readValue(saved, ScheduleDtos.InterviewEvent.class);
        if (event == null || !link.eventId().equals(event.id()) || link.revision() != event.revision() || event.time() == null) throw broken();
        // 按历史修订白名单投影；地点、会议链接、备注和材料正文不会进入回忆版本。
        InterviewSnapshot snapshot = new InterviewSnapshot(event.id(), event.revision(), event.title(), event.round(),
                event.time().startAt(), event.time().zoneId(), event.status(), event.targetId(), event.targetVersionId());
        validateInterview(snapshot);
        return snapshot;
    }

    private Version versionView(VersionRow row) {
        if (row.contentJson() == null || bytes(row.contentJson()) > MAX_CONTENT_BYTES) throw broken();
        Content content = json.readValue(row.contentJson(), Content.class);
        InterviewSnapshot interview = null;
        try {
            RecollectionRules.content(content);
            if (!Objects.equals(row.title(), content.title()) || !Objects.equals(row.baselineKind(), content.baselineKind().name())) throw broken();
            if (row.interviewId() == null) {
                if (row.interviewRevision() != null || row.interviewJson() != null) throw broken();
            } else {
                if (row.interviewRevision() == null || row.interviewJson() == null || bytes(row.interviewJson()) > MAX_INTERVIEW_BYTES) throw broken();
                interview = json.readValue(row.interviewJson(), InterviewSnapshot.class);
                validateInterview(interview);
                if (!row.interviewId().equals(interview.eventId()) || row.interviewRevision() != interview.revision()) throw broken();
            }
        } catch (ApiException exception) { throw new IllegalStateException("已保存回忆版本的内容不符合约束", exception); }
        return new Version(row.id(), row.versionNumber(), content, interview, row.createdAt());
    }

    private void validateInterview(InterviewSnapshot snapshot) {
        if (snapshot == null || snapshot.status() == null || snapshot.startAt() == null
                || snapshot.revision() < 0 || snapshot.revision() > MAX_INTERVIEW_REVISION
                || ((snapshot.targetId() == null) != (snapshot.targetVersionId() == null))) throw broken();
        uuid(snapshot.eventId()); text(snapshot.title(), 200, true); text(snapshot.round(), 100, false); text(snapshot.zoneId(), 100, true);
        if (snapshot.targetId() != null) { uuid(snapshot.targetId()); uuid(snapshot.targetVersionId()); }
    }

    private void requireQuestionLink(String owner, QuestionRow row) {
        if (!owner.equals(row.ownerId())) throw broken();
        requireQuestionVersion(owner, row.questionId(), row.questionVersionId());
    }

    private void requireQuestionVersion(String owner, String id, String versionId) {
        var version = questionBank.version(uuid(owner), uuid(id), uuid(versionId));
        if (version == null) throw missing();
        if (!owner.equals(version.ownerId()) || !id.equals(version.questionId()) || !versionId.equals(version.id())) throw broken();
    }

    private String bounded(Object value, int maximum, String message) {
        String encoded = json.writeValueAsString(value);
        if (bytes(encoded) > maximum) throw invalid(message);
        return encoded;
    }

    private static QuestionLink questionLink(QuestionRow row) {
        return new QuestionLink(row.recollectionId(), row.recollectionVersionId(), row.questionId(), row.questionVersionId(), row.createdAt());
    }
    private static void expect(RecollectionRow row, long expected) {
        if (row.revision() != expected) throw new ApiException(409, "REVISION_CONFLICT", "回忆已发生变化，请保留输入并核对最新修订。", row.revision());
    }
    private static void active(RecollectionRow row) {
        if (!Status.ACTIVE.name().equals(row.status())) throw new ApiException(409, "ITEM_ARCHIVED", "请先恢复回忆，再编辑或整理为题目。");
    }
    private static void changed(int count) {
        if (count != 1) throw new ApiException(409, "REVISION_CONFLICT", "回忆已发生变化，请保留输入并核对最新修订。");
    }
    private static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
    private static String newId() { return UUID.randomUUID().toString(); }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static IllegalStateException broken() { return new IllegalStateException("回忆、历史安排或题目来源的确切身份不完整"); }
}
