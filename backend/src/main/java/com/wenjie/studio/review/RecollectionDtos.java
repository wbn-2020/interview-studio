package com.wenjie.studio.review;

import com.wenjie.studio.bank.QuestionDtos;
import com.wenjie.studio.schedule.ScheduleDtos;

import java.time.Instant;
import java.util.List;

public final class RecollectionDtos {
    private RecollectionDtos() {}

    public enum Status { ACTIVE, ARCHIVED }
    public enum BaselineKind { RECOLLECTION, NONE }
    public enum SourceState { CURRENT, SUPERSEDED, ARCHIVED }

    public record InterviewLink(String eventId, Long revision) {}
    public record InterviewSnapshot(String eventId, long revision, String title, String round,
                                    Instant startAt, String zoneId, ScheduleDtos.Status status,
                                    String targetId, String targetVersionId) {}
    public record Content(String title, String question, QuestionDtos.Type type, String conditions,
                          BaselineKind baselineKind, String answerFragment, String feedback,
                          String uncertainty, String gap) {}
    public record Create(InterviewLink interview, Content content) {}
    public record Save(Long expectedRevision, InterviewLink interview, Content content) {}
    public record ChangeStatus(Long expectedRevision) {}
    public record CreateQuestion(Long expectedRevision, String versionId, QuestionDtos.QuestionInput content) {}

    // 回忆始终引用独立版本；补记、改期和归档不能把它变成正式训练提交。
    public record Version(String id, int number, Content content, InterviewSnapshot interview, Instant createdAt) {}
    public record Recollection(String id, Status status, long revision, Version currentVersion,
                               Instant createdAt, Instant updatedAt) {}
    public record Summary(String id, String title, BaselineKind baselineKind, Status status, long revision,
                          String versionId, int versionNumber, String interviewId, String interviewTitle, Instant updatedAt) {}
    public record Page(List<Summary> items, String nextCursor) {}
    public record VersionSummary(String id, int number, String title, Instant createdAt) {}
    public record VersionPage(List<VersionSummary> items, Integer nextBefore) {}
    public record QuestionLink(String recollectionId, String recollectionVersionId,
                               String questionId, String questionVersionId, Instant createdAt) {}
    public record QuestionAdoption(QuestionDtos.Question question, QuestionLink link) {}
    public record QuestionLookup(QuestionLink question) {}
    public record QuestionSource(QuestionLink link, Version recollection, SourceState state) {}
    public record QuestionOrigin(QuestionSource source) {}
}
