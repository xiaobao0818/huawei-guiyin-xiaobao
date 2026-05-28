package com.attribution.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Value("${attribution.security.swagger-public:false}")
    private boolean swaggerPublic;

    @Value("${attribution.security.h2-console-public:false}")
    private boolean h2ConsolePublic;

    @Value("${attribution.security.metrics-public:false}")
    private boolean metricsPublic;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                    ReportApiKeyFilter reportApiKeyFilter,
                                                    ClickRateLimitFilter clickRateLimitFilter,
                                                    DebugApiKeyFilter debugApiKeyFilter)
            throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .headers(headers -> headers.frameOptions(frameOptions -> frameOptions.sameOrigin()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                    auth.requestMatchers("/api/v1/health", "/api/v1/health/**", "/api/v1/click").permitAll();
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/report").permitAll();
                    auth.requestMatchers("/actuator/health", "/actuator/health/**").permitAll();

                    if (metricsPublic) {
                        auth.requestMatchers("/actuator/prometheus", "/actuator/metrics/**").permitAll();
                    } else {
                        auth.requestMatchers("/actuator/prometheus", "/actuator/metrics/**").hasRole("ADMIN");
                    }

                    if (swaggerPublic) {
                        auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
                    } else {
                        auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").hasRole("ADMIN");
                    }

                    if (h2ConsolePublic) {
                        auth.requestMatchers("/h2-console/**").permitAll();
                    } else {
                        auth.requestMatchers("/h2-console/**").denyAll();
                    }

                    auth.requestMatchers("/admin/api/**").hasRole("ADMIN");
                    auth.anyRequest().denyAll();
                })
                .httpBasic(Customizer.withDefaults())
                .addFilterBefore(debugApiKeyFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(clickRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(reportApiKeyFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public UserDetailsService userDetailsService(
            @Value("${admin.username}") String username,
            @Value("${admin.password}") String password,
            PasswordEncoder passwordEncoder) {
        return new InMemoryUserDetailsManager(User.withUsername(username)
                .password(passwordEncoder.encode(password))
                .roles("ADMIN")
                .build());
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
