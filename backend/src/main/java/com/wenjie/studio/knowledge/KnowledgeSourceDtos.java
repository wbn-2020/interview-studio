package com.wenjie.studio.knowledge;

import java.time.Instant;
import java.util.List;

public final class KnowledgeSourceDtos {
    private KnowledgeSourceDtos() {}

    public record Source(String title, String url, String author, Instant acquiredAt,
                         String applicableVersion, String rights, String note) {}
    public record Document(String itemId, String versionId, long revision,
                           List<Source> sources, Instant savedAt) {}
    public record Save(Long expectedRevision, List<Source> sources) {}
    public record Row(long revision, String sourcesJson, Instant savedAt) {}
}
