package com.wenjie.studio.review;

/** 原域进度和成长投影共享适用性；调用前仍须验证比较、答卷与固定来源身份。 */
public final class RepracticeApplicability {
    private RepracticeApplicability() {}

    public static boolean applicable(boolean hasLatest, ReviewDtos.ActionStatus actionStatus,
                                     ReviewDtos.Tracking tracking, boolean verificationMatches,
                                     RecollectionDtos.SourceState recollectionState) {
        return hasLatest && actionStatus == ReviewDtos.ActionStatus.OPEN
                && tracking == ReviewDtos.Tracking.TRACKING && verificationMatches
                && (recollectionState == null || recollectionState == RecollectionDtos.SourceState.CURRENT);
    }
}
