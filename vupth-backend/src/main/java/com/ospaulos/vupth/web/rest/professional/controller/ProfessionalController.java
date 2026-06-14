package com.ospaulos.vupth.web.rest.professional.controller;

import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceGuestRequest;
import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInQueueResponse;
import com.ospaulos.vupth.api.queue.dto.response.QueueResponse;
import com.ospaulos.vupth.api.serviceItem.dto.request.ServiceItemRequest;
import com.ospaulos.vupth.api.serviceItem.dto.request.UpdateServiceItemRequest;
import com.ospaulos.vupth.api.serviceItem.dto.response.ServiceItemResponse;
import com.ospaulos.vupth.domain.attendance.service.AttendanceService;
import com.ospaulos.vupth.domain.professional.service.ProfessionalService;
import com.ospaulos.vupth.domain.serviceItem.service.ServiceItemService;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/professional-queue")
@AllArgsConstructor
public class ProfessionalController {

    private final AttendanceService attendanceService;
    private final ProfessionalService professionalService;
    private final ServiceItemService serviceItemService;

    // TODO: accept ID as parameter to support caller-specific routing

    @PostMapping("add-attendance")
    public ResponseEntity<AttendanceInQueueResponse> addAttendanceInQueue(
            @AuthenticationPrincipal UserDetails professional,
            @RequestBody @Valid AttendanceRequest request) {

        log.info("PROFESSIONAL CONTROLLER ADD CLIENT AT QUEUE");

        AttendanceInQueueResponse response = attendanceService.createAttendance(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("add-quest-attendance")
    public ResponseEntity<QueueResponse> addGuestAttendanceInQueue(@RequestBody @Valid AttendanceGuestRequest request){

        QueueResponse response = professionalService.createAndAddGuestAttendanceInQueue(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * DELETE /professional-queue/{attendanceId}/remove
     *
     * Profissional remove qualquer attendance da fila (status → CANCELED).
     * Cobre: guest que não apareceu, cliente que pediu verbalmente para sair, etc.
     * Requer: Bearer token com ROLE_PROFESSIONAL.
     */
    @DeleteMapping("/{attendanceId}/remove")
    public ResponseEntity<AttendanceInQueueResponse> removeAttendanceFromQueue(
            @PathVariable UUID attendanceId,
            @AuthenticationPrincipal com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl principal) {

        log.info("PROFESSIONAL CONTROLLER REMOVE ATTENDANCE {} from queue by professional={}",
                attendanceId, principal.getUserId());

        AttendanceInQueueResponse response = attendanceService.removeFromQueue(attendanceId, principal.getUserId());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{professionalId}/add-service-items")
    public ResponseEntity<ServiceItemResponse> addServiceItem(
            @PathVariable UUID professionalId,
            @RequestBody @Valid ServiceItemRequest request) {

        log.info("CONTROLLER CREATE USER SERVICE-ITEM");

        ServiceItemResponse response = professionalService.createServiceItem(professionalId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * PATCH /professional-queue/{professionalId}/service-items/{itemId}
     *
     * Profissional edita um servico seu (nome, preco e/ou duracao estimada).
     * So o proprio profissional (JWT) pode editar seus servicos.
     * Requer: ROLE_PROFESSIONAL.
     */
    @PatchMapping("/{professionalId}/service-items/{itemId}")
    public ResponseEntity<ServiceItemResponse> updateServiceItem(
            @PathVariable UUID professionalId,
            @PathVariable UUID itemId,
            @RequestBody @Valid UpdateServiceItemRequest request,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("PROFESSIONAL CONTROLLER UPDATE SERVICE-ITEM {} professional={} by user={}",
                itemId, professionalId, principal.getUserId());

        ServiceItemResponse response = serviceItemService.updateServiceItem(
                principal.getUserId(), professionalId, itemId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * DELETE /professional-queue/{professionalId}/service-items/{itemId}
     *
     * Profissional remove um servico seu.
     * So o proprio profissional (JWT) pode remover seus servicos.
     * Requer: ROLE_PROFESSIONAL.
     */
    @DeleteMapping("/{professionalId}/service-items/{itemId}")
    public ResponseEntity<Void> deleteServiceItem(
            @PathVariable UUID professionalId,
            @PathVariable UUID itemId,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("PROFESSIONAL CONTROLLER DELETE SERVICE-ITEM {} professional={} by user={}",
                itemId, professionalId, principal.getUserId());

        serviceItemService.deleteServiceItem(principal.getUserId(), professionalId, itemId);
        return ResponseEntity.noContent().build();
    }

    /*
    TODO: Create method to add client with GUEST status
    TODO: Queue view for any client
    TODO: Full queue view
    TODO: Client's current attendance view
     */
}
