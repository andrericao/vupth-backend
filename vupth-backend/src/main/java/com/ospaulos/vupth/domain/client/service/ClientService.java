package com.ospaulos.vupth.domain.client.service;

import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInQueueResponse;
import com.ospaulos.vupth.api.client.dto.response.ClientInProgressResponse;
import com.ospaulos.vupth.api.client.dto.response.ClientInQueueResponse;
import com.ospaulos.vupth.api.queue.dto.response.QueueResponse;

import java.util.UUID;

public interface ClientService {

    public ClientInQueueResponse createAndAddtAttendanceInQueue(AttendanceRequest request);

    public QueueResponse getCompleteQueueInfo(UUID professionalId, UUID establishmentId);

    public ClientInQueueResponse getClientInQueueWaitingTime(UUID clientId, UUID attendanceId);

    public ClientInProgressResponse getClientInProgressWaitingTime(UUID attendanceId, UUID clientId);

    public AttendanceInQueueResponse cancelAttendance(UUID attendanceId, UUID clientId);
}
