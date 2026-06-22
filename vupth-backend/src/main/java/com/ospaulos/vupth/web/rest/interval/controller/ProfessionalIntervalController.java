package com.ospaulos.vupth.web.rest.interval.controller;

import com.ospaulos.vupth.domain.events.CreateRecurringIntervalCommand;
import com.ospaulos.vupth.domain.events.CreateUnexpectedIntervalCommand;
import com.ospaulos.vupth.api.interval.dto.request.CreateRecurringIntervalRequest;
import com.ospaulos.vupth.api.interval.dto.request.CreateUnexpectedIntervalRequest;
import com.ospaulos.vupth.api.interval.dto.request.UpdateIntervalRequest;
import com.ospaulos.vupth.api.interval.dto.response.IntervalResponse;
import com.ospaulos.vupth.domain.professional.model.ProfessionalInterval;
import com.ospaulos.vupth.domain.professional.service.impl.ProfessionalIntervalService;
import com.ospaulos.vupth.domain.professional.service.impl.ProfessionalIntervalService.IntervalSkipResult;
import com.ospaulos.vupth.api.interval.mapper.IntervalMapper;
import com.ospaulos.vupth.infrastructure.websocket.QueueWebSocketPublisher;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/professionals/{professionalId}/intervals")
@PreAuthorize("hasRole('PROFESSIONAL') or hasRole('ADMIN')")
@Validated
//@AllArgsConstructor
//@RequiredArgsConstructor
//@CrossOrigin(origins = "${app.cors.allowed-origins}")
public class ProfessionalIntervalController {

    @Autowired private IntervalMapper intervalMapper;
    @Autowired private ProfessionalIntervalService intervalService;
    @Autowired private QueueWebSocketPublisher queuePublisher;

    @PostMapping("/recurring")
    @ResponseStatus(HttpStatus.CREATED)
    public IntervalResponse createRecurringInterval(
            @PathVariable @Valid UUID professionalId,
            @RequestBody @Valid CreateRecurringIntervalRequest request) {

        CreateRecurringIntervalCommand command = intervalMapper.toCommand(professionalId, request);
        ProfessionalInterval interval = intervalService.createRecurringInterval(command);

        return intervalMapper.toResponse(interval);
    }

    @PostMapping("/unexpected")
    @ResponseStatus(HttpStatus.CREATED)
    public IntervalResponse createUnexpectedInterval(
            @PathVariable @Valid UUID professionalId,
            @RequestBody @Valid CreateUnexpectedIntervalRequest request) {

        CreateUnexpectedIntervalCommand command = intervalMapper.toCommand(professionalId, request);
        ProfessionalInterval interval = intervalService.createUnexpectedInterval(command);

        return intervalMapper.toResponse(interval);
    }

    @PatchMapping("/{intervalId}")
    public IntervalResponse updateInterval(
            @PathVariable UUID professionalId,
            @PathVariable UUID intervalId,
            @RequestBody @Valid UpdateIntervalRequest request) {

        ProfessionalInterval interval = intervalService.updateInterval(
                professionalId, intervalId,
                request.durationMinutes(), request.fixedStartTime(), request.description());

        return intervalMapper.toResponse(interval);
    }

    @PatchMapping("/{intervalId}/start")
    public IntervalResponse startInterval(
            @PathVariable UUID professionalId,
            @PathVariable UUID intervalId) {

        ProfessionalInterval interval = intervalService.startInterval(professionalId, intervalId);
        return intervalMapper.toResponse(interval);
    }

    @PatchMapping("/{intervalId}/finish")
    public IntervalResponse finishInterval(
            @PathVariable UUID professionalId,
            @PathVariable UUID intervalId) {

        ProfessionalInterval interval = intervalService.finishIntervalManually(professionalId, intervalId);
        return intervalMapper.toResponse(interval);
    }

    /**
     * "Nao almocar" do dia (item #41): pula a refeicao recorrente SO naquele dia, derrubando-a
     * do ETA de todos os clientes na hora. {@code date} default = hoje (UTC). A recorrencia
     * permanece para os proximos dias. Apos o skip, rebroadcast da fila (best-effort).
     */
    @PatchMapping("/{intervalId}/skip")
    public IntervalResponse skipInterval(
            @PathVariable UUID professionalId,
            @PathVariable UUID intervalId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        LocalDate target = Optional.ofNullable(date).orElse(LocalDate.now(ZoneOffset.UTC));
        IntervalSkipResult result = intervalService.skipMeal(professionalId, intervalId, target);
        queuePublisher.broadcastQueue(result.establishmentId());
        return intervalMapper.toResponse(result.interval());
    }

    /** Desfaz o skip do dia (item #41): a refeicao volta a contar no ETA. */
    @DeleteMapping("/{intervalId}/skip")
    public IntervalResponse unskipInterval(
            @PathVariable UUID professionalId,
            @PathVariable UUID intervalId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        LocalDate target = Optional.ofNullable(date).orElse(LocalDate.now(ZoneOffset.UTC));
        IntervalSkipResult result = intervalService.unskipMeal(professionalId, intervalId, target);
        queuePublisher.broadcastQueue(result.establishmentId());
        return intervalMapper.toResponse(result.interval());
    }

    @DeleteMapping("/{intervalId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteInterval(
            @PathVariable UUID professionalId,
            @PathVariable UUID intervalId) {

        intervalService.deleteInterval(professionalId, intervalId);
    }

    @GetMapping
    public List<IntervalResponse> getActiveIntervals(
            @PathVariable UUID professionalId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        //Claude
        LocalDate targetDate = Optional.ofNullable(date).orElse(LocalDate.now(ZoneOffset.UTC));
        //LocalDate targetDate = Optional.ofNullable(date).orElse(LocalDate.now());

        List<ProfessionalInterval> intervals = intervalService
                .getActiveIntervalsForProfessional(professionalId, targetDate);

        return intervalMapper.toResponseList(intervals);
    }

    // Exception handlers
    /*
    @ExceptionHandler(ProfessionalNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleProfessionalNotFound(ProfessionalNotFoundException ex) {
        return new ErrorResponse("PROFESSIONAL_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(IntervalConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleIntervalConflict(IntervalConflictException ex) {
        return new ErrorResponse("INTERVAL_CONFLICT", ex.getMessage());
    }
    */
}
