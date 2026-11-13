package com.wenjie.studio.material;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface MaterialDependencyImpactMapper {
    record Dependency(String binding, String bindingId, String taskId, String domain, String targetId,
                      String targetVersionId, Integer targetVersionNumber, Long targetRevision, String targetTitle) {}
    record Task(String ownerId, String id, String purpose, String status, String manifestJson, String manifestHash) {}
    record MaterialBinding(String excerptId, Integer ordinal) {}
    record Row(String ownerId, String id, String correctionId, String excerptId, String binding, String bindingId,
               String taskId, String domain, String targetId, String targetVersionId, Integer targetVersionNumber,
               Long targetRevision, String targetTitle, Long revision, Instant createdAt, String choice, String reason,
               String resolutionTargetVersionId, Long resolutionTargetRevision, Instant decidedAt) {}
    record Current(String domain, String id, String versionId, Integer versionNumber, Long revision, String status, String title) {}

    List<Dependency> dependencies(@Param("owner") String owner, @Param("excerptId") String excerptId, @Param("limit") int limit);
    Task task(@Param("owner") String owner, @Param("taskId") String taskId);
    List<MaterialBinding> taskMaterials(@Param("owner") String owner, @Param("taskId") String taskId,
                                       @Param("binding") String binding, @Param("limit") int limit);
    boolean bindingExists(@Param("owner") String owner, @Param("excerptId") String excerptId, @Param("row") Row row);
    Current current(@Param("owner") String owner, @Param("domain") String domain, @Param("targetId") String targetId);
    int insert(@Param("row") Row row);
    Row impact(@Param("owner") String owner, @Param("impactId") String impactId);
    List<Row> impacts(@Param("owner") String owner, @Param("excerptId") String excerptId, @Param("state") String state,
                      @Param("beforeTime") Instant beforeTime, @Param("beforeId") String beforeId, @Param("limit") int limit);
    int resolve(@Param("owner") String owner, @Param("impactId") String impactId, @Param("request") MaterialDependencyImpactDtos.Resolve request,
                @Param("now") Instant now);
    boolean reviewRequired(@Param("owner") String owner, @Param("domain") String domain, @Param("targetId") String targetId,
                           @Param("versionId") String versionId, @Param("baseline") Instant baseline);
    boolean bodyUpdated(@Param("owner") String owner, @Param("domain") String domain, @Param("targetId") String targetId,
                        @Param("previousVersionId") String previousVersionId, @Param("currentVersionId") String currentVersionId);
    List<String> preparationExcerpts(@Param("owner") String owner, @Param("itemId") String itemId,
                                     @Param("versionId") String versionId, @Param("limit") int limit);
    boolean knowledgeDraftClear(@Param("owner") String owner, @Param("itemId") String itemId);
    boolean lifecycleReviewRequired(@Param("owner") String owner, @Param("domain") String domain,
                                    @Param("targetId") String targetId, @Param("versionId") String versionId,
                                    @Param("baseline") Instant baseline);
}
