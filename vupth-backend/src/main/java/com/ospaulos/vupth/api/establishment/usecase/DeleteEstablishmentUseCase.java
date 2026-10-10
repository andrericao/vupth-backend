package com.ospaulos.vupth.api.establishment.usecase;

import com.ospaulos.vupth.api.professional.usecase.LeaveEstablishmentUseCase;
import com.ospaulos.vupth.api.user.exception.InvalidCurrentPasswordException;
import com.ospaulos.vupth.api.user.exception.UserNotFoundException;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentHasActiveAttendancesException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotFoundException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.valueObject.EstablishmentStatus;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Apagar a loja (desativar-conta, regras (c) e (6)): {@code DELETE /establishment/{id}}, so o dono, com a senha.
 *
 * <p>A linha NAO some: os atendimentos dela sao historico do cliente e do profissional. Ela vira
 * {@code REMOVED}, com o nome "Loja removida" (o historico mostra isso), sai da vitrine, e cada
 * profissional sai da loja e recebe {@code ESTABLISHMENT_REMOVED}. Com fila viva: 409
 * {@code ESTABLISHMENT_HAS_ACTIVE_ATTENDANCES}, e nada muda.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeleteEstablishmentUseCase {

    public static final String REMOVED_NAME = "Loja removida";
    static final String REMOVED_TYPE = "ESTABLISHMENT_REMOVED";

    private static final List<AttendanceStatus> LIVE = List.of(
            AttendanceStatus.SCHEDULED, AttendanceStatus.IN_PROGRESS, AttendanceStatus.PAUSED,
            AttendanceStatus.LATE, AttendanceStatus.INTENTION);

    private final EstablishmentRepository establishmentRepository;
    private final ProfessionalRepository professionalRepository;
    private final AttendanceRepository attendanceRepository;
    private final UserRepository userRepository;
    private final LeaveEstablishmentUseCase leaveEstablishmentUseCase;
    private final PasswordEncoder passwordEncoder;
    private final PushNotificationPublisher pushPublisher;

    @Transactional
    public void execute(UUID ownerId, UUID establishmentId, String currentPassword) {
        log.info("[DeleteEstablishment] execute — owner={} establishment={}", ownerId, establishmentId);
        Establishment establishment = establishmentRepository.findById(establishmentId)
                .filter(e -> e.getStatus() != EstablishmentStatus.REMOVED)
                .orElseThrow(() -> new EstablishmentNotFoundException(establishmentId));
        if (establishment.getOwner() == null || !ownerId.equals(establishment.getOwner().getId())) {
            throw new EstablishmentAccessDeniedException("Only the owner can delete the establishment.");
        }
        BaseUser owner = userRepository.findById(ownerId).orElseThrow(() -> new UserNotFoundException(ownerId));
        if (!passwordEncoder.matches(currentPassword, owner.getPassword())) {
            throw new InvalidCurrentPasswordException();
        }
        if (attendanceRepository.existsByEstablishment_IdAndStatusIn(establishmentId, LIVE)) {
            throw new EstablishmentHasActiveAttendancesException(establishmentId);
        }

        String formerName = establishment.getName();
        for (Professional professional : professionalRepository.findByEstablishmentId(establishmentId)) {
            leaveEstablishmentUseCase.execute(professional.getId());
            if (!ownerId.equals(professional.getId())) {
                pushPublisher.notifyUser(professional.getId(), "Loja removida",
                        "A loja " + formerName + " foi removida pelo dono. Você saiu dela e pode pedir vínculo em outra.",
                        Map.of("type", REMOVED_TYPE, "establishmentId", String.valueOf(establishmentId)));
            }
        }

        establishment.setStatus(EstablishmentStatus.REMOVED);
        establishment.setOpenStatus(OpenStatus.CLOSED);
        establishment.setName(REMOVED_NAME);
        establishmentRepository.save(establishment);
        log.info("[DeleteEstablishment] establishment={} REMOVED", establishmentId);
    }
}
