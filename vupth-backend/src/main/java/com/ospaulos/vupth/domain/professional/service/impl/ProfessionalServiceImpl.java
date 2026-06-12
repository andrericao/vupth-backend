package com.ospaulos.vupth.domain.professional.service.impl;

import com.ospaulos.vupth.api.attendance.dto.request.AttendanceGuestRequest;
import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInProgressResponse;
import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInQueueResponse;
import com.ospaulos.vupth.api.queue.dto.response.QueueResponse;
import com.ospaulos.vupth.api.serviceItem.dto.request.ServiceItemRequest;
import com.ospaulos.vupth.api.serviceItem.dto.response.ServiceItemResponse;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceAlreadyInProgressException;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceInvalidStatusException;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceNotFoundException;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.attendance.service.AttendanceService;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotFoundException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.service.EstablishmentService;
import com.ospaulos.vupth.domain.professional.exception.NotAProfessionalException;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotFoundException;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.professional.service.ProfessionalService;
import com.ospaulos.vupth.domain.queue.service.QueueService;
import com.ospaulos.vupth.domain.serviceItem.exception.ServiceItemAlreadyExistsException;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import com.ospaulos.vupth.domain.serviceItem.service.ServiceItemService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@AllArgsConstructor
@org.springframework.transaction.annotation.Transactional
public class ProfessionalServiceImpl implements ProfessionalService {

    private final QueueService queueService;
    private final AttendanceService attendanceService;
    private final ServiceItemService serviceItemService;
    private final EstablishmentService establishmentService;
    private final ProfessionalRepository professionalRepository;
    private final AttendanceRepository attendanceRepository;
    private final EstablishmentRepository establishmentRepository;

    // ─────────────────────────────────────────────────────────────
    // GUEST
    // ─────────────────────────────────────────────────────────────

    @Override
    public QueueResponse createAndAddGuestAttendanceInQueue(AttendanceGuestRequest request) {
        log.info("[Professional] createAndAddGuestAttendanceInQueue — professional={} establishment={}",
                request.professionalId(), request.establishmentId());

        Professional professional = professionalRepository.findByIdWithBaseUser(request.professionalId())
                .orElseThrow(() -> new ProfessionalNotFoundException(request.professionalId()));

        Establishment establishment = establishmentRepository.findById(request.establishmentId())
                .orElseThrow(() -> new EstablishmentNotFoundException(request.establishmentId()));

        // Bug fix: was != (reference) — agora .equals()
        if (!establishment.getId().equals(request.establishmentId())) {
            log.warn("[Professional] createAndAddGuestAttendanceInQueue — professional={} does not belong to establishment={}",
                    request.professionalId(), request.establishmentId());
            throw new EstablishmentAccessDeniedException(request.professionalId(), request.establishmentId());
        }

        Attendance attendance = attendanceService.createGuestAttendance(request, professional, establishment);
        log.info("[Professional] createAndAddGuestAttendanceInQueue — guest added attendanceId={}", attendance.getId());

        return queueService.enqueueGuestAttendance(attendance, professional);
    }

    // ─────────────────────────────────────────────────────────────
    // QUEUE QUERY
    // ─────────────────────────────────────────────────────────────

    @Override
    public QueueResponse getProfessionalQueue(UUID professionalId, UUID establishmentId) {
        log.info("[Professional] getProfessionalQueue — professional={} establishment={}",
                professionalId, establishmentId);

        Professional professional = professionalRepository.findByIdWithBaseUser(professionalId)
                .orElseThrow(() -> new ProfessionalNotFoundException(professionalId));

        Establishment establishment = professional.getEstablishment();

        if (!establishment.getId().equals(establishmentId)) {
            log.warn("[Professional] getProfessionalQueue — professional={} does not belong to establishment={}",
                    professionalId, establishmentId);
            throw new EstablishmentAccessDeniedException(professionalId, establishmentId);
        }

        return queueService.getQueueOverview(professional, establishment);
    }

    // ─────────────────────────────────────────────────────────────
    // ATTENDANCE LIFECYCLE
    // ─────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public QueueResponse startAttendance(UUID professionalId, UUID attendanceId) {
        log.info("[Professional] startAttendance — professional={} attendance={}",
                professionalId, attendanceId);

        Attendance attendance = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(attendanceId));

        Establishment establishment = attendance.getEstablishment();
        Professional professional = professionalRepository.findByIdWithBaseUser(professionalId)
                .orElseThrow(() -> new ProfessionalNotFoundException(professionalId));

        assertProfessionalOwnsAttendance(professional, attendance);

        if (attendance.getStatus() != AttendanceStatus.SCHEDULED) {
            throw new AttendanceInvalidStatusException(attendance.getStatus(), AttendanceStatus.SCHEDULED);
        }

        if (professional.getCurrentAttendance().isPresent()) {
            log.warn("[Professional] startAttendance — professional={} already has attendance IN_PROGRESS", professionalId);
            throw new AttendanceAlreadyInProgressException(professionalId);
        }

        attendanceService.startAttendance(attendance, professional);
        queueService.updateQueueEstimates(professional);

        log.info("[Professional] startAttendance — attendance={} iniciado", attendanceId);
        return queueService.getQueueOverview(professional, establishment);
    }

    @Override
    public QueueResponse finishAttendance(UUID professionalId, UUID attendanceId) {
        log.info("[Professional] finishAttendance — professional={} attendance={}",
                professionalId, attendanceId);

        Attendance attendance = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(attendanceId));

        Establishment establishment = attendance.getEstablishment();
        Professional professional = professionalRepository.findByIdWithBaseUser(professionalId)
                .orElseThrow(() -> new ProfessionalNotFoundException(professionalId));

        assertProfessionalOwnsAttendance(professional, attendance);

        if (attendance.getStatus() != AttendanceStatus.IN_PROGRESS) {
            throw new AttendanceInvalidStatusException(attendance.getStatus(), AttendanceStatus.IN_PROGRESS);
        }

        attendanceService.finishAttendance(attendance);
        log.info("[Professional] finishAttendance — attendance={} finalizado (FINISHED)", attendanceId);

        // Reload after save to reflect FINISHED status and computed duration
        Attendance finished = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(attendanceId));

        // Build snapshot with FINISHED status for inAttendance field
        AttendanceInProgressResponse finishedSnapshot = attendanceService.buildFinishedAttendanceResponse(finished);

        QueueResponse nextQueue = queueService.getQueueOverview(professional, establishment);

        return new QueueResponse(
                nextQueue.professionalId(),
                nextQueue.professionalName(),
                nextQueue.establishmentId(),
                nextQueue.establishmentName(),
                nextQueue.size(),
                nextQueue.attendancesInQueue(),
                nextQueue.totalEstimatedMinutes(),
                nextQueue.totalElapsedMinutes(),
                nextQueue.totalRemainingMinutes(),
                finishedSnapshot
        );
    }

    @Override
    public QueueResponse cancelAttendance(UUID professionalId, UUID attendanceId) {
        log.info("[Professional] cancelAttendance — professional={} attendance={}",
                professionalId, attendanceId);

        Attendance attendance = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(attendanceId));

        Establishment establishment = attendance.getEstablishment();
        Professional professional = professionalRepository.findByIdWithBaseUser(professionalId)
                .orElseThrow(() -> new ProfessionalNotFoundException(professionalId));

        assertProfessionalOwnsAttendance(professional, attendance);

        if (attendance.getStatus() != AttendanceStatus.SCHEDULED) {
            throw new AttendanceInvalidStatusException(attendance.getStatus(), AttendanceStatus.SCHEDULED);
        }

        attendanceService.cancelAttendance(attendance);
        log.info("[Professional] cancelAttendance — attendance={} cancelado", attendanceId);

        return queueService.getQueueOverview(professional, establishment);
    }

    @Override
    public QueueResponse pauseAttendance(UUID attendanceId, UUID professionalId) {
        log.info("[Professional] pauseAttendance — professional={} attendance={}",
                professionalId, attendanceId);

        Attendance attendance = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(attendanceId));

        Establishment establishment = attendance.getEstablishment();
        Professional professional = professionalRepository.findByIdWithBaseUser(professionalId)
                .orElseThrow(() -> new ProfessionalNotFoundException(professionalId));

        assertProfessionalOwnsAttendance(professional, attendance);

        // Bug fix: era SCHEDULED — deve ser IN_PROGRESS para pausar
        if (attendance.getStatus() != AttendanceStatus.IN_PROGRESS) {
            throw new AttendanceInvalidStatusException(attendance.getStatus(), AttendanceStatus.IN_PROGRESS);
        }

        attendanceService.pauseAttendance(attendance);
        log.info("[Professional] pauseAttendance — attendance={} pausado", attendanceId);

        return queueService.getQueueOverview(professional, establishment);
    }

    @Override
    public QueueResponse resumeAttendance(UUID attendanceId, UUID professionalId) {
        log.info("[Professional] resumeAttendance — professional={} attendance={}",
                professionalId, attendanceId);

        Attendance attendance = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(attendanceId));

        Establishment establishment = attendance.getEstablishment();
        Professional professional = professionalRepository.findByIdWithBaseUser(professionalId)
                .orElseThrow(() -> new ProfessionalNotFoundException(professionalId));

        assertProfessionalOwnsAttendance(professional, attendance);

        // Bug fix: era SCHEDULED — deve ser PAUSED para retomar
        if (attendance.getStatus() != AttendanceStatus.PAUSED) {
            throw new AttendanceInvalidStatusException(attendance.getStatus(), AttendanceStatus.PAUSED);
        }

        attendanceService.resumeAttendance(attendance);
        log.info("[Professional] resumeAttendance — attendance={} retomado", attendanceId);

        return queueService.getQueueOverview(professional, establishment);
    }

    // ─────────────────────────────────────────────────────────────
    // SERVICE ITEMS
    // ─────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public ServiceItemResponse createServiceItem(UUID professionalId, ServiceItemRequest request) {
        log.info("[Professional] createServiceItem — professional={} type={} name={}",
                professionalId, request.serviceItemType(), request.customName());

        // Bug fix: era request.establishmentId() — agora usa professionalId correto
        Professional professional = professionalRepository.findByIdWithBaseUser(professionalId)
                .orElseThrow(() -> new ProfessionalNotFoundException(professionalId));

        Establishment establishment = professional.getEstablishment();

        if (!establishment.getProfessionals().contains(professional)) {
            throw new EstablishmentAccessDeniedException(professionalId, establishment.getId());
        }

        List<ServiceItem> services = establishmentService.getServicesItemByEstablishment(establishment.getId());

        boolean nameExists = services.stream()
                .anyMatch(s -> s.getCustomName().equalsIgnoreCase(request.customName()));
        if (nameExists) {
            throw new ServiceItemAlreadyExistsException(request.customName());
        }

        return serviceItemService.createServiceItem(professional, request);
    }

    @Override
    @Transactional
    public ServiceItemResponse createServiceItemForEstablishment(UUID ownerId, UUID establishmentId, ServiceItemRequest request) {
        log.info("[Professional] createServiceItemForEstablishment — owner={} establishment={} type={} name={}",
                ownerId, establishmentId, request.serviceItemType(), request.customName());

        Establishment establishment = establishmentRepository.findById(establishmentId)
                .orElseThrow(() -> new EstablishmentNotFoundException(establishmentId));

        if (establishment.getOwner() == null || !establishment.getOwner().getId().equals(ownerId)) {
            throw new EstablishmentAccessDeniedException("User " + ownerId + " is not the owner of establishment " + establishmentId);
        }

        Professional professional = professionalRepository.findByIdWithBaseUser(ownerId)
                .orElseThrow(() -> new ProfessionalNotFoundException(ownerId));

        List<ServiceItem> services = establishmentService.getServicesItemByEstablishment(establishmentId);
        boolean nameExists = services.stream()
                .anyMatch(s -> s.getCustomName().equalsIgnoreCase(request.customName()));
        if (nameExists) {
            throw new ServiceItemAlreadyExistsException(request.customName());
        }

        return serviceItemService.createServiceItem(professional, request);
    }

    // ─────────────────────────────────────────────────────────────
    // GUARDS
    // ─────────────────────────────────────────────────────────────

    /**
     * Verifica que:
     * 1. O professional carregado pelo requestedProfessionalId é o dono do attendance.
     * 2. O professional possui a role PROFESSIONAL ativa.
     *
     * Como o professional já foi carregado via findByIdWithBaseUser(requestedProfessionalId),
     * a guarda de ID verifica que o attendance.professional.id == requestedProfessionalId.
     */
    private void assertProfessionalOwnsAttendance(Professional professional, Attendance attendance) {
        // Verify that the professional is the one who owns this attendance
        if (!professional.getId().equals(attendance.getProfessional().getId())) {
            log.warn("[Professional] assertProfessionalOwnsAttendance — professional={} does not own attendance={}",
                    professional.getId(), attendance.getId());
            throw new EstablishmentAccessDeniedException(
                    String.format("Professional '%s' does not own attendance '%s'",
                            professional.getId(), attendance.getId()));
        }
        
        // Also verify that the professional has the PROFESSIONAL role
        if (!professional.getBaseUser().isProfessional()) {
            log.warn("[Professional] assertProfessionalOwnsAttendance — user={} does not have PROFESSIONAL role",
                    professional.getBaseUser().getId());
            throw new NotAProfessionalException(professional.getBaseUser().getId());
        }
    }
}
