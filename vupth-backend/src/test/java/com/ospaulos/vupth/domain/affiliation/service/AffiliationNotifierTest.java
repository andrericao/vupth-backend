package com.ospaulos.vupth.domain.affiliation.service;

import com.ospaulos.vupth.domain.affiliation.model.AffiliationRequest;
import com.ospaulos.vupth.domain.affiliation.model.AffiliationStatus;
import com.ospaulos.vupth.domain.affiliation.model.AffiliationType;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.owner.model.Owner;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.infrastructure.mail.EmailDeliveryException;
import com.ospaulos.vupth.infrastructure.mail.EmailSender;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** DEC-19 · historico-de-vinculo: quem recebe cada aviso, por push e por e-mail. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AffiliationNotifierTest {

    @Mock PushNotificationPublisher pushPublisher;
    @Mock EmailSender emailSender;
    @InjectMocks AffiliationNotifier notifier;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID professionalId = UUID.randomUUID();
    private AffiliationRequest ar;

    @BeforeEach
    void setUp() {
        BaseUser ownerUser = mock(BaseUser.class);
        when(ownerUser.getId()).thenReturn(ownerId);
        when(ownerUser.getEmail()).thenReturn("dono@vupth.app");
        Owner owner = mock(Owner.class);
        when(owner.getBaseUser()).thenReturn(ownerUser);

        BaseUser profUser = mock(BaseUser.class);
        when(profUser.getId()).thenReturn(professionalId);
        when(profUser.getEmail()).thenReturn("pro@vupth.app");
        when(profUser.getFirstName()).thenReturn("Dedeco");
        Professional professional = mock(Professional.class);
        when(professional.getBaseUser()).thenReturn(profUser);

        Establishment establishment = mock(Establishment.class);
        when(establishment.getId()).thenReturn(UUID.randomUUID());
        when(establishment.getName()).thenReturn("Barbearia X");
        when(establishment.getOwner()).thenReturn(owner);

        ar = mock(AffiliationRequest.class);
        when(ar.getId()).thenReturn(UUID.randomUUID());
        when(ar.getProfessional()).thenReturn(professional);
        when(ar.getEstablishment()).thenReturn(establishment);
    }

    private static java.util.Map<String, Object> ofType(String type) {
        return argThat(m -> type.equals(m.get("type")));
    }

    @Test
    @DisplayName("pedido do profissional -> o DONO recebe AFFILIATION_REQUESTED por push e e-mail")
    void request_notifiesOwner() {
        when(ar.getType()).thenReturn(AffiliationType.REQUEST);
        notifier.created(ar);
        verify(pushPublisher).notifyUser(eq(ownerId), anyString(), anyString(), ofType(AffiliationNotifier.REQUESTED));
        verify(emailSender).send(eq("dono@vupth.app"), anyString(), anyString());
    }

    @Test
    @DisplayName("convite do dono -> o PROFISSIONAL recebe AFFILIATION_INVITED")
    void invitation_notifiesProfessional() {
        when(ar.getType()).thenReturn(AffiliationType.INVITATION);
        notifier.created(ar);
        verify(pushPublisher).notifyUser(eq(professionalId), anyString(), anyString(), ofType(AffiliationNotifier.INVITED));
        verify(emailSender).send(eq("pro@vupth.app"), anyString(), anyString());
    }

    @Test
    @DisplayName("pedido aceito -> o PROFISSIONAL recebe AFFILIATION_ACCEPTED")
    void requestAccepted_notifiesProfessional() {
        when(ar.getType()).thenReturn(AffiliationType.REQUEST);
        when(ar.getStatus()).thenReturn(AffiliationStatus.ACCEPTED);
        notifier.resolved(ar);
        verify(pushPublisher).notifyUser(eq(professionalId), anyString(), anyString(), ofType(AffiliationNotifier.ACCEPTED));
        verify(emailSender).send(eq("pro@vupth.app"), anyString(), anyString());
    }

    @Test
    @DisplayName("pedido recusado -> o PROFISSIONAL recebe AFFILIATION_REJECTED")
    void requestRejected_notifiesProfessional() {
        when(ar.getType()).thenReturn(AffiliationType.REQUEST);
        when(ar.getStatus()).thenReturn(AffiliationStatus.REJECTED);
        notifier.resolved(ar);
        verify(pushPublisher).notifyUser(eq(professionalId), anyString(), anyString(), ofType(AffiliationNotifier.REJECTED));
    }

    @Test
    @DisplayName("convite recusado -> o DONO recebe AFFILIATION_REJECTED")
    void invitationRejected_notifiesOwner() {
        when(ar.getType()).thenReturn(AffiliationType.INVITATION);
        when(ar.getStatus()).thenReturn(AffiliationStatus.REJECTED);
        notifier.resolved(ar);
        verify(pushPublisher).notifyUser(eq(ownerId), anyString(), anyString(), ofType(AffiliationNotifier.REJECTED));
    }

    @Test
    @DisplayName("e-mail falhando nao derruba o vinculo (best-effort)")
    void emailFailure_isSwallowed() {
        when(ar.getType()).thenReturn(AffiliationType.REQUEST);
        doThrow(new EmailDeliveryException("brevo fora", null)).when(emailSender).send(anyString(), anyString(), anyString());
        assertThatCode(() -> notifier.created(ar)).doesNotThrowAnyException();
        verify(pushPublisher).notifyUser(eq(ownerId), anyString(), anyString(), anyMap());
    }
}
