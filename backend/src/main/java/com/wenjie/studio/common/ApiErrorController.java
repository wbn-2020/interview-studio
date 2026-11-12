package com.wenjie.studio.common;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class ApiErrorController implements ErrorController {
    private final ProblemWriter problems;

    public ApiErrorController(ProblemWriter problems) {
        this.problems = problems;
    }

    @RequestMapping("/error")
    public ResponseEntity<ApiProblem> error(HttpServletRequest request) {
        Object value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = value instanceof Integer number && number >= 400 && number <= 599 ? number : 500;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problems.create(request, status, "HTTP_ERROR", "请求未完成，请核对地址和请求格式。", null));
    }
}
