package com.wenjie.studio.lifecycle;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

import static com.wenjie.studio.lifecycle.KnowledgeRevalidationRows.*;

@Mapper
public interface KnowledgeRevalidationMapper {
    boolean itemExists(@Param("owner") String owner, @Param("itemId") String itemId);
    Integer versionNumber(@Param("owner") String owner, @Param("itemId") String itemId, @Param("versionId") String versionId);
    boolean pending(@Param("owner") String owner, @Param("itemId") String itemId, @Param("number") int number);
    boolean changedAfter(@Param("owner") String owner, @Param("itemId") String itemId,
                         @Param("number") int number, @Param("baseline") Instant baseline);
    boolean reviewRequired(@Param("owner") String owner, @Param("itemId") String itemId,
                           @Param("number") int number, @Param("baseline") Instant baseline);
    Instant latestFrozenAt(@Param("owner") String owner);
    int insertEvent(@Param("owner") String owner, @Param("id") String id, @Param("rootItemId") String rootItemId,
                    @Param("previousVersionId") String previousVersionId, @Param("currentVersionId") String currentVersionId,
                    @Param("kind") String kind, @Param("now") Instant now);
    List<Adoption> adoptions(@Param("owner") String owner, @Param("sourceItemId") String sourceItemId,
                            @Param("minimumVersion") int minimumVersion, @Param("after") String after,
                            @Param("limit") int limit);
    boolean edgeExists(@Param("owner") String owner, @Param("eventId") String eventId,
                       @Param("taskId") String taskId, @Param("sourceItemId") String sourceItemId,
                       @Param("sourceVersionId") String sourceVersionId);
    int insertImpact(@Param("owner") String owner, @Param("id") String id, @Param("eventId") String eventId,
                     @Param("rootItemId") String rootItemId, @Param("sourceItemId") String sourceItemId,
                     @Param("sourceVersionId") String sourceVersionId,
                     @Param("taskId") String taskId, @Param("targetItemId") String targetItemId,
                     @Param("targetVersionId") String targetVersionId, @Param("parentImpactId") String parentImpactId,
                     @Param("depth") int depth, @Param("now") Instant now);
    List<ImpactRow> pendingImpacts(@Param("owner") String owner, @Param("eventId") String eventId, @Param("limit") int limit);
    boolean propagationPending(@Param("owner") String owner, @Param("eventId") String eventId);
    int completePropagation(@Param("owner") String owner, @Param("eventId") String eventId, @Param("impactId") String impactId);
    List<ImpactRow> impacts(@Param("owner") String owner, @Param("itemId") String itemId,
                            @Param("direction") String direction, @Param("state") String state,
                            @Param("beforeTime") Instant beforeTime, @Param("beforeId") String beforeId,
                            @Param("limit") int limit);
    ImpactRow impact(@Param("owner") String owner, @Param("itemId") String itemId, @Param("impactId") String impactId);
    ImpactRow lineage(@Param("owner") String owner, @Param("itemId") String itemId, @Param("impactId") String impactId);
    Target target(@Param("owner") String owner, @Param("itemId") String itemId, @Param("adoptedVersionId") String adoptedVersionId);
    int resolve(@Param("owner") String owner, @Param("itemId") String itemId, @Param("impactId") String impactId,
                @Param("expected") long expected, @Param("choice") String choice, @Param("reason") String reason,
                @Param("targetVersionId") String targetVersionId, @Param("targetRevision") long targetRevision,
                @Param("now") Instant now);
}
