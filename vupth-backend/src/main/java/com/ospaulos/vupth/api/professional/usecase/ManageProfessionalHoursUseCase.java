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
    private final com.ospaulos.vupth.domain.attendance.cut.service.CutRequestService cutRequestService;

    @Transactional(readOnly = true)
    public BusinessHoursResponse get(UUID professionalId) {
        Professional professional = load(professionalId);
        return BusinessHoursResponse.forProfessional(
                professional.getWorkingHours(), professional.getEffectiveHours(), BusinessClock.today());
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
