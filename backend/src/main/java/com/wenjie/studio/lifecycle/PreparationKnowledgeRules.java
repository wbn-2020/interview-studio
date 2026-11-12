package com.wenjie.studio.lifecycle;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.prepare.PreparationDtos;
import java.time.Instant;
import java.util.Objects;
import static com.wenjie.studio.lifecycle.KnowledgeRevalidationRules.*;
import static com.wenjie.studio.lifecycle.PreparationKnowledgeDtos.*;
import static com.wenjie.studio.lifecycle.PreparationKnowledgeRows.*;

final class PreparationKnowledgeRules {
    private PreparationKnowledgeRules() {}
    static <T extends Enum<T>> T enumeration(Class<T> type, String value) {
        try { return Enum.valueOf(type, value); }
        catch (IllegalArgumentException | NullPointerException exception) { throw integrity(); }
    }
    static void observation(ObservationRow row) {
        if (row == null || row.title() == null || row.title().isBlank() || row.versionNumber() < 1) throw integrity();
        uuid(row.itemId()); uuid(row.versionId()); revision(row.sourceGeneration()); revision(row.revision());
        var kind = enumeration(PreparationDtos.Kind.class, row.kind());
        enumeration(PreparationDtos.Status.class, row.status());
        if (kind == PreparationDtos.Kind.FACT) {
            uuid(row.projectId()); enumeration(PreparationDtos.Verification.class, row.verification());
        } else if (row.projectId() != null || row.verification() != null) throw integrity();
    }
    static Root root(EventRow row) {
        if (row == null || row.rootTitle() == null || row.rootTitle().isBlank()) throw integrity();
        uuid(row.id()); uuid(row.rootItemId()); uuid(row.previousVersionId()); uuid(row.currentVersionId()); time(row.createdAt());
        var kind = enumeration(PreparationDtos.Kind.class, row.rootKind());
        var change = enumeration(ChangeKind.class, row.changeKind());
        revision(row.previousGeneration()); revision(row.currentGeneration()); revision(row.previousRevision()); revision(row.currentRevision());
        if (row.previousGeneration() == MAX || row.currentGeneration() != row.previousGeneration() + 1
                || row.previousRevision() == MAX || row.currentRevision() != row.previousRevision() + 1
                || row.previousVersionNumber() < 1 || row.currentVersionNumber() < 1) throw integrity();
        var beforeStatus = enumeration(PreparationDtos.Status.class, row.previousStatus());
        var afterStatus = enumeration(PreparationDtos.Status.class, row.currentStatus());
        PreparationDtos.Verification beforeVerification = null, afterVerification = null;
        if (kind == PreparationDtos.Kind.FACT) {
            uuid(row.rootProjectId());
            beforeVerification = enumeration(PreparationDtos.Verification.class, row.previousVerification());
            afterVerification = enumeration(PreparationDtos.Verification.class, row.currentVerification());
        } else if (row.rootProjectId() != null || row.previousVerification() != null || row.currentVerification() != null) throw integrity();
        if (change == ChangeKind.CONTENT_SAVED) {
            if (row.previousVersionId().equals(row.currentVersionId())
                    || (long) row.previousVersionNumber() + 1 != row.currentVersionNumber()
                    || beforeStatus != PreparationDtos.Status.ACTIVE || afterStatus != beforeStatus
                    || kind == PreparationDtos.Kind.FACT && afterVerification != PreparationDtos.Verification.UNVERIFIED) throw integrity();
        } else {
            if (!row.previousVersionId().equals(row.currentVersionId())
                    || row.previousVersionNumber() != row.currentVersionNumber()) throw integrity();
            if (change != ChangeKind.STATUS_SET && beforeStatus != afterStatus) throw integrity();
            if (change == ChangeKind.FACT_DECIDED && (kind != PreparationDtos.Kind.FACT
                    || afterVerification == PreparationDtos.Verification.UNVERIFIED)) throw integrity();
            if (change == ChangeKind.EXPRESSION_BASIS_CONFIRMED && (kind != PreparationDtos.Kind.EXPRESSION
                    || beforeStatus != PreparationDtos.Status.ACTIVE)) throw integrity();
            if (change != ChangeKind.FACT_DECIDED && !Objects.equals(beforeVerification, afterVerification)) throw integrity();
        }
        return new Root(kind, row.rootItemId(), row.rootTitle(), row.rootProjectId(), change,
                new Observation(row.previousVersionId(), row.previousVersionNumber(), row.previousGeneration(), row.previousRevision(),
                        beforeStatus, beforeVerification),
                new Observation(row.currentVersionId(), row.currentVersionNumber(), row.currentGeneration(), row.currentRevision(),
                        afterStatus, afterVerification));
    }
    static Source source(ImpactRow row, Root root) {
        if (row == null || row.sourceTitle() == null || row.sourceVersionNumber() < 1 || row.depth() < 1) throw integrity();
        uuid(row.id()); uuid(row.eventId()); uuid(row.sourceItemId()); uuid(row.sourceVersionId()); uuid(row.taskId());
        uuid(row.targetItemId()); uuid(row.targetVersionId()); time(row.createdAt());
        var domain = enumeration(Domain.class, row.sourceDomain());
        var dependency = enumeration(DependencyKind.class, row.dependencyKind());
        Fact via = null;
        boolean expressionBridge = dependency == DependencyKind.FACT_EXPRESSION || dependency == DependencyKind.PROJECT_EXPRESSION;
        if (expressionBridge) {
            uuid(row.viaFactItemId()); uuid(row.viaFactVersionId());
            if (row.viaFactTitle() == null || row.viaFactVersionNumber() == null || row.viaFactVersionNumber() < 1) throw integrity();
            via = new Fact(row.viaFactItemId(), row.viaFactTitle(), row.viaFactVersionId(), row.viaFactVersionNumber());
        } else if (row.viaFactItemId() != null || row.viaFactVersionId() != null || row.viaFactTitle() != null
                || row.viaFactVersionNumber() != null) throw integrity();
        if (domain == Domain.PREPARATION) {
            var kind = enumeration(PreparationDtos.Kind.class, row.sourceKind());
            if (row.depth() != 1 || row.parentImpactId() != null || dependency == DependencyKind.KNOWLEDGE) throw integrity();
            switch (dependency) {
                case DIRECT -> {
                    if (kind != root.kind() || !row.sourceItemId().equals(root.itemId())
                            || row.sourceVersionNumber() > root.before().versionNumber()
                            || row.sourceVersionNumber() == root.before().versionNumber()
                            && !row.sourceVersionId().equals(root.before().versionId())) throw integrity();
                }
                case PROJECT_FACT -> { if (root.kind() != PreparationDtos.Kind.PROJECT || kind != PreparationDtos.Kind.FACT) throw integrity(); }
                case FACT_EXPRESSION -> {
                    if (root.kind() != PreparationDtos.Kind.FACT || kind != PreparationDtos.Kind.EXPRESSION
                            || !row.viaFactItemId().equals(root.itemId())) throw integrity();
                }
                case PROJECT_EXPRESSION -> { if (root.kind() != PreparationDtos.Kind.PROJECT || kind != PreparationDtos.Kind.EXPRESSION) throw integrity(); }
                default -> throw integrity();
            }
        } else {
            if (!"KNOWLEDGE".equals(row.sourceKind()) || dependency != DependencyKind.KNOWLEDGE
                    || row.depth() < 2 || via != null) throw integrity();
            uuid(row.parentImpactId());
            if (row.id().equals(row.parentImpactId())) throw integrity();
        }
        return new Source(domain, row.sourceKind(), row.sourceItemId(), row.sourceTitle(), row.sourceVersionId(),
                row.sourceVersionNumber(), dependency, via);
    }
    static void parent(ImpactRow child, ImpactRow parent) {
        if (child == null || parent == null || !Objects.equals(child.parentImpactId(), parent.id())
                || !child.eventId().equals(parent.eventId()) || !child.createdAt().equals(parent.createdAt())
                || !"KNOWLEDGE".equals(child.sourceDomain()) || !child.sourceItemId().equals(parent.targetItemId())
                || child.sourceVersionNumber() < parent.targetVersionNumber()
                || (long) parent.depth() + 1 != child.depth()) throw integrity();
    }
    static ApiException conflict(String message, long revision) {
        return new ApiException(409, "PREPARATION_KNOWLEDGE_CONFLICT", message, revision);
    }
    static ApiException invalidRequest(String message) {
        return new ApiException(400, "INVALID_PREPARATION_KNOWLEDGE_REQUEST", message);
    }
    static ApiException notFound() {
        return new ApiException(404, "PREPARATION_KNOWLEDGE_NOT_FOUND", "准备来源、知识或采用影响不存在或不可访问。");
    }
}
