package com.wenjie.studio.material;

import com.wenjie.studio.material.parse.MaterialParserProcess;
import com.wenjie.studio.material.parse.MaterialParserProcess.Result;
import com.wenjie.studio.security.OwnerProperties;
import com.wenjie.studio.dataprivacy.PrivacyLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static com.wenjie.studio.material.MaterialGuards.*;

@Component
@ConditionalOnProperty(name = "studio.material.enabled", havingValue = "true")
public class MaterialRuntime {
    private static final Logger LOG = LoggerFactory.getLogger(MaterialRuntime.class);
    private final MaterialMapper mapper;
    private final MaterialService service;
    private final MaterialTransactions transactions;
    private final MaterialStorage storage;
    private final MaterialProperties properties;
    private final MaterialParserProcess parser;
    private final JsonMapper json;
    private final String owner;
    private final PrivacyLifecycle privacy;

    public MaterialRuntime(MaterialMapper mapper, MaterialService service, MaterialTransactions transactions,
                           MaterialStorage storage, MaterialProperties properties, MaterialParserProcess parser,
                           JsonMapper json, OwnerProperties owner, PrivacyLifecycle privacy) {
        this.mapper = mapper; this.service = service; this.transactions = transactions; this.storage = storage;
        this.properties = properties; this.parser = parser; this.json = json; this.owner = owner.ownerId();
        this.privacy = privacy;
    }

    @Scheduled(scheduler = "materialScheduler", fixedDelay = 3000, initialDelay = 1000)
    public synchronized void tick() {
        MaterialRows.Run run;
        try {
            privacy.requireSynchronized(owner);
            run = transactions.locked(owner, () -> {
                service.recover(owner);
                if (!properties.parserEnabled() || mapper.runningCount(owner) != 0) return null;
                var queued = mapper.queued(owner);
                if (queued == null) return null;
                Instant now = now();
                if (mapper.claimRun(owner, queued.id(), id(), now, now.plusSeconds(300)) != 1) return null;
                return mapper.run(owner, queued.id());
            });
        } catch (RuntimeException exception) {
            LOG.warn("event=material_queue_unavailable exceptionType={}", exception.getClass().getSimpleName());
            return;
        }
        if (run == null) return;
        Result result;
        try {
            var attachment = service.readyAttachment(owner, run.attachmentId());
            if (!attachment.sha256().equals(run.attachmentSha256())) throw new IOException("MATERIAL_FILE_CHANGED");
            Path input = storage.uploadPath(owner, attachment.id(), attachment.readyUploadId(), false);
            storage.verifyDigest(input, attachment);
            Path directory = storage.runDirectory(owner, attachment.id(), run.id(), true);
            result = parser.parse(input, directory, attachment.kind());
            checkResult(result, attachment.kind());
            if (result.status().equals("SUCCEEDED")) {
                storage.fileSize(directory.resolve("result.json"), 2_097_152);
                if (result.hasPreview()) storage.fileSize(directory.resolve("preview.png"), MAX_FILE_BYTES);
            }
        } catch (IOException | RuntimeException exception) {
            result = new Result("FAILED", run.parserVersion(), false, List.of(), "PARSE_SOURCE_OR_RESULT_UNAVAILABLE", List.of(), false);
        }
        try { complete(run, result); }
        catch (RuntimeException exception) {
            // 结果文件已经写完也不能代替数据库提交；旧执行保留额度，租约到期后显示中断。
            LOG.warn("event=material_result_uncommitted exceptionType={}", exception.getClass().getSimpleName());
        }
    }

    private void complete(MaterialRows.Run run, Result result) {
        transactions.locked(owner, () -> {
            String status = "PARSER_INTERRUPTED".equals(result.errorCode()) ? "INTERRUPTED" : result.status();
            if (mapper.finishRun(owner, run.id(), run.claimToken(), status, result.parserVersion(), result.truncated(),
                    json.writeValueAsString(result.warnings()), result.errorCode(), result.segments().size(),
                    result.hasPreview(), now()) != 1) throw new IllegalStateException("解析执行已失去确认资格");
            int ordinal = 0;
            for (var segment : result.segments()) {
                mapper.insertSegment(owner, new MaterialDtos.Segment(id(), run.attachmentId(), run.id(), ++ordinal,
                        segment.locator(), segment.text()));
            }
            return null;
        });
    }

    private static void checkResult(Result result, String kind) throws IOException {
        if (result == null || !Set.of("SUCCEEDED", "FAILED", "UNSUPPORTED").contains(result.status())
                || result.parserVersion() == null || result.parserVersion().length() > 100
                || result.warnings() == null || result.warnings().size() > 20
                || result.segments() == null || result.segments().size() > 1000) throw new IOException("PARSER_RESULT_INVALID");
        int characters = 0;
        for (String warning : result.warnings()) if (warning == null || warning.isBlank() || warning.length() > 500) throw new IOException("PARSER_RESULT_INVALID");
        for (var segment : result.segments()) {
            if (segment == null || segment.locator() == null || segment.locator().isBlank() || segment.locator().length() > 500
                    || segment.text() == null || segment.text().isBlank() || segment.text().length() > 10000) throw new IOException("PARSER_RESULT_INVALID");
            characters += segment.text().length();
        }
        if (characters > 250_000 || (result.hasPreview() && !Set.of("PNG", "JPEG").contains(kind))) throw new IOException("PARSER_RESULT_INVALID");
        if (result.status().equals("SUCCEEDED")) {
            if (result.errorCode() != null) throw new IOException("PARSER_RESULT_INVALID");
        } else if (result.errorCode() == null || !result.errorCode().matches("[A-Z0-9_]{1,64}")
                || !result.segments().isEmpty() || result.hasPreview()) throw new IOException("PARSER_RESULT_INVALID");
    }
}
