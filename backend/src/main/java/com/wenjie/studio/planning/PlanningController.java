package com.wenjie.studio.planning;

import com.wenjie.studio.common.CurrentOwner;
import com.wenjie.studio.planning.PlanningDtos.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.Set;

import static com.wenjie.studio.planning.PlanningRules.invalid;

@RestController
@RequestMapping("/api/v1/prepare/tasks")
public class PlanningController {
    private final PlanningService planning;
    private final CurrentOwner owner;

    public PlanningController(PlanningService planning, CurrentOwner owner) {
        this.planning = planning;
        this.owner = owner;
    }

    @GetMapping
    public TaskPage list(@RequestParam(name = "status", required = false) Status status,
                         @RequestParam(name = "targetId", required = false) String targetId,
                         @RequestParam(name = "from", required = false) String from,
                         @RequestParam(name = "to", required = false) String to,
                         @RequestParam(name = "cursor", required = false) String cursor,
                         @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return planning.list(owner.id(), status, targetId, from, to, cursor, limit);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskDetail create(@RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode body) {
        fields(body, Set.of("target", "content"));
        TargetLink target = null;
        if (!body.get("target").isNull()) {
            JsonNode source = body.get("target");
            fields(source, Set.of("itemId", "versionId"));
            target = new TargetLink(text(source.get("itemId"), false), text(source.get("versionId"), false));
        }
        return planning.create(owner.id(), key, new CreateTask(target, content(body.get("content"))), body);
    }

    @GetMapping("/{id}")
    public TaskDetail detail(@PathVariable("id") String id) { return planning.detail(owner.id(), id); }

    @PutMapping("/{id}")
    public TaskDetail save(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                           @RequestBody JsonNode body) {
        fields(body, Set.of("expectedRevision", "content"));
        return planning.save(owner.id(), id, key,
                new SaveTask(integer(body.get("expectedRevision")), content(body.get("content"))), body);
    }

    @PostMapping("/{id}/status")
    public TaskDetail status(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                             @RequestBody JsonNode body) {
        fields(body, Set.of("expectedRevision", "status"));
        Status status;
        try { status = Status.valueOf(text(body.get("status"), false)); }
        catch (IllegalArgumentException exception) { throw invalid("准备任务状态无效。"); }
        return planning.status(owner.id(), id, key, new ChangeStatus(integer(body.get("expectedRevision")), status), body);
    }

    @GetMapping("/{id}/history")
    public HistoryPage history(@PathVariable("id") String id,
                               @RequestParam(name = "before", required = false) Long before,
                               @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return planning.history(owner.id(), id, before, limit);
    }

    private static Content content(JsonNode node) {
        fields(node, Set.of("title", "detail", "minutes", "plannedDate", "zoneId"));
        long minutes = integer(node.get("minutes"));
        if (minutes < 1 || minutes > 1440) throw invalid("预计工作量须为 1 至 1440 的整数分钟。");
        return new Content(text(node.get("title"), false), text(node.get("detail"), false), (int) minutes,
                text(node.get("plannedDate"), true), text(node.get("zoneId"), false));
    }

    private static void fields(JsonNode node, Set<String> names) {
        if (node == null || !node.isObject() || node.size() != names.size()
                || names.stream().anyMatch(name -> !node.has(name))) {
            throw invalid("请求字段不完整或包含不支持的字段。");
        }
    }

    private static String text(JsonNode node, boolean nullable) {
        if (node != null && nullable && node.isNull()) return null;
        if (node == null || !node.isTextual()) throw invalid("文本字段类型无效。");
        return node.stringValue();
    }

    private static long integer(JsonNode node) {
        // 修订和分钟必须保持原始整数类型，拒绝小数截断或字符串转数值后误保存。
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw invalid("分钟与修订字段必须是有效整数。");
        }
        return node.longValue();
    }
}
