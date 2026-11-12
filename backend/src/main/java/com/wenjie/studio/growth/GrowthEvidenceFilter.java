package com.wenjie.studio.growth;

import org.springframework.stereotype.Component;

import static com.wenjie.studio.growth.GrowthDtos.*;

/** 目录发现与历史活动共用维度规则，用户关联只参与明确允许它的目标发现。 */
@Component
public final class GrowthEvidenceFilter {
    private final GrowthEvidenceLinkMapper links;

    public GrowthEvidenceFilter(GrowthEvidenceLinkMapper links) { this.links = links; }

    public EvidenceSummary match(String owner, EvidenceSummary value, Scope scope,
                                 AssessmentLevel level, ReviewFlag flag) {
        GrowthRules.uuid(owner);
        GrowthRules.scope(scope);
        GrowthRules.summary(value);
        var window = scope.window();
        if (value.occurredAt().isBefore(window.startAt()) || !value.occurredAt().isBefore(window.observedUntil())
                || level != null && value.assessmentLevel() != level
                || flag != null && !value.state().reviewFlags().contains(flag)) return null;
        var filter = scope.filter();
        if (!dimensions(value.facets(), filter.topicId(), filter.topicMode(), filter.dimension())) return null;
        TargetMatch match = null;
        if (filter.target() != null) {
            boolean recorded = recordedTarget(value.facets(), filter.target());
            boolean linked = filter.targetMode() == TargetMode.INCLUDE_ACTIVE_LINKS
                    && links.hasActiveLink(owner, GrowthRules.evidenceKey(value.ref()),
                    filter.target().itemId(), filter.target().versionId());
            if (!recorded && !linked) return null;
            match = new TargetMatch(recorded, linked);
        }
        var facets = value.facets();
        // 匹配说明单独返回，不能把后建的跨岗位关联写成原答发生时的目标。
        return new EvidenceSummary(value.ref(), value.occurredAt(), value.title(), value.recordKind(),
                value.assessmentLevel(), value.conditions(), new Facets(facets.targets(), facets.topics(),
                facets.dimensions(), facets.currentTopicClassificationAvailable(), match),
                value.state(), value.stamp(), value.route());
    }

    public boolean matchesRecorded(EvidenceSummary value, ProgressFilter filter) {
        GrowthRules.summary(value);
        return matchesRecorded(value.facets(), filter);
    }

    public boolean matchesRecorded(Facets facets, ProgressFilter filter) {
        GrowthRules.required(facets);
        GrowthRules.required(filter);
        if (filter.target() != null) GrowthRules.target(filter.target());
        if (filter.topicId() != null) GrowthRules.uuid(filter.topicId());
        GrowthRules.required(filter.topicMode());
        return (filter.target() == null || recordedTarget(facets, filter.target()))
                && dimensions(facets, filter.topicId(), filter.topicMode(), filter.dimension());
    }

    private static boolean recordedTarget(Facets facets, TargetRef target) {
        return facets.targets().stream().anyMatch(value -> value.basis() == FacetBasis.RECORDED
                && value.target().equals(target));
    }

    private static boolean dimensions(Facets facets, String topicId, TopicMode mode,
                                       com.wenjie.studio.bank.QuestionDtos.Dimension dimension) {
        if (topicId != null && facets.topics().stream().noneMatch(value -> value.topicId().equals(topicId)
                && (value.basis() == FacetBasis.RECORDED || mode == TopicMode.INCLUDE_CURRENT_CLASSIFICATION
                && value.basis() == FacetBasis.CURRENT_CLASSIFICATION))) return false;
        return dimension == null || facets.dimensions().stream().anyMatch(value ->
                value.basis() == FacetBasis.RECORDED && value.dimension() == dimension);
    }
}
