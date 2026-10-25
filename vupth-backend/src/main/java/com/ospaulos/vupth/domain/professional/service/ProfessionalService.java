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
     * O cliente nao compareceu (falta-do-cliente, DEC-83): o profissional do atendimento ou o dono da loja o tira da fila
     * ({@code REMOVED}, motivo "removido por atraso") com multa. So o primeiro da fila, depois da espera.
     */
    public QueueResponse markNoShow(UUID userId, UUID attendanceId);

    /** regra-do-atraso-na-fila-vazia (DEC-101): o NAO CHEGOU ja vale para este atendimento? Quanto falta, pelo relogio do servidor. */
    public com.ospaulos.vupth.api.attendance.dto.response.NoShowStatusResponse noShowStatus(UUID userId, UUID attendanceId);

    /**
     * Desfaz o LATE (item #19): o cliente volta a SCHEDULED, mantendo a posicao atual
     * (a que ele caiu). Usado quando o cliente chega e ainda nao vai ser atendido na hora.
     */
    public QueueResponse undoAttendanceLate(UUID professionalId, UUID attendanceId);

    /**
     * Encaixa um cliente no meio da fila (item #38): o atendimento e movido para logo apos
     * {@code aheadCount} clientes (vira o {@code aheadCount+1}-esimo). Os que estavam a frente
     * NAO sofrem impacto; os de tras absorvem o tempo do encaixe (D) de forma RAMPADA — entra em
     * fatias de {@code D/aheadCount} a cada cliente que sai da frente, sem solavanco na estimativa.
     */
    public QueueResponse fitInAttendance(UUID professionalId, UUID attendanceId, int aheadCount);

    /**
     * Desfaz o encaixe (item #38): o atendimento deixa de ser FIT (para de rampar e passa a
     * contribuir o tempo cheio), mantendo a posicao atual.
     */
    public QueueResponse undoFitInAttendance(UUID professionalId, UUID attendanceId);

    public QueueResponse finishAttendance(UUID professionalId, UUID attendanceId);

    public QueueResponse cancelAttendance(UUID professionalId, UUID attendanceId);

    public QueueResponse pauseAttendance(UUID attendanceId, UUID professionalId);

    public QueueResponse resumeAttendance(UUID professionalId, UUID attendanceId);

    //public Set<ServiceItem> getServicesItemByEstablishment();

    public ServiceItemResponse createServiceItem(UUID professionalId, ServiceItemRequest request);

    // S1: requester pode ser o OWNER ou um PROFESSIONAL afiliado a loja.
    public ServiceItemResponse createServiceItemForEstablishment(UUID requesterId, UUID establishmentId, ServiceItemRequest request);
}
