package com.wenjie.studio.today;

import com.wenjie.studio.common.CurrentOwner;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

import static com.wenjie.studio.today.TodayDtos.*;
import static com.wenjie.studio.today.TodayRules.*;

@RestController
@RequestMapping("/api/v1/today")
public class TodayController {
    private final TodayService today;
    private final CurrentOwner owner;

    public TodayController(TodayService today, CurrentOwner owner) { this.today = today; this.owner = owner; }

    @GetMapping("/overview")
    public TodayOverview overview(@RequestParam MultiValueMap<String, String> query, HttpServletResponse response) {
        parameters(query, Set.of("zoneId", "targetId", "includeOverBudget")); noStore(response);
        return today.overview(owner.id(), new OverviewQuery(required(query, "zoneId"), query.getFirst("targetId"),
                bool(query.getFirst("includeOverBudget"))));
    }

    @GetMapping("/items")
    public TodayPage items(@RequestParam MultiValueMap<String, String> query, HttpServletResponse response) {
        parameters(query, Set.of("zoneId", "kind", "section", "targetId", "includeOverBudget", "cursor", "limit"));
        noStore(response);
        return today.items(owner.id(), new ItemsQuery(required(query, "zoneId"),
                enumValue(required(query, "kind"), SourceKind.class), enumValue(required(query, "section"), Section.class),
                query.getFirst("targetId"), bool(query.getFirst("includeOverBudget")), query.getFirst("cursor"), limit(query.getFirst("limit"))));
    }

    @GetMapping("/calendar")
    public CalendarPage calendar(@RequestParam MultiValueMap<String, String> query, HttpServletResponse response) {
        parameters(query, Set.of("from", "to", "zoneId", "kind", "targetId", "cursor", "limit")); noStore(response);
        return today.calendar(owner.id(), new CalendarQuery(date(required(query, "from")), date(required(query, "to")),
                required(query, "zoneId"), enumValue(required(query, "kind"), SourceKind.class),
                query.getFirst("targetId"), query.getFirst("cursor"), limit(query.getFirst("limit"))));
    }

    @GetMapping("/sources/{kind}/{id}")
    public SourceDetail source(@PathVariable("kind") String kind, @PathVariable("id") String id,
                               @RequestParam MultiValueMap<String, String> query, HttpServletResponse response) {
        parameters(query, Set.of("zoneId")); noStore(response);
        return today.source(owner.id(), new SourceRef(enumValue(kind, SourceKind.class), uuid(id)),
                new SourceQuery(required(query, "zoneId")));
    }

    private static void noStore(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
}
