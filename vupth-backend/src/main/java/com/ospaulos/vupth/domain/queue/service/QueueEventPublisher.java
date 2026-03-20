package com.ospaulos.vupth.domain.queue.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueEventPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    public void publishQueueUpdate(UUID professionalId, Object queueData) {
        String destination = "/topic/queue/" + professionalId;
        log.info("[WebSocket] Publishing queue update to {}", destination);
        messagingTemplate.convertAndSend(destination, queueData);
    }

    public void publishEstablishmentUpdate(UUID establishmentId, Object data) {
        String destination = "/topic/establishment/" + establishmentId;
        log.info("[WebSocket] Publishing establishment update to {}", destination);
        messagingTemplate.convertAndSend(destination, data);
    }
}
