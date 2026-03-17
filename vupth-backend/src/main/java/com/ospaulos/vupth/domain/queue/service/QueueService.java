package com.ospaulos.vupth.domain.queue.service;

import com.ospaulos.vupth.api.client.dto.response.ClientInQueueResponse;
import com.ospaulos.vupth.api.queue.dto.response.QueueResponse;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.professional.model.Professional;

public interface QueueService {

    /**
     * Returns an overview of a professional's queue in an establishment.
     */
    QueueResponse getQueueOverview(Professional professional, Establishment establishment);

    /**
     * Enqueues an already-created attendance into the professional's queue.
     * Returns the queue position and estimated wait time for the client.
     *
     * Usado por: ClientServiceImpl (fluxo de cliente autenticado)
     */
    ClientInQueueResponse enqueueAttendance(Attendance attendance, Professional professional);

    /**
     * Enfileira um attendance de guest e retorna o snapshot completo da fila.
     * Differs from enqueueAttendance in that it returns QueueResponse (establishment view),
     * not ClientInQueueResponse (client view).
     *
     * Used by: anonymous attendance flow (no login)
     */
    QueueResponse enqueueGuestAttendance(Attendance attendance, Professional professional);

    /**
     * Recalcula estimativas de tempo para todos os attendances na fila do profissional.
     */
    void updateQueueEstimates(Professional professional);

    /**
     * Calculates the wait time for a specific attendance already in the queue.
     */
    ClientInQueueResponse queueWaitingTime(Attendance attendance);
}
