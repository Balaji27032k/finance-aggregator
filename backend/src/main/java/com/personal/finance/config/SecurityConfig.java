package com.personal.finance.config;

import com.personal.finance.repository.SyncDeviceRepository;
import com.personal.finance.security.AdminSessionFilter;
import com.personal.finance.security.AdminSessions;
import com.personal.finance.security.DeviceTokenFilter;
import com.personal.finance.security.RateLimitFilter;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SyncDeviceRepository devices, AppProperties properties,
                                            Clock clock, AdminSessions adminSessions) throws Exception {
        DeviceTokenFilter tokenFilter = new DeviceTokenFilter(devices);
        AdminSessionFilter adminFilter = new AdminSessionFilter(adminSessions);
        RateLimitFilter rateLimitFilter = new RateLimitFilter(
                properties.rateLimit().anonymousPerMinute(), properties.rateLimit().devicePerMinute(), clock);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, "/api/v1/devices/register").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/admin/login").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/**").hasRole("DEVICE")
                        // The dashboard's static files and client-side routes.
                        .requestMatchers(request -> "GET".equals(request.getMethod()) && !request.getRequestURI().startsWith("/api/"))
                        .permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) -> problem(response, HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, ex) -> problem(response, HttpStatus.FORBIDDEN)))
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
                                        + "font-src 'self' data:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'")))
                .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(adminFilter, DeviceTokenFilter.class)
                .addFilterAfter(rateLimitFilter, AdminSessionFilter.class);
        return http.build();
    }

    private static void problem(jakarta.servlet.http.HttpServletResponse response, HttpStatus status)
            throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase()
                + "\",\"status\":" + status.value() + "}");
    }
}
