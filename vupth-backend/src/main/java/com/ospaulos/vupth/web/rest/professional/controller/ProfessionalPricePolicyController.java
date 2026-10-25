package com.ospaulos.vupth.web.rest.professional.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.pricepolicy.service.PricePolicyService;
import com.ospaulos.vupth.domain.pricepolicy.service.PricePolicyService.Status;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

/**
 * politica-de-preco-com-ciencia (DEC-100): o profissional le a situacao da politica de precos e registra a ciencia. Sem a ciencia
 * no prazo de 7 dias, abrir a loja e a fila responde 409 {@code PRICE_POLICY_ACK_REQUIRED}.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/professional/me/price-policy")
public class ProfessionalPricePolicyController {

    private final PricePolicyService service;

    public record StatusResponse(
            @JsonProperty("version") Integer version,
            @JsonProperty("published_at") OffsetDateTime publishedAt,
            @JsonProperty("summary") String summary,
            @JsonProperty("acknowledged") boolean acknowledged,
            @JsonProperty("acknowledged_at") OffsetDateTime acknowledgedAt,
            @JsonProperty("noticed_at") OffsetDateTime noticedAt,
            @JsonProperty("deadline_at") OffsetDateTime deadlineAt,
            @JsonProperty("days_left") Long daysLeft,
            @JsonProperty("blocked") boolean blocked) {
        static StatusResponse of(Status s) {
            return new StatusResponse(s.version(), s.publishedAt(), s.summary(), s.acknowledged(), s.acknowledgedAt(),
                    s.noticedAt(), s.deadlineAt(), s.daysLeft(), s.blocked());
        }
    }

    public record AckRequest(@NotNull @JsonProperty("version") Integer version) {}

    @GetMapping
    public ResponseEntity<StatusResponse> status(@AuthenticationPrincipal UserDetailsImpl user) {
        return ResponseEntity.ok(StatusResponse.of(service.status(user.getUserId())));
    }

    @PostMapping("/ack")
    public ResponseEntity<StatusResponse> ack(@AuthenticationPrincipal UserDetailsImpl user,
                                              @RequestBody @Valid AckRequest request) {
        return ResponseEntity.ok(StatusResponse.of(service.acknowledge(user.getUserId(), request.version())));
    }
}
