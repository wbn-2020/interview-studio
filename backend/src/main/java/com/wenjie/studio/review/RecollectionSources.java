package com.wenjie.studio.review;

import com.wenjie.studio.common.ApiException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static com.wenjie.studio.review.RecollectionDtos.*;

@Component
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class RecollectionSources {
    private final RecollectionService recollections;

    public RecollectionSources(RecollectionService recollections) {
        this.recollections = recollections;
    }

    public Version version(String owner, String id, String versionId) {
        return recollections.version(owner, id, versionId);
    }

    public Version requireCurrent(String owner, String id, String versionId, long revision) {
        RecollectionRules.revision(revision);
        Recollection current = recollections.detail(owner, id);
        Version version = version(owner, current, versionId);
        if (current.revision() != revision || !current.currentVersion().id().equals(version.id())) {
            throw new ApiException(409, "REVISION_CONFLICT", "回忆已发生变化，请保留输入并核对当前版本。", current.revision());
        }
        if (current.status() != Status.ACTIVE) {
            throw new ApiException(409, "ITEM_ARCHIVED", "请先恢复回忆，再创建动作或复练。");
        }
        return version;
    }

    public SourceState state(String owner, String id, String versionId) {
        Recollection current = recollections.detail(owner, id);
        return state(current, version(owner, current, versionId));
    }

    public RepracticeDtos.RecollectionBaseline baseline(String owner, String id, String versionId, long revision) {
        RecollectionRules.revision(revision);
        Recollection current = recollections.detail(owner, id);
        Version version = version(owner, current, versionId);
        // 创建时修订只作历史依据；补记或归档仅改变状态，不能使旧对照无法读取。
        return new RepracticeDtos.RecollectionBaseline(id, revision, version, state(current, version));
    }

    private Version version(String owner, Recollection current, String versionId) {
        ReviewRules.uuid(versionId);
        return current.currentVersion().id().equals(versionId)
                ? current.currentVersion() : recollections.version(owner, current.id(), versionId);
    }

    private static SourceState state(Recollection current, Version version) {
        return current.status() == Status.ARCHIVED ? SourceState.ARCHIVED
                : current.currentVersion().id().equals(version.id()) ? SourceState.CURRENT : SourceState.SUPERSEDED;
    }
}
