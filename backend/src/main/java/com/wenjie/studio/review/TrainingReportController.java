package com.wenjie.studio.review;

import com.wenjie.studio.common.CurrentOwner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/review")
public class TrainingReportController {
    private final TrainingReportService service;
    private final CurrentOwner owner;

    public TrainingReportController(TrainingReportService service, CurrentOwner owner) {
        this.service = service;
        this.owner = owner;
    }

    @GetMapping("/sessions/{sessionId}/report")
    public TrainingReportDtos.Report report(@PathVariable("sessionId") String sessionId) {
        return service.report(owner.id(), sessionId);
    }

    @GetMapping("/sessions/{sessionId}/questions/{questionId}/report")
    public TrainingReportDtos.SubmissionPage submissions(@PathVariable("sessionId") String sessionId,
            @PathVariable("questionId") String questionId,
            @RequestParam(name = "before", required = false) Integer before,
            @RequestParam(name = "limit", defaultValue = "10") int limit) {
        return service.submissions(owner.id(), sessionId, questionId, before, limit);
    }
}
