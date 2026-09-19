package com.ospaulos.vupth.api.professional.usecase;

import com.ospaulos.vupth.api.professional.dto.request.UpdateProfessionalStatusRequest;
import com.ospaulos.vupth.api.professional.dto.response.ProfessionalQuickCreateResponse;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.service.EstablishmentOpenStatusService;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalHasActiveAttendancesException;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalHasNoServiceException;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotFoundException;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.professional.valueObject.ProfessionalStatus;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * S1 (fatia 1) — o profissional muda o proprio status de trabalho
 * ({@code WORKING}/{@code ON_BREAK}/{@code OFF}).
 *
 * <p>Gates:</p>
 * <ul>
 *   <li><b>→ WORKING/ON_BREAK</b> (contam como aberto): exige ≥1 servico cadastrado, senao
 *       {@code 422 PROFESSIONAL_HAS_NO_SERVICE} (mesmo gate #31).</li>
 *   <li><b>→ OFF</b>: bloqueado se o profissional tem atendimento ativo ou fila propria
 *       (SCHEDULED/LATE/IN_PROGRESS/PAUSED), senao {@code 409 PROFESSIONAL_HAS_ACTIVE_ATTENDANCES}.
 *       O caminho para sair com fila e "fechar fila" (drenar), que chega na fatia 3.</li>
 * </ul>
 *
 * <p>Fatia 2: apos persistir, o {@code open_status} do estabelecimento e recalculado na MESMA
 * transacao ({@code aberto ⇔ ∃ prof em {WORKING, ON_BREAK}} — ver
 * {@link com.ospaulos.vupth.domain.establishment.service.EstablishmentOpenStatusService#deriveFromProfessionals}).
 * HTTP: {@code PATCH /professional/me/status}, Auth: ROLE_PROFESSIONAL.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UpdateProfessionalStatusUseCase {

    /** Atendimentos que impedem o profissional de ficar indisponivel (ativos + fila propria). */
    private static final List<AttendanceStatus> ACTIVE_STATUSES = List.of(
            AttendanceStatus.SCHEDULED, AttendanceStatus.LATE,
            AttendanceStatus.IN_PROGRESS, AttendanceStatus.PAUSED);

    private final ProfessionalRepository professionalRepository;
    private final AttendanceRepository attendanceRepository;
    private final EstablishmentOpenStatusService openStatusService;

    @Transactional
    public ProfessionalQuickCreateResponse execute(UUID userId, UpdateProfessionalStatusRequest request) {
        ProfessionalStatus target = request.status();
        log.info("[ProfessionalStatus] execute — user={} target={}", userId, target);

        Professional professional = professionalRepository
                .findByIdWithBaseUserAndEstablishment(userId)
                .orElseThrow(() -> new ProfessionalNotFoundException(userId));

        if (target.countsAsOpen() && !hasService(professional)) {
            throw new ProfessionalHasNoServiceException(userId);
        }

        if (target == ProfessionalStatus.OFF
                && attendanceRepository.existsByProfessional_IdAndStatusIn(userId, ACTIVE_STATUSES)) {
            throw new ProfessionalHasActiveAttendancesException(userId);
        }

        // Decisao do Andre (09/09): ir para Fora FECHA a fila, e voltar NAO a reabre — quem fechou
        // decide quando abrir de novo. Antes, voltar de OFF resetava o accepting_new (S1 fatia 3).
        if (target == ProfessionalStatus.OFF) {
            professional.setAcceptingNew(false);
        }

        professional.setWorkStatus(target);
        professionalRepository.save(professional);

        // S1 fatia 2 — recalculo atomico do open_status derivado (mesma transacao).
        if (professional.getEstablishment() != null) {
            openStatusService.deriveFromProfessionals(professional.getEstablishment().getId());
        }

        log.info("[ProfessionalStatus] updated — id={} status={}", professional.getId(), target);

        return new ProfessionalQuickCreateResponse(
                professional.getId(),
                professional.getBaseUser().getFirstName(),
                professional.getSpecialty(),
                professional.getBaseUser().getNickname(),
                professional.getEstablishment() != null ? professional.getEstablishment().getId() : null,
                professional.getEstablishment() != null ? professional.getEstablishment().getName() : null,
                professional.getIsAvailable(),
                professional.getWorkStatus(),
                professional.getAcceptingNew(),
                null
        );
    }

    private boolean hasService(Professional professional) {
        return professional.getServices() != null && !professional.getServices().isEmpty();
    }
}
