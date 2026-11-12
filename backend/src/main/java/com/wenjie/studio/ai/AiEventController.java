package com.wenjie.studio.ai;

import com.wenjie.studio.common.CurrentOwner;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@RestController
public class AiEventController {
    private final AiEventStream streams;
    private final CurrentOwner owner;

    public AiEventController(AiEventStream streams, CurrentOwner owner) {
        this.streams = streams;
        this.owner = owner;
    }

    @GetMapping(value = "/api/v1/ai/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(HttpServletRequest request, HttpServletResponse response,
                             @RequestParam(name = "after", required = false) String after,
                             @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        SseEmitter emitter = streams.open(owner.id(), request.getSession(false), after, lastEventId);
        response.setHeader("Cache-Control", "no-store, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        response.setCharacterEncoding("UTF-8");
        return emitter;
    }

    @ExceptionHandler({IOException.class, AsyncRequestTimeoutException.class})
    public void disconnected(HttpServletResponse response) {
        // 已开始的事件流只能结束，不能让通用错误处理器在流尾附加 problem JSON。
        if (!response.isCommitted()) response.setStatus(503);
    }
}
