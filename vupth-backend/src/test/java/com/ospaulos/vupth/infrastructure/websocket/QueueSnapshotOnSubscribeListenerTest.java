package com.ospaulos.vupth.infrastructure.websocket;

import com.ospaulos.vupth.api.establishment.dto.response.EstablishmentWithProfessionalsResponse;
import com.ospaulos.vupth.api.establishment.usecase.GetEstablishmentWithQueueUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("snapshot-ao-assinar — quem assina a fila recebe o estado atual na hora")
class QueueSnapshotOnSubscribeListenerTest {

    private static final UUID ESTABLISHMENT = UUID.fromString("21fd06da-2581-4f7a-ac2c-99b98575c235");

    @Mock GetEstablishmentWithQueueUseCase getWithQueueUseCase;
    @Mock SimpMessagingTemplate messagingTemplate;
    @Mock MessageConverter converter;
    @Mock MessageChannel clientOutboundChannel;

    private QueueSnapshotOnSubscribeListener listener;

    @BeforeEach
    void setUp() {
        listener = new QueueSnapshotOnSubscribeListener(getWithQueueUseCase, messagingTemplate, clientOutboundChannel);
    }

    private static SessionSubscribeEvent subscribe(String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionId("sess-1");
        accessor.setSubscriptionId("sub-0");
        accessor.setDestination(destination);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionSubscribeEvent(new Object(), message);
    }

    @Test
    @DisplayName("assinar /topic/establishment/{id}/queue manda o snapshot so para a sessao que assinou")
    void sendsSnapshotToSubscribingSession() {
        EstablishmentWithProfessionalsResponse snapshot = mock(EstablishmentWithProfessionalsResponse.class);
        when(getWithQueueUseCase.execute(ESTABLISHMENT)).thenReturn(snapshot);
        when(messagingTemplate.getMessageConverter()).thenReturn(converter);
        Message<?> converted = MessageBuilder.withPayload("{}").build();
        ArgumentCaptor<MessageHeaders> headers = ArgumentCaptor.forClass(MessageHeaders.class);
        when(converter.toMessage(eq(snapshot), headers.capture())).thenAnswer(inv -> converted);

        listener.onSubscribe(subscribe("/topic/establishment/" + ESTABLISHMENT + "/queue"));

        verify(clientOutboundChannel).send(converted);
        SimpMessageHeaderAccessor sent = SimpMessageHeaderAccessor.wrap(MessageBuilder.createMessage("", headers.getValue()));
        assertThat(sent.getMessageType()).isEqualTo(SimpMessageType.MESSAGE);
        assertThat(sent.getSessionId()).isEqualTo("sess-1");
        assertThat(sent.getSubscriptionId()).isEqualTo("sub-0");
        assertThat(sent.getDestination()).isEqualTo("/topic/establishment/" + ESTABLISHMENT + "/queue");
    }

    @Test
    @DisplayName("outros destinos (canal pessoal, outro topico) sao ignorados")
    void ignoresOtherDestinations() {
        listener.onSubscribe(subscribe("/user/queue/notifications"));
        listener.onSubscribe(subscribe("/topic/establishment/nao-e-uuid/queue"));
        listener.onSubscribe(subscribe("/topic/establishment/" + ESTABLISHMENT + "/queue/extra"));

        verifyNoInteractions(getWithQueueUseCase, clientOutboundChannel);
    }

    @Test
    @DisplayName("falha ao montar o snapshot so loga — a assinatura segue")
    void failureIsSwallowed() {
        when(getWithQueueUseCase.execute(ESTABLISHMENT)).thenThrow(new IllegalStateException("loja nao existe"));

        listener.onSubscribe(subscribe("/topic/establishment/" + ESTABLISHMENT + "/queue"));

        verifyNoInteractions(clientOutboundChannel);
    }

    @Test
    @DisplayName("conversor que nao converte: nada e enviado")
    void nullConversionSendsNothing() {
        when(getWithQueueUseCase.execute(ESTABLISHMENT)).thenReturn(mock(EstablishmentWithProfessionalsResponse.class));
        when(messagingTemplate.getMessageConverter()).thenReturn(converter);
        when(converter.toMessage(any(), any(MessageHeaders.class))).thenReturn(null);

        listener.onSubscribe(subscribe("/topic/establishment/" + ESTABLISHMENT + "/queue"));

        verifyNoInteractions(clientOutboundChannel);
    }
}
