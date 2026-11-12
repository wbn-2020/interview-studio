package com.wenjie.studio.memory;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.knowledge.KnowledgeDtos;
import com.wenjie.studio.knowledge.KnowledgeService;
import com.wenjie.studio.lifecycle.KnowledgeRevalidationQuery;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.wenjie.studio.memory.MemoryDtos.*;

@Component
public final class MemorySources {
    private final KnowledgeService knowledge;
    private final KnowledgeRevalidationQuery revalidation;

    public MemorySources(KnowledgeService knowledge, KnowledgeRevalidationQuery revalidation) {
        this.knowledge = knowledge; this.revalidation = revalidation;
    }

    void validate(String owner, Content content, Prompt previous) {
        Set<SourceLink> retainedSources = previous == null ? Set.of() : new HashSet<>(previous.sources());
        Set<String> retainedConcepts = previous == null ? Set.of() : new HashSet<>(previous.relatedConceptIds());
        // 保留例外仅取编辑前当前卡版，不能从任意历史重新引入已过期或归档的来源。
        for (SourceLink source : content.sources()) {
            revalidation.requireClear(owner, source.itemId(), source.versionId());
            SourceReference reference = source(owner, source);
            if (!retainedSources.contains(source) && reference.state() != SourceState.CURRENT) {
                throw new ApiException(409, "SOURCE_CHANGED", "新增来源须为有效知识的当前发布版本，请重新选择。");
            }
        }
        for (String id : content.relatedConceptIds()) {
            RelatedConcept concept = concept(owner, id);
            if (!retainedConcepts.contains(id) && concept.status() != KnowledgeDtos.Status.ACTIVE) {
                throw new ApiException(409, "SOURCE_CHANGED", "新增相关概念须处于有效状态，请重新选择。");
            }
        }
    }

    List<SourceReference> references(String owner, Prompt prompt) {
        return prompt.sources().stream().map(link -> source(owner, link)).toList();
    }

    List<RelatedConcept> concepts(String owner, Prompt prompt) {
        return prompt.relatedConceptIds().stream().map(id -> concept(owner, id)).toList();
    }

    private SourceReference source(String owner, SourceLink link) {
        try {
            KnowledgeDtos.ItemDetail item = knowledge.detail(owner, link.itemId());
            KnowledgeDtos.Version referenced = knowledge.version(owner, link.itemId(), link.versionId());
            SourceState state = item.status() == KnowledgeDtos.Status.ARCHIVED ? SourceState.ARCHIVED
                    : referenced.id().equals(item.currentVersionId()) ? SourceState.CURRENT : SourceState.SUPERSEDED;
            // title/body 必须来自发布版；草稿编辑和结构关联都不能冒充来源正文更正。
            boolean bodyChanged = !referenced.body().equals(item.published().body());
            return new SourceReference(item.id(), referenced.id(), item.kind(), referenced.title(), referenced.number(), state, bodyChanged);
        } catch (ApiException exception) {
            if (exception.status() == 404) throw unavailable();
            throw exception;
        }
    }

    private RelatedConcept concept(String owner, String id) {
        try {
            KnowledgeDtos.ItemDetail item = knowledge.detail(owner, id);
            if (item.kind() != KnowledgeDtos.Kind.CONCEPT) throw unavailable();
            return new RelatedConcept(item.id(), item.published().title(), item.status());
        } catch (ApiException exception) {
            if (exception.status() == 404) throw unavailable();
            throw exception;
        }
    }

    private ApiException unavailable() {
        return new ApiException(422, "SOURCE_UNAVAILABLE", "知识来源或相关概念不存在、类型不符或不可访问。");
    }
}
