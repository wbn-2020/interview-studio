package com.wenjie.studio.ai;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 固定公网地址发起最小 HTTPS 请求；只回传状态，不读取或返回供应商正文。 */
@Component
public final class AiConfigTester {
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final int MAX_HEADER_BYTES = 32 * 1024;
    private static final int MAX_LINE_BYTES = 8 * 1024;
    private static final int MAX_TEST_TOKENS = 16;
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ai-config-test-timeout");
        thread.setDaemon(true);
        return thread;
    });

    private final JsonMapper json;

    public AiConfigTester(JsonMapper json) { this.json = json; }

    public AiConfigDtos.TestResult test(Target target) {
        long started = System.nanoTime();
        try {
            if (target == null || !List.of("CHAT_COMPLETIONS", "RESPONSES").contains(target.protocol())
                    || target.model() == null || target.model().isBlank() || !AiProperties.validKey(target.apiKey())
                    || target.timeoutSeconds() < 1 || target.timeoutSeconds() > 120) {
                return failure(started, 0, "AI_CONFIG_TEST_NOT_CONFIGURED");
            }
            long deadline = started + TimeUnit.SECONDS.toNanos(target.timeoutSeconds());
            URI endpoint = AiEndpointPolicy.parse(target.endpoint());
            List<InetAddress> addresses = AiEndpointPolicy.resolve(endpoint, remainingMillis(deadline));
            if (remainingMillis(deadline) <= 0) return failure(started, 0, "AI_CONFIG_TEST_TIMEOUT");
            byte[] body = requestBody(target);
            int port = endpoint.getPort() < 0 ? 443 : endpoint.getPort();
            AtomicReference<Socket> active = new AtomicReference<>();
            AtomicBoolean timedOut = new AtomicBoolean();
            long remainingNanos = Math.max(1, deadline - System.nanoTime());
            var timeout = WATCHDOG.schedule(() -> {
                timedOut.set(true);
                Socket socket = active.get();
                if (socket != null) close(socket);
            }, remainingNanos, TimeUnit.NANOSECONDS);
            try (Socket plain = new Socket()) {
                active.set(plain);
                int connectTimeout = remainingMillis(deadline);
                if (connectTimeout < 1) return failure(started, 0, "AI_CONFIG_TEST_TIMEOUT");
                plain.connect(new InetSocketAddress(addresses.getFirst(), port), Math.min(10_000, connectTimeout));
                plain.setSoTimeout(Math.max(1, remainingMillis(deadline)));
                String host = AiEndpointPolicy.asciiHost(endpoint);
                try (SSLSocket secured = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(plain, host, port, true)) {
                    // 地址来自先前核验的 DNS 结果；SNI 与证书主机名仍使用原供应商域名。
                    active.set(secured);
                    secured.setSoTimeout(Math.max(1, remainingMillis(deadline)));
                    SSLParameters ssl = secured.getSSLParameters();
                    ssl.setEndpointIdentificationAlgorithm("HTTPS");
                    if (!ipLiteral(host)) ssl.setServerNames(List.of(new SNIHostName(host)));
                    secured.setSSLParameters(ssl);
                    secured.startHandshake();
                    writeRequest(secured.getOutputStream(), endpoint, AiEndpointPolicy.hostHeader(endpoint), target.apiKey(), body);
                    return readResponse(secured.getInputStream(), started);
                }
            } catch (AiEndpointPolicy.EndpointException exception) {
                return failure(started, 0, exception.code());
            } catch (SocketTimeoutException exception) {
                return failure(started, 0, "AI_CONFIG_TEST_TIMEOUT");
            } catch (javax.net.ssl.SSLException exception) {
                return failure(started, 0, "AI_CONFIG_TEST_TLS_FAILED");
            } catch (ProtocolException exception) {
                return failure(started, exception.status, exception.code);
            } catch (IOException exception) {
                return failure(started, 0, timedOut.get() ? "AI_CONFIG_TEST_TIMEOUT" : "AI_CONFIG_TEST_NETWORK_FAILED");
            } catch (RuntimeException exception) {
                return failure(started, 0, "AI_CONFIG_TEST_INVALID_REQUEST");
            } finally {
                timeout.cancel(false);
                close(active.get());
            }
        } catch (AiEndpointPolicy.EndpointException exception) {
            return failure(started, 0, exception.code());
        } catch (RuntimeException exception) {
            return failure(started, 0, "AI_CONFIG_TEST_INVALID_REQUEST");
        }
    }

    public record Target(String protocol, String endpoint, String model, String apiKey, int timeoutSeconds,
                         String maxTokensField, boolean jsonMode) { }

    private byte[] requestBody(Target target) {
        String prompt = target.jsonMode() ? "Return a JSON object." : "Reply with OK.";
        Map<String, Object> request = new HashMap<>();
        request.put("model", target.model());
        request.put("stream", false);
        if ("RESPONSES".equals(target.protocol())) {
            request.put("input", prompt);
            request.put("max_output_tokens", MAX_TEST_TOKENS);
            request.put("store", false);
            if (target.jsonMode()) request.put("text", Map.of("format", Map.of("type", "json_object")));
        } else {
            request.put("messages", List.of(Map.of("role", "user", "content", prompt)));
            request.put("max_completion_tokens".equals(target.maxTokensField()) ? "max_completion_tokens" : "max_tokens",
                    MAX_TEST_TOKENS);
            request.put("n", 1);
            if (target.jsonMode()) request.put("response_format", Map.of("type", "json_object"));
        }
        return json.writeValueAsBytes(request);
    }

    private static void writeRequest(OutputStream output, URI endpoint, String host, String apiKey, byte[] body)
            throws IOException {
        String request = "POST " + endpoint.getRawPath() + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Authorization: Bearer " + apiKey + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Accept: application/json\r\n"
                + "Accept-Encoding: identity\r\n"
                + "Connection: close\r\n"
                + "Content-Length: " + body.length + "\r\n\r\n";
        output.write(request.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    private static AiConfigDtos.TestResult readResponse(InputStream input, long started) throws IOException {
        Lines lines = new Lines(input);
        String statusLine = lines.read();
        if (statusLine == null || !statusLine.matches("HTTP/1\\.[01] [0-9]{3}(?: .*)?")) {
            throw new ProtocolException("AI_CONFIG_TEST_INVALID_RESPONSE");
        }
        int status = Integer.parseInt(statusLine.substring(9, 12));
        if (status < 100 || status > 599) throw new ProtocolException("AI_CONFIG_TEST_INVALID_RESPONSE");
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = lines.read()) != null) {
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon < 1) throw new ProtocolException("AI_CONFIG_TEST_INVALID_RESPONSE");
            String name = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();
            if (headers.putIfAbsent(name, value) != null && !"set-cookie".equals(name)) {
                throw new ProtocolException("AI_CONFIG_TEST_INVALID_RESPONSE");
            }
        }
        if (line == null) throw new ProtocolException("AI_CONFIG_TEST_INVALID_RESPONSE");
        if (status >= 300 && status < 400) throw new ProtocolException("AI_CONFIG_TEST_REDIRECT_REJECTED", status);
        if (status < 200 || status >= 300 || status == 101) {
            return new AiConfigDtos.TestResult(false, status, latency(started), "AI_CONFIG_TEST_PROVIDER_ERROR");
        }
        String encoding = headers.getOrDefault("content-encoding", "identity");
        if (!"identity".equalsIgnoreCase(encoding)) throw new ProtocolException("AI_CONFIG_TEST_UNSUPPORTED_ENCODING");
        String length = headers.get("content-length");
        String transfer = headers.get("transfer-encoding");
        if (length != null && transfer != null) throw new ProtocolException("AI_CONFIG_TEST_INVALID_RESPONSE");
        if (transfer != null) {
            if (!"chunked".equalsIgnoreCase(transfer)) throw new ProtocolException("AI_CONFIG_TEST_UNSUPPORTED_ENCODING");
            drainChunked(lines);
        } else if (length != null) {
            long amount;
            try { amount = Long.parseLong(length); }
            catch (NumberFormatException exception) { throw new ProtocolException("AI_CONFIG_TEST_INVALID_RESPONSE"); }
            if (amount < 0 || amount > MAX_RESPONSE_BYTES) throw new ProtocolException("AI_CONFIG_TEST_RESPONSE_TOO_LARGE");
            drain(lines, amount);
        } else {
            drainUntilClose(lines);
        }
        return new AiConfigDtos.TestResult(true, status, latency(started), null);
    }

    private static void drainChunked(Lines lines) throws IOException {
        long total = 0;
        int chunks = 0;
        while (true) {
            String header = lines.read();
            if (header == null || ++chunks > 1024) throw new ProtocolException("AI_CONFIG_TEST_INVALID_RESPONSE");
            String sizeText = header.split(";", 2)[0].strip();
            if (!sizeText.matches("[0-9a-fA-F]{1,16}")) throw new ProtocolException("AI_CONFIG_TEST_INVALID_RESPONSE");
            long size;
            try { size = Long.parseLong(sizeText, 16); }
            catch (NumberFormatException exception) { throw new ProtocolException("AI_CONFIG_TEST_INVALID_RESPONSE"); }
            if (size == 0) {
                int trailerBytes = 0;
                String trailer;
                while ((trailer = lines.read()) != null) {
                    if (++trailerBytes > 100) throw new ProtocolException("AI_CONFIG_TEST_INVALID_RESPONSE");
                    if (trailer.isEmpty()) return;
                }
                throw new ProtocolException("AI_CONFIG_TEST_INVALID_RESPONSE");
            }
            if (size > MAX_RESPONSE_BYTES - total) throw new ProtocolException("AI_CONFIG_TEST_RESPONSE_TOO_LARGE");
            total += size;
            drain(lines, size);
            String ending = lines.read();
            if (ending == null || !ending.isEmpty()) throw new ProtocolException("AI_CONFIG_TEST_INVALID_RESPONSE");
        }
    }

    private static void drain(Lines lines, long amount) throws IOException {
        byte[] buffer = new byte[2048];
        long remaining = amount;
        while (remaining > 0) {
            int read = lines.input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) throw new EOFException();
            remaining -= read;
        }
    }

    private static void drainUntilClose(Lines lines) throws IOException {
        byte[] buffer = new byte[2048];
        int total = 0, read;
        while ((read = lines.input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_RESPONSE_BYTES) throw new ProtocolException("AI_CONFIG_TEST_RESPONSE_TOO_LARGE");
        }
    }

    private static long latency(long started) { return Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)); }

    private static AiConfigDtos.TestResult failure(long started, int status, String code) {
        return new AiConfigDtos.TestResult(false, status, latency(started), code);
    }

    private static int remainingMillis(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) return 0;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos) + 1));
    }

    private static void close(Socket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (IOException ignored) { /* 关闭失败不改变测试结果。 */ }
    }

    private static boolean ipLiteral(String host) {
        return host.indexOf(':') >= 0 || host.matches("[0-9.]+");
    }

    private static final class ProtocolException extends IOException {
        private final String code;
        private final int status;
        ProtocolException(String code) { this(code, 0); }
        ProtocolException(String code, int status) { super(code); this.code = code; this.status = status; }
    }

    private static final class Lines {
        private final InputStream input;
        private int total;
        Lines(InputStream input) { this.input = input; }
        String read() throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            while (true) {
                int value = input.read();
                if (value < 0) return null;
                if (++total > MAX_HEADER_BYTES || line.size() >= MAX_LINE_BYTES) {
                    throw new ProtocolException("AI_CONFIG_TEST_HEADER_TOO_LARGE");
                }
                if (value == '\n') {
                    if (line.size() == 0 || line.toByteArray()[line.size() - 1] != '\r') {
                        throw new ProtocolException("AI_CONFIG_TEST_INVALID_RESPONSE");
                    }
                    byte[] bytes = line.toByteArray();
                    return new String(bytes, 0, bytes.length - 1, StandardCharsets.US_ASCII);
                }
                line.write(value);
            }
        }
    }
}
