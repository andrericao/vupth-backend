package com.ospaulos.vupth.domain.attendance.service.impl;

import com.ospaulos.vupth.api.attendance.dto.request.AttendanceGuestRequest;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInProgressResponse;
import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInQueueResponse;
import com.ospaulos.vupth.api.attendance.dto.response.AttendanceListResponse;
import com.ospaulos.vupth.api.attendance.dto.response.AttendancePausedResponse;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceAlreadyCancelledException;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceCancelNotAllowedException;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceInvalidStatusException;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceNotFoundException;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException;
import com.ospaulos.vupth.domain.attendance.service.AttendanceService;
import com.ospaulos.vupth.domain.client.exception.ClientNotFoundException;
import com.ospaulos.vupth.domain.client.model.Client;
import com.ospaulos.vupth.domain.client.repository.ClientRepository;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotFoundException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotAffiliatedException;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotFoundException;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.queue.service.QueueUtils;
import com.ospaulos.vupth.domain.serviceItem.exception.ServiceItemNotFoundException;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import com.ospaulos.vupth.domain.serviceItem.repository.ServiceItemRepository;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@AllArgsConstructor
@org.springframework.transaction.annotation.Transactional
public class AttendanceServiceImpl implements AttendanceService {

    private final AttendanceRepository attendanceRepository;
    private final ServiceItemRepository serviceItemRepository;
    private final ProfessionalRepository professionalRepository;
    private final ClientRepository clientRepository;
    private final EstablishmentRepository establishmentRepository;

    // ─────────────────────────────────────────────────────────────
    // CREATE
    // ─────────────────────────────────────────────────────────────

    @Override
    public com.ospaulos.vupth.domain.attendance.model.Attendance createAttendance(
            AttendanceRequest request,
            Professional professional,
            Establishment establishment,
            Client client) {

        log.info("[Attendance] createAttendance — professional={} establishment={} client={}",
                professional.getId(), establishment.getId(), client.getId());

        if (professional.getEstablishment() == null) {
            throw new ProfessionalNotAffiliatedException(professional.getId());
        }

        List<ServiceItem> serviceItems = serviceItemRepository.findAllById(request.serviceItemIds());
        if (serviceItems.isEmpty()) {
            log.warn("[Attendance] createAttendance — no serviceItems found for ids={}", request.serviceItemIds());
            throw new ServiceItemNotFoundException();
        }

        com.ospaulos.vupth.domain.attendance.model.Attendance attendance =
                new com.ospaulos.vupth.domain.attendance.model.Attendance();
        attendance.setProfessional(professional);
        attendance.setEstablishment(establishment);
        attendance.setClient(client);
        attendance.setServiceItems(serviceItems);
        attendance.setStatus(AttendanceStatus.SCHEDULED);
        attendance.setScheduledTime(request.scheduledTime());

        com.ospaulos.vupth.domain.attendance.model.Attendance saved = attendanceRepository.save(attendance);
        initializeLazyAssociations(saved);
        lockMealsAtIngress(saved, professional, establishment);
        log.info("[Attendance] createAttendance — created id={} totalValue={}", saved.getId(), saved.getTotalValue());
        return saved;
    }

    /**
     * Trava (lock) no ingresso as refeicoes que ja estavam no ETA do cliente (item #37): grava no
     * atendimento os ids das refeicoes recorrentes contadas ate a posicao dele na fila. Depois disso,
     * essas refeicoes nao saem mais do ETA dele por ritmo da fila (so por skip #41 / inicio / remocao).
     * Best-effort: falha aqui nao quebra a criacao do atendimento.
     */
    private void lockMealsAtIngress(com.ospaulos.vupth.domain.attendance.model.Attendance saved,
                                    Professional professional, Establishment establishment) {
        try {
            List<Attendance> queue = attendanceRepository
                    .findWaitingQueueByProfessionalAndEstablishment(professional.getId(), establishment.getId());
            int pos = -1;
            for (int i = 0; i < queue.size(); i++) {
                if (queue.get(i).getId().equals(saved.getId())) { pos = i; break; }
            }
            if (pos <= 0) {
                return; // primeiro da fila (ou fora dela): nenhuma refeicao a frente para travar
            }
            Set<UUID> locked = QueueUtils.mealsCountedForPosition(
                    queue, professional.getIntervals(), OffsetDateTime.now(), pos);
            if (!locked.isEmpty()) {
                saved.setLockedIntervalIds(locked);
                attendanceRepository.save(saved);
                log.info("[Attendance] lock refeicoes no ingresso — attendance={} meals={}", saved.getId(), locked);
            }
        } catch (Exception e) {
            log.warn("[Attendance] lockMealsAtIngress falhou para {}: {}", saved.getId(), e.getMessage());
        }
    }

    @Override
    public AttendanceInQueueResponse createAttendance(AttendanceRequest request) {
        log.info("[Attendance] createAttendance(request) — professionalId={} clientId={}",
                request.professionalId(), request.clientId());

        Professional professional = professionalRepository.findById(request.professionalId())
                .orElseThrow(() -> new ProfessionalNotFoundException(request.professionalId()));

        if (professional.getEstablishment() == null) {
            throw new ProfessionalNotAffiliatedException(request.professionalId());
        }

        Establishment establishment = professional.getEstablishment();

        // Bug fix: was == (reference comparison) — now uses .equals()
        if (!establishment.getId().equals(request.establishmentId())) {
            log.warn("[Attendance] createAttendance — establishmentId={} does not belong to professional={}",
                    request.establishmentId(), request.professionalId());
            throw new EstablishmentNotFoundException(request.establishmentId());
        }

        Client client = clientRepository.findById(request.clientId())
                .orElseThrow(() -> new ClientNotFoundException(request.clientId()));

        List<ServiceItem> serviceItems = serviceItemRepository.findAllById(request.serviceItemIds());
        if (serviceItems.isEmpty()) {
            log.warn("[Attendance] createAttendance — no serviceItem ids={}", request.serviceItemIds());
            throw new ServiceItemNotFoundException();
        }

        com.ospaulos.vupth.domain.attendance.model.Attendance attendance =
                new com.ospaulos.vupth.domain.attendance.model.Attendance();
        attendance.setProfessional(professional);
        attendance.setEstablishment(establishment);
        attendance.setClient(client);
        attendance.setServiceItems(serviceItems);
        attendance.setStatus(AttendanceStatus.SCHEDULED);
        attendance.setScheduledTime(request.scheduledTime());

        com.ospaulos.vupth.domain.attendance.model.Attendance saved = attendanceRepository.save(attendance);
        lockMealsAtIngress(saved, professional, establishment);
        log.info("[Attendance] createAttendance — created id={} totalValue={}", saved.getId(), saved.getTotalValue());
        return AttendanceInQueueResponse.fromEntity(saved);
    }

    @Override
    public com.ospaulos.vupth.domain.attendance.model.Attendance createGuestAttendance(
            AttendanceGuestRequest request,
            Professional professional,
            Establishment establishment) {

        log.info("[Attendance] createGuestAttendance — professional={} establishment={} guestName={}",
                professional.getId(), establishment.getId(), request.clientName());

        if (professional.getEstablishment() == null) {
            throw new ProfessionalNotAffiliatedException(professional.getId());
        }

        com.ospaulos.vupth.domain.attendance.model.Attendance attendance =
                new com.ospaulos.vupth.domain.attendance.model.Attendance();
        attendance.setProfessional(professional);
        attendance.setEstablishment(establishment);
        attendance.setClient(null); // guest — no authentication
        attendance.setGuestName(request.clientName());
        attendance.setStatus(AttendanceStatus.SCHEDULED);
        attendance.setScheduledTime(OffsetDateTime.now());

        // serviceItemIds is optional in the MVP
        if (request.serviceItemIds() != null && !request.serviceItemIds().isEmpty()) {
            List<ServiceItem> serviceItems = serviceItemRepository.findAllById(request.serviceItemIds());
            attendance.setServiceItems(serviceItems);
            log.debug("[Attendance] createGuestAttendance — {} service items linked", serviceItems.size());
        } else {
            attendance.setServiceItems(List.of());
            log.debug("[Attendance] createGuestAttendance — no service items (anonymous guest)");
        }

        com.ospaulos.vupth.domain.attendance.model.Attendance saved = attendanceRepository.save(attendance);
        initializeLazyAssociations(saved);
        lockMealsAtIngress(saved, professional, establishment);
        log.info("[Attendance] createGuestAttendance — created id={}", saved.getId());
        return saved;
    }

    // ─────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────

    @Override
    public void startAttendance(com.ospaulos.vupth.domain.attendance.model.Attendance attendance, Professional professional) {
        log.info("[Attendance] startAttendance — attendanceId={} professionalId={}",
                attendance.getId(), professional.getId());

        // Aceita SCHEDULED ou LATE (item #19): um cliente atrasado pode ser atendido direto
        // quando finalmente chega, sem precisar desfazer o LATE antes.
        if (attendance.getStatus() != AttendanceStatus.SCHEDULED
                && attendance.getStatus() != AttendanceStatus.LATE) {
            log.warn("[Attendance] startAttendance — invalid status: current={} required=SCHEDULED|LATE",
                    attendance.getStatus());
            throw new AttendanceInvalidStatusException(attendance.getStatus(), AttendanceStatus.SCHEDULED);
        }

        attendance.setStartTime(OffsetDateTime.now());
        attendance.setIsTimerActive(true);
        attendance.setStatus(AttendanceStatus.IN_PROGRESS);
        attendanceRepository.save(attendance);

        String clientName = attendance.getClient() != null
                ? attendance.getClient().getFirstName()
                : (attendance.getGuestName() != null ? attendance.getGuestName() : "anonymous");
        log.info("[Attendance] startAttendance — cliente='{}' started by professional='{}'",
                clientName, professional.getName());
    }

    @Override
    public void finishAttendance(com.ospaulos.vupth.domain.attendance.model.Attendance attendance) {
        log.info("[Attendance] finishAttendance — attendanceId={}", attendance.getId());

        if (!attendance.getStatus().equals(AttendanceStatus.IN_PROGRESS)) {
            log.warn("[Attendance] finishAttendance — invalid status: current={} required=IN_PROGRESS",
                    attendance.getStatus());
            throw new AttendanceInvalidStatusException(attendance.getStatus(), AttendanceStatus.IN_PROGRESS);
        }

        if (attendance.getStartTime() == null) {
            throw new AttendanceInvalidStatusException(
                    "Attendance cannot be finished without a recorded start time");
        }

        if (attendance.getStatus().equals(AttendanceStatus.PAUSED)) {
            attendance.resumeTimer();
        }

        attendance.setEndTime(OffsetDateTime.now());
        attendance.setIsTimerActive(false);
        attendance.setStatus(AttendanceStatus.FINISHED);
        attendance.setDurationInMinutes(attendance.getServiceDurationInMinutes());

        com.ospaulos.vupth.domain.attendance.model.Attendance saved = attendanceRepository.save(attendance);

        String clientName = attendance.getClient() != null
                ? attendance.getClient().getFirstName()
                : (attendance.getGuestName() != null ? attendance.getGuestName() : "anonymous");
        log.info("[Attendance] finishAttendance — cliente='{}' finished by professional='{}'. Duration={}min (paused={}min)",
                clientName,
                attendance.getProfessional().getName(),
                saved.getDurationInMinutes(),
                saved.getPausedDurationInMinutes());
    }

    @Override
    public AttendanceInQueueResponse getById(UUID attendanceId) {
        log.info("[Attendance] getById — attendanceId={}", attendanceId);
        Attendance attendance = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(attendanceId));
        return buildAttendanceInQueueResponse(attendance);
    }

    @Override
    public AttendanceInQueueResponse cancelAttendance(UUID attendanceId, UUID clientId) {
        log.info("[Attendance] cancelAttendance — attendanceId={} clientId={}", attendanceId, clientId);

        com.ospaulos.vupth.domain.attendance.model.Attendance attendance = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(attendanceId));

        // Guard: already cancelled — inform the caller instead of silently overwriting
        if (attendance.getStatus() == AttendanceStatus.CANCELLED) {
            log.warn("[Attendance] cancelAttendance — attendance={} is already CANCELLED", attendanceId);
            throw new AttendanceAlreadyCancelledException(attendanceId);
        }

        // Guard: client can only cancel SCHEDULED attendances.
        // IN_PROGRESS, PAUSED and FINISHED cannot be cancelled by the client.
        if (attendance.getStatus() != AttendanceStatus.SCHEDULED) {
            log.warn("[Attendance] cancelAttendance — client={} attempted to cancel attendance={} with status={}",
                    clientId, attendanceId, attendance.getStatus());
            throw new AttendanceCancelNotAllowedException(attendanceId);
        }

        attendance.setStatus(AttendanceStatus.CANCELLED);
        com.ospaulos.vupth.domain.attendance.model.Attendance saved = attendanceRepository.save(attendance);

        String clientName = attendance.getClient() != null
                ? attendance.getClient().getFirstName()
                : (attendance.getGuestName() != null ? attendance.getGuestName() : "anonymous");
        log.info("[Attendance] cancelAttendance — cliente='{}' canceled attendance with professional='{}'",
                clientName, attendance.getProfessional().getName());

        return AttendanceInQueueResponse.fromEntity(saved);
    }

    @Override
    public AttendanceInQueueResponse cancelAttendance(com.ospaulos.vupth.domain.attendance.model.Attendance attendance) {
        log.info("[Attendance] cancelAttendance(entity) — attendanceId={}", attendance.getId());

        attendance.setStatus(AttendanceStatus.CANCELLED);
        com.ospaulos.vupth.domain.attendance.model.Attendance saved = attendanceRepository.save(attendance);

        String clientName = attendance.getClient() != null
                ? attendance.getClient().getFirstName()
                : (attendance.getGuestName() != null ? attendance.getGuestName() : "anonymous");
        log.info("[Attendance] cancelAttendance — cliente='{}' attendance canceled", clientName);

        return buildAttendanceInQueueResponse(saved);
    }

    @Override
    public AttendanceInQueueResponse leaveQueue(UUID attendanceId, UUID clientId) {
        log.info("[Attendance] leaveQueue — attendanceId={} clientId={}", attendanceId, clientId);

        Attendance attendance = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(attendanceId));

        if (attendance.getClient() == null || !attendance.getClient().getId().equals(clientId)) {
            throw new EstablishmentAccessDeniedException(
                    "You can only leave your own queue position.");
        }

        if (!attendance.getStatus().equals(AttendanceStatus.SCHEDULED)) {
            throw new AttendanceInvalidStatusException(attendance.getStatus(), AttendanceStatus.SCHEDULED);
        }

        attendance.setStatus(AttendanceStatus.CANCELLED);
        Attendance saved = attendanceRepository.save(attendance);

        log.info("[Attendance] leaveQueue — client='{}' left the queue voluntarily",
                attendance.getClient().getFirstName());

        return buildAttendanceInQueueResponse(saved);
    }

    @Override
    public AttendanceInQueueResponse removeFromQueue(UUID attendanceId, UUID professionalId) {
        log.info("[Attendance] removeFromQueue — attendanceId={} professionalId={}", attendanceId, professionalId);

        Attendance attendance = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(attendanceId));

        if (!attendance.getProfessional().getId().equals(professionalId)) {
            throw new EstablishmentAccessDeniedException(
                    "You can only remove attendances from your own queue.");
        }

        var status = attendance.getStatus();
        if (status != AttendanceStatus.SCHEDULED
                && status != AttendanceStatus.IN_PROGRESS
                && status != AttendanceStatus.PAUSED) {
            throw new AttendanceInvalidStatusException(status, AttendanceStatus.SCHEDULED);
        }

        attendance.setStatus(AttendanceStatus.CANCELLED);
        Attendance saved = attendanceRepository.save(attendance);

        String clientName = attendance.getClient() != null
                ? attendance.getClient().getFirstName()
                : (attendance.getGuestName() != null ? attendance.getGuestName() : "anonymous");
        log.info("[Attendance] removeFromQueue — professional cancelled '{}' (was {})", clientName, status);

        return buildAttendanceInQueueResponse(saved);
    }

    @Override
    public AttendanceInProgressResponse resumeAttendance(com.ospaulos.vupth.domain.attendance.model.Attendance attendance) {
        log.info("[Attendance] resumeAttendance — attendanceId={}", attendance.getId());

        attendance.resumeTimer();
        attendance.setStatus(AttendanceStatus.IN_PROGRESS);
        com.ospaulos.vupth.domain.attendance.model.Attendance saved = attendanceRepository.save(attendance);

        String clientName = attendance.getClient() != null
                ? attendance.getClient().getFirstName()
                : (attendance.getGuestName() != null ? attendance.getGuestName() : "anonymous");
        log.info("[Attendance] resumeAttendance — cliente='{}' resumed. Total paused={}min",
                clientName, saved.getPausedDurationInMinutes());

        return buildAttendanceInProgressResponse(saved);
    }

    // ─────────────────────────────────────────────────────────────
    // QUERY
    // ─────────────────────────────────────────────────────────────

    @Override
    public AttendanceInQueueResponse getNextAttendance(Professional professional, Establishment establishment) {
        log.debug("[Attendance] getNextAttendance — professional={} establishment={}",
                professional.getId(), establishment.getId());

        List<com.ospaulos.vupth.domain.attendance.model.Attendance> queue = professional.getScheduledAttendances();

        if (queue.isEmpty()) {
            log.info("[Attendance] getNextAttendance — empty queue for professional='{}'", professional.getName());
            return null;
        }

        com.ospaulos.vupth.domain.attendance.model.Attendance next = queue.getFirst();
        String nextClientName = next.getClient() != null
                ? next.getClient().getFirstName()
                : (next.getGuestName() != null ? next.getGuestName() : "anonymous");
        log.info("[Attendance] getNextAttendance — next client='{}' agendado={}",
                nextClientName, next.getScheduledTime());

        return buildAttendanceInQueueResponse(next);
    }

    // ─────────────────────────────────────────────────────────────
    // PAUSE
    // ─────────────────────────────────────────────────────────────

    @Override
    public AttendancePausedResponse pauseAttendance(com.ospaulos.vupth.domain.attendance.model.Attendance attendance) {
        log.info("[Attendance] pauseAttendance — attendanceId={}", attendance.getId());

        attendance.pauseTimer();
        com.ospaulos.vupth.domain.attendance.model.Attendance saved = attendanceRepository.save(attendance);

        String clientName = attendance.getClient() != null
                ? attendance.getClient().getFirstName()
                : (attendance.getGuestName() != null ? attendance.getGuestName() : "anonymous");
        log.info("[Attendance] pauseAttendance — cliente='{}' paused. Elapsed time={}min",
                clientName, saved.getServiceDurationInMinutes());

        return buildPausedAttendance(saved);
    }

    // ─────────────────────────────────────────────────────────────
    // STATS
    // ─────────────────────────────────────────────────────────────

    @Scheduled(cron = "0 0 3 * * MON")
    @Override
    public double calculateWeeklyAverageDuration(Professional professional, LocalDate weekStart, LocalDate weekEnd) {
        log.debug("[Attendance] calculateWeeklyAverageDuration — professional={} period={}/{}",
                professional.getId(), weekStart, weekEnd);

        List<com.ospaulos.vupth.domain.attendance.model.Attendance> attendances = attendanceRepository
                .findByProfessionalAndStatusAndScheduledTimeBetween(
                        professional, AttendanceStatus.FINISHED,
                        weekStart.atStartOfDay(), weekEnd.atTime(23, 59, 59));

        double avg = attendances.stream()
                .filter(a -> a.getDurationInMinutes() != null)
                .mapToInt(com.ospaulos.vupth.domain.attendance.model.Attendance::getDurationInMinutes)
                .average()
                .orElse(0.0);

        log.info("[Attendance] calculateWeeklyAverageDuration — professional='{}' avg={}min ({} attendances)",
                professional.getName(), avg, attendances.size());
        return avg;
    }

    // ─────────────────────────────────────────────────────────────
    // BUILDERS
    // ─────────────────────────────────────────────────────────────

    @Override
    public Object buildAttendanceResponse(com.ospaulos.vupth.domain.attendance.model.Attendance attendance) {
        if (attendance.getStatus() == AttendanceStatus.IN_PROGRESS ||
                attendance.getStatus() == AttendanceStatus.PAUSED) {
            return buildAttendanceInProgressResponse(attendance);
        }
        return buildAttendanceInQueueResponse(attendance);
    }

    @Override
    public AttendanceInProgressResponse buildAttendanceInProgressResponse(
            com.ospaulos.vupth.domain.attendance.model.Attendance attendance) {

        if (attendance.getStartTime() == null && attendance.getStatus() != AttendanceStatus.IN_PROGRESS) {
            throw new AttendanceInvalidStatusException(
                    "Cannot build progress response: attendance has no start time");
        }

        OffsetDateTime startTime = attendance.getStartTime();
        OffsetDateTime now = OffsetDateTime.now();

        int totalElapsedMinutes = (int) Duration.between(startTime, now).toMinutes();
        int estimatedMinutes = attendance.getEstimatedTotalMinutes();
        int pausedMinutes = attendance.getPausedDurationInMinutes() != null
                ? attendance.getPausedDurationInMinutes() : 0;
        int elapsedOnlyServiceMinutes = Math.max(0, totalElapsedMinutes - pausedMinutes);
        int remainingMinutes = Math.max(0, estimatedMinutes - elapsedOnlyServiceMinutes);

        List<AttendanceInQueueResponse.ServiceItemSummary> services = attendance.getServiceItems()
                .stream()
                .map(AttendanceInQueueResponse.ServiceItemSummary::fromEntity)
                .collect(Collectors.toList());

        boolean isPaused = attendance.getStatus() == AttendanceStatus.PAUSED;

        // NPE fix: client pode ser null (guest)
        UUID clientId = attendance.getClient() != null ? attendance.getClient().getId() : null;
        String clientName = attendance.getClient() != null
                ? attendance.getClient().getFirstName()
                : (attendance.getGuestName() != null ? attendance.getGuestName() : "anonymous");

        return new AttendanceInProgressResponse(
                attendance.getId(),
                attendance.getEstablishment().getId(),
                attendance.getEstablishment().getName(),
                attendance.getProfessional().getId(),
                attendance.getProfessional().getName(),
                clientId,
                clientName,
                services,
                attendance.getScheduledTime(),
                attendance.getStatus(),
                attendance.getTotalValue(),
                isPaused,
                startTime,
                elapsedOnlyServiceMinutes,
                pausedMinutes,
                totalElapsedMinutes,
                remainingMinutes);
    }

    @Override
    public AttendanceInProgressResponse buildFinishedAttendanceResponse(Attendance attendance) {

        OffsetDateTime startTime = attendance.getStartTime() != null
                ? attendance.getStartTime()
                : attendance.getScheduledTime();

        OffsetDateTime endTime = attendance.getEndTime() != null
                ? attendance.getEndTime()
                : OffsetDateTime.now();

        int totalElapsedMinutes = (int) Duration.between(startTime, endTime).toMinutes();
        int pausedMinutes = attendance.getPausedDurationInMinutes() != null
                ? attendance.getPausedDurationInMinutes() : 0;
        int elapsedOnlyServiceMinutes = Math.max(0, totalElapsedMinutes - pausedMinutes);
        int estimatedMinutes = attendance.getEstimatedTotalMinutes();
        int remainingMinutes = 0; // finished — no time remaining

        List<AttendanceInQueueResponse.ServiceItemSummary> services = attendance.getServiceItems()
                .stream()
                .map(AttendanceInQueueResponse.ServiceItemSummary::fromEntity)
                .collect(Collectors.toList());

        UUID clientId = attendance.getClient() != null ? attendance.getClient().getId() : null;
        String clientName = attendance.getClient() != null
                ? attendance.getClient().getFirstName()
                : (attendance.getGuestName() != null ? attendance.getGuestName() : "anonymous");

        return new AttendanceInProgressResponse(
                attendance.getId(),
                attendance.getEstablishment().getId(),
                attendance.getEstablishment().getName(),
                attendance.getProfessional().getId(),
                attendance.getProfessional().getName(),
                clientId,
                clientName,
                services,
                attendance.getScheduledTime(),
                attendance.getStatus(),   // FINISHED
                attendance.getTotalValue(),
                false,                    // not paused
                startTime,
                elapsedOnlyServiceMinutes,
                pausedMinutes,
                totalElapsedMinutes,
                remainingMinutes);
    }

    @Override
    public AttendancePausedResponse buildPausedAttendance(
            com.ospaulos.vupth.domain.attendance.model.Attendance attendance) {

        if (attendance == null) {
            throw new AttendanceNotFoundException(null);
        }
        if (attendance.getStatus() != AttendanceStatus.PAUSED) {
            throw new AttendanceInvalidStatusException(attendance.getStatus(), AttendanceStatus.PAUSED);
        }

        UUID clientId = attendance.getClient() != null ? attendance.getClient().getId() : null;
        String clientName = attendance.getClient() != null
                ? attendance.getClient().getFirstName()
                : (attendance.getGuestName() != null ? attendance.getGuestName() : "anonymous");

        OffsetDateTime startTime = attendance.getStartTime();
        OffsetDateTime now = OffsetDateTime.now();

        int totalElapsedMinutes = (int) Duration.between(startTime, now).toMinutes();
        int pausedMinutes = attendance.getPausedDurationInMinutes() != null
                ? attendance.getPausedDurationInMinutes() : 0;
        int elapsedOnlyServiceMinutes = Math.max(0, totalElapsedMinutes - pausedMinutes);
        int estimatedMinutes = attendance.getEstimatedTotalMinutes();
        int remainingMinutes = Math.max(0, estimatedMinutes - elapsedOnlyServiceMinutes);

        List<AttendancePausedResponse.ServiceItemSummary> services = attendance.getServiceItems()
                .stream()
                .map(AttendancePausedResponse.ServiceItemSummary::fromEntity)
                .toList();

        return new AttendancePausedResponse(
                attendance.getId(),
                clientId,
                clientName,
                attendance.getStatus(),
                attendance.getStartTime(),
                attendance.getLastPauseTime(),
                elapsedOnlyServiceMinutes,
                pausedMinutes,
                remainingMinutes,
                attendance.getTotalValue(),
                services);
    }

    @Override
    public AttendanceInQueueResponse buildAttendanceInQueueResponse(
            com.ospaulos.vupth.domain.attendance.model.Attendance attendance) {
        return AttendanceInQueueResponse.fromEntity(attendance);
    }

    // ─────────────────────────────────────────────────────────────
    // LIST
    // ─────────────────────────────────────────────────────────────

    @Override
    public List<AttendanceListResponse> listAttendances(
            UUID userId,
            UUID establishmentId,
            UUID professionalId,
            LocalDate dateFrom,
            LocalDate dateTo,
            AttendanceStatus status) {

        log.info("[Attendance] listAttendances — userId={} establishmentId={} professionalId={} dateFrom={} dateTo={} status={}",
                userId, establishmentId, professionalId, dateFrom, dateTo, status);

        Professional professional = professionalRepository.findByIdWithBaseUser(userId).orElse(null);
        final UUID resolvedEstablishmentId = resolveEstablishmentId(userId, establishmentId, professionalId, professional);

        OffsetDateTime from = dateFrom != null ? dateFrom.atStartOfDay().atOffset(java.time.ZoneOffset.UTC) : null;
        OffsetDateTime to = dateTo != null ? dateTo.plusDays(1).atStartOfDay().atOffset(java.time.ZoneOffset.UTC) : null;

        String statusStr = status != null ? status.name() : null;
        List<Attendance> attendances = attendanceRepository.findByFilters(
                resolvedEstablishmentId, professionalId, statusStr, from, to);

        attendances.forEach(this::initializeLazyAssociations);

        return attendances.stream()
                .map(AttendanceListResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<AttendanceListResponse> listClientAttendances(
            UUID clientId,
            AttendanceStatus status,
            LocalDate dateFrom,
            LocalDate dateTo) {

        log.info("[Attendance] listClientAttendances — clientId={} status={} dateFrom={} dateTo={}",
                clientId, status, dateFrom, dateTo);

        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ClientNotFoundException(clientId));

        OffsetDateTime from = dateFrom != null
                ? dateFrom.atStartOfDay().atOffset(java.time.ZoneOffset.UTC)
                : OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, java.time.ZoneOffset.UTC);
        OffsetDateTime to = dateTo != null
                ? dateTo.plusDays(1).atStartOfDay().atOffset(java.time.ZoneOffset.UTC)
                : OffsetDateTime.of(2100, 1, 1, 0, 0, 0, 0, java.time.ZoneOffset.UTC);

        List<Attendance> attendances = attendanceRepository.findByClientFilters(
                clientId, status, from, to);

        return attendances.stream()
                .map(AttendanceListResponse::fromEntity)
                .collect(Collectors.toList());
    }

    private UUID resolveEstablishmentId(UUID userId, UUID establishmentId, UUID professionalId, Professional professional) {
        if (establishmentId != null) {
            Establishment establishment = establishmentRepository.findById(establishmentId)
                    .orElseThrow(() -> new EstablishmentNotFoundException(establishmentId));

            boolean isOwner = establishment.getOwner() != null
                    && establishment.getOwner().getId().equals(userId);
            boolean isProfessionalOfEstablishment = professional != null
                    && professional.getEstablishment() != null
                    && professional.getEstablishment().getId().equals(establishmentId);

            if (!isOwner && !isProfessionalOfEstablishment) {
                throw new EstablishmentAccessDeniedException("You can only list attendances from your own establishment.");
            }
            return establishmentId;
        }

        if (professionalId != null) {
            if (professional == null || !professional.getId().equals(professionalId)) {
                throw new EstablishmentAccessDeniedException("You can only list your own attendances.");
            }
            return null;
        }

        if (professional == null || professional.getEstablishment() == null) {
            throw new EstablishmentAccessDeniedException("You must specify an establishmentId or professionalId.");
        }
        return professional.getEstablishment().getId();
    }

    private void initializeLazyAssociations(Attendance attendance) {
        Hibernate.initialize(attendance.getEstablishment());
        Hibernate.initialize(attendance.getProfessional());
        Hibernate.initialize(attendance.getProfessional().getBaseUser());
        Hibernate.initialize(attendance.getServiceItems());
        if (attendance.getClient() != null) {
            Hibernate.initialize(attendance.getClient().getBaseUser());
        }
    }
}
