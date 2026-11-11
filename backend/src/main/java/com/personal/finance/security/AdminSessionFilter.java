package com.personal.finance.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticates the dashboard from its session cookie. */
public class AdminSessionFilter extends OncePerRequestFilter {

    public static final String COOKIE = "fin_session";

    private final AdminSessions sessions;

    public AdminSessionFilter(AdminSessions sessions) {
        this.sessions = sessions;
    }

    public static String tokenFrom(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (COOKIE.equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        return null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String username = sessions.usernameFor(tokenFrom(request));
        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    new AdminPrincipal(username), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        }
        chain.doFilter(request, response);
    }
}
