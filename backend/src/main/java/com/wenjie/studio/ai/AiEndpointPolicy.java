package com.wenjie.studio.ai;

import java.net.IDN;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Web 配置只能指向公网 HTTPS；测试连接随后固定使用已核验地址，避免 DNS 重绑定。 */
final class AiEndpointPolicy {
    private static final ExecutorService RESOLVER = Executors.newVirtualThreadPerTaskExecutor();

    private AiEndpointPolicy() { }

    static URI parse(String value) {
        try {
            if (value == null || value.isBlank() || value.length() > 2048) throw new IllegalArgumentException();
            URI uri = URI.create(value.strip());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65_535
                    || uri.getRawPath() == null || uri.getRawPath().isEmpty()
                    || uri.toASCIIString().length() > 2048) throw new IllegalArgumentException();
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new EndpointException("AI_CONFIG_ENDPOINT_INVALID");
        }
    }

    static List<InetAddress> resolve(URI uri) { return resolve(uri, 5_000); }

    static List<InetAddress> resolve(URI uri, int maximumMillis) {
        if (maximumMillis < 1) throw new EndpointException("AI_CONFIG_TEST_DNS_TIMEOUT");
        CompletableFuture<List<InetAddress>> pending = CompletableFuture.supplyAsync(() -> {
            try {
                InetAddress[] addresses = InetAddress.getAllByName(asciiHost(uri));
                if (addresses.length == 0) throw new UnknownHostException();
                return java.util.Arrays.stream(addresses)
                        .map(AiEndpointPolicy::normalize)
                        .toList();
            } catch (UnknownHostException exception) {
                throw new EndpointException("AI_CONFIG_TEST_DNS_FAILED");
            }
        }, RESOLVER);
        try {
            List<InetAddress> addresses = pending.get(maximumMillis, TimeUnit.MILLISECONDS);
            if (addresses.stream().anyMatch(address -> !publicAddress(address))) {
                throw new EndpointException("AI_CONFIG_TEST_ENDPOINT_FORBIDDEN");
            }
            return addresses;
        } catch (TimeoutException exception) {
            throw new EndpointException("AI_CONFIG_TEST_DNS_TIMEOUT");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new EndpointException("AI_CONFIG_TEST_CANCELED");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof EndpointException endpoint) throw endpoint;
            throw new EndpointException("AI_CONFIG_TEST_DNS_FAILED");
        } catch (CancellationException exception) {
            throw new EndpointException("AI_CONFIG_TEST_CANCELED");
        } finally {
            pending.cancel(true);
        }
    }

    static String asciiHost(URI uri) {
        try {
            String host = uri.getHost();
            if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
            return host.indexOf(':') >= 0 ? host : IDN.toASCII(host);
        } catch (IllegalArgumentException exception) { throw new EndpointException("AI_CONFIG_ENDPOINT_INVALID"); }
    }

    static String hostHeader(URI uri) {
        String host = asciiHost(uri);
        String authority = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        int port = uri.getPort();
        return port < 0 || port == 443 ? authority : authority + ":" + port;
    }

    private static InetAddress normalize(InetAddress address) {
        try { return InetAddress.getByAddress(address.getAddress()); }
        catch (UnknownHostException exception) { throw new EndpointException("AI_CONFIG_TEST_DNS_FAILED"); }
    }

    private static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (address instanceof Inet6Address) {
            boolean global = (bytes[0] & 0xe0) == 0x20;
            boolean documentation = bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && bytes[3] == (byte) 0xb8;
            boolean teredo = bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0 && bytes[3] == 0;
            return global && !documentation && !teredo;
        }
        int a = bytes[0] & 0xff, b = bytes[1] & 0xff, c = bytes[2] & 0xff;
        // 除常见私网外，也拒绝共享地址、保留文档段、基准段和特殊用途网段。
        return a != 0 && a != 10 && a != 127 && !(a == 100 && b >= 64 && b <= 127)
                && !(a == 169 && b == 254) && !(a == 172 && b >= 16 && b <= 31)
                && !(a == 192 && b == 168) && !(a == 192 && b == 0)
                && !(a == 198 && (b == 18 || b == 19))
                && !(a == 198 && b == 51 && c == 100) && !(a == 203 && b == 0 && c == 113) && a < 224;
    }

    static final class EndpointException extends RuntimeException {
        private final String code;

        EndpointException(String code) { super(code); this.code = code; }
        String code() { return code; }
    }
}
