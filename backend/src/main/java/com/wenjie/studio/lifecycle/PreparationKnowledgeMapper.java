package com.wenjie.studio.lifecycle;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;
import static com.wenjie.studio.lifecycle.PreparationKnowledgeRows.*;

@Mapper
public interface PreparationKnowledgeMapper {
    ObservationRow observation(@Param("owner") String owner, @Param("itemId") String itemId);
    int insertEvent(@Param("owner") String owner, @Param("event") EventRow event);
    EventRow event(@Param("owner") String owner, @Param("eventId") String eventId);
    List<Candidate> candidates(@Param("owner") String owner, @Param("rootItemId") String rootItemId,
                               @Param("rootKind") String rootKind, @Param("after") String after, @Param("limit") int limit);
    List<Binding> bindings(@Param("owner") String owner, @Param("taskId") String taskId);
    boolean itemExists(@Param("owner") String owner, @Param("itemId") String itemId, @Param("context") String context);
    boolean edgeExists(@Param("owner") String owner, @Param("eventId") String eventId, @Param("domain") String domain,
                       @Param("taskId") String taskId, @Param("sourceItemId") String sourceItemId,
                       @Param("sourceVersionId") String sourceVersionId);
    int insertImpact(@Param("owner") String owner, @Param("row") ImpactRow row);
    List<ImpactRow> pendingImpacts(@Param("owner") String owner, @Param("eventId") String eventId, @Param("limit") int limit);
    boolean propagationPending(@Param("owner") String owner, @Param("eventId") String eventId);
    int completePropagation(@Param("owner") String owner, @Param("eventId") String eventId, @Param("impactId") String impactId);
    List<ImpactRow> impacts(@Param("owner") String owner, @Param("itemId") String itemId,
                           @Param("context") String context, @Param("state") String state,
                           @Param("beforeTime") Instant beforeTime, @Param("beforeId") String beforeId, @Param("limit") int limit);
    ImpactRow impact(@Param("owner") String owner, @Param("impactId") String impactId);
    int resolve(@Param("owner") String owner, @Param("itemId") String itemId, @Param("impactId") String impactId,
                @Param("expected") long expected, @Param("choice") String choice, @Param("reason") String reason,
                @Param("targetVersionId") String targetVersionId, @Param("targetRevision") long targetRevision,
                @Param("now") Instant now);
}
