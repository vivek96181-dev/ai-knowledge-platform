package com.enterprise.aiknowledge.security;

import com.enterprise.aiknowledge.exception.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Called by Spring Security when an <strong>unauthenticated</strong> request
 * hits a protected endpoint (missing token, invalid token, expired token).
 *
 * <p>Spring Security's filter chain calls {@link #commence} instead of continuing
 * to the controller. This means the {@code GlobalExceptionHandler} is NOT involved
 * here — we write the JSON response directly to the HTTP response.</p>
 *
 * <p><strong>HTTP 401 Unauthorized:</strong> "I don't know who you are — please authenticate."</p>
 *
 * <p>The response format matches the project's existing {@link ErrorResponse} structure
 * so the frontend always gets a consistent JSON shape.</p>
 */
@Component
public class SecurityAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public SecurityAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.UNAUTHORIZED.value(),
                HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                "Authentication required",
                request.getRequestURI()
        );

        objectMapper.writeValue(response.getOutputStream(), errorResponse);
    }
}
