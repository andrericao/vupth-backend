package com.ospaulos.vupth.domain.attendance.service;

import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceGuestRequest;
import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInProgressResponse;
import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInQueueResponse;
import com.ospaulos.vupth.api.attendance.dto.response.AttendancePausedResponse;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.client.model.Client;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.professional.model.Professional;

import java.time.LocalDate;
import java.util.UUID;

public interface AttendanceService {

    public Attendance createAttendance(AttendanceRequest request,
                                       Professional professional,
                                       Establishment establishment,
                                       Client client);

    public Attendance createAttendance(AttendanceRequest request);

    public Attendance createGuestAttendance(AttendanceGuestRequest request, Professional professional, Establishment establishment);

    public void startAttendance(Attendance attendance, Professional professional);

    public void finishAttendance(Attendance attendance);

    public AttendanceInQueueResponse cancelAttendance(Attendance attendance);

    public AttendanceInQueueResponse cancelAttendance(UUID attendance, UUID clientId);

    /**
     * Client leaves the queue voluntarily (status → CANCELED).
     * Only the owner of the attendance (by clientId from JWT) can call this.
     */
    public AttendanceInQueueResponse leaveQueue(UUID attendanceId, UUID clientId);

    /**
     * Professional removes any attendance from the queue (status → CANCELED).
     * Used to remove guests or no-shows. No ownership check on the client side.
     */
    public AttendanceInQueueResponse removeFromQueue(UUID attendanceId, UUID professionalId);

    public AttendanceInQueueResponse getNextAttendance(Professional professional, Establishment establishment);

    public AttendancePausedResponse pauseAttendance(Attendance attendance);

    public AttendanceInProgressResponse resumeAttendance(Attendance attendance);

    public double calculateWeeklyAverageDuration(Professional professional, LocalDate weekStart, LocalDate weekEnd);

    public Object buildAttendanceResponse(Attendance attendance);

    public AttendanceInProgressResponse buildAttendanceInProgressResponse(Attendance attendance);

    /**
     * Builds a response for a FINISHED attendance, populating the status and duration fields.
     * Used by finishAttendance to return the completed attendance in the QueueResponse.inAttendance field.
     */
    public AttendanceInProgressResponse buildFinishedAttendanceResponse(Attendance attendance);

    public AttendancePausedResponse buildPausedAttendance(Attendance attendance);

    public AttendanceInQueueResponse buildAttendanceInQueueResponse(Attendance attendance);
}
