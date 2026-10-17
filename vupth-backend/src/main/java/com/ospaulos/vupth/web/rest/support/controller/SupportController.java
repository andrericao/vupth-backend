package com.ospaulos.vupth.web.rest.support.controller;

import com.ospaulos.vupth.api.support.dto.request.ArticleVoteRequest;
import com.ospaulos.vupth.api.support.dto.request.SupportContactRequest;
import com.ospaulos.vupth.domain.support.service.ArticleVoteService;
import com.ospaulos.vupth.infrastructure.web.ClientAddress;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final ArticleVoteService articleVoteService;

    /** O header de onde vem o IP de quem chamou (o mesmo do limite de requisicoes). */
    @Value("${vupth.auth.rate-limit.ip-header:}")
    private String ipHeader;

    @PostMapping("/contact")
    public ResponseEntity<Void> contact(@RequestBody @Valid SupportContactRequest request) {
        supportContactService.forward(request);
        return ResponseEntity.noContent().build();
    }

    /**
     * {@code POST /support/articles/{slug}/vote} — o joinha ou dedao do artigo (voto-do-artigo). Publico, 204;
     * 422 com slug fora do formato ou voto invalido; 429 pelo limite por IP.
     */
    @PostMapping("/articles/{slug}/vote")
    public ResponseEntity<Void> vote(@PathVariable String slug, @RequestBody @Valid ArticleVoteRequest request,
                                     HttpServletRequest http) {
        articleVoteService.vote(slug, request.vote(), ClientAddress.ip(http, ipHeader));
        return ResponseEntity.noContent().build();
    }
}
