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

    /**
     * Item #39 — escalada de no-show. Remove da fila (status CANCELLED) um cliente que ja
     * estava LATE (#19) e nao compareceu ao ser chamado de novo, registrando um strike
     * {@code NO_SHOW}. Exige que o atendimento esteja em LATE (a escalada pressupoe que ele
     * ja caiu de posicao ao menos uma vez). Convidados sao removidos mas nao acumulam strike.
     */
    public QueueResponse markAttendanceNoShow(UUID professionalId, UUID attendanceId);

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
