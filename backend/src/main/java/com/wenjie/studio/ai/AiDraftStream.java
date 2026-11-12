package com.wenjie.studio.ai;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.security.OwnerProperties;
import com.wenjie.studio.training.TrainingAccessGuard;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.wenjie.studio.ai.AiDraftDtos.CloseReason;

@Service
public class AiDraftStream {
    public static final String PATH = "/api/v1/ai/tasks/{id}/attempts/{attemptId}/draft-events";
    private static final Logger LOG = LoggerFactory.getLogger(AiDraftStream.class);
    private static final Set<String> ACTIVE = Set.of("QUEUED", "RUNNING", "VALIDATING");
    private static final Set<String> ACCESS_ERRORS = Set.of("AUTHENTICATION_REQUIRED", "ASSISTANCE_CONFIRMATION_REQUIRED",
            "AI_EVALUATION_ACCESS_REQUIRED", "AI_EARLY_FEEDBACK_CONFIRMATION_REQUIRED");
    private static final long LIFETIME_NANOS = Duration.ofSeconds(90).toNanos();
    private static final long HEARTBEAT_NANOS = Duration.ofSeconds(10).toNanos();
    private static final int DELTA_CHARACTERS = 8192;
    private final AiService ai;
    private final AiDraftBuffer buffer;
    private final TrainingAccessGuard trainingAccess;
    private final OwnerProperties ownerProperties;
    private final Semaphore slots = new Semaphore(4);
    private final ExecutorService workers = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("ai-drafts-", 0).factory());
    private final Map<SseEmitter, AtomicBoolean> connections = new ConcurrentHashMap<>();

    public AiDraftStream(AiService ai, AiDraftBuffer buffer, TrainingAccessGuard trainingAccess,
                         OwnerProperties ownerProperties) {
        this.ai = ai;
        this.buffer = buffer;
        this.trainingAccess = trainingAccess;
        this.ownerProperties = ownerProperties;
    }

    public SseEmitter open(String owner, String taskId, String attemptId, HttpSession session, String lastEventId) {
        if (lastEventId != null) throw new ApiException(400, "INVALID_REQUEST", "暂态正文不支持历史事件重放，请重新打开当前尝试。");
        String sessionId;
        try { sessionId = session == null ? null : session.getId(); }
        catch (IllegalStateException exception) { sessionId = null; }
        requireSession(owner, session, sessionId);
        final String authorizedSessionId = sessionId;
        if (!slots.tryAcquire()) throw new ApiException(429, "AI_DRAFT_STREAM_LIMIT", "实时预览连接已达上限，请通过任务状态查看最终结果。");
        try {
            AiService.AiDraftAccess access = checkedAccess(owner, taskId, attemptId, session, sessionId);
            requireStreaming(access);
            SseEmitter emitter = new SseEmitter(95_000L);
            AtomicBoolean closed = new AtomicBoolean();
            connections.put(emitter, closed);
            emitter.onCompletion(() -> closed.set(true));
            emitter.onTimeout(() -> closed.set(true));
            emitter.onError(error -> closed.set(true));
            try { workers.execute(() -> stream(owner, taskId, attemptId, session, authorizedSessionId, emitter, closed)); }
            catch (RuntimeException exception) { connections.remove(emitter); throw exception; }
            return emitter;
        } catch (RuntimeException exception) {
            slots.release();
            throw exception;
        }
    }

    private void stream(String owner, String taskId, String attemptId, HttpSession session, String sessionId,
                        SseEmitter emitter, AtomicBoolean closed) {
        boolean transportFailed = false;
        try {
            long started = System.nanoTime(), lastSent = started;
            long sequence = 0;
            int offset = -1;
            boolean observedBuffer = false;
            while (!closed.get() && !Thread.currentThread().isInterrupted()) {
                AiService.AiDraftAccess access = checkedAccess(owner, taskId, attemptId, session, sessionId);
                if (!ACTIVE.contains(access.status())) {
                    send(emitter, "closed", new AiDraftDtos.Closed(taskId, attemptId, CloseReason.FINISHED));
                    return;
                }
                requireStreaming(access);
                AiDraftBuffer.Snapshot snapshot = buffer.snapshot(owner, taskId, attemptId);
                // 读缓冲不授予资料权限；发送前再次核对会话、当前来源、许可和任务，网络写入在事务之外。
                access = checkedAccess(owner, taskId, attemptId, session, sessionId);
                if (closed.get()) return;
                if (!ACTIVE.contains(access.status())) {
                    send(emitter, "closed", new AiDraftDtos.Closed(taskId, attemptId, CloseReason.FINISHED));
                    return;
                }
                requireStreaming(access);
                long now = System.nanoTime();
                if (now - started >= LIFETIME_NANOS) {
                    send(emitter, "closed", new AiDraftDtos.Closed(taskId, attemptId, CloseReason.RECONNECT));
                    return;
                }
                if (observedBuffer && !snapshot.present() || offset > snapshot.text().length()) {
                    send(emitter, "closed", new AiDraftDtos.Closed(taskId, attemptId, CloseReason.UNAVAILABLE));
                    return;
                }
                observedBuffer |= snapshot.present();
                if (offset < 0) {
                    // 连接序号从零开始；旧连接的前缀和事件标识不能参与本次拼接。
                    send(emitter, "snapshot", new AiDraftDtos.Snapshot(taskId, attemptId, 0, snapshot.text()));
                    offset = snapshot.text().length();
                    lastSent = now;
                } else if (offset < snapshot.text().length()) {
                    int end = Math.min(snapshot.text().length(), offset + DELTA_CHARACTERS);
                    if (end < snapshot.text().length() && Character.isHighSurrogate(snapshot.text().charAt(end - 1))) end--;
                    send(emitter, "delta", new AiDraftDtos.Delta(taskId, attemptId, ++sequence, offset,
                            snapshot.text().substring(offset, end)));
                    offset = end;
                    lastSent = now;
                } else if (now - lastSent >= HEARTBEAT_NANOS) {
                    send(emitter, "heartbeat", new AiDraftDtos.Heartbeat(taskId, attemptId));
                    lastSent = now;
                }
                Thread.sleep(500);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException exception) {
            transportFailed = true;
        } catch (ApiException exception) {
            CloseReason reason = exception.status() == 401 || exception.status() == 403 || ACCESS_ERRORS.contains(exception.code())
                    ? CloseReason.ACCESS_CHANGED : CloseReason.UNAVAILABLE;
            transportFailed = notifyClosed(emitter, closed, taskId, attemptId, reason);
        } catch (IllegalStateException exception) {
            transportFailed = notifyClosed(emitter, closed, taskId, attemptId, CloseReason.UNAVAILABLE);
        } catch (RuntimeException exception) {
            LOG.warn("event=ai_draft_unavailable exceptionType={}", exception.getClass().getSimpleName());
            transportFailed = notifyClosed(emitter, closed, taskId, attemptId, CloseReason.UNAVAILABLE);
        } finally {
            // 回调仅标记关闭；额度由真正退出的工作线程归还，慢写不能提前释放额度。
            closed.set(true);
            connections.remove(emitter);
            slots.release();
            if (!transportFailed) complete(emitter);
        }
    }

    private AiService.AiDraftAccess checkedAccess(String owner, String taskId, String attemptId,
                                                 HttpSession session, String sessionId) {
        requireSession(owner, session, sessionId);
        trainingAccess.checkHttp(owner, "GET", PATH, null);
        AiService.AiDraftAccess access = ai.readDraftAccess(owner, taskId, attemptId);
        requireSession(owner, session, sessionId);
        return access;
    }

    private static void requireStreaming(AiService.AiDraftAccess access) {
        if (ACTIVE.contains(access.status()) && !access.streaming()) {
            throw new ApiException(409, "AI_DRAFT_STREAMING_DISABLED", "本次尝试未启用流式生成，请继续查看任务最终结果。");
        }
    }

    private void requireSession(String owner, HttpSession session, String sessionId) {
        if (!authorized(owner, session, sessionId)) {
            throw new ApiException(401, "AUTHENTICATION_REQUIRED", "请先登录后继续操作。");
        }
    }

    private boolean authorized(String owner, HttpSession session, String sessionId) {
        if (session == null || sessionId == null || !ownerProperties.ownerId().equals(owner)) return false;
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

    private static void send(SseEmitter emitter, String event, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
    }

    private static boolean notifyClosed(SseEmitter emitter, AtomicBoolean closed, String taskId, String attemptId, CloseReason reason) {
        if (closed.get()) return false;
        try { send(emitter, "closed", new AiDraftDtos.Closed(taskId, attemptId, reason)); }
        catch (IOException exception) { return true; }
        catch (IllegalStateException ignored) { }
        return false;
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
