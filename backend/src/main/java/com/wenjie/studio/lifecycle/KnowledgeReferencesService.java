package com.wenjie.studio.lifecycle;

import com.wenjie.studio.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static com.wenjie.studio.lifecycle.KnowledgeReferencesDtos.*;

@Service
public class KnowledgeReferencesService {
    private final KnowledgeReferencesMapper mapper;

    public KnowledgeReferencesService(KnowledgeReferencesMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 20)
    public Page list(String owner, String itemId, String versionId, Kind kind, String cursor, int limit) {
        uuid(owner);
        uuid(itemId);
        uuid(versionId);
        if (kind == null) throw invalid("必须指定引用分类。");
        if (limit < 1 || limit > 20) throw invalid("每页数量须为 1 至 20。");
        After after = cursor(cursor, itemId, versionId, kind);

        // 同一读取快照中先核对根版本归属；目录不调用会加载正文的知识详情流程。
        Source source = mapper.source(owner, itemId, versionId);
        if (source == null) {
            throw new ApiException(404, "KNOWLEDGE_REFERENCE_SOURCE_NOT_FOUND", "知识对象或所选发布版本不存在或不可访问。");
        }
        var rows = mapper.references(owner, itemId, versionId, kind.name(),
                after == null ? null : after.id(), after == null ? 0L : after.revision(), limit + 1);
        if (rows.size() > limit + 1) throw new IllegalStateException("知识引用目录返回数量超出上限");
        List<Entry> items = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
        String next = null;
        if (rows.size() > limit) {
            Entry last = items.getLast();
            next = itemId + "~" + versionId + "~" + kind.name() + "~"
                    + last.referenceId() + "~" + last.referenceRevision();
        }
        // 当前指针仅说明绑定位置，不能把版本变化推断为正文错误或完整删除影响。
        boolean versionFilterApplied = switch (kind) {
            case BOARD_SNAPSHOTS, PREPARATION_VERSIONS, QUESTION_VERSIONS,
                    MEMORY_VERSIONS, RELATION_SUPPLEMENTS, AI_KNOWLEDGE_ADOPTIONS,
                    AI_QUESTION_ADOPTIONS, AI_RELATION_INPUTS, AI_CARD_INPUTS, AI_SOURCE_QA_INPUTS,
                    STANDARD_REVIEW_EVIDENCE, INTERVIEW_MATERIALS, AI_KNOWLEDGE_INPUTS, AI_QUESTION_INPUTS,
                    TRAINING_QUESTION_SNAPSHOTS, TRAINING_DRAFT_DIAGRAMS, TRAINING_ANSWER_DIAGRAMS,
                    MEMORY_ATTEMPT_SOURCES, MEMORY_REVIEW_DEPENDENCIES, MEMORY_REVIEW_OBSERVATIONS,
                    MEMORY_IMPACT_DEPENDENCIES, MEMORY_IMPACT_PREVIOUS, MEMORY_IMPACT_CURRENT -> true;
            default -> false;
        };
        return new Page(kind, versionFilterApplied, "REGISTERED_DIRECT_ONLY", source, items, next);
    }

    static Kind kind(String value) {
        try {
            if (value == null) throw new IllegalArgumentException();
            return Kind.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw invalid("引用分类无效。");
        }
    }

    private static After cursor(String value, String itemId, String versionId, Kind kind) {
        if (value == null) return null;
        if (value.isEmpty() || value.length() > 200) throw invalid("引用目录游标无效。");
        String[] parts = value.split("~", -1);
        if (parts.length != 5 || !itemId.equals(parts[0]) || !versionId.equals(parts[1])
                || !kind.name().equals(parts[2])) {
            throw invalid("游标与知识对象、版本或分类不一致。");
        }
        uuid(parts[3]);
        if (!parts[4].matches("(?:0|[1-9][0-9]{0,18})")) throw invalid("引用目录游标修订无效。");
        try {
            long revision = Long.parseLong(parts[4]);
            if (revision != 0 && kind != Kind.BOARD_SNAPSHOTS
                    && kind != Kind.STANDARD_REVIEW_EVIDENCE && kind != Kind.INTERVIEW_MATERIALS
                    && kind != Kind.TODAY_PLACEMENT_HISTORY) {
                throw invalid("引用目录游标修订无效。");
            }
            return new After(parts[3], revision);
        } catch (NumberFormatException exception) {
            throw invalid("引用目录游标修订无效。");
        }
    }

    private static void uuid(String value) {
        try {
            if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw invalid("对象与版本标识须为标准小写 UUID。");
        }
    }

    static ApiException invalid(String message) {
        return new ApiException(400, "INVALID_KNOWLEDGE_REFERENCE_REQUEST", message);
    }

    private record After(String id, long revision) {}
}
