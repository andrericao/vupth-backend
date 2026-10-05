package com.ospaulos.vupth.domain.affiliation.service;

import com.ospaulos.vupth.domain.affiliation.model.AffiliationRequest;
import com.ospaulos.vupth.domain.affiliation.model.AffiliationStatus;
import com.ospaulos.vupth.domain.affiliation.model.AffiliationType;
import com.ospaulos.vupth.domain.affiliation.model.EmailInvitation;
import com.ospaulos.vupth.domain.affiliation.model.EmailInvitationStatus;
import com.ospaulos.vupth.domain.affiliation.repository.AffiliationRequestRepository;
import com.ospaulos.vupth.domain.affiliation.repository.EmailInvitationRepository;
import com.ospaulos.vupth.domain.professional.model.Professional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Fecha o convite-sem-conta: quando alguem cria o perfil profissional, os convites por e-mail que
 * esperavam esse e-mail viram convite comum ({@code AffiliationRequest INVITATION}).
 *
 * <p>Pendencia unica global (#8b): um profissional so tem um vinculo PENDING por vez. Entao so o
 * convite <b>mais novo</b> vira convite comum; os mais antigos sao cancelados (o dono deles pode
 * convidar de novo pelo fluxo normal, ja que a pessoa agora tem conta).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailInvitationConverter {

    private final EmailInvitationRepository emailInvitationRepository;
    private final AffiliationRequestRepository affiliationRepository;
    private final AffiliationNotifier notifier;

    /** Chamado na mesma transacao que cria o perfil profissional (sem loja). */
    public void convertFor(Professional professional) {
        if (professional.getBaseUser() == null || professional.getEstablishment() != null) {
            return;
        }
        String email = EmailInvitation.normalize(professional.getBaseUser().getEmail());
        List<EmailInvitation> waiting =
                emailInvitationRepository.findByEmailAndStatus(email, EmailInvitationStatus.PENDING);
        if (waiting.isEmpty()) {
            return;
        }
        if (affiliationRepository.existsByProfessional_IdAndTypeAndStatus(
                        professional.getId(), AffiliationType.INVITATION, AffiliationStatus.PENDING)
                || affiliationRepository.existsByProfessional_IdAndTypeAndStatus(
                        professional.getId(), AffiliationType.REQUEST, AffiliationStatus.PENDING)) {
            return; // perfil recem-criado nao tem vinculo, mas a regra #8b vale acima de tudo
        }

        EmailInvitation newest = waiting.get(0);
        AffiliationRequest invitation = affiliationRepository.save(
                new AffiliationRequest(professional, newest.getEstablishment(), AffiliationType.INVITATION));
        newest.convertTo(invitation);
        emailInvitationRepository.save(newest);
        notifier.created(invitation);

        for (EmailInvitation older : waiting.subList(1, waiting.size())) {
            older.setStatus(EmailInvitationStatus.CANCELLED);
            emailInvitationRepository.save(older);
        }
        log.info("[EmailInvitation] converted — professional={} establishment={} affiliation={} (outros {} cancelados)",
                professional.getId(), newest.getEstablishment().getId(), invitation.getId(), waiting.size() - 1);
    }
}
