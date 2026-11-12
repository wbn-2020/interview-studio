package com.wenjie.studio.memory;

import com.wenjie.studio.knowledge.KnowledgeDtos;

import java.time.Instant;
import java.util.List;

public final class MemoryDtos {
    private MemoryDtos() {}

    public enum Kind { QA, CLOZE }
    public enum Status { ACTIVE, ARCHIVED }
    public enum SourceState { CURRENT, SUPERSEDED, ARCHIVED }

    public record SourceLink(String itemId, String versionId) {}
    public record Content(Kind kind, String title, String prompt, String reference,
                          List<SourceLink> sources, List<String> relatedConceptIds) {}
    public record Create(Content content) {}
    public record Save(Long expectedRevision, Content content) {}
    public record ChangeStatus(Long expectedRevision) {}
    public record EmptyRequest() {}

    // 普通读取没有参考字段；编辑或浏览须显式请求确切版本参考，不能据此生成复习事件。
    public record Prompt(Kind kind, String title, String prompt,
                         List<SourceLink> sources, List<String> relatedConceptIds) {}
    public record Version(String id, int number, Prompt content, Instant createdAt) {}

    // 状态和正文差异按读取时的已发布版本计算，不写回不可变卡版本，也不证明来源错误。
    public record SourceReference(String itemId, String versionId, KnowledgeDtos.Kind kind,
                                  String title, int number, SourceState state, boolean bodyChanged) {}
    public record RelatedConcept(String itemId, String title, KnowledgeDtos.Status status) {}
    public record Card(String id, Status status, long revision, Version currentVersion,
                       List<SourceReference> sourceReferences, List<RelatedConcept> relatedConcepts,
                       Instant createdAt, Instant updatedAt) {}
    public record VersionDetail(Version version, List<SourceReference> sourceReferences,
                                List<RelatedConcept> relatedConcepts) {}
    public record Summary(String id, Kind kind, String title, Status status, long revision,
                          String versionId, int versionNumber, int sourceCount, int relatedConceptCount, Instant updatedAt) {}
    public record Page(List<Summary> items, String nextCursor) {}
    public record VersionSummary(String id, int number, Kind kind, String title, Instant createdAt) {}
    public record VersionPage(List<VersionSummary> items, Integer nextBefore) {}
    public record Reference(String cardId, String versionId, int versionNumber, String reference) {}
}
