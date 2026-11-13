package com.wenjie.studio.lifecycle;

import com.wenjie.studio.common.ApiException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static com.wenjie.studio.lifecycle.KnowledgeRevalidationRules.*;

@Component
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class KnowledgeRevalidationQuery {
    private final KnowledgeRevalidationMapper mapper;

    public KnowledgeRevalidationQuery(KnowledgeRevalidationMapper mapper) { this.mapper = mapper; }

    public boolean pending(String owner, String itemId, String versionId) {
        return mapper.pending(owner, itemId, number(owner, itemId, versionId));
    }

    public void requireClear(String owner, String itemId, String versionId) {
        if (pending(owner, itemId, versionId)) {
            throw new ApiException(409, "KNOWLEDGE_REVALIDATION_REQUIRED", "历史采用来源仍待核对，请先处理知识的来源更正。");
        }
    }

    public boolean changedAfter(String owner, String itemId, String versionId, Instant baseline) {
        time(baseline);
        // 已解决的影响也代表基线之后发生过变化；处理不能恢复旧授权或抹去旧证据的失效依据。
        return mapper.changedAfter(owner, itemId, number(owner, itemId, versionId), baseline);
    }

    public boolean reviewRequired(String owner, String itemId, String versionId, Instant baseline) {
        try { time(baseline); }
        catch (ApiException exception) { throw integrity(); }
        // 冻结前尚待处理、冻结后才确认保留的来源，也不能自动恢复这份历史题目的当前性。
        return mapper.reviewRequired(owner, itemId, number(owner, itemId, versionId), baseline);
    }

    private int number(String owner, String itemId, String versionId) {
        uuid(owner); uuid(itemId); uuid(versionId);
        Integer number = mapper.versionNumber(owner, itemId, versionId);
        if (number == null) throw missing();
        if (number < 1) throw integrity();
        return number;
    }
}
