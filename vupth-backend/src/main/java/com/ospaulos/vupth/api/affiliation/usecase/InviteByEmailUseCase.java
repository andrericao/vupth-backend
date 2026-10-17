package com.ospaulos.vupth.api.affiliation.usecase;

import com.ospaulos.vupth.infrastructure.logging.LogMask;
import com.ospaulos.vupth.api.affiliation.dto.response.AffiliationRequestResponse;
import com.ospaulos.vupth.api.affiliation.dto.response.EmailInvitationResponse;
import com.ospaulos.vupth.api.affiliation.dto.response.InviteByEmailResponse;
import com.ospaulos.vupth.domain.affiliation.exception.AffiliationNotFoundException;
import com.ospaulos.vupth.domain.affiliation.model.EmailInvitation;
import com.ospaulos.vupth.domain.affiliation.model.EmailInvitationStatus;
import com.ospaulos.vupth.domain.affiliation.repository.EmailInvitationRepository;
import com.ospaulos.vupth.domain.affiliation.service.AffiliationNotifier;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotFoundException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Convite pelo e-mail (convite-sem-conta, Partner S30): o dono convida quem ainda nao tem conta Vupth
 * — o funcionario que ele quer na loja. Se o e-mail ja e de um profissional, vira o convite comum
 * na hora; senao, fica guardado e um e-mail diz a pessoa como entrar. Quando ela cria o perfil
 * profissional com esse e-mail, o {@code EmailInvitationConverter} transforma em convite comum.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InviteByEmailUseCase {

    private final EstablishmentRepository establishmentRepository;
    private final UserRepository userRepository;
    private final EmailInvitationRepository emailInvitationRepository;
    private final InviteProfessionalUseCase inviteProfessionalUseCase;
    private final AffiliationNotifier notifier;

    @Transactional
    public InviteByEmailResponse invite(UUID ownerId, UUID establishmentId, String rawEmail) {
        String email = EmailInvitation.normalize(rawEmail);
        log.info("[EmailInvitation] invite — owner={} establishment={} email={}", ownerId, establishmentId, LogMask.email(email));

        Establishment establishment = ownedEstablishment(ownerId, establishmentId);

        // Ja e profissional: o convite comum resolve, com as mesmas regras (ja afiliado, ja pendente).
        BaseUser existing = userRepository.findByEmail(email).orElse(null);
        if (existing != null && existing.getProfessional() != null) {
            AffiliationRequestResponse invitation =
                    inviteProfessionalUseCase.execute(ownerId, establishmentId, existing.getId());
            return new InviteByEmailResponse(InviteByEmailResponse.INVITATION_SENT, invitation, null);
        }

        // Idempotente: convidar de novo o mesmo e-mail para a mesma loja devolve o convite que espera.
        EmailInvitation invitation = emailInvitationRepository
                .findByEstablishmentAndStatus(establishmentId, EmailInvitationStatus.PENDING).stream()
                .filter(i -> i.getEmail().equals(email))
                .findFirst()
                .orElse(null);
        if (invitation == null) {
            invitation = emailInvitationRepository.save(new EmailInvitation(establishment, email));
            notifier.invitedByEmail(email, establishment.getName());
        }
        return new InviteByEmailResponse(InviteByEmailResponse.WAITING_ACCOUNT, null,
                EmailInvitationResponse.fromEntity(invitation));
    }

    @Transactional
    public List<EmailInvitationResponse> listPending(UUID ownerId, UUID establishmentId) {
        ownedEstablishment(ownerId, establishmentId);
        return emailInvitationRepository
                .findByEstablishmentAndStatus(establishmentId, EmailInvitationStatus.PENDING).stream()
                .map(EmailInvitationResponse::fromEntity)
                .toList();
    }

    @Transactional
    public EmailInvitationResponse cancel(UUID ownerId, UUID invitationId) {
        EmailInvitation invitation = emailInvitationRepository.findById(invitationId)
                .orElseThrow(() -> AffiliationNotFoundException.emailInvitation(invitationId));
        ownedEstablishment(ownerId, invitation.getEstablishment().getId());
        if (invitation.getStatus() != EmailInvitationStatus.PENDING) {
            throw new IllegalArgumentException("Email invitation is already " + invitation.getStatus().name().toLowerCase() + ".");
        }
        invitation.setStatus(EmailInvitationStatus.CANCELLED);
        log.info("[EmailInvitation] cancelled — id={}", invitationId);
        return EmailInvitationResponse.fromEntity(emailInvitationRepository.save(invitation));
    }

    private Establishment ownedEstablishment(UUID ownerId, UUID establishmentId) {
        Establishment establishment = establishmentRepository.findById(establishmentId)
                .orElseThrow(() -> new EstablishmentNotFoundException(establishmentId));
        if (establishment.getOwner() == null || !establishment.getOwner().getId().equals(ownerId)) {
            throw new EstablishmentAccessDeniedException("You can only invite professionals to your own establishments.");
        }
        return establishment;
    }
}
