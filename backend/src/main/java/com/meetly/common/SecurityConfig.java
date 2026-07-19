package com.meetly.common;

import com.meetly.auth.JwtAuthFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    private final JwtAuthFilter jwtAuthFilter;
    private final CorsProperties corsProperties;

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * In production the SPA and API share an origin (ingress routes /api), so CORS rarely
     * kicks in; the config still matters when the SPA is served from another domain, and it
     * keeps meetly.cors.allowed-origins a live setting rather than a dead declaration.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(corsProperties.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);   // refresh cookie httpOnly
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // /actuator is not exposed through the ingress — probes hit the pod directly
                // and Prometheus scrapes internally via the ServiceMonitor
                .requestMatchers("/api/v1/auth/**", "/actuator/health", "/actuator/health/**",
                        "/actuator/prometheus",
                        "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST,
                        "/api/v1/meetings/*/join").permitAll()
                .requestMatchers("/api/v1/livekit/webhook").permitAll()
                .requestMatchers("/ws/**").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(eh -> eh.authenticationEntryPoint((req, res, ex) -> {
                res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                res.setContentType("application/problem+json");
                res.getWriter().write("""
                        {"status":401,"detail":"Unauthorized","code":"INVALID_CREDENTIALS"}""");
            }))
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
