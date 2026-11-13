package com.wenjie.studio.bank;

import java.time.Instant;
import java.util.List;

public final class QuestionDtos {
    private QuestionDtos() {}

    public enum Status { ACTIVE, ARCHIVED }
    public enum Type { EXPLAIN, CODE_READING, DEBUG, PROJECT, TRADEOFF, SYSTEM_DESIGN, BEHAVIOR }
    public enum Dimension { EXPLAIN, DEBUG, TRADEOFF, OWNERSHIP, COMMUNICATION, OTHER }
    public enum StandardStatus { PERSONAL_UNREVIEWED }
    public enum ReferenceState { CURRENT, SUPERSEDED, ARCHIVED, REVIEW_REQUIRED }
    public enum ExposureKind { AUTHORED, REFERENCE_VIEW, EVALUATION_ACCESS }

    public record Source(String title, String url, String note) {}
    public record KnowledgeLink(String itemId, String versionId) {}
    public record Checkpoint(String id, String text) {}
    public record QuestionInput(String title, String prompt, String conditions, String technology,
                                String scenario, Type type, Integer difficulty, List<Dimension> dimensions,
                                List<String> topicIds, List<KnowledgeLink> knowledgeLinks, List<String> tags,
                                Source source, String referenceAnswer, List<Checkpoint> checkpoints) {}

    // 题干与参考使用不同类型，普通读取不会把隐藏答案先发送到浏览器再折叠。
    public record QuestionPrompt(String title, String prompt, String conditions, String technology,
                                 String scenario, Type type, Integer difficulty, List<Dimension> dimensions,
                                 List<String> topicIds, List<KnowledgeLink> knowledgeLinks, List<String> tags,
                                 Source source) {}
    public record SaveQuestion(Long expectedRevision, QuestionInput content) {}
    public record ChangeStatus(Long expectedRevision) {}
    public record SetFavorite(Long expectedRevision, Boolean favorite) {}
    public record EmptyRequest() {}

    public record QuestionVersion(String id, int number, Instant createdAt, QuestionPrompt content,
                                  StandardStatus standardStatus, boolean hasReference) {}
    public record KnowledgeReference(String itemId, String versionId, String title, int number,
                                     ReferenceState state) {}
    public record KnowledgeReferencePage(String questionId, String versionId, List<KnowledgeReference> items) {}
    public record Question(String id, Status status, long revision, boolean favorite, Instant createdAt,
                           Instant updatedAt, QuestionVersion currentVersion,
                           List<KnowledgeReference> knowledgeReferences) {}
    public record QuestionSummary(String id, String title, Type type, Integer difficulty, Status status,
                                  long revision, boolean favorite, String versionId, int versionNumber,
                                  StandardStatus standardStatus, List<String> topicIds, Instant updatedAt) {}
    public record QuestionPage(List<QuestionSummary> items, String nextCursor) {}
    public record VersionSummary(String id, int number, String title, Instant createdAt) {}
    public record VersionPage(List<VersionSummary> items, Integer nextCursor) {}
    public record Reference(String questionId, String versionId, StandardStatus standardStatus,
                            String referenceAnswer, List<Checkpoint> checkpoints,
                            String exposureId, Instant recordedAt) {}
    public record Exposure(String id, String questionId, String versionId, ExposureKind kind, Instant createdAt) {}
    public record ExposurePage(List<Exposure> items, String nextCursor) {}
}
