package com.noted.backend.config;

import com.noted.backend.security.JwtAuthenticationFilter;
import com.noted.backend.security.OAuth2LoginSuccessHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;
import java.util.Set;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {

    private static final Set<String> KNOWN_OAUTH_ERRORS = Set.of(
            "invalid_client", "invalid_grant", "invalid_request", "invalid_scope",
            "unauthorized_client", "access_denied", "server_error",
            "temporarily_unavailable", "invalid_token_response");

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

    @Value("${app.cors.allowed-origins}")
    private List<String> allowedOrigins;

    // Dang nhap Google that bai: dua nguoi dung VE FRONTEND thay vi de Spring tu
    // chuyen toi "/login?error" tren chinh domain backend. Backend nay co domain
    // rieng (noted-api.*) va khong co trang /login, nen mac dinh cua Spring de
    // nguoi dung roi vao trang trong/404 cua API.
    @Value("${app.frontend-redirect-url:http://localhost:8200/oauth/callback}")
    private String frontendRedirectUrl;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // API stateless dung JWT, khong dung cookie session -> khong can CSRF
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/oauth2/**", "/login/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/drive/callback").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().authenticated()
                )
                .oauth2Login(oauth2 -> oauth2
                        .successHandler(oAuth2LoginSuccessHandler)
                        .failureHandler((request, response, exception) -> {
                            String chiTiet;
                            if (exception instanceof OAuth2AuthenticationException oae) {
                                String code = oae.getError().getErrorCode();
                                if (!KNOWN_OAUTH_ERRORS.contains(code)) code = "unknown";
                                chiTiet = code;
                            } else {
                                chiTiet = exception.getClass().getSimpleName();
                            }
                            String causeClass = exception.getCause() == null
                                    ? "(khong co)"
                                    : exception.getCause().getClass().getSimpleName();
                            log.warn("Dang nhap OAuth2 that bai: {} | goc: {}", chiTiet, causeClass);
                            response.sendRedirect(frontendRedirectUrl + "?error=login_failed");
                        })
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}