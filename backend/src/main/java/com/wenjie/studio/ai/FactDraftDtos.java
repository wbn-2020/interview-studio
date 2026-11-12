package com.wenjie.studio.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wenjie.studio.material.MaterialDtos;

import java.time.Instant;
import java.util.List;

public final class FactDraftDtos {
    private FactDraftDtos() {}

    public enum Kind { MATERIAL, TRAINING }
    public record TrainingSelection(String sessionId, String questionId, String answerId,
                                    List<AiDtos.TrainingDraftFragment> fragments) {}
    public record Selection(Kind kind, List<String> excerptIds, TrainingSelection training) {}
    public record PreviewRequest(String instruction, Selection source) {}
    public record CreateRequest(String instruction, Selection source, String expectedManifestHash,
                                Boolean consentConfirmed,
                                @JsonInclude(JsonInclude.Include.NON_NULL) AiAuthorizationDtos.Use authorization) {}
    public record TrainingInput(String sessionId, String questionId, String answerId, int answerNumber,
                                String answerCondition, List<AiDtos.TrainingDraftFragment> fragments) {}
    // 训练仅保留确切提交元数据与选区；题面、评价和个人参考不得进入事实整理的固定输入。
    public record Input(Kind kind, List<MaterialDtos.AiExcerpt> materials, TrainingInput training) {}
    public record Content(String title, String scenario, String problem, String action, String result,
                          String technology, List<String> tags) {}
    public record Candidate(Content content, List<AiDtos.MaterialCitation> materialCitations,
                            List<AiDtos.TrainingDraftCitation> trainingCitations,
                            String limitations, String validationNote) {}
    public record Adopt(Long expectedRevision, String projectId, String expectedProjectVersionId,
                        Long expectedProjectRevision, Content content, Boolean confirmed) {}
    public record Decision(AiDtos.DecisionStatus status, String targetProjectId, String targetItemId,
                           String targetVersionId, Instant createdAt) {}
    public record TrainingOrigin(String sessionId, String questionId, String answerId, int answerNumber,
                                 String answerCondition, AiDtos.OriginState state) {}
    public record Origin(String taskId, String targetProjectId, String targetItemId, String targetVersionId,
                         int targetVersionNumber, Instant adoptedAt, boolean direct, Kind sourceKind,
                         List<AiDtos.MaterialOriginSource> materialSources, TrainingOrigin trainingSource) {}
    public record OriginPage(List<Origin> items, Integer nextCursor) {}
}
