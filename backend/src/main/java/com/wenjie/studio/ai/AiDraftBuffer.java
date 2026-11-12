package com.wenjie.studio.ai;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 只保存本进程当前尝试的暂态文本，不能用于恢复任务或确认费用。 */
@Service
public class AiDraftBuffer {
    public static final int MAX_BYTES = 256 * 1024;
    private static final int MAX_ENTRIES = 4;
    private final Map<Identity, Entry> entries = new HashMap<>();

    public synchronized void begin(AiService.Claim claim) {
        Key key = key(claim);
        long now = System.nanoTime();
        expire(now);
        int timeout = claim.manifest() == null || claim.manifest().configuration() == null
                ? 0 : claim.manifest().configuration().timeoutSeconds();
        if (timeout < 1 || timeout > 120) throw invalid();
        if (entries.containsKey(key.identity()) || entries.size() >= MAX_ENTRIES) throw unavailable();
        entries.put(key.identity(), new Entry(key, now + Duration.ofSeconds(timeout + 60L).toNanos()));
    }

    public synchronized void append(AiService.Claim claim, String text) {
        Key key = key(claim);
        expire(System.nanoTime());
        Entry entry = entries.get(key.identity());
        if (entry == null || !entry.key.equals(key)) throw unavailable();
        int bytes = utf8Length(text);
        if (bytes > MAX_BYTES - entry.bytes) throw new BufferException("AI_DRAFT_LIMIT");
        entry.text.append(text);
        entry.bytes += bytes;
    }

    public synchronized void clear(AiService.Claim claim) {
        Key key = key(claim);
        Entry entry = entries.get(key.identity());
        // 迟到的 finally 只能清自己的令牌，不能清同任务后来建立的缓冲。
        if (entry != null && entry.key.equals(key)) entries.remove(key.identity());
        expire(System.nanoTime());
    }

    public synchronized Snapshot snapshot(String owner, String taskId, String attemptId) {
        expire(System.nanoTime());
        Entry entry = entries.get(identity(owner, taskId, attemptId));
        return entry == null ? new Snapshot(false, "") : new Snapshot(true, entry.text.toString());
    }

    private void expire(long now) {
        // 单调时钟限制故障遗留；读取或追加都不能延长同一次模型执行的保留期限。
        entries.values().removeIf(entry -> now - entry.expiresAt >= 0);
    }

    private static Key key(AiService.Claim claim) {
        if (claim == null) throw invalid();
        return new Key(identity(claim.owner(), claim.taskId(), claim.attemptId()),
                uuid(claim.token()), uuid(claim.epoch()));
    }

    private static Identity identity(String owner, String taskId, String attemptId) {
        return new Identity(uuid(owner), uuid(taskId), uuid(attemptId));
    }

    private static String uuid(String value) {
        try {
            if (value == null || !UUID.fromString(value).toString().equals(value)) throw invalid();
            return value;
        } catch (IllegalArgumentException exception) { throw invalid(); }
    }

    private static int utf8Length(String text) {
        if (text == null || text.length() > MAX_BYTES) throw invalid();
        int bytes = 0;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (Character.isHighSurrogate(value)) {
                if (index + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(++index))) throw invalid();
                bytes += 4;
            } else if (Character.isLowSurrogate(value)) throw invalid();
            else bytes += value < 0x80 ? 1 : value < 0x800 ? 2 : 3;
            if (bytes > MAX_BYTES) throw new BufferException("AI_DRAFT_LIMIT");
        }
        return bytes;
    }

    private static BufferException invalid() { return new BufferException("AI_DRAFT_INVALID"); }
    private static BufferException unavailable() { return new BufferException("AI_DRAFT_UNAVAILABLE"); }

    @PreDestroy
    public synchronized void close() { entries.clear(); }

    public record Snapshot(boolean present, String text) {}
    private record Identity(String owner, String taskId, String attemptId) {}
    private record Key(Identity identity, String token, String epoch) {}

    private static final class Entry {
        private final Key key;
        private final long expiresAt;
        private final StringBuilder text = new StringBuilder();
        private int bytes;

        private Entry(Key key, long expiresAt) {
            this.key = key;
            this.expiresAt = expiresAt;
        }
    }

    public static final class BufferException extends RuntimeException {
        private final String code;

        private BufferException(String code) {
            super("暂态生成缓冲不可接纳当前片段。");
            this.code = code;
        }

        public String code() { return code; }
    }
}
