package com.ospaulos.vupth.web.rest.client.controller;

import com.ospaulos.vupth.api.client.dto.response.ClientInQueueResponse;
import com.ospaulos.vupth.api.queue.dto.response.QueueResponse;
import com.ospaulos.vupth.domain.client.service.ClientService;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/client-queue")
@RequiredArgsConstructor
public class ClientQueueController {

    /*
    TODO: Create client methods -> CLIENT should see their wait time
     Client should see the professional's queue size
     Two GET endpoints
    */

    private final ClientService clientService;

    @GetMapping("/{professionalId}/queue")
    public ResponseEntity<QueueResponse> getProfessionalQueue(@PathVariable UUID professionalId, @RequestParam UUID establishmentId){

        final QueueResponse queue = clientService.getCompleteQueueInfo(professionalId, establishmentId);

        return ResponseEntity.status(HttpStatus.OK).body(queue);
    }

    @GetMapping("/{attendanceId}/wait-time")
    public ResponseEntity<ClientInQueueResponse> getClienInQueuetWaitTime(
            @PathVariable UUID attendanceId,
            @RequestParam UUID clientId) {

        // Note: service signature is (clientId, attendanceId)
        final ClientInQueueResponse clientResponse = clientService.getClientInQueueWaitingTime(clientId, attendanceId);

        return ResponseEntity.status(HttpStatus.OK).body(clientResponse);
    }
}