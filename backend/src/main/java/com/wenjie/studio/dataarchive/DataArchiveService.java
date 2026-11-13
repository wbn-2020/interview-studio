package com.wenjie.studio.dataarchive;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import com.wenjie.studio.material.MaterialStorage;
import com.wenjie.studio.training.TrainingAccessGuard;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static com.wenjie.studio.dataarchive.DataArchiveDtos.*;

@Service
public class DataArchiveService {
    private final DataArchiveMapper mapper;
    private final IdempotencyService commands;
    private final DataArchiveProperties properties;
    private final ObjectProvider<ArchiveLifecyclePolicy> policies;
    private final TrainingAccessGuard training;
    private final DataSource database;
    private final MaterialStorage materials;
    private final JsonMapper json;

    public DataArchiveService(DataArchiveMapper mapper, IdempotencyService commands, DataArchiveProperties properties,
                              ObjectProvider<ArchiveLifecyclePolicy> policies, TrainingAccessGuard training,
                              DataSource database, MaterialStorage materials, JsonMapper json) {
        this.mapper = mapper; this.commands = commands; this.properties = properties; this.policies = policies;
        this.training = training; this.database = database; this.materials = materials; this.json = json;
    }

    public Status status(String owner) {
        training.checkHttp(owner, "GET", "/api/v1/lifecycle/archives/status", null);
        boolean ready = false;
        if (policies.getIfUnique() != null) {
            try (var snapshot = database.getConnection()) {
                snapshot.setAutoCommit(false);
                try {
                    policy().verifyExportCheckpoint(snapshot, owner, policy().current(owner));
                    ready = true;
                } finally { snapshot.rollback(); }
            } catch (Exception ignored) { ready = false; }
        }
        return new Status(properties.enabled(), ready, ArchiveFormat.VERSION, properties.expiryHours(),
                properties.maximumBytes(), properties.maximumRows(), ArchiveFormat.EXCLUSIONS);
    }

    public Job create(String owner, String key, Create request) {
        if (request == null || !request.confirmedSensitiveData()) throw new ApiException(400, "ARCHIVE_CONFIRMATION_REQUIRED", "完整导出含私人正文与历史，请先确认导出范围。");
        requireEnabled();
        return commands.execute(owner, key, "POST /api/v1/lifecycle/archives", request, Job.class, () -> {
            requireEnabled();
            training.checkHttp(owner, "POST", "/api/v1/lifecycle/archives", null);
            var connection = DataSourceUtils.getConnection(database);
            try { policy().verifyExportCheckpoint(connection, owner, policy().current(owner)); }
            catch (Exception exception) {
                throw new ApiException(503, "ARCHIVE_LIFECYCLE_UNAVAILABLE", "独立隐私账本与业务检查点未对齐，不能创建完整导出。");
            } finally { DataSourceUtils.releaseConnection(connection, database); }
            if (mapper.active(owner) != 0) throw new ApiException(409, "ARCHIVE_ALREADY_ACTIVE", "已有完整导出排队或执行中，请先查看原作业。");
            if (mapper.retained(owner) >= 50) throw new ApiException(409, "ARCHIVE_RETENTION_LIMIT", "导出保留记录已达上限。失效不代表物理文件已删除，请由服务器管理员按精确清单处理保留文件。");
            String id = UUID.randomUUID().toString();
            Instant now = Instant.now();
            mapper.insert(owner, id, now, now.plus(properties.expiryHours(), ChronoUnit.HOURS));
            return dto(mapper.find(owner, id), null);
        });
    }

    public JobPage list(String owner) {
        training.checkHttp(owner, "GET", "/api/v1/lifecycle/archives", null);
        requireEnabled();
        var checkpoint = policy().current(owner);
        return new JobPage(mapper.list(owner).stream().map(row -> dto(row, checkpoint)).toList());
    }

    public Job find(String owner, String id) {
        validId(id); training.checkHttp(owner, "GET", "/api/v1/lifecycle/archives/" + id, null);
        requireEnabled();
        var row = mapper.find(owner, id);
        if (row == null) throw new ApiException(404, "ARCHIVE_NOT_FOUND", "完整导出作业不存在。");
        return dto(row, policy().current(owner));
    }

    @FunctionalInterface
    public interface Download { void write(Job job, Path file) throws IOException; }

    @Transactional(timeout = 300, rollbackFor = Exception.class)
    public void download(String owner, String id, Download response) throws IOException {
        validId(id); requireEnabled();
        // 文件流全程持有同一工作区锁，开始训练或撤权不能在下载资格核对与读取之间穿过。
        training.checkHttp(owner, "GET", "/api/v1/lifecycle/archives/" + id + "/content", null);
        var row = mapper.find(owner, id);
        if (row == null) throw new ApiException(404, "ARCHIVE_NOT_FOUND", "完整导出作业不存在。");
        Job job = dto(row, policy().current(owner));
        if (!job.downloadable()) throw new ApiException(409, "ARCHIVE_NOT_DOWNLOADABLE", "导出尚未完成、已失效，或删除／撤权记录已变化，请重新导出。");
        Path file = output(owner, id, false);
        if (ArchivePaths.regular(file, properties.maximumBytes(), false) != row.byteSize()
                || !row.sha256().equals(ArchivePaths.sha256(file, properties.maximumBytes())))
            throw new ApiException(409, "ARCHIVE_FILE_CHANGED", "导出文件缺失或校验失败，请重新创建导出。");
        response.write(job, file);
    }

    void export(DataArchiveMapper.Row row) throws Exception {
        requireEnabled();
        Path file = output(row.ownerId(), row.id(), true);
        ArchiveFormat format = format();
        Manifest manifest;
        try (Connection source = database.getConnection()) {
            source.setAutoCommit(false);
            source.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                lockOwner(source, row.ownerId());
                assertQuiescent(source);
                var schema = ArchiveSchema.read(source, json);
                ArchiveSchema.ownerOnly(source, schema, row.ownerId());
                manifest = format.write(source, schema, row.ownerId(), row.id(), row.createdAt(), row.expiresAt(), policy(), materials, file);
                source.rollback();
            } catch (Exception exception) { source.rollback(); throw exception; }
        }
        var inspected = format.inspect(file);
        if (!manifest.equals(inspected.manifest())) throw new IOException("ARCHIVE_READBACK_MISMATCH");
        if (mapper.ready(row.ownerId(), row.id(), Instant.now(), ArchivePaths.regular(file, properties.maximumBytes(), false),
                inspected.archiveSha256(), manifest.rowCount(), (int) manifest.entries().stream().filter(entry -> !"TABLE".equals(entry.kind())).count(),
                manifest.lifecycleCheckpoint().sequence(), manifest.lifecycleCheckpoint().sha256()) != 1)
            throw new IOException("ARCHIVE_COMPLETION_LOST");
    }

    private static void lockOwner(Connection source, String owner) throws SQLException {
        try (var query = source.prepareStatement("SELECT owner_id FROM owner_workspace WHERE owner_id=? FOR UPDATE")) {
            query.setQueryTimeout(10); query.setString(1, owner);
            try (var result = query.executeQuery()) {
                if (!result.next()) throw new SQLException("ARCHIVE_OWNER_NOT_FOUND");
            }
        }
    }

    private static void assertQuiescent(Connection source) throws SQLException {
        for (String sql : List.of(
                "SELECT 1 FROM ai_task WHERE status IN ('QUEUED','RUNNING','VALIDATING','CANCEL_REQUESTED') LIMIT 1",
                "SELECT 1 FROM ai_attempt WHERE status IN ('QUEUED','RUNNING','VALIDATING','CANCEL_REQUESTED') LIMIT 1",
                "SELECT 1 FROM material_upload WHERE status='RECEIVING' LIMIT 1",
                "SELECT 1 FROM material_parse_run WHERE status IN ('QUEUED','RUNNING') LIMIT 1")) {
            try (var query = source.createStatement()) {
                query.setQueryTimeout(5);
                try (var result = query.executeQuery(sql)) {
                    if (result.next()) throw new SQLException("ARCHIVE_BACKGROUND_WORK_ACTIVE");
                }
            }
        }
    }

    Path output(String owner, String id, boolean create) throws IOException {
        Path root = ArchivePaths.root(properties.directory());
        Path directory = ArchivePaths.childDirectory(root, ArchivePaths.uuid(owner), create);
        return directory.resolve(ArchivePaths.uuid(id) + ".zip");
    }

    ArchiveFormat format() { return new ArchiveFormat(json, properties.maximumBytes(), properties.maximumRows()); }

    private ArchiveLifecyclePolicy policy() {
        var result = policies.getIfUnique();
        if (result == null) throw new ApiException(503, "ARCHIVE_LIFECYCLE_UNAVAILABLE", "最新独立删除／撤权账本尚不可用，完整导出保持关闭。");
        return result;
    }

    private void requireEnabled() {
        if (!properties.enabled()) throw new ApiException(503, "ARCHIVE_DISABLED", "服务器尚未启用完整导出。");
        policy();
        try { ArchivePaths.root(properties.directory()); }
        catch (IOException exception) { throw new ApiException(503, "ARCHIVE_STORAGE_UNAVAILABLE", "完整导出私有目录不可用，请检查服务器配置。"); }
    }

    private static void validId(String id) {
        try { ArchivePaths.uuid(id); }
        catch (IllegalArgumentException exception) { throw new ApiException(400, "INVALID_ARCHIVE_ID", "导出标识须为标准小写 UUID。"); }
    }

    private static Job dto(DataArchiveMapper.Row row, ArchiveLifecyclePolicy.Checkpoint checkpoint) {
        State state = State.valueOf(row.state());
        if (state == State.READY && !row.expiresAt().isAfter(Instant.now())) state = State.EXPIRED;
        boolean downloadable = state == State.READY && checkpoint != null && row.ledgerSequence() != null
                && row.ledgerSequence() == checkpoint.sequence() && checkpoint.sha256().equals(row.ledgerSha256());
        return new Job(row.id(), state, row.formatVersion(), row.createdAt(), row.startedAt(), row.completedAt(), row.expiresAt(),
                row.byteSize(), row.sha256(), row.rowCount(), row.fileCount(), row.errorCode(), downloadable);
    }
}
