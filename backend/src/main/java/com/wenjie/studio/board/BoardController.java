package com.wenjie.studio.board;

import com.wenjie.studio.board.BoardDtos.*;
import com.wenjie.studio.common.CurrentOwner;
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

@RestController
@RequestMapping("/api/v1/knowledge/boards")
public class BoardController {
    private final BoardService boards;
    private final CurrentOwner owner;
    public BoardController(BoardService boards, CurrentOwner owner) { this.boards = boards; this.owner = owner; }

    @GetMapping
    public Page list(@RequestParam(name = "cursor", required = false) String cursor,
                     @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return boards.list(owner.id(), cursor, limit);
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Detail create(@RequestHeader("Idempotency-Key") String key, @RequestBody Create request) {
        return boards.create(owner.id(), key, request);
    }
    @GetMapping("/{id}")
    public Detail detail(@PathVariable("id") String id) { return boards.detail(owner.id(), id); }
    @PutMapping("/{id}")
    public Detail save(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                       @RequestBody Save request) { return boards.save(owner.id(), id, key, request); }
    @GetMapping("/{id}/history")
    public HistoryPage history(@PathVariable("id") String id,
                               @RequestParam(name = "before", required = false) Long before,
                               @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return boards.history(owner.id(), id, before, limit);
    }
    @GetMapping("/{id}/history/{revision}")
    public Detail snapshot(@PathVariable("id") String id, @PathVariable("revision") long revision) {
        return boards.snapshot(owner.id(), id, revision);
    }
    @PostMapping("/{id}/history/{revision}/restore")
    public Detail restore(@PathVariable("id") String id, @PathVariable("revision") long revision,
                          @RequestHeader("Idempotency-Key") String key, @RequestBody Restore request) {
        return boards.restore(owner.id(), id, revision, key, request);
    }
    @PostMapping("/{id}/nodes/{nodeId}/convert")
    public ConvertedText convertText(@PathVariable("id") String id, @PathVariable("nodeId") String nodeId,
                                      @RequestHeader("Idempotency-Key") String key, @RequestBody ConvertText request) {
        return boards.convertText(owner.id(), id, nodeId, key, request);
    }
    @PostMapping("/{id}/edges/{edgeId}/convert")
    public ConvertedEdge convertEdge(@PathVariable("id") String id, @PathVariable("edgeId") String edgeId,
                                     @RequestHeader("Idempotency-Key") String key, @RequestBody ConvertEdge request) {
        return boards.convertEdge(owner.id(), id, edgeId, key, request);
    }
}
