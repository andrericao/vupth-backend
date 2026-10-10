// Mesmo pacote da classe sob teste: expiresAtFor, isExpired e setClock sao package-private.
package com.ospaulos.vupth.domain.attendance.cut.service.impl;

import com.ospaulos.vupth.api.websocket.dto.QueueNotification;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.cut.CutRequestStatus;
import com.ospaulos.vupth.domain.attendance.cut.model.CutRequest;
import com.ospaulos.vupth.domain.attendance.cut.repository.CutRequestRepository;
import com.ospaulos.vupth.domain.attendance.exception.CutRequestExpiredException;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.service.AttendanceService;
import com.ospaulos.vupth.domain.client.model.Client;
import com.ospaulos.vupth.domain.client.repository.ClientRepository;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.professional.valueObject.ProfessionalStatus;
import com.ospaulos.vupth.domain.serviceItem.repository.ServiceItemRepository;
import com.ospaulos.vupth.domain.shared.BusinessClock;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import com.ospaulos.vupth.infrastructure.websocket.QueueWebSocketPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Quando o pedido de corte vence — DEC-27 com a emenda pedido-vence-no-horario (Andre, 10/10): "o pedido
 * dura ate o horario de interesse ou ate o profissional aceitar". Nunca fica sem vencimento, e o aceite
 * confere a hora.
 */
class CutRequestValidityTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 5);
    private static final OffsetDateTime NOW = BusinessClock.at(DAY, LocalTime.of(10, 0));

    @Nested
    @DisplayName("expires_at no POST")
    class ExpiresAt {

        @Test
        @DisplayName("sem horario, profissional em expediente: vale 5 min")
        void noDesiredTime_working_fiveMinutes() {
            assertThat(CutRequestServiceImpl.expiresAtFor(null, NOW, ProfessionalStatus.WORKING))
                    .isEqualTo(NOW.plusMinutes(5));
            assertThat(CutRequestServiceImpl.expiresAtFor(null, NOW, ProfessionalStatus.ON_BREAK))
                    .isEqualTo(NOW.plusMinutes(5));
        }

        @Test
        @DisplayName("sem horario, profissional em Fora (assim que abrir): vale ate 23:59 de hoje, nao passa para amanha")
        void noDesiredTime_off_endOfDay() {
            assertThat(CutRequestServiceImpl.expiresAtFor(null, NOW, ProfessionalStatus.OFF))
                    .isEqualTo(BusinessClock.at(DAY, LocalTime.of(23, 59)));
        }

        @Test
        @DisplayName("em Fora, pedido as 23:57: nao nasce vencido, vale os 5 min (passa das 23:59)")
        void off_lastMinutesOfDay_fiveMinutes() {
            OffsetDateTime at2357 = BusinessClock.at(DAY, LocalTime.of(23, 57));
            assertThat(CutRequestServiceImpl.expiresAtFor(null, at2357, ProfessionalStatus.OFF))
                    .isEqualTo(at2357.plusMinutes(5));
        }

        @Test
        @DisplayName("hoje as 14:00, pedido as 10:00: vence as 14:00")
        void today_expiresAtDesiredTime() {
            OffsetDateTime at14 = BusinessClock.at(DAY, LocalTime.of(14, 0));
            assertThat(CutRequestServiceImpl.expiresAtFor(at14, NOW, ProfessionalStatus.WORKING)).isEqualTo(at14);
        }

        @Test
        @DisplayName("hoje com menos de 5 min: pedido as 13:57 para as 14:00 vence as 14:00, nao as 14:02")
        void todayInLessThanFiveMinutes_expiresAtDesiredTime() {
            OffsetDateTime at1357 = BusinessClock.at(DAY, LocalTime.of(13, 57));
            OffsetDateTime at14 = BusinessClock.at(DAY, LocalTime.of(14, 0));
            assertThat(CutRequestServiceImpl.expiresAtFor(at14, at1357, ProfessionalStatus.WORKING)).isEqualTo(at14);
        }

        @Test
        @DisplayName("amanha as 14:00: vence amanha as 14:00 (nao fica sem vencimento)")
        void tomorrow_expiresAtDesiredTime() {
            OffsetDateTime tomorrow14 = BusinessClock.at(DAY.plusDays(1), LocalTime.of(14, 0));
            assertThat(CutRequestServiceImpl.expiresAtFor(tomorrow14, NOW, ProfessionalStatus.OFF))
                    .isEqualTo(tomorrow14)
                    .isNotNull();
        }

        @Test
        @DisplayName("virada do dia: pedido as 23:58 para 00:30 e outro dia e vence as 00:30")
        void dayTurn() {
            OffsetDateTime at2358 = BusinessClock.at(DAY, LocalTime.of(23, 58));
            OffsetDateTime at0030 = BusinessClock.at(DAY.plusDays(1), LocalTime.of(0, 30));
            assertThat(CutRequestServiceImpl.isForAnotherDay(at0030, at2358)).isTrue();
            assertThat(CutRequestServiceImpl.expiresAtFor(at0030, at2358, ProfessionalStatus.WORKING)).isEqualTo(at0030);
        }

        @Test
        @DisplayName("fuso: o mesmo instante com -03:00 e com Z vence no mesmo instante")
        void sameInstantDifferentOffsets() {
            OffsetDateTime local = OffsetDateTime.of(2026, 10, 6, 14, 0, 0, 0, ZoneOffset.ofHours(-3));
            OffsetDateTime utc = local.withOffsetSameInstant(ZoneOffset.UTC);
            assertThat(CutRequestServiceImpl.expiresAtFor(local, NOW, ProfessionalStatus.WORKING))
                    .isEqualTo(CutRequestServiceImpl.expiresAtFor(utc, NOW, ProfessionalStatus.WORKING).withOffsetSameInstant(ZoneOffset.ofHours(-3)));
        }

        @Test
        @DisplayName("fuso: 01:00Z ainda e o dia anterior na loja (22:00 de Brasilia)")
        void utcEarlyMorningIsPreviousStoreDay() {
            OffsetDateTime at22Local = BusinessClock.at(DAY, LocalTime.of(22, 0));
            OffsetDateTime oneAmUtc = OffsetDateTime.of(2026, 10, 6, 1, 0, 0, 0, ZoneOffset.UTC);
            assertThat(CutRequestServiceImpl.isForAnotherDay(oneAmUtc, at22Local)).isFalse();
            assertThat(CutRequestServiceImpl.expiresAtFor(null, oneAmUtc, ProfessionalStatus.OFF))
                    .isEqualTo(BusinessClock.at(DAY, LocalTime.of(23, 59)));
        }
    }

    @Nested
    @DisplayName("isExpired: o limite exato")
    class Boundary {

        private final OffsetDateTime at14 = BusinessClock.at(DAY, LocalTime.of(14, 0));

        private CutRequest cut(OffsetDateTime expiresAt) {
            CutRequest c = new CutRequest();
            c.setExpiresAt(expiresAt);
            return c;
        }

        @Test
        @DisplayName("1 min antes: vale")
        void oneMinuteBefore_valid() {
            assertThat(CutRequestServiceImpl.isExpired(cut(at14), at14.minusMinutes(1))).isFalse();
        }

        @Test
        @DisplayName("na hora exata: ja venceu")
        void exactTime_expired() {
            assertThat(CutRequestServiceImpl.isExpired(cut(at14), at14)).isTrue();
        }

        @Test
        @DisplayName("1 min depois: venceu")
        void oneMinuteAfter_expired() {
            assertThat(CutRequestServiceImpl.isExpired(cut(at14), at14.plusMinutes(1))).isTrue();
        }

        @Test
        @DisplayName("legado sem vencimento (antes da migration 032): nao vence pelo relogio")
        void legacyNull_notExpired() {
            assertThat(CutRequestServiceImpl.isExpired(cut(null), at14)).isFalse();
        }
    }

    @Nested
    @DisplayName("aceite e timer com relogio fixo")
    class AcceptAndTimer {

        private final OffsetDateTime at14 = BusinessClock.at(DAY.plusDays(1), LocalTime.of(14, 0));
        private final UUID cutId = UUID.randomUUID();
        private final UUID profId = UUID.randomUUID();
        private final UUID clientId = UUID.randomUUID();
        private final UUID establishmentId = UUID.randomUUID();

        private CutRequestRepository cutRequestRepository;
        private AttendanceService attendanceService;
        private QueueWebSocketPublisher queuePublisher;
        private PushNotificationPublisher pushPublisher;
        private CutRequestServiceImpl service;
        private CutRequest cut;

        @BeforeEach
        void setUp() {
            cutRequestRepository = mock(CutRequestRepository.class);
            attendanceService = mock(AttendanceService.class);
            queuePublisher = mock(QueueWebSocketPublisher.class);
            pushPublisher = mock(PushNotificationPublisher.class);
            service = new CutRequestServiceImpl(cutRequestRepository, mock(ProfessionalRepository.class),
                    mock(EstablishmentRepository.class), mock(ClientRepository.class),
                    mock(ServiceItemRepository.class), attendanceService, queuePublisher, pushPublisher);

            Professional professional = mock(Professional.class);
            when(professional.getId()).thenReturn(profId);
            Client client = mock(Client.class);
            when(client.getId()).thenReturn(clientId);
            Establishment establishment = mock(Establishment.class);
            when(establishment.getId()).thenReturn(establishmentId);

            cut = new CutRequest();
            cut.setId(cutId);
            cut.setProfessional(professional);
            cut.setClient(client);
            cut.setEstablishment(establishment);
            cut.setServiceItems(List.of());
            cut.setStatus(CutRequestStatus.PENDING);
            cut.setDesiredTime(at14);
            cut.setExpiresAt(at14);
            when(cutRequestRepository.findByIdEager(cutId)).thenReturn(Optional.of(cut));
            when(cutRequestRepository.save(any(CutRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        }

        private void clockAt(OffsetDateTime instant) {
            service.setClock(Clock.fixed(instant.toInstant(), ZoneOffset.UTC));
        }

        @Test
        @DisplayName("aceitar 1 min antes do horario: ACCEPTED, agendado naquele horario")
        void acceptOneMinuteBefore_accepted() {
            Attendance attendance = mock(Attendance.class);
            when(attendance.getId()).thenReturn(UUID.randomUUID());
            when(attendance.getStatus()).thenReturn(AttendanceStatus.SCHEDULED);
            when(attendanceService.createScheduledFromCutRequest(any(), any(), any(), any())).thenReturn(attendance);
            clockAt(at14.minusMinutes(1));

            service.acceptCutRequest(cutId, profId);

            assertThat(cut.getStatus()).isEqualTo(CutRequestStatus.ACCEPTED);
            verify(pushPublisher, never()).notifyUser(eq(clientId), eq("Pedido de corte expirado"), anyString(), anyMap());
        }

        @Test
        @DisplayName("aceitar na hora exata: 409 CUT_REQUEST_EXPIRED, pedido EXPIRED, cliente e profissional avisados")
        void acceptAtExactTime_expired() {
            clockAt(at14);

            assertThatThrownBy(() -> service.acceptCutRequest(cutId, profId))
                    .isInstanceOf(CutRequestExpiredException.class);

            assertThat(cut.getStatus()).isEqualTo(CutRequestStatus.EXPIRED);
            verify(pushPublisher).notifyUser(eq(clientId), eq("Pedido de corte expirado"),
                    eq("O horario pedido passou sem resposta do profissional."), anyMap());
            verify(queuePublisher).notifyClient(eq(profId), any(QueueNotification.class));
        }

        @Test
        @DisplayName("aceitar 30 min depois (o exemplo do Andre, 14:30): 409, EXPIRED, nada agendado")
        void acceptThirtyMinutesLate_expired() {
            clockAt(at14.plusMinutes(30));

            assertThatThrownBy(() -> service.acceptCutRequest(cutId, profId))
                    .isInstanceOf(CutRequestExpiredException.class);

            assertThat(cut.getStatus()).isEqualTo(CutRequestStatus.EXPIRED);
            verify(attendanceService, never()).createIntentionFromCutRequest(any(), any(), any(), any(), any());
            verify(attendanceService, never()).createScheduledFromCutRequest(any(), any(), any(), any());
        }

        @Test
        @DisplayName("timer: o vencido vira EXPIRED e avisa o cliente (WS + push) e o profissional (WS)")
        void timerExpiresAndNotifiesBoth() {
            clockAt(at14.plusMinutes(1));
            when(cutRequestRepository.findByStatusAndExpiresAtBefore(eq(CutRequestStatus.PENDING), any()))
                    .thenReturn(List.of(cut));

            assertThat(service.expireDueCutRequests()).isEqualTo(1);

            assertThat(cut.getStatus()).isEqualTo(CutRequestStatus.EXPIRED);
            ArgumentCaptor<QueueNotification> toClient = ArgumentCaptor.forClass(QueueNotification.class);
            verify(queuePublisher).notifyClient(eq(clientId), toClient.capture());
            verify(queuePublisher).notifyClient(eq(profId), any(QueueNotification.class));
            verify(pushPublisher).notifyUser(eq(clientId), eq("Pedido de corte expirado"), anyString(), anyMap());
        }

        @Test
        @DisplayName("timer: o que ainda nao venceu fica intacto")
        void timerLeavesFutureAlone() {
            clockAt(at14.minusMinutes(1));
            when(cutRequestRepository.findByStatusAndExpiresAtBefore(eq(CutRequestStatus.PENDING), any()))
                    .thenReturn(List.of());

            assertThat(service.expireDueCutRequests()).isZero();
            assertThat(cut.getStatus()).isEqualTo(CutRequestStatus.PENDING);
        }
    }
}
