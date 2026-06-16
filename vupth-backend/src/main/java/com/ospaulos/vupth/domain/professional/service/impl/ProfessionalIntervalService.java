package com.ospaulos.vupth.domain.professional.service.impl;

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
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
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
                                               Integer durationMinutes, OffsetTime fixedStartTime,
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

        ProfessionalInterval saved = intervalRepository.save(interval);
        log.info("[Interval] updated — id={} professional={}", intervalId, professionalId);
        return saved;
    }

    @Transactional
    public List<ProfessionalInterval> getActiveIntervalsForProfessional(UUID professionalId, LocalDate date) {
        //return intervalRepository.findActiveIntervalsForProfessionalAndDate(professionalId, date);

        log.info("SERVICE PROFESSIONALINTERVAL - GETACTIVEINTERVALS()");

        OffsetDateTime startOfDay = date.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime startOfNextDay = date.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        return intervalRepository.findActiveIntervalsForProfessionalAndDate(professionalId, startOfDay, startOfNextDay);
    }

    @Transactional
    public boolean isProfessionalInInterval(UUID professionalId, OffsetDateTime dateTime) {
        return intervalRepository.existsActiveIntervalAt(professionalId, dateTime);
    }

    private void validateRecurringIntervalCreation(Professional professional, CreateRecurringIntervalCommand command) {
        // Check if an interval already exists at the same time
        boolean hasConflict = intervalRepository.existsRecurringIntervalConflict(
                professional.getId(), command.fixedStartTime(), command.durationMinutes());

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
    // TODO: Add action to serve next attendance, finishing the interval
}