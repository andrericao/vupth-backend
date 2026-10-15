package com.ospaulos.vupth.web.rest.support.controller;

import com.ospaulos.vupth.api.support.dto.request.SupportContactRequest;
import com.ospaulos.vupth.domain.support.service.SupportContactService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /support/contact} — o formulario do site de suporte (formulario-do-suporte, DEC-59).
 * Publico, sem login; o limite por IP e o do {@code AuthRateLimitFilter} (429). Responde 204 quando a
 * mensagem saiu, 422 com campo invalido. O site precisa estar em {@code CORS_ALLOWED_ORIGINS}.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/support")
public class SupportController {

    private final SupportContactService supportContactService;

    @PostMapping("/contact")
    public ResponseEntity<Void> contact(@RequestBody @Valid SupportContactRequest request) {
        supportContactService.forward(request);
        return ResponseEntity.noContent().build();
    }
}
