package com.wenjie.studio.material;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import com.wenjie.studio.material.MaterialDtos.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.wenjie.studio.material.MaterialGuards.*;

@Service
public class MaterialService {
    private final MaterialMapper mapper;
    private final MaterialProperties properties;
    private final MaterialStorage storage;
    private final MaterialTransactions transactions;
    private final IdempotencyService commands;
    private final JsonMapper json;
    private final MaterialLifecycleService lifecycle;

    public MaterialService(MaterialMapper mapper, MaterialProperties properties, MaterialStorage storage,
                           MaterialTransactions transactions, IdempotencyService commands, JsonMapper json,
                           MaterialLifecycleService lifecycle) {
        this.mapper = mapper; this.properties = properties; this.storage = storage;
        this.transactions = transactions; this.commands = commands; this.json = json; this.lifecycle = lifecycle;
    }

    public Status status(String owner) {
        return new Status(properties.enabled(), mapper.usedBytes(owner), properties.quotaBytes(), MAX_FILE_BYTES,
                properties.enabled() && properties.parserEnabled());
    }

    public AttachmentPage list(String owner, String cursor, String query, int limit) {
        enabled(); limit(limit); if (cursor != null) uuid(cursor);
        if (query != null && query.length() > 100) throw invalid("文件名筛选最多 100 字符。");
        String pattern = query == null || query.isBlank() ? null : "%" + query.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        var rows = mapper.attachments(owner, cursor, pattern, limit + 1);
        var page = rows.subList(0, Math.min(rows.size(), limit));
        return new AttachmentPage(page.stream().map(row -> new AttachmentSummary(row.id(), row.fileName(), Kind.valueOf(row.kind()),
                row.byteSize(), row.description(), row.status(), row.revision(), row.createdAt(), row.updatedAt())).toList(),
                rows.size() > limit ? page.getLast().id() : null);
    }

    public Attachment attachment(String owner, String id) {
        enabled(); uuid(id);
        return transactions.locked(owner, () -> { recover(owner); return detail(requireAttachment(owner, id)); });
    }

    public Attachment create(String owner, String key, CreateAttachment request) {
        enabled(); required(request);
        if (request.kind() == null || request.byteSize() == null || request.byteSize() < 1 || request.byteSize() > MAX_FILE_BYTES)
            throw invalid("文件格式和大小无效；单文件最多 20 MiB。");
        text(request.fileName(), 200, false); text(request.description(), 5000, true);
        if (request.fileName().codePoints().anyMatch(Character::isISOControl) || request.fileName().contains("/") || request.fileName().contains("\\")) throw invalid("文件名不能包含控制字符或路径分隔符。");
        String name = request.fileName().toLowerCase(Locale.ROOT);
        boolean suffix = switch (request.kind()) {
            case PDF -> name.endsWith(".pdf"); case DOCX -> name.endsWith(".docx");
            case PNG -> name.endsWith(".png"); case JPEG -> name.endsWith(".jpg") || name.endsWith(".jpeg");
        };
        if (!suffix || request.sha256() == null || !request.sha256().matches("[0-9a-f]{64}")) throw invalid("文件后缀与格式须匹配，摘要须为小写 SHA-256。");
        storage.verifyAvailable();
        return commands.execute(owner, key, "POST /api/v1/materials/attachments", request, Attachment.class, () -> {
            reserve(owner, request.byteSize());
            Instant now = now();
            var row = new MaterialRows.Attachment(id(), owner, request.fileName(), request.kind().name(), request.byteSize(),
                    request.sha256(), request.description(), "RESERVED", 0, null, now, now);
            mapper.insertAttachment(row);
            mapper.insertUpload(newUpload(owner, row, now));
            return detail(row);
        });
    }

    public Attachment description(String owner, String id, String key, ChangeDescription request) {
        enabled(); uuid(id); required(request); revision(request.expectedRevision()); text(request.description(), 5000, true);
        return commands.execute(owner, key, "PUT /api/v1/materials/attachments/" + id + "/description", request, Attachment.class, () -> {
            var row = requireAttachment(owner, id); changeable(row, request.expectedRevision());
            changed(mapper.description(owner, id, row.revision(), request.description(), now()));
            return detail(requireAttachment(owner, id));
        });
    }

    public Upload retryUpload(String owner, String id, String key, RetryUpload request) {
        enabled(); uuid(id); required(request); revision(request.expectedRevision()); storage.verifyAvailable();
        return commands.execute(owner, key, "POST /api/v1/materials/attachments/" + id + "/uploads", request, Upload.class, () -> {
            recover(owner);
            var row = requireAttachment(owner, id); changeable(row, request.expectedRevision());
            if (row.status().equals("READY")) throw new ApiException(409, "ATTACHMENT_IMMUTABLE", "原附件已确认，不能替换字节；不同文件请新建附件。");
            var attempts = mapper.uploads(owner, id);
            if (attempts.stream().anyMatch(item -> Set.of("RESERVED", "RECEIVING").contains(item.status()))) throw new ApiException(409, "UPLOAD_IN_PROGRESS", "已有尚未结束的上传尝试，请先读取其状态。");
            if (attempts.size() >= 3) throw new ApiException(409, "UPLOAD_ATTEMPT_LIMIT", "此附件已达到三次上传尝试上限，保留文件仍计入容量。");
            reserve(owner, row.byteSize());
            Instant now = now(); var attempt = newUpload(owner, row, now);
            mapper.insertUpload(attempt); changed(mapper.touchAttachment(owner, id, row.revision(), now));
            return uploadView(attempt);
        });
    }

    public Upload upload(String owner, String id) {
        enabled(); uuid(id);
        return transactions.locked(owner, () -> { recover(owner); return uploadView(requireUpload(owner, id)); });
    }

    public Attachment receive(String owner, String uploadId, String sha256, long contentLength, InputStream input) {
        enabled(); uuid(uploadId); storage.verifyAvailable();
        UploadClaim claim = transactions.locked(owner, () -> {
            recover(owner); var upload = requireUpload(owner, uploadId);
            var attachment = requireAttachment(owner, upload.attachmentId());
            if (!attachment.sha256().equals(sha256) || (contentLength >= 0 && contentLength != attachment.byteSize())) throw invalid("传输摘要或长度与上传意图不一致。");
            if (upload.status().equals("SUCCEEDED") && upload.id().equals(attachment.readyUploadId())) return new UploadClaim(attachment, null);
            if (upload.status().equals("RECEIVING")) throw new ApiException(409, "UPLOAD_IN_PROGRESS", "该上传正在接收；请读取状态后再重试。");
            if (!upload.status().equals("RESERVED") || attachment.status().equals("READY")) throw new ApiException(409, "UPLOAD_ATTEMPT_CLOSED", "上传尝试已结束；请保留状态并显式创建新的尝试。");
            String token = id(); Instant now = now();
            changed(mapper.claimUpload(owner, uploadId, token, now, now.plusSeconds(300)));
            return new UploadClaim(attachment, token);
        });
        if (claim.token() == null) return attachment(owner, claim.attachment().id());
        long received;
        try {
            Path destination = storage.uploadPath(owner, claim.attachment().id(), uploadId, true);
            received = storage.receive(destination, input, claim.attachment());
        } catch (MaterialStorage.TransferFailure failure) {
            finishFailedUpload(owner, uploadId, claim.token(), failure.received(), failure.code());
            throw new ApiException(422, failure.code(), "上传未确认。请读取尝试状态；原部分文件和预留容量仍保留。");
        } catch (IOException | RuntimeException exception) {
            finishFailedUpload(owner, uploadId, claim.token(), 0, "UPLOAD_IO_FAILED");
            throw new ApiException(503, "UPLOAD_IO_FAILED", "上传文件不可写，请读取状态并检查服务器资料目录。");
        }
        return transactions.locked(owner, () -> {
            recover(owner);
            if (mapper.finishUpload(owner, uploadId, claim.token(), "SUCCEEDED", received, null, now()) != 1)
                throw new ApiException(409, "UPLOAD_INTERRUPTED", "本次传输已失去确认资格，请读取上传状态。");
            changed(mapper.ready(owner, claim.attachment().id(), uploadId, now()));
            return detail(requireAttachment(owner, claim.attachment().id()));
        });
    }

    private void finishFailedUpload(String owner, String id, String token, long received, String code) {
        transactions.locked(owner, () -> { mapper.finishUpload(owner, id, token, "FAILED", received, code, now()); return null; });
    }

    public ParseRun createRun(String owner, String id, String key, EmptyRequest request) {
        enabled(); uuid(id); required(request);
        if (!properties.parserEnabled()) throw new ApiException(503, "PARSER_DISABLED", "服务器尚未启用资料解析。");
        storage.verifyAvailable();
        return commands.execute(owner, key, "POST /api/v1/materials/attachments/" + id + "/runs", request, ParseRun.class, () -> {
            recover(owner); var attachment = readyAttachment(owner, id);
            if (mapper.pendingRuns(owner, id) != 0) throw new ApiException(409, "PARSE_IN_PROGRESS", "此附件已有待执行或进行中的解析。");
            if (mapper.runCount(owner, id) >= 10) throw new ApiException(409, "PARSE_ATTEMPT_LIMIT", "此附件已经达到十次解析执行上限。");
            if (mapper.pendingRuns(owner, null) >= 20) throw new ApiException(409, "PARSE_QUEUE_FULL", "解析队列已满，请等待当前任务结束。");
            long bytes = Set.of("PNG", "JPEG").contains(attachment.kind()) ? 25_165_824L : 2_097_152L;
            reserve(owner, bytes);
            var run = new MaterialRows.Run(id(), owner, id, attachment.sha256(), "QUEUED", bytes,
                    parserVersion(attachment.kind()), false, "[]", null, 0, false, null, null, now(), null, null);
            mapper.insertRun(run);
            return runView(run);
        });
    }

    public ParseRun run(String owner, String id) {
        enabled(); uuid(id);
        return transactions.locked(owner, () -> { recover(owner); return runView(requireRun(owner, id)); });
    }

    public RunPage runs(String owner, String id, String before, int limit) {
        enabled(); uuid(id); limit(limit); requireAttachment(owner, id);
        Instant beforeTime = null; String beforeId = null;
        if (before != null) {
            String[] parts = before.split("~", -1);
            if (parts.length != 2) throw invalid("解析历史游标无效。");
            beforeTime = cursorTime(parts[0]); beforeId = uuid(parts[1]);
        }
        var rows = mapper.runs(owner, id, beforeTime, beforeId, limit + 1);
        var page = rows.subList(0, Math.min(rows.size(), limit));
        return new RunPage(page.stream().map(this::runView).toList(), rows.size() > limit ? page.getLast().createdAt() + "~" + page.getLast().id() : null);
    }

    public SegmentPage segments(String owner, String runId, int after, int limit) {
        enabled(); uuid(runId); limit(limit);
        if (after < 0 || after > 1000) throw invalid("片段位置无效。");
        var run = requireRun(owner, runId);
        if (!run.status().equals("SUCCEEDED")) throw new ApiException(409, "PARSE_NOT_SUCCEEDED", "只有已完成的解析结果可以读取片段。");
        var rows = mapper.segments(owner, runId, after, limit + 1);
        var page = rows.subList(0, Math.min(rows.size(), limit));
        return new SegmentPage(List.copyOf(page), rows.size() > limit ? page.getLast().ordinal() : null);
    }

    public Excerpt createExcerpt(String owner, String attachmentId, String key, CreateExcerpt request) {
        enabled(); uuid(attachmentId); required(request);
        return commands.execute(owner, key, "POST /api/v1/materials/attachments/" + attachmentId + "/excerpts", request, Excerpt.class, () -> {
            var attachment = readyAttachment(owner, attachmentId);
            String runId = null, origin, locator, content;
            if (request.segmentId() != null) {
                uuid(request.segmentId());
                if (request.locator() != null || request.text() != null || request.startOffset() == null || request.endOffset() == null) throw invalid("解析摘录只接受片段与起止位置，原文由服务器取得。");
                var segment = mapper.segment(owner, request.segmentId());
                if (segment == null || !segment.attachmentId().equals(attachmentId)) throw missing();
                var run = requireRun(owner, segment.runId());
                if (!run.status().equals("SUCCEEDED") || !run.attachmentSha256().equals(attachment.sha256())) throw missing();
                int start = request.startOffset(), end = request.endOffset();
                if (start < 0 || end <= start || end > segment.text().length() || splitsSurrogate(segment.text(), start) || splitsSurrogate(segment.text(), end)) throw invalid("摘录范围超出片段或切断字符。");
                runId = segment.runId(); origin = "PARSED"; locator = segment.locator(); content = segment.text().substring(start, end);
            } else {
                if (request.startOffset() != null || request.endOffset() != null) throw invalid("手工摘录不能伪造解析偏移。");
                locator = text(request.locator(), 500, false); content = text(request.text(), 10000, false); origin = "MANUAL";
            }
            text(content, 10000, false);
            var row = new MaterialRows.Excerpt(id(), owner, attachmentId, attachment.sha256(), runId, request.segmentId(),
                    origin, locator, content, request.startOffset(), request.endOffset(), now());
            mapper.insertExcerpt(row);
            lifecycle.initialize(owner, row.id(), attachmentId, row.createdAt());
            return excerptView(row);
        });
    }

    public Excerpt excerpt(String owner, String id) { enabled(); return excerptView(requireExcerpt(owner, uuid(id))); }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 20)
    public ExcerptPage excerpts(String owner, String id, String cursor, String query, int limit) {
        return excerpts(owner, id, cursor, query, limit, MaterialLifecycleDtos.StatusFilter.ACTIVE, MaterialLifecycleDtos.AiUseFilter.ALL);
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 20)
    public ExcerptPage excerpts(String owner, String id, String cursor, String query, int limit,
                                MaterialLifecycleDtos.StatusFilter status, MaterialLifecycleDtos.AiUseFilter aiUse) {
        enabled(); uuid(id); limit(limit); requireAttachment(owner, id); if (cursor != null) uuid(cursor);
        if (status == null || aiUse == null) throw invalid("摘录状态与 AI 用途筛选无效。");
        lifecycle.requireBaselines(owner, id);
        String keyword = text(query == null ? "" : query.strip(), 100, true);
        String pattern = keyword.isEmpty() ? null : "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        var rows = mapper.excerpts(owner, id, cursor, pattern, status.name(), aiUse.name(), limit + 1);
        var page = rows.subList(0, Math.min(rows.size(), limit));
        return new ExcerptPage(page.stream().map(this::excerptView).toList(), rows.size() > limit ? page.getLast().id() : null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<Excerpt> requireExcerpts(String owner, List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        if (ids.size() > 20 || new HashSet<>(ids).size() != ids.size()) throw invalid("来源摘录最多 20 个且不能重复。");
        return ids.stream().map(id -> {
            var excerpt = requireExcerpt(owner, uuid(id));
            lifecycle.requireActive(owner, id);
            var attachment = readyAttachment(owner, excerpt.attachmentId());
            if (!attachment.sha256().equals(excerpt.attachmentSha256())) throw missing();
            return excerptView(excerpt);
        }).toList();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void bindPreparationSources(String owner, String itemId, String versionId, List<String> excerptIds) {
        // 来源身份只依赖已保存的数据库记录；关闭解析或暂时失去磁盘不改变历史摘录的归属。
        for (Excerpt excerpt : requireExcerpts(owner, excerptIds)) mapper.insertPreparationSource(owner, itemId, versionId, excerpt.id());
    }

    public Download original(String owner, String id) {
        enabled(); var attachment = readyAttachment(owner, uuid(id));
        try {
            Path path = storage.uploadPath(owner, id, attachment.readyUploadId(), false);
            storage.verifyDigest(path, attachment);
            return new Download(path, attachment.fileName(), mime(attachment.kind()), attachment.byteSize(), false, attachment.sha256());
        } catch (IOException | RuntimeException exception) { throw new ApiException(503, "MATERIAL_FILE_UNAVAILABLE", "附件文件暂不可读，元数据和历史仍保留。"); }
    }

    public Download preview(String owner, String runId) {
        enabled(); var run = requireRun(owner, uuid(runId));
        var attachment = readyAttachment(owner, run.attachmentId());
        if (!run.status().equals("SUCCEEDED") || !run.hasPreview() || !Set.of("PNG", "JPEG").contains(attachment.kind())) throw missing();
        try {
            Path path = storage.runDirectory(owner, attachment.id(), run.id(), false).resolve("preview.png");
            return new Download(path, "preview.png", "image/png", storage.fileSize(path, MAX_FILE_BYTES), true, null);
        } catch (IOException | RuntimeException exception) { throw new ApiException(503, "MATERIAL_PREVIEW_UNAVAILABLE", "图片预览暂不可读，可保留原件并重新解析。"); }
    }

    MaterialRows.Attachment requireAttachment(String owner, String id) { var value = mapper.attachment(owner, id); if (value == null) throw missing(); return value; }
    MaterialRows.Attachment readyAttachment(String owner, String id) { var value = requireAttachment(owner, id); if (!value.status().equals("READY")) throw new ApiException(409, "ATTACHMENT_NOT_READY", "原附件尚未确认完成。"); return value; }
    MaterialRows.Upload requireUpload(String owner, String id) { var value = mapper.upload(owner, id); if (value == null) throw missing(); return value; }
    MaterialRows.Run requireRun(String owner, String id) { var value = mapper.run(owner, id); if (value == null) throw missing(); return value; }
    private MaterialRows.Excerpt requireExcerpt(String owner, String id) { var value = mapper.excerpt(owner, id); if (value == null) throw missing(); return value; }
    void recover(String owner) { Instant now = now(); mapper.recoverUploads(owner, now); mapper.recoverRuns(owner, now); }
    ParseRun runView(MaterialRows.Run row) { return new ParseRun(row.id(), row.attachmentId(), row.status(), row.parserVersion(), row.truncated(), List.of(json.readValue(row.warningsJson(), String[].class)), row.errorCode(), row.segmentCount(), row.createdAt(), row.startedAt(), row.finishedAt(), row.hasPreview()); }
    private Upload uploadView(MaterialRows.Upload row) { return new Upload(row.id(), row.attachmentId(), row.status(), row.reservedBytes(), row.receivedBytes(), row.errorCode(), row.createdAt(), row.startedAt(), row.finishedAt()); }
    private Excerpt excerptView(MaterialRows.Excerpt row) { return new Excerpt(row.id(), row.attachmentId(), row.runId(), row.segmentId(), row.origin(), row.locator(), row.text(), row.startOffset(), row.endOffset(), row.createdAt()); }
    private Attachment detail(MaterialRows.Attachment row) {
        var runs = mapper.runs(row.ownerId(), row.id(), null, null, 1);
        return new Attachment(row.id(), row.fileName(), Kind.valueOf(row.kind()), row.byteSize(), row.sha256(), row.description(), row.status(), row.revision(), row.createdAt(), row.updatedAt(), mapper.uploads(row.ownerId(), row.id()).stream().map(this::uploadView).toList(), runs.isEmpty() ? null : runView(runs.getFirst()));
    }
    private MaterialRows.Upload newUpload(String owner, MaterialRows.Attachment row, Instant now) { return new MaterialRows.Upload(id(), owner, row.id(), "RESERVED", row.byteSize(), 0, null, null, null, now, null, null); }
    private void reserve(String owner, long bytes) { if (properties.quotaBytes() < bytes || mapper.usedBytes(owner) > properties.quotaBytes() - bytes) throw new ApiException(409, "MATERIAL_QUOTA_EXCEEDED", "保留容量不足；失败尝试和历史解析仍计入预留容量。"); }
    private void enabled() { if (!properties.enabled()) throw new ApiException(503, "MATERIALS_DISABLED", "服务器尚未启用附件资料，请先配置私有目录。"); }
    private static void required(Object value) { if (value == null) throw invalid("请求内容不能为空。"); }
    private static void changed(int rows) { if (rows != 1) throw new ApiException(409, "REVISION_CONFLICT", "资料状态已变化，请读取最新状态并保留本地输入。"); }
    private static boolean splitsSurrogate(String text, int index) { return index > 0 && index < text.length() && Character.isHighSurrogate(text.charAt(index - 1)) && Character.isLowSurrogate(text.charAt(index)); }
    static String parserVersion(String kind) { return switch (kind) { case "PDF" -> "PDFBox 3.0.8"; case "DOCX" -> "POI XWPF 6.0.0"; default -> "JDK ImageIO 21"; }; }
    private static String mime(String kind) { return switch (kind) { case "PDF" -> "application/pdf"; case "DOCX" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; case "PNG" -> "image/png"; default -> "image/jpeg"; }; }
    private record UploadClaim(MaterialRows.Attachment attachment, String token) {}
    public record Download(Path path, String fileName, String contentType, long size, boolean inline, String sha256) {}
}
