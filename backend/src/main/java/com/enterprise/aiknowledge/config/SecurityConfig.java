package com.enterprise.aiknowledge.config;

import com.enterprise.aiknowledge.security.JwtAuthenticationFilter;
import com.enterprise.aiknowledge.security.SecurityAccessDeniedHandler;
import com.enterprise.aiknowledge.security.SecurityAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * Central Spring Security configuration.
 *
 * <p>This class defines the entire security model of the application in one place:
 * which endpoints are public, which require authentication, which require specific roles,
 * how sessions are managed, and which filters/handlers are active.</p>
 *
 * <p><strong>Interview explanation of the SecurityFilterChain:</strong><br>
 * Every HTTP request passes through a chain of filters before reaching a controller.
 * We configure that chain here. Our custom {@link JwtAuthenticationFilter} runs before
 * Spring's built-in {@code UsernamePasswordAuthenticationFilter}, so JWT auth is
 * resolved first on every request.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final SecurityAuthenticationEntryPoint authEntryPoint;
    private final SecurityAccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            SecurityAuthenticationEntryPoint authEntryPoint,
            SecurityAccessDeniedHandler accessDeniedHandler) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authEntryPoint = authEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    /**
     * Defines the security filter chain — the complete set of security rules.
     *
     * <p><strong>Rule evaluation order matters:</strong> Spring Security evaluates
     * {@code authorizeHttpRequests} rules top-to-bottom. More specific rules must
     * come before more general ones. For example, {@code GET /api/users} (ADMIN only)
     * must appear before {@code GET /api/users/**} (authenticated), otherwise the
     * more specific rule would never be reached.</p>
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // CSRF protection is designed for browser-based (cookie/session) attacks.
            // Our stateless JWT API doesn't use sessions or cookies, so CSRF is not applicable.
            .csrf(AbstractHttpConfigurer::disable)

            // Enable CORS — picks up the CorsConfigurationSource bean or falls back to
            // WebMvcConfig.addCorsMappings() via HandlerMappingIntrospector.
            .cors(withDefaults())

            // STATELESS: Spring Security must NOT create or use an HTTP session.
            // Every request must carry a JWT — no server-side state is remembered.
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // Authorization rules — evaluated in order (most specific first)
            .authorizeHttpRequests(auth -> auth
                // Public endpoints — no token required
                .requestMatchers("/api/health").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/users").permitAll()  // Registration is open

                // ADMIN-only endpoints
                .requestMatchers(HttpMethod.GET, "/api/users").hasRole("ADMIN")       // List all users
                .requestMatchers(HttpMethod.DELETE, "/api/users/**").hasRole("ADMIN") // Delete any user

                // Any authenticated user (USER or ADMIN)
                .requestMatchers(HttpMethod.GET, "/api/users/**").authenticated()     // Get user by ID
                .requestMatchers("/api/auth/me").authenticated()                      // Current user info
                .requestMatchers("/api/documents/**").authenticated()                 // Document management

                // Everything else also requires authentication (safe default)
                .anyRequest().authenticated()
            )

            // Custom error handlers — return our JSON format instead of Spring's default HTML pages
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(authEntryPoint)    // 401: no/invalid token
                .accessDeniedHandler(accessDeniedHandler))   // 403: wrong role

            // Register our JWT filter to run BEFORE Spring's built-in auth filter
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Exposes {@link BCryptPasswordEncoder} as a Spring bean so it can be injected
     * into {@code AuthService} for password verification during login.
     *
     * <p>Strength 10 = 2^10 = 1024 BCrypt iterations. Industry-standard balance
     * between security and performance (~100ms per hash on typical hardware).</p>
     */
    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
