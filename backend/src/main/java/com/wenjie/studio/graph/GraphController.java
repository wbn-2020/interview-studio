package com.wenjie.studio.graph;

import com.wenjie.studio.common.CurrentOwner;
import com.wenjie.studio.graph.GraphDtos.*;
import com.wenjie.studio.knowledge.KnowledgeDtos.Graph;
import com.wenjie.studio.knowledge.KnowledgeDtos.LayoutSaved;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/knowledge/graph")
public class GraphController {
    private final CurrentOwner owner;
    private final GraphHistoryService history;
    private final GraphExplorerService explorer;

    public GraphController(CurrentOwner owner, GraphHistoryService history, GraphExplorerService explorer) {
        this.owner = owner;
        this.history = history;
        this.explorer = explorer;
    }

    @GetMapping("/state")
    public State state() { return history.state(owner.id()); }

    @PutMapping("/state")
    public LayoutSaved save(@RequestHeader("Idempotency-Key") String key, @RequestBody SaveState request) {
        return history.save(owner.id(), key, request);
    }

    @GetMapping("/history")
    public HistoryPage history(@RequestParam(name = "before", required = false) Long before,
                               @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return history.history(owner.id(), before, limit);
    }

    @PostMapping("/history/{revision}/restore")
    public LayoutSaved restore(@PathVariable("revision") long revision,
                               @RequestHeader("Idempotency-Key") String key, @RequestBody Restore request) {
        return history.restore(owner.id(), revision, key, request);
    }

    @GetMapping("/neighborhood")
    public Graph neighborhood(@RequestParam("centerId") String centerId,
                              @RequestParam(name = "depth", defaultValue = "1") int depth,
                              @RequestParam(name = "limit", defaultValue = "200") int limit) {
        return explorer.neighborhood(owner.id(), centerId, depth, limit);
    }

    @GetMapping("/path")
    public Path path(@RequestParam("fromId") String fromId, @RequestParam("toId") String toId,
                     @RequestParam(name = "type", required = false) String type) {
        return explorer.path(owner.id(), fromId, toId, type);
    }
}
