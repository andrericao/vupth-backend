package com.ospaulos.vupth.unit.push;

import com.ospaulos.vupth.domain.notification.model.DeviceToken;
import com.ospaulos.vupth.domain.notification.repository.DeviceTokenRepository;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import com.ospaulos.vupth.infrastructure.push.PushSender;
import com.ospaulos.vupth.infrastructure.push.PushTicket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests — limpeza do device token morto (backlog #28).
 *
 * <p>Antes desta entrega o unico caminho de saida de um token era o logout explicito
 * ({@code DELETE /devices}): quem desinstalava o app deixava o token para tras <b>para sempre</b>, e
 * todo push seguinte era gasto no vazio. A politica mora no publisher — o {@code PushSender} e
 * transporte puro e nao toca em persistencia.</p>
 */
class PushNotificationPublisherTest {

    private static final String TOKEN_VIVO = "ExponentPushToken[vivovivovivovivovivo11]";
    private static final String TOKEN_MORTO = "ExponentPushToken[mortomortomortomorto22]";

    private DeviceTokenRepository deviceTokenRepository;
    private PushSender pushSender;
    private PushNotificationPublisher publisher;
    private UUID userId;

    @BeforeEach
    void setUp() {
        deviceTokenRepository = mock(DeviceTokenRepository.class);
        pushSender = mock(PushSender.class);
        publisher = new PushNotificationPublisher(deviceTokenRepository, pushSender);
        userId = UUID.randomUUID();
    }

    private void givenDevices(String... tokens) {
        List<DeviceToken> devices = java.util.Arrays.stream(tokens)
                .map(t -> new DeviceToken(userId, t, "android"))
                .toList();
        when(deviceTokenRepository.findByUserId(userId)).thenReturn(devices);
    }

    private void notifyUser() {
        publisher.notifyUser(userId, "Sua vez", "Voce e o proximo", Map.of("type", "QUEUE"));
    }

    @Test
    @DisplayName("DeviceNotRegistered apaga SO o token morto")
    void deadTokenIsPurged() {
        givenDevices(TOKEN_VIVO, TOKEN_MORTO);
        when(pushSender.send(any(), any(), any(), any())).thenReturn(List.of(
                PushTicket.accepted(TOKEN_VIVO),
                PushTicket.rejected(TOKEN_MORTO, PushTicket.DEVICE_NOT_REGISTERED, "not registered")));

        notifyUser();

        verify(deviceTokenRepository).deleteByToken(TOKEN_MORTO);
        verify(deviceTokenRepository, never()).deleteByToken(TOKEN_VIVO);
    }

    @Test
    @DisplayName("Ticket aceito nao apaga nada")
    void acceptedTicketKeepsToken() {
        givenDevices(TOKEN_VIVO);
        when(pushSender.send(any(), any(), any(), any()))
                .thenReturn(List.of(PushTicket.accepted(TOKEN_VIVO)));

        notifyUser();

        verify(deviceTokenRepository, never()).deleteByToken(anyString());
    }

    @Test
    @DisplayName("Erro de MENSAGEM (MessageTooBig) nao apaga o device")
    void unrelatedErrorKeepsToken() {
        givenDevices(TOKEN_VIVO);
        when(pushSender.send(any(), any(), any(), any()))
                .thenReturn(List.of(PushTicket.rejected(TOKEN_VIVO, "MessageTooBig", "Message too big")));

        notifyUser();

        verify(deviceTokenRepository, never()).deleteByToken(anyString());
    }

    @Test
    @DisplayName("Sender que nao devolve tickets (rede fora) nao apaga nada e nao lanca")
    void noTicketsIsSafe() {
        givenDevices(TOKEN_VIVO);
        when(pushSender.send(any(), any(), any(), any())).thenReturn(List.of());

        assertThatCode(this::notifyUser).doesNotThrowAnyException();
        verify(deviceTokenRepository, never()).deleteByToken(anyString());
    }

    @Test
    @DisplayName("Sender que devolve null nao quebra o chamador")
    void nullTicketsIsSafe() {
        // Cenario real: um @MockBean PushSender sem stub devolve null, e push nunca pode derrubar
        // a operacao principal (criar atendimento, iniciar, finalizar).
        givenDevices(TOKEN_VIVO);
        when(pushSender.send(any(), any(), any(), any())).thenReturn(null);

        assertThatCode(this::notifyUser).doesNotThrowAnyException();
        verify(deviceTokenRepository, never()).deleteByToken(anyString());
    }

    @Test
    @DisplayName("Falha ao apagar o token morto nao propaga — limpeza e manutencao, nao o push")
    void purgeFailureIsSwallowed() {
        givenDevices(TOKEN_MORTO);
        when(pushSender.send(any(), any(), any(), any())).thenReturn(List.of(
                PushTicket.rejected(TOKEN_MORTO, PushTicket.DEVICE_NOT_REGISTERED, "not registered")));
        doThrow(new RuntimeException("banco fora")).when(deviceTokenRepository).deleteByToken(TOKEN_MORTO);

        assertThatCode(this::notifyUser).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Usuario sem device nao chama o provedor")
    void noDevicesShortCircuits() {
        when(deviceTokenRepository.findByUserId(userId)).thenReturn(List.of());

        notifyUser();

        verify(pushSender, never()).send(any(), any(), any(), any());
    }

    @Test
    @DisplayName("userId nulo e no-op")
    void nullUserIsNoOp() {
        publisher.notifyUser(null, "Sua vez", "Voce e o proximo", Map.of());

        verifyNoInteractions(deviceTokenRepository, pushSender);
    }
}
