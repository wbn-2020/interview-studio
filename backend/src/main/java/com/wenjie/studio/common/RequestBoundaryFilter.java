package com.wenjie.studio.common;

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

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public final class RequestBoundaryFilter extends OncePerRequestFilter {
    private static final int MAX_JSON_BYTES = 1_048_576;
    private static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");
    private final ProblemWriter problems;

    public RequestBoundaryFilter(ProblemWriter problems) {
        this.problems = problems;
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
        byte[] body = request.getInputStream().readNBytes(MAX_JSON_BYTES + 1);
        if (body.length > MAX_JSON_BYTES) {
            tooLarge(request, response);
            return;
        }
        filterChain.doFilter(new BufferedJsonRequest(request, body), response);
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
