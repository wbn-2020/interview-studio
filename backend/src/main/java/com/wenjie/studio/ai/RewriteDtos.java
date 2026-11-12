package com.wenjie.studio.ai;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** 改写始终是确切提交的只读参考，与正式答案和评价决定分开保存。 */
public final class RewriteDtos {
    private RewriteDtos() {}

    public record PreviewRequest(String sessionId, String questionId, String answerId) {}
    public record CreateRequest(String sessionId, String questionId, String answerId,
                                String expectedManifestHash, Boolean consentConfirmed,
                                @JsonInclude(JsonInclude.Include.NON_NULL) AiAuthorizationDtos.Use authorization) {}
    public record Change(String explanation, List<AiDtos.EvaluationCitation> citations) {}
    public record Candidate(AiDtos.EvaluationLevel level, String summary, String answer,
                            List<Change> changes, List<String> questions, String limitations, String validationNote) {}
}
