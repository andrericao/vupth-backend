package com.ospaulos.vupth.web.rest.openwatch.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.openwatch.model.OpenWatch;
import com.ospaulos.vupth.domain.openwatch.model.OpenWatchKind;
import com.ospaulos.vupth.domain.openwatch.service.OpenWatchService;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * avisar-quando-abrir (BAK-8, DEC-160). So logado (qualquer papel). Os pedidos valem so para hoje.
 * <ul>
 *   <li>{@code POST /open-watches} {@code {establishment_id, professional_id?, kind: NOTIFY_OPEN|QUEUE_MOVING}} → 201
 *       (repetir devolve 200 com o mesmo); 404 {@code ESTABLISHMENT_NOT_FOUND}; 422 {@code OPEN_WATCH_PROFESSIONAL_INVALID};
 *       429 {@code OPEN_WATCH_LIMIT} (20 por dia).</li>
 *   <li>{@code GET /open-watches/me} → os de hoje.</li>
 *   <li>{@code DELETE /open-watches/{id}} → 204; de outro usuario ou inexistente, 404 {@code OPEN_WATCH_NOT_FOUND}.</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/open-watches")
public class OpenWatchController {

    private final OpenWatchService service;

    public record OpenWatchRequest(
            @NotNull @JsonProperty("establishment_id") UUID establishmentId,
            @JsonProperty("professional_id") UUID professionalId,
            @NotNull @JsonProperty("kind") OpenWatchKind kind) {
    }

    public record OpenWatchResponse(
            @JsonProperty("id") UUID id,
            @JsonProperty("establishment_id") UUID establishmentId,
            @JsonProperty("professional_id") UUID professionalId,
            @JsonProperty("kind") OpenWatchKind kind,
            @JsonProperty("day") LocalDate day,
            @JsonProperty("created_at") OffsetDateTime createdAt,
            @JsonProperty("fired_at") OffsetDateTime firedAt) {

        static OpenWatchResponse of(OpenWatch w) {
            return new OpenWatchResponse(w.getId(), w.getEstablishmentId(), w.getProfessionalId(), w.getKind(),
                    w.getDay(), w.getCreatedAt(), w.getFiredAt());
        }
    }

    @PostMapping
    public ResponseEntity<OpenWatchResponse> subscribe(@AuthenticationPrincipal UserDetailsImpl principal,
                                                       @RequestBody @Valid OpenWatchRequest request) {
        OpenWatchService.Subscription result = service.subscribe(principal.getUserId(), request.establishmentId(),
                request.professionalId(), request.kind());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(OpenWatchResponse.of(result.watch()));
    }

    @GetMapping("/me")
    public List<OpenWatchResponse> mine(@AuthenticationPrincipal UserDetailsImpl principal) {
        return service.listToday(principal.getUserId()).stream().map(OpenWatchResponse::of).toList();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(@AuthenticationPrincipal UserDetailsImpl principal, @PathVariable UUID id) {
        service.cancel(principal.getUserId(), id);
        return ResponseEntity.noContent().build();
    }
}
