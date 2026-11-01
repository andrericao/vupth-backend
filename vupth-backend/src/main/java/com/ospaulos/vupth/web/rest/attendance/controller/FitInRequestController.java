package com.ospaulos.vupth.web.rest.attendance.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.attendance.fitin.service.FitInRequestService;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Pedido de encaixe na fila do dia (encaixe-diluido, DEC-136 a 141). Todas as rotas respondem so ao cliente e ao profissional do
 * pedido; qualquer outro usuario recebe o 404 de um id inexistente.
 */
@RestController
@RequestMapping("/attendances/fit-in-requests")
@RequiredArgsConstructor
public class FitInRequestController {

    private final FitInRequestService service;

    public record CreateBody(@NotNull @JsonProperty("professional_id") UUID professionalId,
                             @NotEmpty @JsonProperty("service_item_ids") List<UUID> serviceItemIds,
                             @JsonProperty("ahead_count") int aheadCount,
                             @NotNull @JsonProperty("desired_time") OffsetDateTime desiredTime,
                             @NotNull @JsonProperty("extra") BigDecimal extra) {}

    public record CounterBody(@NotNull @JsonProperty("extra") BigDecimal extra) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ViewBody(UUID id, String status,
                           @JsonProperty("ahead_count") int aheadCount,
                           @JsonProperty("desired_time") OffsetDateTime desiredTime,
                           @JsonProperty("current_extra") BigDecimal currentExtra,
                           @JsonProperty("last_offer_by") String lastOfferBy,
                           @JsonProperty("final_extra") BigDecimal finalExtra,
                           @JsonProperty("professional_share") BigDecimal professionalShare,
                           @JsonProperty("app_share") BigDecimal appShare,
                           @JsonProperty("client_id") UUID clientId,
                           @JsonProperty("professional_id") UUID professionalId,
                           @JsonProperty("attendance_id") UUID attendanceId,
                           @JsonProperty("created_at") OffsetDateTime createdAt,
                           @JsonProperty("decided_at") OffsetDateTime decidedAt) {
        static ViewBody of(FitInRequestService.View v) {
            return new ViewBody(v.id(), v.status().name(), v.aheadCount(), v.desiredTime(), v.currentExtra(), v.lastOfferBy(),
                    v.finalExtra(), v.professionalShare(), v.appShare(), v.clientId(), v.professionalId(), v.attendanceId(),
                    v.createdAt(), v.decidedAt());
        }
    }

    @PostMapping
    public ResponseEntity<ViewBody> create(@RequestBody @Valid CreateBody body, @AuthenticationPrincipal UserDetailsImpl user) {
        var view = service.create(user.getUserId(), new FitInRequestService.CreateCommand(body.professionalId(),
                body.serviceItemIds(), body.aheadCount(), body.desiredTime(), body.extra()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ViewBody.of(view));
    }

    @GetMapping
    public ResponseEntity<List<ViewBody>> list(@AuthenticationPrincipal UserDetailsImpl user) {
        return ResponseEntity.ok(service.list(user.getUserId()).stream().map(ViewBody::of).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ViewBody> get(@PathVariable UUID id, @AuthenticationPrincipal UserDetailsImpl user) {
        return ResponseEntity.ok(ViewBody.of(service.get(id, user.getUserId())));
    }

    @PutMapping("/{id}/accept")
    public ResponseEntity<ViewBody> accept(@PathVariable UUID id, @AuthenticationPrincipal UserDetailsImpl user) {
        return ResponseEntity.ok(ViewBody.of(service.accept(id, user.getUserId())));
    }

    @PutMapping("/{id}/decline")
    public ResponseEntity<ViewBody> decline(@PathVariable UUID id, @AuthenticationPrincipal UserDetailsImpl user) {
        return ResponseEntity.ok(ViewBody.of(service.decline(id, user.getUserId())));
    }

    @PutMapping("/{id}/cancel")
    public ResponseEntity<ViewBody> cancel(@PathVariable UUID id, @AuthenticationPrincipal UserDetailsImpl user) {
        return ResponseEntity.ok(ViewBody.of(service.cancel(id, user.getUserId())));
    }

    @PutMapping("/{id}/counter")
    public ResponseEntity<ViewBody> counter(@PathVariable UUID id, @RequestBody @Valid CounterBody body,
                                            @AuthenticationPrincipal UserDetailsImpl user) {
        return ResponseEntity.ok(ViewBody.of(service.counter(id, user.getUserId(), body.extra())));
    }

    @GetMapping("/{id}/support")
    public ResponseEntity<FitInRequestService.Support> support(@PathVariable UUID id, @AuthenticationPrincipal UserDetailsImpl user) {
        return ResponseEntity.ok(service.support(id, user.getUserId()));
    }
}
