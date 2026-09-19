package com.enterprise.aiknowledge.controller;

import com.enterprise.aiknowledge.dto.RagRequest;
import com.enterprise.aiknowledge.dto.RagResponse;
import com.enterprise.aiknowledge.service.RagService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing the Retrieval-Augmented Generation (RAG) question-answering endpoint.
 *
 * <p><strong>Endpoint:</strong> {@code POST /api/rag/ask}</p>
 * <p><strong>Access:</strong> Authenticated users (USER or ADMIN).</p>
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagService ragService;

    @Autowired
    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    /**
     * Answers a natural-language question grounded strictly in enterprise document knowledge.
     *
     * @param request        RAG request containing user query and optional topK
     * @param authentication current user authentication principal
     * @return RAG response containing grounded answer and sources
     */
    @PostMapping("/ask")
    public ResponseEntity<RagResponse> ask(
            @Valid @RequestBody RagRequest request,
            Authentication authentication) {
        String currentUserEmail = authentication.getName();
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

        RagResponse response = ragService.ask(request, currentUserEmail, isAdmin);
        return ResponseEntity.ok(response);
    }
}
