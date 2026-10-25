package com.ospaulos.vupth.web.rest.asaas;

import com.fasterxml.jackson.databind.JsonNode;
import com.ospaulos.vupth.infrastructure.asaas.AsaasWebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /webhooks/asaas — o aviso do Asaas (sandbox). Publico, mas so aceita quem traz o {@code asaas-access-token} igual ao
 * {@code ASAAS_WEBHOOK_TOKEN}. Sem o token configurado a rota responde 404 (desligada); com token errado, 403. Sempre 200
 * rapido para um aviso valido (tambem o repetido), senao o Asaas pausa a fila.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/webhooks/asaas")
public class AsaasWebhookController {

    private final AsaasWebhookService service;

    @PostMapping
    public ResponseEntity<Void> receive(@RequestHeader(name = "asaas-access-token", required = false) String token,
                                        @RequestBody JsonNode body) {
        return switch (service.receive(token, body)) {
            case OK, DUPLICATE -> ResponseEntity.ok().build();
            case DISABLED -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            case INVALID -> ResponseEntity.badRequest().build();
        };
    }
}
