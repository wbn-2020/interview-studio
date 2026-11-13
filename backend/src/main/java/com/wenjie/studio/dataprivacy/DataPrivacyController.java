package com.wenjie.studio.dataprivacy;

import com.wenjie.studio.common.CurrentOwner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.wenjie.studio.dataprivacy.DataPrivacyDtos.*;

@RestController
@RequestMapping("/api/v1/data-privacy")
public class DataPrivacyController {
    private final DataPrivacyService service;
    private final CurrentOwner owner;

    public DataPrivacyController(DataPrivacyService service, CurrentOwner owner) {
        this.service = service; this.owner = owner;
    }
    @GetMapping("/status")
    public PrivacyLifecycle.Status status() { return service.status(owner.id()); }
    @PostMapping("/ledger/initialize")
    public PrivacyLifecycle.Status initialize(@RequestHeader("Idempotency-Key") String key,
                                               @RequestBody InitializeLedger request) {
        return service.initialize(owner.id(), key, request);
    }
    @GetMapping("/trash")
    public TrashPage trash(@RequestParam(name = "cursor", required = false) String cursor,
                           @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return service.trash(owner.id(), cursor, limit);
    }
    @PostMapping("/trash/preview")
    public Preview previewTrash(@RequestBody SelectObject request) { return service.preview(owner.id(), "TRASH", request); }
    @PostMapping("/restore/preview")
    public Preview previewRestore(@RequestBody SelectObject request) { return service.preview(owner.id(), "RESTORE", request); }
    @PostMapping("/purge/preview")
    public Preview previewPurge(@RequestBody SelectObject request) { return service.preview(owner.id(), "PURGE", request); }
    @PostMapping("/trash")
    public Result trash(@RequestHeader("Idempotency-Key") String key, @RequestBody Command request) {
        return service.command(owner.id(), key, "TRASH", request);
    }
    @PostMapping("/restore")
    public Result restore(@RequestHeader("Idempotency-Key") String key, @RequestBody Command request) {
        return service.command(owner.id(), key, "RESTORE", request);
    }
    @PostMapping("/purge")
    public Result purge(@RequestHeader("Idempotency-Key") String key, @RequestBody Command request) {
        return service.command(owner.id(), key, "PURGE", request);
    }
}
