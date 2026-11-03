package com.ospaulos.vupth.web.rest.feedback.controller;

import com.ospaulos.vupth.api.feedback.dto.FeedbackRequest;
import com.ospaulos.vupth.domain.feedback.service.FeedbackService;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /feedback} (feedback-por-email, DEC-167): so logado (qualquer papel). 204 quando saiu; 422 com campo
 * invalido; 429 {@code FEEDBACK_LIMIT} acima do teto diario do usuario.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/feedback")
public class FeedbackController {

    private final FeedbackService feedbackService;

    @PostMapping
    public ResponseEntity<Void> send(@AuthenticationPrincipal UserDetailsImpl principal,
                                     @RequestBody @Valid FeedbackRequest request) {
        feedbackService.send(principal.getUserId(), principal.getUsername(), request);
        return ResponseEntity.noContent().build();
    }
}
