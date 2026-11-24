package com.wenjie.studio.common;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public final class RequestBoundaryFilter extends OncePerRequestFilter {
    private static final int MAX_JSON_BYTES = 1_048_576;
    private static final int READER_THREADS = 4;
    private static final int READER_QUEUE = 16;
    private static final long READ_TIMEOUT_MILLIS = 5_000;
    private static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");
    private final ProblemWriter problems;
    private final ThreadPoolExecutor bodyReaders;
    private final AtomicInteger readerSequence = new AtomicInteger();

    public RequestBoundaryFilter(ProblemWriter problems) {
        this.problems = problems;
        // Security 前不能用无界 common pool 接慢速连接；工作线程与等待队列都必须有上限。
        bodyReaders = new ThreadPoolExecutor(READER_THREADS, READER_THREADS, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(READER_QUEUE), task -> {
                    Thread thread = new Thread(task, "request-json-body-" + readerSequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    void shutdown() {
        bodyReaders.shutdownNow();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (!WRITES.contains(request.getMethod()) || contentType == null) {
            return true;
        }
        String mediaType = contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].strip();
        return !mediaType.equals("application/json")
                && !(mediaType.startsWith("application/") && mediaType.endsWith("+json"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_JSON_BYTES) {
            tooLarge(request, response);
            return;
        }
        // 分块请求可能没有 Content-Length；读取上限也必须作用于实际输入流。
        byte[] body = readBoundedBody(request, response);
        if (body == null) return;
        if (body.length > MAX_JSON_BYTES) {
            tooLarge(request, response);
            return;
        }
        filterChain.doFilter(new BufferedJsonRequest(request, body), response);
    }

    private byte[] readBoundedBody(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var input = request.getInputStream();
        Future<byte[]> reading;
        try {
            reading = bodyReaders.submit(() -> input.readNBytes(MAX_JSON_BYTES + 1));
        } catch (RejectedExecutionException exception) {
            closeQuietly(input);
            problems.write(request, response, 503, "REQUEST_BUSY", "服务器读取队列已满，请稍后重试。");
            return null;
        }
        try {
            return reading.get(READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            reading.cancel(true);
            problems.write(request, response, 408, "REQUEST_READ_TIMEOUT", "请求内容读取超时，请检查连接后重试。");
            return null;
        } catch (InterruptedException exception) {
            reading.cancel(true);
            Thread.currentThread().interrupt();
            problems.write(request, response, 408, "REQUEST_READ_TIMEOUT", "请求读取已中断，请重新发送。");
            return null;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("读取 JSON 请求内容失败。", cause);
        } finally {
            // 超时或拒绝时关闭原流，唤醒阻塞读取；成功时正文已完整复制，后续只读内存副本。
            closeQuietly(input);
        }
    }

    private static void closeQuietly(AutoCloseable value) {
        try { value.close(); } catch (Exception ignored) { }
    }

    private void tooLarge(HttpServletRequest request, HttpServletResponse response) throws IOException {
        problems.write(request, response, 413, "REQUEST_TOO_LARGE", "请求内容不能超过 1 MiB。");
    }

    private static final class BufferedJsonRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private BufferedJsonRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] bytes, int offset, int length) { return input.read(bytes, offset, length); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    throw new IllegalStateException("此同步 JSON 接口不支持异步读取请求体。");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
