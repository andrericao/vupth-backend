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

    public QueueResponse finishAttendance(UUID professionalId, UUID attendanceId);

    public QueueResponse cancelAttendance(UUID professionalId, UUID attendanceId);

    public QueueResponse pauseAttendance(UUID attendanceId, UUID professionalId);

    public QueueResponse resumeAttendance(UUID professionalId, UUID attendanceId);

    //public Set<ServiceItem> getServicesItemByEstablishment();

    public ServiceItemResponse createServiceItem(UUID professionalId, ServiceItemRequest request);

    public ServiceItemResponse createServiceItemForEstablishment(UUID ownerId, UUID establishmentId, ServiceItemRequest request);
}
