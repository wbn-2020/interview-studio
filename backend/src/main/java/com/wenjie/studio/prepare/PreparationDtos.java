package com.wenjie.studio.prepare;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class PreparationDtos {
    private PreparationDtos() {}

    public enum Kind { TARGET, PROJECT, FACT, EXPRESSION }
    public enum Status { ACTIVE, ARCHIVED }
    public enum Verification { UNVERIFIED, CONFIRMED, WITHDRAWN }
    public enum Responsibility { OWNER, PARTICIPANT, FAMILIAR, UNVERIFIED }
    public enum ExpressionType { INTRO, PROJECT_SHORT, PROJECT_LONG, STAR, TRADEOFF }
    public enum LinkKind { PROJECT, FACT, KNOWLEDGE, COLLECTION }
    public enum ReferenceState { CURRENT, SUPERSEDED, WITHDRAWN, ARCHIVED, UNVERIFIED }
    public enum SourceState { CURRENT, SUPERSEDED, WITHDRAWN, ARCHIVED, PROJECT_ARCHIVED, UNVERIFIED, UNAVAILABLE }

    public record SourceCheck(Kind kind, String itemId, String versionId, SourceState state,
                               Long sourceGeneration, Long projectGeneration) {}
    public record AiVersion(String itemId, String versionId, int number, Kind kind,
                            String title, String body, SourceCheck check) {}
    public record ExpressionCheck(SourceCheck expression, List<SourceCheck> factChecks) {}
    public record ExpressionRead(AiVersion version, ExpressionCheck check) {}

    public record Source(String title, String url, String note, List<String> excerptIds) {}
    public record Requirement(String id, String text, List<String> dimensions, Integer priority) {}
    public record VersionLink(LinkKind kind, String itemId, String versionId) {}
    public record TargetContent(String title, String company, String jd, String sourceUrl,
                                LocalDate capturedOn, Integer weeklyMinutes,
                                List<Requirement> requirements, List<VersionLink> links) {}
    public record ProjectContent(String title, String overview, String technology, Source source) {}
    public record FactContent(String title, String scenario, String problem, String action, String result,
                              String technology, Responsibility responsibility, Source source, List<String> tags) {}
    public record ExpressionContent(String title, ExpressionType type, String body,
                                    List<String> factVersionIds, List<String> tags) {}

    public record CreateItem(Kind kind, String projectId, JsonNode content) {}
    public record SaveItem(Long expectedRevision, JsonNode content) {}
    public record ChangeStatus(Long expectedRevision) {}
    public record DecideFact(Long expectedRevision, String reason) {}
    public record SetFavorite(Long expectedRevision, Boolean favorite) {}
    public record SetFocus(Long expectedRevision, String targetId) {}

    public record Version(String id, int number, Instant createdAt, JsonNode content) {}
    public record VersionSummary(String id, int number, String title, Instant createdAt) {}
    public record VersionPage(List<VersionSummary> items, Integer nextCursor) {}
    public record Reference(LinkKind kind, String itemId, String versionId, String title,
                            int number, ReferenceState state) {}
    public record Item(String id, Kind kind, String projectId, Status status, long revision,
                       Verification verification, boolean favorite, Instant createdAt, Instant updatedAt,
                       Version currentVersion, List<Reference> references, boolean reviewRequired) {}
    public record Summary(String id, Kind kind, String projectId, String title, Status status, long revision,
                          Verification verification, boolean favorite, String versionId, int versionNumber,
                          Instant updatedAt) {}
    public record ItemPage(List<Summary> items, String nextCursor) {}
    public record Decision(String id, String versionId, Verification decision, String reason, Instant createdAt) {}
    public record DecisionPage(List<Decision> items, String nextCursor) {}
    public record Impact(String id, Kind kind, String title, String versionId, boolean reviewRequired) {}
    public record ImpactPage(List<Impact> items, String nextCursor) {}
    public record Focus(String targetId, long revision) {}
}
