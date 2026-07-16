package com.ospaulos.vupth.domain.attendance.service;

import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import com.ospaulos.vupth.infrastructure.websocket.QueueWebSocketPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests do gatilho de promoção EVENT-DRIVEN ({@link IntentionPromotionScheduler}). Foca no
 * loop de drenagem e nas guardas de due-check com um {@code AttendanceRepository}/{@code AttendanceService}
 * mockados — sem subir contexto Spring nem depender de temporização real.
 */
class IntentionPromotionSchedulerTest {

    AttendanceRepository repo;
    AttendanceService service;
    QueueWebSocketPublisher queuePublisher;
    PushNotificationPublisher pushPublisher;
    IntentionPromotionScheduler scheduler;

    @BeforeEach
    void setup() {
        repo = mock(AttendanceRepository.class);
        service = mock(AttendanceService.class);
        queuePublisher = mock(QueueWebSocketPublisher.class);
        pushPublisher = mock(PushNotificationPublisher.class);
        scheduler = new IntentionPromotionScheduler(repo, service, queuePublisher, pushPublisher);
        scheduler.init(); // instancia o ThreadPoolTaskScheduler interno
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown(); // encerra threads não-daemon do scheduler para não travar o JVM do teste
    }

    private Attendance intention(UUID id, OffsetDateTime desiredTime) {
        // Entidade real (nao mock): o scheduler so le id/desiredTime, e mockar entidade JPA e fragil
        // com o inline mock maker. @Setter (Lombok) expoe setId/setDesiredTime.
        Attendance a = new Attendance();
        a.setId(id);
        a.setDesiredTime(desiredTime);
        return a;
    }

    @Test
    @DisplayName("drena a cabeça vencida: promove e notifica (WS broadcast + pessoal + push)")
    void drains_due_head() {
        UUID id = UUID.randomUUID();
        UUID estab = UUID.randomUUID();
        UUID client = UUID.randomUUID();
        // Cabeça vencida (desiredTime = agora => now >= now-15min). Depois de promovida, fila vazia.
        when(repo.findEarliestIntention())
                .thenReturn(Optional.of(intention(id, OffsetDateTime.now())))
                .thenReturn(Optional.empty());
        when(service.promoteIntention(id))
                .thenReturn(Optional.of(new AttendanceService.PromotionResult(id, estab, client)));

        scheduler.runPromotionCycle();

        verify(service).promoteIntention(id);
        verify(queuePublisher).broadcastQueue(estab);
        verify(queuePublisher).notifyClient(eq(client), any());
        verify(pushPublisher).notifyUser(eq(client), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("cabeça NÃO vencida: não promove, apenas reagenda")
    void skips_not_due_head() {
        UUID id = UUID.randomUUID();
        // desiredTime a 2h => now < desiredTime-15min => não vencida.
        when(repo.findEarliestIntention())
                .thenReturn(Optional.of(intention(id, OffsetDateTime.now().plusHours(2))));

        scheduler.runPromotionCycle();

        verify(service, never()).promoteIntention(any());
        verify(queuePublisher, never()).broadcastQueue(any());
    }

    @Test
    @DisplayName("sem intenções: ocioso, não promove nada")
    void idle_when_empty() {
        when(repo.findEarliestIntention()).thenReturn(Optional.empty());

        scheduler.runPromotionCycle();

        verify(service, never()).promoteIntention(any());
        verify(queuePublisher, never()).broadcastQueue(any());
    }

    @Test
    @DisplayName("promoção de convidado (clientId null): rebroadcast, mas sem WS pessoal nem push")
    void guest_promotion_no_personal_notification() {
        UUID id = UUID.randomUUID();
        UUID estab = UUID.randomUUID();
        when(repo.findEarliestIntention())
                .thenReturn(Optional.of(intention(id, OffsetDateTime.now())))
                .thenReturn(Optional.empty());
        when(service.promoteIntention(id))
                .thenReturn(Optional.of(new AttendanceService.PromotionResult(id, estab, null)));

        scheduler.runPromotionCycle();

        verify(queuePublisher).broadcastQueue(estab);
        verify(queuePublisher, never()).notifyClient(any(), any());
        verify(pushPublisher, never()).notifyUser(any(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("corrida (promoteIntention devolve empty): drena sem notificar e avança")
    void race_noop_does_not_notify() {
        UUID id = UUID.randomUUID();
        when(repo.findEarliestIntention())
                .thenReturn(Optional.of(intention(id, OffsetDateTime.now())))
                .thenReturn(Optional.empty());
        when(service.promoteIntention(id)).thenReturn(Optional.empty()); // já cancelada/promovida

        scheduler.runPromotionCycle();

        verify(service).promoteIntention(id);
        verify(queuePublisher, never()).broadcastQueue(any());
    }
}
