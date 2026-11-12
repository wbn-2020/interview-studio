package com.wenjie.studio.ai;

import java.time.Instant;

/** 改写来源绑定独立保存，避免把只读参考误计入正式评价任务。 */
public final class RewriteRows {
    private RewriteRows() {}

    public record BindingRow(String ownerId, String taskId, String sessionId, String questionId,
                             String answerId, Instant createdAt) {}
}
