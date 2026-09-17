package com.ospaulos.vupth.api.establishment.usecase;

import com.ospaulos.vupth.api.establishment.dto.request.DateHoursRequest;
import com.ospaulos.vupth.api.establishment.dto.request.WeeklyHoursRequest;
import com.ospaulos.vupth.api.establishment.dto.response.BusinessHoursResponse;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotFoundException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.valueObject.BusinessHours;
import com.ospaulos.vupth.domain.shared.BusinessClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Horario por dia (#2): leitura publica e as tres escritas do dono (semana inteira, excecao de uma
 * data, remover excecao). As regras moram no {@link BusinessHours}; aqui so se carrega, aplica e grava.
 * "Hoje" e o dia da LOJA ({@link BusinessClock}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManageBusinessHoursUseCase {

    private final EstablishmentRepository repository;

    @Transactional(readOnly = true)
    public BusinessHoursResponse get(UUID establishmentId) {
        Establishment establishment = repository.findById(establishmentId)
                .orElseThrow(() -> new EstablishmentNotFoundException(establishmentId));
        return BusinessHoursResponse.from(establishment.getBusinessHours(), BusinessClock.today());
    }

    @Transactional
    public BusinessHoursResponse replaceWeek(UUID ownerId, UUID establishmentId, WeeklyHoursRequest request) {
        log.info("[BusinessHours] replaceWeek — establishment={} owner={}", establishmentId, ownerId);
        return change(ownerId, establishmentId, hours -> hours.withWeek(request.toWeek()));
    }

    @Transactional
    public BusinessHoursResponse putDate(UUID ownerId, UUID establishmentId, LocalDate date, DateHoursRequest request) {
        log.info("[BusinessHours] putDate — establishment={} owner={} date={}", establishmentId, ownerId, date);
        return change(ownerId, establishmentId,
                hours -> hours.withException(date, request.toDayHours(), BusinessClock.today()));
    }

    @Transactional
    public void deleteDate(UUID ownerId, UUID establishmentId, LocalDate date) {
        log.info("[BusinessHours] deleteDate — establishment={} owner={} date={}", establishmentId, ownerId, date);
        change(ownerId, establishmentId, hours -> hours.withoutException(date, BusinessClock.today()));
    }

    private BusinessHoursResponse change(UUID ownerId, UUID establishmentId, UnaryOperator<BusinessHours> edit) {
        Establishment establishment = repository.findByIdAndOwnerId(establishmentId, ownerId)
                .orElseThrow(() -> new EstablishmentNotFoundException(establishmentId));
        BusinessHours next = edit.apply(establishment.getBusinessHours());
        establishment.applyBusinessHours(next);
        repository.save(establishment);
        return BusinessHoursResponse.from(next, BusinessClock.today());
    }
}
