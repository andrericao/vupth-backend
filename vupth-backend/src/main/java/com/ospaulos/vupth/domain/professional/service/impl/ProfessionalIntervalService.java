package com.ospaulos.vupth.domain.professional.service.impl;

import com.ospaulos.vupth.domain.shared.BusinessClock;
import com.ospaulos.vupth.domain.interval.exception.InvalidIntervalTimeException;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotFoundException;
import com.ospaulos.vupth.domain.interval.valueObject.IntervalType;
import com.ospaulos.vupth.domain.events.CreateRecurringIntervalCommand;
import com.ospaulos.vupth.domain.events.CreateUnexpectedIntervalCommand;
import com.ospaulos.vupth.domain.interval.exception.IntervalConflictException;
import com.ospaulos.vupth.domain.interval.exception.IntervalNotFoundException;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.model.ProfessionalInterval;
import com.ospaulos.vupth.domain.events.IntervalCreatedEvent;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@Transactional
@AllArgsConstructor
public class ProfessionalIntervalService {

    private final ApplicationEventPublisher eventPublisher;
    private final ProfessionalRepository professionalRepository;
    private final ProfessionalIntervalRepository intervalRepository;

    public ProfessionalInterval createRecurringInterval(@Valid CreateRecurringIntervalCommand command) {

        Professional professional = professionalRepository.findById(command.professionalId())
                .orElseThrow(() -> new ProfessionalNotFoundException(command.professionalId()));

        validateRecurringIntervalCreation(professional, command);
        validateWithinBusinessHours(professional, command.fixedStartTime(), command.durationMinutes());

        ProfessionalInterval interval = ProfessionalInterval.createRecurring(
                professional,
                command.durationMinutes(),
                command.fixedStartTime(),
                command.recurrencePattern(),
                command.description()
        );

        ProfessionalInterval saved = intervalRepository.save(interval);

        // Publish event (for auditing, notifications, etc.)
        eventPublisher.publishEvent(new IntervalCreatedEvent(saved, IntervalType.RECURRENT));

        return saved;
    }

    public ProfessionalInterval createUnexpectedInterval(@Valid CreateUnexpectedIntervalCommand command) {

        Professional professional = professionalRepository.findById(command.professionalId())
                .orElseThrow(() -> new ProfessionalNotFoundException(command.professionalId()));

        validateUnexpectedIntervalCreation(professional, command);

        ProfessionalInterval interval = ProfessionalInterval.createUnexpected(
                professional,
                command.durationMinutes(),
                command.startTime(),
                command.description()
        );

        ProfessionalInterval saved = intervalRepository.save(interval);
        eventPublisher.publishEvent(new IntervalCreatedEvent(saved, IntervalType.UNEXPECTED));

        return saved;
    }

    /**
     * Seed das refeicoes padrao (spec item #11a): ao criar um profissional, ja
     * nascem almoco (12:00) e janta (18:00), recorrentes em dias uteis, 60min cada.
     *
     * Sao apenas PREVISAO — nascem com {@code isActive = false} e so comecam de fato
     * quando o profissional inicia manualmente (regra 3 da spec; item (d) ainda pendente).
     * Sem validacao de conflito: o profissional acabou de ser criado, nao ha intervalos.
     */
    public List<ProfessionalInterval> seedDefaultMeals(Professional professional) {
        List<ProfessionalInterval> meals = List.of(
                ProfessionalInterval.createLunchInterval(professional),
                ProfessionalInterval.createDinnerInterval(professional)
        );

        List<ProfessionalInterval> saved = intervalRepository.saveAll(meals);
        log.info("[Interval] seeded default meals for professional={} ({} intervals)",
                professional.getId(), saved.size());
        return saved;
    }

    /**
     * Edita um intervalo (spec item #11b/c). Semantica PATCH: aplica apenas os
     * campos non-null. Valida que o intervalo pertence ao profissional do path.
     * Nao re-valida conflito aqui (refeicoes nascem inativas e a query de conflito
     * so olha ativos — fica para o item (g)).
     */
    public ProfessionalInterval updateInterval(UUID professionalId, UUID intervalId,
                                               Integer durationMinutes, LocalTime fixedStartTime,
                                               String description) {

        ProfessionalInterval interval = intervalRepository.findById(intervalId)
                .orElseThrow(() -> new IntervalNotFoundException(intervalId));

        if (!interval.getProfessional().getId().equals(professionalId)) {
            throw new IntervalNotFoundException(intervalId);
        }

        if (durationMinutes != null) {
            interval.setDurationMinutes(durationMinutes);
        }
        if (fixedStartTime != null) {
            interval.setFixedStartTime(fixedStartTime);
        }
        if (description != null) {
            interval.setDescription(description);
        }

        // Apos aplicar o patch, a refeicao (recorrente) ainda precisa caber na janela de funcionamento.
        if (interval.getFixedStartTime() != null && interval.getDurationMinutes() != null) {
            validateWithinBusinessHours(interval.getProfessional(),
                    interval.getFixedStartTime(), interval.getDurationMinutes());
        }

        ProfessionalInterval saved = intervalRepository.save(interval);
        log.info("[Interval] updated — id={} professional={}", intervalId, professionalId);
        return saved;
    }

    /**
     * Inicia manualmente um intervalo (spec item #11d-start) — o "botao iniciar almoco/janta".
     * Marca {@code actualStartTime}/{@code isActive}/{@code plannedEndTime}.
     *
     * Autonomia do dono: pode iniciar a qualquer hora (sem janela de horario). Unico bloqueio:
     * a mesma refeicao ja foi iniciada OU encerrada HOJE → {@link IntervalConflictException} (409).
     * Estado ativo remanescente de outro dia (stale) e resetado antes de iniciar (day-scoped).
     */
    public ProfessionalInterval startInterval(UUID professionalId, UUID intervalId) {
        ProfessionalInterval interval = intervalRepository.findById(intervalId)
                .orElseThrow(() -> new IntervalNotFoundException(intervalId));

        if (!interval.getProfessional().getId().equals(professionalId)) {
            throw new IntervalNotFoundException(intervalId);
        }

        LocalDate today = BusinessClock.today(); // BUG2b: dia da LOJA
        if (interval.isStartedOn(today) || interval.isDoneOn(today)) {
            throw new IntervalConflictException("This interval was already started/taken today.");
        }

        // reset de estado ativo remanescente de outro dia (nunca encerrado) — day-scoped
        interval.setIsActive(false);
        interval.startInterval();

        ProfessionalInterval saved = intervalRepository.save(interval);
        log.info("[Interval] started — id={} professional={} actualStart={}",
                intervalId, professionalId, saved.getActualStartTime());
        return saved;
    }

    /**
     * Resultado de um skip/unskip — o intervalo afetado + o establishment do profissional
     * (para o broadcast da fila via WebSocket, feito pela camada web apos o commit).
     */
    public record IntervalSkipResult(ProfessionalInterval interval, UUID establishmentId) {}

    /**
     * Pula a refeicao SO naquele dia (spec item #41 — "Nao almocar"). A refeicao recorrente
     * cai do ETA de todos os clientes na hora (consumido por {@code QueueUtils.isSkippedOn}),
     * mas a recorrencia segue intacta para os proximos dias.
     *
     * So vale para refeicoes RECURRENT que ainda nao foram iniciadas/encerradas no dia —
     * se ja iniciou, usar {@code finish} ({@link IntervalConflictException} 409). Idempotente:
     * pular um dia ja pulado nao faz nada. Ownership pelo path (404).
     */
    public IntervalSkipResult skipMeal(UUID professionalId, UUID intervalId, LocalDate date) {
        ProfessionalInterval interval = loadOwned(professionalId, intervalId);

        if (interval.getIntervalType() != IntervalType.RECURRENT) {
            throw new IntervalConflictException("Only recurring meals can be skipped.");
        }
        if (interval.isStartedOn(date) || interval.isDoneOn(date)) {
            throw new IntervalConflictException(
                    "Meal already started/taken on " + date + " — use finish instead.");
        }

        interval.skipOn(date);
        ProfessionalInterval saved = intervalRepository.save(interval);
        log.info("[Interval] skipped — id={} professional={} date={}", intervalId, professionalId, date);
        return new IntervalSkipResult(saved, establishmentIdOf(saved));
    }

    /**
     * Desfaz o skip do dia (spec item #41) — a refeicao volta a contar no ETA. Idempotente:
     * desfazer um dia nao pulado nao faz nada. Ownership pelo path (404).
     */
    public IntervalSkipResult unskipMeal(UUID professionalId, UUID intervalId, LocalDate date) {
        ProfessionalInterval interval = loadOwned(professionalId, intervalId);
        interval.unskipOn(date);
        ProfessionalInterval saved = intervalRepository.save(interval);
        log.info("[Interval] unskipped — id={} professional={} date={}", intervalId, professionalId, date);
        return new IntervalSkipResult(saved, establishmentIdOf(saved));
    }

    /** Carrega um intervalo garantindo que pertence ao profissional do path (senao 404). */
    private ProfessionalInterval loadOwned(UUID professionalId, UUID intervalId) {
        ProfessionalInterval interval = intervalRepository.findById(intervalId)
                .orElseThrow(() -> new IntervalNotFoundException(intervalId));
        if (!interval.getProfessional().getId().equals(professionalId)) {
            throw new IntervalNotFoundException(intervalId);
        }
        return interval;
    }

    private UUID establishmentIdOf(ProfessionalInterval interval) {
        return interval.getProfessional().getEstablishment() != null
                ? interval.getProfessional().getEstablishment().getId()
                : null;
    }

    /**
     * Encerrar ao atender (spec item #11e): quando o profissional inicia um atendimento,
     * a refeicao/intervalo que ele iniciou manualmente e ainda esta em curso e encerrada
     * (reason {@code ATTEND_NEXT_CLIENT}; {@code finishedEarly} conforme o horario).
     *
     * So encerra intervalos efetivamente INICIADOS (com {@code actualStartTime} e
     * {@code plannedEndTime}); intervalos apenas previstos — refeicoes recorrentes que
     * nunca foram iniciadas — nao sao afetados (regra 3 da spec). Idempotente: sem
     * intervalo ativo, nao faz nada.
     */
    public void finishActiveIntervalsOnAttend(UUID professionalId) {
        for (ProfessionalInterval interval : intervalRepository.findByProfessionalIdAndIsActiveTrue(professionalId)) {
            if (interval.getActualStartTime() == null || interval.getPlannedEndTime() == null) {
                continue; // apenas previsto / sem inicio real — nada a encerrar
            }
            interval.finishInterval("ATTEND_NEXT_CLIENT");
            intervalRepository.save(interval);
            log.info("[Interval] auto-finished on attend — id={} professional={} finishedEarly={}",
                    interval.getId(), professionalId, interval.getFinishedEarly());
        }
    }

    /**
     * Encerra manualmente um intervalo (spec item #11e2) — o "botao encerrar refeicao",
     * sem precisar atender ninguem. Marca {@code actualEndTime}/{@code isActive=false}/
     * {@code finishedEarly}, reason {@code MANUAL}.
     *
     * Valida ownership pelo path (404). So encerra intervalos ATIVOS (iniciados); encerrar
     * uma refeicao que nao esta em curso → {@link IntervalConflictException} (409).
     */
    public ProfessionalInterval finishIntervalManually(UUID professionalId, UUID intervalId) {
        ProfessionalInterval interval = intervalRepository.findById(intervalId)
                .orElseThrow(() -> new IntervalNotFoundException(intervalId));

        if (!interval.getProfessional().getId().equals(professionalId)) {
            throw new IntervalNotFoundException(intervalId);
        }

        if (!Boolean.TRUE.equals(interval.getIsActive())) {
            throw new IntervalConflictException("Interval is not active — nothing to finish.");
        }

        interval.finishInterval("MANUAL");
        ProfessionalInterval saved = intervalRepository.save(interval);
        log.info("[Interval] finished manually — id={} professional={} finishedEarly={}",
                intervalId, professionalId, saved.getFinishedEarly());
        return saved;
    }

    /**
     * Remove uma refeicao/intervalo (spec item #11f) — o profissional apaga uma refeicao
     * que nao usa (ex.: nao janta). Hard delete: o registro recorrente some. Para voltar a
     * ter a refeicao, basta cria-la de novo (`POST .../recurring`).
     *
     * Valida ownership pelo path (404). Permitido em qualquer estado (e o dado do dono).
     */
    public void deleteInterval(UUID professionalId, UUID intervalId) {
        ProfessionalInterval interval = intervalRepository.findById(intervalId)
                .orElseThrow(() -> new IntervalNotFoundException(intervalId));

        if (!interval.getProfessional().getId().equals(professionalId)) {
            throw new IntervalNotFoundException(intervalId);
        }

        intervalRepository.delete(interval);
        log.info("[Interval] deleted — id={} professional={}", intervalId, professionalId);
    }

    @Transactional
    public List<ProfessionalInterval> getActiveIntervalsForProfessional(UUID professionalId, LocalDate date) {
        //return intervalRepository.findActiveIntervalsForProfessionalAndDate(professionalId, date);

        log.info("SERVICE PROFESSIONALINTERVAL - GETACTIVEINTERVALS()");

        // BUG2b: o "dia" e o da LOJA — quem chama passa BusinessClock.today(), entao a fatia tem
        // de ir da meia-noite LOCAL a meia-noite LOCAL seguinte (com corte em UTC, uma refeicao
        // iniciada as 22:00 caia no dia seguinte no fuso -03:00).
        OffsetDateTime startOfDay = BusinessClock.at(date, LocalTime.MIDNIGHT);
        OffsetDateTime startOfNextDay = BusinessClock.at(date.plusDays(1), LocalTime.MIDNIGHT);
        return intervalRepository.findActiveIntervalsForProfessionalAndDate(professionalId, startOfDay, startOfNextDay);
    }

    @Transactional
    public boolean isProfessionalInInterval(UUID professionalId, OffsetDateTime dateTime) {
        return intervalRepository.existsActiveIntervalAt(professionalId, dateTime);
    }

    /**
     * Valida que a refeicao recorrente cabe na janela de funcionamento do estabelecimento
     * (spec #11g2): {@code fixedStartTime >= abertura} e {@code fixedStartTime + duracao <= fechamento}
     * (ex.: janta de 60min nao inicia <60min antes de fechar). So valida quando o estabelecimento
     * tem horario definido — sem horario (null), nao restringe (assume-se dia inteiro).
     */
    private void validateWithinBusinessHours(Professional professional, LocalTime start, Integer durationMinutes) {
        if (professional == null || professional.getEstablishment() == null
                || start == null || durationMinutes == null) {
            return;
        }
        LocalTime opening = professional.getEstablishment().getOpeningTime();
        LocalTime closing = professional.getEstablishment().getClosingTime();
        if (opening == null || closing == null) {
            return; // sem horario de funcionamento definido — nao valida
        }

        LocalTime end = start.plusMinutes(durationMinutes);
        if (start.isBefore(opening) || end.isAfter(closing)) {
            throw new InvalidIntervalTimeException(
                    "Interval must fit within business hours (" + opening + " to " + closing
                            + "); requested " + start + " for " + durationMinutes + "min.");
        }
    }

    private void validateRecurringIntervalCreation(Professional professional, CreateRecurringIntervalCommand command) {
        // Overlap calculado em Java (a antiga query nativa estava escrita em JPQL — invalida).
        // Mantem a semantica original: so colide com recorrentes ATIVOS do profissional.
        LocalTime newStart = command.fixedStartTime();
        LocalTime newEnd = newStart.plusMinutes(command.durationMinutes());

        boolean hasConflict = intervalRepository
                .findActiveByProfessionalAndType(professional.getId(), IntervalType.RECURRENT)
                .stream()
                .anyMatch(existing -> {
                    LocalTime exStart = existing.getFixedStartTime();
                    if (exStart == null) {
                        return false;
                    }
                    LocalTime exEnd = exStart.plusMinutes(existing.getDurationMinutes());
                    return newStart.isBefore(exEnd) && exStart.isBefore(newEnd);
                });

        if (hasConflict) {
            throw new IntervalConflictException("There is already a break at this time!");
        }
    }

    // Claude
    private void validateUnexpectedIntervalCreation(Professional professional, CreateUnexpectedIntervalCommand command) {
        if (command.startTime().isBefore(OffsetDateTime.now())) {
            throw new InvalidIntervalTimeException("Cannot create range in past!");
        }

        OffsetDateTime endTime = command.startTime().plusMinutes(command.durationMinutes());

        boolean hasConflict = intervalRepository.existsUnexpectedIntervalConflict(
                professional.getId(), command.startTime(), endTime);

        if (hasConflict) {
            throw new IntervalConflictException("There is already a break at this time!");
        }
    }
/*
    private void validateUnexpectedIntervalCreation(Professional professional, CreateUnexpectedIntervalCommand command) {
        // Check that the interval is not in the past
        if (command.startTime().isBefore(OffsetDateTime.now())) {
            throw new InvalidIntervalTimeException("Cannot create range in past!");
        }

        // Verificar conflitos com outros intervalos
        boolean hasConflict = intervalRepository.existsUnexpectedIntervalConflict(
                professional.getId(), command.startTime().toOffsetTime(), command.durationMinutes());

        if (hasConflict) {
            throw new IntervalConflictException("There is already a break at this time!");
        }*/

    
    // TODO: Validate ProfessionalInterval start time vs end time
    // TODO: poder adicionar mais 5 minutos (inclusive ter um campo para isso
    // "Encerrar ao atender" (spec #11e) feito: finishActiveIntervalsOnAttend, chamado em
    // ProfessionalServiceImpl.startAttendance.
}