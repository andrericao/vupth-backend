package com.ospaulos.vupth.domain.professional.service;

import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.valueObject.BusinessHours;
import com.ospaulos.vupth.domain.establishment.valueObject.DayHours;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.professional.valueObject.OperationMode;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** agenda-aberta-semana (DEC-130): o aviso de domingo as 10h ao profissional de agenda sem nenhum dia aberto por ele na semana seguinte. */
class AgendaEveReminderSchedulerTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    private final ProfessionalRepository repository = mock(ProfessionalRepository.class);
    private final PushNotificationPublisher push = mock(PushNotificationPublisher.class);

    private AgendaEveReminderScheduler at(String isoInSaoPaulo) {
        Instant instant = ZonedDateTime.parse(isoInSaoPaulo + "[America/Sao_Paulo]").toInstant();
        return new AgendaEveReminderScheduler(repository, push, Clock.fixed(instant, SP));
    }

    private static Professional professional(OperationMode mode, boolean withStore) {
        Professional p = new Professional(new BaseUser("p@x.com"));
        p.setId(UUID.randomUUID());
        p.setOperationMode(mode);
        if (withStore) {
            p.setEstablishment(new Establishment());
        }
        return p;
    }

    private static void open(Professional p, LocalDate date) {
        BusinessHours own = p.getWorkingHours();
        BusinessHours withWeek = own.isConfigured() ? own : BusinessHours.everyDay(new DayHours(false, LocalTime.of(9, 0), LocalTime.of(17, 0)));
        p.applyWorkingHours(withWeek.withException(date, new DayHours(false, LocalTime.of(10, 0), LocalTime.of(12, 0)), LocalDate.of(2026, 11, 1)));
    }

    @Test
    @DisplayName("o proximo disparo e o domingo as 10h: hoje se ainda nao passou, senao o domingo seguinte")
    void nextFire() {
        assertThat(at("2026-11-01T08:00:00-03:00").nextFire())
                .isEqualTo(ZonedDateTime.parse("2026-11-01T10:00:00-03:00[America/Sao_Paulo]").toInstant());
        assertThat(at("2026-11-01T10:00:00-03:00").nextFire())
                .isEqualTo(ZonedDateTime.parse("2026-11-08T10:00:00-03:00[America/Sao_Paulo]").toInstant());
        assertThat(at("2026-10-28T12:00:00-03:00").nextFire())
                .isEqualTo(ZonedDateTime.parse("2026-11-01T10:00:00-03:00[America/Sao_Paulo]").toInstant());
    }

    @Test
    @DisplayName("avisa so o profissional de agenda sem dia aberto por ele; semana cadastrada nao conta; fila e sem loja ficam de fora")
    void sendsOnlyToAgendaProfessionalsWithoutOpenedDays() {
        // domingo 01/11/2026: a semana seguinte vai de 02/11 a 08/11
        Professional empty = professional(OperationMode.AGENDA_ONLY, true);
        Professional weekOnly = professional(OperationMode.AGENDA_ONLY, true);
        weekOnly.applyWorkingHours(BusinessHours.everyDay(new DayHours(false, LocalTime.of(9, 0), LocalTime.of(17, 0))));
        Professional opened = professional(OperationMode.AGENDA_ONLY, true);
        open(opened, LocalDate.of(2026, 11, 4));
        Professional openedLater = professional(OperationMode.AGENDA_ONLY, true);
        open(openedLater, LocalDate.of(2026, 11, 9));
        Professional queue = professional(OperationMode.QUEUE, true);
        Professional noStore = professional(OperationMode.AGENDA_ONLY, false);
        when(repository.findActiveProfessionals()).thenReturn(List.of(empty, weekOnly, opened, openedLater, queue, noStore));

        assertThat(at("2026-11-01T10:00:00-03:00").sendReminders()).isEqualTo(3);

        verify(push).notifyUser(eq(empty.getId()), any(), any(), anyMap());
        verify(push).notifyUser(eq(weekOnly.getId()), any(), any(), anyMap());
        verify(push).notifyUser(eq(openedLater.getId()), any(), any(), anyMap());
        verify(push, never()).notifyUser(eq(opened.getId()), any(), any(), anyMap());
        verify(push, never()).notifyUser(eq(queue.getId()), any(), any(), anyMap());
        verify(push, never()).notifyUser(eq(noStore.getId()), any(), any(), anyMap());
    }

    @Test
    @DisplayName("sem profissional de agenda, nada e enviado")
    void nothingToSend() {
        when(repository.findActiveProfessionals()).thenReturn(List.of());
        assertThat(at("2026-11-01T10:00:00-03:00").sendReminders()).isZero();
    }
}
