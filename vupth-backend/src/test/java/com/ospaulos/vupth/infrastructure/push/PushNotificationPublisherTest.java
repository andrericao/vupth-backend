package com.ospaulos.vupth.infrastructure.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** O retorno do envio (push-quase-a-vez-nao-chega): true so quando saiu para ao menos um aparelho; o log dos tickets nao quebra o envio. */
class PushNotificationPublisherTest {

    private final com.ospaulos.vupth.domain.notification.repository.DeviceTokenRepository tokens =
            mock(com.ospaulos.vupth.domain.notification.repository.DeviceTokenRepository.class);
    private final PushSender sender = mock(PushSender.class);
    private final com.ospaulos.vupth.domain.notification.repository.PendingPushReceiptRepository receipts =
            mock(com.ospaulos.vupth.domain.notification.repository.PendingPushReceiptRepository.class);
    private final PushNotificationPublisher publisher = new PushNotificationPublisher(tokens, sender, receipts);

    private com.ospaulos.vupth.domain.notification.model.DeviceToken device(String token) {
        var d = mock(com.ospaulos.vupth.domain.notification.model.DeviceToken.class);
        when(d.getToken()).thenReturn(token);
        return d;
    }

    @Test
    @DisplayName("devolve false sem usuario, sem aparelho registrado e quando o envio falha; true quando a Expo recebe (aceito ou recusado)")
    void returnsWhetherItWasSent() {
        UUID user = UUID.randomUUID();
        assertThat(publisher.notifyUser(null, "t", "b", Map.of())).isFalse();

        when(tokens.findByUserId(user)).thenReturn(List.of());
        assertThat(publisher.notifyUser(user, "t", "b", Map.of("type", "X"))).as("sem aparelho").isFalse();

        var aparelho = device("ExponentPushToken[aaaaa]"); // criado antes: stubbing dentro de outro when() quebra o Mockito
        when(tokens.findByUserId(user)).thenReturn(List.of(aparelho));
        when(sender.send(anyList(), anyString(), anyString(), anyMap())).thenThrow(new IllegalStateException("expo fora"));
        assertThat(publisher.notifyUser(user, "t", "b", Map.of("type", "X"))).as("envio falhou").isFalse();

        when(sender.send(anyList(), anyString(), anyString(), anyMap())).thenReturn(List.of(
                PushTicket.accepted("ExponentPushToken[aaaaa]", "tk1"),
                new PushTicket("ExponentPushToken[bbbbb]", false, "InvalidCredentials", "Invalid credentials", null)));
        assertThat(publisher.notifyUser(user, "t", "b", Map.of("type", "QUEUE_NEXT_IN_LINE"))).as("a Expo recebeu o lote").isTrue();
    }
}
