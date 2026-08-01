package com.ospaulos.vupth.domain.establishment.service;

import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import com.ospaulos.vupth.infrastructure.websocket.QueueWebSocketPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests do timer EVENT-DRIVEN de auto-fechamento ({@link EstablishmentAutoCloseScheduler}). Foca
 * na orquestracao e nas notificacoes do {@code runCycle} com o {@link AutoCloseService} e os publishers
 * mockados — sem subir contexto Spring nem depender de temporizacao real.
 */
class EstablishmentAutoCloseSchedulerTest {

    AutoCloseService autoCloseService;
    QueueWebSocketPublisher queuePublisher;
    PushNotificationPublisher pushPublisher;
    EstablishmentAutoCloseScheduler scheduler;

    @BeforeEach
    void setup() {
        autoCloseService = mock(AutoCloseService.class);
        queuePublisher = mock(QueueWebSocketPublisher.class);
        pushPublisher = mock(PushNotificationPublisher.class);
        // reschedule() no fim do ciclo consulta nextEventAt — mantem ocioso por padrao.
        when(autoCloseService.nextEventAt()).thenReturn(Optional.empty());
        when(autoCloseService.promptDueClosings()).thenReturn(List.of());
        when(autoCloseService.applyDueGraces()).thenReturn(List.of());

        scheduler = new EstablishmentAutoCloseScheduler(autoCloseService, queuePublisher, pushPublisher);
        scheduler.init(); // instancia o ThreadPoolTaskScheduler interno
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    @Test
    @DisplayName("prompt: notifica o profissional (CLOSING_PROMPT + push) e cada cliente na fila (QUEUE_CLOSING), rebroadcast")
    void prompt_notifies_professional_and_queue() {
        UUID prof = UUID.randomUUID();
        UUID estab = UUID.randomUUID();
        UUID attendance = UUID.randomUUID();
        UUID client = UUID.randomUUID();
        OffsetDateTime graceUntil = OffsetDateTime.now().plusMinutes(15);

        when(autoCloseService.promptDueClosings()).thenReturn(List.of(
                new AutoCloseService.PromptedProfessional(prof, estab, graceUntil,
                        List.of(new AutoCloseService.WaitingClient(attendance, client)))));

        scheduler.runCycle();

        verify(queuePublisher).broadcastQueue(estab);
        verify(queuePublisher).notifyClient(eq(prof), argThat(n -> "CLOSING_PROMPT".equals(n.type())
                && graceUntil.equals(n.graceUntil())));
        verify(pushPublisher).notifyUser(eq(prof), anyString(), anyString(), anyMap());
        verify(queuePublisher).notifyClient(eq(client), argThat(n -> "QUEUE_CLOSING".equals(n.type())));
        verify(pushPublisher).notifyUser(eq(client), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("prompt sem fila: notifica so o profissional, nenhum QUEUE_CLOSING")
    void prompt_without_queue_notifies_only_professional() {
        UUID prof = UUID.randomUUID();
        UUID estab = UUID.randomUUID();

        when(autoCloseService.promptDueClosings()).thenReturn(List.of(
                new AutoCloseService.PromptedProfessional(prof, estab,
                        OffsetDateTime.now().plusMinutes(15), List.of())));

        scheduler.runCycle();

        verify(queuePublisher).notifyClient(eq(prof), argThat(n -> "CLOSING_PROMPT".equals(n.type())));
        verify(pushPublisher).notifyUser(eq(prof), anyString(), anyString(), anyMap());
        // Nenhum cliente notificado (so o profissional acima).
        verify(queuePublisher, times(1)).notifyClient(any(), any());
    }

    @Test
    @DisplayName("carencia vencida com auto-OFF: rebroadcast (open_status derivado mudou)")
    void grace_autoOff_rebroadcasts() {
        UUID prof = UUID.randomUUID();
        UUID estab = UUID.randomUUID();

        when(autoCloseService.applyDueGraces()).thenReturn(List.of(
                new AutoCloseService.GraceOutcome(prof, estab, true)));

        scheduler.runCycle();

        verify(queuePublisher).broadcastQueue(estab);
        verify(queuePublisher, never()).notifyClient(any(), any());
    }

    @Test
    @DisplayName("carencia vencida SEM auto-OFF (drenando/auto-correcao): nada a notificar")
    void grace_no_autoOff_is_silent() {
        when(autoCloseService.applyDueGraces()).thenReturn(List.of(
                new AutoCloseService.GraceOutcome(UUID.randomUUID(), UUID.randomUUID(), false)));

        scheduler.runCycle();

        verify(queuePublisher, never()).broadcastQueue(any());
        verify(queuePublisher, never()).notifyClient(any(), any());
    }

    @Test
    @DisplayName("nada devido: ocioso, nenhuma notificacao")
    void idle_notifies_nothing() {
        scheduler.runCycle();

        verify(queuePublisher, never()).broadcastQueue(any());
        verify(queuePublisher, never()).notifyClient(any(), any());
        verify(pushPublisher, never()).notifyUser(any(), anyString(), anyString(), anyMap());
    }
}
