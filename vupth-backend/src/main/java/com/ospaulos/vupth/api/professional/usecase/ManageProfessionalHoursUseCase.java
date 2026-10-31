package com.ospaulos.vupth.api.professional.usecase;

import com.ospaulos.vupth.api.establishment.dto.request.DateHoursRequest;
import com.ospaulos.vupth.api.establishment.dto.request.WeeklyHoursRequest;
import com.ospaulos.vupth.api.establishment.dto.response.BusinessHoursResponse;
import com.ospaulos.vupth.domain.establishment.valueObject.BusinessHours;
import com.ospaulos.vupth.domain.establishment.valueObject.DayHours;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalHoursOutsideEstablishmentException;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotFoundException;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.shared.BusinessClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Agenda do profissional (agenda-do-profissional, Andre 05/10): leitura publica e as tres escritas do
 * PROPRIO profissional (o dono nao edita a dos outros). O modelo e o do horario da loja
 * ({@link BusinessHours}); a regra a mais e que cada dia cabe no horario da loja — o que passa e 422.
 * O que vale de fato e a intersecao ({@link Professional#getEffectiveHours()}): se a loja encolher
 * depois, a agenda dele encolhe junto sem ninguem regravar.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManageProfessionalHoursUseCase {

    private final ProfessionalRepository repository;
    private final com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository attendanceRepository;
    private final com.ospaulos.vupth.domain.attendance.cut.service.CutRequestService cutRequestService;

    @Transactional(readOnly = true)
    public BusinessHoursResponse get(UUID professionalId) {
        Professional professional = load(professionalId);
        return BusinessHoursResponse.forProfessional(
                professional.getWorkingHours(), professional.getEffectiveHours(), BusinessClock.today());
    }

    /** agenda-aberta-semana: os dias de {@code from} a {@code to} (hoje a 30 dias, no maximo 31) ja resolvidos, com os agendamentos. */
    @Transactional(readOnly = true)
    public com.ospaulos.vupth.api.professional.dto.response.ProfessionalAgendaResponse agenda(UUID professionalId, LocalDate from, LocalDate to) {
        Professional professional = load(professionalId);
        LocalDate today = BusinessClock.today();
        LocalDate start = from == null || from.isBefore(today) ? today : from;
        LocalDate end = to == null ? start.plusDays(30) : to;
        int max = com.ospaulos.vupth.api.professional.dto.response.ProfessionalAgendaResponse.MAX_DAYS;
        if (end.isBefore(start)) {
            end = start;
        } else if (end.isAfter(start.plusDays(max - 1L))) {
            end = start.plusDays(max - 1L);
        }
        BusinessHours own = professional.getWorkingHours();
        BusinessHours effective = professional.getEffectiveHours();
        BusinessHours store = professional.getEstablishment() == null
                ? BusinessHours.notConfigured() : professional.getEstablishment().getBusinessHours();
        java.util.Map<LocalDate, java.util.List<com.ospaulos.vupth.api.professional.dto.response.ProfessionalAgendaResponse.Appointment>> byDay =
                new java.util.HashMap<>();
        attendanceRepository.findIntentionsByProfessional(professionalId).forEach(a -> {
            java.time.OffsetDateTime when = a.getScheduledTime() != null ? a.getScheduledTime() : a.getDesiredTime();
            if (when != null) {
                byDay.computeIfAbsent(BusinessClock.dateAt(when), d -> new java.util.ArrayList<>()).add(
                        new com.ospaulos.vupth.api.professional.dto.response.ProfessionalAgendaResponse.Appointment(
                                a.getId(), when, a.getEstimatedTotalMinutes()));
            }
        });
        java.util.List<com.ospaulos.vupth.api.professional.dto.response.ProfessionalAgendaResponse.Day> days = new java.util.ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            DayHours day = effective.isConfigured() ? effective.on(d) : null;
            boolean storeOpen = !store.isConfigured() || !store.on(d).closed();
            var appointments = byDay.getOrDefault(d, java.util.List.of());
            days.add(new com.ospaulos.vupth.api.professional.dto.response.ProfessionalAgendaResponse.Day(
                    d, day != null && day.closed(), day == null ? null : day.opens(), day == null ? null : day.closes(),
                    storeOpen, own.hasExceptionOn(d), appointments));
        }
        return new com.ospaulos.vupth.api.professional.dto.response.ProfessionalAgendaResponse(days);
    }

    @Transactional
    public BusinessHoursResponse replaceWeek(UUID professionalId, WeeklyHoursRequest request) {
        log.info("[ProfessionalHours] replaceWeek — professional={}", professionalId);
        return change(professionalId, hours -> hours.withWeek(request.toWeek()));
    }

    @Transactional
    public BusinessHoursResponse putDate(UUID professionalId, LocalDate date, DateHoursRequest request) {
        log.info("[ProfessionalHours] putDate — professional={} date={}", professionalId, date);
        return change(professionalId, hours -> hours.withException(date, request.toDayHours(), BusinessClock.today()));
    }

    @Transactional
    public void deleteDate(UUID professionalId, LocalDate date) {
        log.info("[ProfessionalHours] deleteDate — professional={} date={}", professionalId, date);
        change(professionalId, hours -> hours.withoutException(date, BusinessClock.today()));
    }

    private BusinessHoursResponse change(UUID professionalId, UnaryOperator<BusinessHours> edit) {
        Professional professional = load(professionalId);
        BusinessHours next = edit.apply(professional.getWorkingHours());
        requireInsideStore(professional, next);
        professional.applyWorkingHours(next);
        repository.save(professional);
        // solicitar-agendamento: abriu um dia que alguem tinha pedido sem aceitar -> o pedido vence e o cliente agenda direto
        cutRequestService.expireRequestsForOpenedDays(professionalId);
        return BusinessHoursResponse.forProfessional(next, professional.getEffectiveHours(), BusinessClock.today());
    }

    /** Cada dia da semana dele cabe no mesmo dia da loja, e cada excecao de hoje em diante, na data da loja. */
    private static void requireInsideStore(Professional professional, BusinessHours mine) {
        if (professional.getEstablishment() == null) {
            return;
        }
        BusinessHours store = professional.getEstablishment().getBusinessHours();
        if (!store.isConfigured()) {
            return;
        }
        for (DayOfWeek day : DayOfWeek.values()) {
            if (!store.onWeekday(day).contains(mine.onWeekday(day))) {
                throw new ProfessionalHoursOutsideEstablishmentException(day.toString());
            }
        }
        for (Map.Entry<LocalDate, DayHours> exception : mine.exceptionsFrom(BusinessClock.today()).entrySet()) {
            if (!store.on(exception.getKey()).contains(exception.getValue())) {
                throw new ProfessionalHoursOutsideEstablishmentException(exception.getKey().toString());
            }
        }
    }

    private Professional load(UUID professionalId) {
        return repository.findById(professionalId)
                .orElseThrow(() -> new ProfessionalNotFoundException(professionalId));
    }
}
