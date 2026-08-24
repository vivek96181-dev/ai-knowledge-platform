package com.enterprise.aiknowledge.security;

import com.enterprise.aiknowledge.exception.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Called by Spring Security when an <strong>authenticated</strong> user tries
 * to access a resource they don't have permission for (wrong role).
 *
 * <p>Example: a USER tries to call {@code GET /api/users} which requires ADMIN.
 * Spring Security knows who the user is (their token is valid) but their role
 * is insufficient — so it calls this handler instead of the controller.</p>
 *
 * <p><strong>HTTP 403 Forbidden:</strong> "I know who you are — but you can't do that."</p>
 *
 * <p>Like {@link SecurityAuthenticationEntryPoint}, this writes JSON directly to
 * the response, bypassing {@code GlobalExceptionHandler}.</p>
 */
@Component
public class SecurityAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public SecurityAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException
    ) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.FORBIDDEN.value(),
                HttpStatus.FORBIDDEN.getReasonPhrase(),
                "Access denied",
                request.getRequestURI()
        );

        objectMapper.writeValue(response.getOutputStream(), errorResponse);
    }
}
