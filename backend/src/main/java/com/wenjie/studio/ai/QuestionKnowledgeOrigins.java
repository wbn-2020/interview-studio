package com.wenjie.studio.ai;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.lifecycle.KnowledgeRevalidationQuery;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.wenjie.studio.ai.AiDtos.*;
import static com.wenjie.studio.ai.AiRows.*;

@Component
public class QuestionKnowledgeOrigins {
    private static final int MAX_ORIGINS = 500;
    private final AiMapper mapper;
    private final AiManifestDigest digest;
    private final KnowledgeRevalidationQuery revalidation;
    private final JsonMapper json;

    public QuestionKnowledgeOrigins(AiMapper mapper, AiManifestDigest digest,
                                    KnowledgeRevalidationQuery revalidation, JsonMapper json) {
        this.mapper = mapper;
        this.digest = digest;
        this.revalidation = revalidation;
        this.json = json;
    }

    public void requireCurrent(String owner, String questionId, int through) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly() || through < 1) {
            throw new IllegalStateException("AI出题知识资格必须在持有owner锁的新使用命令事务中核对");
        }
        // 新使用核截至所选题库版的全部采用；后续人工改版不能清除实际生成来源。
        var rows = mapper.questionOrigins(owner, questionId, through, null, MAX_ORIGINS + 1);
        if (rows.size() > MAX_ORIGINS) {
            throw new ApiException(422, "QUESTION_ORIGIN_LIMIT", "题目实际采用记录超过本次完整核对上限，请选择其他题目。");
        }
        var sources = read(owner, rows).values().stream().flatMap(List::stream).toList();
        if (sources.stream().anyMatch(source -> source.state() == OriginState.UNAVAILABLE)) {
            throw new ApiException(422, "SOURCE_UNAVAILABLE", "AI出题的原知识来源已不可用，请选择来源适用的题目。");
        }
        if (sources.stream().anyMatch(source -> source.state() != OriginState.CURRENT)) {
            throw new ApiException(409, "SOURCE_CHANGED", "AI出题的原知识来源已变化，请核对后另选适用题目或重新生成题目。");
        }
    }

    public HashMap<String, List<OriginSource>> read(String owner, List<OriginRow> rows) {
        if (rows.isEmpty()) return new HashMap<>();
        var taskIds = rows.stream().map(OriginRow::taskId).toList();
        var manifests = new HashMap<String, Manifest>();
        for (OriginRow row : rows) {
            Manifest manifest = json.readValue(row.manifestJson(), Manifest.class);
            if (!Purpose.QUESTION_DRAFT.name().equals(row.taskPurpose()) || !Status.SUCCEEDED.name().equals(row.taskStatus())
                    || !AiSources.validDraftInput(manifest) || manifest.purpose() != Purpose.QUESTION_DRAFT
                    || !Objects.equals(row.manifestHash(), digest.hash(manifest)) || row.targetVersionNumber() < 1
                    || row.taskCreatedAt() == null || row.adoptedAt() == null || row.adoptedAt().isBefore(row.taskCreatedAt())
                    || manifests.put(row.taskId(), manifest) != null) {
                throw new IllegalStateException("题目采用历史的任务或固定清单无效");
            }
        }
        var relations = new HashMap<String, List<OriginSourceRow>>();
        for (OriginSourceRow source : mapper.questionOriginSources(owner, taskIds)) {
            if (source == null || !manifests.containsKey(source.taskId())) {
                throw new IllegalStateException("知识来源关系不属于本页题目采用历史");
            }
            relations.computeIfAbsent(source.taskId(), ignored -> new ArrayList<>()).add(source);
        }
        var result = new HashMap<String, List<OriginSource>>();
        for (OriginRow row : rows) {
            Manifest manifest = manifests.get(row.taskId());
            var recorded = relations.getOrDefault(row.taskId(), List.of());
            var values = new ArrayList<OriginSource>();
            int knowledgeIndex = 0;
            for (int index = 0; index < manifest.sources().size(); index++) {
                Source fixed = manifest.sources().get(index);
                if (!AiSources.knowledgeKind(fixed.kind())) continue;
                if (knowledgeIndex >= recorded.size()) throw new IllegalStateException("题目采用历史缺少确切知识来源关系");
                OriginSourceRow source = recorded.get(knowledgeIndex++);
                OriginState state;
                try { state = OriginState.valueOf(source.state()); }
                catch (IllegalArgumentException | NullPointerException exception) {
                    throw new IllegalStateException("题目知识来源状态无效", exception);
                }
                if (!Set.of(OriginState.CURRENT, OriginState.SUPERSEDED, OriginState.ARCHIVED, OriginState.UNAVAILABLE).contains(state)) {
                    throw new IllegalStateException("题目知识来源状态无效");
                }
                if (source.ordinal() != index + 1 || !fixed.itemId().equals(source.itemId())
                        || !fixed.versionId().equals(source.versionId()) || fixed.number() != source.number()
                        || !fixed.title().equals(source.title())
                        || (source.kind() == null ? state != OriginState.UNAVAILABLE : !fixed.kind().equals(source.kind()))) {
                    throw new IllegalStateException("知识来源关系的身份、顺序或当时元数据与题目固定清单不一致");
                }
                // 固定清单保留当时元数据；待处理及冻结后才确认的影响都不能把旧采用恢复为当前。
                if ((state == OriginState.CURRENT || state == OriginState.SUPERSEDED)
                        && revalidation.reviewRequired(owner, fixed.itemId(), fixed.versionId(), row.taskCreatedAt())) {
                    state = OriginState.REVIEW_REQUIRED;
                }
                values.add(new OriginSource(fixed.itemId(), fixed.versionId(), fixed.number(), fixed.title(), state, fixed.kind()));
            }
            if (knowledgeIndex != recorded.size()) throw new IllegalStateException("题目采用历史存在清单之外的知识来源关系");
            result.put(row.taskId(), values);
        }
        return result;
    }
}
