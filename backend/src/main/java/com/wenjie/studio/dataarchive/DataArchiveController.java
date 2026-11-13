package com.wenjie.studio.dataarchive;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.CurrentOwner;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static com.wenjie.studio.dataarchive.DataArchiveDtos.*;

@RestController
@RequestMapping("/api/v1/lifecycle/archives")
public class DataArchiveController {
    private final DataArchiveService service;
    private final DataArchiveProperties properties;
    private final CurrentOwner owner;

    public DataArchiveController(DataArchiveService service, DataArchiveProperties properties, CurrentOwner owner) {
        this.service = service; this.properties = properties; this.owner = owner;
    }

    @GetMapping("/status")
    public Status status() { return service.status(owner.id()); }

    @GetMapping
    public JobPage list() { return service.list(owner.id()); }

    @GetMapping("/{id}")
    public Job find(@PathVariable("id") String id) { return service.find(owner.id(), id); }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Job create(@RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 1 || !body.has("confirmedSensitiveData")
                || !body.get("confirmedSensitiveData").isBoolean())
            throw new ApiException(400, "INVALID_ARCHIVE_REQUEST", "导出请求须仅包含布尔 confirmedSensitiveData 字段。");
        return service.create(owner.id(), key, new Create(body.get("confirmedSensitiveData").asBoolean()));
    }

    @GetMapping("/{id}/content")
    public void content(@PathVariable("id") String id, HttpServletResponse response) throws IOException {
        service.download(owner.id(), id, (job, file) -> {
            try (InputStream input = ArchivePaths.open(file, properties.maximumBytes())) {
                response.setContentType("application/zip");
                response.setContentLengthLong(job.byteSize());
                response.setHeader("Cache-Control", "no-store, private");
                response.setHeader("Pragma", "no-cache");
                response.setHeader("X-Content-Type-Options", "nosniff");
                response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
                response.setHeader("Content-Security-Policy", "sandbox; default-src 'none'; frame-ancestors 'none'");
                response.setHeader("Content-Disposition", ContentDisposition.attachment()
                        .filename("wenjie-full-" + job.id() + ".zip", StandardCharsets.UTF_8).build().toString());
                response.setHeader("X-Content-SHA256", job.sha256());
                byte[] buffer = new byte[65_536];
                long written = 0, started = System.nanoTime();
                for (int count; (count = input.read(buffer)) != -1;) {
                    ArchiveFormat.time(started); written += count;
                    if (written > job.byteSize()) throw new IOException("ARCHIVE_FILE_CHANGED");
                    response.getOutputStream().write(buffer, 0, count);
                }
                if (written != job.byteSize()) throw new IOException("ARCHIVE_FILE_CHANGED");
            }
        });
    }
}
