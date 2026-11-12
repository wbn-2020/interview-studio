package com.wenjie.studio.ai;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.training.TrainingAccessGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SourceQaReadChecks {
    private final SourceQaSources sources;
    private final TrainingAccessGuard training;

    public SourceQaReadChecks(SourceQaSources sources, TrainingAccessGuard training) {
        this.sources = sources;
        this.training = training;
    }

    @Transactional(readOnly = true, timeout = 5)
    public void check(String owner, Object body) {
        AiDtos.Manifest manifest;
        String taskId = null;
        if (body instanceof AiDtos.Preview preview) manifest = preview.manifest();
        else if (body instanceof AiDtos.Task task) {
            manifest = task.manifest();
            if (task.task() != null) taskId = task.task().id();
        } else return;
        if (manifest == null || manifest.purpose() != AiDtos.Purpose.SOURCE_QA && manifest.sourceQa() == null) return;
        if (!AiSources.validSourceQaInput(manifest)) throw new ApiException(409, "AI_SOURCE_QA_BINDING_INVALID",
                "跨域问答的固定来源无法核实，正文暂不展示。");
        // 写回执也含完整清单；独立读取事务再核普通资料条件，不重新申请或改变训练辅助状态。
        training.checkCommand(owner, "POST /api/v1/ai/source-qa/tasks");
        if (taskId != null) sources.requireBinding(owner, taskId, manifest.sourceQa());
        // 原查询事务结束后再核原答评价许可；普通资料放行不能替代每份反馈的读取许可。
        sources.requireAccess(owner, manifest.sourceQa());
    }
}
