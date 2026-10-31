package com.ospaulos.vupth.web.rest.professional.controller;

import com.ospaulos.vupth.api.establishment.dto.request.DateHoursRequest;
import com.ospaulos.vupth.api.establishment.dto.request.WeeklyHoursRequest;
import com.ospaulos.vupth.api.establishment.dto.response.BusinessHoursResponse;
import com.ospaulos.vupth.api.professional.usecase.ManageProfessionalHoursUseCase;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Agenda do profissional (agenda-do-profissional, Andre 05/10). GET publico (o Client le para o
 * Agendar); as escritas sao do PROPRIO profissional — o dono nao edita a agenda dos outros. O corpo e o
 * mesmo do horario da loja ({@code /establishment/{id}/hours/...}).
 */
@RestController
@RequestMapping("/professional")
@RequiredArgsConstructor
public class ProfessionalHoursController {

    private final ManageProfessionalHoursUseCase useCase;

    @GetMapping("/{professionalId}/hours")
    public ResponseEntity<BusinessHoursResponse> getHours(@PathVariable UUID professionalId) {
        return ResponseEntity.ok(useCase.get(professionalId));
    }

    @GetMapping("/me/agenda")
    public ResponseEntity<com.ospaulos.vupth.api.professional.dto.response.ProfessionalAgendaResponse> agenda(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal UserDetailsImpl user) {
        return ResponseEntity.ok(useCase.agenda(user.getUserId(), from, to));
    }

    @PutMapping("/me/hours/weekly")
    public ResponseEntity<BusinessHoursResponse> replaceWeek(
            @RequestBody WeeklyHoursRequest request,
            @AuthenticationPrincipal UserDetailsImpl user) {
        return ResponseEntity.ok(useCase.replaceWeek(user.getUserId(), request));
    }

    @PutMapping("/me/hours/dates/{date}")
    public ResponseEntity<BusinessHoursResponse> putDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestBody DateHoursRequest request,
            @AuthenticationPrincipal UserDetailsImpl user) {
        return ResponseEntity.ok(useCase.putDate(user.getUserId(), date, request));
    }

    @DeleteMapping("/me/hours/dates/{date}")
    public ResponseEntity<Void> deleteDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @AuthenticationPrincipal UserDetailsImpl user) {
        useCase.deleteDate(user.getUserId(), date);
        return ResponseEntity.noContent().build();
    }
}
