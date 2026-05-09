package com.ospaulos.vupth.domain.client.service.impl;

import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInProgressResponse;
import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInQueueResponse;
import com.ospaulos.vupth.api.client.dto.response.ClientInProgressResponse;
import com.ospaulos.vupth.api.client.dto.response.ClientInQueueResponse;
import com.ospaulos.vupth.api.queue.dto.response.QueueResponse;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.client.model.Client;
import com.ospaulos.vupth.domain.client.repository.ClientRepository;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.attendance.service.AttendanceService;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceInvalidStatusException;
import com.ospaulos.vupth.domain.client.service.ClientService;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException;
import com.ospaulos.vupth.domain.queue.service.QueueService;
import jakarta.persistence.EntityNotFoundException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@AllArgsConstructor
@org.springframework.transaction.annotation.Transactional
public class ClientServiceImpl implements ClientService {

    private final QueueService queueService;
    private final AttendanceService attendanceService;

    private final AttendanceRepository attendanceRepository;
    private final EstablishmentRepository establishmentRepository;
    private final ClientRepository clientRepository;
    private final ProfessionalRepository professionalRepository;

    @Override
    public ClientInQueueResponse createAndAddtAttendanceInQueue(AttendanceRequest request){

        Professional professional = professionalRepository.findById(request.professionalID())
                .orElseThrow(() -> new EntityNotFoundException("Professional not found"));

        Establishment establishment = establishmentRepository.findById(request.establishmentId())
                .orElseThrow(() -> new EntityNotFoundException("Establishment not found"));

        Client client = clientRepository.findById(request.clientId())
                .orElseThrow(() -> new EntityNotFoundException("Client not found"));

        Attendance attendance = attendanceService.createAttendance(
                request, professional, establishment, client);

        return queueService.enqueueAttendance(attendance, professional);
    }

    @Override
    public QueueResponse getCompleteQueueInfo(UUID professionalId, UUID establishmentId) {

        log.info("CLIENT CONTROLLER GET QUEUE INFO");

        Professional professional = professionalRepository.findById(professionalId)
                .orElseThrow(() -> new EntityNotFoundException("Professional not found"));

        Establishment establishment = professional.getEstablishment();

        return queueService.getQueueOverview(professional, establishment);
    }

    @Override
    public ClientInQueueResponse getClientInQueueWaitingTime(UUID clientId, UUID attendanceId){

        log.info("CLIENT CONTROLLER GET WAITING TIME IN QUEUE");

        Attendance attendance = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new EntityNotFoundException("Attendance not found"));

        if (!attendance.getClient().getId().equals(clientId)) {
            throw new EstablishmentAccessDeniedException("Client can only check their own attendance");
        }

        if (attendance.getStatus() == AttendanceStatus.FINISHED) {
            throw new AttendanceInvalidStatusException("Attendance is already completed");
        }

        if (attendance.getStatus() == AttendanceStatus.CANCELLED) {
            throw new AttendanceInvalidStatusException("Attendance is canceled");
        }

        return queueService.queueWaitingTime(attendance);
    }

    @Override
    public ClientInProgressResponse getClientInProgressWaitingTime(UUID attendanceId, UUID clientId){

        Attendance attendance = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new EntityNotFoundException("Attendance not found"));

        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new EntityNotFoundException("Client not found"));

        if(!attendance.getClient().getId().equals(clientId)){
            throw new EstablishmentAccessDeniedException("Attendance or Client don't exists");
        }

        AttendanceInProgressResponse attendanceInProgressResponse =
                attendanceService.buildAttendanceInProgressResponse(attendance);

        return new ClientInProgressResponse(
                attendance.getId(),
                client.getId(),
                attendanceInProgressResponse
        );

    }

    @Override
    public AttendanceInQueueResponse cancelAttendance(UUID attendanceId, UUID clientId){

        Attendance attendance = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new EntityNotFoundException("Attendance not found"));

        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new EntityNotFoundException("Client not found"));

        if(!attendance.getClient().getId().equals(clientId)){
            throw new EstablishmentAccessDeniedException("Attendance or Client don't exists");
        }

        return attendanceService.cancelAttendance(attendance);
    }

    // TODO: Trocar lugar na fila
}
