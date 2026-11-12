package com.wenjie.studio.search;

import com.wenjie.studio.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UnifiedSearchReadChecks {
    private final UnifiedSearchHistorySources history;
    private final UnifiedSearchAiSources ai;

    public UnifiedSearchReadChecks(UnifiedSearchHistorySources history, UnifiedSearchAiSources ai) {
        this.history = history;
        this.ai = ai;
    }

    @Transactional(readOnly = true, timeout = 5)
    public void check(String owner, Object body) {
        List<UnifiedSearchDtos.Hit> items;
        if (body instanceof UnifiedSearchDtos.Page page) items = page.items();
        else if (body instanceof UnifiedSearchDtos.Source source) items = List.of(source.item());
        else return;
        for (UnifiedSearchDtos.Hit hit : items) {
            if (hit.domain() == UnifiedSearchDtos.Domain.AI_FEEDBACK) {
                ai.requireAccess(owner, hit);
                continue;
            }
            if (hit.domain() != UnifiedSearchDtos.Domain.REVIEW_FEEDBACK) continue;
            if (hit.training() == null || hit.feedback() == null || hit.versionNumber() == null
                    || hit.versionNumber() < 1 || hit.versionNumber() > 100 || !hit.id().equals(hit.versionId())) {
                throw new ApiException(409, "SEARCH_SOURCE_INVALID", "反馈检索来源身份无法确认，请重新查询。");
            }
            // 正文离开原查询快照后再核原答访问；普通资料放行不等于评价区已经开放。
            history.requireFeedbackAccess(owner, hit.training());
        }
    }
}
