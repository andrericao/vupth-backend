package com.ospaulos.vupth.api.establishment.usecase;

import com.ospaulos.vupth.api.professional.usecase.LeaveEstablishmentUseCase;
import com.ospaulos.vupth.api.user.exception.InvalidCurrentPasswordException;
import com.ospaulos.vupth.api.user.exception.UserNotFoundException;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentDeleteBlockedException;
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
        assertNothingBlocks(establishmentId, ownerId);

        // so o dono pode estar vinculado agora (DEC-173): ele sai da loja e segue como profissional sem loja
        for (Professional professional : professionalRepository.findByEstablishmentId(establishmentId)) {
            leaveEstablishmentUseCase.execute(professional.getId());
        }

        establishment.setStatus(EstablishmentStatus.REMOVED);
        establishment.setOpenStatus(OpenStatus.CLOSED);
        establishment.setName(REMOVED_NAME);
        establishmentRepository.save(establishment);
        log.info("[DeleteEstablishment] establishment={} REMOVED", establishmentId);
    }

    /**
     * DEC-173: 409 com a lista de tudo que impede, em vez de desvincular os profissionais sozinho. Profissionais alem do dono; fila
     * ativa (em curso, pausado, atrasado ou na fila de hoje); agendamento futuro (marcado para depois de hoje).
     */
    private void assertNothingBlocks(UUID establishmentId, UUID ownerId) {
        List<EstablishmentDeleteBlockedException.Blocker> blockers = new java.util.ArrayList<>();

        List<EstablishmentDeleteBlockedException.Item> others = professionalRepository.findByEstablishmentId(establishmentId).stream()
                .filter(p -> !ownerId.equals(p.getId()))
                .map(p -> new EstablishmentDeleteBlockedException.Item(p.getId(), p.getName()))
                .toList();
        if (!others.isEmpty()) {
            blockers.add(new EstablishmentDeleteBlockedException.Blocker(
                    EstablishmentDeleteBlockedException.PROFESSIONALS, others.size(), others));
        }

        java.time.OffsetDateTime endOfToday = com.ospaulos.vupth.domain.shared.BusinessClock.endOfDayExclusive(
                com.ospaulos.vupth.domain.shared.BusinessClock.today());
        List<EstablishmentDeleteBlockedException.Item> active = new java.util.ArrayList<>();
        List<EstablishmentDeleteBlockedException.Item> future = new java.util.ArrayList<>();
        for (var a : attendanceRepository.findByEstablishment_IdAndStatusIn(establishmentId, LIVE)) {
            var item = new EstablishmentDeleteBlockedException.Item(a.getId(),
                    a.getClient() != null ? a.getClient().getFirstName() : a.getGuestName());
            // INTENTION guarda o horario pretendido em desiredTime; SCHEDULED, em scheduledTime
            java.time.OffsetDateTime planned = a.getStatus() == AttendanceStatus.INTENTION ? a.getDesiredTime() : a.getScheduledTime();
            boolean later = (a.getStatus() == AttendanceStatus.SCHEDULED || a.getStatus() == AttendanceStatus.INTENTION)
                    && planned != null && !planned.isBefore(endOfToday);
            (later ? future : active).add(item);
        }
        if (!active.isEmpty()) {
            blockers.add(new EstablishmentDeleteBlockedException.Blocker(
                    EstablishmentDeleteBlockedException.ACTIVE_ATTENDANCES, active.size(), active));
        }
        if (!future.isEmpty()) {
            blockers.add(new EstablishmentDeleteBlockedException.Blocker(
                    EstablishmentDeleteBlockedException.FUTURE_BOOKINGS, future.size(), future));
        }
        if (!blockers.isEmpty()) {
            throw new EstablishmentDeleteBlockedException(establishmentId, blockers);
        }
    }
}
