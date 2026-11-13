package com.wenjie.studio.dataarchive;

import java.nio.file.Path;
import java.sql.Connection;

/**
 * 业务备份之外的生命周期边界。实现必须读取最新独立账本，不能信任包内授权或删除记录。
 * 离线恢复通过 ServiceLoader 装载同一个实现，且不能启动 HTTP 应用或模型 worker。
 */
public interface ArchiveLifecyclePolicy {
    record Checkpoint(long sequence, String sha256) {
        public Checkpoint {
            if (sequence < 0 || sha256 == null || !sha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("生命周期账本检查点无效");
        }
    }

    Checkpoint current(String owner);

    /** 独立意图已落盘而业务事务未提交时，禁止把尚未清除的旧正文导出。 */
    void verifyExportCheckpoint(Connection snapshot, String owner, Checkpoint latest) throws Exception;

    /** 包内检查点只能作为旧基线；必须证明它属于最新独立账本的同一条完整哈希链。 */
    void verifyArchiveCheckpoint(String owner, Checkpoint archived, Checkpoint externallyExpectedLatest);

    /** 调用方已关闭对外访问；实现应在当前事务中清除副本、撤销授权并登记实际应用的账本。 */
    void applyLatest(Connection target, String owner, Checkpoint checkpoint, Path restoredMaterialDirectory) throws Exception;

    /** 隐私清除后仍占位的附件不能把磁盘上的旧副本重新装入导出包。 */
    boolean includeAttachment(Connection snapshot, String owner, String attachmentId) throws Exception;

    /** 只允许向指定的新索引目录重建；不得读取或复制旧索引，也不得发送模型请求。 */
    void rebuildIndexes(Connection target, String owner, Path newIndexDirectory) throws Exception;

    /** 账本与索引验证完成仍不能恢复旧模型授权；开放前由实现核对全部恢复后置条件。 */
    void verifyBeforeOpen(Connection target, String owner, Checkpoint checkpoint, Path newIndexDirectory) throws Exception;
}
