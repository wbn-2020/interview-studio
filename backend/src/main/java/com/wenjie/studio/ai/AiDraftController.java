package com.wenjie.studio.ai;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.CurrentOwner;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@RestController
public class AiDraftController {
    private final AiDraftStream streams;
    private final CurrentOwner owner;

    public AiDraftController(AiDraftStream streams, CurrentOwner owner) {
        this.streams = streams;
        this.owner = owner;
    }

    @GetMapping(value = AiDraftStream.PATH, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable("id") String taskId, @PathVariable("attemptId") String attemptId,
                             HttpServletRequest request, HttpServletResponse response,
                             @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        if (request.getQueryString() != null && !request.getQueryString().isEmpty()) {
            throw new ApiException(400, "INVALID_REQUEST", "暂态正文只按当前任务与尝试读取，不接受查询参数。");
        }
        SseEmitter emitter = streams.open(owner.id(), taskId, attemptId, request.getSession(false), lastEventId);
        response.setHeader("Cache-Control", "no-store, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        response.setCharacterEncoding("UTF-8");
        return emitter;
    }

    @ExceptionHandler({IOException.class, AsyncRequestTimeoutException.class})
    public void disconnected(HttpServletResponse response) {
        // 已开始的正文流只能结束，不能把通用 problem JSON 拼入暂态文本。
        if (!response.isCommitted()) response.setStatus(503);
    }
}
