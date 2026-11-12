package com.wenjie.studio.review;

import java.time.Instant;
import java.util.List;

// 今日聚合只接收来源进度和确切目标身份，验证正文与判断理由留在复盘域。
public record TodayActionProgress(String actionId, long revision, ReviewDtos.ActionStatus status,
                                  String mergedInto, int sourceCount, int trackingCount, int pendingCount,
                                  String progressHash, List<TargetRef> targetRefs, Instant updatedAt) {
    public TodayActionProgress { targetRefs = List.copyOf(targetRefs); }

    public record TargetRef(String itemId, String versionId) {}
}
