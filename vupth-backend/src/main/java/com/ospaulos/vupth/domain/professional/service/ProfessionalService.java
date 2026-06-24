package com.ospaulos.vupth.domain.professional.service;

import com.ospaulos.vupth.api.attendance.dto.request.AttendanceGuestRequest;
import com.ospaulos.vupth.api.serviceItem.dto.request.ServiceItemRequest;
import com.ospaulos.vupth.api.queue.dto.response.QueueResponse;
import com.ospaulos.vupth.api.serviceItem.dto.response.ServiceItemResponse;

import java.util.List;
import java.util.UUID;

public interface ProfessionalService {

    public QueueResponse createAndAddGuestAttendanceInQueue(AttendanceGuestRequest request);

    public QueueResponse getProfessionalQueue(UUID professionalId, UUID establishmentId);

    public QueueResponse startAttendance(UUID professionalId, UUID attendanceId);

    /**
     * Marca um cliente que nao chegou como LATE (item #19): ele cai uma posicao na fila
     * (fica atras do proximo) mas NAO sai. Pode ser chamado de novo para cair mais.
     */
    public QueueResponse markAttendanceLate(UUID professionalId, UUID attendanceId);

    /**
     * Desfaz o LATE (item #19): o cliente volta a SCHEDULED, mantendo a posicao atual
     * (a que ele caiu). Usado quando o cliente chega e ainda nao vai ser atendido na hora.
     */
    public QueueResponse undoAttendanceLate(UUID professionalId, UUID attendanceId);

    public QueueResponse finishAttendance(UUID professionalId, UUID attendanceId);

    public QueueResponse cancelAttendance(UUID professionalId, UUID attendanceId);

    public QueueResponse pauseAttendance(UUID attendanceId, UUID professionalId);

    public QueueResponse resumeAttendance(UUID professionalId, UUID attendanceId);

    //public Set<ServiceItem> getServicesItemByEstablishment();

    public ServiceItemResponse createServiceItem(UUID professionalId, ServiceItemRequest request);

    public ServiceItemResponse createServiceItemForEstablishment(UUID ownerId, UUID establishmentId, ServiceItemRequest request);
}
