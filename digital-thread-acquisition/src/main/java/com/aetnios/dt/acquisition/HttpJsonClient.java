package com.aetnios.dt.acquisition;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Rate-limited HTTP client with cookies and retries. FAA's WAF rejects non-browser User-Agents. */
public class HttpJsonClient {

    public record Response(int status, byte[] body) {
        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private static final int MAX_ATTEMPTS = 4;
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .cookieHandler(new CookieManager())
            .build();
    private final long rateMillis;
    private long lastRequestAt = 0;

    public HttpJsonClient(long rateMillis) {
        this.rateMillis = rateMillis;
    }

    public Response get(String url) throws Exception {
        return send(builder(url).GET().build());
    }

    public Response postForm(String url, String form) throws Exception {
        return send(builder(url)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build());
    }

    private HttpRequest.Builder builder(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", UA)
                .header("Accept", "*/*");
    }

    private Response send(HttpRequest request) throws Exception {
        long backoff = 1000;
        for (int attempt = 1; ; attempt++) {
            throttle();
            HttpResponse<byte[]> resp = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = resp.statusCode();
            if ((status == 429 || status >= 500) && attempt < MAX_ATTEMPTS) {
                System.err.printf("HTTP %d on %s, retry %d/%d after %dms%n",
                        status, request.uri(), attempt, MAX_ATTEMPTS - 1, backoff);
                Thread.sleep(backoff);
                backoff *= 2;
                continue;
            }
            return new Response(status, resp.body());
        }
    }

    private void throttle() throws InterruptedException {
        long wait = lastRequestAt + rateMillis - System.currentTimeMillis();
        if (wait > 0) Thread.sleep(wait);
        lastRequestAt = System.currentTimeMillis();
    }
}
