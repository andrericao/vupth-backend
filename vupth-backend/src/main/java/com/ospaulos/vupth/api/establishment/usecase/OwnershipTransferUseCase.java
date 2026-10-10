package com.ospaulos.vupth.api.establishment.usecase;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotFoundException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.transfer.OwnershipTransfer;
import com.ospaulos.vupth.domain.establishment.transfer.OwnershipTransferException;
import com.ospaulos.vupth.domain.establishment.transfer.OwnershipTransferRepository;
import com.ospaulos.vupth.domain.establishment.valueObject.EstablishmentStatus;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.infrastructure.mail.EmailDeliveryException;
import com.ospaulos.vupth.infrastructure.mail.EmailSender;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Passar a posse da loja com aceite (desativar-conta; correcao do Andre 08/10: a qualquer momento, sem prazo).
 *
 * <ul>
 *   <li>So para um profissional <b>ativo da propria loja</b> (422 TRANSFER_TARGET_NOT_IN_TEAM); um pendente por
 *       loja (409 TRANSFER_ALREADY_PENDING).</li>
 *   <li>O convidado recebe {@code OWNERSHIP_TRANSFER_REQUESTED} (push) e e-mail; o dono recebe
 *       {@code OWNERSHIP_TRANSFER_ACCEPTED} ou {@code _REFUSED}.</li>
 *   <li>O aceite muda so o dono ({@link TransferEstablishmentOwnershipUseCase}); loja que estava DEACTIVATED
 *       (o dono antigo desativou a conta) volta a ACTIVE com o dono novo.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OwnershipTransferUseCase {

    public record OwnerView(
            UUID id,
            String status,
            @JsonProperty("professional_id") UUID professionalId,
            @JsonProperty("professional_name") String professionalName,
            @JsonProperty("created_at") OffsetDateTime createdAt) {}

    public record InviteView(
            UUID id,
            @JsonProperty("establishment_id") UUID establishmentId,
            @JsonProperty("establishment_name") String establishmentName,
            @JsonProperty("from_owner_name") String fromOwnerName,
            @JsonProperty("created_at") OffsetDateTime createdAt,
            String status) {}

    private final OwnershipTransferRepository transferRepository;
    private final EstablishmentRepository establishmentRepository;
    private final ProfessionalRepository professionalRepository;
    private final UserRepository userRepository;
    private final TransferEstablishmentOwnershipUseCase transferEstablishmentOwnershipUseCase;
    private final PushNotificationPublisher pushPublisher;
    private final EmailSender emailSender;

    @Transactional
    public OwnerView request(UUID ownerId, UUID establishmentId, UUID professionalId) {
        Establishment establishment = ownedStore(ownerId, establishmentId);
        Professional professional = professionalRepository.findById(professionalId)
                .filter(p -> p.getEstablishment() != null && establishmentId.equals(p.getEstablishment().getId()))
                .filter(p -> !ownerId.equals(p.getId()))
                .filter(p -> Boolean.TRUE.equals(p.getBaseUser().getIsActive()) && !p.getBaseUser().isAccountDeactivated())
                .orElseThrow(OwnershipTransferException::notInTeam);
        if (transferRepository.findFirstByEstablishmentIdAndStatus(establishmentId, OwnershipTransfer.Status.PENDING).isPresent()) {
            throw OwnershipTransferException.alreadyPending();
        }

        OwnershipTransfer saved = transferRepository.save(new OwnershipTransfer(establishmentId, ownerId, professionalId));
        String ownerName = userRepository.findById(ownerId).map(BaseUser::getFirstName).orElse("O dono");
        pushPublisher.notifyUser(professionalId, "Convite para ser o dono",
                ownerName + " quer passar a loja " + establishment.getName() + " para você.",
                Map.of("type", "OWNERSHIP_TRANSFER_REQUESTED", "transferId", String.valueOf(saved.getId()),
                        "establishmentId", String.valueOf(establishmentId)));
        try {
            emailSender.send(professional.getBaseUser().getEmail(), "Convite para ser o dono de " + establishment.getName(),
                    ownerName + " quer passar a loja " + establishment.getName() + " para você.\n\n"
                            + "Abra o app VUPTH, em Vínculo, para aceitar ou recusar.");
        } catch (EmailDeliveryException e) {
            log.error("[OwnershipTransfer] falha ao avisar por e-mail professional={}: {}", professionalId, e.getMessage());
        }
        log.info("[OwnershipTransfer] request — id={} establishment={} to={}", saved.getId(), establishmentId, professionalId);
        return ownerView(saved, professional);
    }

    @Transactional(readOnly = true)
    public OwnerView pendingForOwner(UUID ownerId, UUID establishmentId) {
        ownedStore(ownerId, establishmentId);
        OwnershipTransfer pending = transferRepository
                .findFirstByEstablishmentIdAndStatus(establishmentId, OwnershipTransfer.Status.PENDING)
                .orElseThrow(OwnershipTransferException::notFound);
        Professional professional = professionalRepository.findById(pending.getToProfessionalId()).orElse(null);
        return ownerView(pending, professional);
    }

    @Transactional(readOnly = true)
    public List<InviteView> invitesFor(UUID professionalId) {
        return transferRepository
                .findByToProfessionalIdAndStatusOrderByCreatedAtDesc(professionalId, OwnershipTransfer.Status.PENDING)
                .stream()
                .map(t -> new InviteView(t.getId(), t.getEstablishmentId(),
                        establishmentRepository.findById(t.getEstablishmentId()).map(Establishment::getName).orElse(null),
                        userRepository.findById(t.getFromOwnerId()).map(BaseUser::getFirstName).orElse(null),
                        t.getCreatedAt(), t.getStatus().name()))
                .toList();
    }

    @Transactional
    public void accept(UUID transferId, UUID professionalId) {
        OwnershipTransfer transfer = pendingTo(transferId, professionalId);
        Professional professional = professionalRepository.findById(professionalId).orElseThrow();
        if (professional.getEstablishment() == null
                || !transfer.getEstablishmentId().equals(professional.getEstablishment().getId())) {
            throw OwnershipTransferException.notInTeam(); // saiu da loja depois do convite
        }

        transferEstablishmentOwnershipUseCase.execute(
                transfer.getFromOwnerId(), transfer.getEstablishmentId(), professionalId);
        Establishment establishment = establishmentRepository.findById(transfer.getEstablishmentId()).orElseThrow();
        if (establishment.getStatus() == EstablishmentStatus.DEACTIVATED) {
            establishment.setStatus(EstablishmentStatus.ACTIVE);
            establishmentRepository.save(establishment);
        }
        transfer.close(OwnershipTransfer.Status.ACCEPTED);
        transferRepository.save(transfer);
        notifyOwner(transfer, "OWNERSHIP_TRANSFER_ACCEPTED", "Posse aceita",
                professional.getBaseUser().getFirstName() + " aceitou ser o dono de " + establishment.getName() + ".");
        log.info("[OwnershipTransfer] accepted — id={} establishment={} newOwner={}",
                transferId, transfer.getEstablishmentId(), professionalId);
    }

    @Transactional
    public void refuse(UUID transferId, UUID professionalId) {
        OwnershipTransfer transfer = pendingTo(transferId, professionalId);
        transfer.close(OwnershipTransfer.Status.REFUSED);
        transferRepository.save(transfer);
        String who = userRepository.findById(professionalId).map(BaseUser::getFirstName).orElse("O profissional");
        notifyOwner(transfer, "OWNERSHIP_TRANSFER_REFUSED", "Posse recusada", who + " recusou ser o dono da loja.");
        log.info("[OwnershipTransfer] refused — id={}", transferId);
    }

    @Transactional
    public void cancel(UUID transferId, UUID ownerId) {
        OwnershipTransfer transfer = transferRepository.findById(transferId)
                .filter(t -> t.getStatus() == OwnershipTransfer.Status.PENDING && ownerId.equals(t.getFromOwnerId()))
                .orElseThrow(OwnershipTransferException::notFound);
        transfer.close(OwnershipTransfer.Status.CANCELLED);
        transferRepository.save(transfer);
        log.info("[OwnershipTransfer] cancelled — id={}", transferId);
    }

    private Establishment ownedStore(UUID ownerId, UUID establishmentId) {
        return establishmentRepository.findByIdAndOwnerId(establishmentId, ownerId)
                .filter(e -> e.getStatus() != EstablishmentStatus.REMOVED)
                .orElseThrow(() -> new EstablishmentNotFoundException(establishmentId));
    }

    private OwnershipTransfer pendingTo(UUID transferId, UUID professionalId) {
        return transferRepository.findById(transferId)
                .filter(t -> t.getStatus() == OwnershipTransfer.Status.PENDING && professionalId.equals(t.getToProfessionalId()))
                .orElseThrow(OwnershipTransferException::notFound);
    }

    private void notifyOwner(OwnershipTransfer transfer, String type, String title, String body) {
        pushPublisher.notifyUser(transfer.getFromOwnerId(), title, body,
                Map.of("type", type, "transferId", String.valueOf(transfer.getId()),
                        "establishmentId", String.valueOf(transfer.getEstablishmentId())));
    }

    private static OwnerView ownerView(OwnershipTransfer t, Professional professional) {
        return new OwnerView(t.getId(), t.getStatus().name(), t.getToProfessionalId(),
                professional != null ? professional.getBaseUser().getFirstName() : null, t.getCreatedAt());
    }
}
