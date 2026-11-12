package com.wenjie.studio.search;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wenjie.studio.ai.AiDtos;
import com.wenjie.studio.bank.QuestionDtos;
import com.wenjie.studio.review.RecollectionDtos;
import com.wenjie.studio.review.ReviewDtos;
import com.wenjie.studio.review.ReviewedObservationDtos;
import com.wenjie.studio.training.TrainingDtos;

import java.util.List;

public final class UnifiedSearchDtos {
    private UnifiedSearchDtos() { }

    public enum Domain { KNOWLEDGE, PREPARATION, MATERIAL_EXCERPT, QUESTION,
        TRAINING_ANSWER, REVIEW_FEEDBACK, RECOLLECTION, AI_FEEDBACK }
    public enum MatchMode { ANALYZED, LITERAL }

    public record TextField(String id, String label, String text) { }

    // 偏移均为对应白名单字段原文的 UTF-16 半开区间；片段不拼接省略号或改写正文。
    public record Fragment(String field, String label, String text, int startOffset, int endOffset,
                           int matchStart, int matchEnd, int lineStart, int lineEnd) { }

    public record TrainingContext(ReviewDtos.AnswerLink answer, TrainingDtos.Status sessionStatus,
                                  TrainingDtos.FeedbackTiming feedbackTiming,
                                  ReviewedObservationDtos.RecordedAnswerConditions conditions) { }

    public record FeedbackContext(ReviewDtos.FeedbackKind kind, String authorLabel,
                                  QuestionDtos.Dimension dimension, AiDtos.EvaluationCoverage coverage,
                                  String condition, String basis, String limitation,
                                  ReviewDtos.Decision decision) { }

    public record RecollectionContext(RecollectionDtos.BaselineKind baselineKind,
                                      QuestionDtos.Type questionType, String uncertainty) { }

    public record AiFeedbackContext(String feedbackId, String reportHash, AiDtos.EvaluationLevel level,
                                    String reportLimitations, String validationNote, QuestionDtos.Dimension dimension,
                                    AiDtos.EvaluationCoverage coverage, String condition, String basis, String limitation,
                                    int decisionRevision, AiDtos.FeedbackChoice decisionChoice) { }

    public record Hit(Domain domain, String id, String versionId, Integer versionNumber, String kind,
                      String title, String status, boolean currentVersion, String verification,
                      String projectStatus, String attachmentId, String runId, List<Fragment> fragments,
                      @JsonInclude(JsonInclude.Include.NON_NULL) TrainingContext training,
                      @JsonInclude(JsonInclude.Include.NON_NULL) FeedbackContext feedback,
                      @JsonInclude(JsonInclude.Include.NON_NULL) RecollectionContext recollection,
                      @JsonInclude(JsonInclude.Include.NON_NULL) AiFeedbackContext aiFeedback) {
        // 旧四域保持原响应形状；新来源的条件投影只由服务端构造，未适用的尾字段省略。
        public Hit(Domain domain, String id, String versionId, Integer versionNumber, String kind,
                   String title, String status, boolean currentVersion, String verification,
                   String projectStatus, String attachmentId, String runId, List<Fragment> fragments) {
            this(domain, id, versionId, versionNumber, kind, title, status, currentVersion, verification,
                    projectStatus, attachmentId, runId, fragments, null, null, null, null);
        }

        public Hit(Domain domain, String id, String versionId, Integer versionNumber, String kind,
                   String title, String status, boolean currentVersion, String verification,
                   String projectStatus, String attachmentId, String runId, List<Fragment> fragments,
                   TrainingContext training, FeedbackContext feedback, RecollectionContext recollection) {
            this(domain, id, versionId, versionNumber, kind, title, status, currentVersion, verification,
                    projectStatus, attachmentId, runId, fragments, training, feedback, recollection, null);
        }
    }

    public record Page(Domain domain, String query, String status, MatchMode matchMode, boolean applicable,
                       SearchDtos.State indexStatus, List<Hit> items, int limit, int offset,
                       boolean hasMore, boolean scanLimited) { }

    public record Source(Hit item, List<TextField> fields) { }
}
