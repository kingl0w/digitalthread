package com.aetnios.dt.query;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-client fixed-window rate limit on /graphql: this service is deployed as a public,
 * unauthenticated demo, so the only guard it needs is against someone hammering the endpoint.
 * ponytail: in-memory fixed window, resets on redeploy, single-instance only; move to a real
 * limiter (bucket4j/redis) if this ever runs more than one replica or needs smooth limits.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int LIMIT_PER_MINUTE = 30;

    private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
    private volatile long window = 0;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (!req.getRequestURI().startsWith("/graphql")) {
            chain.doFilter(req, res);
            return;
        }
        long now = minuteNow();
        if (now != window) {
            window = now;
            counts.clear();
        }
        // behind Render's proxy the client is in X-Forwarded-For. Take the LAST hop: the proxy
        // appends the address it actually saw, while earlier hops are client-supplied and
        // spoofable (rotating fake first hops would mint a fresh rate-limit identity per request)
        String forwarded = req.getHeader("X-Forwarded-For");
        String client = forwarded != null ? forwarded.substring(forwarded.lastIndexOf(',') + 1).trim()
                : req.getRemoteAddr();
        if (counts.computeIfAbsent(client, k -> new AtomicInteger()).incrementAndGet() > LIMIT_PER_MINUTE) {
            res.setStatus(429);
            res.setContentType("application/json");
            res.getWriter().write("{\"errors\":[{\"message\":\"rate limit exceeded, try again in a minute\"}]}");
            return;
        }
        chain.doFilter(req, res);
    }

    // seam so tests can pin the window instead of racing the wall clock
    long minuteNow() {
        return System.currentTimeMillis() / 60_000;
    }
}
