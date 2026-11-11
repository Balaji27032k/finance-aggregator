package com.personal.finance.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Fixed one-minute window per client, in memory. Authenticated devices and anonymous callers have separate limits. */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int CLEANUP_THRESHOLD = 10_000;

    private record Window(long minute, int count) {
    }

    private final int anonymousLimit;
    private final int deviceLimit;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(int anonymousLimit, int deviceLimit, Clock clock) {
        this.anonymousLimit = anonymousLimit;
        this.deviceLimit = deviceLimit;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean device = auth != null && (auth.getPrincipal() instanceof DevicePrincipal || auth.getPrincipal() instanceof AdminPrincipal);
        String key = auth != null && auth.getPrincipal() instanceof DevicePrincipal d ? "device:" + d.deviceId()
                : device ? "admin" : "ip:" + request.getRemoteAddr();
        int limit = device ? deviceLimit : anonymousLimit;

        long nowSeconds = clock.instant().getEpochSecond();
        long minute = nowSeconds / 60;
        if (windows.size() > CLEANUP_THRESHOLD) {
            windows.entrySet().removeIf(e -> e.getValue().minute() < minute);
        }
        Window window = windows.merge(key, new Window(minute, 1),
                (old, fresh) -> old.minute() == minute ? new Window(minute, old.count() + 1) : fresh);

        if (window.count() > limit) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(60 - (nowSeconds % 60)));
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429}");
            return;
        }
        chain.doFilter(request, response);
    }
}
