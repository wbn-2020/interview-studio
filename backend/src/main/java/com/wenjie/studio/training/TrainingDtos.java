package com.wenjie.studio.training;

import com.wenjie.studio.bank.QuestionDtos;
import com.wenjie.studio.prepare.PreparationDtos;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

public final class TrainingDtos {
    private TrainingDtos() {}

    public enum Mode { LEARNING, FOCUSED, MOCK }
    public enum Status { DRAFT, RUNNING, PAUSED, ENDED }
    public enum FeedbackTiming { AFTER_ANSWER, AFTER_SESSION }
    public enum QuestionStatus { PENDING, ANSWERED, SKIPPED }
    public enum QuestionKind { MAIN, FOLLOW_UP, REPLAY }
    public enum ContextKind { TARGET, PROJECT, FACT }
    public enum LinkKind { KNOWLEDGE, PROJECT, FACT }
    public enum AnswerCondition { LEARNING, BEFORE_ASSISTANCE, ASSISTED }
    public enum EventKind { CREATED, STARTED, PAUSED, RESUMED, ENDED, MATERIAL_ACCESS,
        DRAFT_SAVED, ANSWER_SUBMITTED, SKIPPED, UNSKIPPED, UNOBSERVED_INTERVAL, REFERENCE_VIEW, BOARD_IMPORT, FOLLOW_UP_ADDED }

    public record QuestionSelection(String sourceQuestionId, String sourceVersionId, String stage) {}
    public record ContextLink(ContextKind kind, String itemId, String versionId) {}
    public record CreateSession(String title, Mode mode, FeedbackTiming feedbackTiming,
                                Integer timeBudgetMinutes, Integer followUpLimit, List<String> stages,
                                List<QuestionSelection> questions, List<ContextLink> contextLinks,
                                String collectionId, String collectionVersionId) {}
    public record ChangeState(Long expectedRevision) {}
    public record EndSession(Long expectedRevision, Boolean checklistConfirmed, String expectedChecklistToken) {}
    public record Assistance(Long expectedRevision, String reason) {}
    public record EmptyRequest() {}
    public record QuestionCommand(Long expectedSessionRevision, Long expectedRevision) {}
    public record SaveAnswer(Long expectedSessionRevision, Long expectedRevision, AnswerInput content) {}
    public record ImportBoard(String boardId, Long expectedBoardRevision) {}

    public record DiagramLink(LinkKind kind, String itemId, String versionId) {}
    public record DiagramReference(LinkKind kind, String itemId, String versionId, String title, int number) {}
    public record BoardSource(String boardId, Long revision, String importId) {}
    public record DiagramNodeInput(String id, String label, String description, Double x, Double y, DiagramLink link) {}
    public record DiagramNode(String id, String label, String description, double x, double y, DiagramReference reference) {}
    public record DiagramEdge(String id, String source, String target, String label) {}
    public record DiagramInput(List<DiagramNodeInput> nodes, List<DiagramEdge> edges, BoardSource boardSource) {}
    public record Diagram(List<DiagramNode> nodes, List<DiagramEdge> edges, BoardSource boardSource) {}
    public record AnswerInput(String text, DiagramInput diagram) {}
    public record AnswerContent(String text, Diagram diagram) {}
    public record BoardImportCandidate(String boardId, long revision, DiagramInput diagram) {}

    public record SessionConfig(FeedbackTiming feedbackTiming, Integer timeBudgetMinutes, int followUpLimit,
                                 List<String> stages, String collectionId, String collectionVersionId) {}
    public record ContextSnapshot(ContextKind kind, String itemId, String versionId, String title,
                                   int number, JsonNode content) {}
    public record ContextCheck(ContextKind kind, String itemId, String versionId, String title, int number,
                                PreparationDtos.SourceState state, Long sourceGeneration, Long projectGeneration) {}
    public record QuestionSnapshot(String sourceQuestionId, String sourceVersionId, String title,
                                    int number, String stage, QuestionDtos.QuestionPrompt content,
                                    List<String> warnings) {}
    public record SourceSnapshot(List<ContextSnapshot> context, List<QuestionSnapshot> questions) {}
    public record PriorExposure(long authoredCount, long referenceViewCount, Instant lastExposureAt, Long evaluationAccessCount) {
        public PriorExposure { if (evaluationAccessCount == null) evaluationAccessCount = 0L; }
    }
    public record Timing(long observedSeconds, long unobservedSeconds, long pausedSeconds,
                          long pendingUnknownSeconds, long pendingPausedSeconds, boolean budgetReached, Instant asOf) {}
    public record EndChecklist(int answered, int skipped, int unanswered, int unsubmittedDrafts) {}
    public record SessionSummary(String id, String title, Mode mode, Status status, long revision,
                                  boolean materialAccess, int questionCount, Instant createdAt, Instant updatedAt) {}
    public record Active(SessionSummary session) {}
    public record SessionPage(List<SessionSummary> items, String nextCursor) {}
    public record QuestionSummary(String id, String sourceQuestionId, String sourceVersionId, String title,
                                   String stage, QuestionStatus status, long revision, boolean hasDraft,
                                   int answerCount, List<String> warnings, PriorExposure priorExposure,
                                   QuestionKind kind, FollowUpOrigin followUp) {
        public QuestionSummary { if (kind == null) kind = QuestionKind.MAIN; }
    }
    public record Session(String id, String title, Mode mode, Status status, long revision, boolean materialAccess,
                           Instant createdAt, Instant updatedAt, Instant startedAt, Instant endedAt,
                           SessionConfig config, List<ContextSnapshot> context, List<QuestionSummary> questions,
                           Timing timing, EndChecklist endChecklist, String checklistToken, Long generation,
                           List<ContextCheck> contextChecks) {
        // 旧回执的来源检查保持 null 表示尚未核对；不能补空数组冒充当前没有失效背景。
        public Session { if (generation == null) generation = 0L; }
    }
    public record Draft(AnswerContent content, Instant savedAt) {}
    public record Answer(String id, String questionId, int number, Instant createdAt, AnswerContent content,
                          AnswerCondition condition, int assistanceEventSequence, PriorExposure priorExposure) {}
    public record Question(String id, String sessionId, long sessionRevision, long revision, QuestionStatus status,
                            QuestionSnapshot snapshot, Draft draft, Answer latestAnswer, PriorExposure priorExposure,
                            QuestionKind kind, FollowUpOrigin followUp) {
        public Question { if (kind == null) kind = QuestionKind.MAIN; }
    }
    public record FollowUpOrigin(String rootQuestionId, String parentQuestionId, String parentAnswerId, String taskId) {}
    public record AnswerSummary(String id, int number, Instant createdAt, AnswerCondition condition) {}
    public record AnswerPage(List<AnswerSummary> items, Integer nextCursor) {}
    public record Event(String id, int sequence, EventKind kind, String questionId, String detail,
                         Long durationSeconds, Instant createdAt) {}
    public record EventPage(List<Event> items, Integer nextCursor) {}
}
