package com.wenjie.studio.dataprivacy;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.CurrentOwner;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

import static com.wenjie.studio.dataprivacy.DataPrivacyDtos.*;

@RestController
@RequestMapping("/api/v1/data-privacy")
public class DataPrivacyController {
    private static final Set<String> OBJECT_QUERY_FIELDS = Set.of("kind", "q", "cursor", "limit", "ids");
    private final DataPrivacyService service;
    private final CurrentOwner owner;

    public DataPrivacyController(DataPrivacyService service, CurrentOwner owner) {
        this.service = service; this.owner = owner;
    }
    @GetMapping("/status")
    public PrivacyLifecycle.Status status() { return service.status(owner.id()); }
    @GetMapping("/objects")
    public ResponseEntity<PrivacyObjectPage> objects(@RequestParam MultiValueMap<String, String> query) {
        for (var entry : query.entrySet()) {
            if (!OBJECT_QUERY_FIELDS.contains(entry.getKey()) || entry.getValue().size() != 1) {
                throw invalid("对象查询参数未知或重复。");
            }
        }
        if (query.containsKey("ids")) {
            if (query.size() != 1) throw invalid("按身份读取对象时不能同时使用其他筛选参数。");
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(service.objectsByIds(owner.id(), List.of(query.getFirst("ids").split(",", -1))));
        }
        PrivacyScope.PrivacyActionableKind kind = query.containsKey("kind")
                ? PrivacyScope.actionableKind(query.getFirst("kind")) : null;
        String rawLimit = query.getFirst("limit");
        if (rawLimit != null && !rawLimit.matches("(?:[1-9]|[1-4][0-9]|50)")) throw invalid("每页数量须为 1 至 50。");
        int limit = rawLimit == null ? 20 : Integer.parseInt(rawLimit);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.objects(owner.id(), kind, query.getFirst("q"), query.getFirst("cursor"), limit));
    }
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

    private static ApiException invalid(String message) {
        return new ApiException(400, "INVALID_PRIVACY_REQUEST", message);
    }
}
