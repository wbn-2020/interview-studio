package com.wenjie.studio.ai;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.security.OwnerProperties;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AiEventStream {
    private static final Logger LOG = LoggerFactory.getLogger(AiEventStream.class);
    private static final String EMPTY_CURSOR = "00000000-0000-0000-0000-000000000000";
    private static final long LIFETIME_NANOS = Duration.ofSeconds(90).toNanos();
    private static final long HEARTBEAT_NANOS = Duration.ofSeconds(10).toNanos();
    private final AiMapper mapper;
    private final OwnerProperties ownerProperties;
    private final Semaphore slots = new Semaphore(4);
    private final ExecutorService workers = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("ai-events-", 0).factory());
    private final Map<SseEmitter, AtomicBoolean> connections = new ConcurrentHashMap<>();

    public AiEventStream(AiMapper mapper, OwnerProperties ownerProperties) {
        this.mapper = mapper;
        this.ownerProperties = ownerProperties;
    }

    public SseEmitter open(String owner, HttpSession session, String after, String lastEventId) {
        cursorParameter(after);
        cursorParameter(lastEventId);
        String sessionId;
        try { sessionId = session == null ? null : session.getId(); }
        catch (IllegalStateException exception) { sessionId = null; }
        if (!authorized(session, sessionId)) throw new ApiException(401, "AUTHENTICATION_REQUIRED", "请先登录后继续操作。");
        final String authorizedSessionId = sessionId;
        if (!slots.tryAcquire()) throw new ApiException(429, "AI_STREAM_LIMIT", "实时连接已达上限，请继续通过状态查询查看任务。");
        try {
            // 握手先读存储，让尚未开始的响应可以正常返回存储错误；不持事务等待网络。
            String initial = cursor(owner);
            SseEmitter emitter = new SseEmitter(95_000L);
            AtomicBoolean closed = new AtomicBoolean();
            connections.put(emitter, closed);
            emitter.onCompletion(() -> closed.set(true));
            emitter.onTimeout(() -> closed.set(true));
            emitter.onError(error -> closed.set(true));
            try { workers.execute(() -> stream(owner, session, authorizedSessionId, initial, emitter, closed)); }
            catch (RuntimeException exception) { connections.remove(emitter); throw exception; }
            return emitter;
        } catch (RuntimeException exception) {
            slots.release();
            throw exception;
        }
    }

    private void stream(String owner, HttpSession session, String sessionId, String initial,
                        SseEmitter emitter, AtomicBoolean closed) {
        boolean transportFailed = false;
        try {
            if (closed.get() || !authorized(session, sessionId)) return;
            long started = System.nanoTime(), lastSent = started;
            String previous = initial;
            // 每次连接都要求重读现状；游标不提供事件重放或大小比较的保证。
            signal(emitter, "snapshot", previous);
            while (!closed.get() && !Thread.currentThread().isInterrupted()) {
                Thread.sleep(2000);
                if (closed.get() || !authorized(session, sessionId)) return;
                long now = System.nanoTime();
                if (now - started >= LIFETIME_NANOS) {
                    emitter.send(SseEmitter.event().name("reconnect").data("{}", MediaType.APPLICATION_JSON));
                    return;
                }
                String current = cursor(owner);
                // 数据库等待期间可能退出或换会话，发送前再次核对，且流中永不放业务正文。
                if (closed.get() || !authorized(session, sessionId)) return;
                if (!current.equals(previous)) {
                    signal(emitter, "changed", current);
                    previous = current;
                    lastSent = now;
                } else if (now - lastSent >= HEARTBEAT_NANOS) {
                    emitter.send(SseEmitter.event().name("heartbeat").data("{}", MediaType.APPLICATION_JSON));
                    lastSent = now;
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException exception) {
            // 容器负责处理写失败的异步完成，不能再往已断开的流拼接 JSON 错误响应。
            transportFailed = true;
        } catch (IllegalStateException exception) {
            // 超时或容器已关闭响应时，只释放本连接资源。
        } catch (RuntimeException exception) {
            LOG.warn("event=ai_events_unavailable exceptionType={}", exception.getClass().getSimpleName());
        } finally {
            closed.set(true);
            connections.remove(emitter);
            slots.release();
            if (!transportFailed) complete(emitter);
        }
    }

    private static void signal(SseEmitter emitter, String event, String cursor) throws IOException {
        emitter.send(SseEmitter.event().name(event).id(cursor)
                .data("{\"cursor\":\"" + cursor + "\"}", MediaType.APPLICATION_JSON));
    }

    private String cursor(String owner) {
        String value = mapper.eventCursor(owner);
        return value == null ? EMPTY_CURSOR : value;
    }

    private boolean authorized(HttpSession session, String sessionId) {
        if (session == null || sessionId == null) return false;
        try {
            if (!sessionId.equals(session.getId())) return false;
            Object stored = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            if (!(stored instanceof SecurityContext context)) return false;
            var authentication = context.getAuthentication();
            return authentication != null && authentication.isAuthenticated()
                    && !(authentication instanceof AnonymousAuthenticationToken)
                    && ownerProperties.username().equals(authentication.getName());
        } catch (IllegalStateException exception) { return false; }
    }

    private static void cursorParameter(String value) {
        if (value == null) return;
        try {
            if (!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw new ApiException(400, "INVALID_REQUEST", "状态通知游标必须为标准小写 UUID。");
        }
    }

    private static void complete(SseEmitter emitter) {
        try { emitter.complete(); }
        catch (IllegalStateException ignored) { }
    }

    @PreDestroy
    public void close() {
        connections.forEach((emitter, closed) -> { closed.set(true); complete(emitter); });
        workers.shutdownNow();
    }
}
