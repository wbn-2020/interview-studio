package com.wenjie.studio.dataarchive;

import com.wenjie.studio.security.OwnerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public final class DataArchiveRuntime {
    private static final Logger LOG = LoggerFactory.getLogger(DataArchiveRuntime.class);
    private final DataArchiveProperties properties;
    private final DataArchiveMapper mapper;
    private final DataArchiveService service;
    private final ArchiveServingGate gate;
    private final String owner;

    public DataArchiveRuntime(DataArchiveProperties properties, DataArchiveMapper mapper, DataArchiveService service,
                              ArchiveServingGate gate, OwnerProperties owner) {
        this.properties = properties; this.mapper = mapper; this.service = service; this.gate = gate; this.owner = owner.ownerId();
    }

    @Scheduled(scheduler = "dataArchiveScheduler", fixedDelay = 5000, initialDelay = 3000)
    public synchronized void tick() {
        if (!properties.enabled()) return;
        DataArchiveMapper.Row row = null;
        try {
            gate.requireOpen();
            Instant now = Instant.now();
            mapper.recover(owner, now.minusSeconds(600), now);
            mapper.expire(owner, now);
            row = mapper.queued(owner);
            if (row == null || mapper.claim(owner, row.id(), now) != 1) return;
            service.export(mapper.find(owner, row.id()));
        } catch (Exception exception) {
            // SQL或文件异常可能带正文与目录，只记录类型；失败包保留隔离，不进入下载列表。
            LOG.warn("event=data_archive_failed exceptionType={}", exception.getClass().getSimpleName());
            if (row != null) {
                String message = exception.getMessage();
                String code = message != null && java.util.Set.of("ARCHIVE_BACKGROUND_WORK_ACTIVE", "ARCHIVE_TIME_LIMIT",
                                "ARCHIVE_RECORD_LIMIT", "ARCHIVE_PAYLOAD_LIMIT", "ARCHIVE_ATTACHMENT_CHANGED",
                                "ARCHIVE_SCHEMA_MIGRATION_MISMATCH", "ARCHIVE_OTHER_OWNER_PRESENT",
                                "ARCHIVE_CREDENTIAL_COLUMN_UNSUPPORTED", "ARCHIVE_LIFECYCLE_CHANGED")
                        .contains(message) ? message : "ARCHIVE_EXPORT_FAILED";
                try { mapper.failed(owner, row.id(), Instant.now(), code); }
                catch (RuntimeException ignored) { LOG.warn("event=data_archive_failure_uncommitted"); }
            }
        }
    }
}
