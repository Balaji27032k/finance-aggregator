package com.personal.finance.security;

import com.personal.finance.common.Hashing;
import com.personal.finance.repository.SyncDeviceRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticates "Authorization: Bearer <device token>" by comparing the token's SHA-256 with stored hashes. */
public class DeviceTokenFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";
    private static final int MAX_TOKEN_LENGTH = 128;

    private final SyncDeviceRepository devices;

    public DeviceTokenFilter(SyncDeviceRepository devices) {
        this.devices = devices;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER)) {
            String token = header.substring(BEARER.length()).trim();
            if (!token.isEmpty() && token.length() <= MAX_TOKEN_LENGTH) {
                devices.findByTokenHashAndRevokedAtIsNull(Hashing.sha256Hex(token)).ifPresent(device ->
                        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                                new DevicePrincipal(device.getId()), null, List.of(new SimpleGrantedAuthority("ROLE_DEVICE")))));
            }
        }
        chain.doFilter(request, response);
    }
}
