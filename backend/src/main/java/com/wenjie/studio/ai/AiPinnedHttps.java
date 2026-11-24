package com.wenjie.studio.ai;

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
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 数据库配置的真实请求固定使用已核验公网地址，避免校验后再次解析 DNS。 */
final class AiPinnedHttps {
    private static final int MAX_HEADER_BYTES = 32 * 1024;
    private static final int MAX_LINE_BYTES = 8 * 1024;
    private static final int MAX_CHUNKS = 100_000;
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ai-pinned-https-timeout");
        thread.setDaemon(true);
        return thread;
    });

    private AiPinnedHttps() { }

    static Response post(URI endpoint, List<InetAddress> addresses, byte[] body, String apiKey,
                         int timeoutSeconds, String accept, int maximumBodyBytes, BodyFactory factory) throws ExchangeException {
        long started = System.nanoTime();
        long deadline = started + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        AtomicReference<Socket> active = new AtomicReference<>();
        AtomicBoolean timedOut = new AtomicBoolean();
        var timeout = WATCHDOG.schedule(() -> {
            timedOut.set(true);
            Socket socket = active.get();
            if (socket != null) close(socket);
        }, Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        try (Socket plain = new Socket()) {
            active.set(plain);
            int port = endpoint.getPort() < 0 ? 443 : endpoint.getPort();
            IOException denied = null;
            for (InetAddress address : addresses) {
                try {
                    plain.connect(new InetSocketAddress(address, port), connectTimeout(deadline));
                    denied = null;
                    break;
                } catch (ExchangeException exception) { throw exception; }
                catch (IOException exception) { denied = exception; }
            }
            if (denied != null) throw denied;
            plain.setSoTimeout(readTimeout(deadline));
            String host = AiEndpointPolicy.asciiHost(endpoint);
            try (SSLSocket secured = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(plain, host, port, true)) {
                active.set(secured);
                secured.setSoTimeout(readTimeout(deadline));
                SSLParameters ssl = secured.getSSLParameters();
                ssl.setEndpointIdentificationAlgorithm("HTTPS");
                if (!ipLiteral(host)) ssl.setServerNames(List.of(new SNIHostName(host)));
                secured.setSSLParameters(ssl);
                secured.startHandshake();
                writeRequest(secured.getOutputStream(), endpoint, AiEndpointPolicy.hostHeader(endpoint), apiKey, accept, body);
                Head head = readHead(secured.getInputStream());
                HttpResponse.BodySubscriber<byte[]> subscriber = factory.create(head);
                AtomicBoolean cancelled = new AtomicBoolean();
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override public void request(long count) { }
                    @Override public void cancel() { cancelled.set(true); close(active.get()); }
                });
                byte[] responseBody;
                try {
                    responseBody = readBody(secured.getInputStream(), head, maximumBodyBytes, subscriber, cancelled);
                    subscriber.onComplete();
                    responseBody = subscriber.getBody().toCompletableFuture()
                            .get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (ExecutionException exception) {
                    subscriber.onError(exception);
                    throw new ExchangeException("AI_STREAM_BODY_FAILED", exception);
                } catch (java.util.concurrent.TimeoutException exception) {
                    subscriber.onError(exception);
                    throw new ExchangeException("AI_TIMEOUT_UNKNOWN", exception);
                } catch (IOException | RuntimeException exception) {
                    subscriber.onError(exception);
                    throw exception;
                }
                return new Response(head, responseBody);
            }
        } catch (ExchangeException exception) {
            throw exception;
        } catch (SocketTimeoutException exception) {
            throw new ExchangeException(timedOut.get() ? "AI_TIMEOUT_UNKNOWN" : "AI_NETWORK_UNKNOWN", exception);
        } catch (javax.net.ssl.SSLException exception) {
            throw new ExchangeException("AI_NETWORK_UNKNOWN", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ExchangeException("AI_CANCELED_UNKNOWN", exception);
        } catch (IOException exception) {
            throw new ExchangeException(timedOut.get() ? "AI_TIMEOUT_UNKNOWN" : "AI_NETWORK_UNKNOWN", exception);
        } catch (RuntimeException exception) {
            throw new ExchangeException("AI_RESPONSE_INVALID", exception);
        } finally {
            timeout.cancel(false);
            close(active.get());
        }
    }

    private static void writeRequest(OutputStream output, URI endpoint, String host, String apiKey,
                                     String accept, byte[] body) throws IOException {
        String request = "POST " + endpoint.getRawPath() + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Authorization: Bearer " + apiKey + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Accept: " + accept + "\r\n"
                + "Accept-Encoding: identity\r\n"
                + "Connection: close\r\n"
                + "Content-Length: " + body.length + "\r\n\r\n";
        output.write(request.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    private static Head readHead(InputStream input) throws IOException {
        Lines lines = new Lines(input);
        String statusLine = lines.read();
        if (statusLine == null || !statusLine.matches("HTTP/1\\.[01] [0-9]{3}(?: .*)?"))
            throw new ExchangeException("AI_RESPONSE_INVALID", null);
        int status = Integer.parseInt(statusLine.substring(9, 12));
        if (status < 200 || status > 599) throw new ExchangeException("AI_RESPONSE_INVALID", null);
        Map<String, List<String>> headers = new HashMap<>();
        String line;
        while ((line = lines.read()) != null) {
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon < 1) throw new ExchangeException("AI_RESPONSE_INVALID", null);
            String name = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();
            if (!name.matches("[!#$%&'*+.^_`|~0-9a-z-]+") || value.indexOf('\0') >= 0)
                throw new ExchangeException("AI_RESPONSE_INVALID", null);
            List<String> values = headers.computeIfAbsent(name, ignored -> new java.util.ArrayList<>());
            if (!values.isEmpty() && !"set-cookie".equals(name))
                throw new ExchangeException("AI_RESPONSE_INVALID", null);
            values.add(value);
        }
        if (line == null) throw new ExchangeException("AI_RESPONSE_INVALID", null);
        return new Head(status, Map.copyOf(headers));
    }

    private static byte[] readBody(InputStream input, Head head, int maximumBytes,
                                   HttpResponse.BodySubscriber<byte[]> subscriber, AtomicBoolean cancelled) throws IOException {
        if (head.status() == 204 || head.status() == 304) return new byte[0];
        String transfer = header(head, "transfer-encoding");
        String length = header(head, "content-length");
        if (transfer != null && length != null) throw new ExchangeException("AI_RESPONSE_INVALID", null);
        if (transfer != null) {
            if (!"chunked".equalsIgnoreCase(transfer)) throw new ExchangeException("AI_RESPONSE_INVALID", null);
            return readChunked(input, maximumBytes, subscriber, cancelled);
        }
        if (length != null) {
            long amount;
            try { amount = Long.parseLong(length); }
            catch (NumberFormatException exception) { throw new ExchangeException("AI_RESPONSE_INVALID", exception); }
            if (amount < 0) throw new ExchangeException("AI_RESPONSE_INVALID", null);
            if (amount > maximumBytes) throw new ExchangeException("AI_RESPONSE_TOO_LARGE", null);
            return readAmount(input, amount, maximumBytes, subscriber, cancelled);
        }
        return readUntilClose(input, maximumBytes, subscriber, cancelled);
    }

    private static byte[] readChunked(InputStream input, int maximumBytes,
                                      HttpResponse.BodySubscriber<byte[]> subscriber, AtomicBoolean cancelled) throws IOException {
        Lines lines = new Lines(input);
        int total = 0, chunks = 0;
        while (true) {
            if (cancelled.get()) throw new ExchangeException("AI_STREAM_BODY_FAILED", null);
            String line = lines.read();
            if (line == null || ++chunks > MAX_CHUNKS) throw new ExchangeException("AI_RESPONSE_INVALID", null);
            String sizeText = line.split(";", 2)[0].strip();
            if (!sizeText.matches("[0-9a-fA-F]{1,16}")) throw new ExchangeException("AI_RESPONSE_INVALID", null);
            long size = Long.parseLong(sizeText, 16);
            if (size == 0) {
                int trailers = 0;
                while ((line = lines.read()) != null) {
                    if (++trailers > 100) throw new ExchangeException("AI_RESPONSE_INVALID", null);
                    if (line.isEmpty()) return new byte[0];
                }
                throw new ExchangeException("AI_RESPONSE_INVALID", null);
            }
            if (size > maximumBytes - total) throw new ExchangeException("AI_RESPONSE_TOO_LARGE", null);
            total += (int) size;
            feed(input, size, maximumBytes, subscriber, cancelled);
            String ending = lines.read();
            if (ending == null || !ending.isEmpty()) throw new ExchangeException("AI_RESPONSE_INVALID", null);
        }
    }

    private static byte[] readAmount(InputStream input, long amount, int maximumBytes,
                                     HttpResponse.BodySubscriber<byte[]> subscriber, AtomicBoolean cancelled) throws IOException {
        if (amount > maximumBytes) throw new ExchangeException("AI_RESPONSE_TOO_LARGE", null);
        feed(input, amount, maximumBytes, subscriber, cancelled);
        return new byte[0];
    }

    private static byte[] readUntilClose(InputStream input, int maximumBytes,
                                         HttpResponse.BodySubscriber<byte[]> subscriber, AtomicBoolean cancelled) throws IOException {
        byte[] buffer = new byte[8192];
        int total = 0, read;
        while ((read = input.read(buffer)) != -1) {
            if (cancelled.get()) throw new ExchangeException("AI_STREAM_BODY_FAILED", null);
            if (read > maximumBytes - total) throw new ExchangeException("AI_RESPONSE_TOO_LARGE", null);
            total += read;
            subscriber.onNext(List.of(ByteBuffer.wrap(buffer, 0, read).asReadOnlyBuffer()));
        }
        return new byte[0];
    }

    private static void feed(InputStream input, long amount, int maximumBytes,
                             HttpResponse.BodySubscriber<byte[]> subscriber, AtomicBoolean cancelled) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = amount;
        while (remaining > 0) {
            if (cancelled.get()) throw new ExchangeException("AI_STREAM_BODY_FAILED", null);
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) throw new EOFException();
            remaining -= read;
            subscriber.onNext(List.of(ByteBuffer.wrap(buffer, 0, read).asReadOnlyBuffer()));
        }
    }

    private static String header(Head head, String name) {
        List<String> values = head.headers().get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    private static int connectTimeout(long deadline) throws ExchangeException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new ExchangeException("AI_TIMEOUT_UNKNOWN", null);
        return (int) Math.min(10_000, Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos)));
    }

    private static int readTimeout(long deadline) throws ExchangeException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new ExchangeException("AI_TIMEOUT_UNKNOWN", null);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos) + 1));
    }

    private static boolean ipLiteral(String host) {
        return host.indexOf(':') >= 0 || host.matches("[0-9.]+");
    }

    private static void close(Socket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (IOException ignored) { /* 关闭失败不改变已确定的结果。 */ }
    }

    @FunctionalInterface interface BodyFactory { HttpResponse.BodySubscriber<byte[]> create(Head head); }

    record Head(int status, Map<String, List<String>> headers) { }
    record Response(Head head, byte[] body) { }

    static final class ExchangeException extends IOException {
        private final String code;
        ExchangeException(String code, Throwable cause) { super(code, cause); this.code = code; }
        String code() { return code; }
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
                if (++total > MAX_HEADER_BYTES || line.size() >= MAX_LINE_BYTES)
                    throw new ExchangeException("AI_RESPONSE_INVALID", null);
                if (value == '\n') {
                    if (line.size() == 0 || line.toByteArray()[line.size() - 1] != '\r')
                        throw new ExchangeException("AI_RESPONSE_INVALID", null);
                    byte[] bytes = line.toByteArray();
                    return new String(bytes, 0, bytes.length - 1, StandardCharsets.ISO_8859_1);
                }
                line.write(value);
            }
        }
    }
}
