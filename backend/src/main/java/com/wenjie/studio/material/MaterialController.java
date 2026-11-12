package com.wenjie.studio.material;

import com.wenjie.studio.common.CurrentOwner;
import com.wenjie.studio.material.MaterialDtos.*;
import com.wenjie.studio.training.TrainingAccessGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/materials")
public class MaterialController {
    private final MaterialService service;
    private final MaterialStorage storage;
    private final CurrentOwner owner;
    private final JsonMapper requests;
    private final TrainingAccessGuard trainingAccess;
    public MaterialController(MaterialService service, MaterialStorage storage, CurrentOwner owner, JsonMapper json,
                              TrainingAccessGuard trainingAccess) {
        this.service = service; this.storage = storage; this.owner = owner;
        this.trainingAccess = trainingAccess;
        requests = json.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @GetMapping("/status")
    public Status status() { return service.status(owner.id()); }

    @GetMapping("/attachments")
    public AttachmentPage list(@RequestParam(name = "cursor", required = false) String cursor,
                               @RequestParam(name = "q", required = false) String query,
                               @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return service.list(owner.id(), cursor, query, limit);
    }

    @PostMapping("/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    public Attachment create(@RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode request) {
        return service.create(owner.id(), key, decode(request, CreateAttachment.class));
    }

    @GetMapping("/attachments/{id}")
    public Attachment attachment(@PathVariable("id") String id) { return service.attachment(owner.id(), id); }

    @PutMapping("/attachments/{id}/description")
    public Attachment description(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                                  @RequestBody JsonNode request) {
        return service.description(owner.id(), id, key, decode(request, ChangeDescription.class));
    }

    @PostMapping("/attachments/{id}/uploads")
    @ResponseStatus(HttpStatus.CREATED)
    public Upload retryUpload(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                              @RequestBody JsonNode request) { return service.retryUpload(owner.id(), id, key, decode(request, RetryUpload.class)); }

    @GetMapping("/uploads/{uploadId}")
    public Upload upload(@PathVariable("uploadId") String id) { return service.upload(owner.id(), id); }

    @PutMapping(path = "/uploads/{uploadId}/content", consumes = "application/octet-stream")
    public Attachment content(@PathVariable("uploadId") String id, @RequestHeader("X-Content-SHA256") String digest,
                              HttpServletRequest request) throws IOException {
        return service.receive(owner.id(), id, digest, request.getContentLengthLong(), request.getInputStream());
    }

    @GetMapping("/attachments/{id}/content")
    public void original(@PathVariable("id") String id, HttpServletResponse response) throws IOException {
        download(service.original(owner.id(), id), response);
    }

    @PostMapping("/attachments/{id}/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ParseRun createRun(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                              @RequestBody JsonNode request) { return service.createRun(owner.id(), id, key, decode(request, EmptyRequest.class)); }

    @GetMapping("/attachments/{id}/runs")
    public RunPage runs(@PathVariable("id") String id, @RequestParam(name = "before", required = false) String before,
                        @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return service.runs(owner.id(), id, before, limit);
    }

    @GetMapping("/runs/{runId}")
    public ParseRun run(@PathVariable("runId") String id) { return service.run(owner.id(), id); }

    @GetMapping("/runs/{runId}/segments")
    public SegmentPage segments(@PathVariable("runId") String id,
                                 @RequestParam(name = "after", defaultValue = "0") int after,
                                 @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return service.segments(owner.id(), id, after, limit);
    }

    @GetMapping("/runs/{runId}/preview")
    public void preview(@PathVariable("runId") String id, HttpServletResponse response) throws IOException {
        download(service.preview(owner.id(), id), response);
    }

    @PostMapping("/attachments/{id}/excerpts")
    @ResponseStatus(HttpStatus.CREATED)
    public Excerpt createExcerpt(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                                  @RequestBody JsonNode request) { return service.createExcerpt(owner.id(), id, key, decode(request, CreateExcerpt.class)); }

    @GetMapping("/attachments/{id}/excerpts")
    public ExcerptPage excerpts(@PathVariable("id") String id,
                                @RequestParam MultiValueMap<String, String> query) {
        for (var entry : query.entrySet()) {
            if (!Set.of("cursor", "q", "limit", "status", "aiUse").contains(entry.getKey()) || entry.getValue().size() != 1
                    || entry.getValue().getFirst() == null || !entry.getKey().equals("q") && entry.getValue().getFirst().isEmpty()) {
                throw MaterialGuards.invalid("查询参数未知、重复或为空。");
            }
        }
        String rawLimit = query.getFirst("limit");
        if (rawLimit != null && !rawLimit.matches("(?:[1-9]|[1-4][0-9]|50)")) throw MaterialGuards.invalid("每页数量须为 1–50。");
        MaterialLifecycleDtos.StatusFilter status;
        MaterialLifecycleDtos.AiUseFilter aiUse;
        try {
            status = query.containsKey("status") ? MaterialLifecycleDtos.StatusFilter.valueOf(query.getFirst("status")) : MaterialLifecycleDtos.StatusFilter.ACTIVE;
            aiUse = query.containsKey("aiUse") ? MaterialLifecycleDtos.AiUseFilter.valueOf(query.getFirst("aiUse")) : MaterialLifecycleDtos.AiUseFilter.ALL;
        } catch (IllegalArgumentException exception) { throw MaterialGuards.invalid("摘录状态与 AI 用途筛选无效。"); }
        return service.excerpts(owner.id(), id, query.getFirst("cursor"), query.getFirst("q"),
                rawLimit == null ? 20 : Integer.parseInt(rawLimit), status, aiUse);
    }

    @GetMapping("/excerpts/{id}")
    public Excerpt excerpt(@PathVariable("id") String id) { return service.excerpt(owner.id(), id); }

    private <T> T decode(JsonNode body, Class<T> type) {
        if (body == null || !body.isObject()) throw MaterialGuards.invalid("请求必须为 JSON 对象。");
        try { return requests.treeToValue(body, type); }
        catch (RuntimeException exception) { throw MaterialGuards.invalid("请求字段或类型不符合资料接口要求。"); }
    }

    private void download(MaterialService.Download file, HttpServletResponse response) throws IOException {
        // 文件流不经过 JSON 响应 advice，打开文件前须重新确认训练资料条件。
        trainingAccess.checkHttp(owner.id(), "GET", "/api/v1/materials", null);
        try (InputStream input = storage.open(file.path(), MaterialGuards.MAX_FILE_BYTES)) {
            response.setContentType(file.contentType());
            response.setContentLengthLong(file.size());
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
            response.setHeader("Content-Security-Policy", "sandbox; default-src 'none'; frame-ancestors 'none'");
            response.setHeader("Content-Disposition", (file.inline() ? ContentDisposition.inline() : ContentDisposition.attachment())
                    .filename(file.fileName(), StandardCharsets.UTF_8).build().toString());
            if (file.sha256() != null) response.setHeader("X-Content-SHA256", file.sha256());
            input.transferTo(response.getOutputStream());
        }
    }
}
