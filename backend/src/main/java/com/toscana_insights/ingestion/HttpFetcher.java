package com.toscana_insights.ingestion;

import com.toscana_insights.common.BadRequestException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Outbound HTTP for connectors. Only https hosts on the allowlist are reachable, redirects are not followed and
 * hosts resolving to loopback/private/link-local addresses are refused, so connectors cannot reach internal services.
 */
@Component
public class HttpFetcher {
    public record Response(int status, HttpHeaders headers, byte[] body) {}
    public static class FetchException extends RuntimeException {
        public FetchException(String message) { super(message); }
    }

    private final Set<String> allowedHosts;
    private final boolean allowPrivate;
    private final long retryDelayMs;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();

    public HttpFetcher(@Value("${app.ingestion.allowed-hosts:}") String allowedHosts,
                       @Value("${app.ingestion.allow-private-addresses:false}") boolean allowPrivate,
                       @Value("${app.ingestion.retry-delay-ms:1000}") long retryDelayMs) {
        this.allowedHosts = Arrays.stream(allowedHosts.split(",")).map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        this.allowPrivate = allowPrivate;
        this.retryDelayMs = retryDelayMs;
    }

    public URI validate(String url) {
        URI uri;
        try { uri = URI.create(url); } catch (IllegalArgumentException e) { throw new BadRequestException("Invalid URL"); }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        if (!https && !(allowPrivate && "http".equalsIgnoreCase(uri.getScheme()))) throw new BadRequestException("Only https URLs are allowed");
        if (uri.getUserInfo() != null) throw new BadRequestException("Credentials in URLs are not allowed");
        if (!allowedHosts.contains(host)) throw new BadRequestException("Host '" + host + "' is not on the allowed host list (app.ingestion.allowed-hosts)");
        if (!allowPrivate) {
            try {
                for (InetAddress a : InetAddress.getAllByName(host)) {
                    if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isMulticastAddress())
                        throw new BadRequestException("Host resolves to a private or internal address");
                }
            } catch (java.net.UnknownHostException e) {
                throw new BadRequestException("Host cannot be resolved");
            }
        }
        return uri;
    }

    /** GET with bounded size and retries (network errors, 429 and 5xx). Never logs headers, which may carry credentials. */
    public Response get(String url, Map<String, String> headers, long maxBytes) {
        URI uri = validate(url);
        FetchException last = new FetchException("Request failed");
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET();
                headers.forEach(request::header);
                HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() == 429 || response.statusCode() >= 500) {
                    response.body().close();
                    last = new FetchException("Source returned HTTP " + response.statusCode());
                    sleep(response.headers().firstValue("Retry-After").map(HttpFetcher::seconds).orElse(0L) * 1000, attempt);
                    continue;
                }
                byte[] body = read(response.body(), maxBytes);
                if (response.statusCode() / 100 != 2) throw new FetchException("Source returned HTTP " + response.statusCode());
                return new Response(response.statusCode(), response.headers(), body);
            } catch (IOException e) {
                last = new FetchException("Network error: " + e.getClass().getSimpleName());
                sleep(0, attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FetchException("Interrupted");
            }
        }
        throw last;
    }

    private static byte[] read(InputStream in, long maxBytes) throws IOException {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long total = 0;
            for (int n; (n = in.read(buf)) > 0; ) {
                total += n;
                if (total > maxBytes) throw new FetchException("Response exceeds the " + maxBytes + " byte limit");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static long seconds(String v) {
        try { return Math.min(Long.parseLong(v.trim()), 30); } catch (NumberFormatException e) { return 0; }
    }

    private void sleep(long retryAfterMs, int attempt) {
        long wait = Math.max(retryAfterMs, retryDelayMs * attempt);
        try { if (attempt < 3) Thread.sleep(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
