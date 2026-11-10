package com.personal.finance.config;

import com.personal.finance.repository.SyncDeviceRepository;
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
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SyncDeviceRepository devices, AppProperties properties,
                                            Clock clock) throws Exception {
        DeviceTokenFilter tokenFilter = new DeviceTokenFilter(devices);
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
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/v1/**").hasRole("DEVICE")
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) -> problem(response, HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, ex) -> problem(response, HttpStatus.FORBIDDEN)))
                .headers(Customizer.withDefaults())
                .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(rateLimitFilter, DeviceTokenFilter.class);
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
